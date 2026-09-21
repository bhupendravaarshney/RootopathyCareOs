package com.rootopathy.careos.administration.application;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

final class OperatingHoursDstPolicy {
  private static final int OPEN_ENDED_VALIDATION_YEARS = 8;

  private OperatingHoursDstPolicy() {}

  static void validate(
      ZoneId zone,
      Instant effectiveFrom,
      Instant effectiveTo,
      List<OperatingHoursStore.Interval> weekly,
      List<OperatingHoursStore.ExceptionDay> exceptions) {
    var rules = zone.getRules();
    if (rules.isFixedOffset()) return;

    var firstDate = effectiveFrom.atZone(zone).toLocalDate();
    var lastDate = effectiveTo == null
        ? firstDate.plusYears(OPEN_ENDED_VALIDATION_YEARS)
        : effectiveTo.atZone(zone).toLocalDate();
    var cursor = effectiveFrom.minusNanos(1);
    while (true) {
      var transition = rules.nextTransition(cursor);
      if (transition == null || !transition.getInstant().isBefore(endExclusive(effectiveTo, zone, lastDate))) {
        break;
      }
      var transitionDate = transition.getDateTimeBefore().toLocalDate();
      if (!transitionDate.isBefore(firstDate.minusDays(1))
          && !transitionDate.isAfter(lastDate.plusDays(1))) {
        validateWeeklyOccurrences(zone, transitionDate, firstDate, lastDate, weekly);
      }
      cursor = transition.getInstant().plusNanos(1);
    }

    for (var exception : exceptions) {
      for (var interval : exception.intervals()) {
        validateBoundary(zone, exception.localDate(), interval.startMinute());
        validateBoundary(
            zone,
            exception.localDate().plusDays(interval.endsNextDay() ? 1 : 0),
            interval.endMinute());
      }
    }
  }

  private static Instant endExclusive(Instant effectiveTo, ZoneId zone, LocalDate lastDate) {
    return effectiveTo == null
        ? lastDate.plusDays(1).atStartOfDay(zone).toInstant()
        : effectiveTo;
  }

  private static void validateWeeklyOccurrences(
      ZoneId zone,
      LocalDate transitionDate,
      LocalDate firstDate,
      LocalDate lastDate,
      List<OperatingHoursStore.Interval> weekly) {
    for (var startDate : List.of(transitionDate.minusDays(1), transitionDate)) {
      if (startDate.isBefore(firstDate) || startDate.isAfter(lastDate)) continue;
      for (var interval : weekly) {
        if (DayOfWeek.of(interval.weekday()) != startDate.getDayOfWeek()) continue;
        validateBoundary(zone, startDate, interval.startMinute());
        validateBoundary(
            zone,
            startDate.plusDays(interval.endsNextDay() ? 1 : 0),
            interval.endMinute());
      }
    }
  }

  private static void validateBoundary(ZoneId zone, LocalDate date, int minuteOfDay) {
    var time = LocalTime.of(minuteOfDay / 60, minuteOfDay % 60);
    LocalDateTime boundary = LocalDateTime.of(date, time);
    if (zone.getRules().getValidOffsets(boundary).size() != 1) {
      throw new IllegalArgumentException(
          "operating-hours boundary is ambiguous or unavailable at a timezone transition");
    }
  }
}
