package com.rootopathy.careos.administration.domain;
import java.time.Instant;import java.util.List;import java.util.UUID;
public record AuditEvidencePage(UUID organizationId,Instant asOf,List<Item> items,int pageSize,boolean hasMore,String nextCursor){public record Item(UUID eventId,Instant occurredAt,UUID actorId,String operationKey,String eventName,int schemaVersion,String subjectType,UUID subjectId,String outcome,String risk,String correlationId,boolean redacted){} }
