package com.shenqianye.samsara.track.view;

import com.shenqianye.samsara.track.TrackEvents;
import com.shenqianye.samsara.track.TrackLayout;
import com.shenqianye.samsara.track.TrackStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回看只读性自动断言：加载 .bin/.evt 前后文件 SHA256 完全一致。
 * 对应设计承诺“回看不写 track/diff/cutoffs/trophies/season”。
 */
class ViewTrackReaderReadOnlyTest {
    @TempDir
    Path tmp;

    @Test
    void loadingTrackDoesNotModifyBinOrEvt() throws Exception {
        UUID uuid = UUID.fromString("11111111-2222-3333-4444-555555555555");
        LocalDate day = LocalDate.now();
        Path bin = TrackLayout.dayDir(tmp, 1, day).resolve(uuid + ".bin");
        Files.createDirectories(bin.getParent());
        TrackStore.Segment segment = new TrackStore.Segment(uuid, "minecraft:overworld",
                System.currentTimeMillis(), 5.0);
        long now = System.currentTimeMillis();
        segment.samples.add(new TrackStore.Sample(now, 0, 64, 0, 0, 0, 0));
        segment.samples.add(new TrackStore.Sample(now + 200, 1, 64, 0, 90, 0, TrackStore.FLAG_RIDING));
        TrackStore.write(bin, List.of(segment));

        Path evt = TrackLayout.evtFileFor(bin);
        Files.write(evt, TrackEvents.header());

        String binHash = sha256(bin);
        String evtHash = sha256(evt);
        long binSize = Files.size(bin);
        long evtSize = Files.size(evt);

        ViewTrackReader.LoadResult result = ViewTrackReader.load(
                List.of(tmp), 1, List.of(uuid), now - 1, now + 1000,
                (root, owner, cycle) -> null);

        assertTrue(result.totalSamples() >= 2, "应读到两个样本");
        assertEquals(binHash, sha256(bin), ".bin 不得被回看改写");
        assertEquals(evtHash, sha256(evt), ".evt 不得被回看改写");
        assertEquals(binSize, Files.size(bin));
        assertEquals(evtSize, Files.size(evt));
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
    }
}
