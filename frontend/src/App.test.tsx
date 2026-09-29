import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import type { ApiFailure, ApiResult } from './api/client';
import type {
  AdministrationReadiness,
  AiScreen,
  AssessmentScreen,
  BillingScreen,
  CarePlanScreen,
  DocumentScreen,
  FacilityDirectory,
  EncounterScreen,
  FollowupScreen,
  IntegrationScreen,
  OrganizationUnitDirectory,
  ServiceLocationDirectory,
  OrganizationAddress,
  OrganizationAccess,
  OrganizationContact,
  OrganizationContactCollection,
  OrganizationIdentifier,
  OrganizationIdentifierCollection,
  OrganizationMembershipPage,
  OrganizationProfile,
  PatientRegistryScreen,
  ReportingScreen,
  ReadinessGate,
  SchedulingScreen,
  SessionState,
  WorkforceScreen,
} from './api/generated';
import App from './App';
import { findScreen, screens } from './data/screens';
import type { AdministrationClient } from './features/administration/administration-types';
import type { AiClient } from './features/ai/ai-types';
import type { AssessmentClient } from './features/assessment/assessment-types';
import type { BillingClient } from './features/billing/billing-types';
import type { CarePlanClient } from './features/careplan/care-plan-types';
import type { DocumentClient } from './features/document/document-types';
import type { EncounterClient } from './features/encounter/encounter-types';
import type { FollowupClient } from './features/followup/followup-types';
import type { IntegrationClient } from './features/integration/integration-types';
import type { PatientRegistryClient } from './features/patient/patient-types';
import type { ReportingClient } from './features/reporting/reporting-types';
import type { SchedulingClient } from './features/scheduling/scheduling-types';
import type { SessionClient } from './features/session/session-types';
import type { WorkforceClient } from './features/workforce/workforce-types';

const correlationId = 'frontend-session-test';
const user = {
  displayName: 'Asha Verma',
  email: 'asha@example.test',
  id: '11111111-1111-4111-8111-111111111111',
};
const selectedOrganization: OrganizationAccess = {
  displayName: 'North Clinic',
  id: '22222222-2222-4222-8222-222222222222',
  roleKeys: ['organization_owner'],
  selected: true,
  status: 'active',
};
const facilityDirectory: FacilityDirectory = {
  organizationId: selectedOrganization.id,
  canCreate: true,
  canManageLifecycle: true,
  evaluatedAt: '2026-09-18T10:00:00Z',
  facilityTypes: [{ key: 'care_site', displayName: 'Care site' }],
  facilities: [
    {
      facilityId: '33333333-3333-4333-8333-333333333333',
      facilityCode: 'NORTH-01',
      legalName: 'North Clinic Limited',
      displayName: 'North Clinic',
      facilityType: 'care_site',
      timezone: 'Asia/Kolkata',
      status: 'draft',
      lockVersion: 0,
      createdAt: '2026-09-18T10:00:00Z',
      updatedAt: '2026-09-18T10:00:00Z',
    },
  ],
};
const unitDirectory: OrganizationUnitDirectory = {
  organizationId: selectedOrganization.id,
  facilityId: facilityDirectory.facilities[0]!.facilityId,
  canManage: true,
  canManageLifecycle: true,
  evaluatedAt: '2026-09-20T10:00:00Z',
  units: [
    {
      unitId: '44444444-4444-4444-8444-444444444444',
      unitCode: 'CLINICAL',
      unitType: 'department',
      name: 'Clinical Services',
      effectiveFrom: '2026-09-20T00:00:00Z',
      status: 'draft',
      lockVersion: 0,
      createdAt: '2026-09-20T10:00:00Z',
      updatedAt: '2026-09-20T10:00:00Z',
    },
    {
      unitId: '55555555-5555-4555-8555-555555555555',
      parentId: '44444444-4444-4444-8444-444444444444',
      unitCode: 'CARDIOLOGY',
      unitType: 'unit',
      name: 'Cardiology',
      effectiveFrom: '2026-09-20T00:00:00Z',
      status: 'draft',
      lockVersion: 2,
      createdAt: '2026-09-20T10:00:00Z',
      updatedAt: '2026-09-20T10:00:00Z',
    },
  ],
};
const locationDirectory: ServiceLocationDirectory = {
  organizationId: selectedOrganization.id,
  facilityId: facilityDirectory.facilities[0]!.facilityId,
  canManage: true,
  canManageLifecycle: true,
  evaluatedAt: '2026-09-20T10:00:00Z',
  locations: [
    {
      locationId: '66666666-6666-4666-8666-666666666666',
      addressId: '66666666-6666-4666-8666-666666666666',
      locationCode: 'MAIN_CLINIC',
      locationType: 'physical',
      name: 'Main clinic',
      capacity: 20,
      effectiveFrom: '2026-09-20T00:00:00Z',
      status: 'draft',
      lockVersion: 0,
      createdAt: '2026-09-20T10:00:00Z',
      updatedAt: '2026-09-20T10:00:00Z',
    },
    {
      locationId: '88888888-8888-4888-8888-888888888888',
      parentId: '66666666-6666-4666-8666-666666666666',
      locationCode: 'SATELLITE',
      locationType: 'virtual',
      name: 'Satellite room',
      virtualServiceType: 'video consultation',
      effectiveFrom: '2026-09-20T00:00:00Z',
      status: 'draft',
      lockVersion: 2,
      createdAt: '2026-09-20T10:00:00Z',
      updatedAt: '2026-09-20T10:00:00Z',
    },
  ],
};
const otherOrganization: OrganizationAccess = {
  displayName: 'South Clinic',
  id: '33333333-3333-4333-8333-333333333333',
  roleKeys: ['organization_viewer'],
  selected: false,
  status: 'draft',
};
const anonymousSession: SessionState = {
  mfaEnabled: false,
  mfaRequired: false,
  recentAuthentication: false,
  state: 'anonymous',
  user: null,
};
const authenticatedSession: SessionState = {
  mfaEnabled: false,
  mfaRequired: false,
  recentAuthentication: true,
  state: 'authenticated',
  user,
};
const mfaSession: SessionState = {
  mfaEnabled: true,
  mfaRequired: false,
  recentAuthentication: false,
  state: 'mfa_required',
  user,
};
const mfaEnrollmentSession: SessionState = {
  mfaEnabled: false,
  mfaRequired: true,
  recentAuthentication: true,
  state: 'mfa_enrollment_required',
  user,
};
const readinessEvaluatedAt = new Date(Date.now() - 60_000);
readinessEvaluatedAt.setMilliseconds(0);
const readinessExpiresAt = new Date(readinessEvaluatedAt.getTime() + 15 * 60_000);

function gate(
  key: ReadinessGate['key'],
  label: string,
  outcome: ReadinessGate['outcome'],
  detail: string,
  href: string,
): ReadinessGate {
  return {
    detail,
    evidenceReferences: [],
    href,
    key,
    label,
    outcome,
    reasonCode: 'm1.readiness.test_fixture',
    remediationCode: 'm1.remediation.test_fixture',
    version: 'm1-readiness-v1',
  };
}

const readiness: AdministrationReadiness = {
  activeMemberships: 2,
  blockedGates: 10,
  catalogueVersion: 'm1-readiness-v1',
  completedGates: 3,
  draftFacilityCount: 1,
  evaluatedAt: readinessEvaluatedAt.toISOString(),
  expiresAt: readinessExpiresAt.toISOString(),
  facilityCount: 1,
  gates: [
    gate(
      'organization.profile.complete',
      'Organization profile',
      'complete',
      'The approved organization profile is complete at the current revision.',
      '#/M1-07',
    ),
    gate(
      'organization.identifier.primary_verified',
      'Primary registration identifier',
      'blocked',
      'A verified primary registration identifier is required.',
      '#/M1-08',
    ),
    gate(
      'organization.contact.coverage',
      'Address and contact coverage',
      'blocked',
      'Registered address and operational contact coverage are missing.',
      '#/M1-09',
    ),
    gate(
      'organization.governance.coverage',
      'Governance responsibility coverage',
      'blocked',
      'Required governance responsibilities are missing.',
      '#/M1-11',
    ),
    gate(
      'access.final_owner',
      'Final owner protection',
      'complete',
      'An active indefinite owner remains after every open demotion.',
      '#/M1-20',
    ),
    gate(
      'access.mfa_enforced',
      'Mandatory-role MFA',
      'complete',
      'Every mandatory-role account has MFA enabled.',
      '#/M1-03',
    ),
    gate(
      'network.facility.minimum',
      'Minimum eligible facility',
      'blocked',
      'No eligible facility has complete approved configuration.',
      '#/M1-12',
    ),
    gate(
      'network.hierarchy.valid',
      'Network hierarchy',
      'blocked',
      'The network hierarchy evaluator is unavailable.',
      '#/M1-14',
    ),
    gate(
      'network.hours.valid',
      'Operating hours',
      'blocked',
      'Approved operating-hours evaluation is unavailable.',
      '#/M1-16',
    ),
    gate(
      'service.catalogue.active',
      'Active service catalogue',
      'blocked',
      'An eligible service catalogue is unavailable.',
      '#/M1-17',
    ),
    gate(
      'service.assignment.valid',
      'Service assignments',
      'warning',
      'Service delivery has not been declared.',
      '#/M1-18',
    ),
    gate(
      'identifier.scheme.active',
      'Identifier scheme',
      'not_applicable',
      'Identifier issuance is not declared.',
      '#/M1-19',
    ),
    gate(
      'configuration.integrity',
      'Configuration integrity',
      'blocked',
      'Versioned configuration integrity is unavailable.',
      '#/M1-21',
    ),
    gate(
      'governance.registry.active',
      'Governance registries',
      'blocked',
      'The full approved registry set is not active.',
      '#/M1-21',
    ),
    gate(
      'platform.dependencies.ready',
      'Required platform dependencies',
      'blocked',
      'Fresh required dependency readiness is unavailable.',
      '#/M1-21',
    ),
  ],
  lifecycleStatus: 'draft',
  notApplicableGates: 1,
  organizationId: selectedOrganization.id,
  organizationRevision: 4,
  totalGates: 15,
  warningGates: 1,
};
const organizationProfile: OrganizationProfile = {
  countryCode: 'IN',
  displayName: 'North Clinic',
  editable: true,
  legalName: 'North Clinic Private Limited',
  lifecycleStatus: 'draft',
  locale: 'en-IN',
  lockVersion: 4,
  organizationId: selectedOrganization.id,
  organizationType: 'care_provider',
  timezone: 'Asia/Kolkata',
  tradingName: null,
  updatedAt: '2026-09-16T08:00:00Z',
};
const organizationIdentifier: OrganizationIdentifier = {
  assigningAuthority: 'National Provider Registry',
  availableActions: ['edit', 'verify'],
  createdAt: '2026-09-16T08:00:00Z',
  effectiveFrom: '2026-09-16T08:00:00Z',
  effectiveTo: null,
  evidenceReference: null,
  expiryDate: null,
  identifierId: '44444444-4444-4444-8444-444444444444',
  identifierType: 'registration',
  isPrimary: true,
  issueDate: '2026-09-01',
  jurisdictionCountryCode: 'IN',
  lockVersion: 0,
  status: 'draft',
  supersedesId: null,
  typeDisplayName: 'Registration identifier',
  updatedAt: '2026-09-16T08:00:00Z',
  value: 'REG-IN-0042',
  verificationStatus: 'unverified',
};
const organizationIdentifiers: OrganizationIdentifierCollection = {
  canCreate: true,
  items: [organizationIdentifier],
  organizationId: selectedOrganization.id,
  types: [
    {
      displayName: 'Registration identifier',
      jurisdictionCountryCode: null,
      key: 'registration',
      primaryRequired: true,
    },
  ],
};
const organizationAddress: OrganizationAddress = {
  addressId: '66666666-6666-4666-8666-666666666666',
  addressLines: ['42 Care Street', 'Andheri East'],
  addressType: 'registered',
  availableActions: ['supersede', 'end'],
  countryCode: 'IN',
  createdAt: '2026-09-16T08:00:00Z',
  effectiveFrom: '2026-09-16T08:00:00Z',
  effectiveTo: null,
  isPrimary: true,
  locality: 'Mumbai',
  lockVersion: 0,
  postcode: '400069',
  region: 'Maharashtra',
  status: 'active',
  supersedesId: null,
  updatedAt: '2026-09-16T08:00:00Z',
  validationSource: 'Approved postal source',
  validationStatus: 'validated',
};
const organizationContact: OrganizationContact = {
  availableActions: ['verify', 'supersede', 'end'],
  channel: 'email',
  contactId: '77777777-7777-4777-8777-777777777770',
  createdAt: '2026-09-16T08:00:00Z',
  effectiveFrom: '2026-09-16T08:00:00Z',
  effectiveTo: null,
  isPreferred: true,
  isPrimary: true,
  lockVersion: 0,
  maskedValue: 'o***@***.org',
  purpose: 'operational',
  purposeDisplayName: 'Operational contact',
  status: 'active',
  supersedesId: null,
  updatedAt: '2026-09-16T08:00:00Z',
  verificationStatus: 'unverified',
};
const organizationContacts: OrganizationContactCollection = {
  addressTypes: ['registered', 'postal', 'service', 'billing'],
  addresses: [organizationAddress],
  canCreate: true,
  contacts: [organizationContact],
  organizationId: selectedOrganization.id,
  purposes: [
    {
      displayName: 'Operational contact',
      key: 'operational',
      publicProjectionAllowed: false,
    },
  ],
};
const membershipPage: OrganizationMembershipPage = {
  asOf: '2026-09-17T05:30:00Z',
  availableActions: [
    'issueInvitation',
    'approveMembershipChange',
    'executeMembershipChange',
    'approveOwnerTransfer',
    'executeOwnerTransfer',
  ],
  items: [
    {
      accessState: 'active',
      accountStatus: 'active',
      availableActions: [
        'requestMfaReset',
        'requestRoleChange',
        'requestRevocation',
        'requestOwnerTransfer',
      ],
      displayName: 'Ravi Shah',
      effectiveFrom: '2026-08-01T06:00:00Z',
      effectiveTo: null,
      email: 'ravi.shah@example.test',
      finalOwner: false,
      lockVersion: 0,
      membershipId: '77777777-7777-4777-8777-777777777777',
      mfaEnabled: true,
      roleDisplayName: 'Security administrator',
      roleKey: 'security_administrator',
      roleStatus: 'active',
      userId: '88888888-8888-4888-8888-888888888888',
    },
  ],
  organizationId: selectedOrganization.id,
  page: { hasMore: false, limit: 25, nextCursor: null },
};
type ApplicationClient = SessionClient &
  AdministrationClient &
  AiClient &
  CarePlanClient &
  FollowupClient &
  Partial<BillingClient> &
  Partial<ReportingClient> &
  Partial<IntegrationClient> &
  WorkforceClient &
  PatientRegistryClient &
  SchedulingClient &
  EncounterClient &
  AssessmentClient &
  DocumentClient;

const patientRegistryProjection: PatientRegistryScreen = {
  actions: [],
  columns: [
    { key: 'primary', label: 'Record' },
    { key: 'secondary', label: 'Type or identifier' },
    { key: 'context', label: 'Context' },
    { key: 'status', label: 'Status' },
  ],
  generatedAt: '2026-09-26T08:00:00Z',
  metrics: [],
  nextCursor: null,
  notices: [],
  organizationId: selectedOrganization.id,
  pageSize: 25,
  purpose: 'Review organization-local patient activity, registration and duplicate work.',
  rows: [],
  screenId: 'P3-01',
  title: 'Patient registry dashboard',
};

const patientIdentityProjection: PatientRegistryScreen = {
  ...patientRegistryProjection,
  actions: [
    {
      fields: [
        {
          inputType: 'text',
          key: 'nameToUse',
          label: 'Name to use',
          options: [],
          required: false,
        },
        {
          inputType: 'select',
          key: 'nameState',
          label: 'Name state',
          options: [{ label: 'Provided', value: 'provided' }],
          required: true,
        },
        {
          inputType: 'select',
          key: 'birthDateCertainty',
          label: 'Birth-date certainty',
          options: [{ label: 'Exact', value: 'exact' }],
          required: true,
        },
        {
          inputType: 'text',
          key: 'provenanceCode',
          label: 'Provenance code',
          options: [],
          required: true,
        },
      ],
      href: null,
      ifMatchRequired: true,
      key: 'correct-identity',
      label: 'Save identity correction',
      reasonRequired: true,
      style: 'primary',
      targetRequired: true,
    },
  ],
  purpose: 'Maintain patient identity with partial-date and provenance semantics.',
  rows: [
    {
      allowedActionKeys: ['correct-identity'],
      etag: '"m3:P3-05:33333333-3333-4333-8333-333333333333:2"',
      id: '33333333-3333-4333-8333-333333333333',
      patientId: '33333333-3333-4333-8333-333333333333',
      revision: 2,
      status: 'draft',
      values: {
        context: 'Birth date not supplied',
        primary: 'Temporary patient',
        secondary: 'PT-•••1234',
      },
    },
  ],
  screenId: 'P3-05',
  title: 'Identity and demographics',
};

const schedulingRequestId = '99999999-9999-4999-8999-999999999991';
const schedulingProjection: SchedulingScreen = {
  actions: [
    {
      fields: [],
      href: null,
      ifMatchRequired: true,
      key: 'confirm-appointment',
      label: 'Confirm appointment',
      reasonRequired: true,
      style: 'primary',
      targetRequired: true,
    },
  ],
  columns: [
    { key: 'primary', label: 'Request' },
    { key: 'secondary', label: 'Patient' },
    { key: 'context', label: 'Context' },
    { key: 'status', label: 'Status' },
  ],
  generatedAt: '2026-09-26T08:00:00Z',
  metrics: [],
  nextCursor: null,
  notices: [],
  organizationId: selectedOrganization.id,
  pageSize: 25,
  purpose: 'Re-evaluate eligibility and consume the held slot atomically.',
  rows: [
    {
      allowedActionKeys: ['confirm-appointment'],
      appointmentId: null,
      etag: `"m4:P4-11:${schedulingRequestId}:3"`,
      id: schedulingRequestId,
      patientId: '33333333-3333-4333-8333-333333333333',
      revision: 3,
      status: 'held',
      values: {
        context: 'Physiotherapy',
        primary: 'Request 99999999',
        secondary: 'PT-•••1234',
      },
    },
  ],
  screenId: 'P4-11',
  title: 'Confirmation',
};

