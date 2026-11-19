package com.shenqianye.samsara.lottery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 旧世界乐透历史只导入已结算轮次，不合并 fund/state/claims。 */
class LotteryHistoryMigrationTest {

    @TempDir
    Path tmp;

    @Test
    void importsOnlySettledRoundsAndKeepsSources() throws Exception {
        Path world = tmp.resolve("world");
        Path topDays = world.resolve("samsara/lottery/days");
        write(topDays.resolve("game-100.json"), "{\"dayId\":\"game-100\",\"state\":\"SETTLED\"}");
        write(topDays.resolve("game-101.json"), "{\"dayId\":\"game-101\",\"state\":\"BETTING\"}");
        write(world.resolve("samsara/lottery/fund.json"), "{\"items\":[\"x\"]}");

        Path layer = world.resolve("samsara/stack/stack_0");
        write(layer.resolve("stack.json"), "{\"id\":\"stack_0\"}");
        write(layer.resolve("lottery/days/game-090.json"),
                "{\"dayId\":\"game-090\",\"state\":\"CANCELLED\"}");

        Path shared = tmp.resolve("server/samsara/shared/lottery");
        LotteryHistoryMigration.Result result = LotteryHistoryMigration.merge(world, shared);

        assertEquals(2, result.imported(), result.summary());
        assertEquals(1, result.skipped(), result.summary());
        assertEquals(0, result.failed(), result.summary());
        assertTrue(Files.isRegularFile(shared.resolve("days/game-100.json")));
        assertTrue(Files.isRegularFile(shared.resolve("days/game-090.json")));
        assertFalse(Files.exists(shared.resolve("days/game-101.json")), "未结算轮次不能导入");
        assertFalse(Files.exists(shared.resolve("fund.json")), "不能合并基金");
        assertTrue(Files.isRegularFile(topDays.resolve("game-100.json")), "源文件必须保留");
        assertTrue(Files.isRegularFile(layer.resolve("lottery/days/game-090.json")), "源文件必须保留");

        LotteryHistoryMigration.Result second = LotteryHistoryMigration.merge(world, shared);
        assertEquals(0, second.imported(), "重复启动不得重复导入");
        assertEquals(3, second.skipped(), second.summary());
    }

    @Test
    void corruptOldFileIsCountedAndDoesNotStopOthers() throws Exception {
        Path world = tmp.resolve("world");
        Path days = world.resolve("samsara/lottery/days");
        write(days.resolve("game-1.json"), "not-json");
        write(days.resolve("game-2.json"), "{\"state\":\"SETTLED\"}");

        LotteryHistoryMigration.Result result = LotteryHistoryMigration.merge(
                world, tmp.resolve("shared/lottery"));

        assertEquals(1, result.imported(), result.summary());
        assertEquals(1, result.failed(), result.summary());
    }

    private static void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }
}
