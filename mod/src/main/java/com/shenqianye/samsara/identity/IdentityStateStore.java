package com.shenqianye.samsara.identity;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * {@code identity/state.json}：账号切换同步的运行时状态。
 *
 * <p>一个服务器可以有多个身份组，因此 state.json 用 {@code groups} 按 data UUID 分组；
 * 每组字段与设计要求一致：{@code dataUuid/lastWriter/lastSyncAt/syncBlocked}。
 */
public final class IdentityStateStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private StateFile state = new StateFile();

    public IdentityStateStore(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    public Path file() {
        return file;
    }

    public synchronized GroupState get(UUID data) {
        if (data == null) {
            return null;
        }
        return state.groups.get(data.toString());
    }

    public synchronized GroupState getOrCreate(UUID data) {
        GroupState value = get(data);
        if (value != null) {
            return value;
        }
        value = new GroupState();
        value.dataUuid = data.toString();
        state.groups.put(value.dataUuid, value);
        return value;
    }

    public synchronized void put(UUID data, GroupState value) {
        if (data == null || value == null) {
            return;
        }
        value.dataUuid = data.toString();
        state.groups.put(value.dataUuid, value);
    }

    public synchronized void remove(UUID data) {
        if (data != null) {
            state.groups.remove(data.toString());
        }
    }

    public synchronized Map<String, GroupState> all() {
        return Map.copyOf(state.groups);
    }

    public synchronized void load() {
        if (!Files.exists(file)) {
            state = new StateFile();
            return;
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            StateFile parsed = GSON.fromJson(text, StateFile.class);
            state = parsed == null ? new StateFile() : parsed;
            if (state.groups == null) {
                state.groups = new LinkedHashMap<>();
            }
            state.groups.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null);
        } catch (JsonParseException | IOException e) {
            // 损坏时保留空状态；同步前会要求重新初始化，不覆盖任何玩家数据。
            state = new StateFile();
        }
    }

    public synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(state), StandardCharsets.UTF_8);
            if (Files.exists(file)) {
                Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new RuntimeException("state.json 写入失败：" + e.getMessage(), e);
        }
    }

    public static final class StateFile {
        public int version = 1;
        public Map<String, GroupState> groups = new LinkedHashMap<>();
    }

    public static final class GroupState {
        public String dataUuid;
        public String lastWriter;
        public long lastSyncAt;
        public boolean syncBlocked;

        public UUID lastWriterUuid() {
            return IdentityConfig.parse(lastWriter);
        }

        public GroupState copy() {
            GroupState copy = new GroupState();
            copy.dataUuid = dataUuid;
            copy.lastWriter = lastWriter;
            copy.lastSyncAt = lastSyncAt;
            copy.syncBlocked = syncBlocked;
            return copy;
        }
    }
}
