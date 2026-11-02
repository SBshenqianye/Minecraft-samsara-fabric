package com.shenqianye.samsara.season;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 全员同意制纯逻辑（F-35~F-38）：计票、名单、过期、执行前校验与 JSON。 */
class SeasonAdvanceVoteTest {
    private static final long NOW = 1_700_000_000_000L;
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    private static Map<UUID, String> online(Object... pairs) {
        Map<UUID, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((UUID) pairs[i], (String) pairs[i + 1]);
        }
        return map;
    }

    private static SeasonAdvanceVote sameVote() {
        return SeasonAdvanceVote.create(SeasonAdvanceVote.MODE_SAME, A, "A",
                online(A, "A", B, "B"), "world", null, "normal", false, NOW);
    }

    @Test
    void rejectsWhenAdminOnline() {
        assertEquals(SeasonAdvanceVote.StartCheck.ADMIN_ONLINE,
                SeasonAdvanceVote.checkStart(true, true, 2, false));
    }

    @Test
    void rejectsWhenOnlyOnePlayerOnline() {
        assertEquals(SeasonAdvanceVote.StartCheck.NOT_ENOUGH_PLAYERS,
                SeasonAdvanceVote.checkStart(true, false, 1, false));
    }

    @Test
    void rejectsNonPlayerSource() {
        assertEquals(SeasonAdvanceVote.StartCheck.NOT_PLAYER,
                SeasonAdvanceVote.checkStart(false, false, 2, false));
    }

    @Test
    void existingPendingIsReportedWithoutOverwrite() {
        SeasonAdvanceVote vote = sameVote();
        assertEquals(SeasonAdvanceVote.StartCheck.ALREADY_PENDING,
                SeasonAdvanceVote.checkStart(true, false, 2, true));
        assertEquals(vote.id, vote.id);
        assertEquals(1, vote.votes.size());
    }

    @Test
    void createAddsAllOnlineAndAutoVotesInitiator() {
        SeasonAdvanceVote vote = SeasonAdvanceVote.create(SeasonAdvanceVote.MODE_SAME, A, "A",
                online(A, "A", B, "B", C, "C"), "world", null, "normal", false, NOW);
        assertEquals(3, vote.eligible.size());
        assertTrue(vote.eligible.containsKey(A.toString()));
        assertTrue(vote.eligible.containsKey(B.toString()));
        assertTrue(vote.eligible.containsKey(C.toString()));
        assertEquals(1, vote.votes.size());
        assertTrue(vote.votes.containsKey(A.toString()));
        assertEquals(NOW, vote.createdAt);
        assertEquals(NOW + SeasonAdvanceVote.TTL_MS, vote.expiresAt);
        assertEquals(List.of("B", "C"), vote.missingNames(online(A, "A", B, "B", C, "C")));
    }

    @Test
    void notAllAgreedCannotExecute() {
        SeasonAdvanceVote vote = sameVote();
        assertFalse(vote.canExecute(online(A, "A", B, "B"), NOW));
        assertEquals(SeasonAdvanceVote.AgreeResult.VOTED,
                vote.agree(B, "B", NOW + 1));
        assertTrue(vote.canExecute(online(A, "A", B, "B"), NOW + 1));
    }

    @Test
    void allAgreedOnlyExecutesOnce() {
        SeasonAdvanceVote vote = sameVote();
        vote.agree(B, "B", NOW + 1);
        assertTrue(vote.canExecute(online(A, "A", B, "B"), NOW + 1));
        vote.markPassed();
        assertEquals(SeasonAdvanceVote.STATUS_PASSED, vote.status);
        assertFalse(vote.canExecute(online(A, "A", B, "B"), NOW + 2));
    }

    @Test
    void duplicateAgreeIsIdempotent() {
        SeasonAdvanceVote vote = sameVote();
        assertEquals(SeasonAdvanceVote.AgreeResult.VOTED, vote.agree(B, "B", NOW + 1));
        assertEquals(SeasonAdvanceVote.AgreeResult.ALREADY_VOTED,
                vote.agree(B, "B", NOW + 2));
        assertEquals(2, vote.votes.size());
    }

    @Test
    void newPlayerMustAgreeAfterJoining() {
        SeasonAdvanceVote vote = sameVote();
        vote.agree(B, "B", NOW + 1);
        assertTrue(vote.allAgreed(online(A, "A", B, "B")));

        List<String> added = vote.addEligible(online(C, "C"));
        assertEquals(List.of("C"), added);
        assertFalse(vote.allAgreed(online(A, "A", B, "B", C, "C")));
        assertEquals(List.of("C"), vote.missingNames(online(A, "A", B, "B", C, "C")));

        assertEquals(SeasonAdvanceVote.AgreeResult.VOTED,
                vote.agree(C, "C", NOW + 2));
        assertTrue(vote.allAgreed(online(A, "A", B, "B", C, "C")));
    }

    @Test
    void agreeAddsCurrentOnlinePlayerWhoWasNotEligible() {
        SeasonAdvanceVote vote = sameVote();
        vote.agree(B, "B", NOW + 1);
        assertEquals(SeasonAdvanceVote.AgreeResult.ADDED_AND_VOTED,
                vote.agree(C, "C", NOW + 2));
        assertTrue(vote.eligible.containsKey(C.toString()));
        assertTrue(vote.votes.containsKey(C.toString()));
        assertTrue(vote.allAgreed(online(A, "A", B, "B", C, "C")));
    }

    @Test
    void leavingKeepsAlreadyCastVotes() {
        SeasonAdvanceVote vote = sameVote();
        vote.agree(B, "B", NOW + 1);
        // B 离线；eligible 仍保留 B，已投票也保留
        assertTrue(vote.allAgreed(online(A, "A")));
        assertTrue(vote.votes.containsKey(B.toString()));
        assertTrue(vote.eligible.containsKey(B.toString()));
    }

    @Test
    void expiresAfter48HoursUsingCreatedAt() {
        SeasonAdvanceVote vote = sameVote();
        assertFalse(vote.expired(NOW + SeasonAdvanceVote.TTL_MS - 1));
        assertTrue(vote.expired(NOW + SeasonAdvanceVote.TTL_MS));
        assertEquals(SeasonAdvanceVote.AgreeResult.EXPIRED,
                vote.agree(B, "B", NOW + SeasonAdvanceVote.TTL_MS));
        assertEquals(0L, vote.remainingMs(NOW + SeasonAdvanceVote.TTL_MS + 1000));
    }

    @Test
    void jsonUsesAtomicWriteAndRestoresPendingVote(@TempDir Path dir) {
        Path file = dir.resolve("season-advance.json");
        SeasonAdvanceVote vote = sameVote();
        vote.agree(B, "B", NOW + 1);

        assertTrue(vote.save(file));
        assertTrue(Files.exists(file));
        assertFalse(Files.exists(dir.resolve("season-advance.json.tmp")));

        SeasonAdvanceVote restored = SeasonAdvanceVote.load(file);
        assertNotNull(restored);
        assertEquals(vote.id, restored.id);
        assertEquals(SeasonAdvanceVote.MODE_SAME, restored.mode);
        assertEquals("A", restored.initiatorName);
        assertEquals("world", restored.layerId);
        assertEquals(2, restored.eligible.size());
        assertEquals(2, restored.votes.size());
        assertEquals(vote.expiresAt, restored.expiresAt);
    }

    @Test
    void corruptJsonFallsBackWithoutDeletingFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("season-advance.json");
        Files.writeString(file, "{ this is not json", StandardCharsets.UTF_8);

        assertNull(SeasonAdvanceVote.load(file));
        assertTrue(Files.exists(file), "损坏文件必须保留，不能自动删除");
    }

    @Test
    void nonPendingFileIsNotRestored(@TempDir Path dir) {
        Path file = dir.resolve("season-advance.json");
        SeasonAdvanceVote vote = sameVote();
        vote.markPassed();
        assertTrue(vote.save(file));

        assertNull(SeasonAdvanceVote.load(file));
        assertTrue(Files.exists(file));
    }

    @Test
    void sameModeFailsWhenTargetLayerMissing() {
        SeasonAdvanceVote vote = sameVote();
        String problem = vote.validateExecution(false, false, false, false, false, NOW);
        assertNotNull(problem);
        assertTrue(problem.contains("目标栈层"));
        assertTrue(problem.contains("world"));
    }

    @Test
    void executionFailureKeepsPendingVoteAndError() {
        SeasonAdvanceVote vote = sameVote();
        vote.markExecutionError("目标栈层 world 已不在世界栈中");
        assertEquals(SeasonAdvanceVote.STATUS_PENDING, vote.status);
        assertEquals("目标栈层 world 已不在世界栈中", vote.executionError);
        assertTrue(vote.isPending());
    }

    @Test
    void newModeDefaultsToRandomSeedNormalAndNoArchive() {
        SeasonAdvanceVote vote = SeasonAdvanceVote.create(SeasonAdvanceVote.MODE_NEW, A, "A",
                online(A, "A", B, "B"), "world", null, null, false, NOW);
        assertNull(vote.seed);
        assertEquals("normal", vote.levelType);
        assertFalse(vote.archiveData);
        assertNull(vote.validateExecution(true, true, false, false, false, NOW));
    }

    @Test
    void newModeRequiresTopLayer() {
        SeasonAdvanceVote vote = SeasonAdvanceVote.create(SeasonAdvanceVote.MODE_NEW, A, "A",
                online(A, "A", B, "B"), "world", 42L, "flat", true, NOW);
        String problem = vote.validateExecution(true, false, false, false, false, NOW);
        assertNotNull(problem);
        assertTrue(problem.contains("栈顶"));
    }

    @Test
    void executionRejectsPendingResetOrNewWorld() {
        SeasonAdvanceVote vote = sameVote();
        assertNotNull(vote.validateExecution(true, true, true, false, false, NOW));
        assertNotNull(vote.validateExecution(true, true, false, true, false, NOW));
        assertNotNull(vote.validateExecution(true, true, false, false, true, NOW));
    }

    @Test
    void voteCommandsDefaultToUser() {
        var defaults = com.shenqianye.samsara.permission.PermissionDefaults.entries();
        assertEquals("user", defaults.get("season advance"));
        assertEquals("user", defaults.get("season agree"));
        assertEquals("user", defaults.get("season vote-status"));
    }

    @Test
    void expiresAtIsAlwaysRecomputedFromCreatedAtOnLoad(@TempDir Path dir) {
        Path file = dir.resolve("season-advance.json");
        SeasonAdvanceVote vote = sameVote();
        assertTrue(vote.save(file));
        String json = null;
        try {
            json = Files.readString(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        // 手工把 expiresAt 改大，加载后仍按 createdAt + 48h
        String tampered = json.replace("\"expiresAt\": " + vote.expiresAt,
                "\"expiresAt\": " + (vote.expiresAt + 999_999L));
        try {
            Files.writeString(file, tampered, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        SeasonAdvanceVote restored = SeasonAdvanceVote.load(file);
        assertNotNull(restored);
        assertEquals(NOW + SeasonAdvanceVote.TTL_MS, restored.expiresAt);
    }
}
