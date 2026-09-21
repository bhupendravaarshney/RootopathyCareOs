package com.rootopathy.careos.administration.domain;
import java.time.Instant;import java.util.List;import java.util.UUID;
public record OperatingHoursOverview(UUID organizationId,boolean canManage,List<Batch> batches,Instant evaluatedAt){public record Batch(UUID batchId,String targetType,UUID targetId,String timezone,Instant effectiveFrom,Instant effectiveTo,String status,long lockVersion){} }
