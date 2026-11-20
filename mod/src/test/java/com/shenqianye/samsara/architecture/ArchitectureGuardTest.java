package com.shenqianye.samsara.architecture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 架构守卫：把「路径只能由 StoragePaths 解析」「领域层不反向依赖 web」变成 `gradle test` 会失败的断言。
 *
 * <p>规则与 `docs/agent/legacy-audit-and-guardrails.md` 一一对应；新增例外必须先写进文档，
 * 否则这里会失败。
 */
class ArchitectureGuardTest {
    private static final String STORAGE_PATHS = "com/shenqianye/samsara/storage/StoragePaths.java";
    private static final String ROOT_ENTRY = "com/shenqianye/samsara/Samsara.java";
    private static final String WEB_PACKAGE = "com/shenqianye/samsara/web/";
    private static final String STORAGE_PACKAGE = "com/shenqianye/samsara/storage/";

    private static final String SAMSARA_PACKAGE = "com/shenqianye/samsara/";
    private static final String SERVER_BACKUP = "com/shenqianye/samsara/server/ServerBackup.java";

    /** 配置根写法：只允许 StoragePaths 自己出现。 */
    private static final Pattern CONFIG_LITERAL = Pattern.compile("\"config/samsara\"");
    private static final Pattern CONFIG_SPLIT_LITERAL =
            Pattern.compile("\"config\"\\s*,\\s*\"samsara\"");
    private static final Pattern BACKUP_LITERAL = Pattern.compile("\"backups/samsara\"");
    private static final Pattern BACKUP_SPLIT_LITERAL =
            Pattern.compile("\"backups\"\\s*,\\s*\"samsara\"");
    /** 链式写法 {@code .resolve("backups").resolve("samsara")} 也必须走 StoragePaths。 */
    private static final Pattern BACKUP_CHAIN_LITERAL =
            Pattern.compile("resolve\\(\"backups\"\\)\\s*\\.\\s*resolve\\(\"samsara\"\\)");
    private static final Pattern SAMSARA_PACKAGE_IMPORT =
            Pattern.compile("^\\s*import\\s+(static\\s+)?(com\\.shenqianye\\.samsara\\.[\\w.]+)\\s*;");

    @TempDir
    Path tmp;

    // ---- 真实源码 ----

    @Test
    void onlyStoragePathsReadsFabricConfigDir() {
        List<String> violations = configDirViolations(sourceRoot());
        assertEquals(List.of(), violations,
                "除 StoragePaths 外不得直接调用 FabricLoader.getConfigDir()；"
                        + "请在 StoragePaths 加方法后调用：\n" + String.join("\n", violations));
    }

    @Test
    void onlyStoragePathsSpellsConfigDirectory() {
        List<String> violations = configLiteralViolations(sourceRoot());
        assertEquals(List.of(), violations,
                "不得硬编码 config/samsara；请用 StoragePaths.CONFIG_DIR / configDirAt / configFile：\n"
                        + String.join("\n", violations));
    }

    @Test
    void domainPackagesDoNotImportWebLayer() {
        List<String> violations = webImportViolations(sourceRoot());
        assertEquals(List.of(), violations,
                "web 是外层；领域包不得 import 它（需要的反向调用请加接口由 Samsara 装配）：\n"
                        + String.join("\n", violations));
    }

    @Test
    void storagePackageOnlyDependsOnConfigAndEntrypoint() {
        List<String> violations = storageLayerViolations(sourceRoot());
        assertEquals(List.of(), violations,
                "storage 只允许依赖根入口 Samsara 与 config 包：\n" + String.join("\n", violations));
    }

    @Test
    void serverBackupIncludesCrossSeasonSharedData() {
        String code = stripComments(read(sourceRoot().resolve(SERVER_BACKUP)));
        assertTrue(code.contains("sharedDirAt("),
                "ServerBackup 必须把 samsara/shared 打进本体备份；"
                        + "请使用 StoragePaths.sharedDirAt(serverRoot)");
        assertTrue(code.contains("migrateShared") || code.contains("shared"),
                "恢复逻辑必须处理 shared/");
    }

