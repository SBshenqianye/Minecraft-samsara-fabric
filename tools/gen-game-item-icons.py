"""Render vanilla-like inventory icons for blocks, dragon egg and skulls.

Reads the Minecraft client jar (assets/.../items, models, blockstates and
textures) and writes 32x32 transparent PNGs.  Non-block items keep their
existing flat icon unless the item model has real 3D geometry.

Usage:
    python tools/gen-game-item-icons.py --jar .tmp/client-26.3-snapshot-9.jar
    python tools/gen-game-item-icons.py --jar ... --only stone,oak_stairs,dragon_egg
"""
from __future__ import annotations

import argparse
import io
import json
import math
import os
import zipfile

import numpy as np
from PIL import Image

SIZE = 32
SS = 4
MARGIN = 1.2
DIR_CORNERS = {
    'north': lambda a, b: [(a[0], b[1], a[2]), (b[0], b[1], a[2]), (b[0], a[1], a[2]), (a[0], a[1], a[2])],
    'south': lambda a, b: [(b[0], b[1], b[2]), (a[0], b[1], b[2]), (a[0], a[1], b[2]), (b[0], a[1], b[2])],
    'east':  lambda a, b: [(b[0], b[1], b[2]), (b[0], b[1], a[2]), (b[0], a[1], a[2]), (b[0], a[1], b[2])],
    'west':  lambda a, b: [(a[0], b[1], a[2]), (a[0], b[1], b[2]), (a[0], a[1], b[2]), (a[0], a[1], a[2])],
    'up':    lambda a, b: [(a[0], b[1], a[2]), (b[0], b[1], a[2]), (b[0], b[1], b[2]), (a[0], b[1], b[2])],
    'down':  lambda a, b: [(a[0], a[1], b[2]), (b[0], a[1], b[2]), (b[0], a[1], a[2]), (a[0], a[1], a[2])],
}
DIR_NORMAL = {
    'north': (0, 0, -1), 'south': (0, 0, 1),
    'east': (1, 0, 0), 'west': (-1, 0, 0),
    'up': (0, 1, 0), 'down': (0, -1, 0),
}
DIR_SHADE = {'north': 0.86, 'south': 0.76, 'east': 0.66, 'west': 0.82, 'up': 1.0, 'down': 0.5}

SKULLS = {
    'creeper_head', 'dragon_head', 'piglin_head', 'player_head',
    'skeleton_skull', 'wither_skeleton_skull', 'zombie_head',
    'creeper_wall_head', 'dragon_wall_head', 'piglin_wall_head',
    'player_wall_head', 'skeleton_wall_skull',
    'wither_skeleton_wall_skull', 'zombie_wall_head',
}
SKULL_ALIAS = {
    'creeper_wall_head': 'creeper_head',
    'dragon_wall_head': 'dragon_head',
    'piglin_wall_head': 'piglin_head',
    'player_wall_head': 'player_head',
    'skeleton_wall_skull': 'skeleton_skull',
    'wither_skeleton_wall_skull': 'wither_skeleton_skull',
    'zombie_wall_head': 'zombie_head',
    'skeleton': 'skeleton_skull',
    'wither_skeleton': 'wither_skeleton_skull',
}


def split_id(value: str):
    if ':' in value:
        ns, path = value.split(':', 1)
        return ns, path
    return 'minecraft', value


