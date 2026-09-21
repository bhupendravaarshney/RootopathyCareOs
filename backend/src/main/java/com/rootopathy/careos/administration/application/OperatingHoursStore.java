package com.rootopathy.careos.administration.application;

import com.rootopathy.careos.administration.domain.OperatingHoursDirectory;
import com.rootopathy.careos.administration.domain.OperatingHoursOverview;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface OperatingHoursStore {
 OperatingHoursOverview overview(AuthorizedTenantContext c);
 OperatingHoursDirectory directory(AuthorizedTenantContext context,String targetType,UUID targetId);
 MutationResult create(AuthorizedTenantContext context,Draft draft);
 MutationResult transition(AuthorizedTenantContext context,String targetType,UUID targetId,UUID batchId,long revision,String toState);
 record Draft(String targetType,UUID targetId,String timezone,Instant effectiveFrom,Instant effectiveTo,List<Interval> intervals,List<ExceptionDay> exceptions) {}
 record Interval(int weekday,int startMinute,int endMinute,boolean endsNextDay) {}
 record ExceptionDay(LocalDate localDate,boolean closed,String label,String reasonCode,List<ExceptionInterval> intervals) {}
 record ExceptionInterval(int startMinute,int endMinute,boolean endsNextDay) {}
 record MutationResult(UUID batchId,String fromState,String toState,Instant effectiveFrom,OperatingHoursDirectory directory) {}
}
