package com.rootopathy.careos.prototype.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rootopathy.careos.prototype.domain.PrototypeScreen;
import org.junit.jupiter.api.Test;

class PrototypeRegistryTest {
    @Test
    void exposesAllApprovedPrototypeSlots() {
        var screens = new PrototypeRegistry().all();
        assertThat(screens).hasSize(122);
        assertThat(screens).extracting(PrototypeScreen::id)
                .contains(
                        "M1-01", "M1-23",
                        "M2-01", "M2-29",
                        "P3-01", "P3-16",
                        "P4-01", "P4-15",
                        "P5-01", "P5-12",
                        "COS-01", "COS-27");
        assertThat(screens.stream()
                        .filter(screen -> screen.id().startsWith("P3-"))
                        .map(PrototypeScreen::module))
                .containsOnly("M3");
        assertThat(screens.stream()
                        .filter(screen -> screen.id().startsWith("P4-"))
                        .map(PrototypeScreen::module))
                .containsOnly("M4");
        assertThat(screens.stream()
                        .filter(screen -> screen.id().startsWith("P5-"))
                        .map(PrototypeScreen::module))
                .containsOnly("M5");
    }
}