class Assets:
    def __init__(self, jar_path: str, out_dir: str):
        self.zip = zipfile.ZipFile(jar_path)
        self.names = set(self.zip.namelist())
        self.out = out_dir
        self._tex_cache = {}
        self._img_cache = {}
        self._model_cache = {}

    def has(self, name):
        return name in self.names

    def read_json(self, name):
        return json.loads(self.zip.read(name))

    def image(self, ref):
        """Load a texture reference as RGBA PIL image (first frame for animated textures)."""
        if ref is None:
            return None
        ref = str(ref)
        if ref.startswith('#'):
            return None
        ns, path = split_id(ref)
        name = f'assets/{ns}/textures/{path}.png'
        if name not in self._img_cache:
            if name not in self.names:
                self._img_cache[name] = None
            else:
                img = Image.open(io.BytesIO(self.zip.read(name))).convert('RGBA')
                w, h = img.size
                if h > w and h % w == 0:
                    img = img.crop((0, 0, w, w))
                self._img_cache[name] = img
        return self._img_cache[name]

    def texture(self, ref):
        if ref is None:
            return None
        ref = str(ref)
        ns, path = split_id(ref)
        name = f'assets/{ns}/textures/{path}.png'
        if name not in self._tex_cache:
            img = self.image(ref)
            self._tex_cache[name] = None if img is None else np.asarray(img, dtype=np.uint8)
        return self._tex_cache[name]

    def resolve_model(self, model_ref, seen=None):
        if isinstance(model_ref, str):
            ns, path = split_id(model_ref)
            name = f'assets/{ns}/models/{path}.json'
        else:
            raise TypeError(model_ref)
        if name in self._model_cache:
            return self._model_cache[name]
        if name not in self.names:
            return {'textures': {}, 'elements': [], 'display': {}}
        if seen is None:
            seen = set()
        if name in seen:
            return {'textures': {}, 'elements': [], 'display': {}}
        seen.add(name)
        obj = self.read_json(name)
        parent_ref = obj.get('parent')
        if parent_ref:
            parent = self.resolve_model(parent_ref, seen)
        else:
            parent = {'textures': {}, 'elements': [], 'display': {}}
        textures = dict(parent.get('textures', {}))
        textures.update(obj.get('textures', {}))
        display = dict(parent.get('display', {}))
        display.update(obj.get('display', {}))
        elements = obj['elements'] if 'elements' in obj else parent.get('elements', [])
        out = {'textures': textures, 'elements': elements, 'display': display}
        self._model_cache[name] = out
        return out

    def item_model(self, path):
        name = f'assets/minecraft/items/{path}.json'
        if name not in self.names:
            return None
        return self.read_json(name).get('model')


# ------------------------------------------------------------------ geometry

def rotate_point(point, origin, axis, degrees):
    if not degrees:
        return np.asarray(point, dtype=np.float64)
    p = np.asarray(point, dtype=np.float64) - np.asarray(origin, dtype=np.float64)
    a = math.radians(float(degrees))
    c, s = math.cos(a), math.sin(a)
    x, y, z = p
    if axis == 'x':
        p = np.array([x, c * y - s * z, s * y + c * z])
    elif axis == 'y':
        p = np.array([c * x + s * z, y, -s * x + c * z])
    else:
        p = np.array([c * x - s * y, s * x + c * y, z])
    return p + np.asarray(origin, dtype=np.float64)


def rotate_vec(vec, axis, degrees):
    if not degrees:
        return np.asarray(vec, dtype=np.float64)
    a = math.radians(float(degrees))
    c, s = math.cos(a), math.sin(a)
    x, y, z = vec
    if axis == 'x':
        return np.array([x, c * y - s * z, s * y + c * z])
    if axis == 'y':
        return np.array([c * x + s * z, y, -s * x + c * z])
    return np.array([c * x - s * y, s * x + c * y, z])


def rotate_uv(uv, rotation):
    if rotation not in (90, 180, 270):
        return uv
    u0, v0, u1, v1 = uv
    if rotation == 90:
        return (u0, v0, u1, v1)  # rare; keep stable
    if rotation == 180:
        return (u0, v0, u1, v1)
    return (u0, v0, u1, v1)


