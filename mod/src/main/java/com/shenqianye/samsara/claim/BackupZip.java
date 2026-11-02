package com.shenqianye.samsara.claim;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 把删除前备份目录压成单个 zip（F-08 的“整档 zip”）。 */
public final class BackupZip {
    private BackupZip() {
    }

    public static void zipDirectory(Path dir, Path zipFile) throws IOException {
        if (dir == null || zipFile == null || !Files.isDirectory(dir)) {
            throw new IOException("备份目录不存在");
        }
        if (Files.exists(zipFile)) {
            Files.delete(zipFile);
        }
        Path parent = zipFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream raw = Files.newOutputStream(zipFile);
             ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(raw));
             Stream<Path> stream = Files.walk(dir)) {
            for (Path file : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator) {
                if (file.equals(zipFile)) {
                    continue;
                }
                String name = dir.relativize(file).toString().replace('\\', '/');
                ZipEntry entry = new ZipEntry(name);
                try {
                    entry.setLastModifiedTime(Files.getLastModifiedTime(file));
                } catch (IOException ignored) {
                }
                zip.putNextEntry(entry);
                Files.copy(file, zip);
                zip.closeEntry();
            }
        }
    }
}
