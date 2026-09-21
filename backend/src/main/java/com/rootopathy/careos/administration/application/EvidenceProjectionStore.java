package com.rootopathy.careos.administration.application;

import com.rootopathy.careos.administration.domain.*;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.time.Instant;
import java.util.*;

public interface EvidenceProjectionStore {
  List<ConfigurationHistoryPage.Item> history(AuthorizedTenantContext context,HistoryQuery query);
  List<AuditEvidencePage.Item> audit(AuthorizedTenantContext context,AuditQuery query);
  AuditEvidenceDetail detail(AuthorizedTenantContext context,UUID eventId,String purposeCode);
  record HistoryQuery(Instant asOf,Instant from,Instant to,String status,String changeType,UUID actorId,String subjectType,UUID subjectId,String correlationId,int limit,EvidenceCursorCodec.Position after){}
  record AuditQuery(Instant asOf,Instant from,Instant to,UUID actorId,String operation,String eventName,Integer schemaVersion,String subjectType,UUID subjectId,String outcome,String risk,String correlationId,int limit,EvidenceCursorCodec.Position after){}
}