def tint_for(texture_path):
    path = str(texture_path or '').lower()
    if 'spruce_leaves' in path:
        return (0x61, 0x99, 0x61)
    if 'birch_leaves' in path:
        return (0x80, 0xA7, 0x55)
    if ('leaves' in path or 'vine' in path or 'lily_pad' in path
            or 'mangrove_roots' in path):
        return (0x48, 0xB5, 0x18)
    if 'grass' in path or 'fern' in path or 'double_plant' in path:
        return (0x91, 0xBD, 0x59)
    if 'water' in path:
        return (0x3F, 0x76, 0xE4)
    return None


def element_faces(assets: Assets, elements, textures):
    def resolve_ref(ref, seen=None):
        if ref is None:
            return None
        ref = str(ref)
        if not ref.startswith('#'):
            return ref
        if seen is None:
            seen = set()
        key = ref[1:]
        if key in seen:
            return None
        seen.add(key)
        return resolve_ref(textures.get(key), seen)

    faces = []
    for el in elements or []:
        a = np.asarray(el['from'], dtype=np.float64)
        b = np.asarray(el['to'], dtype=np.float64)
        if np.any(b < a):
            a, b = np.minimum(a, b), np.maximum(a, b)
        rot = el.get('rotation') or {}
        origin = rot.get('origin', [0, 0, 0])
        axis = rot.get('axis', 'y')
        angle = rot.get('angle', 0)
        rescale = rot.get('rescale', False)
        for direction, data in (el.get('faces') or {}).items():
            uv = data.get('uv')
            if uv is None:
                # Vanilla auto UV: approximate with the box bounds.
                if direction in ('north', 'south'):
                    uv = (a[0], a[2], b[0], b[2])
                elif direction in ('east', 'west'):
                    uv = (a[2], a[1], b[2], b[1])
                else:
                    uv = (a[0], a[2], b[0], b[2])
            tex_ref = data.get('texture', '#all')
            tex_ref = resolve_ref(tex_ref)
            tex = assets.texture(tex_ref)
            if tex is None:
                continue
            corners = DIR_CORNERS[direction](a.tolist(), b.tolist())
            if angle:
                corners = [rotate_point(p, origin, axis, angle) for p in corners]
            normal = rotate_vec(DIR_NORMAL[direction], axis, angle)
            shade = DIR_SHADE[direction]
            if data.get('shade') is False:
                shade = 1.0
            faces.append({
                'corners': corners,
                'normal': normal,
                'uv': rotate_uv(tuple(uv), data.get('rotation', 0)),
                'tex': tex,
                'shade': shade,
                'tint': tint_for(tex_ref) if data.get('tintindex') is not None else None,
            })
    return faces


def cube_uv_faces(x0, y0, z0, x1, y1, z1, u, v, scale=1.0):
    """Vanilla CubeListBuilder box UV unwrap, in a y-up model space."""
    w = (x1 - x0) * scale
    h = (y1 - y0) * scale
    d = (z1 - z0) * scale
    uw = x1 - x0
    uh = y1 - y0
    ud = z1 - z0
    a = (x0 * scale, y0 * scale, z0 * scale)
    b = (x1 * scale, y1 * scale, z1 * scale)
    uv = {
        'east':  (u, v + ud, u + ud, v + ud + uh),
        'west':  (u + ud + uw, v + ud, u + ud + uw + ud, v + ud + uh),
        'north': (u + ud, v + ud, u + ud + uw, v + ud + uh),
        'south': (u + ud + uw + ud, v + ud, u + ud + uw + ud + uw, v + ud + uh),
        'up':    (u + ud, v, u + ud + uw, v + ud),
        'down':  (u + ud + uw, v, u + ud + uw + uw, v + ud),
    }
    return [(direction, a, b, uv[direction]) for direction in DIR_CORNERS]


