package com.shenqianye.samsara.claim;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F-01：方向图案 + 图标图案才识别为圈地旗。 */
class ClaimPatternRulesTest {

    @Test
    void directionPatternAloneIsNotClaim() {
        assertFalse(ClaimPatternRules.isClaimBanner(List.of("stripe_top")));
        assertFalse(ClaimPatternRules.isClaimBanner(List.of("border", "cross")));
        assertTrue(ClaimPatternRules.isDirectionPattern("stripe_top"));
        assertFalse(ClaimPatternRules.isIconPattern("stripe_top"));
    }

    @Test
    void iconPatternAloneIsNotClaim() {
        assertFalse(ClaimPatternRules.isClaimBanner(List.of("creeper")));
        assertTrue(ClaimPatternRules.isIconPattern("creeper"));
        assertFalse(ClaimPatternRules.isDirectionPattern("creeper"));
    }

    @Test
    void directionPlusIconIsClaim() {
        assertTrue(ClaimPatternRules.isClaimBanner(List.of("stripe_top", "creeper")));
        assertTrue(ClaimPatternRules.isClaimBanner(List.of("border", "skull", "cross")));
        assertTrue(ClaimPatternRules.isClaimBanner(List.of("flower", "stripe_left")));
    }

    @Test
    void baseLayerDoesNotCountAsIcon() {
        assertFalse(ClaimPatternRules.isClaimBanner(List.of("base", "stripe_top")));
        assertFalse(ClaimPatternRules.isClaimBanner(List.of("banner_base", "border")));
        assertFalse(ClaimPatternRules.isClaimBanner(List.of("base")));
    }

    @Test
    void unknownDecorativePatternCountsAsIcon() {
        assertTrue(ClaimPatternRules.isClaimBanner(List.of("stripe_middle", "triangle_bottom")));
    }

    @Test
    void emptyOrNullIsNotClaim() {
        assertFalse(ClaimPatternRules.isClaimBanner(null));
        assertFalse(ClaimPatternRules.isClaimBanner(List.of()));
        assertFalse(ClaimPatternRules.hasIconPattern(null));
        assertFalse(ClaimPatternRules.hasIconPattern(List.of()));
    }
}
