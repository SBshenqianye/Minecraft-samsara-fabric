#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
samsara（轮回）· 活跃度分析 v0 —— 不开服读存档，回答"多久没有玩家来玩"

用法:
    python analyze.py <世界目录> [--json <报告路径>]

数据来源（按优先级推断玩家最后登录时间）:
  1. config/samsara/activity/*.jsonl —— 本模组静默活动记录（v0.10.0 起，最可靠）
     （兼容旧版 config/samsara/activity.log，v0.9 及以前）
  2. logs/*.log(.gz)     —— 服务端日志中 "joined the game" 行（文件名日期 + 行内时间）
  3. usercache.json      —— expiresOn ≈ 最后登录 + 3 个月（估算级）
  4. 旧版 player .dat    —— lastPlayed（⚠️ 1.19 起已从玩家数据移除，仅旧世界有）
  世界级：level.dat 的 LastPlayed

零依赖：仅 Python 3 标准库（gzip/zlib + 内置最小 NBT 解析器）。
"""

import argparse
import datetime
import gzip
import json
import os
import re
import struct
import sys
import zlib

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

# usercache 缓存期（天）：玩家最后登录后 3 个月过期，反推估算最后登录
CACHE_DAYS = 90

# ---------- 极简 NBT 解析器（只读，支持全部 TAG 类型） ----------
TAG_END = 0
TAG_BYTE = 1
TAG_SHORT = 2
TAG_INT = 3
TAG_LONG = 4
TAG_FLOAT = 5
TAG_DOUBLE = 6
TAG_BYTE_ARRAY = 7
TAG_STRING = 8
TAG_LIST = 9
TAG_COMPOUND = 10
TAG_INT_ARRAY = 11
TAG_LONG_ARRAY = 12


class NBTParser:
    def __init__(self, data):
        self.buf = data
        self.pos = 0

    def _read(self, n):
        b = self.buf[self.pos:self.pos + n]
        self.pos += n
        return b

    def _u8(self):
        return self._read(1)[0]

    def _i16(self):
        return struct.unpack(">h", self._read(2))[0]

    def _i32(self):
        return struct.unpack(">i", self._read(4))[0]

    def _i64(self):
        return struct.unpack(">q", self._read(8))[0]

    def _f32(self):
        return struct.unpack(">f", self._read(4))[0]

    def _f64(self):
        return struct.unpack(">d", self._read(8))[0]

    def _str(self):
        n = self._i16()
        return self._read(n).decode("utf-8", errors="replace")

    def _payload(self, t):
        if t == TAG_BYTE:
            return self._u8()
        if t == TAG_SHORT:
            return self._i16()
        if t == TAG_INT:
            return self._i32()
        if t == TAG_LONG:
            return self._i64()
        if t == TAG_FLOAT:
            return self._f32()
        if t == TAG_DOUBLE:
            return self._f64()
        if t == TAG_BYTE_ARRAY:
            return list(self._read(self._i32()))
        if t == TAG_STRING:
            return self._str()
        if t == TAG_LIST:
            elem_type = self._u8()
            count = self._i32()
            return [self._payload(elem_type) for _ in range(count)]
        if t == TAG_COMPOUND:
            return self._compound()
        if t == TAG_INT_ARRAY:
            n = self._i32()
            return list(struct.unpack(">" + "i" * n, self._read(4 * n)))
        if t == TAG_LONG_ARRAY:
            n = self._i32()
            return list(struct.unpack(">" + "q" * n, self._read(8 * n)))
        raise ValueError("未知 TAG 类型 %d" % t)

    def _tag(self):
        t = self._u8()
        if t == TAG_END:
            return None
        name = self._str()
        return name, self._payload(t)

    def _compound(self):
        d = {}
        while True:
            tag = self._tag()
            if tag is None:
                break
            d[tag[0]] = tag[1]
        return d

    def root(self):
        self._u8()          # 根标签类型
        self._str()         # 根标签名（通常为空）
        return self._payload(TAG_COMPOUND)


def decompress(data):
    """优先 gzip，其次 zlib，最后按原始 NBT 处理。"""
    for opener in (gzip.decompress, lambda d: zlib.decompress(d)):
        try:
            return opener(data)
        except Exception:
            pass
    return data


def read_nbt(path):
    with open(path, "rb") as f:
        raw = f.read()
    return NBTParser(decompress(raw)).root()


# ---------- 工具 ----------
EPOCH = datetime.datetime(1970, 1, 1)


def fmt_ts(ms):
    if not ms or ms <= 0:
        return "未知"
    return (EPOCH + datetime.timedelta(milliseconds=ms)).strftime("%Y-%m-%d %H:%M")


def days_ago(ms, now):
    if not ms:
        return None
    delta = now - EPOCH - datetime.timedelta(milliseconds=ms)
    return round(delta.total_seconds() / 86400, 1)


def ts_to_ms(dt):
    return int((dt - EPOCH).total_seconds() * 1000)


# ---------- 各来源采集 ----------
def load_usercache(world):
    """usercache.json -> {uuid: {"name": str, "expiresOn": str}}；附 name->uuid 反查。"""
    path = os.path.join(world, "usercache.json")
    by_uuid, by_name = {}, {}
    if os.path.isfile(path):
        try:
            with open(path, "r", encoding="utf-8") as f:
                for entry in json.load(f):
                    u = entry.get("uuid", "").lower()
                    n = entry.get("name", "")
                    if u:
                        by_uuid[u] = {"name": n, "expiresOn": entry.get("expiresOn", "")}
                    if n:
                        by_name[n] = u
        except Exception as e:
            print("[警告] usercache.json 解析失败: %s" % e)
    return by_uuid, by_name


def parse_expires_on(expires_on):
    """ISO 8601（含 Z）-> datetime；失败返回 None。"""
    if not expires_on:
        return None
    try:
        s = expires_on.strip()
        if s.endswith("Z"):
            s = s[:-1] + "+00:00"
        return datetime.datetime.fromisoformat(s).replace(tzinfo=None)
    except Exception:
        return None


def scan_player_dats(dat_dir):
    """旧版 player .dat 的 lastPlayed（1.19 起已移除，仅旧世界有）。"""
    players = {}
    for fn in sorted(os.listdir(dat_dir)):
        if not fn.endswith(".dat") or fn.endswith("_old.dat"):
            continue
        path = os.path.join(dat_dir, fn)
        try:
            nbt = read_nbt(path)
            uuid = fn[:-4].lower()
            players[uuid] = {
                "lastPlayedLegacy": nbt.get("lastPlayed"),
                "bukkitName": str(nbt.get("bukkit", {}).get("lastKnownName", "")) or None,
            }
        except Exception as e:
            print("[警告] 解析 %s 失败: %s" % (fn, e))
    return players


def parse_logs(world):
    """扫描 logs/ 下 *.log / *.log.gz，提取 "<name> joined the game" 的最后时间。"""
    log_dir = os.path.join(world, "logs")
    last_by_name = {}
    if not os.path.isdir(log_dir):
        return last_by_name
    now = datetime.datetime.now()
    for fn in sorted(os.listdir(log_dir)):
        if not (fn.endswith(".log") or fn.endswith(".log.gz")):
            continue
        m = re.match(r"(\d{4}-\d{2}-\d{2})-\d+\.log(?:\.gz)?$", fn)
        if m:
            base_date = datetime.datetime.strptime(m.group(1), "%Y-%m-%d")
        elif fn == "latest.log":
            base_date = now
        else:
            continue
        path = os.path.join(log_dir, fn)
        try:
            if fn.endswith(".gz"):
                with gzip.open(path, "rb") as f:
                    text = f.read().decode("utf-8", errors="replace")
            else:
                with open(path, "r", encoding="utf-8", errors="replace") as f:
                    text = f.read()
        except Exception as e:
            print("[警告] 日志读取失败 %s: %s" % (fn, e))
            continue
        for line in text.splitlines():
            mm = re.search(r"\[(\d{2}):(\d{2}):(\d{2})\].*?([\w.]+) joined the game", line)
            if mm:
                hh, mi, ss = int(mm.group(1)), int(mm.group(2)), int(mm.group(3))
                name = mm.group(4)
                ts = base_date.replace(hour=hh, minute=mi, second=ss)
                if ts > now + datetime.timedelta(hours=1):  # latest.log 跨天修正
                    ts -= datetime.timedelta(days=1)
                if name not in last_by_name or ts > last_by_name[name]:
                    last_by_name[name] = ts
    return last_by_name


def read_activity_records(world):
    """本模组活动记录：优先读 `config/samsara/activity/YYYY-MM-DD.jsonl`（v0.10.0 起，
    每行一个 JSON 事件，取 ev=join 的 t/name），并兼容旧版 `activity.log`（`epoch_ms name JOIN`）。

    兼容两种位置：`<world>/config/samsara/`（久路径）与 `<world>/../config/samsara/`（服务端根配置目录）。
    返回 {玩家名: (epoch_ms, 来源标签)}，同一玩家取最晚上线时间。"""
    candidates = [
        os.path.join(world, "config", "samsara"),
        os.path.normpath(os.path.join(world, "..", "config", "samsara")),
    ]
    by_name = {}
    seen_dirs = set()

    def remember(name, ms, label):
        if not name or not isinstance(ms, int) or ms <= 0:
            return
        old = by_name.get(name)
        if old is None or ms > old[0]:
            by_name[name] = (ms, label)

    for cfg_dir in candidates:
        cfg_dir = os.path.normpath(cfg_dir)
        if cfg_dir in seen_dirs:
            continue
        seen_dirs.add(cfg_dir)

        # 新格式：activity/*.jsonl（按日期文件名升序；只取 join 事件）
        act_dir = os.path.join(cfg_dir, "activity")
        if os.path.isdir(act_dir):
            for fn in sorted(os.listdir(act_dir)):
                if not fn.endswith(".jsonl"):
                    continue
                path = os.path.join(act_dir, fn)
                try:
                    with open(path, "r", encoding="utf-8") as f:
                        for line in f:
                            line = line.strip()
                            if not line:
                                continue
                            try:
                                ev = json.loads(line)
                            except ValueError:
                                continue  # 追加写入时的残行
                            if ev.get("ev") != "join":
                                continue
                            remember(ev.get("name"), ev.get("t"), "activity.jsonl")
                except Exception as e:
                    print("[警告] %s 读取失败: %s" % (path, e))

        # 旧格式：activity.log（滚动档先，当前档后）
        for fn in ("activity.1.log", "activity.log"):
            path = os.path.join(cfg_dir, fn)
            if not os.path.isfile(path):
                continue
            try:
                with open(path, "r", encoding="utf-8") as f:
                    for line in f:
                        parts = line.split()
                        if len(parts) < 2 or not parts[0].isdigit():
                            continue
                        ev = parts[2] if len(parts) >= 3 else "JOIN"
                        if ev != "JOIN":
                            continue
                        remember(parts[1], int(parts[0]), "activity.log(旧)")
            except Exception as e:
                print("[警告] %s 读取失败: %s" % (path, e))
    return by_name


# ---------- 主流程 ----------
def main():
    ap = argparse.ArgumentParser(description="samsara 活跃度分析 v0")
    ap.add_argument("world", help="世界目录（含 playerdata/、logs/、usercache.json 等），或直接含 <uuid>.dat 的目录")
    ap.add_argument("--json", help="输出报告 JSON 路径（可选）")
    args = ap.parse_args()

    world = args.world
    now = datetime.datetime.now()

    uc_by_uuid, uc_by_name = load_usercache(world)
    dat_dir = os.path.join(world, "playerdata")
    if not os.path.isdir(dat_dir):
        dat_dir = world  # 兼容"直接是玩家数据目录"的用法
    player_dats = scan_player_dats(dat_dir) if os.path.isdir(dat_dir) else {}
    log_joins = parse_logs(world)
    act_records = read_activity_records(world)

    # 玩家集合 = usercache ∪ playerdata
    uuids = sorted(set(list(uc_by_uuid.keys()) + list(player_dats.keys())))
    if not uuids:
        print("[错误] 未找到任何玩家数据（需要 usercache.json 或 playerdata/ 目录）")
        sys.exit(1)

    players = []
    for uuid in uuids:
        uc = uc_by_uuid.get(uuid, {})
        pd = player_dats.get(uuid, {})
        name = uc.get("name") or pd.get("bukkitName") or uuid
        exp_dt = parse_expires_on(uc.get("expiresOn", ""))
        sources = {}
        # 1. 活动记录（JSONL / 旧 activity.log，按名）
        if name in act_records:
            ms, label = act_records[name]
            sources[label] = ms
        # 2. 日志 joined the game（按名）
        if name in log_joins:
            sources["日志"] = ts_to_ms(log_joins[name])
        # 3. usercache expiresOn 估算
        if exp_dt:
            sources["usercache估算"] = ts_to_ms(exp_dt - datetime.timedelta(days=CACHE_DAYS))
        # 4. 旧版 lastPlayed
        if pd.get("lastPlayedLegacy"):
            sources["旧版lastPlayed"] = pd["lastPlayedLegacy"]

        best_ms = max(sources.values()) if sources else None
        best_src = max(sources, key=sources.get) if sources else None
        players.append({
            "uuid": uuid,
            "name": name,
            "lastPlayed": best_ms,
            "lastPlayedSource": best_src,
            "sources": sources,
        })

    last_any = max((p["lastPlayed"] for p in players if p["lastPlayed"]), default=None)
    level_last = None
    lf = os.path.join(world, "level.dat")
    if os.path.isfile(lf):
        try:
            level_last = read_nbt(lf).get("LastPlayed")
        except Exception as e:
            print("[警告] level.dat 解析失败: %s" % e)

    print("=" * 66)
    print("samsara（轮回）· 活跃度分析 v0")
    print("=" * 66)
    print("玩家总数: %d" % len(players))
    for p in sorted(players, key=lambda x: x["lastPlayed"] or 0, reverse=True):
        d = days_ago(p["lastPlayed"], now)
        if d is not None:
            suffix = "（%.1f 天前）" % d
            src = " · 来源: %s" % p["lastPlayedSource"]
        else:
            suffix = ""
            src = " · 无可用时间来源"
        print("  %-24s 最后登录 %s%s%s" % (p["name"], fmt_ts(p["lastPlayed"]), suffix, src))
    print("-" * 66)
    if last_any:
        print("★ 距上次玩家登录: %.1f 天（%s）" % (days_ago(last_any, now), fmt_ts(last_any)))
    else:
        print("★ 无可用登录时间来源")
    if level_last:
        print("  世界最后写入(level.dat LastPlayed): %s（%.1f 天前）"
              % (fmt_ts(level_last), days_ago(level_last, now) or 0))
    print("=" * 66)
    if not (log_joins or act_records or uc_by_uuid or level_last):
        print("提示：本目录仅有 player .dat。1.19 起 lastPlayed 已从玩家数据移除，")
        print("      建议提供 usercache.json / logs/ 目录，或 config/samsara/activity/（模组 v0.10.0+ 自动写入）。")

    report = {
        "schema": "samsara-activity-v0.1",
        "generated": now.isoformat(),
        "playerCount": len(players),
        "players": players,
        "lastPlayerActivity": last_any,
        "lastPlayerActivityDaysAgo": days_ago(last_any, now),
        "levelLastPlayed": level_last,
        "levelLastPlayedDaysAgo": days_ago(level_last, now),
    }
    if args.json:
        out = args.json
        os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
        with open(out, "w", encoding="utf-8") as f:
            json.dump(report, f, ensure_ascii=False, indent=2)
        print("报告已写入 %s" % out)


if __name__ == "__main__":
    main()