def skull_faces(assets: Assets, kind: str):
    kind = SKULL_ALIAS.get(kind, kind)
    if kind == 'dragon_head':
        return dragon_head_faces(assets)
    if kind == 'player_head':
        tex = assets.texture('entity/player/wide/steve')
    elif kind == 'creeper_head':
        tex = assets.texture('entity/creeper/creeper')
    elif kind == 'piglin_head':
        tex = assets.texture('entity/piglin/piglin')
    elif kind == 'wither_skeleton_skull':
        tex = assets.texture('entity/skeleton/wither_skeleton')
    elif kind == 'zombie_head':
        tex = assets.texture('entity/zombie/zombie')
    else:
        tex = assets.texture('entity/skeleton/skeleton')
    if tex is None:
        return []
    faces = []
    for direction, a, b, uv in cube_uv_faces(-4, 0, -4, 4, 8, 4, 0, 0):
        faces.append({
            'corners': DIR_CORNERS[direction](a, b),
            'normal': DIR_NORMAL[direction],
            'uv': uv,
            'tex': tex,
            'shade': DIR_SHADE[direction],
        })
    return faces


def dragon_head_faces(assets: Assets):
    """Vanilla DragonHeadModel cuboids, in y-up item space."""
    tex = assets.texture('entity/enderdragon/dragon')
    if tex is None:
        return []
    texture_units = 256.0
    scale = 0.75
    faces = []

    def box_faces(x0, y0, z0, x1, y1, z1, u, v):
        # Entity model space uses +Y down; flip to y-up before mapping.
        for direction, a, b, uv in cube_uv_faces(x0, -y1, z0, x1, -y0, z1,
                                                 u, v, scale=scale):
            faces.append({
                'corners': DIR_CORNERS[direction](a, b),
                'normal': DIR_NORMAL[direction],
                'uv': (uv[0] / texture_units * 256.0,
                       uv[1] / texture_units * 256.0,
                       uv[2] / texture_units * 256.0,
                       uv[3] / texture_units * 256.0),
                'tex': tex,
                'shade': DIR_SHADE[direction],
            })

    for (a, b, u, v) in [
        ((-6, -1, -24), (6, 4, -8), 176, 44),
        ((-8, -8, -10), (8, 8, 6), 112, 30),
        ((-5, -12, -4), (-3, -8, 2), 0, 0),
        ((3, -12, -4), (5, -8, 2), 0, 0),
        ((-5, -3, -22), (-3, -1, -18), 112, 0),
        ((3, -3, -22), (5, -1, -18), 112, 0),
    ]:
        box_faces(a[0], a[1], a[2], b[0], b[1], b[2], u, v)
    # Lower jaw (rotated slightly open).
    jaw_angle = math.radians(0.2)
    cj, sj = math.cos(jaw_angle), math.sin(jaw_angle)
    pivot = np.array([0.0, -4.0, -8.0])
    for direction, a, b, uv in cube_uv_faces(-6, 0, -16, 6, 4, 0, 176, 65,
                                             scale=scale):
        pts = []
        for p in DIR_CORNERS[direction](a, b):
            q = np.asarray(p, dtype=np.float64) - pivot
            q = np.array([q[0], cj * q[1] - sj * q[2], sj * q[1] + cj * q[2]])
            pts.append((q + pivot).tolist())
        faces.append({
            'corners': pts,
            'normal': DIR_NORMAL[direction],
            'uv': uv,
            'tex': tex,
            'shade': DIR_SHADE[direction],
        })
    return faces


# ----------------------------------------------------------------- rendering

