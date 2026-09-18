package com.rootopathy.careos.administration.application;

import com.rootopathy.careos.administration.domain.FacilityDirectory;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.util.UUID;

public interface FacilityStore {
    FacilityDirectory directory(AuthorizedTenantContext context,String query,String status);
    Result create(AuthorizedTenantContext context,Draft draft);
    Result update(AuthorizedTenantContext context,UUID facilityId,long revision,Draft draft);
    record Draft(String facilityCode,String legalName,String displayName,String facilityType,UUID addressId,UUID contactId,String timezone) {}
    record Result(FacilityDirectory directory,UUID facilityId,long lockVersion) {}
}
