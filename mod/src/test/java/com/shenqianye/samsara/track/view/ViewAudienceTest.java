package com.shenqianye.samsara.track.view;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回看人偶可见性：默认“个人摄像头”，只给登记过的观看者；
 * 空集合 = 对所有人隐藏（宁可不显示，也不漏给别人）。
 */
class ViewAudienceTest {
    private static final UUID VIEWER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID OTHER = UUID.fromString("66666666-7777-8888-9999-000000000000");

    @Test
    void allowsOnlyRegisteredViewer() {
        ViewAudience audience = new ViewAudience();
        audience.allow(VIEWER);

        assertTrue(audience.allows(VIEWER));
        assertFalse(audience.allows(OTHER));
        assertEquals(1, audience.size());
    }

    @Test
    void emptyAudienceHidesEveryone() {
        ViewAudience audience = new ViewAudience();

        assertTrue(audience.isEmpty());
        assertFalse(audience.allows(VIEWER));
        assertFalse(audience.allows(OTHER));
    }

    @Test
    void allowAndDenyAreIdempotent() {
        ViewAudience audience = new ViewAudience();
        audience.allow(VIEWER);
        audience.allow(VIEWER);
        assertEquals(1, audience.size());

        audience.deny(VIEWER);
        audience.deny(VIEWER);
        assertFalse(audience.allows(VIEWER));
        assertTrue(audience.isEmpty());
    }

    @Test
    void sharedViewerIsIndependent() {
        ViewAudience audience = new ViewAudience();
        audience.allow(VIEWER);
        audience.allow(OTHER);
        assertEquals(2, audience.size());

        audience.deny(OTHER);
        assertTrue(audience.allows(VIEWER));
        assertFalse(audience.allows(OTHER));
    }

    @Test
    void nullIsNeverVisible() {
        ViewAudience audience = new ViewAudience();
        audience.allow(null);
        audience.deny(null);

        assertFalse(audience.allows(null));
        assertTrue(audience.isEmpty());
    }
}
