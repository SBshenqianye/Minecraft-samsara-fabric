package com.shenqianye.samsara.announce;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.shenqianye.samsara.config.spec.ConfigFileIO;
import com.shenqianye.samsara.storage.StoragePaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 公告数据文件 {@code config/samsara/announcements.json}。 */
public final class AnnouncementStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
            .disableHtmlEscaping().create();

    private final Path file;

    public AnnouncementStore() {
        this(StoragePaths.configRoot().resolve("announcements.json"));
    }

    AnnouncementStore(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    public Path file() {
        return file;
    }

    public synchronized List<Announcement> load() {
        if (!Files.isRegularFile(file)) {
            List<Announcement> defaults = defaults();
            save(defaults);
            return defaults;
        }
        try {
            String text = ConfigFileIO.read(file);
            Data data = GSON.fromJson(text, Data.class);
            List<Announcement> list = new ArrayList<>();
            if (data != null && data.announcements != null) {
                for (Announcement item : data.announcements) {
                    if (item != null) {
                        list.add(item.normalize());
                    }
                }
            }
            return list;
        } catch (RuntimeException | IOException e) {
            return defaults();
        }
    }

    public synchronized void save(List<Announcement> announcements) {
        Data data = new Data();
        data.announcements = announcements == null ? List.of() : announcements;
        try {
            if (Files.isRegularFile(file)) {
                Path bak = file.resolveSibling(file.getFileName() + ".bak");
                Files.copy(file, bak, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            ConfigFileIO.atomicWrite(file, GSON.toJson(data) + "\n");
        } catch (IOException e) {
            throw new IllegalStateException("保存公告失败：" + e.getMessage(), e);
        }
    }

    public static List<Announcement> defaults() {
        Announcement welcome = new Announcement();
        welcome.id = "welcome";
        welcome.name = "欢迎公告";
        welcome.enabled = false;
        welcome.trigger = "join";
        welcome.target = "all";
        welcome.chat = true;
        welcome.chatText = "&e[公告] &f欢迎 {player} 来到服务器！\n&7在线 {online} 人 · {world}";
        List<Announcement> list = new ArrayList<>();
        list.add(welcome);
        return list;
    }

    /** 保存校验：id 唯一、字段合法。 */
    public static List<String> validateAll(List<Announcement> announcements) {
        List<String> errors = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (Announcement item : announcements == null ? List.<Announcement>of() : announcements) {
            if (item == null) {
                errors.add("公告不能为空");
                continue;
            }
            item.normalize();
            if (!ids.add(item.id)) {
                errors.add("公告 id 重复：" + item.id);
            }
            for (String error : item.validate()) {
                errors.add(item.id + "：" + error);
            }
        }
        return errors;
    }

    private static final class Data {
        List<Announcement> announcements = List.of();
    }
}
