package com.rootopathy.careos.prototype.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rootopathy.careos.prototype.domain.PrototypeScreen;
import org.junit.jupiter.api.Test;

class PrototypeRegistryTest {
    @Test
    void exposesAllApprovedPrototypeSlots() {
        var screens = new PrototypeRegistry().all();
        assertThat(screens).hasSize(79);
        assertThat(screens).extracting(PrototypeScreen::id)
                .contains("M1-01", "M1-23", "M2-01", "M2-29", "COS-01", "COS-27");
    }
}
