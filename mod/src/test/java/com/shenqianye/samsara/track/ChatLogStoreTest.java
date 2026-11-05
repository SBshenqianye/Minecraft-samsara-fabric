package com.shenqianye.samsara.track;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 聊天记录 JSONL：追加、按窗口/身份过滤、坏行容错。 */
class ChatLogStoreTest {
    @TempDir
    Path tmp;

    private static final UUID ALICE =
            UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB =
            UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    void appendAndReadWindowFiltersByTimeAndUuid() {
        Path root = tmp.resolve("chat");
        long t0 = 1761667200000L;
        ChatLogStore.append(root, 3, new ChatLogStore.Entry(t0, ALICE, "Alice",
                "minecraft:overworld", 1, 64, 2, "hello"));
        ChatLogStore.append(root, 3, new ChatLogStore.Entry(t0 + 1_000L, BOB, "Bob",
                "minecraft:overworld", 3, 64, 4, "hi"));

        List<ChatLogStore.Entry> all = ChatLogStore.readRange(root, 3, t0, t0 + 5_000L, null);
        assertEquals(2, all.size());
        assertEquals("hello", all.get(0).message());

        List<ChatLogStore.Entry> alice = ChatLogStore.readRange(root, 3, t0, t0 + 5_000L,
                List.of(ALICE));
        assertEquals(1, alice.size());
        assertEquals("Alice", alice.get(0).name());
    }

    @Test
    void readRangeIgnoresMalformedLinesAndOtherCycles() throws Exception {
        Path root = tmp.resolve("chat");
        long t0 = 1761667200000L;
        ChatLogStore.append(root, 3, new ChatLogStore.Entry(t0, ALICE, "Alice",
                "minecraft:overworld", 1, 64, 2, "ok"));
        Path file = ChatLogStore.fileFor(root, 3, com.shenqianye.samsara.track.view.ViewTime.dayOf(t0));
        Files.writeString(file, Files.readString(file) + "not json\n");

        assertEquals(1, ChatLogStore.readRange(root, 3, t0 - 1, t0 + 1, null).size());
        assertTrue(ChatLogStore.readRange(root, 4, t0 - 1, t0 + 1, null).isEmpty());
    }
}
