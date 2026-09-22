import type {
  AcceptInvitationData,
  AcceptInvitationResponse,
  AcceptInvitationResponses,
  ActivateOrganizationUnitData,
  ActivateOrganizationUnitResponse,
  ActivateOrganizationUnitResponses,
  ActivateServiceLocationData,
  ActivateServiceLocationResponse,
  ActivateServiceLocationResponses,
  ApproveMfaAdministrativeResetData,
  ApproveMfaAdministrativeResetResponse,
  ApproveMfaAdministrativeResetResponses,
  ApproveOrganizationMembershipChangeData,
  ApproveOrganizationMembershipChangeResponse,
  ApproveOrganizationMembershipChangeResponses,
  ApproveOrganizationOwnerTransferData,
  ApproveOrganizationOwnerTransferResponse,
  ApproveOrganizationOwnerTransferResponses,
  CompleteMfaChallengeData,
  CompleteMfaChallengeResponse,
  CompleteMfaChallengeResponses,
  CompletePasswordResetData,
  CompletePasswordResetResponse,
  CompletePasswordResetResponses,
  CloseOrganizationUnitData,
  CloseOrganizationUnitResponse,
  CloseOrganizationUnitResponses,
  CloseServiceLocationData,
  CloseServiceLocationResponse,
  CloseServiceLocationResponses,
  CreateFacilityDraftData,
  CreateFacilityDraftResponse,
  CreateFacilityDraftResponses,
  CreateOrganizationUnitDraftData,
  CreateOrganizationUnitDraftResponse,
  CreateOrganizationUnitDraftResponses,
  CreateServiceLocationDraftData,
  CreateServiceLocationDraftResponse,
  CreateServiceLocationDraftResponses,
  UpdateServiceLocationDraftData,
  UpdateServiceLocationDraftResponse,
  UpdateServiceLocationDraftResponses,
  ReparentServiceLocationData,
  ReparentServiceLocationResponse,
  ReparentServiceLocationResponses,
  ReparentOrganizationUnitData,
  ReparentOrganizationUnitResponse,
  ReparentOrganizationUnitResponses,
  ReactivateOrganizationUnitData,
  ReactivateOrganizationUnitResponse,
  ReactivateOrganizationUnitResponses,
  ReactivateServiceLocationData,
  ReactivateServiceLocationResponse,
  ReactivateServiceLocationResponses,
  UpdateOrganizationUnitDraftData,
  UpdateOrganizationUnitDraftResponse,
  UpdateOrganizationUnitDraftResponses,
  UpdateFacilityDraftData,
  UpdateFacilityDraftResponse,
  UpdateFacilityDraftResponses,
  SubmitFacilityDraftData,
  SubmitFacilityDraftResponse,
  SubmitFacilityDraftResponses,
  SuspendOrganizationUnitData,
  SuspendOrganizationUnitResponse,
  SuspendOrganizationUnitResponses,
  SuspendServiceLocationData,
  SuspendServiceLocationResponse,
  SuspendServiceLocationResponses,
  CreateOrganizationAddressData,
  CreateOrganizationAddressResponse,
  CreateOrganizationAddressResponses,
  CreateOrganizationContactData,
  CreateOrganizationContactResponse,
  CreateOrganizationContactResponses,
  CreateOrganizationIdentifierData,
  CreateOrganizationIdentifierResponse,
  CreateOrganizationIdentifierResponses,
  CreateOrganizationGovernanceResponsibilityData,
  CreateOrganizationGovernanceResponsibilityResponse,
  CreateOrganizationGovernanceResponsibilityResponses,
  CsrfToken,
  EndOrganizationAddressData,
  EndOrganizationAddressResponse,
  EndOrganizationAddressResponses,
  EndOrganizationContactData,
  EndOrganizationContactResponse,
  EndOrganizationContactResponses,
  EndOrganizationGovernanceResponsibilityData,
  EndOrganizationGovernanceResponsibilityResponse,
  EndOrganizationGovernanceResponsibilityResponses,
  FacilityDirectory,
  ExecuteMfaAdministrativeResetData,
  ExecuteMfaAdministrativeResetResponse,
  ExecuteMfaAdministrativeResetResponses,
  ExecuteOrganizationMembershipChangeData,
  ExecuteOrganizationMembershipChangeResponse,
  ExecuteOrganizationMembershipChangeResponses,
  ExecuteOrganizationOwnerTransferData,
  ExecuteOrganizationOwnerTransferResponse,
  ExecuteOrganizationOwnerTransferResponses,
  GetAdministrationReadinessData,
  GetAdministrationReadinessResponse,
  GetAdministrationReadinessResponses,
  GetAuthenticationSessionData,
  GetAuthenticationSessionResponse,
  GetAuthenticationSessionResponses,
  GetFacilityDirectoryData,
  GetFacilityDirectoryResponse,
  GetFacilityDirectoryResponses,
  GetOrganizationUnitDirectoryData,
  GetOrganizationUnitDirectoryResponse,
  GetOrganizationUnitDirectoryResponses,
  GetServiceLocationDirectoryData,
  GetServiceLocationDirectoryResponse,
  GetServiceLocationDirectoryResponses,
  GetOrganizationProfileData,
  GetOrganizationProfileResponse,
  GetOrganizationProfileResponses,
  GetOrganizationInternationalSettingsData,
  GetOrganizationInternationalSettingsResponse,
  GetOrganizationInternationalSettingsResponses,
  GetOrganizationGovernanceDirectoryData,
  GetOrganizationGovernanceDirectoryResponse,
  GetOrganizationGovernanceDirectoryResponses,
  GetSystemSummaryData,
  GetSystemSummaryResponse,
  GetSystemSummaryResponses,
  GetWorkforceScreenData,
  GetWorkforceScreenResponse,
  IssueCsrfTokenData,
  IssueCsrfTokenResponse,
  IssueCsrfTokenResponses,
  IssueInvitationData,
  IssueInvitationResponse,
  IssueInvitationResponses,
  ListOrganizationIdentifiersData,
  ListOrganizationIdentifiersResponse,
  ListOrganizationIdentifiersResponses,
  ListOrganizationContactsData,
  ListOrganizationContactsResponse,
  ListOrganizationContactsResponses,
  ListPrototypeScreensData,
  ListPrototypeScreensResponse,
  ListPrototypeScreensResponses,
  ListOrganizationMembershipsData,
  ListOrganizationMembershipsResponse,
  ListOrganizationMembershipsResponses,
  ListSelectableOrganizationsData,
  ListSelectableOrganizationsResponse,
  ListSelectableOrganizationsResponses,
  LoginData,
  LoginResponse,
  LoginResponses,
  LogoutData,
  LogoutResponse,
  LogoutResponses,
  AuditEvidenceDetail,
  AuditEvidencePage,
  ConfigurationActivationDirectory,
  ConfigurationActivationRequest,
  ConfigurationDecisionRequest,
  ConfigurationHistoryPage,
  ConfigurationResultRequest,
  ConfigurationValidationRequest,
  CredentialDocumentMetadata,
  EvidenceExportAccessRequest,
  EvidenceExportAccessResponse,
  EvidenceExportDecisionRequest,
  EvidenceExportDirectory,
  EvidenceExportRequest,
  IdentifierSchemeDirectory,
  IdentifierSchemeWriteRequest,
  OperatingHoursBatchRequest,
  OperatingHoursDirectory,
  OperatingHoursOverview,
  PerformWorkforceActionResponse,
  Problem,
  RegenerateRecoveryCodesData,
  RegenerateRecoveryCodesResponse,
  RegenerateRecoveryCodesResponses,
  RequestPasswordResetData,
  RequestPasswordResetResponses,
  RequestMfaAdministrativeResetData,
  RequestMfaAdministrativeResetResponse,
  RequestMfaAdministrativeResetResponses,
  RequestOrganizationMembershipChangeData,
  RequestOrganizationMembershipChangeResponse,
  RequestOrganizationMembershipChangeResponses,
  RequestOrganizationOwnerTransferData,
  RequestOrganizationOwnerTransferResponse,
  RequestOrganizationOwnerTransferResponses,
  RevokeOrganizationIdentifierData,
  RevokeOrganizationIdentifierResponse,
  RevokeOrganizationIdentifierResponses,
  RevokeInvitationData,
  RevokeInvitationResponse,
  RevokeInvitationResponses,
  SelectOrganizationData,
  SelectOrganizationResponse,
  SelectOrganizationResponses,
  ServiceAssignmentDirectory,
  ServiceAssignmentWriteRequest,
  ServiceCatalogue,
  ServiceWriteRequest,
  ScheduleOrganizationInternationalSettingsData,
  ScheduleOrganizationInternationalSettingsResponse,
  ScheduleOrganizationInternationalSettingsResponses,
  StartMfaEnrollmentData,
  StartMfaEnrollmentResponse,
  StartMfaEnrollmentResponses,
  SupersedeOrganizationIdentifierData,
  SupersedeOrganizationIdentifierResponse,
  SupersedeOrganizationIdentifierResponses,
  SupersedeOrganizationGovernanceResponsibilityData,
  SupersedeOrganizationGovernanceResponsibilityResponse,
  SupersedeOrganizationGovernanceResponsibilityResponses,
  SupersedeOrganizationAddressData,
  SupersedeOrganizationAddressResponse,
  SupersedeOrganizationAddressResponses,
  SupersedeOrganizationContactData,
  SupersedeOrganizationContactResponse,
  SupersedeOrganizationContactResponses,
  UpdateOrganizationProfileData,
  UpdateOrganizationProfileResponse,
  UpdateOrganizationProfileResponses,
  UpdateOrganizationIdentifierData,
  UpdateOrganizationIdentifierResponse,
  UpdateOrganizationIdentifierResponses,
  VerifyMfaEnrollmentData,
  VerifyMfaEnrollmentResponse,
  VerifyMfaEnrollmentResponses,
  VerifyOrganizationIdentifierData,
  VerifyOrganizationIdentifierResponse,
  VerifyOrganizationIdentifierResponses,
  VerifyOrganizationContactData,
  VerifyOrganizationContactResponse,
  VerifyOrganizationContactResponses,
  VerifyRecentAuthenticationData,
  VerifyRecentAuthenticationResponse,
  VerifyRecentAuthenticationResponses,
  WorkforceActionRequest,
  WorkforceScreen,
} from './generated';
import {
  auditEvidenceDetailValidator,
  auditEvidencePageValidator,
  configurationActivationDirectoryValidator,
  configurationHistoryPageValidator,
  evidenceExportAccessValidator,
  evidenceExportDirectoryValidator,
  identifierSchemeDirectoryValidator,
  operatingHoursDirectoryValidator,
  operatingHoursOverviewValidator,
  serviceAssignmentDirectoryValidator,
  serviceCatalogueValidator,
} from './live-administration-contracts';
import {
  workforceCredentialDocumentAccessValidator,
  workforceEvidenceAccessValidator,
  workforceExportAccessValidator,
  workforceImpactPreviewValidator,
  workforceScreenValidator,
  type WorkforceCredentialDocumentAccessRequest,
  type WorkforceCredentialDocumentAccessResponse,
  type WorkforceEvidenceAccessRequest,
  type WorkforceEvidenceAccessResponse,
  type WorkforceExportAccessRequest,
  type WorkforceExportAccessResponse,
  type WorkforceImpactPreviewResponse,
} from './workforce-contracts';

const ACCEPTED_RESPONSE_TYPES = 'application/json, application/problem+json';
const CORRELATION_ID_PATTERN = /^[A-Za-z0-9._:-]{1,128}$/;
const IDEMPOTENCY_KEY_PATTERN = /^[A-Za-z0-9._:-]{16,128}$/;
const STRONG_ETAG_PATTERN = /^"[A-Za-z0-9._:-]{1,128}"$/;
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const MEMBERSHIP_CURSOR_PATTERN = /^[A-Za-z0-9_-]{1,512}$/;
const MEMBERSHIP_ROLE_KEY_PATTERN = /^[a-z][a-z0-9]*([._:-][a-z0-9]+)*$/;
const MEMBERSHIP_ACCESS_STATES = new Set([
  'active',
  'scheduled',
  'suspended',
  'expired',
  'revoked',
]);
const MAX_RETRY_AFTER_SECONDS = 86_400;
const SESSION_EXPIRY_HEADER = 'X-CareOS-Session-Expires-In';
const MAX_SESSION_EXPIRY_SECONDS = 2_147_483_647;

type ResponseStatus<T> = Extract<keyof T, number>;

