package com.rootopathy.careos.administration.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditEvidenceDetail(UUID organizationId,UUID eventId,Instant occurredAt,Instant recordedAt,
    UUID actorId,String operationKey,String eventName,int schemaVersion,String subjectType,UUID subjectId,
    String outcome,String risk,String correlationId,String purposeCode,String reasonProjection,
    Map<String,Object> payload,boolean redacted,String registryVersion) {}