const encounterId = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const encounterNoteId = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
const encounterProjection: EncounterScreen = {
  actions: [
    {
      fields: [
        {
          inputType: 'textarea',
          key: 'content',
          label: 'Clinical note',
          options: [],
          required: true,
        },
        {
          inputType: 'checkbox',
          key: 'lateEntry',
          label: 'Late entry',
          options: [],
          required: false,
        },
      ],
      href: null,
      ifMatchRequired: true,
      key: 'save-note-version',
      label: 'Save note version',
      reasonRequired: false,
      style: 'primary',
      targetRequired: true,
    },
  ],
  columns: [
    { key: 'primary', label: 'Note' },
    { key: 'context', label: 'Context' },
    { key: 'status', label: 'Status' },
  ],
  generatedAt: '2026-09-26T09:00:00Z',
  metrics: [],
  nextCursor: null,
  notices: [],
  organizationId: selectedOrganization.id,
  pageSize: 25,
  purpose: 'Create append-only, digest-bound draft note versions.',
  rows: [
    {
      allowedActionKeys: ['save-note-version'],
      appointmentId: null,
      encounterId,
      episodeId: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
      etag: `"m5:P5-09:${encounterNoteId}:4"`,
      id: encounterNoteId,
      patientId: '33333333-3333-4333-8333-333333333333',
      revision: 4,
      status: 'draft',
      values: { context: 'Progress note', noteId: encounterNoteId, primary: 'Version 4' },
    },
  ],
  screenId: 'P5-09',
  title: 'Encounter notes',
};

const assessmentSessionId = 'dddddddd-dddd-4ddd-8ddd-dddddddddddd';
const assessmentProjection: AssessmentScreen = {
  actions: [],
  columns: [
    { key: 'primary', label: 'Patient' },
    { key: 'clinician', label: 'Responsible clinician' },
    { key: 'completion', label: 'Completion' },
  ],
  generatedAt: '2026-09-28T09:00:00Z',
  metrics: [],
  nextCursor: null,
  notices: [
    {
      detail: 'Protected visual source package is unavailable; this is a safe structural runtime.',
      title: 'Source package unavailable',
      tone: 'warning',
    },
  ],
  organizationId: selectedOrganization.id,
  pageSize: 25,
  purpose: 'Capture versioned, sourced and attributed clinical assessment evidence.',
  rows: [
    {
      allowedActionKeys: [],
      assessmentSessionId,
      encounterId,
      etag: `"m6:COS-09:${assessmentSessionId}:3"`,
      id: assessmentSessionId,
      patientId: '33333333-3333-4333-8333-333333333333',
      revision: 3,
      status: 'in_progress',
      values: {
        clinician: 'Dr Asha Verma',
        completion: '8/27',
        patientAlerts: '0',
        patientVerification: 'verified',
        primary: 'Samira Patel',
        responsiblePractitionerId: 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee',
        secondary: 'PT-•••1234',
        sourcePackageStatus: 'unavailable',
      },
    },
  ],
  screenId: 'COS-09',
  title: 'Clinical examination',
};

const documentId = 'ffffffff-ffff-4fff-8fff-ffffffffffff';
const documentVersionId = '12121212-1212-4212-8212-121212121212';
const documentProjection: DocumentScreen = {
  actions: [],
  columns: [
    { key: 'primary', label: 'Document' },
    { key: 'secondary', label: 'Patient' },
    { key: 'context', label: 'Context' },
  ],
  generatedAt: '2026-09-28T10:00:00Z',
  metrics: [],
  nextCursor: null,
  notices: [],
  organizationId: selectedOrganization.id,
  pageSize: 25,
  purpose: 'Review document processing, results and acknowledgement safety.',
  rows: [
    {
      allowedActionKeys: [],
      documentId,
      documentVersionId,
      etag: `"m7:P7-01:${documentId}:2"`,
      id: documentId,
      patientId: '33333333-3333-4333-8333-333333333333',
      revision: 2,
      status: 'clean',
      values: {
        context: 'Clean promoted version',
        primary: 'Laboratory report',
        secondary: 'PT-•••1234',
      },
    },
  ],
  screenId: 'P7-01',
  title: 'Document dashboard',
};

const aiSessionId = '14141414-1414-4414-8414-141414141414';
const aiProjection: AiScreen = {
  actions: [],
  columns: [
    { key: 'patient', label: 'Patient' },
    { key: 'sessionType', label: 'Task' },
    { key: 'purpose', label: 'Purpose' },
  ],
  generatedAt: '2026-09-28T11:00:00Z',
  metrics: [{ key: 'sessions', label: 'AI sessions', tone: 'neutral', value: 1 }],
  nextCursor: null,
  notices: [
    {
      detail: 'No provider output changes a clinical source of truth without clinician review.',
      title: 'AI output is always a draft',
      tone: 'warning',
    },
  ],
  organizationId: selectedOrganization.id,
  pageSize: 25,
  purpose: 'Review immutable AI session history.',
  rows: [
    {
      allowedActionKeys: [],
      encounterId,
      etag: `"m8:P8-10:${aiSessionId}:4"`,
      id: aiSessionId,
      patientId: '33333333-3333-4333-8333-333333333333',
      revision: 4,
      status: 'accepted',
      values: {
        patient: '••••1234',
        purpose: 'Clinical documentation',
        sessionType: 'Summary',
      },
    },
  ],
  screenId: 'P8-10',
  title: 'AI session history',
};

const carePlanId = '15151515-1515-4515-8515-151515151515';
const carePlanVersionId = '16161616-1616-4616-8616-161616161616';
const carePlanProjection: CarePlanScreen = {
  actions: [],
  columns: [
    { key: 'planTitle', label: 'Plan' },
    { key: 'patient', label: 'Patient' },
    { key: 'reviewState', label: 'Review state' },
  ],
  generatedAt: '2026-09-28T12:00:00Z',
  metrics: [{ key: 'draft', label: 'Draft plans', tone: 'warning', value: 1 }],
  nextCursor: null,
  notices: [],
  organizationId: selectedOrganization.id,
  pageSize: 25,
  purpose: 'Review coordinated-plan readiness, ownership, safety and lifecycle state.',
  rows: [
    {
      allowedActionKeys: [],
      carePlanVersionId,
      encounterId,
      etag: `"m9:P9-01:${carePlanId}:5"`,
      id: carePlanId,
      patientId: '33333333-3333-4333-8333-333333333333',
      revision: 5,
      status: 'draft',
      values: {
        patient: 'PT-•••1234',
        planTitle: 'Coordinated recovery plan',
        reviewState: 'Ownership and safety review pending',
      },
    },
  ],
  screenId: 'P9-01',
  title: 'Care plan dashboard',
};

const followupPlanId = '17171717-1717-4717-8717-171717171717';
const escalationId = '18181818-1818-4818-8818-181818181818';
const followupProjection: FollowupScreen = {
  actions: [],
  columns: [
    { key: 'severity', label: 'Severity' },
    { key: 'threshold', label: 'Threshold' },
  ],
  generatedAt: '2026-09-28T12:30:00Z',
  metrics: [{ key: 'openEscalations', label: 'Open escalations', tone: 'warning', value: 1 }],
  nextCursor: null,
  notices: [],
  organizationId: selectedOrganization.id,
  pageSize: 25,
  purpose: 'Acknowledge and resolve owned threshold breaches with explicit evidence.',
  rows: [
    {
      allowedActionKeys: [],
      clinicalTaskId: '19191918-1918-4918-8918-191919191918',
      encounterId,
      escalationEventId: escalationId,
      etag: `"m10:P10-05:${escalationId}:0"`,
      followupPlanId,
      id: escalationId,
      outcomeMeasurementId: '20202018-2020-4020-8020-202020202018',
      patientId: '33333333-3333-4333-8333-333333333333',
      revision: 0,
      status: 'open',
      values: {
        severity: 'critical',
        threshold: 'gt 10',
      },
    },
  ],
  screenId: 'P10-05',
  title: 'Escalation',
};

const billingInvoiceId = '21212118-2121-4121-8121-212121212118';
const billingProjection: BillingScreen = {
  actions: [],
  columns: [
    { key: 'number', label: 'Invoice' },
    { key: 'balanceMinor', label: 'Balance' },
    { key: 'currency', label: 'Currency' },
  ],
  generatedAt: '2026-09-28T13:00:00Z',
  metrics: [{ key: 'openInvoices', label: 'Open invoices', tone: 'warning', value: 1 }],
  nextCursor: null,
  notices: [],
  organizationId: selectedOrganization.id,
  pageSize: 25,
  purpose: 'Record non-card manual settlement evidence.',
  rows: [
    {
      allowedActionKeys: [],
      etag: `"m11:P11-06:${billingInvoiceId}:2"`,
      id: billingInvoiceId,
      invoiceId: billingInvoiceId,
      patientId: '33333333-3333-4333-8333-333333333333',
      revision: 2,
      status: 'partially_paid',
      values: { balanceMinor: '18600', currency: 'INR', number: 'INV-2026-0001' },
    },
  ],
  screenId: 'P11-06',
  title: 'Payment',
};

const reportingRunId = '22222228-2228-4228-8228-222222222228';
const reportingProjection: ReportingScreen = {
  actions: [],
  columns: [
    { key: 'reportFamily', label: 'Report family' },
    { key: 'metrics', label: 'Aggregate metrics' },
  ],
  generatedAt: '2026-09-28T14:00:00Z',
  metrics: [{ key: 'completedRuns', label: 'Completed runs', tone: 'success', value: 1 }],
  nextCursor: null,
  notices: [
    {
      detail: 'Source clinical and identity records are excluded.',
      title: 'Minimum necessary',
      tone: 'info',
    },
  ],
  organizationId: selectedOrganization.id,
  pageSize: 25,
  purpose: 'Review attributable aggregate reporting history.',
  rows: [
    {
      allowedActionKeys: [],
      etag: `"m12:P12-10:${reportingRunId}:1"`,
      id: reportingRunId,
      reportRunId: reportingRunId,
      revision: 1,
      status: 'completed',
      values: {
        artifact: 'report_run',
        metrics: 'appointment_total=7',
        reportFamily: 'operational',
      },
    },
  ],
  screenId: 'P12-10',
  title: 'Report audit and history',
};

const integrationConnectionId = '23232329-2329-4329-8329-232323232329';
const integrationProjection: IntegrationScreen = {
  actions: [],
  columns: [
    { key: 'artifact', label: 'Artifact' },
    { key: 'outcome', label: 'Outcome' },
  ],
  generatedAt: '2026-09-28T15:00:00Z',
  metrics: [{ key: 'deadLetters', label: 'Dead letters', tone: 'warning', value: 1 }],
  nextCursor: null,
  notices: [
    {
      detail:
        'Original deliveries remain terminal; replay creates a separately authorized successor.',
      title: 'Safe replay',
      tone: 'info',
    },
  ],
  organizationId: selectedOrganization.id,
  pageSize: 25,
  purpose: 'Review payload-free delivery and replay evidence.',
  rows: [
    {
      allowedActionKeys: [],
      connectionId: integrationConnectionId,
      etag: `"m13:P13-10:${integrationConnectionId}:1"`,
      id: integrationConnectionId,
      revision: 1,
      status: 'dead_letter',
      values: { artifact: 'outbound_delivery', outcome: 'retry_exhausted' },
    },
  ],
  screenId: 'P13-10',
  title: 'Integration audit and replay',
};

const workforceProjection: WorkforceScreen = {
  organizationId: selectedOrganization.id,
  screenId: 'M2-24',
  title: 'Offboarding operations',
  purpose: 'Execute only independently approved workforce offboarding plans.',
  generatedAt: '2026-09-25T08:00:00Z',
  metrics: [{ key: 'due', label: 'Due plans', value: 1, tone: 'warning' }],
  columns: [{ key: 'primary', label: 'Member' }],
  rows: [
    {
      id: '33333333-3333-4333-8333-333333333333',
      memberId: '44444444-4444-4444-8444-444444444444',
      status: 'approved',
      revision: 7,
      etag: '"m2:M2-24:33333333-3333-4333-8333-333333333333:7"',
      values: { primary: 'Dr Asha Verma' },
      allowedActionKeys: ['request-offboarding'],
    },
  ],
  actions: [
    {
      key: 'request-offboarding',
      label: 'Request offboarding',
      style: 'primary',
      targetRequired: true,
      ifMatchRequired: true,
      reasonRequired: true,
      href: null,
      fields: [
        {
          key: 'engagementEndAt',
          label: 'Final working time',
          inputType: 'datetime-local',
          required: true,
          options: [],
        },
        {
          key: 'effectiveAt',
          label: 'Effective time',
          inputType: 'datetime-local',
          required: true,
          options: [],
        },
        {
          key: 'reasonEntryId',
          label: 'Offboarding reason entry',
          inputType: 'uuid',
          required: true,
          options: [],
        },
        {
          key: 'reasonVersionId',
          label: 'Offboarding reason version',
          inputType: 'uuid',
          required: true,
          options: [],
        },
        {
          key: 'accessAction',
          label: 'Access timing',
          inputType: 'select',
          required: true,
          options: [
            { value: 'revoke_at_effective', label: 'Revoke at effective time' },
            { value: 'revoke_immediately', label: 'Revoke immediately' },
            { value: 'none', label: 'No access action' },
          ],
        },
      ],
    },
    {
      key: 'open-timeline',
      label: 'Open lifecycle timeline',
      style: 'link',
      targetRequired: false,
      ifMatchRequired: false,
      reasonRequired: false,
      href: '#/M2-29',
      fields: [],
    },
  ],
  notices: [],
  nextCursor: null,
  pageSize: 25,
};

function success<T>(data: T, status = 200): ApiResult<T> {
  return {
    correlationId,
    data,
    ok: true,
    sessionExpiresAt: Date.now() + 30 * 60 * 1_000,
    status,
  };
}

function failure(status = 503): ApiFailure {
  return {
    correlationId,
    kind: 'http',
    ok: false,
    problem: {
      code: 'session-unavailable',
      correlationId,
      detail: 'The session service could not verify access.',
      instance: '/api/v1/auth/session',
      status,
      title: 'Session unavailable',
      type: 'about:blank',
    },
    retryAfterSeconds: 12,
    status,
  };
}