const endpoints = {
  acceptInvitation: {
    path: '/api/v1/auth/invitation-acceptances' satisfies AcceptInvitationData['url'],
    successStatuses: [200, 201] satisfies readonly ResponseStatus<AcceptInvitationResponses>[],
  },
  approveMfaAdministrativeReset: {
    path: '/api/v1/organizations/{organizationId}/users/{targetUserId}/mfa-reset-requests/{approvalId}/approvals' satisfies ApproveMfaAdministrativeResetData['url'],
    successStatuses: [
      200,
    ] satisfies readonly ResponseStatus<ApproveMfaAdministrativeResetResponses>[],
  },
  approveOrganizationMembershipChange: {
    path: '/api/v1/organizations/{organizationId}/memberships/{membershipId}/change-requests/{approvalId}/approvals' satisfies ApproveOrganizationMembershipChangeData['url'],
    successStatuses: [
      200,
    ] satisfies readonly ResponseStatus<ApproveOrganizationMembershipChangeResponses>[],
  },
  approveOrganizationOwnerTransfer: {
    path: '/api/v1/organizations/{organizationId}/memberships/{membershipId}/owner-transfer-requests/{approvalId}/approvals' satisfies ApproveOrganizationOwnerTransferData['url'],
    successStatuses: [
      200,
    ] satisfies readonly ResponseStatus<ApproveOrganizationOwnerTransferResponses>[],
  },
  completeMfaChallenge: {
    path: '/api/v1/auth/mfa/challenges' satisfies CompleteMfaChallengeData['url'],
    successStatuses: [200] satisfies readonly ResponseStatus<CompleteMfaChallengeResponses>[],
  },
  completePasswordReset: {
    path: '/api/v1/auth/password-resets' satisfies CompletePasswordResetData['url'],
    successStatuses: [204] satisfies readonly ResponseStatus<CompletePasswordResetResponses>[],
  },
  createOrganizationAddress: {
    path: '/api/v1/organizations/{organizationId}/addresses' satisfies CreateOrganizationAddressData['url'],
    successStatuses: [201] satisfies readonly ResponseStatus<CreateOrganizationAddressResponses>[],
  },
  createOrganizationContact: {
    path: '/api/v1/organizations/{organizationId}/contacts' satisfies CreateOrganizationContactData['url'],
    successStatuses: [201] satisfies readonly ResponseStatus<CreateOrganizationContactResponses>[],
  },
  createOrganizationIdentifier: {
    path: '/api/v1/organizations/{organizationId}/identifiers' satisfies CreateOrganizationIdentifierData['url'],
    successStatuses: [
      201,
    ] satisfies readonly ResponseStatus<CreateOrganizationIdentifierResponses>[],
  },
  endOrganizationAddress: {
    path: '/api/v1/organizations/{organizationId}/addresses/{addressId}/endings' satisfies EndOrganizationAddressData['url'],
    successStatuses: [200] satisfies readonly ResponseStatus<EndOrganizationAddressResponses>[],
  },
  endOrganizationContact: {
    path: '/api/v1/organizations/{organizationId}/contacts/{contactId}/endings' satisfies EndOrganizationContactData['url'],
    successStatuses: [200] satisfies readonly ResponseStatus<EndOrganizationContactResponses>[],
  },
  executeMfaAdministrativeReset: {
    path: '/api/v1/organizations/{organizationId}/users/{targetUserId}/mfa-reset-requests/{approvalId}/executions' satisfies ExecuteMfaAdministrativeResetData['url'],
    successStatuses: [
      200,
    ] satisfies readonly ResponseStatus<ExecuteMfaAdministrativeResetResponses>[],
  },
  executeOrganizationMembershipChange: {
    path: '/api/v1/organizations/{organizationId}/memberships/{membershipId}/change-requests/{approvalId}/executions' satisfies ExecuteOrganizationMembershipChangeData['url'],
    successStatuses: [
      200,
    ] satisfies readonly ResponseStatus<ExecuteOrganizationMembershipChangeResponses>[],
  },
  executeOrganizationOwnerTransfer: {
    path: '/api/v1/organizations/{organizationId}/memberships/{membershipId}/owner-transfer-requests/{approvalId}/executions' satisfies ExecuteOrganizationOwnerTransferData['url'],
    successStatuses: [
      200,
    ] satisfies readonly ResponseStatus<ExecuteOrganizationOwnerTransferResponses>[],
  },
  getAdministrationReadiness: {
    path: '/api/v1/organizations/{organizationId}/setup-readiness' satisfies GetAdministrationReadinessData['url'],
    successStatuses: [200] satisfies readonly ResponseStatus<GetAdministrationReadinessResponses>[],
  },
  getAuthenticationSession: {
    path: '/api/v1/auth/session' satisfies GetAuthenticationSessionData['url'],
    successStatuses: [200] satisfies readonly ResponseStatus<GetAuthenticationSessionResponses>[],
  },
  getOrganizationProfile: {
    path: '/api/v1/organizations/{organizationId}/profile' satisfies GetOrganizationProfileData['url'],
    successStatuses: [200] satisfies readonly ResponseStatus<GetOrganizationProfileResponses>[],
  },
  getFacilityDirectory: {
    path: '/api/v1/organizations/{organizationId}/facilities' satisfies GetFacilityDirectoryData['url'],
    successStatuses: [200] satisfies readonly ResponseStatus<GetFacilityDirectoryResponses>[],
  },
  getOrganizationUnitDirectory: {
    path: '/api/v1/organizations/{organizationId}/facilities/{facilityId}/units' satisfies GetOrganizationUnitDirectoryData['url'],
    successStatuses: [
      200,
    ] satisfies readonly ResponseStatus<GetOrganizationUnitDirectoryResponses>[],
  },
  getServiceLocationDirectory: {
    path: '/api/v1/organizations/{organizationId}/facilities/{facilityId}/locations' satisfies GetServiceLocationDirectoryData['url'],
    successStatuses: [
      200,
    ] satisfies readonly ResponseStatus<GetServiceLocationDirectoryResponses>[],
  },
  createServiceLocationDraft: {
    path: '/api/v1/organizations/{organizationId}/facilities/{facilityId}/locations' satisfies CreateServiceLocationDraftData['url'],
    successStatuses: [201] satisfies readonly ResponseStatus<CreateServiceLocationDraftResponses>[],
  },
  updateServiceLocationDraft: {
    path: '/api/v1/organizations/{organizationId}/facilities/{facilityId}/locations/{locationId}' satisfies UpdateServiceLocationDraftData['url'],
    successStatuses: [200] satisfies readonly ResponseStatus<UpdateServiceLocationDraftResponses>[],
  },
  reparentServiceLocation: {
    path: '/api/v1/organizations/{organizationId}/facilities/{facilityId}/locations/{locationId}/reparentings' satisfies ReparentServiceLocationData['url'],
    successStatuses: [200] satisfies readonly ResponseStatus<ReparentServiceLocationResponses>[],
  },
  activateServiceLocation: {
    path: '/api/v1/organizations/{organizationId}/facilities/{facilityId}/locations/{locationId}/activations' satisfies ActivateServiceLocationData['url'],
    successStatuses: [200] satisfies readonly ResponseStatus<ActivateServiceLocationResponses>[],
  },
  suspendServiceLocation: {
    path: '/api/v1/organizations/{organizationId}/facilities/{facilityId}/locations/{locationId}/suspensions' satisfies SuspendServiceLocationData['url'],
    successStatuses: [200] satisfies readonly ResponseStatus<SuspendServiceLocationResponses>[],
  },
  reactivateServiceLocation: {
    path: '/api/v1/organizations/{organizationId}/facilities/{facilityId}/locations/{locationId}/reactivations' satisfies ReactivateServiceLocationData['url'],
    successStatuses: [200] satisfies readonly ResponseStatus<ReactivateServiceLocationResponses>[],
  },
  closeServiceLocation: {
    path: '/api/v1/organizations/{organizationId}/facilities/{facilityId}/locations/{locationId}/closures' satisfies CloseServiceLocationData['url'],
    successStatuses: [200] satisfies readonly ResponseStatus<CloseServiceLocationResponses>[],
  },
  createOrganizationUnitDraft: {
    path: '/api/v1/organizations/{organizationId}/facilities/{facilityId}/units' satisfies CreateOrganizationUnitDraftData['url'],
    successStatuses: [
      201,
    ] satisfies readonly ResponseStatus<CreateOrganizationUnitDraftResponses>[],
  },
  updateOrganizationUnitDraft: {
    path: '/api/v1/organizations/{organizationId}/facilities/{facilityId}/units/{unitId}' satisfies UpdateOrganizationUnitDraftData['url'],
    successStatuses: [
      200,
    ] satisfies readonly ResponseStatus<UpdateOrganizationUnitDraftResponses>[],
  },
  reparentOrganizationUnit: {
    path: '/api/v1/organizations/{organizationId}/facilities/{facilityId}/units/{unitId}/reparentings' satisfies ReparentOrganizationUnitData['url'],
    successStatuses: [200] satisfies readonly ResponseStatus<ReparentOrganizationUnitResponses>[],
  },
  activateOrganizationUnit: {
    path: '/api/v1/organizations/{organizationId}/facilities/{facilityId}/units/{unitId}/activations' satisfies ActivateOrganizationUnitData['url'],
    successStatuses: [200] satisfies readonly ResponseStatus<ActivateOrganizationUnitResponses>[],
  },
  suspendOrganizationUnit: {
    path: '/api/v1/organizations/{organizationId}/facilities/{facilityId}/units/{unitId}/suspensions' satisfies SuspendOrganizationUnitData['url'],
    successStatuses: [200] satisfies readonly ResponseStatus<SuspendOrganizationUnitResponses>[],
  },
  reactivateOrganizationUnit: {
    path: '/api/v1/organizations/{organizationId}/facilities/{facilityId}/units/{unitId}/reactivations' satisfies ReactivateOrganizationUnitData['url'],
    successStatuses: [200] satisfies readonly ResponseStatus<ReactivateOrganizationUnitResponses>[],
  },
  closeOrganizationUnit: {
    path: '/api/v1/organizations/{organizationId}/facilities/{facilityId}/units/{unitId}/closures' satisfies CloseOrganizationUnitData['url'],
    successStatuses: [200] satisfies readonly ResponseStatus<CloseOrganizationUnitResponses>[],
  },
  createFacilityDraft: {
    path: '/api/v1/organizations/{organizationId}/facilities' satisfies CreateFacilityDraftData['url'],
    successStatuses: [201] satisfies readonly ResponseStatus<CreateFacilityDraftResponses>[],
  },
  updateFacilityDraft: {
    path: '/api/v1/organizations/{organizationId}/facilities/{facilityId}' satisfies UpdateFacilityDraftData['url'],
    successStatuses: [200] satisfies readonly ResponseStatus<UpdateFacilityDraftResponses>[],
  },
  submitFacilityDraft: {
    path: '/api/v1/organizations/{organizationId}/facilities/{facilityId}/submissions' satisfies SubmitFacilityDraftData['url'],
    successStatuses: [200] satisfies readonly ResponseStatus<SubmitFacilityDraftResponses>[],
  },
  getOrganizationInternationalSettings: {
    path: '/api/v1/organizations/{organizationId}/international-settings' satisfies GetOrganizationInternationalSettingsData['url'],
    successStatuses: [
      200,
    ] satisfies readonly ResponseStatus<GetOrganizationInternationalSettingsResponses>[],
  },
  getOrganizationGovernanceDirectory: {
    path: '/api/v1/organizations/{organizationId}/governance-responsibilities' satisfies GetOrganizationGovernanceDirectoryData['url'],
    successStatuses: [
      200,
    ] satisfies readonly ResponseStatus<GetOrganizationGovernanceDirectoryResponses>[],
  },
  createOrganizationGovernanceResponsibility: {
    path: '/api/v1/organizations/{organizationId}/governance-responsibilities' satisfies CreateOrganizationGovernanceResponsibilityData['url'],
    successStatuses: [
      201,
    ] satisfies readonly ResponseStatus<CreateOrganizationGovernanceResponsibilityResponses>[],
  },
  supersedeOrganizationGovernanceResponsibility: {
    path: '/api/v1/organizations/{organizationId}/governance-responsibilities/{responsibilityId}/supersessions' satisfies SupersedeOrganizationGovernanceResponsibilityData['url'],
    successStatuses: [
      200,
    ] satisfies readonly ResponseStatus<SupersedeOrganizationGovernanceResponsibilityResponses>[],
  },
  endOrganizationGovernanceResponsibility: {
    path: '/api/v1/organizations/{organizationId}/governance-responsibilities/{responsibilityId}/endings' satisfies EndOrganizationGovernanceResponsibilityData['url'],
    successStatuses: [
      200,
    ] satisfies readonly ResponseStatus<EndOrganizationGovernanceResponsibilityResponses>[],
  },
  getSystemSummary: {
    path: '/api/public/system-summary' satisfies GetSystemSummaryData['url'],
    successStatuses: [200] satisfies readonly ResponseStatus<GetSystemSummaryResponses>[],
  },
  issueCsrfToken: {
    path: '/api/v1/auth/csrf' satisfies IssueCsrfTokenData['url'],
    successStatuses: [200] satisfies readonly ResponseStatus<IssueCsrfTokenResponses>[],
  },
  issueInvitation: {
    path: '/api/v1/organizations/{organizationId}/invitations' satisfies IssueInvitationData['url'],
    successStatuses: [201] satisfies readonly ResponseStatus<IssueInvitationResponses>[],
  },
  listPrototypeScreens: {
    path: '/api/public/prototype-screens' satisfies ListPrototypeScreensData['url'],
    successStatuses: [200] satisfies readonly ResponseStatus<ListPrototypeScreensResponses>[],
  },
  listOrganizationMemberships: {
    path: '/api/v1/organizations/{organizationId}/memberships' satisfies ListOrganizationMembershipsData['url'],
    successStatuses: [
      200,
    ] satisfies readonly ResponseStatus<ListOrganizationMembershipsResponses>[],
  },
  listOrganizationIdentifiers: {
    path: '/api/v1/organizations/{organizationId}/identifiers' satisfies ListOrganizationIdentifiersData['url'],
    successStatuses: [
      200,
    ] satisfies readonly ResponseStatus<ListOrganizationIdentifiersResponses>[],
  },
  listOrganizationContacts: {
    path: '/api/v1/organizations/{organizationId}/contacts' satisfies ListOrganizationContactsData['url'],
    successStatuses: [200] satisfies readonly ResponseStatus<ListOrganizationContactsResponses>[],
  },
  listSelectableOrganizations: {
    path: '/api/v1/organizations' satisfies ListSelectableOrganizationsData['url'],
    successStatuses: [
      200,
    ] satisfies readonly ResponseStatus<ListSelectableOrganizationsResponses>[],
  },
  login: {
    path: '/api/v1/auth/login' satisfies LoginData['url'],
    successStatuses: [200, 202] satisfies readonly ResponseStatus<LoginResponses>[],
  },
  logout: {
    path: '/api/v1/auth/logout' satisfies LogoutData['url'],
    successStatuses: [204] satisfies readonly ResponseStatus<LogoutResponses>[],
  },
  regenerateRecoveryCodes: {
    path: '/api/v1/auth/mfa/recovery-codes' satisfies RegenerateRecoveryCodesData['url'],
    successStatuses: [200] satisfies readonly ResponseStatus<RegenerateRecoveryCodesResponses>[],
  },
  requestPasswordReset: {
    path: '/api/v1/auth/password-reset-requests' satisfies RequestPasswordResetData['url'],
    successStatuses: [202] satisfies readonly ResponseStatus<RequestPasswordResetResponses>[],
  },
  requestMfaAdministrativeReset: {
    path: '/api/v1/organizations/{organizationId}/users/{targetUserId}/mfa-reset-requests' satisfies RequestMfaAdministrativeResetData['url'],
    successStatuses: [
      201,
    ] satisfies readonly ResponseStatus<RequestMfaAdministrativeResetResponses>[],
  },
  requestOrganizationMembershipChange: {
    path: '/api/v1/organizations/{organizationId}/memberships/{membershipId}/change-requests' satisfies RequestOrganizationMembershipChangeData['url'],
    successStatuses: [
      201,
    ] satisfies readonly ResponseStatus<RequestOrganizationMembershipChangeResponses>[],
  },
  requestOrganizationOwnerTransfer: {
    path: '/api/v1/organizations/{organizationId}/memberships/{membershipId}/owner-transfer-requests' satisfies RequestOrganizationOwnerTransferData['url'],
    successStatuses: [
      201,
    ] satisfies readonly ResponseStatus<RequestOrganizationOwnerTransferResponses>[],
  },
  revokeInvitation: {
    path: '/api/v1/organizations/{organizationId}/invitations/{invitationId}/revocations' satisfies RevokeInvitationData['url'],
    successStatuses: [200] satisfies readonly ResponseStatus<RevokeInvitationResponses>[],
  },
  revokeOrganizationIdentifier: {
    path: '/api/v1/organizations/{organizationId}/identifiers/{identifierId}/revocations' satisfies RevokeOrganizationIdentifierData['url'],
    successStatuses: [
      200,
    ] satisfies readonly ResponseStatus<RevokeOrganizationIdentifierResponses>[],
  },
  selectOrganization: {
    path: '/api/v1/auth/organization-selections' satisfies SelectOrganizationData['url'],
    successStatuses: [200] satisfies readonly ResponseStatus<SelectOrganizationResponses>[],
  },
  scheduleOrganizationInternationalSettings: {
    path: '/api/v1/organizations/{organizationId}/international-settings' satisfies ScheduleOrganizationInternationalSettingsData['url'],
    successStatuses: [
      200,
    ] satisfies readonly ResponseStatus<ScheduleOrganizationInternationalSettingsResponses>[],
  },
  startMfaEnrollment: {
    path: '/api/v1/auth/mfa/enrollments' satisfies StartMfaEnrollmentData['url'],
    successStatuses: [200] satisfies readonly ResponseStatus<StartMfaEnrollmentResponses>[],
  },
  supersedeOrganizationIdentifier: {
    path: '/api/v1/organizations/{organizationId}/identifiers/{identifierId}/supersessions' satisfies SupersedeOrganizationIdentifierData['url'],
    successStatuses: [
      200,
    ] satisfies readonly ResponseStatus<SupersedeOrganizationIdentifierResponses>[],
  },
  supersedeOrganizationAddress: {
    path: '/api/v1/organizations/{organizationId}/addresses/{addressId}/supersessions' satisfies SupersedeOrganizationAddressData['url'],
    successStatuses: [
      201,
    ] satisfies readonly ResponseStatus<SupersedeOrganizationAddressResponses>[],
  },
  supersedeOrganizationContact: {
    path: '/api/v1/organizations/{organizationId}/contacts/{contactId}/supersessions' satisfies SupersedeOrganizationContactData['url'],
    successStatuses: [
      201,
    ] satisfies readonly ResponseStatus<SupersedeOrganizationContactResponses>[],
  },
  verifyMfaEnrollment: {
    path: '/api/v1/auth/mfa/enrollments/verification' satisfies VerifyMfaEnrollmentData['url'],
    successStatuses: [200] satisfies readonly ResponseStatus<VerifyMfaEnrollmentResponses>[],
  },
  verifyRecentAuthentication: {
    path: '/api/v1/auth/recent-authentications' satisfies VerifyRecentAuthenticationData['url'],
    successStatuses: [204] satisfies readonly ResponseStatus<VerifyRecentAuthenticationResponses>[],
  },
  updateOrganizationProfile: {
    path: '/api/v1/organizations/{organizationId}/profile' satisfies UpdateOrganizationProfileData['url'],
    successStatuses: [200] satisfies readonly ResponseStatus<UpdateOrganizationProfileResponses>[],
  },
  updateOrganizationIdentifier: {
    path: '/api/v1/organizations/{organizationId}/identifiers/{identifierId}' satisfies UpdateOrganizationIdentifierData['url'],
    successStatuses: [
      200,
    ] satisfies readonly ResponseStatus<UpdateOrganizationIdentifierResponses>[],
  },
  verifyOrganizationIdentifier: {
    path: '/api/v1/organizations/{organizationId}/identifiers/{identifierId}/verifications' satisfies VerifyOrganizationIdentifierData['url'],
    successStatuses: [
      200,
    ] satisfies readonly ResponseStatus<VerifyOrganizationIdentifierResponses>[],
  },
  verifyOrganizationContact: {
    path: '/api/v1/organizations/{organizationId}/contacts/{contactId}/verifications' satisfies VerifyOrganizationContactData['url'],
    successStatuses: [200] satisfies readonly ResponseStatus<VerifyOrganizationContactResponses>[],
  },
} as const;