def render(faces, yaw=225.0, pitch=30.0, out_size=SIZE, margin=MARGIN, ss=SS):
    if not faces:
        return Image.new('RGBA', (out_size, out_size), (0, 0, 0, 0))
    yaw, pitch = math.radians(yaw), math.radians(pitch)
    cy, sy = math.cos(yaw), math.sin(yaw)
    cp, sp = math.cos(pitch), math.sin(pitch)
    ry = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]], dtype=np.float64)
    rx = np.array([[1, 0, 0], [0, cp, -sp], [0, sp, cp]], dtype=np.float64)
    rot = rx @ ry

    prepared = []
    all_pts = []
    for face in faces:
        pts = np.asarray(face['corners'], dtype=np.float64) @ rot.T
        n = rot @ np.asarray(face['normal'], dtype=np.float64)
        if n[2] <= 1e-6:
            continue
        prepared.append((pts, face))
        all_pts.append(pts[:, :2])
    if not prepared:
        return Image.new('RGBA', (out_size, out_size), (0, 0, 0, 0))
    all_pts = np.concatenate(all_pts, axis=0)
    minx, miny = all_pts.min(axis=0)
    maxx, maxy = all_pts.max(axis=0)
    span = max(maxx - minx, maxy - miny)
    target = out_size - 2 * margin
    scale = target / span if span > 0 else 1.0
    cx = (minx + maxx) / 2.0
    cyy = (miny + maxy) / 2.0

    w = h = out_size * ss
    color = np.zeros((h, w, 4), dtype=np.float32)
    depth = np.full((h, w), -1e9, dtype=np.float64)

    def project(p):
        return ((p[0] - cx) * scale * ss + w / 2.0,
                -(p[1] - cyy) * scale * ss + h / 2.0,
                p[2])

    for rp, face in prepared:
        u0, v0, u1, v1 = face['uv']
        texf = face['tex'].astype(np.float32)
        tex_h, tex_w = face['tex'].shape[:2]
        shade = face['shade']
        tint = face.get('tint')
        tintf = (np.asarray(tint, dtype=np.float32) / 255.0) if tint else None
        proj = [project(p) for p in rp]
        for ia, ib, ic in ((0, 1, 2), (0, 2, 3)):
            (xa, ya, za), (xb, yb, zb), (xc, yc, zc) = proj[ia], proj[ib], proj[ic]
            minxi = max(0, int(math.floor(min(xa, xb, xc))))
            maxxi = min(w - 1, int(math.ceil(max(xa, xb, xc))))
            minyi = max(0, int(math.floor(min(ya, yb, yc))))
            maxyi = min(h - 1, int(math.ceil(max(ya, yb, yc))))
            if minxi > maxxi or minyi > maxyi:
                continue
            det = (yb - yc) * (xa - xc) + (xc - xb) * (ya - yc)
            if abs(det) < 1e-9:
                continue
            ab = {0: (0.0, 0.0), 1: (1.0, 0.0), 2: (1.0, 1.0), 3: (0.0, 1.0)}
            (aa, ab_) = ab[ia]
            (ba, bb) = ab[ib]
            (ca, cb) = ab[ic]
            ys, xs = np.mgrid[minyi:maxyi + 1, minxi:maxxi + 1]
            px = xs + 0.5
            py = ys + 0.5
            l1 = ((yb - yc) * (px - xc) + (xc - xb) * (py - yc)) / det
            l2 = ((yc - ya) * (px - xc) + (xa - xc) * (py - yc)) / det
            l3 = 1.0 - l1 - l2
            inside = (l1 >= -1e-6) & (l2 >= -1e-6) & (l3 >= -1e-6)
            if not inside.any():
                continue
            z = l1 * za + l2 * zb + l3 * zc
            aa_f = l1 * aa + l2 * ba + l3 * ca
            bb_f = l1 * ab_ + l2 * bb + l3 * cb
            uu = (u0 + aa_f * (u1 - u0)) / tex_w
            vv = (v0 + bb_f * (v1 - v0)) / tex_h
            tx = np.clip((uu * tex_w).astype(np.int32), 0, tex_w - 1)
            ty = np.clip((vv * tex_h).astype(np.int32), 0, tex_h - 1)
            sample = texf[ty, tx]
            rgb = sample[..., :3] * shade
            if tintf is not None:
                rgb = rgb * tintf
            alpha = sample[..., 3] / 255.0
            sel = inside & (z > depth[ys, xs] - 1e-6) & (alpha > 0.02)
            if not sel.any():
                continue
            depth[ys[sel], xs[sel]] = z[sel]
            color[ys[sel], xs[sel], :3] = np.clip(rgb[sel], 0, 255)
            color[ys[sel], xs[sel], 3] = sample[sel, 3]

    img = Image.fromarray(np.clip(color, 0, 255).astype(np.uint8), 'RGBA')
    return img.resize((out_size, out_size), Image.BOX)


