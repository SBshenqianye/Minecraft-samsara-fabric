package com.shenqianye.samsara.track;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 客户端假载具：类型白名单、固定水平姿态与视距常量。 */
class FakeVehicleTest {
    @Test
    void acceptsKnownRideables() {
        assertTrue(FakeVehicle.vehicleLikePath("oak_boat"));
        assertTrue(FakeVehicle.vehicleLikePath("bamboo_raft"));
        assertTrue(FakeVehicle.vehicleLikePath("minecart"));
        assertTrue(FakeVehicle.vehicleLikePath("horse"));
        assertTrue(FakeVehicle.vehicleLikePath("skeleton_horse"));
        assertTrue(FakeVehicle.vehicleLikePath("llama"));
        assertTrue(FakeVehicle.vehicleLikePath("donkey"));
        assertTrue(FakeVehicle.vehicleLikePath("mule"));
        assertTrue(FakeVehicle.vehicleLikePath("camel"));
        assertTrue(FakeVehicle.vehicleLikePath("pig"));
        assertTrue(FakeVehicle.vehicleLikePath("strider"));
    }

    @Test
    void rejectsNonVehicles() {
        assertFalse(FakeVehicle.vehicleLikePath("player"));
        assertFalse(FakeVehicle.vehicleLikePath("armor_stand"));
        assertFalse(FakeVehicle.vehicleLikePath("zombie"));
        assertFalse(FakeVehicle.vehicleLikePath(null));
    }

    @Test
    void pitchIsFixedHorizontal() {
        assertEquals(0.0f, FakeVehicle.VEHICLE_PITCH, 1e-9);
        assertEquals(128.0, FakeVehicle.VIEW_RANGE, 1e-9);
    }
}
