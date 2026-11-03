package com.shenqianye.samsara.track;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 轨迹事件侧车 {@code .evt} 的纯二进制格式（设计规范〈轨迹事件 .evt〉/ F-105、F-106）。
 * <p>
 * 本类不依赖 Minecraft 运行时：只处理文件头、varint、事件类型与 payload 边界；
 * 物品 NBT 的编解码在 {@link TrackEventStore}。
 * <pre>
 * 文件头：MAGIC "SARE"(4B) + version(1B)
 * 记录：dt varint + type(1B) + payload
 * </pre>
 * 首条事件的 dt 为绝对 epoch 毫秒（64 位 varint），后续为相对间隔；首条事件必须是
 * {@code EQUIP} 初始快照。尾部不完整时只保留最后一条完整记录。
 */
public final class TrackEvents {
    public static final byte[] MAGIC = {'S', 'A', 'R', 'E'};
    public static final int VERSION = 1;

    public static final int TYPE_EQUIP = 0x01;
    public static final int TYPE_SWING = 0x02;
    public static final int TYPE_USE_START = 0x03;
    public static final int TYPE_USE_STOP = 0x04;
    public static final int TYPE_INVENTORY = 0x05;
    /** 预留；当前写入端不会产生，读取端按空 payload 处理。 */
    public static final int TYPE_BLOCK_BREAK = 0x06;
    public static final int TYPE_FLIGHT_MODE = 0x07;
    public static final int TYPE_VEHICLE = 0x08;
    /** 聊天发言：varint 字节长度 + UTF-8 文本。 */
    public static final int TYPE_CHAT = 0x09;

    public static final int FLIGHT_NONE = 0;
    public static final int FLIGHT_ELYTRA = 1;
    public static final int FLIGHT_CREATIVE = 2;

    /** VEHICLE v2 payload 标记（旧格式首字节是字符串长度，永远不会是 0xFF）。 */
    public static final int VEHICLE_FORMAT_V2 = 0xFF;
    public static final int VEHICLE_VERSION = 2;

    /** 六个可见槽的顺序：主手、副手、头、胸、腿、脚。 */
    public static final int SLOT_COUNT = 6;
    public static final int SLOT_MAINHAND = 0;
    public static final int SLOT_OFFHAND = 1;
    public static final int SLOT_HEAD = 2;
    public static final int SLOT_CHEST = 3;
    public static final int SLOT_LEGS = 4;
    public static final int SLOT_FEET = 5;
    public static final int ALL_SLOTS_MASK = (1 << SLOT_COUNT) - 1;

    private TrackEvents() {
    }

    /** 一条未解码的事件；{@code payload} 不含 type 字节。 */
    public record Event(long timeMs, int type, byte[] payload) {
        public Event {
            payload = payload == null ? new byte[0] : payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }

    /** 解析结果；{@code validBytes} 是最后一个完整记录结束位置（用于追加前截断尾部）。 */
    public record ReadResult(List<Event> events, boolean truncated, String warning, int validBytes) {
        public static ReadResult empty() {
            return new ReadResult(List.of(), false, null, 0);
        }
    }

    public static byte[] header() {
        byte[] out = new byte[MAGIC.length + 1];
        System.arraycopy(MAGIC, 0, out, 0, MAGIC.length);
        out[MAGIC.length] = (byte) VERSION;
        return out;
    }

    /**
     * 把一条事件追加到输出流（dt 由调用方给出上一条时间）。
     * <p>首条事件的 previous 传 0，dt 即绝对 epoch 毫秒（64 位 varint）；后续为相对间隔。
     */
    public static void write(OutputStream out, long previousTimeMs, Event event) throws IOException {
        long dt = event.timeMs() - previousTimeMs;
        if (dt < 0) {
            dt = 0;
        }
        writeVarLong(out, dt);
        out.write(event.type() & 0xFF);
        byte[] payload = event.payload();
        out.write(payload, 0, payload.length);
    }

