package com.rootopathy.careos.administration.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class OperatingHoursDstPolicyTest {
  private static final Instant MARCH_START = Instant.parse("2026-03-01T00:00:00Z");
  private static final Instant NOVEMBER_START = Instant.parse("2026-11-01T00:00:00Z");

  @Test
  void rejectsWeeklyBoundaryInsideSpringForwardGap() {
    var weekly = List.of(new OperatingHoursStore.Interval(7, 150, 240, false));

    assertThrows(
        IllegalArgumentException.class,
        () -> OperatingHoursDstPolicy.validate(
            ZoneId.of("Europe/Paris"), MARCH_START, NOVEMBER_START, weekly, List.of()));
  }

  @Test
  void rejectsDatedBoundaryInsideFallBackOverlap() {
    var exceptions = List.of(new OperatingHoursStore.ExceptionDay(
        LocalDate.of(2026, 10, 25),
        false,
        "Seasonal coverage",
        "hours.seasonal",
        List.of(new OperatingHoursStore.ExceptionInterval(150, 240, false))));

    assertThrows(
        IllegalArgumentException.class,
        () -> OperatingHoursDstPolicy.validate(
            ZoneId.of("Europe/Paris"), MARCH_START, NOVEMBER_START, List.of(), exceptions));
  }

  @Test
  void acceptsUnambiguousBoundariesAcrossTransitionsAndFixedOffsetZones() {
    var weekly = List.of(new OperatingHoursStore.Interval(7, 540, 1020, false));

    assertDoesNotThrow(() -> OperatingHoursDstPolicy.validate(
        ZoneId.of("Europe/Paris"), MARCH_START, null, weekly, List.of()));
    assertDoesNotThrow(() -> OperatingHoursDstPolicy.validate(
        ZoneId.of("Asia/Kolkata"), MARCH_START, null, weekly, List.of()));
  }
}