function sessionClient(overrides: Partial<ApplicationClient> = {}): ApplicationClient {
  return {
    acceptInvitation: async () =>
      success(
        {
          accountLink: 'created',
          invitationId: '77777777-7777-4777-8777-777777777777',
          organizationId: selectedOrganization.id,
          roleKey: 'organization_viewer',
          userId: '88888888-8888-4888-8888-888888888888',
        },
        201,
      ),
    approveMfaAdministrativeReset: async (_organizationId, targetUserId, approvalId) =>
      success({
        approvalId,
        expiresAt: '2026-09-16T12:00:00Z',
        status: 'approved',
        targetUserId,
      }),
    approveOrganizationMembershipChange: async (_organizationId, membershipId, approvalId) =>
      success({
        approvalId,
        changeType: 'role_change',
        expiresAt: '2026-09-16T12:00:00Z',
        fromRoleKey: 'security_administrator',
        lockVersion: 0,
        membershipId,
        status: 'approved',
        targetUserId: '88888888-8888-4888-8888-888888888888',
        toRoleKey: 'organization_viewer',
      }),
    approveOrganizationOwnerTransfer: async (_organizationId, membershipId, approvalId) =>
      success({
        approvalId,
        changeType: 'owner_promotion',
        expiresAt: '2026-09-16T12:00:00Z',
        fromRoleKey: 'security_administrator',
        lockVersion: 0,
        membershipId,
        status: 'approved',
        targetUserId: '88888888-8888-4888-8888-888888888888',
        toRoleKey: 'organization_owner',
      }),
    completeMfaChallenge: async () => success(authenticatedSession),
    completePasswordReset: async () => success(undefined, 204),
    createOrganizationAddress: async (_organizationId, body) => {
      const created: OrganizationAddress = {
        ...organizationAddress,
        addressId: '66666666-6666-4666-8666-666666666667',
        addressLines: body.addressLines,
        addressType: body.addressType,
        countryCode: body.countryCode.toUpperCase(),
        effectiveFrom: body.effectiveFrom,
        effectiveTo: body.effectiveTo,
        isPrimary: body.isPrimary,
        locality: body.locality,
        postcode: body.postcode,
        region: body.region,
        validationSource: body.validationSource,
        validationStatus: body.validationStatus,
      };
      return {
        ...success(created, 201),
        etag: `"organization-address:${created.addressId}:0"`,
      };
    },
    createOrganizationContact: async (_organizationId, body) => {
      const created: OrganizationContact = {
        ...organizationContact,
        channel: body.channel,
        contactId: '77777777-7777-4777-8777-777777777771',
        effectiveFrom: body.effectiveFrom,
        effectiveTo: body.effectiveTo,
        isPreferred: body.isPreferred,
        isPrimary: body.isPrimary,
        maskedValue:
          body.channel === 'phone'
            ? '+***3210'
            : body.channel === 'web'
              ? 'https://e***/'
              : 'n***@***.org',
        purpose: body.purpose,
      };
      return {
        ...success(created, 201),
        etag: `"organization-contact:${created.contactId}:0"`,
      };
    },
    createOrganizationIdentifier: async (_organizationId, body) => {
      const created: OrganizationIdentifier = {
        ...organizationIdentifier,
        assigningAuthority: body.assigningAuthority,
        effectiveFrom: body.effectiveFrom,
        effectiveTo: body.effectiveTo,
        expiryDate: body.expiryDate,
        identifierId: '55555555-5555-4555-8555-555555555555',
        identifierType: body.identifierType,
        isPrimary: body.isPrimary,
        issueDate: body.issueDate,
        jurisdictionCountryCode: body.jurisdictionCountryCode,
        value: body.value,
      };
      return {
        ...success(created, 201),
        etag: `"organization-identifier:${created.identifierId}:0"`,
      };
    },
    executeMfaAdministrativeReset: async (_organizationId, targetUserId, approvalId) =>
      success({
        approvalId,
        expiresAt: '2026-09-16T12:00:00Z',
        status: 'reset',
        targetUserId,
      }),
    executeOrganizationMembershipChange: async (_organizationId, membershipId, approvalId) =>
      success({
        approvalId,
        changeType: 'role_change',
        expiresAt: '2026-09-16T12:00:00Z',
        fromRoleKey: 'security_administrator',
        lockVersion: 1,
        membershipId,
        status: 'changed',
        targetUserId: '88888888-8888-4888-8888-888888888888',
        toRoleKey: 'organization_viewer',
      }),
    executeOrganizationOwnerTransfer: async (_organizationId, membershipId, approvalId) =>
      success({
        approvalId,
        changeType: 'owner_promotion',
        expiresAt: '2026-09-16T12:00:00Z',
        fromRoleKey: 'security_administrator',
        lockVersion: 1,
        membershipId,
        status: 'transferred',
        targetUserId: '88888888-8888-4888-8888-888888888888',
        toRoleKey: 'organization_owner',
      }),
    endOrganizationAddress: async () => {
      const ended: OrganizationAddress = {
        ...organizationAddress,
        availableActions: [],
        effectiveTo: '2026-09-18T08:00:00Z',
        lockVersion: 1,
        status: 'ended',
        updatedAt: '2026-09-18T08:00:00Z',
      };
      return {
        ...success(ended),
        etag: `"organization-address:${ended.addressId}:1"`,
      };
    },
    endOrganizationContact: async () => {
      const ended: OrganizationContact = {
        ...organizationContact,
        availableActions: [],
        effectiveTo: '2026-09-18T08:00:00Z',
        lockVersion: 1,
        status: 'ended',
        updatedAt: '2026-09-18T08:00:00Z',
      };
      return {
        ...success(ended),
        etag: `"organization-contact:${ended.contactId}:1"`,
      };
    },
    getAdministrationReadiness: async () => success(readiness),
    getAuthenticationSession: async () => success(authenticatedSession),
    getFacilityDirectory: async () => success(facilityDirectory),
    getOrganizationUnitDirectory: async () => success(unitDirectory),
    createOrganizationUnitDraft: async () => success(unitDirectory, 201),
    getServiceLocationDirectory: async () => success(locationDirectory),
    createServiceLocationDraft: async () => success(locationDirectory, 201),
    updateServiceLocationDraft: async () => success(locationDirectory),
    reparentServiceLocation: async () => success(locationDirectory),
    activateServiceLocation: async () => success(locationDirectory),
    suspendServiceLocation: async () => success(locationDirectory),
    reactivateServiceLocation: async () => success(locationDirectory),
    closeServiceLocation: async () => success(locationDirectory),
    activateOrganizationUnit: async () => success(unitDirectory),
    suspendOrganizationUnit: async () => success(unitDirectory),
    reactivateOrganizationUnit: async () => success(unitDirectory),
    closeOrganizationUnit: async () => success(unitDirectory),
    updateOrganizationUnitDraft: async () => success(unitDirectory),
    reparentOrganizationUnit: async () => success(unitDirectory),
    createFacilityDraft: async () => success(facilityDirectory, 201),
    updateFacilityDraft: async () => success(facilityDirectory),
    submitFacilityDraft: async () => success(facilityDirectory),
    getOrganizationProfile: async () => ({
      ...success(organizationProfile),
      etag: '"organization-profile:4"',
    }),
    getPatientRegistryScreen: async (_organizationId, screenId) =>
      success({
        ...patientRegistryProjection,
        screenId,
        title: findScreen(screenId).title,
        purpose: findScreen(screenId).purpose,
      }),
    getSchedulingScreen: async (_organizationId, screenId) =>
      success({
        ...schedulingProjection,
        screenId,
        title: findScreen(screenId).title,
        purpose: findScreen(screenId).purpose,
        rows: [],
        actions: [],
      }),
    getEncounterScreen: async (_organizationId, screenId) =>
      success({
        ...encounterProjection,
        screenId,
        title: findScreen(screenId).title,
        purpose: findScreen(screenId).purpose,
        rows: [],
        actions: [],
      }),
    getAssessmentScreen: async (_organizationId, screenId) =>
      success({
        ...assessmentProjection,
        screenId,
        title: findScreen(screenId).title,
        purpose: findScreen(screenId).purpose,
        rows: [],
        actions: [],
      }),
    getDocumentScreen: async (_organizationId, screenId) =>
      success({
        ...documentProjection,
        screenId,
        title: findScreen(screenId).title,
        purpose: findScreen(screenId).purpose,
        rows: [],
        actions: [],
      }),
    getAiScreen: async (_organizationId, screenId) =>
      success({
        ...aiProjection,
        screenId,
        title: findScreen(screenId).title,
        purpose: findScreen(screenId).purpose,
        rows: [],
        actions: [],
      }),
    getCarePlanScreen: async (_organizationId, screenId) =>
      success({
        ...carePlanProjection,
        screenId,
        title: findScreen(screenId).title,
        purpose: findScreen(screenId).purpose,
        rows: [],
        actions: [],
      }),
    getFollowupScreen: async (_organizationId, screenId) =>
      success({
        ...followupProjection,
        screenId,
        title: findScreen(screenId).title,
        purpose: findScreen(screenId).purpose,
        rows: [],
        actions: [],
      }),
    issueInvitation: async () =>
      success(
        {
          expiresAt: '2026-09-16T12:00:00Z',
          invitationId: '77777777-7777-4777-8777-777777777777',
          roleKey: 'organization_viewer',
          status: 'pending',
        },
        201,
      ),
    listOrganizationContacts: async () => success(organizationContacts),
    listOrganizationIdentifiers: async () => success(organizationIdentifiers),
    listOrganizationMemberships: async () => success(membershipPage),
    listSelectableOrganizations: async () => success([selectedOrganization]),
    login: async () => success(authenticatedSession),
    logout: async () => success(undefined, 204),
    regenerateRecoveryCodes: async () =>
      success({ recoveryCodes: ['2345-6789-ABCD', 'EFGH-JKLM-NPQR'] }),
    requestPasswordReset: async () => success(undefined, 202),
    performPatientRegistryAction: async (_organizationId, screenId) =>
      success({
        ...patientRegistryProjection,
        screenId,
        title: findScreen(screenId).title,
        purpose: findScreen(screenId).purpose,
      }),
    performSchedulingAction: async (_organizationId, screenId) =>
      success({
        ...schedulingProjection,
        screenId,
        title: findScreen(screenId).title,
        purpose: findScreen(screenId).purpose,
        rows: [],
        actions: [],
      }),
    performEncounterAction: async (_organizationId, screenId) =>
      success({
        ...encounterProjection,
        screenId,
        title: findScreen(screenId).title,
        purpose: findScreen(screenId).purpose,
        rows: [],
        actions: [],
      }),
    performAssessmentAction: async (_organizationId, screenId) =>
      success({
        ...assessmentProjection,
        screenId,
        title: findScreen(screenId).title,
        purpose: findScreen(screenId).purpose,
        rows: [],
        actions: [],
      }),
    performDocumentAction: async (_organizationId, screenId) =>
      success({
        ...documentProjection,
        screenId,
        title: findScreen(screenId).title,
        purpose: findScreen(screenId).purpose,
        rows: [],
        actions: [],
      }),
    performAiAction: async (_organizationId, screenId) =>
      success({
        ...aiProjection,
        screenId,
        title: findScreen(screenId).title,
        purpose: findScreen(screenId).purpose,
        rows: [],
        actions: [],
      }),
    performCarePlanAction: async (_organizationId, screenId) =>
      success({
        ...carePlanProjection,
        screenId,
        title: findScreen(screenId).title,
        purpose: findScreen(screenId).purpose,
        rows: [],
        actions: [],
      }),
    performFollowupAction: async (_organizationId, screenId) =>
      success({
        ...followupProjection,
        screenId,
        title: findScreen(screenId).title,
        purpose: findScreen(screenId).purpose,
        rows: [],
        actions: [],
      }),
    uploadDocument: async () => success({ ...documentProjection, screenId: 'P7-03' }, 201),
    createDocumentAccess: async (_organizationId, requestedDocumentId, body) =>
      success({
        accessIntentId: '13131313-1313-4313-8313-131313131313',
        accessPath: `/api/v1/organizations/${selectedOrganization.id}/documents/${requestedDocumentId}/versions/${body.documentVersionId ?? documentVersionId}/accesses/13131313-1313-4313-8313-131313131313?purposeKey=${body.purposeKey}`,
        byteCount: 128,
        documentId: requestedDocumentId,
        documentVersionId: body.documentVersionId ?? documentVersionId,
        expiresAt: '2026-09-28T10:01:00Z',
        mediaType: 'application/pdf',
        purposeKey: body.purposeKey,
        sha256: 'a'.repeat(64),
      }),
    previewPatientRegistryImpact: async (_organizationId, screenId, actionKey, body) =>
      success({
        actionKey,
        blocked: false,
        digest: 'a'.repeat(64),
        expiresAt: '2099-09-26T08:10:00Z',
        items: [
          {
            affectedCount: 1,
            code: 'patient_record',
            detail: 'One patient record is affected.',
            tone: 'impact',
          },
        ],
        revision: 0,
        screenId,
        targetId: body.targetId!,
        token: 'a'.repeat(64),
      }),
    requestMfaAdministrativeReset: async (_organizationId, targetUserId) =>
      success(
        {
          approvalId: '99999999-9999-4999-8999-999999999999',
          expiresAt: '2026-09-16T12:00:00Z',
          status: 'pending',
          targetUserId,
        },
        201,
      ),
    requestOrganizationMembershipChange: async (_organizationId, membershipId, body) =>
      success(
        {
          approvalId: '99999999-9999-4999-8999-999999999999',
          changeType: body.changeType,
          expiresAt: '2026-09-16T12:00:00Z',
          fromRoleKey: 'security_administrator',
          lockVersion: 0,
          membershipId,
          status: 'pending',
          targetUserId: '88888888-8888-4888-8888-888888888888',
          toRoleKey: body.toRoleKey ?? null,
        },
        201,
      ),
    requestOrganizationOwnerTransfer: async (_organizationId, membershipId, body) =>
      success(
        {
          approvalId: '99999999-9999-4999-8999-999999999999',
          changeType: 'owner_promotion',
          expiresAt: '2026-09-16T12:00:00Z',
          fromRoleKey: 'security_administrator',
          lockVersion: 0,
          membershipId,
          status: 'pending',
          targetUserId: '88888888-8888-4888-8888-888888888888',
          toRoleKey: body.toRoleKey,
        },
        201,
      ),
    revokeInvitation: async () =>
      success({
        expiresAt: '2026-09-16T12:00:00Z',
        invitationId: '77777777-7777-4777-8777-777777777777',
        roleKey: 'organization_viewer',
        status: 'revoked',
      }),
    revokeOrganizationIdentifier: async () => {
      const revoked: OrganizationIdentifier = {
        ...organizationIdentifier,
        availableActions: [],
        lockVersion: 1,
        status: 'revoked',
        updatedAt: '2026-09-16T09:00:00Z',
      };
      return {
        ...success(revoked),
        etag: `"organization-identifier:${revoked.identifierId}:1"`,
      };
    },
    selectOrganization: async ({ organizationId }) =>
      success({
        ...(organizationId === otherOrganization.id ? otherOrganization : selectedOrganization),
        selected: true,
      }),
    startMfaEnrollment: async () =>
      success({
        provisioningUri:
          'otpauth://totp/ROOTOPATHY%20CareOS:asha@example.test?secret=ABCDEFGHIJKLMNOP',
        secret: 'ABCDEFGHIJKLMNOP',
      }),
    supersedeOrganizationAddress: async (_organizationId, addressId, body) => {
      const replacement: OrganizationAddress = {
        ...organizationAddress,
        addressId: '66666666-6666-4666-8666-666666666668',
        addressLines: body.addressLines,
        countryCode: body.countryCode.toUpperCase(),
        effectiveFrom: body.effectiveFrom,
        effectiveTo: body.effectiveTo,
        locality: body.locality,
        postcode: body.postcode,
        region: body.region,
        supersedesId: addressId,
        validationSource: body.validationSource,
        validationStatus: body.validationStatus,
      };
      return {
        ...success(replacement, 201),
        etag: `"organization-address:${replacement.addressId}:0"`,
      };
    },
    supersedeOrganizationContact: async (_organizationId, contactId, body) => {
      const replacement: OrganizationContact = {
        ...organizationContact,
        contactId: '77777777-7777-4777-8777-777777777772',
        effectiveFrom: body.effectiveFrom,
        effectiveTo: body.effectiveTo,
        maskedValue: 'r***@***.org',
        supersedesId: contactId,
      };
      return {
        ...success(replacement, 201),
        etag: `"organization-contact:${replacement.contactId}:0"`,
      };
    },
    supersedeOrganizationIdentifier: async (_organizationId, identifierId) => {
      const superseded: OrganizationIdentifier = {
        ...organizationIdentifier,
        availableActions: [],
        identifierId,
        lockVersion: 1,
        status: 'superseded',
        updatedAt: '2026-09-16T09:00:00Z',
      };
      return {
        ...success(superseded),
        etag: `"organization-identifier:${superseded.identifierId}:1"`,
      };
    },
    subscribeSessionLifecycle: () => () => undefined,
    updateOrganizationProfile: async (_organizationId, body) => {
      const updated = {
        ...organizationProfile,
        ...body,
        lockVersion: organizationProfile.lockVersion + 1,
        updatedAt: '2026-09-16T09:00:00Z',
      };
      return {
        ...success(updated),
        etag: '"organization-profile:5"',
      };
    },
    updateOrganizationIdentifier: async (_organizationId, _identifierId, body) => {
      const updated: OrganizationIdentifier = {
        ...organizationIdentifier,
        assigningAuthority: body.assigningAuthority,
        effectiveFrom: body.effectiveFrom,
        effectiveTo: body.effectiveTo,
        expiryDate: body.expiryDate,
        identifierType: body.identifierType,
        isPrimary: body.isPrimary,
        issueDate: body.issueDate,
        jurisdictionCountryCode: body.jurisdictionCountryCode,
        lockVersion: 1,
        updatedAt: '2026-09-16T09:00:00Z',
        value: body.value,
      };
      return {
        ...success(updated),
        etag: `"organization-identifier:${updated.identifierId}:1"`,
      };
    },
    verifyMfaEnrollment: async () =>
      success({ recoveryCodes: ['2345-6789-ABCD', 'EFGH-JKLM-NPQR'] }),
    verifyOrganizationContact: async () => {
      const verified: OrganizationContact = {
        ...organizationContact,
        availableActions: ['supersede', 'end'],
        lockVersion: 1,
        updatedAt: '2026-09-18T08:00:00Z',
        verificationStatus: 'verified',
      };
      return {
        ...success(verified),
        etag: `"organization-contact:${verified.contactId}:1"`,
      };
    },
    verifyOrganizationIdentifier: async (_organizationId, _identifierId, body) => {
      const verified: OrganizationIdentifier = {
        ...organizationIdentifier,
        availableActions: [],
        evidenceReference: body.evidenceReference,
        lockVersion: 1,
        status: 'verified',
        updatedAt: '2026-09-16T09:00:00Z',
        verificationStatus: 'verified',
      };
      return {
        ...success(verified),
        etag: `"organization-identifier:${verified.identifierId}:1"`,
      };
    },
    verifyRecentAuthentication: async () => success(undefined, 204),
    ...overrides,
  } as ApplicationClient;
}