function relativeEndpointPath(path: `/api/${string}`): string {
  return path.slice('/api'.length);
}

type FetchImplementation = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>;

export type ApiFailureKind = 'aborted' | 'contract' | 'http' | 'network';

export type ApiSuccess<T> = {
  correlationId: string;
  data: T;
  etag?: string;
  ok: true;
  sessionExpiresAt?: number;
  status: number;
};

export type ApiFailure = {
  correlationId: string;
  kind: ApiFailureKind;
  ok: false;
  problem: Problem;
  responseStatus?: number;
  retryAfterSeconds?: number;
  status: number;
};

export type ApiResult<T> = ApiSuccess<T> | ApiFailure;

export type ApiRequestOptions = {
  signal?: AbortSignal;
};

export type SessionLifecycleEvent =
  { expiresAt: number; type: 'deadline' } | { type: 'invalidated' };

export type CareOsApiClientOptions = {
  baseUrl?: string;
  correlationIdFactory?: () => string;
  fetch?: FetchImplementation;
  now?: () => number;
};

type RequestDescriptor = {
  body?: unknown;
  idempotencyKey?: string;
  ifMatch?: string;
  method: 'GET' | 'POST' | 'PUT';
  multipartBody?: FormData;
  path: string;
  responseBody: 'empty' | 'json';
  signal?: AbortSignal;
  successStatuses: readonly number[];
  validateResponse?: (value: unknown) => boolean;
};

function requireUuid(value: string, name: string): string {
  if (!UUID_PATTERN.test(value)) {
    throw new Error(`${name} must be a UUID.`);
  }
  return value;
}

function requireIdempotencyKey(value: string): string {
  if (!IDEMPOTENCY_KEY_PATTERN.test(value)) {
    throw new Error(
      'The idempotency key must contain 16 to 128 letters, digits, periods, underscores, colons, or hyphens.',
    );
  }
  return value;
}

function requireStrongEtag(value: string): string {
  if (!STRONG_ETAG_PATTERN.test(value)) {
    throw new Error('If-Match must contain a strong entity tag from the latest response.');
  }
  return value;
}

type WorkforceScreenQuery = NonNullable<GetWorkforceScreenData['query']>;

function requireWorkforceScreenId(value: string): string {
  if (!/^M2-(0[1-9]|1[0-9]|2[0-9])$/.test(value)) {
    throw new Error('screenId must identify a Module 2 screen from M2-01 through M2-29.');
  }
  return value;
}

function requireWorkforceActionKey(value: string): string {
  if (!/^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$/.test(value)) {
    throw new Error('actionKey has an invalid format.');
  }
  return value;
}

function workforceScreenQuery(query: WorkforceScreenQuery): string {
  const parameters = new URLSearchParams();
  if (query.memberId !== undefined) {
    parameters.set('memberId', requireUuid(query.memberId, 'memberId'));
  }
  if (query.q !== undefined) {
    if (typeof query.q !== 'string' || Array.from(query.q).length > 100) {
      throw new Error('Workforce search must contain at most 100 characters.');
    }
    const normalized = query.q.trim().normalize('NFC');
    if (normalized) parameters.set('q', normalized);
  }
  if (query.status !== undefined) {
    if (typeof query.status !== 'string' || Array.from(query.status).length > 40) {
      throw new Error('Workforce status must contain at most 40 characters.');
    }
    const normalized = query.status.trim().normalize('NFC');
    if (normalized) parameters.set('status', normalized);
  }
  if (query.limit !== undefined) {
    if (!Number.isInteger(query.limit) || query.limit < 1 || query.limit > 100) {
      throw new Error('Workforce page limit must be an integer from 1 to 100.');
    }
    parameters.set('limit', String(query.limit));
  }
  if (query.cursor !== undefined) {
    if (!/^[A-Za-z0-9_-]{1,2048}$/.test(query.cursor)) {
      throw new Error('Workforce page cursor has an invalid format.');
    }
    parameters.set('cursor', query.cursor);
  }
  const serialized = parameters.toString();
  return serialized ? `?${serialized}` : '';
}

type OrganizationMembershipQuery = NonNullable<ListOrganizationMembershipsData['query']>;

function organizationMembershipQuery(query: OrganizationMembershipQuery): string {
  const parameters = new URLSearchParams();
  if (query.search !== undefined) {
    if (typeof query.search !== 'string') {
      throw new Error('Membership search must be text.');
    }
    const search = query.search.trim().normalize('NFC');
    const length = Array.from(search).length;
    const hasControlCharacter = Array.from(search).some((character) => {
      const code = character.charCodeAt(0);
      return code <= 31 || code === 127;
    });
    if (length < 2 || length > 100 || hasControlCharacter) {
      throw new Error('Membership search must contain 2 to 100 valid characters.');
    }
    parameters.set('search', search);
  }
  if (query.state !== undefined) {
    if (typeof query.state !== 'string' || !MEMBERSHIP_ACCESS_STATES.has(query.state)) {
      throw new Error('Membership state is not allowed.');
    }
    parameters.set('state', query.state);
  }
  if (query.roleKey !== undefined) {
    if (
      typeof query.roleKey !== 'string' ||
      query.roleKey.length > 100 ||
      !MEMBERSHIP_ROLE_KEY_PATTERN.test(query.roleKey)
    ) {
      throw new Error('Membership role key has an invalid format.');
    }
    parameters.set('roleKey', query.roleKey);
  }
  if (query.limit !== undefined) {
    if (!Number.isInteger(query.limit) || query.limit < 1 || query.limit > 100) {
      throw new Error('Membership page limit must be an integer from 1 to 100.');
    }
    parameters.set('limit', String(query.limit));
  }
  if (query.cursor !== undefined) {
    if (typeof query.cursor !== 'string' || !MEMBERSHIP_CURSOR_PATTERN.test(query.cursor)) {
      throw new Error('Membership cursor has an invalid format.');
    }
    parameters.set('cursor', query.cursor);
  }
  const serialized = parameters.toString();
  return serialized ? `?${serialized}` : '';
}

function normalizeBaseUrl(value: string): string {
  const candidate = value.trim();
  if (!candidate || candidate.includes('?') || candidate.includes('#')) {
    throw new Error('The CareOS API base URL must not contain a query or fragment.');
  }

  const hasApiSuffix = (path: string) => path.replace(/\/+$/, '').endsWith('/api');

  if (candidate.startsWith('/')) {
    if (
      candidate.startsWith('//') ||
      candidate.split('/').some((segment) => segment === '.' || segment === '..') ||
      !hasApiSuffix(candidate)
    ) {
      throw new Error('The CareOS API base URL must be a local path ending in /api.');
    }
    return candidate.replace(/\/+$/, '');
  }

  let parsed: URL;
  try {
    parsed = new URL(candidate);
  } catch {
    throw new Error('The CareOS API base URL must be absolute or begin with /.');
  }

  const localHttpHosts = new Set(['127.0.0.1', '[::1]', 'localhost']);
  const secure = parsed.protocol === 'https:';
  const explicitLocalDevelopment =
    parsed.protocol === 'http:' && localHttpHosts.has(parsed.hostname);
  if (!secure && !explicitLocalDevelopment) {
    throw new Error('Absolute CareOS API URLs require HTTPS outside local development.');
  }
  if (parsed.username || parsed.password || !hasApiSuffix(parsed.pathname)) {
    throw new Error('The CareOS API base URL must end in /api and contain no credentials.');
  }

  parsed.pathname = parsed.pathname.replace(/\/+$/, '');
  return parsed.toString().replace(/\/$/, '');
}

function defaultCorrelationId(): string {
  if (!globalThis.crypto?.randomUUID) {
    throw new Error('CareOS requires crypto.randomUUID() for request correlation.');
  }
  return globalThis.crypto.randomUUID();
}

