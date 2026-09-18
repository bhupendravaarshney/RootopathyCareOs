import type { CareOsApiClient } from '../../api/client';

export type AdministrationClient = Pick<
  CareOsApiClient,
  | 'approveOrganizationMembershipChange'
  | 'approveOrganizationOwnerTransfer'
  | 'executeOrganizationMembershipChange'
  | 'executeOrganizationOwnerTransfer'
  | 'getAdministrationReadiness'
  | 'getOrganizationProfile'
  | 'listOrganizationMemberships'
  | 'requestOrganizationMembershipChange'
  | 'requestOrganizationOwnerTransfer'
  | 'updateOrganizationProfile'
>;