# ------------------------------------------------------------------ item list

def choose_model_spec(spec):
    """Pick a representative model for a vanilla item definition."""
    if not isinstance(spec, dict):
        return None
    t = spec.get('type')
    if t == 'minecraft:model':
        return spec
    if t == 'minecraft:select':
        return choose_model_spec(spec.get('fallback')) or next(
            (choose_model_spec(c.get('model')) for c in spec.get('cases', []) if c.get('model')), None)
    if t == 'minecraft:condition':
        return choose_model_spec(spec.get('on_false')) or choose_model_spec(spec.get('on_true'))
    if t == 'minecraft:range_dispatch':
        return choose_model_spec(spec.get('fallback')) or next(
            (choose_model_spec(c.get('model')) for c in spec.get('entries', []) if c.get('model')), None)
    return spec


def apply_item_transform(faces, transform):
    if not faces or not transform:
        return faces
    translation = transform.get('translation') or [0, 0, 0]
    scale = transform.get('scale') or [1, 1, 1]
    # Item model transformations use block units (1 = 16 model pixels).
    tx, ty, tz = [float(v) * 16.0 for v in translation]
    sx, sy, sz = [float(v) for v in scale]
    out = []
    for face in faces:
        corners = [(p[0] * sx + tx, p[1] * sy + ty, p[2] * sz + tz)
                   for p in face['corners']]
        normal = (face['normal'][0], face['normal'][1], face['normal'][2])
        out.append({**face, 'corners': corners, 'normal': normal})
    return out


def shulker_faces(assets: Assets, path: str):
    """Shulker boxes use a special block-entity renderer; approximate with a cube."""
    tex_ref = f'minecraft:block/{path}'
    if assets.texture(tex_ref) is None:
        tex_ref = 'minecraft:block/shulker_box'
    element = {
        'from': [0, 0, 0],
        'to': [16, 16, 16],
        'faces': {direction: {'texture': '#all'} for direction in DIR_CORNERS},
    }
    faces = element_faces(assets, [element], {'all': tex_ref})
    base = assets.resolve_model('minecraft:item/template_shulker_box')
    gui = dict((base.get('display') or {}).get('gui') or {})
    gui.setdefault('rotation', [30, 45, 0])
    return faces, gui


def spec_faces(assets: Assets, spec):
    spec = choose_model_spec(spec)
    if not spec:
        return None, None
    t = spec.get('type')
    if t == 'minecraft:model':
        model = assets.resolve_model(spec.get('model'))
        faces = element_faces(assets, model.get('elements'), model.get('textures', {}))
        faces = apply_item_transform(faces, spec.get('transformation'))
        return faces, (model.get('display') or {}).get('gui')
    if t == 'minecraft:composite':
        faces = []
        gui = None
        for child in spec.get('models', []):
            cf, cgui = spec_faces(assets, child)
            gui = gui or cgui
            if cf:
                faces.extend(cf)
        return faces, gui
    if t == 'minecraft:special':
        head = spec.get('model') or {}
        if head.get('type') in ('minecraft:head', 'minecraft:player_head'):
            kind = head.get('kind', 'player')
            if head.get('type') == 'minecraft:player_head':
                kind = 'player_head'
            else:
                kind = {
                    'creeper': 'creeper_head',
                    'dragon': 'dragon_head',
                    'piglin': 'piglin_head',
                    'skeleton': 'skeleton_skull',
                    'wither_skeleton': 'wither_skeleton_skull',
                    'zombie': 'zombie_head',
                }.get(kind, kind)
            base = assets.resolve_model(spec.get('base') or 'item/template_skull')
            gui = dict((base.get('display') or {}).get('gui') or {})
            rot = list(gui.get('rotation', [30, 45, 0]))
            rot[1] = (rot[1] + 180) % 360
            gui['rotation'] = rot
            return skull_faces(assets, kind), gui
    return None, None