function clientProblem(
  status: number,
  code: string,
  title: string,
  detail: string,
  instance: string,
  correlationId: string,
): Problem {
  return {
    code,
    correlationId,
    detail,
    instance,
    status,
    title,
    type: 'about:blank',
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function parseProblem(value: unknown): Problem | undefined {
  if (!isRecord(value)) {
    return undefined;
  }

  const requiredStrings = ['type', 'title', 'detail', 'instance', 'code', 'correlationId'] as const;
  if (
    !requiredStrings.every((field) => typeof value[field] === 'string') ||
    typeof value.status !== 'number' ||
    !Number.isInteger(value.status) ||
    value.status < 400 ||
    value.status > 599
  ) {
    return undefined;
  }

  let errors: Problem['errors'];
  if (value.errors !== undefined) {
    if (
      !Array.isArray(value.errors) ||
      !value.errors.every(
        (error) =>
          isRecord(error) &&
          typeof error.path === 'string' &&
          typeof error.code === 'string' &&
          typeof error.message === 'string',
      )
    ) {
      return undefined;
    }
    errors = value.errors.map((error) => ({
      code: error.code as string,
      message: error.message as string,
      path: error.path as string,
    }));
  }

  return {
    code: value.code as string,
    correlationId: value.correlationId as string,
    detail: value.detail as string,
    ...(errors ? { errors } : {}),
    instance: value.instance as string,
    status: value.status as number,
    title: value.title as string,
    type: value.type as string,
  };
}

function retryAfterSeconds(headers: Headers): number | undefined {
  const value = headers.get('Retry-After')?.trim();
  if (!value || !/^\d{1,5}$/.test(value)) {
    return undefined;
  }
  const seconds = Number(value);
  return seconds >= 1 && seconds <= MAX_RETRY_AFTER_SECONDS ? seconds : undefined;
}

function sessionExpirySeconds(headers: Headers): number | undefined {
  const value = headers.get(SESSION_EXPIRY_HEADER)?.trim();
  if (!value || !/^\d{1,10}$/.test(value)) {
    return undefined;
  }
  const seconds = Number(value);
  return seconds <= MAX_SESSION_EXPIRY_SECONDS ? seconds : undefined;
}

function responseMediaType(headers: Headers): string {
  return headers.get('Content-Type')?.split(';', 1)[0]?.trim().toLowerCase() ?? '';
}

function validCsrfToken(value: unknown): value is CsrfToken {
  return (
    isRecord(value) &&
    value.headerName === 'X-XSRF-TOKEN' &&
    value.parameterName === '_csrf' &&
    typeof value.token === 'string' &&
    value.token.length >= 16 &&
    value.token.length <= 512 &&
    value.token.trim() === value.token &&
    !Array.from(value.token).some((character) => {
      const code = character.charCodeAt(0);
      return code <= 31 || code === 127;
    })
  );
}

function wasAborted(error: unknown): boolean {
  return isRecord(error) && error.name === 'AbortError';
}

export class CareOsApiClient {
  readonly #baseUrl: string;
  readonly #correlationIdFactory: () => string;
  readonly #fetch: FetchImplementation;
  readonly #now: () => number;
  readonly #sessionLifecycleListeners = new Set<(event: SessionLifecycleEvent) => void>();

  constructor(options: CareOsApiClientOptions = {}) {
    this.#baseUrl = normalizeBaseUrl(
      options.baseUrl ?? import.meta.env.VITE_API_BASE_URL ?? '/api',
    );
    this.#correlationIdFactory = options.correlationIdFactory ?? defaultCorrelationId;
    this.#fetch = options.fetch ?? globalThis.fetch.bind(globalThis);
    this.#now = options.now ?? Date.now;
  }

  subscribeSessionLifecycle(listener: (event: SessionLifecycleEvent) => void): () => void {
    this.#sessionLifecycleListeners.add(listener);
    return () => this.#sessionLifecycleListeners.delete(listener);
  }

  async #request<T>(descriptor: RequestDescriptor): Promise<ApiResult<T>> {
    const correlationId = this.#correlationIdFactory();
    if (!CORRELATION_ID_PATTERN.test(correlationId)) {
      throw new Error('The correlation ID factory returned an invalid value.');
    }

    const headers = new Headers({
      Accept: ACCEPTED_RESPONSE_TYPES,
      'X-Correlation-Id': correlationId,
    });
    if (descriptor.body !== undefined) {
      headers.set('Content-Type', 'application/json');
    }
    if (descriptor.idempotencyKey !== undefined) {
      headers.set('Idempotency-Key', descriptor.idempotencyKey);
    }
    if (descriptor.ifMatch !== undefined) {
      headers.set('If-Match', descriptor.ifMatch);
    }

    try {
      const requestStartedAt = this.#now();
      const response = await this.#fetch(`${this.#baseUrl}${descriptor.path}`, {
        body: descriptor.body === undefined ? undefined : JSON.stringify(descriptor.body),
        credentials: 'include',
        headers,
        method: descriptor.method,
        signal: descriptor.signal,
      });
      return await this.#resultFromResponse<T>(
        response,
        descriptor,
        correlationId,
        requestStartedAt,
      );
    } catch (error) {
      const aborted = wasAborted(error);
      return {
        correlationId,
        kind: aborted ? 'aborted' : 'network',
        ok: false,
        problem: clientProblem(
          0,
          aborted ? 'request_aborted' : 'network_error',
          aborted ? 'Request cancelled' : 'Network request failed',
          aborted
            ? 'The request was cancelled before it completed.'
            : 'CareOS could not reach the API. No server error details are available.',
          descriptor.path,
          correlationId,
        ),
        status: 0,
      };
    }
  }

  async #resultFromResponse<T>(
    response: Response,
    descriptor: RequestDescriptor,
    requestCorrelationId: string,
    requestStartedAt: number,
  ): Promise<ApiResult<T>> {
    const responseCorrelationId = response.headers.get('X-Correlation-Id');
    const correlationId =
      responseCorrelationId && CORRELATION_ID_PATTERN.test(responseCorrelationId)
        ? responseCorrelationId
        : requestCorrelationId;
    const retryAfter = retryAfterSeconds(response.headers);
    const sessionExpiresAt = this.#publishSessionLifecycle(response, requestStartedAt);
    const text = await response.text();

    if (!responseCorrelationId || !CORRELATION_ID_PATTERN.test(responseCorrelationId)) {
      return this.#contractFailure(
        response.status,
        descriptor.path,
        correlationId,
        'The API response omitted its required correlation identifier.',
        retryAfter,
      );
    }

    if (!response.ok) {
      const mediaType = responseMediaType(response.headers);
      let parsed: unknown;
      try {
        parsed = text ? JSON.parse(text) : undefined;
      } catch {
        parsed = undefined;
      }
      const problem = mediaType === 'application/problem+json' ? parseProblem(parsed) : undefined;
      if (!problem) {
        return this.#contractFailure(
          response.status,
          descriptor.path,
          correlationId,
          'The API error response did not match the checked problem contract.',
          retryAfter,
        );
      }

      return {
        correlationId,
        kind: 'http',
        ok: false,
        problem: {
          ...problem,
          correlationId,
          status: response.status,
        },
        ...(retryAfter === undefined ? {} : { retryAfterSeconds: retryAfter }),
        status: response.status,
      };
    }

    if (!descriptor.successStatuses.includes(response.status)) {
      return this.#contractFailure(
        response.status,
        descriptor.path,
        correlationId,
        'The API returned an undocumented success status.',
        retryAfter,
      );
    }

    if (descriptor.responseBody === 'empty') {
      if (text.trim()) {
        return this.#contractFailure(
          response.status,
          descriptor.path,
          correlationId,
          'The API returned a body for an empty response.',
          retryAfter,
        );
      }
      return {
        correlationId,
        data: undefined as T,
        ok: true,
        ...(sessionExpiresAt === undefined ? {} : { sessionExpiresAt }),
        status: response.status,
      };
    }

    const mediaType = responseMediaType(response.headers);
    if (!text || (mediaType !== 'application/json' && !mediaType.endsWith('+json'))) {
      return this.#contractFailure(
        response.status,
        descriptor.path,
        correlationId,
        'The API success response did not contain JSON.',
        retryAfter,
      );
    }

    let parsed: unknown;
    try {
      parsed = JSON.parse(text) as unknown;
    } catch {
      return this.#contractFailure(
        response.status,
        descriptor.path,
        correlationId,
        'The API success response contained invalid JSON.',
        retryAfter,
      );
    }
    if (descriptor.validateResponse && !descriptor.validateResponse(parsed)) {
      return this.#contractFailure(
        response.status,
        descriptor.path,
        correlationId,
        'The API success response did not match the checked response contract.',
        retryAfter,
      );
    }
    const data = parsed as T;

    const etag = response.headers.get('ETag');
    return {
      correlationId,
      data,
      ...(etag && STRONG_ETAG_PATTERN.test(etag) ? { etag } : {}),
      ok: true,
      ...(sessionExpiresAt === undefined ? {} : { sessionExpiresAt }),
      status: response.status,
    };
  }

  #publishSessionLifecycle(response: Response, requestStartedAt: number): number | undefined {
    if (response.status === 401) {
      this.#notifySessionLifecycle({ type: 'invalidated' });
      return undefined;
    }
    const expiresInSeconds = sessionExpirySeconds(response.headers);
    if (expiresInSeconds === undefined) {
      return undefined;
    }
    const expiresAt = requestStartedAt + expiresInSeconds * 1_000;
    this.#notifySessionLifecycle({ expiresAt, type: 'deadline' });
    return expiresAt;
  }

  #notifySessionLifecycle(event: SessionLifecycleEvent): void {
    for (const listener of this.#sessionLifecycleListeners) {
      try {
        listener(event);
      } catch {
        // A UI observer cannot change the checked transport result.
      }
    }
  }

  #contractFailure(
    responseStatus: number,
    instance: string,
    correlationId: string,
    detail: string,
    retryAfter: number | undefined,
  ): ApiFailure {
    const status = responseStatus >= 400 && responseStatus <= 599 ? responseStatus : 502;
    return {
      correlationId,
      kind: 'contract',
      ok: false,
      problem: clientProblem(
        status,
        'invalid_api_response',
        'Invalid API response',
        detail,
        instance,
        correlationId,
      ),
      ...(responseStatus === status ? {} : { responseStatus }),
      ...(retryAfter === undefined ? {} : { retryAfterSeconds: retryAfter }),
      status,
    };
  }

  async #mutation<T>(
    descriptor: Omit<RequestDescriptor, 'method'> & { method?: 'POST' | 'PUT' },
  ): Promise<ApiResult<T>> {
    if (descriptor.body !== undefined && descriptor.multipartBody !== undefined) {
      throw new Error('A mutation cannot contain both JSON and multipart bodies.');
    }
    const csrf = await this.issueCsrfToken({ signal: descriptor.signal });
    if (!csrf.ok) {
      return csrf;
    }
    if (!validCsrfToken(csrf.data)) {
      return this.#contractFailure(
        csrf.status,
        descriptor.path,
        csrf.correlationId,
        'The CSRF bootstrap response was invalid, so the mutation was not sent.',
        undefined,
      );
    }

    const correlationId = this.#correlationIdFactory();
    if (!CORRELATION_ID_PATTERN.test(correlationId)) {
      throw new Error('The correlation ID factory returned an invalid value.');
    }
    const headers = new Headers({
      Accept: ACCEPTED_RESPONSE_TYPES,
      'X-Correlation-Id': correlationId,
      [csrf.data.headerName]: csrf.data.token,
    });
    if (descriptor.body !== undefined) {
      headers.set('Content-Type', 'application/json');
    }
    if (descriptor.idempotencyKey !== undefined) {
      headers.set('Idempotency-Key', descriptor.idempotencyKey);
    }
    if (descriptor.ifMatch !== undefined) {
      headers.set('If-Match', descriptor.ifMatch);
    }

    try {
      const method = descriptor.method ?? 'POST';
      const requestStartedAt = this.#now();
      const response = await this.#fetch(`${this.#baseUrl}${descriptor.path}`, {
        body:
          descriptor.multipartBody ??
          (descriptor.body === undefined ? undefined : JSON.stringify(descriptor.body)),
        credentials: 'include',
        headers,
        method,
        signal: descriptor.signal,
      });
      return await this.#resultFromResponse<T>(
        response,
        { ...descriptor, method },
        correlationId,
        requestStartedAt,
      );
    } catch (error) {
      const aborted = wasAborted(error);
      return {
        correlationId,
        kind: aborted ? 'aborted' : 'network',
        ok: false,
        problem: clientProblem(
          0,
          aborted ? 'request_aborted' : 'network_error',
          aborted ? 'Request cancelled' : 'Network request failed',
          aborted
            ? 'The request was cancelled before it completed.'
            : 'CareOS could not reach the API. The mutation was not retried automatically.',
          descriptor.path,
          correlationId,
        ),
        status: 0,
      };
    }
  }

  listPrototypeScreens(options: ApiRequestOptions = {}) {
    return this.#request<ListPrototypeScreensResponse>({
      method: 'GET',
      path: relativeEndpointPath(endpoints.listPrototypeScreens.path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.listPrototypeScreens.successStatuses,
    });
  }

  getSystemSummary(options: ApiRequestOptions = {}) {
    return this.#request<GetSystemSummaryResponse>({
      method: 'GET',
      path: relativeEndpointPath(endpoints.getSystemSummary.path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.getSystemSummary.successStatuses,
    });
  }

  issueCsrfToken(options: ApiRequestOptions = {}) {
    return this.#request<IssueCsrfTokenResponse>({
      method: 'GET',
      path: relativeEndpointPath(endpoints.issueCsrfToken.path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.issueCsrfToken.successStatuses,
    });
  }

  getAuthenticationSession(options: ApiRequestOptions = {}) {
    return this.#request<GetAuthenticationSessionResponse>({
      method: 'GET',
      path: relativeEndpointPath(endpoints.getAuthenticationSession.path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.getAuthenticationSession.successStatuses,
    });
  }

  login(body: LoginData['body'], options: ApiRequestOptions = {}) {
    return this.#mutation<LoginResponse>({
      body,
      path: relativeEndpointPath(endpoints.login.path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.login.successStatuses,
    });
  }

  logout(options: ApiRequestOptions = {}) {
    return this.#mutation<LogoutResponse>({
      path: relativeEndpointPath(endpoints.logout.path),
      responseBody: 'empty',
      signal: options.signal,
      successStatuses: endpoints.logout.successStatuses,
    });
  }

  requestPasswordReset(body: RequestPasswordResetData['body'], options: ApiRequestOptions = {}) {
    return this.#mutation<void>({
      body,
      path: relativeEndpointPath(endpoints.requestPasswordReset.path),
      responseBody: 'empty',
      signal: options.signal,
      successStatuses: endpoints.requestPasswordReset.successStatuses,
    });
  }

  completePasswordReset(body: CompletePasswordResetData['body'], options: ApiRequestOptions = {}) {
    return this.#mutation<CompletePasswordResetResponse>({
      body,
      path: relativeEndpointPath(endpoints.completePasswordReset.path),
      responseBody: 'empty',
      signal: options.signal,
      successStatuses: endpoints.completePasswordReset.successStatuses,
    });
  }

  acceptInvitation(body: AcceptInvitationData['body'], options: ApiRequestOptions = {}) {
    return this.#mutation<AcceptInvitationResponse>({
      body,
      path: relativeEndpointPath(endpoints.acceptInvitation.path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.acceptInvitation.successStatuses,
    });
  }

  issueInvitation(
    organizationId: string,
    body: IssueInvitationData['body'],
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const path = endpoints.issueInvitation.path.replace(
      '{organizationId}',
      requireUuid(organizationId, 'organizationId'),
    ) as `/api/v1/organizations/${string}/invitations`;
    return this.#mutation<IssueInvitationResponse>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.issueInvitation.successStatuses,
    });
  }

  revokeInvitation(
    organizationId: string,
    invitationId: string,
    body: RevokeInvitationData['body'],
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const path = endpoints.revokeInvitation.path
      .replace('{organizationId}', requireUuid(organizationId, 'organizationId'))
      .replace(
        '{invitationId}',
        requireUuid(invitationId, 'invitationId'),
      ) as `/api/v1/organizations/${string}/invitations/${string}/revocations`;
    return this.#mutation<RevokeInvitationResponse>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.revokeInvitation.successStatuses,
    });
  }

  requestMfaAdministrativeReset(
    organizationId: string,
    targetUserId: string,
    body: RequestMfaAdministrativeResetData['body'],
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const path = endpoints.requestMfaAdministrativeReset.path
      .replace('{organizationId}', requireUuid(organizationId, 'organizationId'))
      .replace(
        '{targetUserId}',
        requireUuid(targetUserId, 'targetUserId'),
      ) as `/api/v1/organizations/${string}/users/${string}/mfa-reset-requests`;
    return this.#mutation<RequestMfaAdministrativeResetResponse>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.requestMfaAdministrativeReset.successStatuses,
    });
  }

  approveMfaAdministrativeReset(
    organizationId: string,
    targetUserId: string,
    approvalId: string,
    body: ApproveMfaAdministrativeResetData['body'],
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const path = endpoints.approveMfaAdministrativeReset.path
      .replace('{organizationId}', requireUuid(organizationId, 'organizationId'))
      .replace('{targetUserId}', requireUuid(targetUserId, 'targetUserId'))
      .replace(
        '{approvalId}',
        requireUuid(approvalId, 'approvalId'),
      ) as `/api/v1/organizations/${string}/users/${string}/mfa-reset-requests/${string}/approvals`;
    return this.#mutation<ApproveMfaAdministrativeResetResponse>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.approveMfaAdministrativeReset.successStatuses,
    });
  }

  executeMfaAdministrativeReset(
    organizationId: string,
    targetUserId: string,
    approvalId: string,
    body: ExecuteMfaAdministrativeResetData['body'],
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const path = endpoints.executeMfaAdministrativeReset.path
      .replace('{organizationId}', requireUuid(organizationId, 'organizationId'))
      .replace('{targetUserId}', requireUuid(targetUserId, 'targetUserId'))
      .replace(
        '{approvalId}',
        requireUuid(approvalId, 'approvalId'),
      ) as `/api/v1/organizations/${string}/users/${string}/mfa-reset-requests/${string}/executions`;
    return this.#mutation<ExecuteMfaAdministrativeResetResponse>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.executeMfaAdministrativeReset.successStatuses,
    });
  }

  requestOrganizationMembershipChange(
    organizationId: string,
    membershipId: string,
    body: RequestOrganizationMembershipChangeData['body'],
    ifMatch: string,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const path = endpoints.requestOrganizationMembershipChange.path
      .replace('{organizationId}', requireUuid(organizationId, 'organizationId'))
      .replace(
        '{membershipId}',
        requireUuid(membershipId, 'membershipId'),
      ) as `/api/v1/organizations/${string}/memberships/${string}/change-requests`;
    return this.#mutation<RequestOrganizationMembershipChangeResponse>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      ifMatch: requireStrongEtag(ifMatch),
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.requestOrganizationMembershipChange.successStatuses,
    });
  }

  approveOrganizationMembershipChange(
    organizationId: string,
    membershipId: string,
    approvalId: string,
    body: ApproveOrganizationMembershipChangeData['body'],
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const path = endpoints.approveOrganizationMembershipChange.path
      .replace('{organizationId}', requireUuid(organizationId, 'organizationId'))
      .replace('{membershipId}', requireUuid(membershipId, 'membershipId'))
      .replace(
        '{approvalId}',
        requireUuid(approvalId, 'approvalId'),
      ) as `/api/v1/organizations/${string}/memberships/${string}/change-requests/${string}/approvals`;
    return this.#mutation<ApproveOrganizationMembershipChangeResponse>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.approveOrganizationMembershipChange.successStatuses,
    });
  }

  executeOrganizationMembershipChange(
    organizationId: string,
    membershipId: string,
    approvalId: string,
    body: ExecuteOrganizationMembershipChangeData['body'],
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const path = endpoints.executeOrganizationMembershipChange.path
      .replace('{organizationId}', requireUuid(organizationId, 'organizationId'))
      .replace('{membershipId}', requireUuid(membershipId, 'membershipId'))
      .replace(
        '{approvalId}',
        requireUuid(approvalId, 'approvalId'),
      ) as `/api/v1/organizations/${string}/memberships/${string}/change-requests/${string}/executions`;
    return this.#mutation<ExecuteOrganizationMembershipChangeResponse>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.executeOrganizationMembershipChange.successStatuses,
    });
  }

  requestOrganizationOwnerTransfer(
    organizationId: string,
    membershipId: string,
    body: RequestOrganizationOwnerTransferData['body'],
    ifMatch: string,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const path = endpoints.requestOrganizationOwnerTransfer.path
      .replace('{organizationId}', requireUuid(organizationId, 'organizationId'))
      .replace(
        '{membershipId}',
        requireUuid(membershipId, 'membershipId'),
      ) as `/api/v1/organizations/${string}/memberships/${string}/owner-transfer-requests`;
    return this.#mutation<RequestOrganizationOwnerTransferResponse>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      ifMatch: requireStrongEtag(ifMatch),
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.requestOrganizationOwnerTransfer.successStatuses,
    });
  }

  approveOrganizationOwnerTransfer(
    organizationId: string,
    membershipId: string,
    approvalId: string,
    body: ApproveOrganizationOwnerTransferData['body'],
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const path = endpoints.approveOrganizationOwnerTransfer.path
      .replace('{organizationId}', requireUuid(organizationId, 'organizationId'))
      .replace('{membershipId}', requireUuid(membershipId, 'membershipId'))
      .replace(
        '{approvalId}',
        requireUuid(approvalId, 'approvalId'),
      ) as `/api/v1/organizations/${string}/memberships/${string}/owner-transfer-requests/${string}/approvals`;
    return this.#mutation<ApproveOrganizationOwnerTransferResponse>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.approveOrganizationOwnerTransfer.successStatuses,
    });
  }

  executeOrganizationOwnerTransfer(
    organizationId: string,
    membershipId: string,
    approvalId: string,
    body: ExecuteOrganizationOwnerTransferData['body'],
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const path = endpoints.executeOrganizationOwnerTransfer.path
      .replace('{organizationId}', requireUuid(organizationId, 'organizationId'))
      .replace('{membershipId}', requireUuid(membershipId, 'membershipId'))
      .replace(
        '{approvalId}',
        requireUuid(approvalId, 'approvalId'),
      ) as `/api/v1/organizations/${string}/memberships/${string}/owner-transfer-requests/${string}/executions`;
    return this.#mutation<ExecuteOrganizationOwnerTransferResponse>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.executeOrganizationOwnerTransfer.successStatuses,
    });
  }

  startMfaEnrollment(body: StartMfaEnrollmentData['body'] = {}, options: ApiRequestOptions = {}) {
    return this.#mutation<StartMfaEnrollmentResponse>({
      body,
      path: relativeEndpointPath(endpoints.startMfaEnrollment.path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.startMfaEnrollment.successStatuses,
    });
  }

  verifyMfaEnrollment(body: VerifyMfaEnrollmentData['body'], options: ApiRequestOptions = {}) {
    return this.#mutation<VerifyMfaEnrollmentResponse>({
      body,
      path: relativeEndpointPath(endpoints.verifyMfaEnrollment.path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.verifyMfaEnrollment.successStatuses,
    });
  }

  completeMfaChallenge(body: CompleteMfaChallengeData['body'], options: ApiRequestOptions = {}) {
    return this.#mutation<CompleteMfaChallengeResponse>({
      body,
      path: relativeEndpointPath(endpoints.completeMfaChallenge.path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.completeMfaChallenge.successStatuses,
    });
  }

  verifyRecentAuthentication(
    body: VerifyRecentAuthenticationData['body'],
    options: ApiRequestOptions = {},
  ) {
    return this.#mutation<VerifyRecentAuthenticationResponse>({
      body,
      path: relativeEndpointPath(endpoints.verifyRecentAuthentication.path),
      responseBody: 'empty',
      signal: options.signal,
      successStatuses: endpoints.verifyRecentAuthentication.successStatuses,
    });
  }

  regenerateRecoveryCodes(options: ApiRequestOptions = {}) {
    return this.#mutation<RegenerateRecoveryCodesResponse>({
      path: relativeEndpointPath(endpoints.regenerateRecoveryCodes.path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.regenerateRecoveryCodes.successStatuses,
    });
  }

  listSelectableOrganizations(options: ApiRequestOptions = {}) {
    return this.#request<ListSelectableOrganizationsResponse>({
      method: 'GET',
      path: relativeEndpointPath(endpoints.listSelectableOrganizations.path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.listSelectableOrganizations.successStatuses,
    });
  }

  listOrganizationMemberships(
    organizationId: string,
    query: OrganizationMembershipQuery = {},
    options: ApiRequestOptions = {},
  ) {
    const path = endpoints.listOrganizationMemberships.path.replace(
      '{organizationId}',
      requireUuid(organizationId, 'organizationId'),
    ) as `/api/v1/organizations/${string}/memberships`;
    return this.#request<ListOrganizationMembershipsResponse>({
      method: 'GET',
      path: `${relativeEndpointPath(path)}${organizationMembershipQuery(query)}`,
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.listOrganizationMemberships.successStatuses,
    });
  }

  getAdministrationReadiness(organizationId: string, options: ApiRequestOptions = {}) {
    const path = endpoints.getAdministrationReadiness.path.replace(
      '{organizationId}',
      requireUuid(organizationId, 'organizationId'),
    ) as `/api/v1/organizations/${string}/setup-readiness`;
    return this.#request<GetAdministrationReadinessResponse>({
      method: 'GET',
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.getAdministrationReadiness.successStatuses,
    });
  }

  getOrganizationProfile(organizationId: string, options: ApiRequestOptions = {}) {
    const path = endpoints.getOrganizationProfile.path.replace(
      '{organizationId}',
      requireUuid(organizationId, 'organizationId'),
    ) as `/api/v1/organizations/${string}/profile`;
    return this.#request<GetOrganizationProfileResponse>({
      method: 'GET',
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.getOrganizationProfile.successStatuses,
    });
  }

  getOrganizationInternationalSettings(organizationId: string, options: ApiRequestOptions = {}) {
    const path = endpoints.getOrganizationInternationalSettings.path.replace(
      '{organizationId}',
      requireUuid(organizationId, 'organizationId'),
    ) as `/api/v1/organizations/${string}/international-settings`;
    return this.#request<GetOrganizationInternationalSettingsResponse>({
      method: 'GET',
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.getOrganizationInternationalSettings.successStatuses,
    });
  }

  getFacilityDirectory(
    organizationId: string,
    query: NonNullable<GetFacilityDirectoryData['query']> = {},
    options: ApiRequestOptions = {},
  ) {
    const base = endpoints.getFacilityDirectory.path.replace(
      '{organizationId}',
      requireUuid(organizationId, 'organizationId'),
    ) as `/api/v1/organizations/${string}/facilities`;
    const parameters = new URLSearchParams();
    if (query.query) parameters.set('query', query.query.trim());
    if (query.status) parameters.set('status', query.status);
    const suffix = parameters.toString();
    return this.#request<GetFacilityDirectoryResponse>({
      method: 'GET',
      path: relativeEndpointPath(`${base}${suffix ? `?${suffix}` : ''}`),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.getFacilityDirectory.successStatuses,
    });
  }

  getOrganizationUnitDirectory(
    organizationId: string,
    facilityId: string,
    options: ApiRequestOptions = {},
  ) {
    const path = endpoints.getOrganizationUnitDirectory.path
      .replace('{organizationId}', requireUuid(organizationId, 'organizationId'))
      .replace(
        '{facilityId}',
        requireUuid(facilityId, 'facilityId'),
      ) as `/api/v1/organizations/${string}/facilities/${string}/units`;
    return this.#request<GetOrganizationUnitDirectoryResponse>({
      method: 'GET',
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.getOrganizationUnitDirectory.successStatuses,
    });
  }

  getServiceLocationDirectory(
    organizationId: string,
    facilityId: string,
    options: ApiRequestOptions = {},
  ) {
    const path = endpoints.getServiceLocationDirectory.path
      .replace('{organizationId}', requireUuid(organizationId, 'organizationId'))
      .replace(
        '{facilityId}',
        requireUuid(facilityId, 'facilityId'),
      ) as `/api/v1/organizations/${string}/facilities/${string}/locations`;
    return this.#request<GetServiceLocationDirectoryResponse>({
      method: 'GET',
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.getServiceLocationDirectory.successStatuses,
    });
  }

  createServiceLocationDraft(
    organizationId: string,
    facilityId: string,
    body: CreateServiceLocationDraftData['body'],
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const path = endpoints.createServiceLocationDraft.path
      .replace('{organizationId}', requireUuid(organizationId, 'organizationId'))
      .replace(
        '{facilityId}',
        requireUuid(facilityId, 'facilityId'),
      ) as `/api/v1/organizations/${string}/facilities/${string}/locations`;
    return this.#mutation<CreateServiceLocationDraftResponse>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      method: 'POST',
      path: relativeEndpointPath(path as `/api/${string}`),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.createServiceLocationDraft.successStatuses,
    });
  }

  updateServiceLocationDraft(
    organizationId: string,
    facilityId: string,
    locationId: string,
    body: UpdateServiceLocationDraftData['body'],
    ifMatch: string,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const path = endpoints.updateServiceLocationDraft.path
      .replace('{organizationId}', requireUuid(organizationId, 'organizationId'))
      .replace('{facilityId}', requireUuid(facilityId, 'facilityId'))
      .replace(
        '{locationId}',
        requireUuid(locationId, 'locationId'),
      ) as `/api/v1/organizations/${string}/facilities/${string}/locations/${string}`;
    return this.#mutation<UpdateServiceLocationDraftResponse>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      ifMatch: requireStrongEtag(ifMatch),
      method: 'PUT',
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.updateServiceLocationDraft.successStatuses,
    });
  }

  reparentServiceLocation(
    organizationId: string,
    facilityId: string,
    locationId: string,
    body: ReparentServiceLocationData['body'],
    ifMatch: string,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const path = endpoints.reparentServiceLocation.path
      .replace('{organizationId}', requireUuid(organizationId, 'organizationId'))
      .replace('{facilityId}', requireUuid(facilityId, 'facilityId'))
      .replace(
        '{locationId}',
        requireUuid(locationId, 'locationId'),
      ) as `/api/v1/organizations/${string}/facilities/${string}/locations/${string}/reparentings`;
    return this.#mutation<ReparentServiceLocationResponse>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      ifMatch: requireStrongEtag(ifMatch),
      method: 'POST',
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.reparentServiceLocation.successStatuses,
    });
  }

  activateServiceLocation(
    organizationId: string,
    facilityId: string,
    locationId: string,
    body: ActivateServiceLocationData['body'],
    ifMatch: string,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    return this.#serviceLocationLifecycle<ActivateServiceLocationResponse>(
      'activateServiceLocation',
      organizationId,
      facilityId,
      locationId,
      body,
      ifMatch,
      idempotencyKey,
      options,
    );
  }

  suspendServiceLocation(
    organizationId: string,
    facilityId: string,
    locationId: string,
    body: SuspendServiceLocationData['body'],
    ifMatch: string,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    return this.#serviceLocationLifecycle<SuspendServiceLocationResponse>(
      'suspendServiceLocation',
      organizationId,
      facilityId,
      locationId,
      body,
      ifMatch,
      idempotencyKey,
      options,
    );
  }

  reactivateServiceLocation(
    organizationId: string,
    facilityId: string,
    locationId: string,
    body: ReactivateServiceLocationData['body'],
    ifMatch: string,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    return this.#serviceLocationLifecycle<ReactivateServiceLocationResponse>(
      'reactivateServiceLocation',
      organizationId,
      facilityId,
      locationId,
      body,
      ifMatch,
      idempotencyKey,
      options,
    );
  }

  closeServiceLocation(
    organizationId: string,
    facilityId: string,
    locationId: string,
    body: CloseServiceLocationData['body'],
    ifMatch: string,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    return this.#serviceLocationLifecycle<CloseServiceLocationResponse>(
      'closeServiceLocation',
      organizationId,
      facilityId,
      locationId,
      body,
      ifMatch,
      idempotencyKey,
      options,
    );
  }

  #serviceLocationLifecycle<T>(
    endpoint:
      | 'activateServiceLocation'
      | 'suspendServiceLocation'
      | 'reactivateServiceLocation'
      | 'closeServiceLocation',
    organizationId: string,
    facilityId: string,
    locationId: string,
    body: unknown,
    ifMatch: string,
    idempotencyKey: string,
    options: ApiRequestOptions,
  ) {
    const definition = endpoints[endpoint];
    const path = definition.path
      .replace('{organizationId}', requireUuid(organizationId, 'organizationId'))
      .replace('{facilityId}', requireUuid(facilityId, 'facilityId'))
      .replace('{locationId}', requireUuid(locationId, 'locationId'));
    return this.#mutation<T>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      ifMatch: requireStrongEtag(ifMatch),
      method: 'POST',
      path: relativeEndpointPath(path as `/api/${string}`),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: definition.successStatuses,
    });
  }

  createOrganizationUnitDraft(
    organizationId: string,
    facilityId: string,
    body: CreateOrganizationUnitDraftData['body'],
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const path = endpoints.createOrganizationUnitDraft.path
      .replace('{organizationId}', requireUuid(organizationId, 'organizationId'))
      .replace(
        '{facilityId}',
        requireUuid(facilityId, 'facilityId'),
      ) as `/api/v1/organizations/${string}/facilities/${string}/units`;
    return this.#mutation<CreateOrganizationUnitDraftResponse>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      method: 'POST',
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.createOrganizationUnitDraft.successStatuses,
    });
  }

  updateOrganizationUnitDraft(
    organizationId: string,
    facilityId: string,
    unitId: string,
    body: UpdateOrganizationUnitDraftData['body'],
    ifMatch: string,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const path = endpoints.updateOrganizationUnitDraft.path
      .replace('{organizationId}', requireUuid(organizationId, 'organizationId'))
      .replace('{facilityId}', requireUuid(facilityId, 'facilityId'))
      .replace(
        '{unitId}',
        requireUuid(unitId, 'unitId'),
      ) as `/api/v1/organizations/${string}/facilities/${string}/units/${string}`;
    return this.#mutation<UpdateOrganizationUnitDraftResponse>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      ifMatch: requireStrongEtag(ifMatch),
      method: 'PUT',
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.updateOrganizationUnitDraft.successStatuses,
    });
  }

  reparentOrganizationUnit(
    organizationId: string,
    facilityId: string,
    unitId: string,
    body: ReparentOrganizationUnitData['body'],
    ifMatch: string,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const path = endpoints.reparentOrganizationUnit.path
      .replace('{organizationId}', requireUuid(organizationId, 'organizationId'))
      .replace('{facilityId}', requireUuid(facilityId, 'facilityId'))
      .replace(
        '{unitId}',
        requireUuid(unitId, 'unitId'),
      ) as `/api/v1/organizations/${string}/facilities/${string}/units/${string}/reparentings`;
    return this.#mutation<ReparentOrganizationUnitResponse>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      ifMatch: requireStrongEtag(ifMatch),
      method: 'POST',
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.reparentOrganizationUnit.successStatuses,
    });
  }

  activateOrganizationUnit(
    organizationId: string,
    facilityId: string,
    unitId: string,
    body: ActivateOrganizationUnitData['body'],
    ifMatch: string,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    return this.#organizationUnitLifecycleMutation<ActivateOrganizationUnitResponse>(
      endpoints.activateOrganizationUnit,
      organizationId,
      facilityId,
      unitId,
      body,
      ifMatch,
      idempotencyKey,
      options,
    );
  }

  suspendOrganizationUnit(
    organizationId: string,
    facilityId: string,
    unitId: string,
    body: SuspendOrganizationUnitData['body'],
    ifMatch: string,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    return this.#organizationUnitLifecycleMutation<SuspendOrganizationUnitResponse>(
      endpoints.suspendOrganizationUnit,
      organizationId,
      facilityId,
      unitId,
      body,
      ifMatch,
      idempotencyKey,
      options,
    );
  }

  reactivateOrganizationUnit(
    organizationId: string,
    facilityId: string,
    unitId: string,
    body: ReactivateOrganizationUnitData['body'],
    ifMatch: string,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    return this.#organizationUnitLifecycleMutation<ReactivateOrganizationUnitResponse>(
      endpoints.reactivateOrganizationUnit,
      organizationId,
      facilityId,
      unitId,
      body,
      ifMatch,
      idempotencyKey,
      options,
    );
  }

  closeOrganizationUnit(
    organizationId: string,
    facilityId: string,
    unitId: string,
    body: CloseOrganizationUnitData['body'],
    ifMatch: string,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    return this.#organizationUnitLifecycleMutation<CloseOrganizationUnitResponse>(
      endpoints.closeOrganizationUnit,
      organizationId,
      facilityId,
      unitId,
      body,
      ifMatch,
      idempotencyKey,
      options,
    );
  }

  #organizationUnitLifecycleMutation<T>(
    endpoint: { path: string; successStatuses: readonly number[] },
    organizationId: string,
    facilityId: string,
    unitId: string,
    body: unknown,
    ifMatch: string,
    idempotencyKey: string,
    options: ApiRequestOptions,
  ) {
    const path = endpoint.path
      .replace('{organizationId}', requireUuid(organizationId, 'organizationId'))
      .replace('{facilityId}', requireUuid(facilityId, 'facilityId'))
      .replace('{unitId}', requireUuid(unitId, 'unitId'));
    return this.#mutation<T>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      ifMatch: requireStrongEtag(ifMatch),
      method: 'POST',
      path: relativeEndpointPath(path as `/api/${string}`),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoint.successStatuses,
    });
  }

  createFacilityDraft(
    organizationId: string,
    body: CreateFacilityDraftData['body'],
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const path = endpoints.createFacilityDraft.path.replace(
      '{organizationId}',
      requireUuid(organizationId, 'organizationId'),
    ) as `/api/v1/organizations/${string}/facilities`;
    return this.#mutation<CreateFacilityDraftResponse>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      method: 'POST',
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.createFacilityDraft.successStatuses,
    });
  }

  updateFacilityDraft(
    organizationId: string,
    facilityId: string,
    body: UpdateFacilityDraftData['body'],
    ifMatch: string,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const path = endpoints.updateFacilityDraft.path
      .replace('{organizationId}', requireUuid(organizationId, 'organizationId'))
      .replace(
        '{facilityId}',
        requireUuid(facilityId, 'facilityId'),
      ) as `/api/v1/organizations/${string}/facilities/${string}`;
    return this.#mutation<UpdateFacilityDraftResponse>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      ifMatch: requireStrongEtag(ifMatch),
      method: 'PUT',
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.updateFacilityDraft.successStatuses,
    });
  }

  submitFacilityDraft(
    organizationId: string,
    facilityId: string,
    body: SubmitFacilityDraftData['body'],
    ifMatch: string,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const path = endpoints.submitFacilityDraft.path
      .replace('{organizationId}', requireUuid(organizationId, 'organizationId'))
      .replace(
        '{facilityId}',
        requireUuid(facilityId, 'facilityId'),
      ) as `/api/v1/organizations/${string}/facilities/${string}/submissions`;
    return this.#mutation<SubmitFacilityDraftResponse>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      ifMatch: requireStrongEtag(ifMatch),
      method: 'POST',
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.submitFacilityDraft.successStatuses,
    });
  }

  getOrganizationGovernanceDirectory(organizationId: string, options: ApiRequestOptions = {}) {
    const path = endpoints.getOrganizationGovernanceDirectory.path.replace(
      '{organizationId}',
      requireUuid(organizationId, 'organizationId'),
    ) as `/api/v1/organizations/${string}/governance-responsibilities`;
    return this.#request<GetOrganizationGovernanceDirectoryResponse>({
      method: 'GET',
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.getOrganizationGovernanceDirectory.successStatuses,
    });
  }

  createOrganizationGovernanceResponsibility(
    organizationId: string,
    body: CreateOrganizationGovernanceResponsibilityData['body'],
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const path = endpoints.createOrganizationGovernanceResponsibility.path.replace(
      '{organizationId}',
      requireUuid(organizationId, 'organizationId'),
    ) as `/api/v1/organizations/${string}/governance-responsibilities`;
    return this.#mutation<CreateOrganizationGovernanceResponsibilityResponse>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      method: 'POST',
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.createOrganizationGovernanceResponsibility.successStatuses,
    });
  }

  supersedeOrganizationGovernanceResponsibility(
    organizationId: string,
    responsibilityId: string,
    body: SupersedeOrganizationGovernanceResponsibilityData['body'],
    ifMatch: string,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const path = endpoints.supersedeOrganizationGovernanceResponsibility.path
      .replace('{organizationId}', requireUuid(organizationId, 'organizationId'))
      .replace(
        '{responsibilityId}',
        requireUuid(responsibilityId, 'responsibilityId'),
      ) as `/api/v1/organizations/${string}/governance-responsibilities/${string}/supersessions`;
    return this.#mutation<SupersedeOrganizationGovernanceResponsibilityResponse>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      ifMatch: requireStrongEtag(ifMatch),
      method: 'POST',
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.supersedeOrganizationGovernanceResponsibility.successStatuses,
    });
  }

  endOrganizationGovernanceResponsibility(
    organizationId: string,
    responsibilityId: string,
    body: EndOrganizationGovernanceResponsibilityData['body'],
    ifMatch: string,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const path = endpoints.endOrganizationGovernanceResponsibility.path
      .replace('{organizationId}', requireUuid(organizationId, 'organizationId'))
      .replace(
        '{responsibilityId}',
        requireUuid(responsibilityId, 'responsibilityId'),
      ) as `/api/v1/organizations/${string}/governance-responsibilities/${string}/endings`;
    return this.#mutation<EndOrganizationGovernanceResponsibilityResponse>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      ifMatch: requireStrongEtag(ifMatch),
      method: 'POST',
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.endOrganizationGovernanceResponsibility.successStatuses,
    });
  }

  listOrganizationIdentifiers(organizationId: string, options: ApiRequestOptions = {}) {
    const path = endpoints.listOrganizationIdentifiers.path.replace(
      '{organizationId}',
      requireUuid(organizationId, 'organizationId'),
    ) as `/api/v1/organizations/${string}/identifiers`;
    return this.#request<ListOrganizationIdentifiersResponse>({
      method: 'GET',
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.listOrganizationIdentifiers.successStatuses,
    });
  }

  createOrganizationIdentifier(
    organizationId: string,
    body: CreateOrganizationIdentifierData['body'],
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const path = endpoints.createOrganizationIdentifier.path.replace(
      '{organizationId}',
      requireUuid(organizationId, 'organizationId'),
    ) as `/api/v1/organizations/${string}/identifiers`;
    return this.#mutation<CreateOrganizationIdentifierResponse>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      method: 'POST',
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.createOrganizationIdentifier.successStatuses,
    });
  }

  updateOrganizationIdentifier(
    organizationId: string,
    identifierId: string,
    body: UpdateOrganizationIdentifierData['body'],
    ifMatch: string,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const path = endpoints.updateOrganizationIdentifier.path
      .replace('{organizationId}', requireUuid(organizationId, 'organizationId'))
      .replace(
        '{identifierId}',
        requireUuid(identifierId, 'identifierId'),
      ) as `/api/v1/organizations/${string}/identifiers/${string}`;
    return this.#mutation<UpdateOrganizationIdentifierResponse>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      ifMatch: requireStrongEtag(ifMatch),
      method: 'PUT',
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.updateOrganizationIdentifier.successStatuses,
    });
  }

  verifyOrganizationIdentifier(
    organizationId: string,
    identifierId: string,
    body: VerifyOrganizationIdentifierData['body'],
    ifMatch: string,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const path = endpoints.verifyOrganizationIdentifier.path
      .replace('{organizationId}', requireUuid(organizationId, 'organizationId'))
      .replace(
        '{identifierId}',
        requireUuid(identifierId, 'identifierId'),
      ) as `/api/v1/organizations/${string}/identifiers/${string}/verifications`;
    return this.#mutation<VerifyOrganizationIdentifierResponse>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      ifMatch: requireStrongEtag(ifMatch),
      method: 'POST',
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.verifyOrganizationIdentifier.successStatuses,
    });
  }

  revokeOrganizationIdentifier(
    organizationId: string,
    identifierId: string,
    body: RevokeOrganizationIdentifierData['body'],
    ifMatch: string,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const path = endpoints.revokeOrganizationIdentifier.path
      .replace('{organizationId}', requireUuid(organizationId, 'organizationId'))
      .replace(
        '{identifierId}',
        requireUuid(identifierId, 'identifierId'),
      ) as `/api/v1/organizations/${string}/identifiers/${string}/revocations`;
    return this.#mutation<RevokeOrganizationIdentifierResponse>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      ifMatch: requireStrongEtag(ifMatch),
      method: 'POST',
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.revokeOrganizationIdentifier.successStatuses,
    });
  }

  supersedeOrganizationIdentifier(
    organizationId: string,
    identifierId: string,
    body: SupersedeOrganizationIdentifierData['body'],
    ifMatch: string,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const path = endpoints.supersedeOrganizationIdentifier.path
      .replace('{organizationId}', requireUuid(organizationId, 'organizationId'))
      .replace(
        '{identifierId}',
        requireUuid(identifierId, 'identifierId'),
      ) as `/api/v1/organizations/${string}/identifiers/${string}/supersessions`;
    return this.#mutation<SupersedeOrganizationIdentifierResponse>({
      body: {
        ...body,
        replacementEtag: requireStrongEtag(body.replacementEtag),
        replacementId: requireUuid(body.replacementId, 'replacementId'),
      },
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      ifMatch: requireStrongEtag(ifMatch),
      method: 'POST',
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.supersedeOrganizationIdentifier.successStatuses,
    });
  }

  listOrganizationContacts(organizationId: string, options: ApiRequestOptions = {}) {
    const path = endpoints.listOrganizationContacts.path.replace(
      '{organizationId}',
      requireUuid(organizationId, 'organizationId'),
    ) as `/api/v1/organizations/${string}/contacts`;
    return this.#request<ListOrganizationContactsResponse>({
      method: 'GET',
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.listOrganizationContacts.successStatuses,
    });
  }

  createOrganizationAddress(
    organizationId: string,
    body: CreateOrganizationAddressData['body'],
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const path = endpoints.createOrganizationAddress.path.replace(
      '{organizationId}',
      requireUuid(organizationId, 'organizationId'),
    ) as `/api/v1/organizations/${string}/addresses`;
    return this.#mutation<CreateOrganizationAddressResponse>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      method: 'POST',
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.createOrganizationAddress.successStatuses,
    });
  }

  supersedeOrganizationAddress(
    organizationId: string,
    addressId: string,
    body: SupersedeOrganizationAddressData['body'],
    ifMatch: string,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const path = endpoints.supersedeOrganizationAddress.path
      .replace('{organizationId}', requireUuid(organizationId, 'organizationId'))
      .replace(
        '{addressId}',
        requireUuid(addressId, 'addressId'),
      ) as `/api/v1/organizations/${string}/addresses/${string}/supersessions`;
    return this.#mutation<SupersedeOrganizationAddressResponse>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      ifMatch: requireStrongEtag(ifMatch),
      method: 'POST',
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.supersedeOrganizationAddress.successStatuses,
    });
  }

  endOrganizationAddress(
    organizationId: string,
    addressId: string,
    body: EndOrganizationAddressData['body'],
    ifMatch: string,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const path = endpoints.endOrganizationAddress.path
      .replace('{organizationId}', requireUuid(organizationId, 'organizationId'))
      .replace(
        '{addressId}',
        requireUuid(addressId, 'addressId'),
      ) as `/api/v1/organizations/${string}/addresses/${string}/endings`;
    return this.#mutation<EndOrganizationAddressResponse>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      ifMatch: requireStrongEtag(ifMatch),
      method: 'POST',
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.endOrganizationAddress.successStatuses,
    });
  }

  createOrganizationContact(
    organizationId: string,
    body: CreateOrganizationContactData['body'],
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const path = endpoints.createOrganizationContact.path.replace(
      '{organizationId}',
      requireUuid(organizationId, 'organizationId'),
    ) as `/api/v1/organizations/${string}/contacts`;
    return this.#mutation<CreateOrganizationContactResponse>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      method: 'POST',
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.createOrganizationContact.successStatuses,
    });
  }

  verifyOrganizationContact(
    organizationId: string,
    contactId: string,
    body: VerifyOrganizationContactData['body'],
    ifMatch: string,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const path = endpoints.verifyOrganizationContact.path
      .replace('{organizationId}', requireUuid(organizationId, 'organizationId'))
      .replace(
        '{contactId}',
        requireUuid(contactId, 'contactId'),
      ) as `/api/v1/organizations/${string}/contacts/${string}/verifications`;
    return this.#mutation<VerifyOrganizationContactResponse>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      ifMatch: requireStrongEtag(ifMatch),
      method: 'POST',
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.verifyOrganizationContact.successStatuses,
    });
  }

  supersedeOrganizationContact(
    organizationId: string,
    contactId: string,
    body: SupersedeOrganizationContactData['body'],
    ifMatch: string,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const path = endpoints.supersedeOrganizationContact.path
      .replace('{organizationId}', requireUuid(organizationId, 'organizationId'))
      .replace(
        '{contactId}',
        requireUuid(contactId, 'contactId'),
      ) as `/api/v1/organizations/${string}/contacts/${string}/supersessions`;
    return this.#mutation<SupersedeOrganizationContactResponse>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      ifMatch: requireStrongEtag(ifMatch),
      method: 'POST',
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.supersedeOrganizationContact.successStatuses,
    });
  }

  endOrganizationContact(
    organizationId: string,
    contactId: string,
    body: EndOrganizationContactData['body'],
    ifMatch: string,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const path = endpoints.endOrganizationContact.path
      .replace('{organizationId}', requireUuid(organizationId, 'organizationId'))
      .replace(
        '{contactId}',
        requireUuid(contactId, 'contactId'),
      ) as `/api/v1/organizations/${string}/contacts/${string}/endings`;
    return this.#mutation<EndOrganizationContactResponse>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      ifMatch: requireStrongEtag(ifMatch),
      method: 'POST',
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.endOrganizationContact.successStatuses,
    });
  }

  updateOrganizationProfile(
    organizationId: string,
    body: UpdateOrganizationProfileData['body'],
    ifMatch: string,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const path = endpoints.updateOrganizationProfile.path.replace(
      '{organizationId}',
      requireUuid(organizationId, 'organizationId'),
    ) as `/api/v1/organizations/${string}/profile`;
    return this.#mutation<UpdateOrganizationProfileResponse>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      ifMatch: requireStrongEtag(ifMatch),
      method: 'PUT',
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.updateOrganizationProfile.successStatuses,
    });
  }

  scheduleOrganizationInternationalSettings(
    organizationId: string,
    body: ScheduleOrganizationInternationalSettingsData['body'],
    ifMatch: string,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const path = endpoints.scheduleOrganizationInternationalSettings.path.replace(
      '{organizationId}',
      requireUuid(organizationId, 'organizationId'),
    ) as `/api/v1/organizations/${string}/international-settings`;
    return this.#mutation<ScheduleOrganizationInternationalSettingsResponse>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      ifMatch: requireStrongEtag(ifMatch),
      method: 'PUT',
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.scheduleOrganizationInternationalSettings.successStatuses,
    });
  }

  getOperatingHoursDirectory(organizationId: string, options: ApiRequestOptions = {}) {
    const id = requireUuid(organizationId, 'organizationId');
    return this.#request<OperatingHoursOverview>({
      method: 'GET',
      path: `/v1/organizations/${id}/operating-hours`,
      responseBody: 'json',
      signal: options.signal,
      successStatuses: [200],
      validateResponse: operatingHoursOverviewValidator(id),
    });
  }

  getOperatingHoursTarget(
    organizationId: string,
    targetType: 'facility' | 'location',
    targetId: string,
    options: ApiRequestOptions = {},
  ) {
    const organization = requireUuid(organizationId, 'organizationId');
    const target = requireUuid(targetId, 'targetId');
    return this.#request<OperatingHoursDirectory>({
      method: 'GET',
      path: `/v1/organizations/${organization}/operating-hours/${targetType}/${target}`,
      responseBody: 'json',
      signal: options.signal,
      successStatuses: [200],
      validateResponse: operatingHoursDirectoryValidator(organization, targetType, target),
    });
  }

  replaceOperatingHours(
    organizationId: string,
    targetType: 'facility' | 'location',
    targetId: string,
    body: OperatingHoursBatchRequest,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const organization = requireUuid(organizationId, 'organizationId');
    const target = requireUuid(targetId, 'targetId');
    return this.#mutation<OperatingHoursDirectory>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      method: 'POST',
      path: `/v1/organizations/${organization}/operating-hours/${targetType}/${target}/batches`,
      responseBody: 'json',
      signal: options.signal,
      successStatuses: [201],
      validateResponse: operatingHoursDirectoryValidator(organization, targetType, target),
    });
  }

  cancelOperatingHours(
    organizationId: string,
    targetType: 'facility' | 'location',
    targetId: string,
    batchId: string,
    reason: string,
    ifMatch: string,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const organization = requireUuid(organizationId, 'organizationId');
    const target = requireUuid(targetId, 'targetId');
    const batch = requireUuid(batchId, 'batchId');
    return this.#mutation<OperatingHoursDirectory>({
      body: { reason },
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      ifMatch: requireStrongEtag(ifMatch),
      method: 'POST',
      path: `/v1/organizations/${organization}/operating-hours/${targetType}/${target}/batches/${batch}/cancellations`,
      responseBody: 'json',
      signal: options.signal,
      successStatuses: [200],
      validateResponse: operatingHoursDirectoryValidator(organization, targetType, target),
    });
  }

  transitionFacilityLifecycle(
    organizationId: string,
    facilityId: string,
    action: 'activations' | 'suspensions' | 'reactivations' | 'closures',
    body: { fromState: string; reason: string },
    ifMatch: string,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const organization = requireUuid(organizationId, 'organizationId');
    const facility = requireUuid(facilityId, 'facilityId');
    return this.#mutation<FacilityDirectory>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      ifMatch: requireStrongEtag(ifMatch),
      method: 'POST',
      path: `/v1/organizations/${organization}/facilities/${facility}/${action}`,
      responseBody: 'json',
      signal: options.signal,
      successStatuses: [200],
    });
  }

  getServiceCatalogue(organizationId: string, options: ApiRequestOptions = {}) {
    const id = requireUuid(organizationId, 'organizationId');
    return this.#request<ServiceCatalogue>({
      method: 'GET',
      path: `/v1/organizations/${id}/services`,
      responseBody: 'json',
      signal: options.signal,
      successStatuses: [200],
      validateResponse: serviceCatalogueValidator(id),
    });
  }

  getServiceAssignmentDirectory(organizationId: string, options: ApiRequestOptions = {}) {
    const id = requireUuid(organizationId, 'organizationId');
    return this.#request<ServiceAssignmentDirectory>({
      method: 'GET',
      path: `/v1/organizations/${id}/service-assignments`,
      responseBody: 'json',
      signal: options.signal,
      successStatuses: [200],
      validateResponse: serviceAssignmentDirectoryValidator(id),
    });
  }

  getIdentifierSchemeDirectory(organizationId: string, options: ApiRequestOptions = {}) {
    const id = requireUuid(organizationId, 'organizationId');
    return this.#request<IdentifierSchemeDirectory>({
      method: 'GET',
      path: `/v1/organizations/${id}/identifier-schemes`,
      responseBody: 'json',
      signal: options.signal,
      successStatuses: [200],
      validateResponse: identifierSchemeDirectoryValidator(id),
    });
  }

  getConfigurationActivationDirectory(organizationId: string, options: ApiRequestOptions = {}) {
    const id = requireUuid(organizationId, 'organizationId');
    return this.#request<ConfigurationActivationDirectory>({
      method: 'GET',
      path: `/v1/organizations/${id}/configuration-activations`,
      responseBody: 'json',
      signal: options.signal,
      successStatuses: [200],
      validateResponse: configurationActivationDirectoryValidator(id),
    });
  }

  getConfigurationHistory(organizationId: string, options: ApiRequestOptions = {}) {
    const id = requireUuid(organizationId, 'organizationId');
    return this.#request<ConfigurationHistoryPage>({
      method: 'GET',
      path: `/v1/organizations/${id}/configuration-history`,
      responseBody: 'json',
      signal: options.signal,
      successStatuses: [200],
      validateResponse: configurationHistoryPageValidator(id),
    });
  }

  queryConfigurationHistory(
    organizationId: string,
    filters: Record<string, string | number | undefined>,
    options: ApiRequestOptions = {},
  ) {
    const id = requireUuid(organizationId, 'organizationId');
    const query = new URLSearchParams();
    for (const [key, value] of Object.entries(filters))
      if (value !== undefined && value !== '') query.set(key, String(value));
    const suffix = query.size ? `?${query.toString()}` : '';
    return this.#request<ConfigurationHistoryPage>({
      method: 'GET',
      path: `/v1/organizations/${id}/configuration-history${suffix}`,
      responseBody: 'json',
      signal: options.signal,
      successStatuses: [200],
      validateResponse: configurationHistoryPageValidator(id),
    });
  }

  getAuditEvidence(organizationId: string, options: ApiRequestOptions = {}) {
    const id = requireUuid(organizationId, 'organizationId');
    return this.#request<AuditEvidencePage>({
      method: 'GET',
      path: `/v1/organizations/${id}/audit-evidence`,
      responseBody: 'json',
      signal: options.signal,
      successStatuses: [200],
      validateResponse: auditEvidencePageValidator(id),
    });
  }

  queryAuditEvidence(
    organizationId: string,
    filters: Record<string, string | number | undefined>,
    options: ApiRequestOptions = {},
  ) {
    const id = requireUuid(organizationId, 'organizationId');
    const query = new URLSearchParams();
    for (const [key, value] of Object.entries(filters))
      if (value !== undefined && value !== '') query.set(key, String(value));
    const suffix = query.size ? `?${query.toString()}` : '';
    return this.#request<AuditEvidencePage>({
      method: 'GET',
      path: `/v1/organizations/${id}/audit-evidence${suffix}`,
      responseBody: 'json',
      signal: options.signal,
      successStatuses: [200],
      validateResponse: auditEvidencePageValidator(id),
    });
  }

  accessAuditEvidenceDetail(
    organizationId: string,
    eventId: string,
    purposeCode: string,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const organization = requireUuid(organizationId, 'organizationId');
    const event = requireUuid(eventId, 'eventId');
    return this.#mutation<AuditEvidenceDetail>({
      body: { purposeCode },
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      method: 'POST',
      path: `/v1/organizations/${organization}/audit-evidence/${event}/accesses`,
      responseBody: 'json',
      signal: options.signal,
      successStatuses: [200],
      validateResponse: auditEvidenceDetailValidator(organization, event),
    });
  }

  getEvidenceExportDirectory(
    organizationId: string,
    source: 'history' | 'audit',
    options: ApiRequestOptions = {},
  ) {
    const id = requireUuid(organizationId, 'organizationId');
    return this.#request<EvidenceExportDirectory>({
      method: 'GET',
      path: `/v1/organizations/${id}/evidence-exports?source=${source}`,
      responseBody: 'json',
      signal: options.signal,
      successStatuses: [200],
      validateResponse: evidenceExportDirectoryValidator(id),
    });
  }

  createServiceDefinition(
    organizationId: string,
    body: ServiceWriteRequest,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const id = requireUuid(organizationId, 'organizationId');
    return this.#mutation<ServiceCatalogue>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      method: 'POST',
      path: `/v1/organizations/${id}/services`,
      responseBody: 'json',
      signal: options.signal,
      successStatuses: [201],
      validateResponse: serviceCatalogueValidator(id),
    });
  }

  updateServiceDefinition(
    organizationId: string,
    serviceId: string,
    body: ServiceWriteRequest,
    ifMatch: string,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const organization = requireUuid(organizationId, 'organizationId');
    const service = requireUuid(serviceId, 'serviceId');
    return this.#mutation<ServiceCatalogue>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      ifMatch: requireStrongEtag(ifMatch),
      method: 'PUT',
      path: `/v1/organizations/${organization}/services/${service}`,
      responseBody: 'json',
      signal: options.signal,
      successStatuses: [200],
      validateResponse: serviceCatalogueValidator(organization),
    });
  }

  transitionServiceDefinition(
    organizationId: string,
    serviceId: string,
    action: 'activations' | 'retirements',
    reason: string,
    ifMatch: string,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const organization = requireUuid(organizationId, 'organizationId');
    const service = requireUuid(serviceId, 'serviceId');
    return this.#mutation<ServiceCatalogue>({
      body: { reason },
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      ifMatch: requireStrongEtag(ifMatch),
      method: 'POST',
      path: `/v1/organizations/${organization}/services/${service}/${action}`,
      responseBody: 'json',
      signal: options.signal,
      successStatuses: [200],
      validateResponse: serviceCatalogueValidator(organization),
    });
  }

  createServiceAssignment(
    organizationId: string,
    body: ServiceAssignmentWriteRequest,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const id = requireUuid(organizationId, 'organizationId');
    return this.#mutation<ServiceAssignmentDirectory>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      method: 'POST',
      path: `/v1/organizations/${id}/service-assignments`,
      responseBody: 'json',
      signal: options.signal,
      successStatuses: [201],
      validateResponse: serviceAssignmentDirectoryValidator(id),
    });
  }

  updateServiceAssignment(
    organizationId: string,
    assignmentId: string,
    body: ServiceAssignmentWriteRequest,
    ifMatch: string,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const organization = requireUuid(organizationId, 'organizationId');
    const assignment = requireUuid(assignmentId, 'assignmentId');
    return this.#mutation<ServiceAssignmentDirectory>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      ifMatch: requireStrongEtag(ifMatch),
      method: 'PUT',
      path: `/v1/organizations/${organization}/service-assignments/${assignment}`,
      responseBody: 'json',
      signal: options.signal,
      successStatuses: [200],
      validateResponse: serviceAssignmentDirectoryValidator(organization),
    });
  }

  transitionServiceAssignment(
    organizationId: string,
    assignmentId: string,
    action: 'activations' | 'suspensions' | 'endings' | 'cancellations',
    fromState: string,
    reason: string,
    ifMatch: string,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const organization = requireUuid(organizationId, 'organizationId');
    const assignment = requireUuid(assignmentId, 'assignmentId');
    return this.#mutation<ServiceAssignmentDirectory>({
      body: { fromState, reason },
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      ifMatch: requireStrongEtag(ifMatch),
      method: 'POST',
      path: `/v1/organizations/${organization}/service-assignments/${assignment}/${action}`,
      responseBody: 'json',
      signal: options.signal,
      successStatuses: [200],
      validateResponse: serviceAssignmentDirectoryValidator(organization),
    });
  }

  createIdentifierScheme(
    organizationId: string,
    body: IdentifierSchemeWriteRequest,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const id = requireUuid(organizationId, 'organizationId');
    return this.#mutation<IdentifierSchemeDirectory>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      method: 'POST',
      path: `/v1/organizations/${id}/identifier-schemes`,
      responseBody: 'json',
      signal: options.signal,
      successStatuses: [201],
      validateResponse: identifierSchemeDirectoryValidator(id),
    });
  }

  createIdentifierSchemeVersion(
    organizationId: string,
    schemeId: string,
    body: IdentifierSchemeWriteRequest,
    ifMatch: string,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const organization = requireUuid(organizationId, 'organizationId');
    const scheme = requireUuid(schemeId, 'schemeId');
    return this.#mutation<IdentifierSchemeDirectory>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      ifMatch: requireStrongEtag(ifMatch),
      method: 'POST',
      path: `/v1/organizations/${organization}/identifier-schemes/${scheme}/versions`,
      responseBody: 'json',
      signal: options.signal,
      successStatuses: [201],
      validateResponse: identifierSchemeDirectoryValidator(organization),
    });
  }

  validateConfiguration(
    organizationId: string,
    body: ConfigurationValidationRequest,
    idempotencyKey: string,
    ifMatch?: string,
    options: ApiRequestOptions = {},
  ) {
    const id = requireUuid(organizationId, 'organizationId');
    return this.#mutation<ConfigurationActivationDirectory>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      ifMatch,
      method: 'POST',
      path: `/v1/organizations/${id}/configuration-validations`,
      responseBody: 'json',
      signal: options.signal,
      successStatuses: [201],
      validateResponse: configurationActivationDirectoryValidator(id),
    });
  }

  submitConfiguration(
    organizationId: string,
    configurationId: string,
    body: ConfigurationResultRequest,
    ifMatch: string,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const organization = requireUuid(organizationId, 'organizationId');
    const configuration = requireUuid(configurationId, 'configurationId');
    return this.#mutation<ConfigurationActivationDirectory>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      ifMatch: requireStrongEtag(ifMatch),
      method: 'POST',
      path: `/v1/organizations/${organization}/configurations/${configuration}/submissions`,
      responseBody: 'json',
      signal: options.signal,
      successStatuses: [200],
      validateResponse: configurationActivationDirectoryValidator(organization),
    });
  }

  decideConfiguration(
    organizationId: string,
    configurationId: string,
    body: ConfigurationDecisionRequest,
    ifMatch: string,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const organization = requireUuid(organizationId, 'organizationId');
    const configuration = requireUuid(configurationId, 'configurationId');
    return this.#mutation<ConfigurationActivationDirectory>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      ifMatch: requireStrongEtag(ifMatch),
      method: 'POST',
      path: `/v1/organizations/${organization}/configurations/${configuration}/decisions`,
      responseBody: 'json',
      signal: options.signal,
      successStatuses: [200],
      validateResponse: configurationActivationDirectoryValidator(organization),
    });
  }

  activateConfiguration(
    organizationId: string,
    configurationId: string,
    body: ConfigurationActivationRequest,
    ifMatch: string,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const organization = requireUuid(organizationId, 'organizationId');
    const configuration = requireUuid(configurationId, 'configurationId');
    return this.#mutation<ConfigurationActivationDirectory>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      ifMatch: requireStrongEtag(ifMatch),
      method: 'POST',
      path: `/v1/organizations/${organization}/configurations/${configuration}/activations`,
      responseBody: 'json',
      signal: options.signal,
      successStatuses: [200],
      validateResponse: configurationActivationDirectoryValidator(organization),
    });
  }

  requestEvidenceExport(
    organizationId: string,
    body: EvidenceExportRequest,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const id = requireUuid(organizationId, 'organizationId');
    return this.#mutation<EvidenceExportDirectory>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      method: 'POST',
      path: `/v1/organizations/${id}/evidence-exports`,
      responseBody: 'json',
      signal: options.signal,
      successStatuses: [201],
      validateResponse: evidenceExportDirectoryValidator(id),
    });
  }

  decideEvidenceExport(
    organizationId: string,
    exportId: string,
    authorize: boolean,
    reason: string,
    ifMatch: string,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const organization = requireUuid(organizationId, 'organizationId');
    const exportJob = requireUuid(exportId, 'exportId');
    const body: EvidenceExportDecisionRequest = { authorize, reason };
    return this.#mutation<EvidenceExportDirectory>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      ifMatch: requireStrongEtag(ifMatch),
      method: 'POST',
      path: `/v1/organizations/${organization}/evidence-exports/${exportJob}/decisions`,
      responseBody: 'json',
      signal: options.signal,
      successStatuses: [200],
      validateResponse: evidenceExportDirectoryValidator(organization),
    });
  }

  accessEvidenceExport(
    organizationId: string,
    exportId: string,
    purposeCode: EvidenceExportAccessRequest['purposeCode'],
    reason: string,
    ifMatch: string,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const organization = requireUuid(organizationId, 'organizationId');
    const exportJob = requireUuid(exportId, 'exportId');
    const body: EvidenceExportAccessRequest = { purposeCode, reason };
    return this.#mutation<EvidenceExportAccessResponse>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      ifMatch: requireStrongEtag(ifMatch),
      method: 'POST',
      path: `/v1/organizations/${organization}/evidence-exports/${exportJob}/accesses`,
      responseBody: 'json',
      signal: options.signal,
      successStatuses: [200],
      validateResponse: evidenceExportAccessValidator(exportJob),
    });
  }

  getWorkforceScreen(
    organizationId: string,
    screenId: string,
    query: WorkforceScreenQuery = {},
    options: ApiRequestOptions = {},
  ) {
    const organization = requireUuid(organizationId, 'organizationId');
    const screen = requireWorkforceScreenId(screenId);
    return this.#request<GetWorkforceScreenResponse>({
      method: 'GET',
      path: `/v1/organizations/${organization}/workforce/screens/${screen}${workforceScreenQuery(query)}`,
      responseBody: 'json',
      signal: options.signal,
      successStatuses: [200],
      validateResponse: workforceScreenValidator(organization, screen),
    });
  }

  performWorkforceAction(
    organizationId: string,
    screenId: string,
    actionKey: string,
    body: WorkforceActionRequest,
    ifMatch: string | undefined,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const organization = requireUuid(organizationId, 'organizationId');
    const screen = requireWorkforceScreenId(screenId);
    const action = requireWorkforceActionKey(actionKey);
    return this.#mutation<PerformWorkforceActionResponse>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      ...(ifMatch === undefined ? {} : { ifMatch: requireStrongEtag(ifMatch) }),
      method: 'POST',
      path: `/v1/organizations/${organization}/workforce/screens/${screen}/actions/${action}`,
      responseBody: 'json',
      signal: options.signal,
      successStatuses: [200, 201],
      validateResponse: workforceScreenValidator(organization, screen),
    });
  }

  previewWorkforceImpact(
    organizationId: string,
    screenId: string,
    actionKey: string,
    body: WorkforceActionRequest,
    ifMatch: string,
    expectedRevision: number,
    options: ApiRequestOptions = {},
  ) {
    const organization = requireUuid(organizationId, 'organizationId');
    const screen = requireWorkforceScreenId(screenId);
    const action = requireWorkforceActionKey(actionKey);
    const targetId = requireUuid(body.targetId ?? '', 'targetId');
    if (!Number.isSafeInteger(expectedRevision) || expectedRevision < 0) {
      throw new Error('Workforce impact preview revision is invalid.');
    }
    return this.#mutation<WorkforceImpactPreviewResponse>({
      body,
      ifMatch: requireStrongEtag(ifMatch),
      method: 'POST',
      path: `/v1/organizations/${organization}/workforce/screens/${screen}/actions/${action}/impact-preview`,
      responseBody: 'json',
      signal: options.signal,
      successStatuses: [200],
      validateResponse: workforceImpactPreviewValidator(
        screen,
        action,
        targetId,
        expectedRevision,
      ),
    });
  }

  accessWorkforceExport(
    organizationId: string,
    exportId: string,
    body: WorkforceExportAccessRequest,
    ifMatch: string,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const organization = requireUuid(organizationId, 'organizationId');
    const exportJob = requireUuid(exportId, 'exportId');
    const purposeKey = body.purposeKey.trim();
    const reason = body.reason.trim().normalize('NFC');
    if (!/^[a-z][a-z0-9_]{1,79}$/.test(purposeKey)) {
      throw new Error('Workforce export purposeKey is invalid.');
    }
    const reasonLength = Array.from(reason).length;
    if (reasonLength < 10 || reasonLength > 500) {
      throw new Error('Workforce export access reason must contain 10 to 500 characters.');
    }
    return this.#mutation<WorkforceExportAccessResponse>({
      body: { purposeKey, reason },
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      ifMatch: requireStrongEtag(ifMatch),
      method: 'POST',
      path: `/v1/organizations/${organization}/workforce/exports/${exportJob}/accesses`,
      responseBody: 'json',
      signal: options.signal,
      successStatuses: [200],
      validateResponse: workforceExportAccessValidator(organization, exportJob),
    });
  }

  accessWorkforceEvidence(
    organizationId: string,
    evidenceId: string,
    body: WorkforceEvidenceAccessRequest,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const organization = requireUuid(organizationId, 'organizationId');
    const evidence = requireUuid(evidenceId, 'evidenceId');
    const memberId = body.memberId == null ? null : requireUuid(body.memberId, 'memberId');
    if (
      body.projection !== 'workforce-audit-detail-v1' &&
      body.projection !== 'member-evidence-detail-v1'
    ) {
      throw new Error('Workforce evidence projection is invalid.');
    }
    if (body.projection === 'member-evidence-detail-v1' && memberId === null) {
      throw new Error('A workforce member is required for member evidence detail.');
    }
    const purposes = new Set([
      'workforce_operations',
      'credentialing_review',
      'regulatory_evidence',
      'security_investigation',
      'employment_record_request',
      'data_correction',
    ]);
    if (!purposes.has(body.purposeCode)) {
      throw new Error('Workforce evidence purposeCode is invalid.');
    }
    const reason = body.reason.trim().normalize('NFC');
    const reasonLength = Array.from(reason).length;
    if (reasonLength < 10 || reasonLength > 500) {
      throw new Error('Workforce evidence access reason must contain 10 to 500 characters.');
    }
    const request: WorkforceEvidenceAccessRequest = {
      ...(memberId === null ? {} : { memberId }),
      projection: body.projection,
      purposeCode: body.purposeCode,
      reason,
    };
    return this.#mutation<WorkforceEvidenceAccessResponse>({
      body: request,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      method: 'POST',
      path: `/v1/organizations/${organization}/workforce/evidence/${evidence}/accesses`,
      responseBody: 'json',
      signal: options.signal,
      successStatuses: [200],
      validateResponse: workforceEvidenceAccessValidator(evidence, memberId),
    });
  }

  accessWorkforceCredentialDocument(
    organizationId: string,
    credentialId: string,
    documentId: string,
    body: WorkforceCredentialDocumentAccessRequest,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const organization = requireUuid(organizationId, 'organizationId');
    const credential = requireUuid(credentialId, 'credentialId');
    const document = requireUuid(documentId, 'documentId');
    const purposes = new Set([
      'credentialing_review',
      'regulatory_evidence',
      'security_investigation',
      'employment_record_request',
      'data_correction',
    ]);
    if (!purposes.has(body.purposeCode)) {
      throw new Error('Credential-document purposeCode is invalid.');
    }
    const reason = body.reason.trim().normalize('NFC');
    const reasonLength = Array.from(reason).length;
    if (reasonLength < 10 || reasonLength > 500) {
      throw new Error('Credential-document access reason must contain 10 to 500 characters.');
    }
    return this.#mutation<WorkforceCredentialDocumentAccessResponse>({
      body: { purposeCode: body.purposeCode, reason },
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      method: 'POST',
      path: `/v1/organizations/${organization}/workforce/credentials/${credential}/documents/${document}/accesses`,
      responseBody: 'json',
      signal: options.signal,
      successStatuses: [200],
      validateResponse: workforceCredentialDocumentAccessValidator(credential, document),
    });
  }

  uploadWorkforceCredentialDocument(
    organizationId: string,
    credentialId: string,
    metadata: CredentialDocumentMetadata,
    file: Blob | File,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const organization = requireUuid(organizationId, 'organizationId');
    const credential = requireUuid(credentialId, 'credentialId');
    if (metadata.memberId !== undefined && metadata.memberId !== null) {
      requireUuid(metadata.memberId, 'memberId');
    }
    if (!/^[0-9a-f]{64}$/.test(metadata.sha256)) {
      throw new Error('Credential document sha256 must be a lower-case SHA-256 digest.');
    }
    if (!metadata.retentionClass.trim() || Array.from(metadata.retentionClass).length > 80) {
      throw new Error('Credential document retentionClass must contain 1 to 80 characters.');
    }
    const reasonLength = Array.from(metadata.reason.trim()).length;
    if (reasonLength < 10 || reasonLength > 500) {
      throw new Error('Credential document reason must contain 10 to 500 characters.');
    }
    if (file.size < 1 || file.size > 20 * 1024 * 1024) {
      throw new Error('Credential document file must contain 1 byte to 20 MiB.');
    }
    const permittedDocumentTypes = new Map([
      ['application/pdf', ['.pdf']],
      ['image/jpeg', ['.jpg', '.jpeg']],
      ['image/png', ['.png']],
    ]);
    const permittedExtensions = permittedDocumentTypes.get(file.type);
    if (!permittedExtensions) {
      throw new Error('Credential document file must be a PDF, JPEG, or PNG.');
    }
    const suppliedName = typeof File !== 'undefined' && file instanceof File
      ? file.name.normalize('NFC')
      : `credential-document${permittedExtensions[0]}`;
    if (!permittedExtensions.some((extension) => suppliedName.toLowerCase().endsWith(extension))) {
      throw new Error('Credential document name does not match its declared media type.');
    }

    const formData = new FormData();
    formData.append(
      'metadata',
      new Blob([JSON.stringify(metadata)], { type: 'application/json' }),
    );
    formData.append(
      'file',
      file,
      suppliedName,
    );

    return this.#mutation<WorkforceScreen>({
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      method: 'POST',
      multipartBody: formData,
      path: `/v1/organizations/${organization}/workforce/credentials/${credential}/documents`,
      responseBody: 'json',
      signal: options.signal,
      successStatuses: [201],
      validateResponse: workforceScreenValidator(organization, 'M2-10'),
    });
  }

  selectOrganization(body: SelectOrganizationData['body'], options: ApiRequestOptions = {}) {
    return this.#mutation<SelectOrganizationResponse>({
      body,
      path: relativeEndpointPath(endpoints.selectOrganization.path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.selectOrganization.successStatuses,
    });
  }
}

export function createCareOsApiClient(options: CareOsApiClientOptions = {}) {
  return new CareOsApiClient(options);
}

export const careOsApi = createCareOsApiClient();