describe('CareOS frontend session boundary', () => {
  beforeEach(() => {
    window.location.hash = '#/M1-05';
  });

  it('registers all M1 through M13 and COS screens', () => {
    expect(screens).toHaveLength(195);
    expect(new Set(screens.map((item) => item.id)).size).toBe(195);
    expect(findScreen('M1-01').purpose).toBe(
      'Authenticate securely and continue to the requested authorized workspace.',
    );
    expect(findScreen('M1-02').title).toBe('Invitations');
    expect(findScreen('M1-03').title).toBe('Multi-factor authentication');
    expect(findScreen('M1-04').purpose).toBe(
      'Choose one currently authorized organization workspace.',
    );
    expect(findScreen('P3-15').title).toBe('Merge review');
    expect(findScreen('P4-11').title).toBe('Confirmation');
    expect(findScreen('P5-10').title).toBe('Review and sign');
    expect(findScreen('P7-11').title).toBe('Export or share intent');
    expect(findScreen('P8-10').title).toBe('AI session history');
    expect(findScreen('P9-12').title).toBe('Plan versions and amendments');
    expect(findScreen('P10-09').title).toBe('Outcome timeline');
    expect(findScreen('P11-11').title).toBe('Financial audit or export');
    expect(findScreen('P12-10').title).toBe('Report audit and history');
    expect(findScreen('P13-07').title).toBe('Lab/imaging interfaces');
    expect(findScreen('P13-10').title).toBe('Integration audit and replay');
    expect(() => findScreen('M1-99')).toThrow('does not contain M1-99');
  });

  it('renders an M2 route from the live server projection and row-scoped actions', async () => {
    window.location.hash = '#/M2-24';
    const getWorkforceScreen = vi.fn<WorkforceClient['getWorkforceScreen']>(async () =>
      success(workforceProjection),
    );

    render(<App client={sessionClient({ getWorkforceScreen })} />);

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Offboarding operations' }),
    ).toBeVisible();
    expect(screen.getByText('Dr Asha Verma')).toBeVisible();
    expect(screen.getByText('Server governed')).toBeVisible();
    expect(getWorkforceScreen).toHaveBeenCalledOnce();
    expect(getWorkforceScreen.mock.calls[0]?.[0]).toBe(selectedOrganization.id);
    expect(getWorkforceScreen.mock.calls[0]?.[1]).toBe('M2-24');
    expect(getWorkforceScreen.mock.calls[0]?.[2]).toEqual({
      limit: 25,
      q: undefined,
      status: undefined,
    });

    fireEvent.click(screen.getByRole('radio', { name: 'Select Dr Asha Verma' }));
    fireEvent.click(screen.getByRole('button', { name: 'Request offboarding' }));
    expect(screen.getByRole('heading', { level: 2, name: 'Request offboarding' })).toBeVisible();
  });

  it('requires and forwards an explicit purpose before loading restricted workforce records', async () => {
    window.location.hash = '#/M2-04';
    const getWorkforceScreen = vi.fn<WorkforceClient['getWorkforceScreen']>(async () =>
      success({
        ...workforceProjection,
        purpose: findScreen('M2-04').purpose,
        screenId: 'M2-04',
        title: findScreen('M2-04').title,
      }),
    );

    render(<App client={sessionClient({ getWorkforceScreen })} />);

    expect(
      await screen.findByRole('heading', {
        name: 'State your purpose before viewing restricted records',
      }),
    ).toBeVisible();
    expect(getWorkforceScreen).not.toHaveBeenCalled();

    fireEvent.change(screen.getByLabelText('Access reason'), {
      target: { value: 'Reviewing possible duplicates during approved onboarding' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Continue to protected view' }));

    await waitFor(() => expect(getWorkforceScreen).toHaveBeenCalledOnce());
    expect(getWorkforceScreen.mock.calls[0]?.[3]).toMatchObject({
      authorizationReason: 'Reviewing possible duplicates during approved onboarding',
    });
  });

  it('renders a P3 route and submits a revision-bound patient action', async () => {
    window.location.hash = '#/P3-05';
    const getPatientRegistryScreen = vi.fn<PatientRegistryClient['getPatientRegistryScreen']>(
      async () => success(patientIdentityProjection),
    );
    const performPatientRegistryAction = vi.fn<
      PatientRegistryClient['performPatientRegistryAction']
    >(async () => success(patientIdentityProjection));

    render(
      <App client={sessionClient({ getPatientRegistryScreen, performPatientRegistryAction })} />,
    );

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Identity and demographics' }),
    ).toBeVisible();
    expect(getPatientRegistryScreen).toHaveBeenCalledWith(
      selectedOrganization.id,
      'P3-05',
      { limit: 25, q: undefined, status: undefined },
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    );

    fireEvent.click(screen.getByRole('radio', { name: 'Select Temporary patient' }));
    fireEvent.click(screen.getByRole('button', { name: 'Save identity correction' }));
    const actionDialog = screen.getByRole('dialog');
    fireEvent.change(within(actionDialog).getByLabelText('Name to use'), {
      target: { value: 'Asha' },
    });
    fireEvent.change(within(actionDialog).getByLabelText('Provenance code'), {
      target: { value: 'patient_supplied' },
    });
    fireEvent.change(within(actionDialog).getByRole('textbox', { name: /^Reason/ }), {
      target: { value: 'Corrected with patient confirmation.' },
    });
    fireEvent.click(within(actionDialog).getByRole('button', { name: 'Save identity correction' }));

    await waitFor(() => expect(performPatientRegistryAction).toHaveBeenCalledOnce());
    expect(performPatientRegistryAction.mock.calls[0]?.slice(0, 4)).toEqual([
      selectedOrganization.id,
      'P3-05',
      'correct-identity',
      expect.objectContaining({
        fields: expect.objectContaining({
          birthDateCertainty: 'exact',
          nameState: 'provided',
          nameToUse: 'Asha',
          provenanceCode: 'patient_supplied',
        }),
        patientId: '33333333-3333-4333-8333-333333333333',
        targetId: '33333333-3333-4333-8333-333333333333',
      }),
    ]);
    expect(performPatientRegistryAction.mock.calls[0]?.[4]).toBe(
      '"m3:P3-05:33333333-3333-4333-8333-333333333333:2"',
    );
  });

  it('loads child records only after selecting an explicit patient context', async () => {
    window.location.hash = '#/P3-06';
    const directory: PatientRegistryScreen = {
      ...patientIdentityProjection,
      actions: [],
      screenId: 'P3-06',
      title: 'Contacts and addresses',
    };
    const details: PatientRegistryScreen = {
      ...directory,
      rows: [
        ...directory.rows,
        {
          allowedActionKeys: [],
          etag: '"m3:P3-06:44444444-4444-4444-8444-444444444444:0"',
          id: '44444444-4444-4444-8444-444444444444',
          patientId: '33333333-3333-4333-8333-333333333333',
          revision: 0,
          status: 'active',
          values: {
            context: 'care-coordination',
            primary: 'a•••@example.invalid',
            secondary: 'email · personal',
          },
        },
      ],
    };
    const getPatientRegistryScreen = vi.fn<PatientRegistryClient['getPatientRegistryScreen']>(
      async (_organizationId, _screenId, query) => success(query?.patientId ? details : directory),
    );

    render(<App client={sessionClient({ getPatientRegistryScreen })} />);

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Contacts and addresses' }),
    ).toBeVisible();
    fireEvent.click(screen.getByRole('radio', { name: 'Select Temporary patient' }));
    expect(await screen.findByText('a•••@example.invalid')).toBeVisible();
    await waitFor(() => expect(getPatientRegistryScreen).toHaveBeenCalledTimes(2));
    expect(getPatientRegistryScreen.mock.calls[1]?.[2]).toEqual(
      expect.objectContaining({ patientId: '33333333-3333-4333-8333-333333333333' }),
    );

    fireEvent.click(screen.getByRole('button', { name: 'All patients' }));
    await waitFor(() => expect(getPatientRegistryScreen).toHaveBeenCalledTimes(3));
    expect(getPatientRegistryScreen.mock.calls[2]?.[2]).not.toHaveProperty('patientId');
  });

  it('renders a P4 route and confirms a revision-bound appointment request', async () => {
    window.location.hash = `#/P4-11?requestId=${schedulingRequestId}`;
    const getSchedulingScreen = vi.fn<SchedulingClient['getSchedulingScreen']>(async () =>
      success(schedulingProjection),
    );
    const performSchedulingAction = vi.fn<SchedulingClient['performSchedulingAction']>(async () =>
      success(schedulingProjection),
    );

    render(<App client={sessionClient({ getSchedulingScreen, performSchedulingAction })} />);

    expect(await screen.findByRole('heading', { level: 1, name: 'Confirmation' })).toBeVisible();
    expect(getSchedulingScreen).toHaveBeenCalledWith(
      selectedOrganization.id,
      'P4-11',
      expect.objectContaining({ limit: 25, requestId: schedulingRequestId }),
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    );

    fireEvent.click(screen.getByRole('radio', { name: 'Select Request 99999999' }));
    fireEvent.click(screen.getByRole('button', { name: 'Confirm appointment' }));
    const actionDialog = screen.getByRole('dialog');
    fireEvent.change(within(actionDialog).getByRole('textbox', { name: /^Reason/ }), {
      target: { value: 'Confirmed after reviewing the exact held slot.' },
    });
    fireEvent.click(within(actionDialog).getByRole('button', { name: 'Confirm appointment' }));

    await waitFor(() => expect(performSchedulingAction).toHaveBeenCalledOnce());
    expect(performSchedulingAction.mock.calls[0]?.slice(0, 4)).toEqual([
      selectedOrganization.id,
      'P4-11',
      'confirm-appointment',
      expect.objectContaining({
        requestId: schedulingRequestId,
        targetId: schedulingRequestId,
      }),
    ]);
    expect(performSchedulingAction.mock.calls[0]?.[4]).toBe(`"m4:P4-11:${schedulingRequestId}:3"`);
  });

  it('renders a P5 route and submits append-only clinical note input', async () => {
    window.location.hash = `#/P5-09?encounterId=${encounterId}`;
    const getEncounterScreen = vi.fn<EncounterClient['getEncounterScreen']>(async () =>
      success(encounterProjection),
    );
    const performEncounterAction = vi.fn<EncounterClient['performEncounterAction']>(async () =>
      success(encounterProjection),
    );

    render(<App client={sessionClient({ getEncounterScreen, performEncounterAction })} />);

    expect(await screen.findByRole('heading', { level: 1, name: 'Encounter notes' })).toBeVisible();
    expect(getEncounterScreen).toHaveBeenCalledWith(
      selectedOrganization.id,
      'P5-09',
      expect.objectContaining({ encounterId, limit: 25 }),
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    );

    fireEvent.click(screen.getByRole('radio', { name: 'Select Version 4' }));
    fireEvent.click(screen.getByRole('button', { name: 'Save note version' }));
    const actionDialog = screen.getByRole('dialog');
    fireEvent.change(within(actionDialog).getByRole('textbox', { name: 'Clinical note' }), {
      target: { value: 'Patient reports improved mobility with no new red flags.' },
    });
    fireEvent.click(within(actionDialog).getByRole('checkbox', { name: 'Late entry' }));
    fireEvent.click(within(actionDialog).getByRole('button', { name: 'Save note version' }));

    await waitFor(() => expect(performEncounterAction).toHaveBeenCalledOnce());
    expect(performEncounterAction.mock.calls[0]?.slice(0, 4)).toEqual([
      selectedOrganization.id,
      'P5-09',
      'save-note-version',
      expect.objectContaining({
        encounterId,
        fields: expect.objectContaining({
          content: 'Patient reports improved mobility with no new red flags.',
          lateEntry: 'true',
        }),
        targetId: encounterNoteId,
      }),
    ]);
    expect(performEncounterAction.mock.calls[0]?.[4]).toBe(`"m5:P5-09:${encounterNoteId}:4"`);
  });

  it('renders a COS route with patient context and debounced revision-bound autosave', async () => {
    window.location.hash = `#/COS-09?assessmentSessionId=${assessmentSessionId}`;
    const responseProjection: AssessmentScreen = {
      ...assessmentProjection,
      actions: [
        {
          fields: [
            {
              inputType: 'uuid',
              key: 'authorPractitionerId',
              label: 'Author clinician',
              options: [],
              required: true,
            },
            {
              inputType: 'text',
              key: 'responseKey',
              label: 'Response key',
              options: [],
              required: true,
            },
            {
              inputType: 'textarea',
              key: 'content',
              label: 'Clinical response',
              options: [],
              required: true,
            },
            {
              inputType: 'text',
              key: 'sourceKey',
              label: 'Source',
              options: [],
              required: true,
            },
            {
              inputType: 'text',
              key: 'methodKey',
              label: 'Method',
              options: [],
              required: true,
            },
            {
              inputType: 'select',
              key: 'interpretationStatus',
              label: 'Interpretation status',
              options: [{ label: 'Uninterpreted', value: 'uninterpreted' }],
              required: true,
            },
          ],
          href: null,
          ifMatchRequired: true,
          key: 'save-section-response',
          label: 'Save response',
          reasonRequired: false,
          style: 'primary',
          targetRequired: true,
        },
      ],
      rows: assessmentProjection.rows.map((row) => ({
        ...row,
        allowedActionKeys: ['save-section-response'],
      })),
    };
    const getAssessmentScreen = vi.fn<AssessmentClient['getAssessmentScreen']>(async () =>
      success(responseProjection),
    );
    const performAssessmentAction = vi.fn<AssessmentClient['performAssessmentAction']>(async () =>
      success(responseProjection),
    );

    render(<App client={sessionClient({ getAssessmentScreen, performAssessmentAction })} />);

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Clinical examination' }),
    ).toBeVisible();
    expect(getAssessmentScreen).toHaveBeenCalledWith(
      selectedOrganization.id,
      'COS-09',
      expect.objectContaining({ assessmentSessionId, limit: 25 }),
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    );

    fireEvent.click(screen.getByRole('radio', { name: 'Select Samira Patel' }));
    expect(screen.getByRole('region', { name: 'Verified patient context' })).toHaveTextContent(
      'Dr Asha Verma',
    );
    fireEvent.click(screen.getByRole('button', { name: 'Save response' }));
    const actionDialog = screen.getByRole('dialog');
    fireEvent.change(within(actionDialog).getByRole('textbox', { name: 'Response key' }), {
      target: { value: 'clinical.examination' },
    });
    fireEvent.change(within(actionDialog).getByRole('textbox', { name: 'Clinical response' }), {
      target: { value: 'Mobility is improved; no new red-flag finding was observed.' },
    });
    fireEvent.change(within(actionDialog).getByRole('textbox', { name: 'Source' }), {
      target: { value: 'direct_observation' },
    });
    fireEvent.change(within(actionDialog).getByRole('textbox', { name: 'Method' }), {
      target: { value: 'structured_examination' },
    });
    expect(within(actionDialog).getByText('Autosave queued')).toBeVisible();

    await waitFor(() => expect(performAssessmentAction).toHaveBeenCalledOnce(), { timeout: 2_000 });
    expect(performAssessmentAction.mock.calls[0]?.slice(0, 4)).toEqual([
      selectedOrganization.id,
      'COS-09',
      'save-section-response',
      expect.objectContaining({
        assessmentSessionId,
        encounterId,
        fields: expect.objectContaining({
          authorPractitionerId: 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee',
          content: 'Mobility is improved; no new red-flag finding was observed.',
          interpretationStatus: 'uninterpreted',
          methodKey: 'structured_examination',
          responseKey: 'clinical.examination',
          sourceKey: 'direct_observation',
        }),
        targetId: assessmentSessionId,
      }),
    ]);
    expect(performAssessmentAction.mock.calls[0]?.[4]).toBe(`"m6:COS-09:${assessmentSessionId}:3"`);
  });

  it('renders a P7 viewer and creates a purpose-bound clean-document access intent', async () => {
    window.location.hash = `#/P7-06?documentId=${documentId}`;
    const responseProjection: DocumentScreen = {
      ...documentProjection,
      actions: [
        {
          fields: [
            {
              inputType: 'select',
              key: 'purposeKey',
              label: 'Access purpose',
              options: [
                { label: 'Clinical care', value: 'clinical_care' },
                { label: 'Result review', value: 'result_review' },
              ],
              required: true,
            },
          ],
          href: null,
          ifMatchRequired: false,
          key: 'access-document',
          label: 'Open clean document',
          reasonRequired: true,
          style: 'primary',
          targetRequired: true,
        },
      ],
      purpose: findScreen('P7-06').purpose,
      rows: documentProjection.rows.map((row) => ({
        ...row,
        allowedActionKeys: ['access-document'],
        etag: `"m7:P7-06:${row.id}:2"`,
      })),
      screenId: 'P7-06',
      title: findScreen('P7-06').title,
    };
    const getDocumentScreen = vi.fn<DocumentClient['getDocumentScreen']>(async () =>
      success(responseProjection),
    );
    const createDocumentAccess = vi.fn<DocumentClient['createDocumentAccess']>(
      async (_organizationId, requestedDocumentId, body) =>
        success({
          accessIntentId: '13131313-1313-4313-8313-131313131313',
          accessPath: `/api/v1/organizations/${selectedOrganization.id}/documents/${requestedDocumentId}/versions/${documentVersionId}/accesses/13131313-1313-4313-8313-131313131313?purposeKey=${body.purposeKey}`,
          byteCount: 128,
          documentId: requestedDocumentId,
          documentVersionId,
          expiresAt: '2026-09-28T10:01:00Z',
          mediaType: 'application/pdf',
          purposeKey: body.purposeKey,
          sha256: 'a'.repeat(64),
        }),
    );

    render(<App client={sessionClient({ createDocumentAccess, getDocumentScreen })} />);

    expect(await screen.findByRole('heading', { level: 1, name: 'Document viewer' })).toBeVisible();
    expect(getDocumentScreen).toHaveBeenCalledWith(
      selectedOrganization.id,
      'P7-06',
      expect.objectContaining({ documentId, limit: 25 }),
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    );
    fireEvent.click(screen.getByRole('radio', { name: 'Select Laboratory report' }));
    fireEvent.click(screen.getByRole('button', { name: 'Open clean document' }));
    const actionDialog = screen.getByRole('dialog');
    fireEvent.change(within(actionDialog).getByRole('combobox', { name: 'Access purpose' }), {
      target: { value: 'result_review' },
    });
    fireEvent.change(within(actionDialog).getByRole('textbox', { name: /^Reason/ }), {
      target: { value: 'Review the clean synthetic diagnostic result evidence.' },
    });
    fireEvent.click(within(actionDialog).getByRole('button', { name: 'Open clean document' }));

    await waitFor(() => expect(createDocumentAccess).toHaveBeenCalledOnce());
    expect(createDocumentAccess.mock.calls[0]?.slice(0, 3)).toEqual([
      selectedOrganization.id,
      documentId,
      {
        documentVersionId,
        purposeKey: 'result_review',
        reason: 'Review the clean synthetic diagnostic result evidence.',
      },
    ]);
    expect(await screen.findByRole('link', { name: /Open clean document/ })).toHaveAttribute(
      'href',
      expect.stringContaining('/accesses/'),
    );
  });

  it('renders a P8 clinician review and submits an explicit revision-bound decision', async () => {
    window.location.hash = `#/P8-09?aiSessionId=${aiSessionId}`;
    const reviewerId = 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee';
    const responseProjection: AiScreen = {
      ...aiProjection,
      actions: [
        {
          fields: [
            {
              inputType: 'select',
              key: 'decision',
              label: 'Decision',
              options: [
                { label: 'Accept', value: 'accepted' },
                { label: 'Reject', value: 'rejected' },
              ],
              required: true,
            },
            {
              inputType: 'uuid',
              key: 'reviewerPractitionerId',
              label: 'Reviewing clinician',
              options: [],
              required: true,
            },
          ],
          href: null,
          ifMatchRequired: true,
          key: 'decide-output',
          label: 'Record clinician decision',
          reasonRequired: true,
          style: 'primary',
          targetRequired: true,
        },
      ],
      purpose: findScreen('P8-09').purpose,
      rows: aiProjection.rows.map((row) => ({
        ...row,
        allowedActionKeys: ['decide-output'],
        etag: `"m8:P8-09:${row.id}:4"`,
        status: 'draft_ready',
      })),
      screenId: 'P8-09',
      title: findScreen('P8-09').title,
    };
    const getAiScreen = vi.fn<AiClient['getAiScreen']>(async () => success(responseProjection));
    const performAiAction = vi.fn<AiClient['performAiAction']>(async () =>
      success(responseProjection),
    );

    render(<App client={sessionClient({ getAiScreen, performAiAction })} />);

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Clinician review and approval' }),
    ).toBeVisible();
    expect(getAiScreen).toHaveBeenCalledWith(
      selectedOrganization.id,
      'P8-09',
      expect.objectContaining({ aiSessionId, limit: 25 }),
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    );
    fireEvent.click(screen.getByRole('button', { name: 'Record clinician decision' }));
    const actionDialog = screen.getByRole('dialog');
    fireEvent.change(within(actionDialog).getByRole('textbox', { name: 'Reviewing clinician' }), {
      target: { value: reviewerId },
    });
    fireEvent.change(within(actionDialog).getByRole('textbox', { name: 'Reason' }), {
      target: { value: 'Clinician reviewed the latest draft and its cited source evidence.' },
    });
    fireEvent.click(within(actionDialog).getByRole('button', { name: 'Confirm action' }));

    await waitFor(() => expect(performAiAction).toHaveBeenCalledOnce());
    expect(performAiAction.mock.calls[0]?.slice(0, 5)).toEqual([
      selectedOrganization.id,
      'P8-09',
      'decide-output',
      {
        fields: { decision: 'accepted', reviewerPractitionerId: reviewerId },
        reason: 'Clinician reviewed the latest draft and its cited source evidence.',
        targetId: aiSessionId,
      },
      `"m8:P8-09:${aiSessionId}:4"`,
    ]);
  });

  it('renders a P9 plan review and submits an exact-version transition', async () => {
    window.location.hash = `#/P9-10?carePlanId=${carePlanId}`;
    const responseProjection: CarePlanScreen = {
      ...carePlanProjection,
      actions: [
        {
          fields: [],
          href: null,
          ifMatchRequired: true,
          key: 'submit-plan',
          label: 'Submit for review',
          reasonRequired: true,
          style: 'primary',
          targetRequired: true,
        },
      ],
      purpose: findScreen('P9-10').purpose,
      rows: carePlanProjection.rows.map((row) => ({
        ...row,
        allowedActionKeys: ['submit-plan'],
        etag: `"m9:P9-10:${row.id}:5"`,
        status: 'draft',
      })),
      screenId: 'P9-10',
      title: findScreen('P9-10').title,
    };
    const getCarePlanScreen = vi.fn<CarePlanClient['getCarePlanScreen']>(async () =>
      success(responseProjection),
    );
    const performCarePlanAction = vi.fn<CarePlanClient['performCarePlanAction']>(async () =>
      success(responseProjection),
    );

    render(<App client={sessionClient({ getCarePlanScreen, performCarePlanAction })} />);

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Clinician approval' }),
    ).toBeVisible();
    expect(getCarePlanScreen).toHaveBeenCalledWith(
      selectedOrganization.id,
      'P9-10',
      expect.objectContaining({ carePlanId, limit: 25 }),
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    );
    fireEvent.click(screen.getByRole('button', { name: 'Submit for review' }));
    const actionDialog = screen.getByRole('dialog');
    fireEvent.change(within(actionDialog).getByRole('textbox', { name: 'Reason' }), {
      target: { value: 'Complete ownership, consent and safety evidence reviewed.' },
    });
    fireEvent.click(within(actionDialog).getByRole('button', { name: 'Confirm action' }));

    await waitFor(() => expect(performCarePlanAction).toHaveBeenCalledOnce());
    expect(performCarePlanAction.mock.calls[0]?.slice(0, 5)).toEqual([
      selectedOrganization.id,
      'P9-10',
      'submit-plan',
      {
        fields: {},
        reason: 'Complete ownership, consent and safety evidence reviewed.',
        targetId: carePlanId,
      },
      `"m9:P9-10:${carePlanId}:5"`,
    ]);
  });

  it('renders a P10 escalation and records an owner acknowledgement', async () => {
    window.location.hash = `#/P10-05?followupPlanId=${followupPlanId}`;
    const followupOwnerId = 'edededed-eded-4ded-8ded-edededededed';
    const responseProjection: FollowupScreen = {
      ...followupProjection,
      actions: [
        {
          fields: [
            {
              help: null,
              inputType: 'uuid',
              key: 'practitionerId',
              label: 'Acknowledging owner',
              options: [],
              required: true,
            },
          ],
          href: null,
          ifMatchRequired: true,
          key: 'acknowledge-escalation',
          label: 'Acknowledge escalation',
          reasonRequired: true,
          style: 'primary',
          targetRequired: true,
        },
      ],
      purpose: findScreen('P10-05').purpose,
      rows: followupProjection.rows.map((row) => ({
        ...row,
        allowedActionKeys: ['acknowledge-escalation'],
      })),
    };
    const getFollowupScreen = vi.fn<FollowupClient['getFollowupScreen']>(async () =>
      success(responseProjection),
    );
    const performFollowupAction = vi.fn<FollowupClient['performFollowupAction']>(async () =>
      success(responseProjection),
    );

    render(<App client={sessionClient({ getFollowupScreen, performFollowupAction })} />);

    expect(await screen.findByRole('heading', { level: 1, name: 'Escalation' })).toBeVisible();
    expect(getFollowupScreen).toHaveBeenCalledWith(
      selectedOrganization.id,
      'P10-05',
      expect.objectContaining({ followupPlanId, limit: 25 }),
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    );
    fireEvent.click(screen.getByRole('button', { name: 'Acknowledge escalation' }));
    const actionDialog = screen.getByRole('dialog');
    fireEvent.change(within(actionDialog).getByRole('textbox', { name: 'Acknowledging owner' }), {
      target: { value: followupOwnerId },
    });
    fireEvent.change(within(actionDialog).getByRole('textbox', { name: 'Reason' }), {
      target: { value: 'Acknowledge responsibility for the critical outcome review.' },
    });
    fireEvent.click(within(actionDialog).getByRole('button', { name: 'Confirm action' }));

    await waitFor(() => expect(performFollowupAction).toHaveBeenCalledOnce());
    expect(performFollowupAction.mock.calls[0]?.slice(0, 5)).toEqual([
      selectedOrganization.id,
      'P10-05',
      'acknowledge-escalation',
      {
        fields: { practitionerId: followupOwnerId },
        reason: 'Acknowledge responsibility for the critical outcome review.',
        targetId: escalationId,
      },
      `"m10:P10-05:${escalationId}:0"`,
    ]);
  });

  it('renders P11 payment evidence without collecting card data', async () => {
    window.location.hash = `#/P11-06?invoiceId=${billingInvoiceId}`;
    const responseProjection: BillingScreen = {
      ...billingProjection,
      actions: [
        {
          fields: [
            {
              help: null,
              inputType: 'select',
              key: 'source',
              label: 'Source',
              options: [{ label: 'Bank transfer', value: 'manual_bank' }],
              required: true,
            },
            {
              help: null,
              inputType: 'text',
              key: 'paymentReference',
              label: 'Payment reference',
              options: [],
              required: true,
            },
            {
              help: null,
              inputType: 'number',
              key: 'amountMinor',
              label: 'Amount (minor units)',
              options: [],
              required: true,
            },
          ],
          href: null,
          ifMatchRequired: true,
          key: 'record-payment',
          label: 'Record payment',
          reasonRequired: true,
          style: 'primary',
          targetRequired: true,
        },
      ],
      rows: billingProjection.rows.map((row) => ({
        ...row,
        allowedActionKeys: ['record-payment'],
      })),
    };
    const getBillingScreen = vi.fn<BillingClient['getBillingScreen']>(async () =>
      success(responseProjection),
    );
    const performBillingAction = vi.fn<BillingClient['performBillingAction']>(async () =>
      success(responseProjection),
    );

    render(<App client={sessionClient({ getBillingScreen, performBillingAction })} />);

    expect(await screen.findByRole('heading', { level: 1, name: 'Payment' })).toBeVisible();
    expect(screen.getByText('Financially governed')).toBeVisible();
    expect(screen.queryByLabelText(/card/i)).not.toBeInTheDocument();
    expect(getBillingScreen).toHaveBeenCalledWith(
      selectedOrganization.id,
      'P11-06',
      expect.objectContaining({ invoiceId: billingInvoiceId, limit: 25 }),
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    );
    fireEvent.click(screen.getByRole('button', { name: 'Record payment' }));
    const actionDialog = screen.getByRole('dialog');
    fireEvent.change(within(actionDialog).getByLabelText('Payment reference'), {
      target: { value: 'BANK-SETTLEMENT-0001' },
    });
    fireEvent.change(within(actionDialog).getByLabelText('Amount (minor units)'), {
      target: { value: '5000' },
    });
    fireEvent.change(within(actionDialog).getByRole('textbox', { name: 'Reason' }), {
      target: { value: 'Record verified bank settlement evidence for this invoice.' },
    });
    fireEvent.click(within(actionDialog).getByRole('button', { name: 'Confirm action' }));

    await waitFor(() => expect(performBillingAction).toHaveBeenCalledOnce());
    expect(performBillingAction.mock.calls[0]?.slice(0, 5)).toEqual([
      selectedOrganization.id,
      'P11-06',
      'record-payment',
      {
        fields: {
          amountMinor: '5000',
          paymentReference: 'BANK-SETTLEMENT-0001',
          source: 'manual_bank',
        },
        reason: 'Record verified bank settlement evidence for this invoice.',
        targetId: billingInvoiceId,
      },
      `"m11:P11-06:${billingInvoiceId}:2"`,
    ]);
  });

  it('renders P12 aggregate reporting without source-record context', async () => {
    window.location.hash = '#/P12-10';
    const getReportingScreen = vi.fn<ReportingClient['getReportingScreen']>(async () =>
      success(reportingProjection),
    );
    const performReportingAction = vi.fn<ReportingClient['performReportingAction']>();

    render(<App client={sessionClient({ getReportingScreen, performReportingAction })} />);

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Report audit and history' }),
    ).toBeVisible();
    expect(screen.getByText('Aggregate only')).toBeVisible();
    expect(screen.getByText('appointment_total=7')).toBeVisible();
    expect(screen.queryByText(/patient name/i)).not.toBeInTheDocument();
    expect(getReportingScreen).toHaveBeenCalledWith(
      selectedOrganization.id,
      'P12-10',
      expect.objectContaining({ limit: 25 }),
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    );
  });

  it('renders P13 integration evidence without secrets or payloads', async () => {
    window.location.hash = '#/P13-10';
    const getIntegrationScreen = vi.fn<IntegrationClient['getIntegrationScreen']>(async () =>
      success(integrationProjection),
    );
    const performIntegrationAction = vi.fn<IntegrationClient['performIntegrationAction']>();

    render(<App client={sessionClient({ getIntegrationScreen, performIntegrationAction })} />);

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Integration audit and replay' }),
    ).toBeVisible();
    expect(screen.getByText('Payload free')).toBeVisible();
    expect(screen.getByText('retry_exhausted')).toBeVisible();
    expect(screen.queryByText(/webhook body/i)).not.toBeInTheDocument();
    expect(getIntegrationScreen).toHaveBeenCalledWith(
      selectedOrganization.id,
      'P13-10',
      expect.objectContaining({ limit: 25 }),
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    );
  });

  it('focuses identity and workspace headings and provides hash-safe skip navigation', async () => {
    const anonymousView = render(
      <App
        client={sessionClient({
          getAuthenticationSession: async () => success(anonymousSession),
        })}
      />,
    );

    const loginHeading = await screen.findByRole('heading', { name: 'Sign in to CareOS' });
    expect(loginHeading).toHaveFocus();
    const identitySkipLink = screen.getByRole('link', { name: 'Skip to main content' });
    expect(identitySkipLink).toHaveAttribute('href', '#main-content');
    const identityHash = window.location.hash;
    identitySkipLink.focus();
    expect(identitySkipLink).toBeVisible();
    fireEvent.click(identitySkipLink);
    expect(document.getElementById('main-content')).toHaveFocus();
    expect(window.location.hash).toBe(identityHash);
    anonymousView.unmount();

    render(<App client={sessionClient()} />);
    const workspaceHeading = await screen.findByRole('heading', {
      name: 'Administration dashboard',
    });
    expect(workspaceHeading).toHaveFocus();
    const workspaceSkipLink = screen.getByRole('link', { name: 'Skip to main content' });
    const workspaceHash = window.location.hash;
    fireEvent.click(workspaceSkipLink);
    expect(document.getElementById('main-content')).toHaveFocus();
    expect(window.location.hash).toBe(workspaceHash);
  });

  it('renders M1-20 from the authorized membership projection and only exposes live actions', async () => {
    window.location.hash = '#/M1-20';
    const listOrganizationMemberships = vi.fn(async () => success(membershipPage));
    render(<App client={sessionClient({ listOrganizationMemberships })} />);

    expect(await screen.findByRole('heading', { name: 'Administrator access' })).toBeVisible();
    expect(await screen.findByText('Ravi Shah')).toBeVisible();
    expect(screen.getByText('ravi.shah@example.test')).toBeVisible();
    expect(screen.queryByText('Synthetic prototype')).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Invite administrator' })).toHaveAttribute(
      'href',
      '#/M1-02',
    );
    expect(screen.getByRole('link', { name: 'Request MFA reset' })).toHaveAttribute(
      'href',
      '#/M1-03',
    );
    expect(screen.getByRole('button', { name: 'Change role' })).toBeVisible();
    expect(screen.getByRole('button', { name: 'Promote to owner' })).toBeVisible();
    expect(screen.getByRole('heading', { name: 'Governed access change' })).toBeVisible();
    expect(screen.getByText(/Facility-scoped grants remain unavailable/)).toBeVisible();
    expect(listOrganizationMemberships).toHaveBeenCalledWith(
      selectedOrganization.id,
      { limit: 25 },
      { signal: expect.any(AbortSignal) },
    );
  });

  it('projects M1-20 memberships as equivalent record cards at drawer widths', async () => {
    window.location.hash = '#/M1-20';
    const previousMatchMedia = window.matchMedia;
    const mediaQuery = {
      addEventListener: vi.fn(),
      addListener: vi.fn(),
      dispatchEvent: vi.fn(),
      matches: true,
      media: '(max-width: 760px)',
      onchange: null,
      removeEventListener: vi.fn(),
      removeListener: vi.fn(),
    };
    const matchMedia = vi.fn(() => mediaQuery);
    Object.defineProperty(window, 'matchMedia', {
      configurable: true,
      value: matchMedia,
      writable: true,
    });

    try {
      render(<App client={sessionClient()} />);

      const memberships = await screen.findByRole('region', {
        name: 'Organization memberships',
      });
      const cards = within(memberships).getByRole('list', {
        name: 'Organization membership cards',
      });
      expect(within(cards).getByRole('listitem')).toHaveTextContent('Ravi Shah');
      expect(within(cards).getByText('Security administrator')).toBeVisible();
      expect(within(cards).getByText('Enabled')).toBeVisible();
      expect(within(cards).getByRole('button', { name: 'Change role' })).toBeVisible();
      expect(within(memberships).queryByRole('table')).not.toBeInTheDocument();
      expect(matchMedia).toHaveBeenCalledWith('(max-width: 760px)');
    } finally {
      Object.defineProperty(window, 'matchMedia', {
        configurable: true,
        value: previousMatchMedia,
        writable: true,
      });
    }
  });

  it('submits an exact membership role-change request from the authorized row action', async () => {
    window.location.hash = '#/M1-20';
    const requestOrganizationMembershipChange = vi.fn(
      async (_organizationId: string, membershipId: string) =>
        success(
          {
            approvalId: '99999999-9999-4999-8999-999999999999',
            changeType: 'role_change' as const,
            expiresAt: '2026-09-17T12:00:00Z',
            fromRoleKey: 'security_administrator',
            lockVersion: 0,
            membershipId,
            status: 'pending' as const,
            targetUserId: '88888888-8888-4888-8888-888888888888',
            toRoleKey: 'organization_viewer',
          },
          201,
        ),
    );
    render(<App client={sessionClient({ requestOrganizationMembershipChange })} />);

    fireEvent.click(await screen.findByRole('button', { name: 'Change role' }));
    fireEvent.change(screen.getByLabelText('Reason'), {
      target: { value: 'Approved least-privilege role adjustment' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Submit governed step' }));

    await waitFor(() => expect(requestOrganizationMembershipChange).toHaveBeenCalledOnce());
    expect(requestOrganizationMembershipChange).toHaveBeenCalledWith(
      selectedOrganization.id,
      '77777777-7777-4777-8777-777777777777',
      {
        changeType: 'role_change',
        reason: 'Approved least-privilege role adjustment',
        toRoleKey: 'organization_viewer',
      },
      '"organization-membership:77777777-7777-4777-8777-777777777777:0"',
      expect.stringMatching(/^membership-request_role_change:/),
    );
    expect(screen.getByText(/Access change is/)).toHaveTextContent('pending');
  });

  it('submits an exact owner-promotion request from the server-authorized row action', async () => {
    window.location.hash = '#/M1-20';
    const requestOrganizationOwnerTransfer = vi.fn(
      async (_organizationId: string, membershipId: string) =>
        success(
          {
            approvalId: '99999999-9999-4999-8999-999999999999',
            changeType: 'owner_promotion' as const,
            expiresAt: '2026-09-17T12:00:00Z',
            fromRoleKey: 'security_administrator',
            lockVersion: 0,
            membershipId,
            status: 'pending' as const,
            targetUserId: '88888888-8888-4888-8888-888888888888',
            toRoleKey: 'organization_owner',
          },
          201,
        ),
    );
    render(<App client={sessionClient({ requestOrganizationOwnerTransfer })} />);

    fireEvent.click(await screen.findByRole('button', { name: 'Promote to owner' }));
    expect(screen.getByLabelText('New role')).toHaveValue('organization_owner');
    fireEvent.change(screen.getByLabelText('Reason'), {
      target: { value: 'Approved owner succession promotion request' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Submit governed step' }));

    await waitFor(() => expect(requestOrganizationOwnerTransfer).toHaveBeenCalledOnce());
    expect(requestOrganizationOwnerTransfer).toHaveBeenCalledWith(
      selectedOrganization.id,
      '77777777-7777-4777-8777-777777777777',
      {
        reason: 'Approved owner succession promotion request',
        toRoleKey: 'organization_owner',
      },
      '"organization-membership:77777777-7777-4777-8777-777777777777:0"',
      expect.stringMatching(/^membership-request_owner_transfer:/),
    );
    expect(screen.getByText(/Access change is/)).toHaveTextContent('pending');
  });

  it('renders the live facility directory and draft action', async () => {
    window.location.hash = '#/M1-12';
    const updateFacilityDraft = vi.fn<AdministrationClient['updateFacilityDraft']>(async () =>
      success(facilityDirectory),
    );
    const submitFacilityDraft = vi.fn<AdministrationClient['submitFacilityDraft']>(async () =>
      success({
        ...facilityDirectory,
        facilities: facilityDirectory.facilities.map((facility) => ({
          ...facility,
          lockVersion: 1,
          status: 'under_review' as const,
        })),
      }),
    );
    render(<App client={sessionClient({ submitFacilityDraft, updateFacilityDraft })} />);

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Facilities' }),
    ).toBeInTheDocument();
    expect(screen.getByText('North Clinic Limited')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Add facility draft' })).toBeInTheDocument();
    expect(
      screen.queryByRole('complementary', { name: 'Synthetic prototype only' }),
    ).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Edit draft' }));
    expect(screen.getByRole('heading', { name: 'Edit facility draft' })).toBeInTheDocument();
    expect(screen.getByLabelText('Facility code')).toHaveValue('NORTH-01');
    fireEvent.change(screen.getByLabelText('Display name'), {
      target: { value: 'North Clinic Updated' },
    });
    fireEvent.change(screen.getByLabelText('Reason'), {
      target: { value: 'Correct the approved facility display name' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Save facility' }));
    await waitFor(() => expect(updateFacilityDraft).toHaveBeenCalledOnce());
    expect(updateFacilityDraft).toHaveBeenCalledWith(
      selectedOrganization.id,
      '33333333-3333-4333-8333-333333333333',
      expect.objectContaining({
        displayName: 'North Clinic Updated',
        reason: 'Correct the approved facility display name',
      }),
      '"facility:33333333-3333-4333-8333-333333333333:0"',
      expect.stringMatching(/^facility-update:/),
    );

    fireEvent.click(screen.getByRole('button', { name: 'Submit for review' }));
    fireEvent.change(screen.getByLabelText('Submission reason'), {
      target: { value: 'Submit the completed facility for independent review' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Confirm submission' }));
    await waitFor(() => expect(submitFacilityDraft).toHaveBeenCalledOnce());
    expect(submitFacilityDraft).toHaveBeenCalledWith(
      selectedOrganization.id,
      '33333333-3333-4333-8333-333333333333',
      { reason: 'Submit the completed facility for independent review' },
      '"facility:33333333-3333-4333-8333-333333333333:0"',
      expect.stringMatching(/^facility-submit:/),
    );
    expect(screen.getByText('under_review')).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('Search facilities'), { target: { value: 'north' } });
    await waitFor(() => expect(screen.getByText('North Clinic Limited')).toBeInTheDocument());
  });

  it('renders the live unit hierarchy and creates a governed draft', async () => {
    window.location.hash = '#/M1-14';
    const createOrganizationUnitDraft = vi.fn<AdministrationClient['createOrganizationUnitDraft']>(
      async () => success(unitDirectory, 201),
    );
    const updateOrganizationUnitDraft = vi.fn<AdministrationClient['updateOrganizationUnitDraft']>(
      async () => success(unitDirectory),
    );
    const reparentOrganizationUnit = vi.fn<AdministrationClient['reparentOrganizationUnit']>(
      async () => success(unitDirectory),
    );
    const activateOrganizationUnit = vi.fn<AdministrationClient['activateOrganizationUnit']>(
      async () => success(unitDirectory),
    );
    render(
      <App
        client={sessionClient({
          createOrganizationUnitDraft,
          activateOrganizationUnit,
          reparentOrganizationUnit,
          updateOrganizationUnitDraft,
        })}
      />,
    );

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Departments and units' }),
    ).toBeInTheDocument();
    expect(await screen.findAllByText('Clinical Services')).toHaveLength(2);
    expect(screen.getByText('— Cardiology')).toBeInTheDocument();
    expect(screen.getByText('Depth 2 · revision 2')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Add hierarchy draft' })).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('Unit code'), { target: { value: 'imaging' } });
    fireEvent.change(screen.getByLabelText('Type'), { target: { value: 'unit' } });
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Imaging' } });
    fireEvent.change(screen.getByLabelText('Parent'), {
      target: { value: '44444444-4444-4444-8444-444444444444' },
    });
    fireEvent.change(screen.getByLabelText('Reason'), {
      target: { value: 'Create the approved imaging unit hierarchy draft' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Add hierarchy draft' }));

    await waitFor(() => expect(createOrganizationUnitDraft).toHaveBeenCalledOnce());
    expect(createOrganizationUnitDraft).toHaveBeenCalledWith(
      selectedOrganization.id,
      facilityDirectory.facilities[0]!.facilityId,
      expect.objectContaining({
        name: 'Imaging',
        parentId: '44444444-4444-4444-8444-444444444444',
        reason: 'Create the approved imaging unit hierarchy draft',
        unitCode: 'IMAGING',
        unitType: 'unit',
      }),
      expect.stringMatching(/^unit-draft:/),
    );

    fireEvent.click(screen.getAllByRole('button', { name: 'Edit draft' })[0]!);
    fireEvent.change(screen.getByLabelText('Edit name'), {
      target: { value: 'Clinical Operations' },
    });
    fireEvent.change(screen.getByLabelText('Edit reason'), {
      target: { value: 'Rename the approved clinical hierarchy draft' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Save draft changes' }));
    await waitFor(() => expect(updateOrganizationUnitDraft).toHaveBeenCalledOnce());
    expect(updateOrganizationUnitDraft).toHaveBeenCalledWith(
      selectedOrganization.id,
      facilityDirectory.facilities[0]!.facilityId,
      '44444444-4444-4444-8444-444444444444',
      expect.objectContaining({
        name: 'Clinical Operations',
        parentId: null,
        reason: 'Rename the approved clinical hierarchy draft',
        unitCode: 'CLINICAL',
      }),
      '"organization-unit:44444444-4444-4444-8444-444444444444:0"',
      expect.stringMatching(/^unit-update:/),
    );

    fireEvent.click(screen.getAllByRole('button', { name: 'Change parent' })[1]!);
    fireEvent.change(screen.getByLabelText('New parent'), { target: { value: '' } });
    fireEvent.change(screen.getByLabelText('Parent change reason'), {
      target: { value: 'Move cardiology to the facility hierarchy root' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Save parent change' }));
    await waitFor(() => expect(reparentOrganizationUnit).toHaveBeenCalledOnce());
    expect(reparentOrganizationUnit).toHaveBeenCalledWith(
      selectedOrganization.id,
      facilityDirectory.facilities[0]!.facilityId,
      '55555555-5555-4555-8555-555555555555',
      expect.objectContaining({
        parentId: null,
        reason: 'Move cardiology to the facility hierarchy root',
      }),
      '"organization-unit:55555555-5555-4555-8555-555555555555:2"',
      expect.stringMatching(/^unit-reparent:/),
    );

    fireEvent.click(screen.getAllByRole('button', { name: 'Activate' })[0]!);
    expect(screen.getByText(/requires current MFA and recent authentication/)).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Lifecycle reason'), {
      target: { value: 'Activate the approved root hierarchy in parent order' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Confirm activate' }));
    await waitFor(() => expect(activateOrganizationUnit).toHaveBeenCalledOnce());
    expect(activateOrganizationUnit).toHaveBeenCalledWith(
      selectedOrganization.id,
      facilityDirectory.facilities[0]!.facilityId,
      '44444444-4444-4444-8444-444444444444',
      { reason: 'Activate the approved root hierarchy in parent order' },
      '"organization-unit:44444444-4444-4444-8444-444444444444:0"',
      expect.stringMatching(/^unit-activate:/),
    );
  });

  it('does not infer hierarchy lifecycle authority from draft management', async () => {
    window.location.hash = '#/M1-14';
    render(
      <App
        client={sessionClient({
          getOrganizationUnitDirectory: async () =>
            success({ ...unitDirectory, canManageLifecycle: false }),
        })}
      />,
    );

    expect(await screen.findAllByRole('button', { name: 'Edit draft' })).toHaveLength(2);
    expect(screen.queryByRole('button', { name: 'Activate' })).not.toBeInTheDocument();
  });

  it('renders service locations and creates a virtual location draft', async () => {
    window.location.hash = '#/M1-15';
    const createServiceLocationDraft = vi.fn<AdministrationClient['createServiceLocationDraft']>(
      async () => success(locationDirectory, 201),
    );
    const updateServiceLocationDraft = vi.fn<AdministrationClient['updateServiceLocationDraft']>(
      async () => success(locationDirectory),
    );
    const reparentServiceLocation = vi.fn<AdministrationClient['reparentServiceLocation']>(
      async () => success(locationDirectory),
    );
    const activateServiceLocation = vi.fn<AdministrationClient['activateServiceLocation']>(
      async () => success(locationDirectory),
    );
    render(
      <App
        client={sessionClient({
          createServiceLocationDraft,
          activateServiceLocation,
          reparentServiceLocation,
          updateServiceLocationDraft,
        })}
      />,
    );

    expect(await screen.findByRole('heading', { level: 1, name: 'Locations' })).toBeInTheDocument();
    expect(screen.getAllByText('Main clinic')).toHaveLength(2);
    fireEvent.change(screen.getByLabelText('Location code'), { target: { value: 'telehealth' } });
    fireEvent.change(screen.getByLabelText('Type'), { target: { value: 'virtual' } });
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Telehealth room' } });
    fireEvent.change(screen.getByLabelText('Virtual service type'), {
      target: { value: 'video consultation' },
    });
    fireEvent.change(screen.getByLabelText('Creation reason'), {
      target: { value: 'Create the approved virtual consultation location' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Create location draft' }));

    await waitFor(() => expect(createServiceLocationDraft).toHaveBeenCalledOnce());
    expect(createServiceLocationDraft).toHaveBeenCalledWith(
      selectedOrganization.id,
      facilityDirectory.facilities[0]!.facilityId,
      expect.objectContaining({
        addressId: null,
        locationCode: 'TELEHEALTH',
        locationType: 'virtual',
        name: 'Telehealth room',
        virtualServiceType: 'video consultation',
      }),
      expect.stringMatching(/^location-draft:/),
    );

    fireEvent.click(screen.getAllByRole('button', { name: 'Edit draft' })[0]!);
    fireEvent.change(screen.getByLabelText('Edit name'), { target: { value: 'Main care clinic' } });
    fireEvent.change(screen.getByLabelText('Edit reason'), {
      target: { value: 'Rename the approved physical location draft' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Save location draft' }));
    await waitFor(() => expect(updateServiceLocationDraft).toHaveBeenCalledOnce());
    expect(updateServiceLocationDraft).toHaveBeenCalledWith(
      selectedOrganization.id,
      facilityDirectory.facilities[0]!.facilityId,
      '66666666-6666-4666-8666-666666666666',
      expect.objectContaining({ name: 'Main care clinic', locationCode: 'MAIN_CLINIC' }),
      '"service-location:66666666-6666-4666-8666-666666666666:0"',
      expect.stringMatching(/^location-update:/),
    );

    fireEvent.click(screen.getAllByRole('button', { name: 'Change parent' })[1]!);
    fireEvent.change(screen.getByLabelText('New location parent'), { target: { value: '' } });
    fireEvent.change(screen.getByLabelText('Parent change reason'), {
      target: { value: 'Move the satellite service location to the facility root' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Save location parent' }));
    await waitFor(() => expect(reparentServiceLocation).toHaveBeenCalledOnce());
    expect(reparentServiceLocation).toHaveBeenCalledWith(
      selectedOrganization.id,
      facilityDirectory.facilities[0]!.facilityId,
      '88888888-8888-4888-8888-888888888888',
      expect.objectContaining({ parentId: null }),
      '"service-location:88888888-8888-4888-8888-888888888888:2"',
      expect.stringMatching(/^location-reparent:/),
    );

    fireEvent.click(screen.getAllByRole('button', { name: 'Activate' })[0]!);
    expect(screen.getByText(/requires current MFA and recent authentication/)).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Location lifecycle reason'), {
      target: { value: 'Activate the approved service location in parent order' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Confirm activate' }));
    await waitFor(() => expect(activateServiceLocation).toHaveBeenCalledOnce());
    expect(activateServiceLocation).toHaveBeenCalledWith(
      selectedOrganization.id,
      facilityDirectory.facilities[0]!.facilityId,
      '66666666-6666-4666-8666-666666666666',
      { reason: 'Activate the approved service location in parent order' },
      '"service-location:66666666-6666-4666-8666-666666666666:0"',
      expect.stringMatching(/^location-activate:/),
    );
  });

  it('does not expose location creation without exact management authority', async () => {
    window.location.hash = '#/M1-15';
    render(
      <App
        client={sessionClient({
          getServiceLocationDirectory: async () =>
            success({ ...locationDirectory, canManage: false }),
        })}
      />,
    );
    expect(await screen.findByText('Main clinic')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Add location draft' })).not.toBeInTheDocument();
  });

  it('does not infer location lifecycle authority from draft management', async () => {
    window.location.hash = '#/M1-15';
    render(
      <App
        client={sessionClient({
          getServiceLocationDirectory: async () =>
            success({ ...locationDirectory, canManageLifecycle: false }),
        })}
      />,
    );
    expect(await screen.findAllByRole('button', { name: 'Edit draft' })).toHaveLength(2);
    expect(screen.queryByRole('button', { name: 'Activate' })).not.toBeInTheDocument();
  });

  it('creates an organization identifier draft through the governed API', async () => {
    window.location.hash = '#/M1-08';
    const createOrganizationIdentifier = vi.fn<
      AdministrationClient['createOrganizationIdentifier']
    >(async (_organizationId, body) => {
      const created: OrganizationIdentifier = {
        ...organizationIdentifier,
        assigningAuthority: body.assigningAuthority,
        effectiveFrom: body.effectiveFrom,
        identifierId: '55555555-5555-4555-8555-555555555555',
        isPrimary: body.isPrimary,
        issueDate: body.issueDate,
        jurisdictionCountryCode: body.jurisdictionCountryCode,
        value: body.value,
      };
      return {
        ...success(created, 201),
        etag: `"organization-identifier:${created.identifierId}:0"`,
      };
    });
    render(<App client={sessionClient({ createOrganizationIdentifier })} />);

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Registration and identifiers' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'REG-IN-0042' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Verify' })).toBeEnabled();

    fireEvent.click(screen.getByRole('button', { name: 'Add identifier' }));
    fireEvent.change(screen.getByLabelText('Assigning authority'), {
      target: { value: 'State Health Registry' },
    });
    fireEvent.change(screen.getByLabelText('Identifier value'), {
      target: { value: 'STATE-1024' },
    });
    fireEvent.change(screen.getByLabelText('Jurisdiction (optional)'), {
      target: { value: 'in' },
    });
    fireEvent.click(screen.getByLabelText(/Use as the primary identifier/));
    fireEvent.change(screen.getByLabelText('Reason'), {
      target: { value: 'Approved registration intake CARE-1024' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Create draft' }));

    await waitFor(() => expect(createOrganizationIdentifier).toHaveBeenCalledOnce());
    expect(createOrganizationIdentifier.mock.calls[0]).toEqual([
      selectedOrganization.id,
      expect.objectContaining({
        assigningAuthority: 'State Health Registry',
        effectiveFrom: expect.stringMatching(/Z$/),
        identifierType: 'registration',
        isPrimary: true,
        jurisdictionCountryCode: 'IN',
        reason: 'Approved registration intake CARE-1024',
        value: 'STATE-1024',
      }),
      expect.stringMatching(/^organization-identifier:[0-9a-f-]{36}$/),
    ]);
    expect(await screen.findByRole('status')).toHaveTextContent(
      'Identifier created as a governed draft',
    );
    expect(screen.getByRole('heading', { name: 'STATE-1024' })).toBeInTheDocument();
  });

  it('verifies an identifier with its strong revision, evidence, and retry key', async () => {
    window.location.hash = '#/M1-08';
    const verifyOrganizationIdentifier = vi.fn<
      AdministrationClient['verifyOrganizationIdentifier']
    >(async (_organizationId, _identifierId, body) => {
      const verified: OrganizationIdentifier = {
        ...organizationIdentifier,
        availableActions: [],
        evidenceReference: body.evidenceReference,
        lockVersion: 1,
        status: 'verified',
        updatedAt: '2026-09-16T09:00:00Z',
        verificationStatus: 'verified',
      };
      return {
        ...success(verified),
        etag: `"organization-identifier:${verified.identifierId}:1"`,
      };
    });
    render(<App client={sessionClient({ verifyOrganizationIdentifier })} />);

    await screen.findByRole('heading', { name: 'REG-IN-0042' });
    fireEvent.click(screen.getByRole('button', { name: 'Verify' }));
    fireEvent.change(screen.getByLabelText('Verification evidence reference'), {
      target: { value: 'NPR-CASE-2026-1042' },
    });
    fireEvent.change(screen.getByLabelText('Reason'), {
      target: { value: 'Authority verification completed CARE-1042' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Verify identifier' }));

    await waitFor(() => expect(verifyOrganizationIdentifier).toHaveBeenCalledOnce());
    expect(verifyOrganizationIdentifier.mock.calls[0]).toEqual([
      selectedOrganization.id,
      organizationIdentifier.identifierId,
      {
        evidenceReference: 'NPR-CASE-2026-1042',
        reason: 'Authority verification completed CARE-1042',
      },
      `"organization-identifier:${organizationIdentifier.identifierId}:0"`,
      expect.stringMatching(/^organization-identifier:[0-9a-f-]{36}$/),
    ]);
    expect(await screen.findByRole('status')).toHaveTextContent(
      'Identifier verified with authority evidence',
    );
    expect(screen.getByText('NPR-CASE-2026-1042')).toBeInTheDocument();
  });

  it('supersedes an identifier with exact revisions for both verified records', async () => {
    window.location.hash = '#/M1-08';
    const target: OrganizationIdentifier = {
      ...organizationIdentifier,
      availableActions: ['supersede'],
      evidenceReference: 'NPR-ORIGINAL-1042',
      lockVersion: 1,
      status: 'verified',
      verificationStatus: 'verified',
    };
    const replacement: OrganizationIdentifier = {
      ...target,
      availableActions: ['revoke', 'supersede'],
      evidenceReference: 'NPR-REPLACEMENT-1042',
      identifierId: '55555555-5555-4555-8555-555555555555',
      isPrimary: false,
      value: 'REG-IN-REPLACEMENT',
    };
    const finalCollection: OrganizationIdentifierCollection = {
      ...organizationIdentifiers,
      items: [
        {
          ...replacement,
          availableActions: ['supersede'],
          isPrimary: true,
          lockVersion: 2,
          supersedesId: target.identifierId,
        },
        { ...target, availableActions: [], lockVersion: 2, status: 'superseded' },
      ],
    };
    let reads = 0;
    const listOrganizationIdentifiers = vi.fn(async () =>
      success(
        reads++ === 0
          ? { ...organizationIdentifiers, items: [target, replacement] }
          : finalCollection,
      ),
    );
    const supersedeOrganizationIdentifier = vi.fn<
      AdministrationClient['supersedeOrganizationIdentifier']
    >(async () => {
      const superseded = finalCollection.items[1]!;
      return {
        ...success(superseded),
        etag: `"organization-identifier:${superseded.identifierId}:2"`,
      };
    });
    render(
      <App
        client={sessionClient({
          listOrganizationIdentifiers,
          supersedeOrganizationIdentifier,
        })}
      />,
    );

    const targetHeading = await screen.findByRole('heading', { name: 'REG-IN-0042' });
    const targetCard = targetHeading.closest('article');
    expect(targetCard).not.toBeNull();
    fireEvent.click(within(targetCard as HTMLElement).getByRole('button', { name: 'Supersede' }));
    expect(screen.getByLabelText('Verified replacement')).toHaveValue(replacement.identifierId);
    fireEvent.change(screen.getByLabelText('Reason'), {
      target: { value: 'Verified registry replacement approved CARE-1042' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Supersede identifier' }));

    await waitFor(() => expect(supersedeOrganizationIdentifier).toHaveBeenCalledOnce());
    expect(supersedeOrganizationIdentifier.mock.calls[0]).toEqual([
      selectedOrganization.id,
      target.identifierId,
      {
        reason: 'Verified registry replacement approved CARE-1042',
        replacementEtag: `"organization-identifier:${replacement.identifierId}:1"`,
        replacementId: replacement.identifierId,
      },
      `"organization-identifier:${target.identifierId}:1"`,
      expect.stringMatching(/^organization-identifier:[0-9a-f-]{36}$/),
    ]);
    expect(await screen.findByRole('status')).toHaveTextContent(
      'Identifier superseded by the verified replacement',
    );
    expect(screen.getByText('Supersedes').nextElementSibling).toHaveTextContent('REG-IN-0042');
  });

  it('renders M1-09 structured addresses and only masked contact values', async () => {
    window.location.hash = '#/M1-09';
    const listOrganizationContacts = vi.fn(async () => success(organizationContacts));
    render(<App client={sessionClient({ listOrganizationContacts })} />);

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Addresses and contacts' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '42 Care Street, Andheri East' })).toBeVisible();
    expect(screen.getByRole('heading', { name: 'o***@***.org' })).toBeVisible();
    expect(screen.getByText('Confidential · masked projection')).toBeVisible();
    expect(screen.queryByText('operations@example.org')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Add contact' })).toBeEnabled();
    expect(listOrganizationContacts).toHaveBeenCalledWith(selectedOrganization.id, {
      signal: expect.any(AbortSignal),
    });
  });

  it('creates a governed contact without projecting its confidential value back', async () => {
    window.location.hash = '#/M1-09';
    const created: OrganizationContact = {
      ...organizationContact,
      contactId: '77777777-7777-4777-8777-777777777773',
      isPreferred: false,
      maskedValue: 'n***@***.org',
    };
    let reads = 0;
    const listOrganizationContacts = vi.fn(async () =>
      success(
        reads++ === 0
          ? organizationContacts
          : { ...organizationContacts, contacts: [created, organizationContact] },
      ),
    );
    const createOrganizationContact = vi.fn<AdministrationClient['createOrganizationContact']>(
      async () => ({
        ...success(created, 201),
        etag: `"organization-contact:${created.contactId}:0"`,
      }),
    );
    render(<App client={sessionClient({ createOrganizationContact, listOrganizationContacts })} />);

    fireEvent.click(await screen.findByRole('button', { name: 'Add contact' }));
    const actionPanel = screen.getByRole('heading', { name: 'Add contact' }).closest('section');
    expect(actionPanel).not.toBeNull();
    fireEvent.change(within(actionPanel as HTMLElement).getByLabelText('Email address'), {
      target: { value: 'new.operations@example.org' },
    });
    fireEvent.click(
      within(actionPanel as HTMLElement).getByLabelText(/Primary contact for this purpose/),
    );
    fireEvent.change(within(actionPanel as HTMLElement).getByLabelText('Reason'), {
      target: { value: 'Approved operational contact intake CARE-2001' },
    });
    fireEvent.click(
      within(actionPanel as HTMLElement).getByRole('button', { name: 'Add contact' }),
    );

    await waitFor(() => expect(createOrganizationContact).toHaveBeenCalledOnce());
    expect(createOrganizationContact.mock.calls[0]).toEqual([
      selectedOrganization.id,
      expect.objectContaining({
        channel: 'email',
        effectiveFrom: expect.stringMatching(/Z$/),
        effectiveTo: null,
        isPreferred: false,
        isPrimary: true,
        purpose: 'operational',
        reason: 'Approved operational contact intake CARE-2001',
        value: 'new.operations@example.org',
      }),
      expect.stringMatching(/^organization-contact:[0-9a-f-]{36}$/),
    ]);
    expect(await screen.findByRole('status')).toHaveTextContent(
      'only its masked projection is displayed',
    );
    expect(screen.queryByText('new.operations@example.org')).not.toBeInTheDocument();
    expect(await screen.findByRole('heading', { name: 'n***@***.org' })).toBeVisible();
  });

  it('fails closed when an M1-09 projection includes a raw contact value', async () => {
    window.location.hash = '#/M1-09';
    const unsafe = {
      ...organizationContacts,
      contacts: [
        {
          ...organizationContact,
          value: 'operations@example.org',
        },
      ],
    } as unknown as OrganizationContactCollection;
    render(
      <App
        client={sessionClient({
          listOrganizationContacts: async () => success(unsafe),
        })}
      />,
    );

    expect(
      await screen.findByText(/did not contain a valid masked address and contact projection/),
    ).toBeVisible();
    expect(screen.queryByText('operations@example.org')).not.toBeInTheDocument();
  });

  it('keeps an empty live assessment projection and terminal pagination non-activatable', async () => {
    window.location.hash = '#/COS-27';
    render(<App client={sessionClient()} />);

    expect(
      await screen.findByRole('heading', { level: 1, name: findScreen('COS-27').title }),
    ).toBeInTheDocument();
    expect(screen.getByText('Source package unavailable')).toBeInTheDocument();
    expect(screen.getByText('No authorized records found')).toBeInTheDocument();
    expect(
      screen.getByText('No governed actions are available for this account and selection.'),
    ).toBeInTheDocument();

    const pagination = screen.getByRole('navigation', { name: 'Assessment screen pagination' });
    expect(within(pagination).queryByRole('link', { name: 'Next' })).not.toBeInTheDocument();
    expect(within(pagination).getByText('Next')).toHaveAttribute('aria-disabled', 'true');
  });

  it('fails closed after authentication when a hash route is not registered', async () => {
    window.location.hash = '#/M1-99/forged?return=M1-05';

    render(<App client={sessionClient()} />);

    const heading = await screen.findByRole('heading', { name: 'Page not found' });
    expect(heading).toHaveFocus();
    expect(screen.getByText(/No business screen was loaded/)).toBeInTheDocument();
    expect(
      screen.getByRole('link', { name: 'Return to administration dashboard' }),
    ).toHaveAttribute('href', '#/M1-05');
    expect(
      screen.queryByRole('heading', { name: 'Administration dashboard' }),
    ).not.toBeInTheDocument();
    expect(screen.queryByText('M1-99/forged')).not.toBeInTheDocument();
  });

  it('keeps an unknown protected route behind the anonymous session gate', async () => {
    window.location.hash = '#/not-a-careos-route';

    render(
      <App
        client={sessionClient({
          getAuthenticationSession: async () => success(anonymousSession),
        })}
      />,
    );

    expect(await screen.findByRole('heading', { name: 'Sign in to CareOS' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Page not found' })).not.toBeInTheDocument();
  });

  it('renders the protected workspace from validated server identity and organization state', async () => {
    render(<App client={sessionClient()} />);

    expect(
      await screen.findByRole('heading', { name: 'Administration dashboard' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Priority exceptions' })).toBeInTheDocument();
    expect(screen.getByText('3/15')).toBeInTheDocument();
    expect(screen.getAllByText('Asha Verma')).toHaveLength(2);
    expect(screen.getByLabelText('Current organization')).toHaveValue(selectedOrganization.id);
  });

  it('renders setup readiness only from the selected organization response', async () => {
    window.location.hash = '#/M1-06';
    const getAdministrationReadiness = vi.fn<AdministrationClient['getAdministrationReadiness']>(
      async () => success(readiness),
    );

    render(<App client={sessionClient({ getAdministrationReadiness })} />);

    expect(await screen.findByRole('heading', { name: 'Setup checklist' })).toBeInTheDocument();
    expect(await screen.findByText(/Approved catalogue m1-readiness-v1/)).toBeInTheDocument();
    expect(
      screen.getByText('Versioned configuration integrity is unavailable.'),
    ).toBeInTheDocument();
    expect(getAdministrationReadiness).toHaveBeenCalledWith(
      selectedOrganization.id,
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    );
  });

  it('fails closed when readiness gates are not in the approved catalogue order', async () => {
    window.location.hash = '#/M1-06';
    const invalidReadiness = structuredClone(readiness);
    [invalidReadiness.gates[0], invalidReadiness.gates[1]] = [
      invalidReadiness.gates[1],
      invalidReadiness.gates[0],
    ];

    render(
      <App
        client={sessionClient({
          getAdministrationReadiness: async () => success(invalidReadiness),
        })}
      />,
    );

    expect(await screen.findByText('Unable to load organization data')).toBeInTheDocument();
    expect(
      screen.getByText('The server response did not match the organization-readiness contract.'),
    ).toBeInTheDocument();
  });

  it('updates an organization profile with its strong revision and a caller-owned retry key', async () => {
    window.location.hash = '#/M1-07';
    const updateOrganizationProfile = vi.fn<AdministrationClient['updateOrganizationProfile']>(
      async (_organizationId, body) => ({
        ...success({
          ...organizationProfile,
          countryCode: body.countryCode,
          displayName: body.displayName,
          legalName: body.legalName,
          locale: body.locale,
          lockVersion: 5,
          organizationType: body.organizationType,
          timezone: body.timezone,
          tradingName: body.tradingName,
          updatedAt: '2026-09-16T09:00:00Z',
        }),
        etag: '"organization-profile:5"',
      }),
    );

    render(<App client={sessionClient({ updateOrganizationProfile })} />);

    expect(
      await screen.findByRole('heading', { name: 'Organization profile' }),
    ).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Display name'), {
      target: { value: 'North Care Network' },
    });
    fireEvent.change(screen.getByLabelText('Trading name (optional)'), {
      target: { value: 'North Care' },
    });
    fireEvent.change(screen.getByLabelText('Organization type'), {
      target: { value: 'care_network' },
    });
    fireEvent.change(screen.getByLabelText('Locale'), {
      target: { value: 'en-GB' },
    });
    fireEvent.change(screen.getByLabelText('Reason for change'), {
      target: { value: 'Approved identity review CARE-42' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Save organization profile' }));

    await waitFor(() => expect(updateOrganizationProfile).toHaveBeenCalledOnce());
    expect(updateOrganizationProfile.mock.calls[0]).toEqual([
      selectedOrganization.id,
      {
        countryCode: 'IN',
        displayName: 'North Care Network',
        legalName: 'North Clinic Private Limited',
        locale: 'en-GB',
        organizationType: 'care_network',
        reason: 'Approved identity review CARE-42',
        timezone: 'Asia/Kolkata',
        tradingName: 'North Care',
      },
      '"organization-profile:4"',
      expect.stringMatching(/^organization-profile:[0-9a-f-]{36}$/),
    ]);
    expect(await screen.findByRole('status')).toHaveTextContent(
      'Organization profile saved with audit and outbox evidence',
    );
    expect(screen.getByText(/Revision 5/)).toBeInTheDocument();
  });

  it('projects organization profile mutations as unavailable without live manage permission', async () => {
    window.location.hash = '#/M1-07';
    const updateOrganizationProfile = vi.fn<AdministrationClient['updateOrganizationProfile']>();
    render(
      <App
        client={sessionClient({
          getOrganizationProfile: async () => ({
            ...success({ ...organizationProfile, editable: false }),
            etag: '"organization-profile:4"',
          }),
          updateOrganizationProfile,
        })}
      />,
    );

    expect(await screen.findByText(/You have read-only profile access/)).toBeInTheDocument();
    expect(screen.getByLabelText('Legal name')).toHaveAttribute('readonly');
    expect(screen.getByLabelText('Organization type')).toBeDisabled();
    expect(screen.queryByLabelText('Reason for change')).not.toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: 'Save organization profile' }),
    ).not.toBeInTheDocument();
    expect(updateOrganizationProfile).not.toHaveBeenCalled();
  });

  it('blocks a stale organization profile result until the latest revision is reloaded', async () => {
    window.location.hash = '#/M1-07';
    const updateOrganizationProfile = vi.fn<AdministrationClient['updateOrganizationProfile']>(
      async () => failure(412),
    );
    render(<App client={sessionClient({ updateOrganizationProfile })} />);

    await screen.findByRole('heading', { name: 'Organization profile' });
    fireEvent.change(screen.getByLabelText('Display name'), {
      target: { value: 'A stale change' },
    });
    fireEvent.change(screen.getByLabelText('Reason for change'), {
      target: { value: 'Concurrent update test' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Save organization profile' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'This profile changed after it was loaded',
    );
    expect(screen.getByRole('button', { name: 'Reload latest' })).toBeEnabled();
  });

  it('keeps a protected route locked until real credentials establish a server session', async () => {
    const login = vi.fn(async () => success(authenticatedSession));
    const listSelectableOrganizations = vi.fn(async () => success([selectedOrganization]));
    render(
      <App
        client={sessionClient({
          getAuthenticationSession: async () => success(anonymousSession),
          listSelectableOrganizations,
          login,
        })}
      />,
    );

    expect(await screen.findByRole('heading', { name: 'Sign in to CareOS' })).toBeInTheDocument();
    expect(
      screen.queryByRole('heading', { name: 'Administration dashboard' }),
    ).not.toBeInTheDocument();
    expect(screen.getByLabelText('Email address')).toHaveValue('');
    expect(screen.getByLabelText('Password')).toHaveValue('');

    fireEvent.change(screen.getByLabelText('Email address'), {
      target: { value: 'asha@example.test' },
    });
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'not-a-demo-secret' } });
    fireEvent.click(screen.getByRole('button', { name: 'Continue securely' }));

    await waitFor(() =>
      expect(login).toHaveBeenCalledWith({
        email: 'asha@example.test',
        password: 'not-a-demo-secret',
      }),
    );
    expect(
      await screen.findByRole('heading', { name: 'Administration dashboard' }),
    ).toBeInTheDocument();
    expect(listSelectableOrganizations).toHaveBeenCalledOnce();
  });

  it('completes a pending MFA challenge before loading organization access', async () => {
    const completeMfaChallenge = vi.fn(async () => success(authenticatedSession));
    render(
      <App
        client={sessionClient({
          completeMfaChallenge,
          getAuthenticationSession: async () => success(mfaSession),
        })}
      />,
    );

    expect(
      await screen.findByRole('heading', { name: 'Verify your identity' }),
    ).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Authentication code'), { target: { value: '654321' } });
    fireEvent.click(screen.getByRole('button', { name: 'Verify securely' }));

    await waitFor(() => expect(completeMfaChallenge).toHaveBeenCalledWith({ code: '654321' }));
    expect(
      await screen.findByRole('heading', { name: 'Administration dashboard' }),
    ).toBeInTheDocument();
  });

  it('keeps mandatory-role access locked until enrollment codes are stored', async () => {
    const getAuthenticationSession = vi
      .fn<SessionClient['getAuthenticationSession']>()
      .mockResolvedValueOnce(success(mfaEnrollmentSession, 202))
      .mockResolvedValue(success({ ...authenticatedSession, mfaEnabled: true, mfaRequired: true }));
    const listSelectableOrganizations = vi.fn(async () => success([selectedOrganization]));
    const startMfaEnrollment = vi.fn(async () =>
      success({
        provisioningUri:
          'otpauth://totp/ROOTOPATHY%20CareOS:asha@example.test?secret=ABCDEFGHIJKLMNOP',
        secret: 'ABCDEFGHIJKLMNOP',
      }),
    );
    const verifyMfaEnrollment = vi.fn(async () =>
      success({ recoveryCodes: ['2345-6789-ABCD', 'EFGH-JKLM-NPQR'] }),
    );
    render(
      <App
        client={sessionClient({
          getAuthenticationSession,
          listSelectableOrganizations,
          startMfaEnrollment,
          verifyMfaEnrollment,
        })}
      />,
    );

    expect(
      await screen.findByRole('heading', { name: 'Set up multi-factor authentication' }),
    ).toBeVisible();
    expect(screen.getByText(/workspace access remains locked/i)).toBeVisible();
    expect(listSelectableOrganizations).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: 'Set up authenticator' }));
    expect(await screen.findByLabelText('One-time authenticator setup key')).toHaveTextContent(
      'ABCDEFGHIJKLMNOP',
    );
    fireEvent.change(screen.getByLabelText('Six-digit authenticator code'), {
      target: { value: '654321' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Confirm and enable MFA' }));

    expect(await screen.findByText('2345-6789-ABCD')).toBeVisible();
    expect(listSelectableOrganizations).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'I have stored these codes securely' }));

    expect(await screen.findByRole('heading', { name: 'Administration dashboard' })).toBeVisible();
    expect(getAuthenticationSession).toHaveBeenCalledTimes(2);
    expect(listSelectableOrganizations).toHaveBeenCalledOnce();
  });

  it('requires explicit organization selection and can switch context from the shell', async () => {
    const selectOrganization = vi.fn(async ({ organizationId }: { organizationId: string }) =>
      success({
        ...(organizationId === otherOrganization.id ? otherOrganization : selectedOrganization),
        selected: true,
      }),
    );
    render(
      <App
        client={sessionClient({
          listSelectableOrganizations: async () =>
            success([
              { ...selectedOrganization, selected: false },
              { ...otherOrganization, selected: false },
            ]),
          selectOrganization,
        })}
      />,
    );

    expect(
      await screen.findByRole('heading', { name: 'Choose an organization' }),
    ).toBeInTheDocument();
    fireEvent.click(screen.getByLabelText(/South Clinic/));
    fireEvent.click(screen.getByRole('button', { name: 'Open workspace' }));

    await waitFor(() =>
      expect(selectOrganization).toHaveBeenCalledWith({ organizationId: otherOrganization.id }),
    );
    await screen.findByRole('heading', { name: 'Administration dashboard' });
    expect(screen.getByLabelText('Current organization')).toHaveValue(otherOrganization.id);

    fireEvent.change(screen.getByLabelText('Current organization'), {
      target: { value: selectedOrganization.id },
    });
    await waitFor(() =>
      expect(selectOrganization).toHaveBeenLastCalledWith({
        organizationId: selectedOrganization.id,
      }),
    );
  });

  it('does not expose the workspace to an authenticated user without organization access', async () => {
    render(
      <App
        client={sessionClient({
          listSelectableOrganizations: async () => success([]),
        })}
      />,
    );

    expect(
      await screen.findByRole('heading', { name: 'No organization access' }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('heading', { name: 'Administration dashboard' }),
    ).not.toBeInTheDocument();
  });

  it('fails closed when the server returns more than one selected organization', async () => {
    render(
      <App
        client={sessionClient({
          listSelectableOrganizations: async () =>
            success([selectedOrganization, { ...otherOrganization, selected: true }]),
        })}
      />,
    );

    expect(
      await screen.findByRole('heading', { name: 'CareOS access is unavailable' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('ambiguous selection');
    expect(
      screen.queryByRole('heading', { name: 'Administration dashboard' }),
    ).not.toBeInTheDocument();
  });

  it('keeps the workspace locked on session errors and retries only when requested', async () => {
    const getAuthenticationSession = vi
      .fn<SessionClient['getAuthenticationSession']>()
      .mockResolvedValueOnce(failure())
      .mockResolvedValueOnce(success(anonymousSession));
    render(<App client={sessionClient({ getAuthenticationSession })} />);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Support reference: frontend-session-test');
    expect(alert).toHaveTextContent('Try again in about 12 seconds.');
    expect(alert).toHaveFocus();
    expect(getAuthenticationSession).toHaveBeenCalledOnce();

    fireEvent.click(screen.getByRole('button', { name: 'Check session again' }));
    expect(await screen.findByRole('heading', { name: 'Sign in to CareOS' })).toBeInTheDocument();
    expect(getAuthenticationSession).toHaveBeenCalledTimes(2);
  });

  it('fails closed when an authenticated response omits its server expiry deadline', async () => {
    render(
      <App
        client={sessionClient({
          getAuthenticationSession: async () => ({
            correlationId,
            data: authenticatedSession,
            ok: true,
            status: 200,
          }),
        })}
      />,
    );

    expect(
      await screen.findByRole('heading', { name: 'CareOS access is unavailable' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('server expiry deadline');
    expect(screen.queryByText('North Clinic')).not.toBeInTheDocument();
  });

  it('locks an expired workspace locally without polling the server', async () => {
    const getAuthenticationSession = vi.fn(async () => ({
      ...success(authenticatedSession),
      sessionExpiresAt: Date.now() + 500,
    }));
    render(<App client={sessionClient({ getAuthenticationSession })} />);

    expect(
      await screen.findByRole('heading', { name: 'Administration dashboard' }),
    ).toBeInTheDocument();
    expect(getAuthenticationSession).toHaveBeenCalledOnce();

    expect(
      await screen.findByRole('heading', { name: 'Sign in to CareOS' }, { timeout: 1_500 }),
    ).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('Session ended');
    expect(getAuthenticationSession).toHaveBeenCalledOnce();
  });

  it('revalidates an authenticated session when the browser regains focus', async () => {
    const getAuthenticationSession = vi
      .fn<SessionClient['getAuthenticationSession']>()
      .mockResolvedValueOnce(success(authenticatedSession))
      .mockResolvedValueOnce(success(anonymousSession));
    render(<App client={sessionClient({ getAuthenticationSession })} />);
    await screen.findByRole('heading', { name: 'Administration dashboard' });

    fireEvent.focus(window);

    expect(await screen.findByRole('heading', { name: 'Sign in to CareOS' })).toBeInTheDocument();
    expect(getAuthenticationSession).toHaveBeenCalledTimes(2);
  });

  it('invalidates local workspace state only after logout succeeds', async () => {
    const logout = vi.fn(async () => success(undefined, 204));
    render(<App client={sessionClient({ logout })} />);
    await screen.findByRole('heading', { name: 'Administration dashboard' });

    fireEvent.click(screen.getByRole('button', { name: 'Sign out' }));

    await waitFor(() => expect(logout).toHaveBeenCalledOnce());
    expect(await screen.findByRole('heading', { name: 'Sign in to CareOS' })).toBeInTheDocument();
  });

  it('retains the authenticated workspace when logout is not acknowledged', async () => {
    const logout = vi.fn(async () => failure());
    render(<App client={sessionClient({ logout })} />);
    await screen.findByRole('heading', { name: 'Administration dashboard' });

    fireEvent.click(screen.getByRole('button', { name: 'Sign out' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Session unavailable');
    expect(screen.getByRole('heading', { name: 'Administration dashboard' })).toBeInTheDocument();
    expect(logout).toHaveBeenCalledOnce();
  });

  it('issues and revokes a governed organization invitation with caller-owned retry keys', async () => {
    window.location.hash = '#/M1-02';
    const issueInvitation = vi.fn<SessionClient['issueInvitation']>(async () =>
      success(
        {
          expiresAt: '2026-09-16T12:00:00Z',
          invitationId: '77777777-7777-4777-8777-777777777777',
          roleKey: 'organization_viewer',
          status: 'pending',
        },
        201,
      ),
    );
    const revokeInvitation = vi.fn<SessionClient['revokeInvitation']>(async () =>
      success({
        expiresAt: '2026-09-16T12:00:00Z',
        invitationId: '77777777-7777-4777-8777-777777777777',
        roleKey: 'organization_viewer',
        status: 'revoked',
      }),
    );
    render(<App client={sessionClient({ issueInvitation, revokeInvitation })} />);

    expect(await screen.findByRole('heading', { name: 'Organization invitations' })).toBeVisible();
    fireEvent.change(screen.getByLabelText('Email address'), {
      target: { value: 'new.user@example.test' },
    });
    fireEvent.change(screen.getByLabelText('Display name'), {
      target: { value: 'New User' },
    });
    fireEvent.change(screen.getByLabelText('Access reason'), {
      target: { value: 'Approved onboarding request CARE-42' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Invite administrator' }));

    await waitFor(() => expect(issueInvitation).toHaveBeenCalledOnce());
    expect(issueInvitation.mock.calls[0]?.[0]).toBe(selectedOrganization.id);
    expect(issueInvitation.mock.calls[0]?.[1]).toEqual({
      displayName: 'New User',
      email: 'new.user@example.test',
      reason: 'Approved onboarding request CARE-42',
      roleKey: 'organization_viewer',
    });
    expect(issueInvitation.mock.calls[0]?.[2]).toMatch(/^invite:[0-9a-f-]{36}$/);
    expect(await screen.findByText(/one-time link was sent/)).toBeVisible();

    fireEvent.change(screen.getByLabelText('Revocation reason'), {
      target: { value: 'Onboarding request withdrawn' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Revoke invitation' }));

    await waitFor(() => expect(revokeInvitation).toHaveBeenCalledOnce());
    expect(revokeInvitation.mock.calls[0]?.[0]).toBe(selectedOrganization.id);
    expect(revokeInvitation.mock.calls[0]?.[1]).toBe('77777777-7777-4777-8777-777777777777');
    expect(revokeInvitation.mock.calls[0]?.[2]).toEqual({
      reason: 'Onboarding request withdrawn',
    });
    expect(revokeInvitation.mock.calls[0]?.[3]).toMatch(/^revoke:[0-9a-f-]{36}$/);
    expect(await screen.findByText(/can no longer be accepted/)).toBeVisible();
  });

  it('scrubs and consumes an anonymous one-time invitation for a new account', async () => {
    const token = 'Case_Sensitive-Invitation-Token-1234567890';
    window.location.hash = `#/accept-invitation?token=${encodeURIComponent(token)}`;
    const acceptInvitation = vi.fn<SessionClient['acceptInvitation']>(async () =>
      success(
        {
          accountLink: 'created',
          invitationId: '77777777-7777-4777-8777-777777777777',
          organizationId: selectedOrganization.id,
          roleKey: 'organization_viewer',
          userId: '88888888-8888-4888-8888-888888888888',
        },
        201,
      ),
    );
    render(
      <App
        client={sessionClient({
          acceptInvitation,
          getAuthenticationSession: async () => success(anonymousSession),
        })}
      />,
    );

    expect(
      await screen.findByRole('heading', { name: 'Accept your CareOS invitation' }),
    ).toBeVisible();
    await waitFor(() => expect(window.location.hash).toBe('#/accept-invitation'));
    fireEvent.change(screen.getByLabelText('New password'), {
      target: { value: 'new-secure-password-27' },
    });
    fireEvent.change(screen.getByLabelText('Confirm new password'), {
      target: { value: 'new-secure-password-27' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Accept invitation' }));

    await waitFor(() =>
      expect(acceptInvitation).toHaveBeenCalledWith({
        newPassword: 'new-secure-password-27',
        token,
      }),
    );
    expect(await screen.findByRole('heading', { name: 'Invitation accepted' })).toBeVisible();
    expect(screen.queryByLabelText('New password')).not.toBeInTheDocument();
  });

  it('requests password recovery without disclosing whether an account exists', async () => {
    window.location.hash = '#/forgot-password';
    const requestPasswordReset = vi.fn(async () => success(undefined, 202));
    render(
      <App
        client={sessionClient({
          getAuthenticationSession: async () => success(anonymousSession),
          requestPasswordReset,
        })}
      />,
    );

    expect(await screen.findByRole('heading', { name: 'Reset your password' })).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Email address'), {
      target: { value: 'person@example.test' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Request reset link' }));

    await waitFor(() =>
      expect(requestPasswordReset).toHaveBeenCalledWith({ email: 'person@example.test' }),
    );
    expect(
      await screen.findByText(/If an active account matches that address/),
    ).toBeInTheDocument();
    expect(screen.getByText(/same whether or not an account exists/)).toBeInTheDocument();
  });

  it('scrubs and consumes a case-sensitive reset token without persisting password fields', async () => {
    const token = 'AbC_def-123.XyZ';
    window.location.hash = `#/reset-password?token=${encodeURIComponent(token)}`;
    const completePasswordReset = vi.fn(async () => success(undefined, 204));
    render(<App client={sessionClient({ completePasswordReset })} />);

    expect(
      await screen.findByRole('heading', { name: 'Choose a new password' }),
    ).toBeInTheDocument();
    await waitFor(() => expect(window.location.hash).toBe('#/reset-password'));

    fireEvent.change(screen.getByLabelText('New password'), {
      target: { value: 'new-secure-password-27' },
    });
    fireEvent.change(screen.getByLabelText('Confirm new password'), {
      target: { value: 'new-secure-password-27' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Change password' }));

    await waitFor(() =>
      expect(completePasswordReset).toHaveBeenCalledWith({
        newPassword: 'new-secure-password-27',
        token,
      }),
    );
    expect(
      await screen.findByRole('heading', { name: 'Password reset complete' }),
    ).toBeInTheDocument();
    expect(screen.queryByLabelText('New password')).not.toBeInTheDocument();
    expect(screen.getByText(/all existing sessions were revoked/)).toBeInTheDocument();
  });

  it('does not submit a missing password-reset token or mismatched passwords', async () => {
    window.location.hash = '#/reset-password';
    const completePasswordReset = vi.fn(async () => success(undefined, 204));
    const view = render(<App client={sessionClient({ completePasswordReset })} />);

    const missingTokenAlert = await screen.findByRole('alert');
    expect(missingTokenAlert).toHaveTextContent('missing its one-time token');
    expect(missingTokenAlert).toHaveFocus();
    expect(completePasswordReset).not.toHaveBeenCalled();

    view.unmount();
    window.location.hash = '#/reset-password?token=usable-token';
    render(<App client={sessionClient({ completePasswordReset })} />);
    await screen.findByRole('heading', { name: 'Choose a new password' });
    fireEvent.change(screen.getByLabelText('New password'), {
      target: { value: 'new-secure-password-27' },
    });
    fireEvent.change(screen.getByLabelText('Confirm new password'), {
      target: { value: 'different-password-28' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Change password' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('does not match');
    expect(completePasswordReset).not.toHaveBeenCalled();
  });

  it('focuses and associates identity validation without changing scrubbed routes', async () => {
    const completePasswordReset = vi.fn(async () => success(undefined, 204));
    window.location.hash = '#/reset-password?token=usable-token';
    const resetView = render(<App client={sessionClient({ completePasswordReset })} />);

    await screen.findByRole('heading', { name: 'Choose a new password' });
    await waitFor(() => expect(window.location.hash).toBe('#/reset-password'));
    fireEvent.change(screen.getByLabelText('New password'), {
      target: { value: 'new-secure-password-27' },
    });
    const resetConfirmation = screen.getByLabelText('Confirm new password');
    fireEvent.change(resetConfirmation, { target: { value: 'different-password-28' } });
    fireEvent.click(screen.getByRole('button', { name: 'Change password' }));

    const resetAlert = await screen.findByRole('alert');
    expect(resetAlert).toHaveFocus();
    expect(resetConfirmation).toHaveAttribute('aria-invalid', 'true');
    expect(resetConfirmation).toHaveAttribute('aria-describedby', 'confirm-password-error');
    const resetHash = window.location.hash;
    const resetFieldLink = within(resetAlert).getByRole('link', { name: /does not match/ });
    expect(resetFieldLink).toHaveAttribute('href', '#confirm-password');
    fireEvent.click(resetFieldLink);
    expect(resetConfirmation).toHaveFocus();
    expect(window.location.hash).toBe(resetHash);
    expect(completePasswordReset).not.toHaveBeenCalled();

    resetView.unmount();

    const token = 'Case_Sensitive-Invitation-Token-1234567890';
    window.location.hash = `#/accept-invitation?token=${token}`;
    const acceptInvitation = vi.fn<SessionClient['acceptInvitation']>(async () =>
      success(
        {
          accountLink: 'created',
          invitationId: '77777777-7777-4777-8777-777777777777',
          organizationId: selectedOrganization.id,
          roleKey: 'organization_viewer',
          userId: '88888888-8888-4888-8888-888888888888',
        },
        201,
      ),
    );
    render(
      <App
        client={sessionClient({
          acceptInvitation,
          getAuthenticationSession: async () => success(anonymousSession),
        })}
      />,
    );

    await screen.findByRole('heading', { name: 'Accept your CareOS invitation' });
    await waitFor(() => expect(window.location.hash).toBe('#/accept-invitation'));
    fireEvent.change(screen.getByLabelText('New password'), {
      target: { value: 'new-secure-password-27' },
    });
    const invitationConfirmation = screen.getByLabelText('Confirm new password');
    fireEvent.change(invitationConfirmation, {
      target: { value: 'different-password-28' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Accept invitation' }));

    const invitationAlert = await screen.findByRole('alert');
    expect(invitationAlert).toHaveFocus();
    expect(invitationConfirmation).toHaveAttribute('aria-invalid', 'true');
    expect(invitationConfirmation).toHaveAttribute(
      'aria-describedby',
      'invitation-password-confirmation-error',
    );
    const invitationHash = window.location.hash;
    fireEvent.click(within(invitationAlert).getByRole('link', { name: /does not match/ }));
    expect(invitationConfirmation).toHaveFocus();
    expect(window.location.hash).toBe(invitationHash);
    expect(acceptInvitation).not.toHaveBeenCalled();
  });

  it('enrolls MFA and reveals validated recovery codes only once in memory', async () => {
    window.location.hash = '#/M1-03';
    const startMfaEnrollment = vi.fn(async () =>
      success({
        provisioningUri:
          'otpauth://totp/ROOTOPATHY%20CareOS:asha@example.test?secret=ABCDEFGHIJKLMNOP',
        secret: 'ABCDEFGHIJKLMNOP',
      }),
    );
    const verifyMfaEnrollment = vi.fn(async () =>
      success({ recoveryCodes: ['2345-6789-ABCD', 'EFGH-JKLM-NPQR'] }),
    );
    render(<App client={sessionClient({ startMfaEnrollment, verifyMfaEnrollment })} />);

    expect(
      await screen.findByRole('heading', { name: 'Multi-factor authentication' }),
    ).toBeInTheDocument();
    expect(screen.getByText('Authenticator not enabled')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Set up authenticator' }));

    expect(await screen.findByLabelText('One-time authenticator setup key')).toHaveTextContent(
      'ABCDEFGHIJKLMNOP',
    );
    expect(startMfaEnrollment).toHaveBeenCalledWith({ label: 'Asha Verma authenticator' });
    fireEvent.change(screen.getByLabelText('Six-digit authenticator code'), {
      target: { value: '654321' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Confirm and enable MFA' }));

    await waitFor(() => expect(verifyMfaEnrollment).toHaveBeenCalledWith({ code: '654321' }));
    expect(await screen.findByText('2345-6789-ABCD')).toBeInTheDocument();
    expect(screen.getByText('EFGH-JKLM-NPQR')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'I have stored these codes securely' }));
    expect(await screen.findByText('Authenticator enabled')).toBeInTheDocument();
    expect(screen.queryByText('2345-6789-ABCD')).not.toBeInTheDocument();
    expect(screen.queryByText('ABCDEFGHIJKLMNOP')).not.toBeInTheDocument();
  });

  it('drives the three-step administrator MFA reset workflow with caller-owned retry keys', async () => {
    window.location.hash = '#/M1-03';
    const targetUserId = '88888888-8888-4888-8888-888888888888';
    const approvalId = '99999999-9999-4999-8999-999999999999';
    const requestMfaAdministrativeReset = vi.fn<SessionClient['requestMfaAdministrativeReset']>(
      async () =>
        success(
          {
            approvalId,
            expiresAt: '2026-09-16T12:00:00Z',
            status: 'pending',
            targetUserId,
          },
          201,
        ),
    );
    const approveMfaAdministrativeReset = vi.fn<SessionClient['approveMfaAdministrativeReset']>(
      async () =>
        success({
          approvalId,
          expiresAt: '2026-09-16T12:00:00Z',
          status: 'approved',
          targetUserId,
        }),
    );
    const executeMfaAdministrativeReset = vi.fn<SessionClient['executeMfaAdministrativeReset']>(
      async () =>
        success({
          approvalId,
          expiresAt: '2026-09-16T12:00:00Z',
          status: 'reset',
          targetUserId,
        }),
    );
    render(
      <App
        client={sessionClient({
          approveMfaAdministrativeReset,
          executeMfaAdministrativeReset,
          requestMfaAdministrativeReset,
        })}
      />,
    );

    expect(await screen.findByRole('heading', { name: 'Administrator MFA reset' })).toBeVisible();
    fireEvent.change(screen.getByLabelText('Target user ID'), {
      target: { value: targetUserId },
    });
    fireEvent.change(screen.getByLabelText('Reset reason'), {
      target: { value: 'Verified lost authenticator on support case CARE-42' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Request independent approval' }));

    await waitFor(() => expect(requestMfaAdministrativeReset).toHaveBeenCalledOnce());
    expect(requestMfaAdministrativeReset.mock.calls[0]).toEqual([
      selectedOrganization.id,
      targetUserId,
      { reason: 'Verified lost authenticator on support case CARE-42' },
      expect.stringMatching(/^mfa-request:[0-9a-f-]{36}$/),
    ]);
    expect(await screen.findByText(/Workflow status:/)).toHaveTextContent('pending');

    fireEvent.change(screen.getByLabelText('Workflow action'), { target: { value: 'approve' } });
    fireEvent.change(screen.getByLabelText('Independent decision reason'), {
      target: { value: 'Identity and support case independently verified' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Approve as independent checker' }));

    await waitFor(() => expect(approveMfaAdministrativeReset).toHaveBeenCalledOnce());
    expect(approveMfaAdministrativeReset.mock.calls[0]).toEqual([
      selectedOrganization.id,
      targetUserId,
      approvalId,
      { reason: 'Identity and support case independently verified' },
      expect.stringMatching(/^mfa-approve:[0-9a-f-]{36}$/),
    ]);

    fireEvent.change(screen.getByLabelText('Workflow action'), { target: { value: 'execute' } });
    fireEvent.change(screen.getByLabelText('Reset reason'), {
      target: { value: 'Verified lost authenticator on support case CARE-42' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Execute approved reset' }));

    await waitFor(() => expect(executeMfaAdministrativeReset).toHaveBeenCalledOnce());
    expect(executeMfaAdministrativeReset.mock.calls[0]).toEqual([
      selectedOrganization.id,
      targetUserId,
      approvalId,
      { reason: 'Verified lost authenticator on support case CARE-42' },
      expect.stringMatching(/^mfa-execute:[0-9a-f-]{36}$/),
    ]);
    expect(await screen.findByText(/Workflow status:/)).toHaveTextContent('reset');
  });

  it('requires recent authentication before replacing one-use recovery codes', async () => {
    window.location.hash = '#/M1-03';
    const verifyRecentAuthentication = vi.fn(async () => success(undefined, 204));
    const regenerateRecoveryCodes = vi.fn(async () =>
      success({ recoveryCodes: ['2345-6789-ABCD', 'EFGH-JKLM-NPQR'] }),
    );
    render(
      <App
        client={sessionClient({
          getAuthenticationSession: async () =>
            success({ ...authenticatedSession, mfaEnabled: true, recentAuthentication: false }),
          regenerateRecoveryCodes,
          verifyRecentAuthentication,
        })}
      />,
    );

    expect(
      await screen.findByRole('heading', { name: 'Verify this protected action' }),
    ).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Current password'), {
      target: { value: 'current-password' },
    });
    fireEvent.change(screen.getByLabelText(/Authenticator or recovery code/), {
      target: { value: '234567' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Verify identity' }));

    await waitFor(() =>
      expect(verifyRecentAuthentication).toHaveBeenCalledWith({
        password: 'current-password',
        secondFactor: '234567',
      }),
    );
    expect(await screen.findByText('Authenticator enabled')).toBeInTheDocument();
    fireEvent.click(screen.getByLabelText(/previous recovery codes will stop working/));
    fireEvent.click(screen.getByRole('button', { name: 'Replace recovery codes' }));

    await waitFor(() => expect(regenerateRecoveryCodes).toHaveBeenCalledOnce());
    expect(await screen.findByText(/previous recovery codes are revoked/i)).toBeInTheDocument();
    expect(screen.getByText('2345-6789-ABCD')).toBeInTheDocument();
  });
});
