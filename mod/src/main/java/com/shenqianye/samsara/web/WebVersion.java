package com.shenqianye.samsara.web;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Web 控制台/用户端的动态版本号：随部署 jar 的构建时间更新，不再手改常量。 */
public final class WebVersion {
    public static final String VALUE = resolve();

    private WebVersion() {
    }

    private static String resolve() {
        String base = "0.58.0";
        try {
            Path location = Path.of(WebVersion.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            FileTime time = Files.getLastModifiedTime(location);
            String stamp = DateTimeFormatter.ofPattern("MMdd.HHmm")
                    .withZone(ZoneId.systemDefault())
                    .format(time.toInstant());
            return base + "+" + stamp;
        } catch (Exception e) {
            return base;
        }
    }
}
