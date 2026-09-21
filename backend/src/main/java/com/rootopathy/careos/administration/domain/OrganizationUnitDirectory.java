package com.rootopathy.careos.administration.domain;
import java.time.Instant; import java.util.List; import java.util.UUID;
public record OrganizationUnitDirectory(UUID organizationId,UUID facilityId,boolean canManage,boolean canManageLifecycle,List<Unit> units,Instant evaluatedAt){
 public record Unit(UUID unitId,UUID parentId,String unitCode,String unitType,String name,Instant effectiveFrom,Instant effectiveTo,String status,long lockVersion,Instant createdAt,Instant updatedAt){}
}