    /**
     * 解码完整文件。文件头损坏返回空结果 + warning；记录截断时返回此前完整记录。
     */
    public static ReadResult read(byte[] bytes) {
        if (bytes == null || bytes.length < MAGIC.length + 1) {
            return new ReadResult(List.of(), true, "事件文件过短", 0);
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (bytes[i] != MAGIC[i]) {
                return new ReadResult(List.of(), true, "事件文件 magic 不匹配", 0);
            }
        }
        int version = bytes[MAGIC.length] & 0xFF;
        if (version != VERSION) {
            return new ReadResult(List.of(), true, "事件文件版本不支持: " + version, 0);
        }
        int[] pos = {MAGIC.length + 1};
        long time = 0;
        List<Event> out = new ArrayList<>();
        while (pos[0] < bytes.length) {
            int mark = pos[0];
            try {
                long dt = readVarLong(bytes, pos);
                if (pos[0] >= bytes.length) {
                    throw new EOFException("缺少 type");
                }
                int type = bytes[pos[0]++] & 0xFF;
                if (type == 0) {
                    throw new EOFException("事件类型 0（尾部未写完）");
                }
                int payloadStart = pos[0];
                skipPayload(bytes, pos, type);
                int payloadEnd = pos[0];
                byte[] payload = new byte[payloadEnd - payloadStart];
                System.arraycopy(bytes, payloadStart, payload, 0, payload.length);
                time += dt;
                out.add(new Event(time, type, payload));
            } catch (EOFException e) {
                pos[0] = mark;
                return new ReadResult(List.copyOf(out), true, e.getMessage(), mark);
            } catch (IllegalArgumentException e) {
                pos[0] = mark;
                return new ReadResult(List.copyOf(out), true, e.getMessage(), mark);
            }
        }
        return new ReadResult(List.copyOf(out), false, null, bytes.length);
    }

    /** 按类型跳过 payload；未知类型视作截断（不猜测边界）。 */
    private static void skipPayload(byte[] bytes, int[] pos, int type) throws EOFException {
        switch (type) {
            case TYPE_EQUIP -> {
                int mask = readByte(bytes, pos) & 0xFF;
                readByte(bytes, pos); // 选中槽
                for (int bit = 0; bit < SLOT_COUNT; bit++) {
                    if ((mask & (1 << bit)) != 0) {
                        int len = readVarInt(bytes, pos);
                        skip(bytes, pos, len);
                    }
                }
            }
            case TYPE_SWING -> skip(bytes, pos, 2);
            case TYPE_USE_START -> skip(bytes, pos, 1);
            case TYPE_USE_STOP -> {
            }
            case TYPE_INVENTORY -> {
                int len = readVarInt(bytes, pos);
                skip(bytes, pos, len);
            }
            case TYPE_BLOCK_BREAK -> {
            }
            case TYPE_FLIGHT_MODE -> skip(bytes, pos, 1);
            case TYPE_VEHICLE -> {
                int len = readVarInt(bytes, pos);
                skip(bytes, pos, len);
            }
            case TYPE_CHAT -> {
                int len = readVarInt(bytes, pos);
                skip(bytes, pos, len);
            }
            default -> throw new IllegalArgumentException("未知事件类型: " + type);
        }
    }

    private static void skip(byte[] bytes, int[] pos, int len) throws EOFException {
        if (len < 0 || pos[0] + len > bytes.length) {
            throw new EOFException("payload 截断");
        }
        pos[0] += len;
    }

    public static void writeVarInt(ByteArrayOutputStream out, int value) {
        int v = value;
        while ((v & ~0x7F) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v);
    }

    public static void writeVarInt(OutputStream out, int value) throws IOException {
        int v = value;
        while ((v & ~0x7F) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v);
    }

