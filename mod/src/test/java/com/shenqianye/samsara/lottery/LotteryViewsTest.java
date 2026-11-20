package com.shenqianye.samsara.lottery;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 乐透 record 视图的 JSON 契约：字段名与旧 Map 输出一致，空值不落键。 */
class LotteryViewsTest {

    private static final Gson GSON = new Gson();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(Object value) {
        return GSON.fromJson(GSON.toJson(value), Map.class);
    }

    @Test
    void resultKeepsLegacyFieldNames() {
        LotteryViews.Result result = new LotteryViews.Result("2026-11-19", 7, 2, 1200.5, 60.0,
                true, false, "", List.of("Alice"), 1763600000000L);
        Map<String, Object> out = json(result);
        assertEquals(Set.of("day", "deathCount", "winnerCount", "pot", "rake", "rollover",
                "refunded", "refundReason", "winners", "at"), out.keySet());
        assertEquals("2026-11-19", out.get("day"));
        assertEquals(List.of("Alice"), out.get("winners"));
    }

    @Test
    void publicResultOmitsRakeAndTimestamp() {
        LotteryViews.PublicResult result = new LotteryViews.PublicResult("2026-11-19", 7, 2,
                1200.5, false, true, List.of("A***e"));
        Map<String, Object> out = json(result);
        assertEquals(Set.of("day", "deathCount", "winnerCount", "pot", "rollover", "refunded",
                "winners"), out.keySet());
        assertFalse(out.containsKey("rake"));
        assertFalse(out.containsKey("at"));
    }

    @Test
    void statusKeepsStringMyTickets() {
        LotteryViews.Status status = new LotteryViews.Status("2026-11-20", "2026-11-19",
                "23:00", "22:30", 10.0, 3, 2, "3 张 / 5 张", null, true);
        Map<String, Object> out = json(status);
        assertEquals(Set.of("bettingDay", "countingDay", "drawTime", "cutoff", "pot", "tickets",
                "players", "myTickets", "enabled"), out.keySet());
        assertEquals("3 张 / 5 张", out.get("myTickets"));
    }

    @Test
    void hiddenPublicModelOmitsEmptySections() {
        LotteryViews.PublicModel model = new LotteryViews.PublicModel(true, "user", false,
                null, null, null, null, null, null, null);
        Map<String, Object> out = json(model);
        assertEquals(Set.of("ok", "scope", "enabled"), out.keySet());
        assertFalse(out.containsKey("round"));
    }

    @Test
    void publicModelKeepsSectionOrderAndInsurance() {
        LotteryViews.Round round = new LotteryViews.Round("2026-11-20", "23:00", "22:30",
                1.0, 2.0, 3.0, 4, 5, List.of(Map.of("id", "minecraft:stone")));
        LotteryViews.PublicModel model = new LotteryViews.PublicModel(true, "user", true, round,
                null, 2.0, null, null, null, Map.of("total", 1));
        Map<String, Object> out = json(model);
        assertEquals(List.of("ok", "scope", "enabled", "round", "rollover", "insurance"),
                List.copyOf(new LinkedHashSet<>(out.keySet())));
        assertEquals(List.of("day", "drawTime", "cutoff", "ownPot", "seedValue", "pot", "tickets",
                        "players", "items"),
                List.copyOf(((Map<String, Object>) out.get("round")).keySet()));
    }

    @Test
    void writeErrorKeepsStatusField() {
        Map<String, Object> out = json(new LotteryViews.WriteError(false, 400, "bad"));
        assertEquals(Set.of("ok", "status", "error"), out.keySet());
        assertTrue(out.containsKey("status"));
    }

    @Test
    void outcomeHttpStatusFollowsOk() {
        assertEquals(200, new LotteryViews.Message(true, "ok").httpStatus());
        assertEquals(400, new LotteryViews.Error(false, "boom").httpStatus());
        assertEquals(400, new LotteryViews.WriteError(false, 400, "bad").httpStatus());
    }
}