# ------------------------------------------------------------- flat item icons

GRASS_TINT = (0x91, 0xBD, 0x59)
POTION_FALLBACK = (0x38, 0x5D, 0xC6)


def tint_color(tints):
    """Item definition tints: grass / potion / dye. Flat icons use a representative color."""
    for tint in tints or []:
        kind = str(tint.get('type', ''))
        if kind == 'minecraft:grass':
            return GRASS_TINT
        if kind == 'minecraft:potion':
            value = tint.get('default')
            if isinstance(value, int):
                value &= 0xFFFFFF
                return ((value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF)
            return POTION_FALLBACK
    return None


def apply_flat_tint(img, tint):
    """Vanilla item tint multiplies grayscale layer pixels; colored pixels stay unchanged."""
    if tint is None:
        return img
    arr = np.asarray(img.convert('RGBA'), dtype=np.float32).copy()
    rgb = arr[..., :3]
    spread = rgb.max(axis=2) - rgb.min(axis=2)
    mask = (arr[..., 3] > 0) & (spread <= 12)
    for channel in range(3):
        rgb[..., channel][mask] *= tint[channel] / 255.0
    return Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8), 'RGBA')


def flat_model_icon(assets: Assets, model_ref, tints):
    model = assets.resolve_model(model_ref)
    textures = model.get('textures') or {}
    keys = [key for key in textures if str(key).startswith('layer')
            and str(key)[5:].isdigit()]
    keys.sort(key=lambda key: int(str(key)[5:]))
    if not keys:
        # 少数模型只给了一个命名贴图（如 item/generated 的变体），按声明顺序兜底。
        keys = [key for key in textures if key != 'particle']
    if not keys:
        return None
    size = 16
    base = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    drew = False
    tint = tint_color(tints)
    for key in keys:
        img = assets.image(textures.get(key))
        if img is None:
            continue
        if img.size != (size, size):
            img = img.resize((size, size), Image.NEAREST)
        img = apply_flat_tint(img, tint)
        base.alpha_composite(img, (0, 0))
        drew = True
    if not drew:
        return None
    return base.resize((SIZE, SIZE), Image.NEAREST)


def special_icon(assets: Assets, spec):
    """Special item renderers that have no elements: banners and shields."""
    model = spec.get('model') or {}
    kind = str(model.get('type', ''))
    if kind == 'minecraft:banner':
        color = str(model.get('color', 'white'))
        img = assets.image(f'minecraft:map/decorations/{color}_banner')
        if img is not None:
            return img.resize((SIZE, SIZE), Image.NEAREST)
    if kind == 'minecraft:shield':
        img = assets.image('minecraft:entity/shield/shield_base_nopattern')
        if img is not None:
            # shield_base 是 64x64 的展开图；正面在 (2,2)-(12,22)，与原版 shield/base.png 的遮罩一致。
            if img.width >= 12 and img.height >= 22:
                img = img.crop((2, 2, 12, 22))
            else:
                bbox = img.getbbox()
                if bbox:
                    img = img.crop(bbox)
            return img.resize((SIZE, SIZE), Image.NEAREST)
    return None


def flat_spec_icon(assets: Assets, spec, tints):
    spec = choose_model_spec(spec)
    if not spec:
        return None
    kind = spec.get('type')
    if kind == 'minecraft:model':
        return flat_model_icon(assets, spec.get('model'), tints)
    if kind == 'minecraft:composite':
        base = None
        for child in spec.get('models', []):
            img = flat_spec_icon(assets, child, tints)
            if img is None:
                continue
            if base is None:
                base = img
            else:
                base.alpha_composite(img, (0, 0))
        return base
    if kind == 'minecraft:special':
        return special_icon(assets, spec)
    return None


