package com.shenqianye.samsara.track.view;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 快照恢复：幂等 + 外部干预守卫。 */
class ViewRestoreTest {

    private static ViewSnapshot.Data data(String dim, double x, double y, double z,
                                          String mode, boolean flying, boolean mayfly,
                                          boolean invulnerable) {
        return new ViewSnapshot.Data(dim, x, y, z, 90, 10, 0, 0, 0, 0,
                mode, flying, mayfly, invulnerable, 0.05f, 0.1f, List.of());
    }

    private static final class FakeSink implements ViewRestore.Sink {
        ViewSnapshot.Data data;
        int writes;

        FakeSink(ViewSnapshot.Data data) {
            this.data = data;
        }

        @Override
        public ViewSnapshot.Data read() {
            return data;
        }

        @Override
        public void write(ViewSnapshot.Data target, boolean restorePosition, boolean restoreMode) {
            writes++;
            ViewSnapshot.Data c = data;
            data = new ViewSnapshot.Data(
                    restorePosition ? target.dimension() : c.dimension(),
                    restorePosition ? target.x() : c.x(),
                    restorePosition ? target.y() : c.y(),
                    restorePosition ? target.z() : c.z(),
                    restorePosition ? target.yaw() : c.yaw(),
                    restorePosition ? target.pitch() : c.pitch(),
                    target.vx(), target.vy(), target.vz(), target.fallDistance(),
                    restoreMode ? target.gameMode() : c.gameMode(),
                    target.flying(), target.mayfly(), target.invulnerable(),
                    target.flyingSpeed(), target.walkingSpeed(), target.effects());
        }
    }

    @Test
    void restoreIsIdempotent() {
        ViewSnapshot.Data original = data("minecraft:overworld", 10, 64, 10,
                "SURVIVAL", false, false, false);
        ViewSnapshot.Data target = data("minecraft:overworld", 0, 65, 0,
                "CREATIVE", true, true, true);
        FakeSink sink = new FakeSink(original);
        ViewRestore.Gate gate = new ViewRestore.Gate("minecraft:overworld",
                target.x(), target.y(), target.z(), null, ViewRestore.MAX_DRIFT, true);

        ViewRestore.Result first = ViewRestore.restore(target, sink, gate);
        assertTrue(first.wrote());
        assertEquals(target, sink.data);

        ViewRestore.Result second = ViewRestore.restore(target, sink, gate);
        assertFalse(second.wrote(), "第二次恢复不应再写");
        assertEquals(target, sink.data);
        assertEquals(1, sink.writes);
    }

    @Test
    void externalDriftSkipsPositionButRestoresAbilities() {
        ViewSnapshot.Data current = data("minecraft:overworld", 100, 64, 0,
                "SURVIVAL", false, false, false);
        ViewSnapshot.Data target = data("minecraft:overworld", 0, 65, 0,
                "CREATIVE", true, true, true);
        FakeSink sink = new FakeSink(current);
        ViewRestore.Gate gate = new ViewRestore.Gate("minecraft:overworld",
                0, 65, 0, null, ViewRestore.MAX_DRIFT, true);

        ViewRestore.Result r = ViewRestore.restore(target, sink, gate);
        assertTrue(r.skippedPosition());
        assertTrue(r.wrote());
        assertEquals(100, sink.data.x(), 1e-9, "外部传送位置必须保留");
        assertTrue(sink.data.flying());
        assertTrue(sink.data.mayfly());
    }

    @Test
    void externalModeChangeIsNotOverwritten() {
        ViewSnapshot.Data current = data("minecraft:overworld", 0, 65, 0,
                "SURVIVAL", false, false, false);
        ViewSnapshot.Data target = data("minecraft:overworld", 0, 65, 0,
                "SPECTATOR", false, false, false);
        FakeSink sink = new FakeSink(current);
        ViewRestore.Gate gate = new ViewRestore.Gate("minecraft:overworld",
                0, 65, 0, "SPECTATOR", ViewRestore.MAX_DRIFT, true);

        ViewRestore.Result r = ViewRestore.restore(target, sink, gate);
        assertTrue(r.skippedMode());
        assertEquals("SURVIVAL", sink.data.gameMode());
    }
}
