package com.rootopathy.careos.prototype.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rootopathy.careos.prototype.domain.PrototypeScreen;
import org.junit.jupiter.api.Test;

class PrototypeRegistryTest {
    @Test
    void exposesAllApprovedPrototypeSlots() {
        var screens = new PrototypeRegistry().all();
        assertThat(screens).hasSize(195);
        assertThat(screens).extracting(PrototypeScreen::id)
                .contains(
                        "M1-01", "M1-23",
                        "M2-01", "M2-29",
                        "P3-01", "P3-16",
                        "P4-01", "P4-15",
                        "P5-01", "P5-12",
                        "COS-01", "COS-27",
                        "P7-01", "P7-11",
                        "P8-01", "P8-10",
                        "P9-01", "P9-12",
                        "P10-01", "P10-09",
                        "P11-01", "P11-11",
                        "P12-01", "P12-10",
                        "P13-01", "P13-10");
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
        assertThat(screens.stream()
                        .filter(screen -> screen.id().startsWith("P7-"))
                        .map(PrototypeScreen::module))
                .containsOnly("M7");
        assertThat(screens.stream()
                        .filter(screen -> screen.id().startsWith("P8-"))
                        .map(PrototypeScreen::module))
                .containsOnly("M8");
        assertThat(screens.stream()
                        .filter(screen -> screen.id().startsWith("P9-"))
                        .map(PrototypeScreen::module))
                .containsOnly("M9");
        assertThat(screens.stream()
                        .filter(screen -> screen.id().startsWith("P10-"))
                        .map(PrototypeScreen::module))
                .containsOnly("M10");
        assertThat(screens.stream()
                        .filter(screen -> screen.id().startsWith("P11-"))
                        .map(PrototypeScreen::module))
                .containsOnly("M11");
        assertThat(screens.stream()
                        .filter(screen -> screen.id().startsWith("P12-"))
                        .map(PrototypeScreen::module))
                .containsOnly("M12");
        assertThat(screens.stream()
                        .filter(screen -> screen.id().startsWith("P13-"))
                        .map(PrototypeScreen::module))
                .containsOnly("M13");
    }
}