def special_fallback(assets: Assets, path: str, out_dir: str):
    """铜箱/铜傀儡雕像等 26.3 特殊实体渲染：没有元素模型时用原版贴图做近似图标。"""
    if path.endswith('_chest') or path == 'copper_chest':
        base_path = os.path.join(out_dir, 'chest.png')
        if os.path.isfile(base_path):
            base = Image.open(base_path).convert('RGBA')
            name = path
            if 'oxidized' in name:
                tint = (0.45, 0.95, 0.78)
            elif 'weathered' in name:
                tint = (0.64, 0.88, 0.70)
            elif 'exposed' in name:
                tint = (0.92, 0.80, 0.66)
            else:
                tint = (1.0, 0.62, 0.36)
            arr = np.asarray(base, dtype=np.float32).copy()
            arr[..., 0] *= tint[0]
            arr[..., 1] *= tint[1]
            arr[..., 2] *= tint[2]
            return Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8), 'RGBA')
    if 'copper_golem_statue' in path:
        texture = 'minecraft:block/copper_block'
        if 'oxidized' in path:
            texture = 'minecraft:block/oxidized_copper'
        elif 'weathered' in path:
            texture = 'minecraft:block/weathered_copper'
        elif 'exposed' in path:
            texture = 'minecraft:block/exposed_copper'
        img = assets.image(texture)
        if img is not None:
            return img.resize((SIZE, SIZE), Image.NEAREST)
    if path == 'decorated_pot':
        img = assets.image('minecraft:entity/decorated_pot/decorated_pot_base')
        if img is not None:
            return img.resize((SIZE, SIZE), Image.NEAREST)
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--jar', required=True)
    ap.add_argument('--out', default='mod/src/main/resources/web/items')
    ap.add_argument('--only', default='')
    args = ap.parse_args()
    assets = Assets(args.jar, args.out)
    lang = json.load(open('mod/src/main/resources/web/item-lang.json', encoding='utf-8'))['items']
    only = {x.strip() for x in args.only.split(',') if x.strip()}
    os.makedirs(args.out, exist_ok=True)
    stats = {'rendered': 0, 'flat': 0, 'skin': 0, 'missing': 0}
    for item_id in lang:
        path = split_id(item_id)[1]
        if path in ('piston_head', 'pottery_shard_skull', 'air'):
            continue
        if only and path not in only:
            continue
        spec = assets.item_model(path)
        if spec is None:
            if only:
                print('MISSING_ITEM', item_id)
            stats['missing'] += 1
            continue
        tints = spec.get('tints') if isinstance(spec, dict) else None
        if path in SKULLS:
            faces, gui = spec_faces(assets, spec)
        elif path == 'shulker_box' or path.endswith('_shulker_box'):
            faces, gui = shulker_faces(assets, path)
        else:
            faces, gui = spec_faces(assets, spec)
        img = None
        if faces:
            if gui:
                yaw = float(gui.get('rotation', [0, 225, 0])[1])
                pitch = float(gui.get('rotation', [0, 225, 0])[0])
            else:
                yaw, pitch = 225.0, 30.0
            img = render(faces, yaw=yaw, pitch=pitch)
        else:
            # 原版 item/generated 的平面多层贴图、旗帜与盾牌等 special 模型。
            img = flat_spec_icon(assets, spec, tints)
            if img is None:
                img = special_fallback(assets, path, args.out)
        if img is not None:
            name = SKULL_ALIAS.get(path, path)
            img.save(os.path.join(args.out, name + '.png'))
            if name != path:
                img.save(os.path.join(args.out, path + '.png'))
            if faces:
                stats['rendered'] += 1
            else:
                stats['flat'] += 1
        else:
            stats['missing'] += 1
    print(stats)


if __name__ == '__main__':
    main()