    public static void writeVarLong(OutputStream out, long value) throws IOException {
        long v = value;
        while ((v & ~0x7FL) != 0) {
            out.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        out.write((int) v);
    }

    public static long readVarLong(byte[] bytes, int[] pos) throws EOFException {
        long value = 0;
        int shift = 0;
        while (true) {
            if (pos[0] >= bytes.length || shift > 63) {
                throw new EOFException("varint 截断/溢出");
            }
            int b = bytes[pos[0]++] & 0xFF;
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
    }

    public static int readVarInt(byte[] bytes, int[] pos) throws EOFException {
        int value = 0;
        int shift = 0;
        while (true) {
            if (pos[0] >= bytes.length || shift > 28) {
                throw new EOFException("varint 截断/溢出");
            }
            int b = bytes[pos[0]++] & 0xFF;
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
    }

    private static int readByte(byte[] bytes, int[] pos) throws EOFException {
        if (pos[0] >= bytes.length) {
            throw new EOFException("payload 截断");
        }
        return bytes[pos[0]++] & 0xFF;
    }

    /** 构造 EQUIP payload（只写非空槽；空槽由缺省 bit 表示）。 */
    public static byte[] equipPayload(int selectedSlot, List<byte[]> slotNbt) {
        int mask = 0;
        for (int i = 0; i < SLOT_COUNT; i++) {
            byte[] nbt = i < slotNbt.size() ? slotNbt.get(i) : null;
            if (nbt != null && nbt.length > 0) {
                mask |= 1 << i;
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(mask);
        out.write(selectedSlot & 0xFF);
        for (int i = 0; i < SLOT_COUNT; i++) {
            byte[] nbt = i < slotNbt.size() ? slotNbt.get(i) : null;
            if ((mask & (1 << i)) != 0) {
                writeVarInt(out, nbt.length);
                out.write(nbt, 0, nbt.length);
            }
        }
        return out.toByteArray();
    }

    /** 解码 EQUIP payload 的非空槽 NBT；空槽返回 null。 */
    public static EquipPayload decodeEquipPayload(byte[] payload) {
        int[] pos = {0};
        try {
            int mask = readByte(payload, pos);
            int selected = readByte(payload, pos);
            byte[][] slots = new byte[SLOT_COUNT][];
            for (int i = 0; i < SLOT_COUNT; i++) {
                if ((mask & (1 << i)) != 0) {
                    int len = readVarInt(payload, pos);
                    if (len < 0 || pos[0] + len > payload.length) {
                        throw new EOFException("装备 payload 截断");
                    }
                    slots[i] = new byte[len];
                    System.arraycopy(payload, pos[0], slots[i], 0, len);
                    pos[0] += len;
                }
            }
            return new EquipPayload(slots, Math.max(0, Math.min(8, selected)));
        } catch (EOFException e) {
            return new EquipPayload(new byte[SLOT_COUNT][], 0);
        }
    }

    public record EquipPayload(byte[][] slots, int selectedSlot) {
    }

    public static byte[] swingPayload(int hand, int animationId) {
        return new byte[]{(byte) hand, (byte) animationId};
    }

    public static int hand(byte[] payload) {
        return payload.length > 0 ? payload[0] & 0xFF : 0;
    }

    public static int animation(byte[] payload) {
        return payload.length > 1 ? payload[1] & 0xFF : 0;
    }

    public static byte[] useStartPayload(int hand) {
        return new byte[]{(byte) hand};
    }

    public static byte[] flightPayload(int mode) {
        return new byte[]{(byte) mode};
    }

    public static int flightMode(byte[] payload) {
        return payload.length > 0 ? payload[0] & 0xFF : FLIGHT_NONE;
    }

    /** 聊天事件 payload：varint 字节长度 + UTF-8 文本（最多 1024 字节）。 */
    public static byte[] chatPayload(String text) {
        byte[] body = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
        if (body.length > 1024) {
            body = java.util.Arrays.copyOf(body, 1024);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(body.length + 4);
        writeVarInt(out, body.length);
        out.write(body, 0, body.length);
        return out.toByteArray();
    }

    public static String chatText(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return "";
        }
        try {
            int[] pos = {0};
            int len = readVarInt(payload, pos);
            if (len <= 0 || pos[0] + len > payload.length) {
                return "";
            }
            return new String(payload, pos[0], len, StandardCharsets.UTF_8);
        } catch (EOFException e) {
            return "";
        }
    }

    /**
     * VEHICLE 事件的内容：实体类型 + 可选的马属性。
     * <p>
     * v1 只存类型 id；v2 在类型之后追加马的最大生命/移速/跳跃/花色/鞍/箱/幼崽。
     * 非马载具仍写 v1 类型 payload，读取端对两种格式都兼容。
     */
    public record VehicleInfo(
            String typeId,
            boolean horse,
            float maxHealth,
            double movementSpeed,
            double jumpStrength,
            int variant,
            int markings,
            boolean saddle,
            boolean chested,
            boolean baby) {

        public VehicleInfo {
            typeId = typeId == null ? "" : typeId;
        }

        public static VehicleInfo typeOnly(String entityTypeId) {
            return new VehicleInfo(entityTypeId, false, 0f, 0d, 0d, 0, 0, false, false, false);
        }

        public boolean isEmpty() {
            return typeId.isBlank();
        }
    }

    public static byte[] vehiclePayload(String entityTypeId) {
        String value = entityTypeId == null ? "" : entityTypeId;
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarInt(out, utf8.length);
        out.write(utf8, 0, utf8.length);
        return out.toByteArray();
    }

    /**
     * v2：只有马写扩展字段；其他载具保持 v1 以节省空间并兼容旧读取端。
     * <p>
     * 外层仍是 `varint 内容长度 + 内容`，让 {@link #skipPayload} 能像旧格式一样整条跳过；
     * v2 内容以 {@code 0xFF + version} 开头，与旧格式的“类型字符串”区分。
     */
    public static byte[] vehiclePayload(VehicleInfo info) {
        if (info == null || info.isEmpty()) {
            return vehiclePayload("");
        }
        if (!info.horse()) {
            return vehiclePayload(info.typeId());
        }
        String value = info.typeId();
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        content.write(VEHICLE_FORMAT_V2);
        content.write(VEHICLE_VERSION);
        writeVarInt(content, utf8.length);
        content.write(utf8, 0, utf8.length);
        int flags = 1; // bit0 = horse
        if (info.saddle()) {
            flags |= 1 << 1;
        }
        if (info.chested()) {
            flags |= 1 << 2;
        }
        if (info.baby()) {
            flags |= 1 << 3;
        }
        content.write(flags);
        writeFloat(content, info.maxHealth());
        writeDouble(content, info.movementSpeed());
        writeDouble(content, info.jumpStrength());
        writeVarInt(content, Math.max(0, info.variant()));
        writeVarInt(content, Math.max(0, info.markings()));

        byte[] body = content.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarInt(out, body.length);
        out.write(body, 0, body.length);
        return out.toByteArray();
    }

    public static String vehicle(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return "";
        }
        int[] pos = {0};
        try {
            int len = readVarInt(payload, pos);
            if (len < 0 || pos[0] + len > payload.length) {
                return "";
            }
            if (len > 1 && (payload[pos[0]] & 0xFF) == VEHICLE_FORMAT_V2) {
                int[] cpos = {pos[0] + 1};
                int version = readByte(payload, cpos);
                if (version != VEHICLE_VERSION) {
                    return "";
                }
                int typeLen = readVarInt(payload, cpos);
                if (typeLen < 0 || cpos[0] + typeLen > payload.length) {
                    return "";
                }
                return new String(payload, cpos[0], typeLen, StandardCharsets.UTF_8);
            }
            return new String(payload, pos[0], len, StandardCharsets.UTF_8);
        } catch (EOFException e) {
            return "";
        }
    }

    /** 解码 VEHICLE payload；未知/损坏时按类型-only 回退。 */
    public static VehicleInfo vehicleInfo(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return VehicleInfo.typeOnly("");
        }
        int[] pos = {0};
        try {
            int len = readVarInt(payload, pos);
            if (len < 0 || pos[0] + len > payload.length) {
                return VehicleInfo.typeOnly("");
            }
            int bodyStart = pos[0];
            if (len <= 1 || (payload[bodyStart] & 0xFF) != VEHICLE_FORMAT_V2) {
                String type = new String(payload, bodyStart, len, StandardCharsets.UTF_8);
                // 旧轨迹只有类型：马类按默认属性回退，仍可生成可捕捉的真实马
                return horseLikeType(type) ? new VehicleInfo(type, true, 0f, 0d, 0d,
                        0, 0, false, false, false) : VehicleInfo.typeOnly(type);
            }
            int[] cpos = {bodyStart + 1};
            int version = readByte(payload, cpos);
            if (version != VEHICLE_VERSION) {
                return VehicleInfo.typeOnly(vehicle(payload));
            }
            int typeLen = readVarInt(payload, cpos);
            if (typeLen < 0 || cpos[0] + typeLen > payload.length) {
                return VehicleInfo.typeOnly("");
            }
            String type = new String(payload, cpos[0], typeLen, StandardCharsets.UTF_8);
            cpos[0] += typeLen;
            int flags = readByte(payload, cpos);
            boolean horse = (flags & 1) != 0;
            if (!horse) {
                return VehicleInfo.typeOnly(type);
            }
            float maxHealth = readFloat(payload, cpos);
            double movementSpeed = readDouble(payload, cpos);
            double jumpStrength = readDouble(payload, cpos);
            int variant = readVarInt(payload, cpos);
            int markings = readVarInt(payload, cpos);
            return new VehicleInfo(type, true, maxHealth, movementSpeed, jumpStrength,
                    variant, markings, (flags & (1 << 1)) != 0,
                    (flags & (1 << 2)) != 0, (flags & (1 << 3)) != 0);
        } catch (EOFException e) {
            return VehicleInfo.typeOnly(vehicle(payload));
        }
    }

    /**
     * 回放时转为真实僵尸马的类型：只认真正的马（horse/skeleton_horse/zombie_horse）。
     * 驴/骡/羊驼继续用客户端假载具，保留它们的外观。
     */
    public static boolean replayHorseType(String typeId) {
        if (typeId == null || typeId.isBlank()) {
            return false;
        }
        String path = typeId;
        int colon = path.indexOf(':');
        if (colon >= 0) {
            path = path.substring(colon + 1);
        }
        path = path.toLowerCase(java.util.Locale.ROOT);
        return path.equals("horse") || path.equals("skeleton_horse")
                || path.equals("zombie_horse");
    }

    /** 旧类型-only payload 的马类识别（用于按默认属性回退）。 */
    static boolean horseLikeType(String typeId) {
        return replayHorseType(typeId);
    }

    private static void writeFloat(ByteArrayOutputStream out, float value) {
        out.writeBytes(ByteBuffer.allocate(4).putFloat(value).array());
    }

    private static void writeDouble(ByteArrayOutputStream out, double value) {
        out.writeBytes(ByteBuffer.allocate(8).putDouble(value).array());
    }

    private static float readFloat(byte[] bytes, int[] pos) throws EOFException {
        if (pos[0] + 4 > bytes.length) {
            throw new EOFException("payload 截断");
        }
        float value = ByteBuffer.wrap(bytes, pos[0], 4).getFloat();
        pos[0] += 4;
        return value;
    }

    private static double readDouble(byte[] bytes, int[] pos) throws EOFException {
        if (pos[0] + 8 > bytes.length) {
            throw new EOFException("payload 截断");
        }
        double value = ByteBuffer.wrap(bytes, pos[0], 8).getDouble();
        pos[0] += 8;
        return value;
    }
}
