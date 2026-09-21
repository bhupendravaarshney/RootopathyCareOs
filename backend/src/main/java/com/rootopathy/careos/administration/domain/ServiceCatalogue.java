package com.rootopathy.careos.administration.domain;
import java.time.Instant;import java.util.List;import java.util.UUID;
public record ServiceCatalogue(UUID organizationId,boolean canManage,boolean canManageLifecycle,List<Definition> services,Instant evaluatedAt){public record Definition(UUID serviceId,String serviceCode,String displayName,String clinicalName,String description,String codingSystem,String codingCode,UUID ownerResponsibilityId,String status,String retirementReason,long lockVersion,Instant createdAt,Instant updatedAt){}}