    @Test
    void onlyStoragePathsSpellsBackupDirectory() {
        List<String> violations = backupLiteralViolations(sourceRoot());
        assertEquals(List.of(), violations,
                "不得硬编码 backups/samsara；请用 StoragePaths.backupsRootAt(serverRoot)：\n"
                        + String.join("\n", violations));
    }

    // ---- 守卫自身有效性（故意违规必须被抓到） ----

    @Test
    void guardDetectsConfigDirUsage() throws IOException {
        write("a/Fake.java", "class Fake {\n"
                + "  void f() { net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir(); }\n"
                + "  // 注释里的 getConfigDir() 不算\n"
                + "  String s = \"https://example.com/getConfigDir()\";\n"
                + "}\n");

        List<String> violations = configDirViolations(tmp);

        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.get(0).startsWith("a/Fake.java:"), violations.toString());
    }

    @Test
    void guardDetectsHardcodedConfigDirectory() throws IOException {
        write("a/Fake.java", "class Fake {\n"
                + "  String dir = \"config/samsara\";\n"
                + "}\n");
        write("b/Fake2.java", "class Fake2 {\n"
                + "  Object p = java.nio.file.Path.of(\"config\", \"samsara\");\n"
                + "}\n");

        List<String> violations = configLiteralViolations(tmp);

        assertEquals(2, violations.size(), violations.toString());
        assertTrue(violations.get(0).startsWith("a/Fake.java:"), violations.toString());
        assertTrue(violations.get(1).startsWith("b/Fake2.java:"), violations.toString());
    }

    @Test
    void guardDetectsDomainToWebImport() throws IOException {
        write("a/Fake.java", "package a;\n"
                + "import com.shenqianye.samsara.web.WebAuditService;\n"
                + "class Fake {}\n");
        write("com/shenqianye/samsara/Samsara.java", "import com.shenqianye.samsara.web.LocalWebServer;\n");

        List<String> violations = webImportViolations(tmp);

        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.get(0).startsWith("a/Fake.java "), violations.toString());
    }

    @Test
    void guardDetectsStorageLeakingIntoDomain() throws IOException {
        write("com/shenqianye/samsara/storage/Fake.java", "package com.shenqianye.samsara.storage;\n"
                + "import com.shenqianye.samsara.season.SeasonManager;\n"
                + "class Fake {}\n");

        List<String> violations = storageLayerViolations(tmp);

        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.get(0).contains("season.SeasonManager"), violations.toString());
    }

    @Test
    void guardDetectsHardcodedBackupDirectory() throws IOException {
        write("a/Fake.java", "class Fake {\n"
                + "  String dir = \"backups/samsara\";\n"
                + "}\n");
        write("b/Fake2.java", "class Fake2 {\n"
                + "  Object p = java.nio.file.Path.of(\"backups\", \"samsara\");\n"
                + "}\n");
        write("c/Fake3.java", "class Fake3 {\n"
                + "  Object p = root.resolve(\"backups\").resolve(\"samsara\");\n"
                + "}\n");

        List<String> violations = backupLiteralViolations(tmp);

        assertEquals(3, violations.size(), violations.toString());
    }

    // ---- 扫描实现 ----

    static List<String> configDirViolations(Path root) {
        List<String> violations = new ArrayList<>();
        forEachSource(root, (relative, code) -> {
            if (relative.equals(STORAGE_PATHS)) {
                return;
            }
            int line = lineOf(code, "getConfigDir()");
            if (line > 0) {
                violations.add(relative + ":" + line + " 直接调用 getConfigDir()");
            }
        });
        return violations;
    }

    static List<String> configLiteralViolations(Path root) {
        List<String> violations = new ArrayList<>();
        forEachSource(root, (relative, code) -> {
            if (relative.equals(STORAGE_PATHS)) {
                return;
            }
            int line = firstMatchLine(code, CONFIG_LITERAL);
            if (line < 0) {
                line = firstMatchLine(code, CONFIG_SPLIT_LITERAL);
            }
            if (line > 0) {
                violations.add(relative + ":" + line + " 硬编码 config/samsara");
            }
        });
        return violations;
    }

    static List<String> webImportViolations(Path root) {
        List<String> violations = new ArrayList<>();
        forEachSource(root, (relative, code) -> {
            if (relative.startsWith(WEB_PACKAGE) || relative.equals(ROOT_ENTRY)) {
                return;
            }
            String imported = importedSamsaraClass(code);
            if (imported != null && imported.startsWith("com.shenqianye.samsara.web.")) {
                violations.add(relative + " import " + imported);
            }
        });
        return violations;
    }

    static List<String> backupLiteralViolations(Path root) {
        List<String> violations = new ArrayList<>();
        forEachSource(root, (relative, code) -> {
            if (relative.startsWith(STORAGE_PACKAGE)) {
                return;
            }
            int line = firstMatchLine(code, BACKUP_LITERAL);
            if (line < 0) {
                line = firstMatchLine(code, BACKUP_SPLIT_LITERAL);
            }
            if (line < 0) {
                line = firstMatchLine(code, BACKUP_CHAIN_LITERAL);
            }
            if (line > 0) {
                violations.add(relative + ":" + line + " 硬编码 backups/samsara");
            }
        });
        return violations;
    }

    static List<String> storageLayerViolations(Path root) {
        List<String> violations = new ArrayList<>();
        forEachSource(root, (relative, code) -> {
            if (!relative.startsWith(STORAGE_PACKAGE)) {
                return;
            }
            String imported = importedSamsaraClass(code);
            if (imported == null) {
                return;
            }
            boolean allowed = imported.equals("com.shenqianye.samsara.Samsara")
                    || imported.startsWith("com.shenqianye.samsara.config.");
            if (!allowed) {
                violations.add(relative + " import " + imported);
            }
        });
        return violations;
    }

    private static String importedSamsaraClass(String code) {
        for (String line : code.split("\n", -1)) {
            var matcher = SAMSARA_PACKAGE_IMPORT.matcher(line);
            if (matcher.find()) {
                return matcher.group(2);
            }
        }
        return null;
    }

    private static int lineOf(String code, String needle) {
        String[] lines = code.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(needle)) {
                return i + 1;
            }
        }
        return -1;
    }

    private static int firstMatchLine(String code, Pattern pattern) {
        String[] lines = code.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (pattern.matcher(lines[i]).find()) {
                return i + 1;
            }
        }
        return -1;
    }

    private interface SourceVisitor {
        void visit(String relative, String code);
    }

    private static void forEachSource(Path root, SourceVisitor visitor) {
        Path base = root.toAbsolutePath().normalize();
        try (Stream<Path> files = Files.walk(base)) {
            files.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .forEach(file -> visitor.visit(
                            base.relativize(file).toString().replace('\\', '/'),
                            stripComments(read(file))));
        } catch (IOException e) {
            throw new IllegalStateException("无法扫描源码目录: " + base, e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new IllegalStateException("无法读取 " + file, e);
        }
    }

    /** 去掉注释但保留字符串字面量：注释里的示例写法不应触发守卫。 */
    static String stripComments(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean lineComment = false;
        boolean blockComment = false;
        boolean string = false;
        boolean character = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            char next = i + 1 < text.length() ? text.charAt(i + 1) : '\0';
            if (lineComment) {
                if (c == '\n') {
                    lineComment = false;
                    out.append(c);
                }
                continue;
            }
            if (blockComment) {
                if (c == '*' && next == '/') {
                    blockComment = false;
                    i++;
                }
                continue;
            }
            if (string || character) {
                out.append(c);
                if (c == '\\') {
                    if (i + 1 < text.length()) {
                        out.append(text.charAt(++i));
                    }
                } else if (string && c == '"') {
                    string = false;
                } else if (character && c == '\'') {
                    character = false;
                }
                continue;
            }
            if (c == '/' && next == '/') {
                lineComment = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                blockComment = true;
                i++;
                continue;
            }
            if (c == '"') {
                string = true;
            } else if (c == '\'') {
                character = true;
            }
            out.append(c);
        }
        return out.toString();
    }

    private void write(String relative, String content) throws IOException {
        Path file = tmp.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static Path sourceRoot() {
        Path dir = Path.of("").toAbsolutePath().normalize();
        for (int i = 0; i < 4 && dir != null; i++) {
            Path candidate = dir.resolve("src").resolve("main").resolve("java");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到 src/main/java（当前目录 "
                + Path.of("").toAbsolutePath() + "）");
    }
}
