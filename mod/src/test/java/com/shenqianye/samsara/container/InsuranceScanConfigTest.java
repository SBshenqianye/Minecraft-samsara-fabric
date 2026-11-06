package com.shenqianye.samsara.container;

import com.shenqianye.samsara.config.ConfigEntry;
import com.shenqianye.samsara.config.ConfigSchema;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InsuranceScanConfigTest {
    @Test
    void newInsuranceScanFlagsDefaultToTrue() {
        Map<String, ConfigEntry> entries = ConfigSchema.entries(ConfigSchema.INSURANCE).stream()
                .collect(Collectors.toMap(ConfigEntry::path, entry -> entry));
        assertEquals(Boolean.TRUE, entries.get("scanOfflinePlayers").defaultValue());
        assertEquals(Boolean.TRUE, entries.get("scanOtherDimensions").defaultValue());
        assertEquals(Boolean.TRUE, entries.get("useContainerIndex").defaultValue());
    }
}
