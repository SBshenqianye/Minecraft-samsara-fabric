package com.shenqianye.samsara.claim;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F-08：删除前 .mca 备份目录再压成整档 zip。 */
class BackupZipTest {

    @Test
    void zipsBackupDirectoryWithRelativePaths(@TempDir Path dir) throws Exception {
        Path backup = dir.resolve("delete_20260101_000000");
        Path region = backup.resolve("region");
        Files.createDirectories(region);
        Files.writeString(region.resolve("r.0.0.mca"), "mca-bytes", StandardCharsets.UTF_8);
        Files.writeString(backup.resolve("manifest.txt"), "note", StandardCharsets.UTF_8);

        Path zip = dir.resolve("delete_20260101_000000.zip");
        BackupZip.zipDirectory(backup, zip);

        assertTrue(Files.isRegularFile(zip));
        try (ZipFile archive = new ZipFile(zip.toFile())) {
            assertEquals("mca-bytes", new String(
                    archive.getInputStream(archive.getEntry("region/r.0.0.mca")).readAllBytes(),
                    StandardCharsets.UTF_8));
            assertEquals("note", new String(
                    archive.getInputStream(archive.getEntry("manifest.txt")).readAllBytes(),
                    StandardCharsets.UTF_8));
        }
    }

    @Test
    void missingDirectoryFailsWithoutCreatingZip(@TempDir Path dir) {
        Path zip = dir.resolve("missing.zip");
        boolean failed = false;
        try {
            BackupZip.zipDirectory(dir.resolve("nope"), zip);
        } catch (Exception e) {
            failed = true;
        }
        assertTrue(failed);
        assertTrue(Files.notExists(zip));
    }
}
