package com.rootopathy.careos.administration.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record OperatingHoursDirectory(UUID organizationId, String targetType, UUID targetId, boolean canManage, List<Batch> batches, Instant evaluatedAt) {
 public record Batch(UUID batchId,String timezone,Instant effectiveFrom,Instant effectiveTo,String status,long lockVersion,List<Interval> intervals,List<ExceptionDay> exceptions) {}
 public record Interval(int weekday,int startMinute,int endMinute,boolean endsNextDay) {}
 public record ExceptionDay(LocalDate localDate,boolean closed,String label,String reasonCode,List<ExceptionInterval> intervals) {}
 public record ExceptionInterval(int startMinute,int endMinute,boolean endsNextDay) {}
}
