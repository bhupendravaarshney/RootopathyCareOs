import { AlertTriangle, Check, CircleAlert, Minus, RefreshCw, ShieldCheck } from 'lucide-react';
import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import type { ApiFailure } from '../../api/client';
import type {
  AdministrationReadiness,
  AuditEvidenceDetail,
  AuditEvidenceItem,
  AuditEvidencePage,
  ConfigurationActivation,
  ConfigurationActivationDirectory,
  ConfigurationHistoryItem,
  ConfigurationHistoryPage,
  EvidenceExportDirectory,
  EvidenceExportJob,
  EvidenceExportRequest,
  MembershipChangeMutation,
  OrganizationAddress,
  OrganizationAddressWriteRequest,
  OrganizationContact,
  OrganizationContactCollection,
  OrganizationContactWriteRequest,
  OrganizationIdentifier,
  OrganizationIdentifierCollection,
  OrganizationIdentifierWriteRequest,
  OrganizationInternationalSettings,
  InternationalSettingsScheduleRequest,
  OrganizationGovernanceDirectory,
  GovernanceResponsibilityWriteRequest,
  FacilityCreateRequest,
  FacilityDirectory,
  IdentifierScheme,
  IdentifierSchemeDirectory,
  OperatingHoursOverview,
  OrganizationUnitCreateRequest,
  OrganizationUnitDirectory,
  ServiceLocationCreateRequest,
  ServiceLocationDirectory,
  ServiceAssignment,
  ServiceAssignmentDirectory,
  ServiceCatalogue,
  ServiceDefinition,
  OrganizationMembershipPage,
  OrganizationMembershipSummary,
  OrganizationProfile,
  OrganizationProfileUpdateRequest,
  ReadinessGate,
} from '../../api/generated';
import { Shell, type ShellSessionProps } from '../../components/Shell';
import { findScreen } from '../../data/screens';
import type { AdministrationClient } from './administration-types';

type AdministrationScreenProps = {
  client: AdministrationClient;
  id:
    | 'M1-05'
    | 'M1-06'
    | 'M1-07'
    | 'M1-08'
    | 'M1-09'
    | 'M1-10'
    | 'M1-11'
    | 'M1-12'
    | 'M1-13'
    | 'M1-14'
    | 'M1-15'
    | 'M1-16'
    | 'M1-17'
    | 'M1-18'
    | 'M1-19'
    | 'M1-20'
    | 'M1-21'
    | 'M1-22'
    | 'M1-23';
  organizationId: string;
  shell: ShellSessionProps;
};

const applicationStartedAt = Date.now();
const exportPollDelays = [1_000, 2_000, 4_000, 8_000, 15_000, 15_000, 15_000, 15_000] as const;
const activeExportStatuses = new Set<EvidenceExportJob['status']>([
  'requested',
  'authorized',
  'running',
]);

type ReadinessScreenProps = Pick<AdministrationScreenProps, 'client' | 'organizationId'> & {
  id: 'M1-05' | 'M1-06';
};

type LoadState<T> =
  { phase: 'loading' } | { issue: string; phase: 'failure' } | { data: T; phase: 'ready' };

type ProfileState =
  | { phase: 'loading' }
  | { issue: string; phase: 'failure' }
  | { etag: string; phase: 'ready'; profile: OrganizationProfile };

type IdentifierState =
  | { phase: 'loading' }
  | { issue: string; phase: 'failure' }
  | { collection: OrganizationIdentifierCollection; phase: 'ready' };

type IdentifierDraftForm = {
  assigningAuthority: string;
  effectiveFrom: string;
  effectiveTo: string;
  expiryDate: string;
  identifierType: string;
  isPrimary: boolean;
  issueDate: string;
  jurisdictionCountryCode: string;
  reason: string;
  value: string;
};

type IdentifierAction =
  | { form: IdentifierDraftForm; kind: 'create' }
  | { form: IdentifierDraftForm; identifier: OrganizationIdentifier; kind: 'edit' }
  | {
      evidenceReference: string;
      identifier: OrganizationIdentifier;
      kind: 'verify';
      reason: string;
    }
  | { identifier: OrganizationIdentifier; kind: 'revoke'; reason: string }
  | {
      identifier: OrganizationIdentifier;
      kind: 'supersede';
      reason: string;
      replacementId: string;
    };

type ContactDirectoryState =
  | { phase: 'loading' }
  | { issue: string; phase: 'failure' }
  | { collection: OrganizationContactCollection; phase: 'ready' };

type AddressDraftForm = {
  addressLines: [string, string, string, string];
  addressType: OrganizationAddressWriteRequest['addressType'];
  countryCode: string;
  effectiveFrom: string;
  effectiveTo: string;
  isPrimary: boolean;
  locality: string;
  postcode: string;
  reason: string;
  region: string;
  validationSource: string;
  validationStatus: OrganizationAddressWriteRequest['validationStatus'];
};

type ContactDraftForm = {
  channel: OrganizationContactWriteRequest['channel'];
  effectiveFrom: string;
  effectiveTo: string;
  isPreferred: boolean;
  isPrimary: boolean;
  purpose: string;
  reason: string;
  value: string;
};

type ContactDirectoryAction =
  | { form: AddressDraftForm; kind: 'create-address' }
  | { address: OrganizationAddress; form: AddressDraftForm; kind: 'supersede-address' }
  | { address: OrganizationAddress; kind: 'end-address'; reason: string }
  | { form: ContactDraftForm; kind: 'create-contact' }
  | { contact: OrganizationContact; form: ContactDraftForm; kind: 'supersede-contact' }
  | { contact: OrganizationContact; kind: 'verify-contact'; reason: string }
  | { contact: OrganizationContact; kind: 'end-contact'; reason: string };

type OrganizationType = NonNullable<OrganizationProfile['organizationType']>;
type ProfileForm = Omit<OrganizationProfileUpdateRequest, 'organizationType'> & {
  organizationType: OrganizationType | '';
};

const lifecycleStatuses = new Set(['draft', 'under_review', 'active', 'suspended', 'closed']);
const organizationTypes = [
  ['care_provider', 'Care provider'],
  ['care_network', 'Care network'],
  ['administrative', 'Administrative organization'],
] as const;
const organizationTypeKeys = new Set<string>(organizationTypes.map(([key]) => key));
const localePattern = /^[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*$/;
const registryKeyPattern = /^[a-z][a-z0-9]*([._:-][a-z0-9]+)*$/;
const identifierStatuses = new Set([
  'draft',
  'verified',
  'active',
  'expired',
  'revoked',
  'superseded',
]);
const identifierVerificationStatuses = new Set(['unverified', 'verified']);
const identifierActions = new Set(['edit', 'verify', 'revoke', 'supersede']);
const addressStatuses = new Set(['scheduled', 'active', 'ended', 'superseded']);
const addressActions = new Set(['supersede', 'end']);
const addressTypes = ['registered', 'postal', 'service', 'billing'] as const;
const contactChannels = new Set(['email', 'phone', 'web']);
const contactVerificationStatuses = new Set(['unverified', 'verified']);
const contactActions = new Set(['verify', 'supersede', 'end']);
const gateOutcomes = new Set(['complete', 'warning', 'blocked', 'not_applicable']);
const readinessGateKeys: ReadinessGate['key'][] = [
  'organization.profile.complete',
  'organization.identifier.primary_verified',
  'organization.contact.coverage',
  'organization.governance.coverage',
  'access.final_owner',
  'access.mfa_enforced',
  'network.facility.minimum',
  'network.hierarchy.valid',
  'network.hours.valid',
  'service.catalogue.active',
  'service.assignment.valid',
  'identifier.scheme.active',
  'configuration.integrity',
  'governance.registry.active',
  'platform.dependencies.ready',
];
const readinessGateLinks: Record<ReadinessGate['key'], readonly string[]> = {
  'access.final_owner': ['#/M1-20', '#/M1-06'],
  'access.mfa_enforced': ['#/M1-03'],
  'configuration.integrity': ['#/M1-21', '#/M1-06'],
  'governance.registry.active': ['#/M1-21', '#/M1-06'],
  'identifier.scheme.active': ['#/M1-19', '#/M1-06'],
  'network.facility.minimum': ['#/M1-12', '#/M1-06'],
  'network.hierarchy.valid': ['#/M1-14', '#/M1-06'],
  'network.hours.valid': ['#/M1-16', '#/M1-06'],
  'organization.contact.coverage': ['#/M1-09', '#/M1-06'],
  'organization.governance.coverage': ['#/M1-11', '#/M1-06'],
  'organization.identifier.primary_verified': ['#/M1-08', '#/M1-06'],
  'organization.profile.complete': ['#/M1-07', '#/M1-06'],
  'platform.dependencies.ready': ['#/M1-21', '#/M1-06'],
  'service.assignment.valid': ['#/M1-18', '#/M1-06'],
  'service.catalogue.active': ['#/M1-17', '#/M1-06'],
};
const membershipAccessStates = new Set(['active', 'scheduled', 'suspended', 'expired', 'revoked']);
const accountStatuses = new Set(['invited', 'active', 'suspended', 'disabled']);
const roleStatuses = new Set(['active', 'reference', 'retired']);
const membershipRowActions = new Set([
  'requestMfaReset',
  'requestRoleChange',
  'requestRevocation',
  'requestOwnerTransfer',
]);
const membershipPageActions = new Set([
  'issueInvitation',
  'approveMembershipChange',
  'executeMembershipChange',
  'approveOwnerTransfer',
  'executeOwnerTransfer',
]);
const compactMembershipQuery = '(max-width: 760px)';
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const cursorPattern = /^[A-Za-z0-9_-]{1,512}$/;
const membershipRoles = [
  ['organization_owner', 'Organization owner'],
  ['organization_administrator', 'Organization administrator'],
  ['configuration_editor', 'Configuration editor'],
  ['configuration_approver', 'Configuration approver'],
  ['security_administrator', 'Security administrator'],
  ['auditor', 'Auditor'],
  ['export_approver', 'Export approver'],
  ['organization_viewer', 'Organization viewer'],
] as const;

type MembershipFilters = {
  roleKey: string;
  search: string;
  state: '' | OrganizationMembershipSummary['accessState'];
};

type MembershipWorkflowAction =
  | 'request_role_change'
  | 'request_revoke'
  | 'request_owner_transfer'
  | 'approve'
  | 'execute'
  | 'approve_owner_transfer'
  | 'execute_owner_transfer';

function nonNegativeInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
}

function validGate(value: unknown, index: number): value is ReadinessGate {
  if (!value || typeof value !== 'object') return false;
  const expectedKey = readinessGateKeys[index];
  if (!expectedKey) return false;
  const gate = value as Partial<ReadinessGate>;
  return (
    gate.key === expectedKey &&
    gate.version === 'm1-readiness-v1' &&
    typeof gate.label === 'string' &&
    gate.label.length > 0 &&
    typeof gate.outcome === 'string' &&
    gateOutcomes.has(gate.outcome) &&
    typeof gate.reasonCode === 'string' &&
    /^[a-z][a-z0-9]*(\.[a-z0-9_]+)+$/.test(gate.reasonCode) &&
    typeof gate.remediationCode === 'string' &&
    /^[a-z][a-z0-9]*(\.[a-z0-9_]+)+$/.test(gate.remediationCode) &&
    typeof gate.detail === 'string' &&
    gate.detail.length > 0 &&
    Array.isArray(gate.evidenceReferences) &&
    gate.evidenceReferences.length <= 4 &&
    gate.evidenceReferences.every(
      (reference) =>
        typeof reference === 'string' && reference.length > 0 && reference.length <= 160,
    ) &&
    new Set(gate.evidenceReferences).size === gate.evidenceReferences.length &&
    typeof gate.href === 'string' &&
    readinessGateLinks[expectedKey].includes(gate.href)
  );
}

function instant(value: unknown) {
  if (typeof value !== 'string') return null;
  const timestamp = Date.parse(value);
  return Number.isFinite(timestamp) ? timestamp : null;
}

function validReadiness(
  value: AdministrationReadiness,
  organizationId: string,
): value is AdministrationReadiness {
  const evaluatedAt = instant(value.evaluatedAt);
  const expiresAt = instant(value.expiresAt);
  return (
    value.organizationId === organizationId &&
    lifecycleStatuses.has(value.lifecycleStatus) &&
    value.catalogueVersion === 'm1-readiness-v1' &&
    nonNegativeInteger(value.organizationRevision) &&
    evaluatedAt !== null &&
    expiresAt !== null &&
    expiresAt - evaluatedAt === 15 * 60 * 1000 &&
    nonNegativeInteger(value.completedGates) &&
    nonNegativeInteger(value.blockedGates) &&
    nonNegativeInteger(value.warningGates) &&
    nonNegativeInteger(value.notApplicableGates) &&
    nonNegativeInteger(value.totalGates) &&
    nonNegativeInteger(value.activeMemberships) &&
    nonNegativeInteger(value.facilityCount) &&
    nonNegativeInteger(value.draftFacilityCount) &&
    Array.isArray(value.gates) &&
    value.gates.length === readinessGateKeys.length &&
    value.gates.every(validGate) &&
    value.totalGates === value.gates.length &&
    value.completedGates === value.gates.filter((gate) => gate.outcome === 'complete').length &&
    value.blockedGates === value.gates.filter((gate) => gate.outcome === 'blocked').length &&
    value.warningGates === value.gates.filter((gate) => gate.outcome === 'warning').length &&
    value.notApplicableGates ===
      value.gates.filter((gate) => gate.outcome === 'not_applicable').length &&
    value.completedGates + value.blockedGates + value.warningGates + value.notApplicableGates ===
      value.totalGates &&
    value.draftFacilityCount <= value.facilityCount
  );
}

function validProfile(
  value: OrganizationProfile,
  organizationId: string,
): value is OrganizationProfile {
  return (
    value.organizationId === organizationId &&
    validProfileName(value.legalName, 2, 200) &&
    validProfileName(value.displayName, 2, 120) &&
    (value.tradingName === null || validProfileName(value.tradingName, 2, 160)) &&
    (value.organizationType === null || organizationTypeKeys.has(value.organizationType)) &&
    typeof value.countryCode === 'string' &&
    /^[A-Z]{2}$/.test(value.countryCode) &&
    typeof value.timezone === 'string' &&
    value.timezone.length > 0 &&
    value.timezone.length <= 80 &&
    value.timezone === value.timezone.trim() &&
    (value.locale === null ||
      (value.locale.length >= 2 &&
        value.locale.length <= 255 &&
        localePattern.test(value.locale))) &&
    lifecycleStatuses.has(value.lifecycleStatus) &&
    typeof value.editable === 'boolean' &&
    nonNegativeInteger(value.lockVersion) &&
    typeof value.updatedAt === 'string' &&
    Number.isFinite(Date.parse(value.updatedAt))
  );
}

function validProfileName(value: unknown, minimum: number, maximum: number): value is string {
  if (
    typeof value !== 'string' ||
    value !== value.trim() ||
    value.includes('<') ||
    value.includes('>') ||
    Array.from(value).some((character) => {
      const codePoint = character.codePointAt(0) ?? 0;
      return codePoint < 32 || codePoint === 127;
    })
  ) {
    return false;
  }
  const length = Array.from(value).length;
  return length >= minimum && length <= maximum;
}

function validOptionalDate(value: unknown): value is string | null {
  return (
    value === null ||
    (typeof value === 'string' &&
      /^\d{4}-\d{2}-\d{2}$/.test(value) &&
      Number.isFinite(Date.parse(`${value}T00:00:00Z`)))
  );
}

function validIdentifierText(value: unknown, minimum: number, maximum: number): value is string {
  return validProfileName(value, minimum, maximum);
}

function validIdentifier(
  value: unknown,
  typeKeys: ReadonlySet<string>,
): value is OrganizationIdentifier {
  if (!value || typeof value !== 'object') return false;
  const identifier = value as Partial<OrganizationIdentifier>;
  const effectiveFrom = instant(identifier.effectiveFrom);
  const effectiveTo = identifier.effectiveTo === null ? null : instant(identifier.effectiveTo);
  const actions = Array.isArray(identifier.availableActions) ? identifier.availableActions : [];
  const actionsMatchLifecycle =
    (identifier.status === 'draft' &&
      actions.every((action) => action === 'edit' || action === 'verify')) ||
    ((identifier.status === 'verified' || identifier.status === 'active') &&
      actions.every((action) => action === 'revoke' || action === 'supersede')) ||
    ((identifier.status === 'expired' ||
      identifier.status === 'revoked' ||
      identifier.status === 'superseded') &&
      actions.length === 0);
  return (
    typeof identifier.identifierId === 'string' &&
    uuidPattern.test(identifier.identifierId) &&
    typeof identifier.identifierType === 'string' &&
    typeKeys.has(identifier.identifierType) &&
    registryKeyPattern.test(identifier.identifierType) &&
    validIdentifierText(identifier.typeDisplayName, 2, 120) &&
    validIdentifierText(identifier.assigningAuthority, 2, 160) &&
    validIdentifierText(identifier.value, 1, 128) &&
    (identifier.jurisdictionCountryCode === null ||
      (typeof identifier.jurisdictionCountryCode === 'string' &&
        /^[A-Z]{2}$/.test(identifier.jurisdictionCountryCode))) &&
    typeof identifier.verificationStatus === 'string' &&
    identifierVerificationStatuses.has(identifier.verificationStatus) &&
    (identifier.evidenceReference === null ||
      validIdentifierText(identifier.evidenceReference, 1, 160)) &&
    typeof identifier.isPrimary === 'boolean' &&
    validOptionalDate(identifier.issueDate) &&
    validOptionalDate(identifier.expiryDate) &&
    (identifier.issueDate === null ||
      identifier.expiryDate === null ||
      identifier.expiryDate >= identifier.issueDate) &&
    effectiveFrom !== null &&
    (identifier.effectiveTo === null || (effectiveTo !== null && effectiveTo > effectiveFrom)) &&
    (identifier.supersedesId === null ||
      (typeof identifier.supersedesId === 'string' &&
        uuidPattern.test(identifier.supersedesId) &&
        identifier.supersedesId !== identifier.identifierId)) &&
    typeof identifier.status === 'string' &&
    identifierStatuses.has(identifier.status) &&
    (identifier.status !== 'draft' || identifier.supersedesId === null) &&
    Array.isArray(identifier.availableActions) &&
    new Set(identifier.availableActions).size === identifier.availableActions.length &&
    identifier.availableActions.every((action) => identifierActions.has(action)) &&
    actionsMatchLifecycle &&
    nonNegativeInteger(identifier.lockVersion) &&
    validDateTime(identifier.createdAt) &&
    validDateTime(identifier.updatedAt)
  );
}

function validIdentifierCollection(
  value: unknown,
  organizationId: string,
): value is OrganizationIdentifierCollection {
  if (!value || typeof value !== 'object') return false;
  const collection = value as Partial<OrganizationIdentifierCollection>;
  if (
    collection.organizationId !== organizationId ||
    typeof collection.canCreate !== 'boolean' ||
    !Array.isArray(collection.types) ||
    !Array.isArray(collection.items)
  ) {
    return false;
  }
  const validTypes = collection.types.every(
    (type) =>
      type &&
      typeof type === 'object' &&
      typeof type.key === 'string' &&
      type.key.length <= 80 &&
      registryKeyPattern.test(type.key) &&
      validIdentifierText(type.displayName, 2, 120) &&
      (type.jurisdictionCountryCode === null ||
        (typeof type.jurisdictionCountryCode === 'string' &&
          /^[A-Z]{2}$/.test(type.jurisdictionCountryCode))) &&
      typeof type.primaryRequired === 'boolean',
  );
  const typeKeys = new Set(collection.types.map((type) => type.key));
  return (
    validTypes &&
    typeKeys.size === collection.types.length &&
    (!collection.canCreate || collection.types.length > 0) &&
    collection.items.every((identifier) => validIdentifier(identifier, typeKeys)) &&
    new Set(collection.items.map((identifier) => identifier.identifierId)).size ===
      collection.items.length
  );
}

function exactObjectKeys(value: object, expected: readonly string[]) {
  const keys = Object.keys(value);
  return keys.length === expected.length && expected.every((key) => keys.includes(key));
}

const exactAddressFields = [
  'addressId',
  'addressType',
  'addressLines',
  'locality',
  'region',
  'postcode',
  'countryCode',
  'validationStatus',
  'validationSource',
  'isPrimary',
  'effectiveFrom',
  'effectiveTo',
  'supersedesId',
  'status',
  'availableActions',
  'lockVersion',
  'createdAt',
  'updatedAt',
] as const;

const exactContactFields = [
  'contactId',
  'channel',
  'purpose',
  'purposeDisplayName',
  'maskedValue',
  'verificationStatus',
  'isPrimary',
  'isPreferred',
  'effectiveFrom',
  'effectiveTo',
  'supersedesId',
  'status',
  'availableActions',
  'lockVersion',
  'createdAt',
  'updatedAt',
] as const;

function validAddress(value: unknown): value is OrganizationAddress {
  if (!value || typeof value !== 'object' || !exactObjectKeys(value, exactAddressFields)) {
    return false;
  }
  const address = value as Partial<OrganizationAddress>;
  const effectiveFrom = instant(address.effectiveFrom);
  const effectiveTo = address.effectiveTo === null ? null : instant(address.effectiveTo);
  const createdAt = instant(address.createdAt);
  const updatedAt = instant(address.updatedAt);
  const actions = Array.isArray(address.availableActions) ? address.availableActions : [];
  return (
    typeof address.addressId === 'string' &&
    uuidPattern.test(address.addressId) &&
    typeof address.addressType === 'string' &&
    (addressTypes as readonly string[]).includes(address.addressType) &&
    Array.isArray(address.addressLines) &&
    address.addressLines.length >= 1 &&
    address.addressLines.length <= 4 &&
    address.addressLines.every((line) => validIdentifierText(line, 1, 120)) &&
    validIdentifierText(address.locality, 1, 100) &&
    validIdentifierText(address.region, 1, 100) &&
    validIdentifierText(address.postcode, 1, 24) &&
    typeof address.countryCode === 'string' &&
    /^[A-Z]{2}$/.test(address.countryCode) &&
    (address.validationStatus === 'unvalidated' || address.validationStatus === 'validated') &&
    ((address.validationStatus === 'unvalidated' && address.validationSource === null) ||
      (address.validationStatus === 'validated' &&
        validIdentifierText(address.validationSource, 2, 160))) &&
    typeof address.isPrimary === 'boolean' &&
    effectiveFrom !== null &&
    (address.effectiveTo === null || (effectiveTo !== null && effectiveTo > effectiveFrom)) &&
    (address.supersedesId === null ||
      (typeof address.supersedesId === 'string' &&
        uuidPattern.test(address.supersedesId) &&
        address.supersedesId !== address.addressId)) &&
    typeof address.status === 'string' &&
    addressStatuses.has(address.status) &&
    Array.isArray(address.availableActions) &&
    new Set(address.availableActions).size === address.availableActions.length &&
    address.availableActions.every((action) => addressActions.has(action)) &&
    (!actions.includes('end') || address.status === 'active') &&
    (!actions.includes('supersede') ||
      address.status === 'active' ||
      address.status === 'scheduled') &&
    (address.status === 'ended' || address.status === 'superseded' ? actions.length === 0 : true) &&
    nonNegativeInteger(address.lockVersion) &&
    createdAt !== null &&
    updatedAt !== null &&
    updatedAt >= createdAt
  );
}

function validContact(
  value: unknown,
  purposeKeys: ReadonlySet<string>,
): value is OrganizationContact {
  if (!value || typeof value !== 'object' || !exactObjectKeys(value, exactContactFields)) {
    return false;
  }
  const contact = value as Partial<OrganizationContact>;
  const effectiveFrom = instant(contact.effectiveFrom);
  const effectiveTo = contact.effectiveTo === null ? null : instant(contact.effectiveTo);
  const createdAt = instant(contact.createdAt);
  const updatedAt = instant(contact.updatedAt);
  const actions = Array.isArray(contact.availableActions) ? contact.availableActions : [];
  return (
    typeof contact.contactId === 'string' &&
    uuidPattern.test(contact.contactId) &&
    typeof contact.channel === 'string' &&
    contactChannels.has(contact.channel) &&
    typeof contact.purpose === 'string' &&
    purposeKeys.has(contact.purpose) &&
    registryKeyPattern.test(contact.purpose) &&
    validIdentifierText(contact.purposeDisplayName, 2, 120) &&
    validIdentifierText(contact.maskedValue, 1, 2048) &&
    typeof contact.verificationStatus === 'string' &&
    contactVerificationStatuses.has(contact.verificationStatus) &&
    typeof contact.isPrimary === 'boolean' &&
    typeof contact.isPreferred === 'boolean' &&
    (!contact.isPreferred || contact.isPrimary) &&
    effectiveFrom !== null &&
    (contact.effectiveTo === null || (effectiveTo !== null && effectiveTo > effectiveFrom)) &&
    (contact.supersedesId === null ||
      (typeof contact.supersedesId === 'string' &&
        uuidPattern.test(contact.supersedesId) &&
        contact.supersedesId !== contact.contactId)) &&
    typeof contact.status === 'string' &&
    addressStatuses.has(contact.status) &&
    Array.isArray(contact.availableActions) &&
    new Set(contact.availableActions).size === contact.availableActions.length &&
    contact.availableActions.every((action) => contactActions.has(action)) &&
    (!actions.includes('verify') ||
      (contact.verificationStatus === 'unverified' &&
        (contact.status === 'scheduled' || contact.status === 'active'))) &&
    (!actions.includes('end') || contact.status === 'active') &&
    (!actions.includes('supersede') ||
      contact.status === 'active' ||
      contact.status === 'scheduled') &&
    (contact.status === 'ended' || contact.status === 'superseded' ? actions.length === 0 : true) &&
    nonNegativeInteger(contact.lockVersion) &&
    createdAt !== null &&
    updatedAt !== null &&
    updatedAt >= createdAt
  );
}

function validContactCollection(
  value: unknown,
  organizationId: string,
): value is OrganizationContactCollection {
  if (!value || typeof value !== 'object') return false;
  const collection = value as Partial<OrganizationContactCollection>;
  if (
    !exactObjectKeys(value, [
      'organizationId',
      'canCreate',
      'addressTypes',
      'purposes',
      'addresses',
      'contacts',
    ]) ||
    collection.organizationId !== organizationId ||
    typeof collection.canCreate !== 'boolean' ||
    !Array.isArray(collection.addressTypes) ||
    JSON.stringify(collection.addressTypes) !== JSON.stringify(addressTypes) ||
    !Array.isArray(collection.purposes) ||
    !Array.isArray(collection.addresses) ||
    !Array.isArray(collection.contacts)
  ) {
    return false;
  }
  const validPurposes = collection.purposes.every(
    (purpose) =>
      purpose &&
      typeof purpose === 'object' &&
      exactObjectKeys(purpose, ['key', 'displayName', 'publicProjectionAllowed']) &&
      typeof purpose.key === 'string' &&
      purpose.key.length <= 80 &&
      registryKeyPattern.test(purpose.key) &&
      validIdentifierText(purpose.displayName, 2, 120) &&
      typeof purpose.publicProjectionAllowed === 'boolean',
  );
  const purposeKeys = new Set(collection.purposes.map((purpose) => purpose.key));
  return (
    validPurposes &&
    purposeKeys.size === collection.purposes.length &&
    (!collection.canCreate || collection.purposes.length > 0) &&
    collection.addresses.every(validAddress) &&
    new Set(collection.addresses.map((address) => address.addressId)).size ===
      collection.addresses.length &&
    collection.contacts.every((contact) => validContact(contact, purposeKeys)) &&
    new Set(collection.contacts.map((contact) => contact.contactId)).size ===
      collection.contacts.length
  );
}

function expectedAddressEtag(address: OrganizationAddress) {
  return `"organization-address:${address.addressId}:${address.lockVersion}"`;
}

function expectedContactEtag(contact: OrganizationContact) {
  return `"organization-contact:${contact.contactId}:${contact.lockVersion}"`;
}

function expectedIdentifierEtag(identifier: OrganizationIdentifier): string {
  return `"organization-identifier:${identifier.identifierId}:${identifier.lockVersion}"`;
}

function validDateTime(value: unknown): value is string {
  return typeof value === 'string' && Number.isFinite(Date.parse(value));
}

function validMembership(value: unknown): value is OrganizationMembershipSummary {
  if (!value || typeof value !== 'object') return false;
  const membership = value as Partial<OrganizationMembershipSummary>;
  return (
    typeof membership.membershipId === 'string' &&
    uuidPattern.test(membership.membershipId) &&
    typeof membership.userId === 'string' &&
    uuidPattern.test(membership.userId) &&
    typeof membership.displayName === 'string' &&
    membership.displayName.length > 0 &&
    membership.displayName.length <= 160 &&
    typeof membership.email === 'string' &&
    membership.email.length > 2 &&
    membership.email.length <= 320 &&
    membership.email.includes('@') &&
    typeof membership.accountStatus === 'string' &&
    accountStatuses.has(membership.accountStatus) &&
    typeof membership.roleKey === 'string' &&
    /^[a-z][a-z0-9]*([._:-][a-z0-9]+)*$/.test(membership.roleKey) &&
    typeof membership.roleDisplayName === 'string' &&
    membership.roleDisplayName.length > 0 &&
    membership.roleDisplayName.length <= 160 &&
    typeof membership.roleStatus === 'string' &&
    roleStatuses.has(membership.roleStatus) &&
    typeof membership.finalOwner === 'boolean' &&
    typeof membership.accessState === 'string' &&
    membershipAccessStates.has(membership.accessState) &&
    validDateTime(membership.effectiveFrom) &&
    (membership.effectiveTo === null || validDateTime(membership.effectiveTo)) &&
    (membership.effectiveTo === null ||
      Date.parse(membership.effectiveTo) > Date.parse(membership.effectiveFrom)) &&
    nonNegativeInteger(membership.lockVersion) &&
    typeof membership.mfaEnabled === 'boolean' &&
    Array.isArray(membership.availableActions) &&
    new Set(membership.availableActions).size === membership.availableActions.length &&
    membership.availableActions.every((action) => membershipRowActions.has(action))
  );
}

function validMembershipPage(
  value: unknown,
  organizationId: string,
  expectedLimit: number,
): value is OrganizationMembershipPage {
  if (!value || typeof value !== 'object') return false;
  const page = value as Partial<OrganizationMembershipPage>;
  if (
    page.organizationId !== organizationId ||
    !validDateTime(page.asOf) ||
    !Array.isArray(page.items) ||
    !page.items.every(validMembership) ||
    new Set(page.items.map((item) => item.membershipId)).size !== page.items.length ||
    !page.page ||
    typeof page.page !== 'object' ||
    page.page.limit !== expectedLimit ||
    typeof page.page.hasMore !== 'boolean' ||
    page.items.length > expectedLimit ||
    (page.page.hasMore && page.items.length !== expectedLimit) ||
    (page.page.hasMore
      ? typeof page.page.nextCursor !== 'string' || !cursorPattern.test(page.page.nextCursor)
      : page.page.nextCursor !== null) ||
    !Array.isArray(page.availableActions) ||
    new Set(page.availableActions).size !== page.availableActions.length ||
    !page.availableActions.every((action) => membershipPageActions.has(action))
  ) {
    return false;
  }
  return true;
}

function expectedProfileEtag(profile: OrganizationProfile): string {
  return `"organization-profile:${profile.lockVersion}"`;
}

const internationalFields = new Set([
  'countryCode',
  'timezone',
  'locale',
  'language',
  'currencyCode',
  'weekStart',
]);

function validInternationalSettings(
  value: OrganizationInternationalSettings,
  organizationId: string,
): value is OrganizationInternationalSettings {
  const current = value.versions?.filter(
    (version) => version.lifecycle === 'default' || version.lifecycle === 'active',
  );
  const scheduled = value.versions?.filter((version) => version.lifecycle === 'scheduled');
  return (
    value.organizationId === organizationId &&
    typeof value.editable === 'boolean' &&
    typeof value.canSchedule === 'boolean' &&
    (!value.canSchedule || value.editable) &&
    nonNegativeInteger(value.lockVersion) &&
    instant(value.evaluatedAt) !== null &&
    Array.isArray(value.weekStarts) &&
    value.weekStarts.join(',') === 'MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY,SUNDAY' &&
    Array.isArray(value.impactRules) &&
    value.impactRules.length === 6 &&
    value.impactRules.every(
      (rule) =>
        internationalFields.has(rule.field) &&
        /^m1\.settings\.[a-z_]+$/.test(rule.code) &&
        (rule.severity === 'information' || rule.severity === 'warning'),
    ) &&
    Array.isArray(value.versions) &&
    value.versions.length >= 1 &&
    value.versions.length <= 2 &&
    current.length === 1 &&
    scheduled.length <= 1 &&
    (!value.canSchedule || scheduled.length === 0) &&
    value.versions.every(
      (version) =>
        /^[A-Z]{2}$/.test(version.countryCode) &&
        /^[A-Z]{3}$/.test(version.currencyCode) &&
        localePattern.test(version.locale) &&
        localePattern.test(version.language) &&
        instant(version.effectiveFrom) !== null &&
        nonNegativeInteger(version.lockVersion) &&
        version.formatPreview?.localeLibraryDerived === true,
    )
  );
}

function expectedInternationalSettingsEtag(value: OrganizationInternationalSettings): string {
  return `"organization-international-settings:${value.lockVersion}"`;
}

function failureMessage(failure: ApiFailure): string {
  const retry = failure.retryAfterSeconds
    ? ` Retry after ${failure.retryAfterSeconds} seconds.`
    : '';
  return `${failure.problem.detail}${retry} Correlation: ${failure.correlationId}`;
}

function PageHeading({ id }: { id: AdministrationScreenProps['id'] }) {
  const screen = findScreen(id);
  return (
    <div className="page-head">
      <div>
        <span className="eyebrow">{screen.id}</span>
        <h1>{screen.title}</h1>
        <p>{screen.purpose}</p>
      </div>
      <span className="badge info">
        <ShieldCheck size={14} aria-hidden="true" /> Approved implementation slice
      </span>
    </div>
  );
}

function FailurePanel({ issue, onRetry }: { issue: string; onRetry(): void }) {
  return (
    <section className="panel administration-state-panel" aria-labelledby="load-failure-heading">
      <div className="alert error-alert" role="alert">
        <CircleAlert size={18} aria-hidden="true" />
        <div>
          <strong id="load-failure-heading">Unable to load organization data</strong>
          <span>{issue}</span>
        </div>
      </div>
      <button className="secondary-button" onClick={onRetry}>
        <RefreshCw size={17} aria-hidden="true" /> Retry
      </button>
    </section>
  );
}

function LoadingPanel() {
  return (
    <section className="panel administration-state-panel" aria-live="polite" aria-busy="true">
      <RefreshCw className="spinner" aria-hidden="true" />
      <p>Loading server-calculated organization state...</p>
    </section>
  );
}

function readinessBadge(outcome: ReadinessGate['outcome']) {
  if (outcome === 'complete') return 'success';
  if (outcome === 'warning') return 'warning';
  if (outcome === 'blocked') return 'danger';
  return 'neutral';
}

function readinessIcon(outcome: ReadinessGate['outcome']) {
  if (outcome === 'complete') return <Check size={17} />;
  if (outcome === 'blocked') return <CircleAlert size={17} />;
  if (outcome === 'warning') return <AlertTriangle size={17} />;
  return <Minus size={17} />;
}

function outcomeLabel(outcome: ReadinessGate['outcome']) {
  return outcome === 'not_applicable' ? 'Not applicable' : outcome;
}

function freshness(readiness: AdministrationReadiness) {
  const remaining = Date.parse(readiness.expiresAt) - Date.now();
  if (remaining <= 0) return 'Expired';
  return `${Math.max(1, Math.ceil(remaining / 60_000))} min`;
}

function ReadinessList({ gates }: { gates: ReadinessGate[] }) {
  return (
    <div className="check-list">
      {gates.map((gate) => (
        <div key={gate.key}>
          <span className={`status-icon ${gate.outcome}`} aria-hidden="true">
            {readinessIcon(gate.outcome)}
          </span>
          <span>
            <strong>{gate.label}</strong>
            <small>{gate.detail}</small>
          </span>
          <a className={`badge ${readinessBadge(gate.outcome)}`} href={gate.href}>
            {outcomeLabel(gate.outcome)}
          </a>
        </div>
      ))}
    </div>
  );
}

function ReadinessContent({
  id,
  readiness,
}: {
  id: 'M1-05' | 'M1-06';
  readiness: AdministrationReadiness;
}) {
  const freshnessLabel = freshness(readiness);
  const priorityGates = readiness.gates
    .filter((gate) => gate.outcome === 'blocked' || gate.outcome === 'warning')
    .sort((left, right) => Number(right.outcome === 'blocked') - Number(left.outcome === 'blocked'))
    .slice(0, 5);

  if (id === 'M1-06') {
    return (
      <section className="panel">
        <div className="panel-heading">
          <div>
            <h2>Setup readiness</h2>
            <p>
              Approved catalogue {readiness.catalogueVersion} · organization revision{' '}
              {readiness.organizationRevision}
            </p>
          </div>
          <span className={`badge ${freshnessLabel === 'Expired' ? 'warning' : 'info'}`}>
            {freshnessLabel === 'Expired' ? 'Evaluation expired' : `Fresh for ${freshnessLabel}`}
          </span>
        </div>
        <div className="readiness-boundary" role="note">
          This live server projection feeds the persisted M1-21 validation, evidence review, and
          activation workflow.
        </div>
        <ReadinessList gates={readiness.gates} />
      </section>
    );
  }

  return (
    <>
      <div className="stats-grid">
        <article className="stat-card">
          <span>Readiness</span>
          <strong>
            {readiness.completedGates}/{readiness.totalGates}
          </strong>
          <small>
            {readiness.blockedGates} blockers · {readiness.warningGates} warnings
          </small>
        </article>
        <article className="stat-card">
          <span>Facilities</span>
          <strong>{readiness.facilityCount}</strong>
          <small>{readiness.draftFacilityCount} still in draft</small>
        </article>
        <article className="stat-card">
          <span>Lifecycle</span>
          <strong className="stat-text-value">{readiness.lifecycleStatus}</strong>
          <small>Current organization state</small>
        </article>
        <article className="stat-card">
          <span>Freshness</span>
          <strong className="stat-text-value">{freshnessLabel}</strong>
          <small>Server evaluated</small>
        </article>
      </div>
      <section className="panel">
        <div className="panel-heading">
          <div>
            <h2>Priority exceptions</h2>
            <p>Server-calculated blockers and warnings ordered by activation impact.</p>
          </div>
          <a className="button-link secondary-button" href="#/M1-06">
            Review full checklist
          </a>
        </div>
        <ReadinessList gates={priorityGates} />
      </section>
    </>
  );
}

function ReadinessScreen({ client, id, organizationId }: ReadinessScreenProps) {
  const [attempt, setAttempt] = useState(0);
  const [state, setState] = useState<LoadState<AdministrationReadiness>>({ phase: 'loading' });

  useEffect(() => {
    const controller = new AbortController();
    void client
      .getAdministrationReadiness(organizationId, { signal: controller.signal })
      .then((result) => {
        if (controller.signal.aborted) return;
        if (!result.ok) {
          setState({ issue: failureMessage(result), phase: 'failure' });
        } else if (!validReadiness(result.data, organizationId)) {
          setState({
            issue: 'The server response did not match the organization-readiness contract.',
            phase: 'failure',
          });
        } else {
          setState({ data: result.data, phase: 'ready' });
        }
      });
    return () => controller.abort();
  }, [attempt, client, organizationId]);

  return (
    <>
      <PageHeading id={id} />
      {state.phase === 'loading' ? (
        <LoadingPanel />
      ) : state.phase === 'failure' ? (
        <FailurePanel
          issue={state.issue}
          onRetry={() => {
            setState({ phase: 'loading' });
            setAttempt((value) => value + 1);
          }}
        />
      ) : (
        <ReadinessContent id={id} readiness={state.data} />
      )}
    </>
  );
}

function accessStateBadge(state: OrganizationMembershipSummary['accessState']) {
  if (state === 'active') return 'success';
  if (state === 'scheduled') return 'info';
  if (state === 'suspended') return 'warning';
  return 'neutral';
}

function displayDate(value: string) {
  return new Date(value).toLocaleString(undefined, {
    dateStyle: 'medium',
    timeStyle: 'short',
  });
}

function useCompactMembershipProjection() {
  const [compact, setCompact] = useState(
    () =>
      typeof window !== 'undefined' &&
      typeof window.matchMedia === 'function' &&
      window.matchMedia(compactMembershipQuery).matches,
  );

  useEffect(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return;

    const media = window.matchMedia(compactMembershipQuery);
    const update = (event?: MediaQueryListEvent) => setCompact(event?.matches ?? media.matches);
    update();
    media.addEventListener('change', update);
    return () => media.removeEventListener('change', update);
  }, []);

  return compact;
}

function validMembershipChange(value: unknown): value is MembershipChangeMutation {
  if (!value || typeof value !== 'object') return false;
  const change = value as Partial<MembershipChangeMutation>;
  return (
    typeof change.approvalId === 'string' &&
    uuidPattern.test(change.approvalId) &&
    typeof change.membershipId === 'string' &&
    uuidPattern.test(change.membershipId) &&
    typeof change.targetUserId === 'string' &&
    uuidPattern.test(change.targetUserId) &&
    ['role_change', 'revoke', 'owner_promotion', 'owner_demotion'].includes(
      change.changeType ?? '',
    ) &&
    typeof change.fromRoleKey === 'string' &&
    (change.toRoleKey === null || typeof change.toRoleKey === 'string') &&
    nonNegativeInteger(change.lockVersion) &&
    ['pending', 'approved', 'changed', 'revoked', 'transferred'].includes(change.status ?? '') &&
    validDateTime(change.expiresAt)
  );
}

function MembershipActions({
  membership,
  onRequest,
}: {
  membership: OrganizationMembershipSummary;
  onRequest(
    membership: OrganizationMembershipSummary,
    changeType: 'role_change' | 'revoke' | 'owner_transfer',
  ): void;
}) {
  if (membership.availableActions.length === 0) {
    return <span className="membership-no-action">No action</span>;
  }

  return (
    <span className="membership-action-list">
      {membership.availableActions.includes('requestRoleChange') && (
        <button
          className="text-action"
          type="button"
          onClick={() => onRequest(membership, 'role_change')}
        >
          Change role
        </button>
      )}
      {membership.availableActions.includes('requestRevocation') && (
        <button
          className="text-action danger-text-action"
          type="button"
          onClick={() => onRequest(membership, 'revoke')}
        >
          Revoke access
        </button>
      )}
      {membership.availableActions.includes('requestOwnerTransfer') && (
        <button
          className={`text-action ${membership.finalOwner ? 'danger-text-action' : ''}`}
          type="button"
          onClick={() => onRequest(membership, 'owner_transfer')}
        >
          {membership.finalOwner ? 'Demote owner' : 'Promote to owner'}
        </button>
      )}
      {membership.availableActions.includes('requestMfaReset') && (
        <a className="text-action" href="#/M1-03">
          Request MFA reset
        </a>
      )}
    </span>
  );
}

function MembershipCards({
  data,
  onRequest,
}: {
  data: OrganizationMembershipPage;
  onRequest(
    membership: OrganizationMembershipSummary,
    changeType: 'role_change' | 'revoke' | 'owner_transfer',
  ): void;
}) {
  return (
    <div className="membership-card-list" role="list" aria-label="Organization membership cards">
      {data.items.map((membership) => (
        <article className="membership-card" role="listitem" key={membership.membershipId}>
          <div className="membership-card-heading">
            <h3>{membership.displayName}</h3>
            <p>{membership.email}</p>
          </div>
          <dl className="membership-card-details">
            <div>
              <dt>Role</dt>
              <dd className="membership-card-value">
                <strong>{membership.roleDisplayName}</strong>
                <small>{membership.roleStatus} registry role</small>
                {membership.finalOwner && <span className="badge warning">Protected owner</span>}
              </dd>
            </div>
            <div>
              <dt>Access</dt>
              <dd className="membership-card-value">
                <span className={`badge ${accessStateBadge(membership.accessState)}`}>
                  {membership.accessState}
                </span>
                <small>{membership.accountStatus} account</small>
              </dd>
            </div>
            <div>
              <dt>MFA</dt>
              <dd>{membership.mfaEnabled ? 'Enabled' : 'Not enabled'}</dd>
            </div>
            <div>
              <dt>Effective period</dt>
              <dd className="membership-card-value">
                <strong>From {displayDate(membership.effectiveFrom)}</strong>
                <small>
                  {membership.effectiveTo
                    ? `Until ${displayDate(membership.effectiveTo)}`
                    : 'No scheduled end'}
                </small>
              </dd>
            </div>
          </dl>
          <div
            className="membership-card-actions"
            aria-label={`Actions for ${membership.displayName}`}
          >
            <MembershipActions membership={membership} onRequest={onRequest} />
          </div>
        </article>
      ))}
    </div>
  );
}

function MembershipTable({
  data,
  onRequest,
}: {
  data: OrganizationMembershipPage;
  onRequest(
    membership: OrganizationMembershipSummary,
    changeType: 'role_change' | 'revoke' | 'owner_transfer',
  ): void;
}) {
  const compact = useCompactMembershipProjection();

  return (
    <section className="panel membership-results" aria-labelledby="membership-results-heading">
      <div className="panel-heading">
        <div>
          <h2 id="membership-results-heading">Administrator directory</h2>
          <p>
            Access state is evaluated at {displayDate(data.asOf)}. Only server-authorized actions
            are shown.
          </p>
        </div>
        <span className="badge neutral">{data.items.length} shown</span>
      </div>
      <div
        className={compact ? 'membership-card-region' : 'table-wrap'}
        role="region"
        aria-label="Organization memberships"
        tabIndex={0}
      >
        {compact ? (
          <MembershipCards data={data} onRequest={onRequest} />
        ) : (
          <table>
            <caption className="sr-only">Authorized organization membership summaries</caption>
            <thead>
              <tr>
                <th>Administrator</th>
                <th>Role</th>
                <th>Access</th>
                <th>MFA</th>
                <th>Effective period</th>
                <th>
                  <span className="sr-only">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {data.items.map((membership) => (
                <tr key={membership.membershipId}>
                  <td>
                    <span className="membership-cell-stack">
                      <strong>{membership.displayName}</strong>
                      <small>{membership.email}</small>
                    </span>
                  </td>
                  <td>
                    <span className="membership-cell-stack">
                      <strong>{membership.roleDisplayName}</strong>
                      <small>{membership.roleStatus} registry role</small>
                      {membership.finalOwner && (
                        <span className="badge warning">Protected owner</span>
                      )}
                    </span>
                  </td>
                  <td>
                    <span className="membership-cell-stack">
                      <span className={`badge ${accessStateBadge(membership.accessState)}`}>
                        {membership.accessState}
                      </span>
                      <small>{membership.accountStatus} account</small>
                    </span>
                  </td>
                  <td>{membership.mfaEnabled ? 'Enabled' : 'Not enabled'}</td>
                  <td>
                    <span className="membership-cell-stack">
                      <strong>From {displayDate(membership.effectiveFrom)}</strong>
                      <small>
                        {membership.effectiveTo
                          ? `Until ${displayDate(membership.effectiveTo)}`
                          : 'No scheduled end'}
                      </small>
                    </span>
                  </td>
                  <td>
                    <MembershipActions membership={membership} onRequest={onRequest} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </section>
  );
}

function MembershipScreen({ client, organizationId }: AdministrationScreenProps) {
  const pageLimit = 25;
  const emptyFilters: MembershipFilters = { roleKey: '', search: '', state: '' };
  const [draftFilters, setDraftFilters] = useState<MembershipFilters>(emptyFilters);
  const [appliedFilters, setAppliedFilters] = useState<MembershipFilters>(emptyFilters);
  const [cursorHistory, setCursorHistory] = useState<string[]>([]);
  const [attempt, setAttempt] = useState(0);
  const [state, setState] = useState<LoadState<OrganizationMembershipPage>>({ phase: 'loading' });
  const [workflowAction, setWorkflowAction] =
    useState<MembershipWorkflowAction>('request_role_change');
  const [workflowMembershipId, setWorkflowMembershipId] = useState('');
  const [workflowApprovalId, setWorkflowApprovalId] = useState('');
  const [workflowRoleKey, setWorkflowRoleKey] = useState('organization_viewer');
  const [workflowReason, setWorkflowReason] = useState('');
  const [workflowBusy, setWorkflowBusy] = useState(false);
  const [workflowIssue, setWorkflowIssue] = useState('');
  const [workflowResult, setWorkflowResult] = useState<MembershipChangeMutation | null>(null);
  const workflowAttempt = useRef<{ fingerprint: string; key: string } | null>(null);
  const cursor = cursorHistory.at(-1);
  const selectedWorkflowTarget =
    state.phase === 'ready'
      ? state.data.items.find((membership) => membership.membershipId === workflowMembershipId)
      : undefined;

  useEffect(() => {
    const controller = new AbortController();
    void client
      .listOrganizationMemberships(
        organizationId,
        {
          limit: pageLimit,
          ...(appliedFilters.search ? { search: appliedFilters.search } : {}),
          ...(appliedFilters.state ? { state: appliedFilters.state } : {}),
          ...(appliedFilters.roleKey ? { roleKey: appliedFilters.roleKey } : {}),
          ...(cursor ? { cursor } : {}),
        },
        { signal: controller.signal },
      )
      .then((result) => {
        if (controller.signal.aborted) return;
        if (!result.ok) {
          setState({ issue: failureMessage(result), phase: 'failure' });
        } else if (!validMembershipPage(result.data, organizationId, pageLimit)) {
          setState({
            issue: 'The server response did not match the organization-membership contract.',
            phase: 'failure',
          });
        } else {
          setState({ data: result.data, phase: 'ready' });
        }
      });
    return () => controller.abort();
  }, [
    appliedFilters.roleKey,
    appliedFilters.search,
    appliedFilters.state,
    attempt,
    client,
    cursor,
    organizationId,
  ]);

  const applyFilters = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setAppliedFilters({ ...draftFilters, search: draftFilters.search.trim().normalize('NFC') });
    setCursorHistory([]);
    setState({ phase: 'loading' });
    setAttempt((value) => value + 1);
  };
  const clearFilters = () => {
    setDraftFilters(emptyFilters);
    setAppliedFilters(emptyFilters);
    setCursorHistory([]);
    setState({ phase: 'loading' });
    setAttempt((value) => value + 1);
  };

  const selectMembershipRequest = (
    membership: OrganizationMembershipSummary,
    changeType: 'role_change' | 'revoke' | 'owner_transfer',
  ) => {
    setWorkflowAction(
      changeType === 'role_change'
        ? 'request_role_change'
        : changeType === 'revoke'
          ? 'request_revoke'
          : 'request_owner_transfer',
    );
    setWorkflowMembershipId(membership.membershipId);
    setWorkflowApprovalId('');
    setWorkflowRoleKey(
      changeType === 'owner_transfer'
        ? membership.finalOwner
          ? 'organization_administrator'
          : 'organization_owner'
        : membership.roleKey === 'organization_viewer'
          ? 'configuration_editor'
          : 'organization_viewer',
    );
    setWorkflowReason('');
    setWorkflowIssue('');
    setWorkflowResult(null);
    workflowAttempt.current = null;
  };

  const submitMembershipWorkflow = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (state.phase !== 'ready') return;
    const reason = workflowReason.trim().normalize('NFC');
    const reasonLength = Array.from(reason).length;
    const invalidControl = Array.from(reason).some((character) => {
      const code = character.charCodeAt(0);
      return code <= 31 || code === 127;
    });
    if (reasonLength < 10 || reasonLength > 500 || invalidControl) {
      setWorkflowIssue('Reason must contain 10 to 500 characters without control characters.');
      return;
    }
    if (!uuidPattern.test(workflowMembershipId)) {
      setWorkflowIssue('Select or enter a valid membership ID.');
      return;
    }
    const requesting = workflowAction.startsWith('request_');
    const target = state.data.items.find(
      (membership) => membership.membershipId === workflowMembershipId,
    );
    if (requesting && !target) {
      setWorkflowIssue('Reload the directory and select the membership again.');
      return;
    }
    if (!requesting && !uuidPattern.test(workflowApprovalId)) {
      setWorkflowIssue('Enter the change request ID returned by the request step.');
      return;
    }

    const fingerprint = JSON.stringify({
      action: workflowAction,
      approvalId: workflowApprovalId,
      membershipId: workflowMembershipId,
      reason,
      roleKey: workflowRoleKey,
    });
    if (workflowAttempt.current?.fingerprint !== fingerprint) {
      workflowAttempt.current = {
        fingerprint,
        key: `membership-${workflowAction}:${crypto.randomUUID()}`,
      };
    }
    const idempotencyKey = workflowAttempt.current.key;
    setWorkflowBusy(true);
    setWorkflowIssue('');
    setWorkflowResult(null);
    try {
      const result =
        workflowAction === 'request_role_change'
          ? await client.requestOrganizationMembershipChange(
              organizationId,
              workflowMembershipId,
              { changeType: 'role_change', reason, toRoleKey: workflowRoleKey },
              `"organization-membership:${workflowMembershipId}:${target!.lockVersion}"`,
              idempotencyKey,
            )
          : workflowAction === 'request_revoke'
            ? await client.requestOrganizationMembershipChange(
                organizationId,
                workflowMembershipId,
                { changeType: 'revoke', reason },
                `"organization-membership:${workflowMembershipId}:${target!.lockVersion}"`,
                idempotencyKey,
              )
            : workflowAction === 'request_owner_transfer'
              ? await client.requestOrganizationOwnerTransfer(
                  organizationId,
                  workflowMembershipId,
                  { reason, toRoleKey: workflowRoleKey },
                  `"organization-membership:${workflowMembershipId}:${target!.lockVersion}"`,
                  idempotencyKey,
                )
              : workflowAction === 'approve'
                ? await client.approveOrganizationMembershipChange(
                    organizationId,
                    workflowMembershipId,
                    workflowApprovalId,
                    { reason },
                    idempotencyKey,
                  )
                : workflowAction === 'execute'
                  ? await client.executeOrganizationMembershipChange(
                      organizationId,
                      workflowMembershipId,
                      workflowApprovalId,
                      { reason },
                      idempotencyKey,
                    )
                  : workflowAction === 'approve_owner_transfer'
                    ? await client.approveOrganizationOwnerTransfer(
                        organizationId,
                        workflowMembershipId,
                        workflowApprovalId,
                        { reason },
                        idempotencyKey,
                      )
                    : await client.executeOrganizationOwnerTransfer(
                        organizationId,
                        workflowMembershipId,
                        workflowApprovalId,
                        { reason },
                        idempotencyKey,
                      );
      if (!result.ok) {
        setWorkflowIssue(failureMessage(result));
      } else if (!validMembershipChange(result.data)) {
        setWorkflowIssue('The server response did not match the membership-change contract.');
      } else {
        setWorkflowResult(result.data);
        setWorkflowApprovalId(result.data.approvalId);
        workflowAttempt.current = null;
        if (
          result.data.status === 'changed' ||
          result.data.status === 'revoked' ||
          result.data.status === 'transferred'
        ) {
          setState({ phase: 'loading' });
          setAttempt((value) => value + 1);
        }
      }
    } catch {
      setWorkflowIssue('The membership workflow input is invalid. Review the IDs and revision.');
    } finally {
      setWorkflowBusy(false);
    }
  };

  return (
    <>
      <PageHeading id="M1-20" />
      <section
        className="panel membership-filter-panel"
        aria-labelledby="membership-filters-heading"
      >
        <div className="panel-heading">
          <div>
            <h2 id="membership-filters-heading">Find organization access</h2>
            <p>Search and filters are evaluated by the authorized server projection.</p>
          </div>
          {state.phase === 'ready' && state.data.availableActions.includes('issueInvitation') && (
            <a className="primary-button membership-primary-action" href="#/M1-02">
              Invite administrator
            </a>
          )}
        </div>
        <form className="filter-grid" onSubmit={applyFilters}>
          <label htmlFor="membership-search">
            Name or email
            <input
              id="membership-search"
              type="search"
              minLength={2}
              maxLength={100}
              placeholder="Search administrators"
              value={draftFilters.search}
              onChange={(event) => setDraftFilters({ ...draftFilters, search: event.target.value })}
            />
          </label>
          <label htmlFor="membership-state">
            Access state
            <select
              id="membership-state"
              value={draftFilters.state}
              onChange={(event) =>
                setDraftFilters({
                  ...draftFilters,
                  state: event.target.value as MembershipFilters['state'],
                })
              }
            >
              <option value="">All access states</option>
              {Array.from(membershipAccessStates).map((accessState) => (
                <option key={accessState} value={accessState}>
                  {accessState}
                </option>
              ))}
            </select>
          </label>
          <label htmlFor="membership-role">
            Role
            <select
              id="membership-role"
              value={draftFilters.roleKey}
              onChange={(event) =>
                setDraftFilters({ ...draftFilters, roleKey: event.target.value })
              }
            >
              <option value="">All approved roles</option>
              {membershipRoles.map(([roleKey, label]) => (
                <option key={roleKey} value={roleKey}>
                  {label}
                </option>
              ))}
            </select>
          </label>
          <div className="membership-filter-actions">
            <button className="secondary-button" type="button" onClick={clearFilters}>
              Clear
            </button>
            <button className="primary-button" type="submit">
              Apply filters
            </button>
          </div>
        </form>
      </section>
      {state.phase === 'loading' ? (
        <LoadingPanel />
      ) : state.phase === 'failure' ? (
        <FailurePanel
          issue={state.issue}
          onRetry={() => {
            setState({ phase: 'loading' });
            setAttempt((value) => value + 1);
          }}
        />
      ) : state.data.items.length === 0 ? (
        <section
          className="panel administration-state-panel"
          aria-labelledby="membership-empty-heading"
        >
          <h2 id="membership-empty-heading">No memberships match these filters</h2>
          <p>Clear or change the filters to review another authorized page.</p>
          <button className="secondary-button" onClick={clearFilters}>
            Clear filters
          </button>
        </section>
      ) : (
        <>
          <MembershipTable data={state.data} onRequest={selectMembershipRequest} />
          <nav className="membership-pagination" aria-label="Membership pages">
            <button
              className="secondary-button"
              disabled={cursorHistory.length === 0}
              onClick={() => {
                setState({ phase: 'loading' });
                setCursorHistory((history) => history.slice(0, -1));
              }}
            >
              Previous
            </button>
            <span>Page {cursorHistory.length + 1}</span>
            <button
              className="secondary-button"
              disabled={!state.data.page.hasMore || !state.data.page.nextCursor}
              onClick={() => {
                if (!state.data.page.nextCursor) return;
                setState({ phase: 'loading' });
                setCursorHistory((history) => [...history, state.data.page.nextCursor as string]);
              }}
            >
              Next
            </button>
          </nav>
        </>
      )}
      {state.phase === 'ready' &&
        (state.data.items.some((membership) =>
          membership.availableActions.some(
            (action) =>
              action === 'requestRoleChange' ||
              action === 'requestRevocation' ||
              action === 'requestOwnerTransfer',
          ),
        ) ||
          state.data.availableActions.includes('approveMembershipChange') ||
          state.data.availableActions.includes('executeMembershipChange') ||
          state.data.availableActions.includes('approveOwnerTransfer') ||
          state.data.availableActions.includes('executeOwnerTransfer')) && (
          <section
            className="panel membership-workflow"
            aria-labelledby="membership-workflow-heading"
          >
            <div className="panel-heading">
              <div>
                <h2 id="membership-workflow-heading">Governed access change</h2>
                <p>
                  A separate owner must approve the exact request. Only its original requester can
                  execute it before expiry.
                </p>
              </div>
              <span className="badge warning">Maker-checker</span>
            </div>
            <form className="form-grid" onSubmit={(event) => void submitMembershipWorkflow(event)}>
              <label htmlFor="membership-workflow-action">
                Workflow step
                <select
                  id="membership-workflow-action"
                  value={workflowAction}
                  disabled={workflowBusy}
                  onChange={(event) => {
                    const nextAction = event.target.value as MembershipWorkflowAction;
                    setWorkflowAction(nextAction);
                    if (nextAction === 'request_role_change') {
                      setWorkflowRoleKey('organization_viewer');
                    } else if (nextAction === 'request_owner_transfer' && selectedWorkflowTarget) {
                      setWorkflowRoleKey(
                        selectedWorkflowTarget.finalOwner
                          ? 'organization_administrator'
                          : 'organization_owner',
                      );
                    }
                    setWorkflowIssue('');
                    setWorkflowResult(null);
                    workflowAttempt.current = null;
                  }}
                >
                  {state.data.items.some((membership) =>
                    membership.availableActions.includes('requestRoleChange'),
                  ) && <option value="request_role_change">Request role change</option>}
                  {state.data.items.some((membership) =>
                    membership.availableActions.includes('requestRevocation'),
                  ) && <option value="request_revoke">Request revocation</option>}
                  {state.data.items.some((membership) =>
                    membership.availableActions.includes('requestOwnerTransfer'),
                  ) && <option value="request_owner_transfer">Request owner role change</option>}
                  {state.data.availableActions.includes('approveMembershipChange') && (
                    <option value="approve">Approve membership request</option>
                  )}
                  {state.data.availableActions.includes('executeMembershipChange') && (
                    <option value="execute">Execute membership request</option>
                  )}
                  {state.data.availableActions.includes('approveOwnerTransfer') && (
                    <option value="approve_owner_transfer">Approve owner role request</option>
                  )}
                  {state.data.availableActions.includes('executeOwnerTransfer') && (
                    <option value="execute_owner_transfer">Execute owner role request</option>
                  )}
                </select>
              </label>
              <label htmlFor="membership-workflow-membership-id">
                Membership ID
                <input
                  id="membership-workflow-membership-id"
                  value={workflowMembershipId}
                  readOnly={workflowAction.startsWith('request_')}
                  disabled={workflowBusy}
                  placeholder="Select a directory row or enter its UUID"
                  onChange={(event) => setWorkflowMembershipId(event.target.value.trim())}
                />
              </label>
              {(workflowAction === 'request_role_change' ||
                workflowAction === 'request_owner_transfer') && (
                <label htmlFor="membership-workflow-role">
                  New role
                  <select
                    id="membership-workflow-role"
                    value={workflowRoleKey}
                    disabled={workflowBusy}
                    onChange={(event) => setWorkflowRoleKey(event.target.value)}
                  >
                    {membershipRoles
                      .filter(([roleKey]) => {
                        if (workflowAction === 'request_role_change') {
                          return roleKey !== 'organization_owner';
                        }
                        return selectedWorkflowTarget?.finalOwner
                          ? roleKey !== 'organization_owner'
                          : roleKey === 'organization_owner';
                      })
                      .map(([roleKey, label]) => (
                        <option key={roleKey} value={roleKey}>
                          {label}
                        </option>
                      ))}
                  </select>
                </label>
              )}
              {!workflowAction.startsWith('request_') && (
                <label htmlFor="membership-workflow-approval-id">
                  Approval request ID
                  <input
                    id="membership-workflow-approval-id"
                    value={workflowApprovalId}
                    disabled={workflowBusy}
                    placeholder="Approval UUID from the request step"
                    onChange={(event) => setWorkflowApprovalId(event.target.value.trim())}
                  />
                </label>
              )}
              <label className="full-width" htmlFor="membership-workflow-reason">
                {workflowAction === 'execute' || workflowAction === 'execute_owner_transfer'
                  ? 'Original request reason'
                  : 'Reason'}
                <textarea
                  id="membership-workflow-reason"
                  required
                  minLength={10}
                  maxLength={500}
                  value={workflowReason}
                  disabled={workflowBusy}
                  placeholder={
                    workflowAction === 'execute' || workflowAction === 'execute_owner_transfer'
                      ? 'Repeat the exact reason recorded on your original request'
                      : 'Record the approved access-control rationale (10–500 characters)'
                  }
                  onChange={(event) => setWorkflowReason(event.target.value)}
                />
              </label>
              {workflowIssue && (
                <div className="alert error-alert full-width" role="alert">
                  <CircleAlert size={18} aria-hidden="true" />
                  <span>{workflowIssue}</span>
                </div>
              )}
              {workflowResult && (
                <div className="alert success-alert full-width" role="status">
                  <Check size={18} aria-hidden="true" />
                  <span>
                    Access change is <strong>{workflowResult.status}</strong>. Approval request ID:{' '}
                    <code>{workflowResult.approvalId}</code>
                  </span>
                </div>
              )}
              <div className="form-actions full-width">
                <button className="primary-button" type="submit" disabled={workflowBusy}>
                  {workflowBusy ? 'Submitting…' : 'Submit governed step'}
                </button>
              </div>
            </form>
          </section>
        )}
      <aside className="membership-mutation-boundary" aria-label="Access change availability">
        <ShieldCheck size={18} aria-hidden="true" />
        <div>
          <strong>Facility-scoped grants remain unavailable</strong>
          <p>
            Organization-wide role changes, revocations, and owner promotions or demotions now use
            independent approval. Facility scope awaits an approved scope record and the scope-aware
            authorization engine.
          </p>
        </div>
      </aside>
    </>
  );
}

function dateTimeInputValue(value: string) {
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) return '';
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000);
  return local.toISOString().slice(0, 16);
}

function identifierDraft(
  identifier: OrganizationIdentifier | null,
  identifierType: string,
): IdentifierDraftForm {
  return {
    assigningAuthority: identifier?.assigningAuthority ?? '',
    effectiveFrom: dateTimeInputValue(identifier?.effectiveFrom ?? new Date().toISOString()),
    effectiveTo: identifier?.effectiveTo ? dateTimeInputValue(identifier.effectiveTo) : '',
    expiryDate: identifier?.expiryDate ?? '',
    identifierType: identifier?.identifierType ?? identifierType,
    isPrimary: identifier?.isPrimary ?? false,
    issueDate: identifier?.issueDate ?? '',
    jurisdictionCountryCode: identifier?.jurisdictionCountryCode ?? '',
    reason: '',
    value: identifier?.value ?? '',
  };
}

function identifierStatusBadge(status: OrganizationIdentifier['status']) {
  if (status === 'active' || status === 'verified') return 'success';
  if (status === 'expired' || status === 'superseded') return 'warning';
  if (status === 'revoked') return 'danger';
  return 'neutral';
}

function identifierReplacementCandidates(
  collection: OrganizationIdentifierCollection,
  identifier: OrganizationIdentifier,
) {
  return collection.items.filter(
    (candidate) =>
      candidate.identifierId !== identifier.identifierId &&
      candidate.identifierType === identifier.identifierType &&
      candidate.supersedesId === null &&
      (candidate.status === 'verified' || candidate.status === 'active'),
  );
}

function IdentifierScreen({ client, organizationId }: AdministrationScreenProps) {
  const [attempt, setAttempt] = useState(0);
  const [state, setState] = useState<IdentifierState>({ phase: 'loading' });
  const [action, setAction] = useState<IdentifierAction | null>(null);
  const [busy, setBusy] = useState(false);
  const [issue, setIssue] = useState('');
  const [saved, setSaved] = useState('');
  const pendingAttempt = useRef<{ fingerprint: string; key: string } | null>(null);

  const load = useCallback(() => {
    setState({ phase: 'loading' });
    setAction(null);
    setIssue('');
    setSaved('');
    pendingAttempt.current = null;
    setAttempt((value) => value + 1);
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    void client
      .listOrganizationIdentifiers(organizationId, { signal: controller.signal })
      .then((result) => {
        if (controller.signal.aborted) return;
        if (!result.ok) {
          setState({ issue: failureMessage(result), phase: 'failure' });
        } else if (!validIdentifierCollection(result.data, organizationId)) {
          setState({
            issue: 'The server response did not contain a valid identifier registry projection.',
            phase: 'failure',
          });
        } else {
          setState({ collection: result.data, phase: 'ready' });
        }
      });
    return () => controller.abort();
  }, [attempt, client, organizationId]);

  const beginCreate = () => {
    if (state.phase !== 'ready' || !state.collection.canCreate || busy) return;
    const firstType = state.collection.types[0];
    if (!firstType) return;
    setIssue('');
    setSaved('');
    pendingAttempt.current = null;
    setAction({ form: identifierDraft(null, firstType.key), kind: 'create' });
  };

  const beginEdit = (identifier: OrganizationIdentifier) => {
    if (!identifier.availableActions.includes('edit') || busy) return;
    setIssue('');
    setSaved('');
    pendingAttempt.current = null;
    setAction({
      form: identifierDraft(identifier, identifier.identifierType),
      identifier,
      kind: 'edit',
    });
  };

  const beginVerify = (identifier: OrganizationIdentifier) => {
    if (!identifier.availableActions.includes('verify') || busy) return;
    setIssue('');
    setSaved('');
    pendingAttempt.current = null;
    setAction({ evidenceReference: '', identifier, kind: 'verify', reason: '' });
  };

  const beginRevoke = (identifier: OrganizationIdentifier) => {
    if (!identifier.availableActions.includes('revoke') || busy) return;
    setIssue('');
    setSaved('');
    pendingAttempt.current = null;
    setAction({ identifier, kind: 'revoke', reason: '' });
  };

  const beginSupersede = (identifier: OrganizationIdentifier) => {
    if (state.phase !== 'ready' || !identifier.availableActions.includes('supersede') || busy) {
      return;
    }
    const firstReplacement = identifierReplacementCandidates(state.collection, identifier)[0];
    setIssue('');
    setSaved('');
    pendingAttempt.current = null;
    setAction({
      identifier,
      kind: 'supersede',
      reason: '',
      replacementId: firstReplacement?.identifierId ?? '',
    });
  };

  const cancelAction = () => {
    if (busy) return;
    setAction(null);
    setIssue('');
    pendingAttempt.current = null;
  };

  const submitAction = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!action || state.phase !== 'ready' || busy) return;
    setIssue('');
    setSaved('');

    let payload: OrganizationIdentifierWriteRequest | null = null;
    let reason: string;
    let evidenceReference = '';
    let replacement: OrganizationIdentifier | null = null;
    if (action.kind === 'create' || action.kind === 'edit') {
      const form = action.form;
      reason = form.reason.normalize('NFC').trim();
      const assigningAuthority = form.assigningAuthority.normalize('NFC').trim();
      const value = form.value.normalize('NFC').trim();
      const jurisdictionCountryCode = form.jurisdictionCountryCode.trim().toUpperCase();
      const typeKeys = new Set(state.collection.types.map((type) => type.key));
      if (!typeKeys.has(form.identifierType)) {
        setIssue('Select an active identifier type for this organization jurisdiction.');
        return;
      }
      if (!validIdentifierText(assigningAuthority, 2, 160)) {
        setIssue('Assigning authority must contain between 2 and 160 valid characters.');
        return;
      }
      if (!validIdentifierText(value, 1, 128)) {
        setIssue('Identifier value must contain between 1 and 128 valid characters.');
        return;
      }
      if (jurisdictionCountryCode && !/^[A-Z]{2}$/.test(jurisdictionCountryCode)) {
        setIssue('Jurisdiction must be a two-letter ISO country code.');
        return;
      }
      if (form.issueDate && form.expiryDate && form.expiryDate < form.issueDate) {
        setIssue('Expiry date cannot be before the issue date.');
        return;
      }
      const effectiveFromDate = new Date(form.effectiveFrom);
      const effectiveToDate = form.effectiveTo ? new Date(form.effectiveTo) : null;
      if (!form.effectiveFrom || !Number.isFinite(effectiveFromDate.getTime())) {
        setIssue('Effective from must contain a valid date and time.');
        return;
      }
      if (
        effectiveToDate &&
        (!Number.isFinite(effectiveToDate.getTime()) ||
          effectiveToDate.getTime() <= effectiveFromDate.getTime())
      ) {
        setIssue('Effective to must be later than effective from.');
        return;
      }
      payload = {
        assigningAuthority,
        effectiveFrom: effectiveFromDate.toISOString(),
        effectiveTo: effectiveToDate?.toISOString() ?? null,
        expiryDate: form.expiryDate || null,
        identifierType: form.identifierType,
        isPrimary: form.isPrimary,
        issueDate: form.issueDate || null,
        jurisdictionCountryCode: jurisdictionCountryCode || null,
        reason,
        value,
      };
    } else {
      reason = action.reason.normalize('NFC').trim();
      if (action.kind === 'verify') {
        evidenceReference = action.evidenceReference.normalize('NFC').trim();
        if (!validIdentifierText(evidenceReference, 1, 160)) {
          setIssue('Verification evidence must contain between 1 and 160 valid characters.');
          return;
        }
      } else if (action.kind === 'supersede') {
        replacement =
          identifierReplacementCandidates(state.collection, action.identifier).find(
            (candidate) => candidate.identifierId === action.replacementId,
          ) ?? null;
        if (!replacement) {
          setIssue('Select a current verified replacement of the same identifier type.');
          return;
        }
      }
    }
    if (!validIdentifierText(reason, 10, 500)) {
      setIssue('Reason must contain between 10 and 500 valid characters.');
      return;
    }

    const identifier = action.kind === 'create' ? null : action.identifier;
    const fingerprint = JSON.stringify([
      action.kind,
      identifier?.identifierId,
      identifier?.lockVersion,
      payload,
      evidenceReference,
      replacement?.identifierId,
      replacement?.lockVersion,
      reason,
    ]);
    if (pendingAttempt.current?.fingerprint !== fingerprint) {
      pendingAttempt.current = {
        fingerprint,
        key: `organization-identifier:${globalThis.crypto.randomUUID()}`,
      };
    }
    const idempotencyKey = pendingAttempt.current.key;
    setBusy(true);
    const result =
      action.kind === 'create'
        ? await client.createOrganizationIdentifier(
            organizationId,
            payload as OrganizationIdentifierWriteRequest,
            idempotencyKey,
          )
        : action.kind === 'edit'
          ? await client.updateOrganizationIdentifier(
              organizationId,
              action.identifier.identifierId,
              payload as OrganizationIdentifierWriteRequest,
              expectedIdentifierEtag(action.identifier),
              idempotencyKey,
            )
          : action.kind === 'verify'
            ? await client.verifyOrganizationIdentifier(
                organizationId,
                action.identifier.identifierId,
                { evidenceReference, reason },
                expectedIdentifierEtag(action.identifier),
                idempotencyKey,
              )
            : action.kind === 'revoke'
              ? await client.revokeOrganizationIdentifier(
                  organizationId,
                  action.identifier.identifierId,
                  { reason },
                  expectedIdentifierEtag(action.identifier),
                  idempotencyKey,
                )
              : await client.supersedeOrganizationIdentifier(
                  organizationId,
                  action.identifier.identifierId,
                  {
                    reason,
                    replacementEtag: expectedIdentifierEtag(replacement as OrganizationIdentifier),
                    replacementId: (replacement as OrganizationIdentifier).identifierId,
                  },
                  expectedIdentifierEtag(action.identifier),
                  idempotencyKey,
                );
    setBusy(false);
    if (!result.ok) {
      setIssue(
        result.status === 412
          ? 'This identifier changed after it was loaded. Reload the latest revision before continuing.'
          : failureMessage(result),
      );
      if (result.status > 0 && result.status < 500 && result.kind !== 'contract') {
        pendingAttempt.current = null;
      }
      return;
    }
    const typeKeys = new Set(state.collection.types.map((type) => type.key));
    if (
      !validIdentifier(result.data, typeKeys) ||
      result.etag !== expectedIdentifierEtag(result.data)
    ) {
      setIssue(
        'The mutation may have completed, but its response was invalid. Retry the same action to recover the idempotent result.',
      );
      return;
    }
    pendingAttempt.current = null;
    setState((current) => {
      if (current.phase !== 'ready') return current;
      const exists = current.collection.items.some(
        (item) => item.identifierId === result.data.identifierId,
      );
      const items = exists
        ? current.collection.items.map((item) =>
            item.identifierId === result.data.identifierId ? result.data : item,
          )
        : [result.data, ...current.collection.items];
      return {
        collection: { ...current.collection, items },
        phase: 'ready',
      };
    });
    setAction(null);
    setSaved(
      action.kind === 'create'
        ? 'Identifier created as a governed draft.'
        : action.kind === 'edit'
          ? 'Identifier draft updated.'
          : action.kind === 'verify'
            ? 'Identifier verified with authority evidence.'
            : action.kind === 'revoke'
              ? 'Identifier revoked with audit and outbox evidence.'
              : 'Identifier superseded by the verified replacement with immutable evidence.',
    );
    if (action.kind === 'supersede') {
      setState({ phase: 'loading' });
      setAttempt((value) => value + 1);
    }
  };

  return (
    <>
      <PageHeading id="M1-08" />
      {state.phase === 'loading' ? (
        <LoadingPanel />
      ) : state.phase === 'failure' ? (
        <FailurePanel issue={state.issue} onRetry={load} />
      ) : (
        <>
          <section className="panel">
            <div className="panel-heading">
              <div>
                <h2>Registration identifiers</h2>
                <p>
                  Governed values, assigning authorities, verification evidence, and effective
                  periods for this organization.
                </p>
              </div>
              {state.collection.canCreate && (
                <button className="primary-button" disabled={busy} onClick={beginCreate}>
                  Add identifier
                </button>
              )}
            </div>
            {saved && (
              <div className="alert success-alert" role="status">
                <Check size={18} aria-hidden="true" /> {saved}
              </div>
            )}
            {issue && !action && (
              <div className="alert error-alert" role="alert">
                <CircleAlert size={18} aria-hidden="true" />
                <div>
                  <strong>Identifier action was not confirmed</strong>
                  <span>{issue}</span>
                </div>
              </div>
            )}
            {state.collection.items.length === 0 ? (
              <div className="readiness-boundary" role="status">
                No identifiers are registered yet. Add a draft, then verify it with evidence to
                satisfy the applicable readiness rule.
              </div>
            ) : (
              <div className="identifier-card-list" aria-label="Organization identifiers">
                {state.collection.items.map((identifier) => (
                  <article className="identifier-card" key={identifier.identifierId}>
                    <div className="identifier-card-heading">
                      <div>
                        <span className="eyebrow">{identifier.typeDisplayName}</span>
                        <h3>{identifier.value}</h3>
                        <p>{identifier.assigningAuthority}</p>
                      </div>
                      <div className="identifier-badges">
                        {identifier.isPrimary && <span className="badge info">Primary</span>}
                        <span className={`badge ${identifierStatusBadge(identifier.status)}`}>
                          {identifier.status}
                        </span>
                      </div>
                    </div>
                    <dl className="identifier-details">
                      <div>
                        <dt>Jurisdiction</dt>
                        <dd>{identifier.jurisdictionCountryCode ?? 'Global'}</dd>
                      </div>
                      <div>
                        <dt>Verification</dt>
                        <dd>{identifier.verificationStatus}</dd>
                      </div>
                      <div>
                        <dt>Effective period</dt>
                        <dd>
                          {new Date(identifier.effectiveFrom).toLocaleString()} –{' '}
                          {identifier.effectiveTo
                            ? new Date(identifier.effectiveTo).toLocaleString()
                            : 'No end date'}
                        </dd>
                      </div>
                      <div>
                        <dt>Issue / expiry</dt>
                        <dd>
                          {identifier.issueDate ?? 'Not recorded'} /{' '}
                          {identifier.expiryDate ?? 'No expiry'}
                        </dd>
                      </div>
                      {identifier.evidenceReference && (
                        <div className="full-width">
                          <dt>Verification evidence</dt>
                          <dd>{identifier.evidenceReference}</dd>
                        </div>
                      )}
                      {identifier.supersedesId && (
                        <div className="full-width">
                          <dt>Supersedes</dt>
                          <dd>
                            {state.collection.items.find(
                              (candidate) => candidate.identifierId === identifier.supersedesId,
                            )?.value ?? identifier.supersedesId}
                          </dd>
                        </div>
                      )}
                    </dl>
                    {identifier.availableActions.length > 0 && (
                      <div className="identifier-card-actions" aria-label="Identifier actions">
                        {identifier.availableActions.includes('edit') && (
                          <button
                            className="text-action"
                            disabled={busy}
                            onClick={() => beginEdit(identifier)}
                          >
                            Edit draft
                          </button>
                        )}
                        {identifier.availableActions.includes('verify') && (
                          <button
                            className="text-action"
                            disabled={busy}
                            onClick={() => beginVerify(identifier)}
                          >
                            Verify
                          </button>
                        )}
                        {identifier.availableActions.includes('revoke') && (
                          <button
                            className="text-action danger-text-action"
                            disabled={busy}
                            onClick={() => beginRevoke(identifier)}
                          >
                            Revoke
                          </button>
                        )}
                        {identifier.availableActions.includes('supersede') && (
                          <button
                            className="text-action"
                            disabled={busy}
                            onClick={() => beginSupersede(identifier)}
                          >
                            Supersede
                          </button>
                        )}
                      </div>
                    )}
                  </article>
                ))}
              </div>
            )}
            {!state.collection.canCreate && (
              <div className="readiness-boundary identifier-read-only" role="note">
                You have read-only identifier access. Creation and lifecycle actions are projected
                only when current permissions and policy allow them.
              </div>
            )}
            <div className="form-actions">
              <button className="secondary-button" disabled={busy} onClick={load}>
                <RefreshCw size={17} aria-hidden="true" /> Reload latest
              </button>
            </div>
          </section>

          {action && (
            <section
              className="panel identifier-action-panel"
              aria-labelledby="identifier-action-heading"
            >
              <div className="panel-heading">
                <div>
                  <h2 id="identifier-action-heading">
                    {action.kind === 'create'
                      ? 'Add identifier'
                      : action.kind === 'edit'
                        ? 'Edit identifier draft'
                        : action.kind === 'verify'
                          ? 'Verify identifier'
                          : action.kind === 'revoke'
                            ? 'Revoke identifier'
                            : 'Supersede identifier'}
                  </h2>
                  <p>
                    {action.kind === 'verify'
                      ? 'Verification requires MFA and recent authentication within ten minutes.'
                      : action.kind === 'revoke'
                        ? 'Revocation is terminal. A required primary must have a verified replacement.'
                        : action.kind === 'supersede'
                          ? 'Supersession is atomic and immutable. Select a separately verified replacement of the same type.'
                          : 'New and edited values remain drafts until an authorized verifier records evidence.'}
                  </p>
                </div>
              </div>
              {issue && (
                <div className="alert error-alert" role="alert">
                  <CircleAlert size={18} aria-hidden="true" />
                  <div>
                    <strong>Identifier action was not confirmed</strong>
                    <span>{issue}</span>
                  </div>
                </div>
              )}
              <form onSubmit={(event) => void submitAction(event)}>
                {action.kind === 'create' || action.kind === 'edit' ? (
                  <div className="form-grid">
                    <label htmlFor="identifier-type">
                      Identifier type
                      <select
                        id="identifier-type"
                        required
                        disabled={busy}
                        value={action.form.identifierType}
                        onChange={(event) =>
                          setAction({
                            ...action,
                            form: { ...action.form, identifierType: event.target.value },
                          })
                        }
                      >
                        {state.collection.types.map((type) => (
                          <option key={type.key} value={type.key}>
                            {type.displayName}
                            {type.jurisdictionCountryCode
                              ? ` (${type.jurisdictionCountryCode})`
                              : ''}
                          </option>
                        ))}
                      </select>
                    </label>
                    <label htmlFor="identifier-authority">
                      Assigning authority
                      <input
                        id="identifier-authority"
                        maxLength={160}
                        minLength={2}
                        required
                        disabled={busy}
                        value={action.form.assigningAuthority}
                        onChange={(event) =>
                          setAction({
                            ...action,
                            form: { ...action.form, assigningAuthority: event.target.value },
                          })
                        }
                      />
                    </label>
                    <label htmlFor="identifier-value">
                      Identifier value
                      <input
                        id="identifier-value"
                        maxLength={128}
                        required
                        disabled={busy}
                        value={action.form.value}
                        onChange={(event) =>
                          setAction({
                            ...action,
                            form: { ...action.form, value: event.target.value },
                          })
                        }
                      />
                    </label>
                    <label htmlFor="identifier-jurisdiction">
                      Jurisdiction (optional)
                      <input
                        id="identifier-jurisdiction"
                        autoCapitalize="characters"
                        maxLength={2}
                        minLength={2}
                        pattern="[A-Za-z]{2}"
                        placeholder="IN"
                        disabled={busy}
                        value={action.form.jurisdictionCountryCode}
                        onChange={(event) =>
                          setAction({
                            ...action,
                            form: {
                              ...action.form,
                              jurisdictionCountryCode: event.target.value,
                            },
                          })
                        }
                      />
                    </label>
                    <label htmlFor="identifier-issue-date">
                      Issue date (optional)
                      <input
                        id="identifier-issue-date"
                        type="date"
                        disabled={busy}
                        value={action.form.issueDate}
                        onChange={(event) =>
                          setAction({
                            ...action,
                            form: { ...action.form, issueDate: event.target.value },
                          })
                        }
                      />
                    </label>
                    <label htmlFor="identifier-expiry-date">
                      Expiry date (optional)
                      <input
                        id="identifier-expiry-date"
                        type="date"
                        disabled={busy}
                        value={action.form.expiryDate}
                        onChange={(event) =>
                          setAction({
                            ...action,
                            form: { ...action.form, expiryDate: event.target.value },
                          })
                        }
                      />
                    </label>
                    <label htmlFor="identifier-effective-from">
                      Effective from
                      <input
                        id="identifier-effective-from"
                        type="datetime-local"
                        required
                        disabled={busy}
                        value={action.form.effectiveFrom}
                        onChange={(event) =>
                          setAction({
                            ...action,
                            form: { ...action.form, effectiveFrom: event.target.value },
                          })
                        }
                      />
                    </label>
                    <label htmlFor="identifier-effective-to">
                      Effective to (optional)
                      <input
                        id="identifier-effective-to"
                        type="datetime-local"
                        disabled={busy}
                        value={action.form.effectiveTo}
                        onChange={(event) =>
                          setAction({
                            ...action,
                            form: { ...action.form, effectiveTo: event.target.value },
                          })
                        }
                      />
                    </label>
                    <label
                      className="identifier-primary-choice full-width"
                      htmlFor="identifier-primary"
                    >
                      <input
                        id="identifier-primary"
                        type="checkbox"
                        disabled={busy}
                        checked={action.form.isPrimary}
                        onChange={(event) =>
                          setAction({
                            ...action,
                            form: { ...action.form, isPrimary: event.target.checked },
                          })
                        }
                      />
                      Use as the primary identifier for this type and effective period
                    </label>
                    <label className="full-width" htmlFor="identifier-reason">
                      Reason
                      <textarea
                        id="identifier-reason"
                        maxLength={500}
                        minLength={10}
                        required
                        disabled={busy}
                        value={action.form.reason}
                        onChange={(event) =>
                          setAction({
                            ...action,
                            form: { ...action.form, reason: event.target.value },
                          })
                        }
                      />
                    </label>
                  </div>
                ) : (
                  <div className="form-grid">
                    <div className="readiness-boundary full-width">
                      <strong>{action.identifier.typeDisplayName}</strong>
                      <br />
                      {action.identifier.value} · {action.identifier.assigningAuthority}
                    </div>
                    {action.kind === 'verify' && (
                      <label className="full-width" htmlFor="identifier-evidence">
                        Verification evidence reference
                        <input
                          id="identifier-evidence"
                          maxLength={160}
                          required
                          disabled={busy}
                          placeholder="Authority record, case, or document reference"
                          value={action.evidenceReference}
                          onChange={(event) =>
                            setAction({ ...action, evidenceReference: event.target.value })
                          }
                        />
                      </label>
                    )}
                    {action.kind === 'supersede' && (
                      <label className="full-width" htmlFor="identifier-replacement">
                        Verified replacement
                        <select
                          id="identifier-replacement"
                          required
                          disabled={busy}
                          value={action.replacementId}
                          onChange={(event) =>
                            setAction({ ...action, replacementId: event.target.value })
                          }
                        >
                          <option value="">Select a verified replacement</option>
                          {identifierReplacementCandidates(state.collection, action.identifier).map(
                            (candidate) => (
                              <option key={candidate.identifierId} value={candidate.identifierId}>
                                {candidate.value} â€” {candidate.assigningAuthority} (
                                {candidate.status})
                              </option>
                            ),
                          )}
                        </select>
                        {identifierReplacementCandidates(state.collection, action.identifier)
                          .length === 0 && (
                          <span className="field-guidance">
                            Add and verify a non-primary replacement before superseding this record.
                          </span>
                        )}
                      </label>
                    )}
                    <label className="full-width" htmlFor="identifier-lifecycle-reason">
                      Reason
                      <textarea
                        id="identifier-lifecycle-reason"
                        maxLength={500}
                        minLength={10}
                        required
                        disabled={busy}
                        value={action.reason}
                        onChange={(event) => setAction({ ...action, reason: event.target.value })}
                      />
                    </label>
                  </div>
                )}
                <div className="form-actions">
                  <button
                    className="secondary-button"
                    type="button"
                    disabled={busy}
                    onClick={cancelAction}
                  >
                    Cancel
                  </button>
                  <button className="primary-button" disabled={busy}>
                    {busy
                      ? 'Submitting...'
                      : action.kind === 'create'
                        ? 'Create draft'
                        : action.kind === 'edit'
                          ? 'Save draft'
                          : action.kind === 'verify'
                            ? 'Verify identifier'
                            : action.kind === 'revoke'
                              ? 'Revoke identifier'
                              : 'Supersede identifier'}
                  </button>
                </div>
              </form>
            </section>
          )}
        </>
      )}
    </>
  );
}

function addressDraft(address: OrganizationAddress | null): AddressDraftForm {
  return {
    addressLines: [
      address?.addressLines[0] ?? '',
      address?.addressLines[1] ?? '',
      address?.addressLines[2] ?? '',
      address?.addressLines[3] ?? '',
    ],
    addressType: address?.addressType ?? 'registered',
    countryCode: address?.countryCode ?? '',
    effectiveFrom: dateTimeInputValue(address?.effectiveFrom ?? new Date().toISOString()),
    effectiveTo: address?.effectiveTo ? dateTimeInputValue(address.effectiveTo) : '',
    isPrimary: address?.isPrimary ?? false,
    locality: address?.locality ?? '',
    postcode: address?.postcode ?? '',
    reason: '',
    region: address?.region ?? '',
    validationSource: address?.validationSource ?? '',
    validationStatus: address?.validationStatus ?? 'unvalidated',
  };
}

function contactDraft(contact: OrganizationContact | null, purpose: string): ContactDraftForm {
  return {
    channel: contact?.channel ?? 'email',
    effectiveFrom: dateTimeInputValue(contact?.effectiveFrom ?? new Date().toISOString()),
    effectiveTo: contact?.effectiveTo ? dateTimeInputValue(contact.effectiveTo) : '',
    isPreferred: contact?.isPreferred ?? false,
    isPrimary: contact?.isPrimary ?? false,
    purpose: contact?.purpose ?? purpose,
    reason: '',
    value: '',
  };
}

function effectiveRecordBadge(status: OrganizationAddress['status']) {
  if (status === 'active') return 'success';
  if (status === 'scheduled') return 'info';
  if (status === 'superseded') return 'warning';
  return 'neutral';
}

function AddressFields({
  busy,
  form,
  lockedIdentity,
  onChange,
}: {
  busy: boolean;
  form: AddressDraftForm;
  lockedIdentity: boolean;
  onChange: (form: AddressDraftForm) => void;
}) {
  const updateLine = (index: number, value: string) => {
    const lines: AddressDraftForm['addressLines'] = [...form.addressLines];
    lines[index] = value;
    onChange({ ...form, addressLines: lines });
  };
  return (
    <div className="form-grid">
      <label htmlFor="contact-address-type">
        Address type
        <select
          id="contact-address-type"
          disabled={busy || lockedIdentity}
          value={form.addressType}
          onChange={(event) =>
            onChange({
              ...form,
              addressType: event.target.value as AddressDraftForm['addressType'],
            })
          }
        >
          {addressTypes.map((type) => (
            <option key={type} value={type}>
              {type.charAt(0).toUpperCase() + type.slice(1)}
            </option>
          ))}
        </select>
      </label>
      <label htmlFor="contact-address-country">
        Country code
        <input
          id="contact-address-country"
          autoCapitalize="characters"
          maxLength={2}
          minLength={2}
          pattern="[A-Za-z]{2}"
          placeholder="IN"
          required
          disabled={busy}
          value={form.countryCode}
          onChange={(event) => onChange({ ...form, countryCode: event.target.value })}
        />
      </label>
      {form.addressLines.map((line, index) => (
        <label key={index} htmlFor={`contact-address-line-${index + 1}`}>
          Address line {index + 1}
          <input
            id={`contact-address-line-${index + 1}`}
            maxLength={120}
            required={index === 0}
            disabled={busy}
            value={line}
            onChange={(event) => updateLine(index, event.target.value)}
          />
        </label>
      ))}
      <label htmlFor="contact-address-locality">
        Locality
        <input
          id="contact-address-locality"
          maxLength={100}
          required
          disabled={busy}
          value={form.locality}
          onChange={(event) => onChange({ ...form, locality: event.target.value })}
        />
      </label>
      <label htmlFor="contact-address-region">
        Region
        <input
          id="contact-address-region"
          maxLength={100}
          required
          disabled={busy}
          value={form.region}
          onChange={(event) => onChange({ ...form, region: event.target.value })}
        />
      </label>
      <label htmlFor="contact-address-postcode">
        Postcode
        <input
          id="contact-address-postcode"
          maxLength={24}
          required
          disabled={busy}
          value={form.postcode}
          onChange={(event) => onChange({ ...form, postcode: event.target.value })}
        />
      </label>
      <label htmlFor="contact-address-validation-status">
        Validation status
        <select
          id="contact-address-validation-status"
          disabled={busy}
          value={form.validationStatus}
          onChange={(event) => {
            const validationStatus = event.target.value as AddressDraftForm['validationStatus'];
            onChange({
              ...form,
              validationSource: validationStatus === 'unvalidated' ? '' : form.validationSource,
              validationStatus,
            });
          }}
        >
          <option value="unvalidated">Unvalidated</option>
          <option value="validated">Validated</option>
        </select>
      </label>
      {form.validationStatus === 'validated' && (
        <label htmlFor="contact-address-validation-source">
          Validation source
          <input
            id="contact-address-validation-source"
            maxLength={160}
            minLength={2}
            required
            disabled={busy}
            value={form.validationSource}
            onChange={(event) => onChange({ ...form, validationSource: event.target.value })}
          />
        </label>
      )}
      <label htmlFor="contact-address-effective-from">
        Effective from
        <input
          id="contact-address-effective-from"
          type="datetime-local"
          required
          disabled={busy}
          value={form.effectiveFrom}
          onChange={(event) => onChange({ ...form, effectiveFrom: event.target.value })}
        />
      </label>
      <label htmlFor="contact-address-effective-to">
        Effective to (optional)
        <input
          id="contact-address-effective-to"
          type="datetime-local"
          disabled={busy}
          value={form.effectiveTo}
          onChange={(event) => onChange({ ...form, effectiveTo: event.target.value })}
        />
      </label>
      <label className="identifier-primary-choice full-width" htmlFor="contact-address-primary">
        <input
          id="contact-address-primary"
          type="checkbox"
          disabled={busy || lockedIdentity}
          checked={form.isPrimary}
          onChange={(event) => onChange({ ...form, isPrimary: event.target.checked })}
        />
        Primary address for this type and effective period
      </label>
      <label className="full-width" htmlFor="contact-address-reason">
        Reason
        <textarea
          id="contact-address-reason"
          maxLength={500}
          minLength={10}
          required
          disabled={busy}
          value={form.reason}
          onChange={(event) => onChange({ ...form, reason: event.target.value })}
        />
      </label>
    </div>
  );
}

function ContactFields({
  busy,
  form,
  lockedIdentity,
  onChange,
  purposes,
}: {
  busy: boolean;
  form: ContactDraftForm;
  lockedIdentity: boolean;
  onChange: (form: ContactDraftForm) => void;
  purposes: OrganizationContactCollection['purposes'];
}) {
  return (
    <div className="form-grid">
      <label htmlFor="organization-contact-channel">
        Channel
        <select
          id="organization-contact-channel"
          disabled={busy || lockedIdentity}
          value={form.channel}
          onChange={(event) =>
            onChange({
              ...form,
              channel: event.target.value as ContactDraftForm['channel'],
              value: '',
            })
          }
        >
          <option value="email">Email</option>
          <option value="phone">Phone</option>
          <option value="web">Web</option>
        </select>
      </label>
      <label htmlFor="organization-contact-purpose">
        Purpose
        <select
          id="organization-contact-purpose"
          disabled={busy || lockedIdentity}
          value={form.purpose}
          onChange={(event) => onChange({ ...form, purpose: event.target.value })}
        >
          {purposes.map((purpose) => (
            <option key={purpose.key} value={purpose.key}>
              {purpose.displayName}
            </option>
          ))}
        </select>
      </label>
      <label className="full-width" htmlFor="organization-contact-value">
        {form.channel === 'email'
          ? 'Email address'
          : form.channel === 'phone'
            ? 'E.164 phone number'
            : 'HTTPS URL'}
        <input
          id="organization-contact-value"
          type={form.channel === 'email' ? 'email' : form.channel === 'phone' ? 'tel' : 'url'}
          maxLength={form.channel === 'email' ? 254 : form.channel === 'phone' ? 32 : 2048}
          placeholder={
            form.channel === 'email'
              ? 'operations@example.org'
              : form.channel === 'phone'
                ? '+919876543210'
                : 'https://example.org/contact'
          }
          required
          disabled={busy}
          value={form.value}
          onChange={(event) => onChange({ ...form, value: event.target.value })}
        />
        {lockedIdentity && (
          <span className="field-guidance">
            The stored value is confidential and cannot be recovered. Enter the complete
            replacement.
          </span>
        )}
      </label>
      <label htmlFor="organization-contact-effective-from">
        Effective from
        <input
          id="organization-contact-effective-from"
          type="datetime-local"
          required
          disabled={busy}
          value={form.effectiveFrom}
          onChange={(event) => onChange({ ...form, effectiveFrom: event.target.value })}
        />
      </label>
      <label htmlFor="organization-contact-effective-to">
        Effective to (optional)
        <input
          id="organization-contact-effective-to"
          type="datetime-local"
          disabled={busy}
          value={form.effectiveTo}
          onChange={(event) => onChange({ ...form, effectiveTo: event.target.value })}
        />
      </label>
      <label
        className="identifier-primary-choice full-width"
        htmlFor="organization-contact-primary"
      >
        <input
          id="organization-contact-primary"
          type="checkbox"
          disabled={busy || lockedIdentity}
          checked={form.isPrimary}
          onChange={(event) =>
            onChange({
              ...form,
              isPreferred: event.target.checked ? form.isPreferred : false,
              isPrimary: event.target.checked,
            })
          }
        />
        Primary contact for this purpose, channel, and effective period
      </label>
      <label
        className="identifier-primary-choice full-width"
        htmlFor="organization-contact-preferred"
      >
        <input
          id="organization-contact-preferred"
          type="checkbox"
          disabled={busy || lockedIdentity || !form.isPrimary}
          checked={form.isPreferred}
          onChange={(event) => onChange({ ...form, isPreferred: event.target.checked })}
        />
        Preferred contact for this purpose
      </label>
      <label className="full-width" htmlFor="organization-contact-reason">
        Reason
        <textarea
          id="organization-contact-reason"
          maxLength={500}
          minLength={10}
          required
          disabled={busy}
          value={form.reason}
          onChange={(event) => onChange({ ...form, reason: event.target.value })}
        />
      </label>
    </div>
  );
}

function ContactDirectoryScreen({ client, organizationId }: AdministrationScreenProps) {
  const [attempt, setAttempt] = useState(0);
  const [state, setState] = useState<ContactDirectoryState>({ phase: 'loading' });
  const [action, setAction] = useState<ContactDirectoryAction | null>(null);
  const [busy, setBusy] = useState(false);
  const [issue, setIssue] = useState('');
  const [saved, setSaved] = useState('');
  const pendingAttempt = useRef<{ fingerprint: string; key: string } | null>(null);

  const load = useCallback(() => {
    setState({ phase: 'loading' });
    setAction(null);
    setIssue('');
    setSaved('');
    pendingAttempt.current = null;
    setAttempt((value) => value + 1);
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    void client
      .listOrganizationContacts(organizationId, { signal: controller.signal })
      .then((result) => {
        if (controller.signal.aborted) return;
        if (!result.ok) {
          setState({ issue: failureMessage(result), phase: 'failure' });
        } else if (!validContactCollection(result.data, organizationId)) {
          setState({
            issue:
              'The server response did not contain a valid masked address and contact projection.',
            phase: 'failure',
          });
        } else {
          setState({ collection: result.data, phase: 'ready' });
        }
      });
    return () => controller.abort();
  }, [attempt, client, organizationId]);

  const beginCreateAddress = () => {
    if (state.phase !== 'ready' || !state.collection.canCreate || busy) return;
    setIssue('');
    setSaved('');
    pendingAttempt.current = null;
    setAction({ form: addressDraft(null), kind: 'create-address' });
  };

  const beginCreateContact = () => {
    if (state.phase !== 'ready' || !state.collection.canCreate || busy) return;
    const purpose = state.collection.purposes[0];
    if (!purpose) return;
    setIssue('');
    setSaved('');
    pendingAttempt.current = null;
    setAction({ form: contactDraft(null, purpose.key), kind: 'create-contact' });
  };

  const beginAddressAction = (
    address: OrganizationAddress,
    kind: 'end-address' | 'supersede-address',
  ) => {
    const requiredAction = kind === 'end-address' ? 'end' : 'supersede';
    if (!address.availableActions.includes(requiredAction) || busy) return;
    setIssue('');
    setSaved('');
    pendingAttempt.current = null;
    setAction(
      kind === 'end-address'
        ? { address, kind, reason: '' }
        : { address, form: addressDraft(address), kind },
    );
  };

  const beginContactAction = (
    contact: OrganizationContact,
    kind: 'end-contact' | 'supersede-contact' | 'verify-contact',
  ) => {
    const requiredAction = kind.replace('-contact', '') as 'end' | 'supersede' | 'verify';
    if (!contact.availableActions.includes(requiredAction) || busy) return;
    setIssue('');
    setSaved('');
    pendingAttempt.current = null;
    setAction(
      kind === 'supersede-contact'
        ? { contact, form: contactDraft(contact, contact.purpose), kind }
        : { contact, kind, reason: '' },
    );
  };

  const cancelAction = () => {
    if (busy) return;
    setAction(null);
    setIssue('');
    pendingAttempt.current = null;
  };

  const submitAction = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!action || state.phase !== 'ready' || busy) return;
    setIssue('');
    setSaved('');
    let addressPayload: OrganizationAddressWriteRequest | null = null;
    let contactPayload: OrganizationContactWriteRequest | null = null;
    let reason: string;

    if (action.kind === 'create-address' || action.kind === 'supersede-address') {
      const form = action.form;
      const lines = form.addressLines
        .map((line) => line.normalize('NFC').trim())
        .filter((line) => line.length > 0);
      const locality = form.locality.normalize('NFC').trim();
      const region = form.region.normalize('NFC').trim();
      const postcode = form.postcode.normalize('NFC').trim();
      const countryCode = form.countryCode.trim().toUpperCase();
      const validationSource = form.validationSource.normalize('NFC').trim();
      reason = form.reason.normalize('NFC').trim();
      if (
        lines.length < 1 ||
        lines.length > 4 ||
        lines.some((line) => !validIdentifierText(line, 1, 120))
      ) {
        setIssue('Provide between one and four valid address lines of at most 120 characters.');
        return;
      }
      if (!validIdentifierText(locality, 1, 100) || !validIdentifierText(region, 1, 100)) {
        setIssue('Locality and region must each contain between 1 and 100 valid characters.');
        return;
      }
      if (!validIdentifierText(postcode, 1, 24)) {
        setIssue('Postcode must contain between 1 and 24 valid characters.');
        return;
      }
      if (!/^[A-Z]{2}$/.test(countryCode)) {
        setIssue('Country must be a two-letter ISO country code.');
        return;
      }
      if (form.validationStatus === 'validated' && !validIdentifierText(validationSource, 2, 160)) {
        setIssue('A validated address requires a validation source of 2 to 160 characters.');
        return;
      }
      const effectiveFrom = new Date(form.effectiveFrom);
      const effectiveTo = form.effectiveTo ? new Date(form.effectiveTo) : null;
      if (!form.effectiveFrom || !Number.isFinite(effectiveFrom.getTime())) {
        setIssue('Effective from must contain a valid date and time.');
        return;
      }
      if (
        effectiveTo &&
        (!Number.isFinite(effectiveTo.getTime()) || effectiveTo <= effectiveFrom)
      ) {
        setIssue('Effective to must be later than effective from.');
        return;
      }
      addressPayload = {
        addressLines: lines,
        addressType: form.addressType,
        countryCode,
        effectiveFrom: effectiveFrom.toISOString(),
        effectiveTo: effectiveTo?.toISOString() ?? null,
        isPrimary: form.isPrimary,
        locality,
        postcode,
        reason,
        region,
        validationSource: form.validationStatus === 'validated' ? validationSource : null,
        validationStatus: form.validationStatus,
      };
    } else if (action.kind === 'create-contact' || action.kind === 'supersede-contact') {
      const form = action.form;
      const value = form.value.normalize('NFC').trim();
      reason = form.reason.normalize('NFC').trim();
      if (!state.collection.purposes.some((purpose) => purpose.key === form.purpose)) {
        setIssue('Select an active contact purpose.');
        return;
      }
      if (form.isPreferred && !form.isPrimary) {
        setIssue('A preferred contact must also be primary.');
        return;
      }
      if (form.channel === 'email' && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value)) {
        setIssue('Enter a valid email address.');
        return;
      }
      if (form.channel === 'phone') {
        const normalizedPhone = value.replace(/[\s().-]/g, '');
        if (!/^\+[1-9][0-9]{1,14}$/.test(normalizedPhone)) {
          setIssue('Enter an E.164 phone number beginning with + and a country code.');
          return;
        }
      }
      if (form.channel === 'web') {
        try {
          const url = new URL(value);
          if (url.protocol !== 'https:' || !url.hostname || url.username || url.password) {
            throw new Error('invalid');
          }
        } catch {
          setIssue('Enter an HTTPS URL without embedded credentials.');
          return;
        }
      }
      if (value.length > (form.channel === 'email' ? 254 : form.channel === 'phone' ? 32 : 2048)) {
        setIssue('The contact value exceeds the approved limit for this channel.');
        return;
      }
      const effectiveFrom = new Date(form.effectiveFrom);
      const effectiveTo = form.effectiveTo ? new Date(form.effectiveTo) : null;
      if (!form.effectiveFrom || !Number.isFinite(effectiveFrom.getTime())) {
        setIssue('Effective from must contain a valid date and time.');
        return;
      }
      if (
        effectiveTo &&
        (!Number.isFinite(effectiveTo.getTime()) || effectiveTo <= effectiveFrom)
      ) {
        setIssue('Effective to must be later than effective from.');
        return;
      }
      contactPayload = {
        channel: form.channel,
        effectiveFrom: effectiveFrom.toISOString(),
        effectiveTo: effectiveTo?.toISOString() ?? null,
        isPreferred: form.isPreferred,
        isPrimary: form.isPrimary,
        purpose: form.purpose,
        reason,
        value,
      };
    } else {
      reason = action.reason.normalize('NFC').trim();
    }
    if (!validIdentifierText(reason, 10, 500)) {
      setIssue('Reason must contain between 10 and 500 valid characters.');
      return;
    }

    const record =
      'address' in action ? action.address : 'contact' in action ? action.contact : null;
    const fingerprint = JSON.stringify([
      action.kind,
      record && ('addressId' in record ? record.addressId : record.contactId),
      record?.lockVersion,
      addressPayload,
      contactPayload,
      reason,
    ]);
    if (pendingAttempt.current?.fingerprint !== fingerprint) {
      pendingAttempt.current = {
        fingerprint,
        key: `organization-contact:${globalThis.crypto.randomUUID()}`,
      };
    }
    const idempotencyKey = pendingAttempt.current.key;
    setBusy(true);
    const result =
      action.kind === 'create-address'
        ? await client.createOrganizationAddress(
            organizationId,
            addressPayload as OrganizationAddressWriteRequest,
            idempotencyKey,
          )
        : action.kind === 'supersede-address'
          ? await client.supersedeOrganizationAddress(
              organizationId,
              action.address.addressId,
              addressPayload as OrganizationAddressWriteRequest,
              expectedAddressEtag(action.address),
              idempotencyKey,
            )
          : action.kind === 'end-address'
            ? await client.endOrganizationAddress(
                organizationId,
                action.address.addressId,
                { reason },
                expectedAddressEtag(action.address),
                idempotencyKey,
              )
            : action.kind === 'create-contact'
              ? await client.createOrganizationContact(
                  organizationId,
                  contactPayload as OrganizationContactWriteRequest,
                  idempotencyKey,
                )
              : action.kind === 'verify-contact'
                ? await client.verifyOrganizationContact(
                    organizationId,
                    action.contact.contactId,
                    { reason },
                    expectedContactEtag(action.contact),
                    idempotencyKey,
                  )
                : action.kind === 'supersede-contact'
                  ? await client.supersedeOrganizationContact(
                      organizationId,
                      action.contact.contactId,
                      contactPayload as OrganizationContactWriteRequest,
                      expectedContactEtag(action.contact),
                      idempotencyKey,
                    )
                  : await client.endOrganizationContact(
                      organizationId,
                      action.contact.contactId,
                      { reason },
                      expectedContactEtag(action.contact),
                      idempotencyKey,
                    );
    setBusy(false);
    if (!result.ok) {
      setIssue(
        result.status === 412
          ? 'This record changed after it was loaded. Reload the latest revision before continuing.'
          : failureMessage(result),
      );
      if (result.status > 0 && result.status < 500 && result.kind !== 'contract') {
        pendingAttempt.current = null;
      }
      return;
    }
    const addressResult = action.kind.endsWith('-address');
    const purposeKeys = new Set(state.collection.purposes.map((purpose) => purpose.key));
    if (
      (addressResult &&
        (!validAddress(result.data) || result.etag !== expectedAddressEtag(result.data))) ||
      (!addressResult &&
        (!validContact(result.data, purposeKeys) ||
          result.etag !== expectedContactEtag(result.data)))
    ) {
      setIssue(
        'The mutation may have completed, but its masked revision response was invalid. Retry the same action to recover the idempotent result.',
      );
      return;
    }
    pendingAttempt.current = null;
    setAction(null);
    setSaved(
      action.kind === 'create-address'
        ? 'Address added with governed effective history.'
        : action.kind === 'supersede-address'
          ? 'Address superseded with immutable lineage.'
          : action.kind === 'end-address'
            ? 'Address ended without deleting its history.'
            : action.kind === 'create-contact'
              ? 'Contact added; only its masked projection is displayed.'
              : action.kind === 'verify-contact'
                ? 'Contact verification recorded.'
                : action.kind === 'supersede-contact'
                  ? 'Contact superseded; the replacement now requires verification.'
                  : 'Contact ended without deleting its history.',
    );
    setState({ phase: 'loading' });
    setAttempt((value) => value + 1);
  };

  return (
    <>
      <PageHeading id="M1-09" />
      {state.phase === 'loading' ? (
        <LoadingPanel />
      ) : state.phase === 'failure' ? (
        <FailurePanel issue={state.issue} onRetry={load} />
      ) : (
        <>
          <section className="panel">
            <div className="panel-heading contact-directory-heading">
              <div>
                <h2>Effective addresses and contacts</h2>
                <p>
                  Maintain effective structured addresses and verified contact channels. Contact
                  values remain confidential and are always masked in this projection.
                </p>
              </div>
              {state.collection.canCreate && (
                <div className="contact-directory-actions">
                  <button className="secondary-button" disabled={busy} onClick={beginCreateAddress}>
                    Add address
                  </button>
                  <button className="primary-button" disabled={busy} onClick={beginCreateContact}>
                    Add contact
                  </button>
                </div>
              )}
            </div>
            {saved && (
              <div className="alert success-alert" role="status">
                <Check size={18} aria-hidden="true" /> {saved}
              </div>
            )}
            {issue && !action && (
              <div className="alert error-alert" role="alert">
                <CircleAlert size={18} aria-hidden="true" />
                <div>
                  <strong>Address or contact action was not confirmed</strong>
                  <span>{issue}</span>
                </div>
              </div>
            )}
            <div className="contact-directory-section">
              <div className="contact-directory-section-heading">
                <div>
                  <span className="eyebrow">Structured records</span>
                  <h3>Addresses</h3>
                </div>
                <span className="badge neutral">{state.collection.addresses.length}</span>
              </div>
              {state.collection.addresses.length === 0 ? (
                <div className="readiness-boundary" role="status">
                  No addresses are recorded. A current registered address is required for readiness.
                </div>
              ) : (
                <div className="identifier-card-list" aria-label="Organization addresses">
                  {state.collection.addresses.map((address) => (
                    <article className="identifier-card" key={address.addressId}>
                      <div className="identifier-card-heading">
                        <div>
                          <span className="eyebrow">{address.addressType} address</span>
                          <h3>{address.addressLines.join(', ')}</h3>
                          <p>
                            {address.locality}, {address.region} {address.postcode} ·{' '}
                            {address.countryCode}
                          </p>
                        </div>
                        <div className="identifier-badges">
                          {address.isPrimary && <span className="badge info">Primary</span>}
                          <span className={`badge ${effectiveRecordBadge(address.status)}`}>
                            {address.status}
                          </span>
                        </div>
                      </div>
                      <dl className="identifier-details">
                        <div>
                          <dt>Validation</dt>
                          <dd>
                            {address.validationStatus}
                            {address.validationSource ? ` · ${address.validationSource}` : ''}
                          </dd>
                        </div>
                        <div>
                          <dt>Effective period</dt>
                          <dd>
                            {new Date(address.effectiveFrom).toLocaleString()} –{' '}
                            {address.effectiveTo
                              ? new Date(address.effectiveTo).toLocaleString()
                              : 'No end date'}
                          </dd>
                        </div>
                        {address.supersedesId && (
                          <div className="full-width">
                            <dt>Supersedes</dt>
                            <dd>{address.supersedesId}</dd>
                          </div>
                        )}
                      </dl>
                      {address.availableActions.length > 0 && (
                        <div className="identifier-card-actions" aria-label="Address actions">
                          {address.availableActions.includes('supersede') && (
                            <button
                              className="text-action"
                              disabled={busy}
                              onClick={() => beginAddressAction(address, 'supersede-address')}
                            >
                              Supersede
                            </button>
                          )}
                          {address.availableActions.includes('end') && (
                            <button
                              className="text-action danger-text-action"
                              disabled={busy}
                              onClick={() => beginAddressAction(address, 'end-address')}
                            >
                              End address
                            </button>
                          )}
                        </div>
                      )}
                    </article>
                  ))}
                </div>
              )}
            </div>
            <div className="contact-directory-section">
              <div className="contact-directory-section-heading">
                <div>
                  <span className="eyebrow">Confidential channels</span>
                  <h3>Contacts</h3>
                </div>
                <span className="badge neutral">{state.collection.contacts.length}</span>
              </div>
              {state.collection.contacts.length === 0 ? (
                <div className="readiness-boundary" role="status">
                  No contacts are recorded. Add and verify a primary operational contact for
                  readiness.
                </div>
              ) : (
                <div className="identifier-card-list" aria-label="Organization contacts">
                  {state.collection.contacts.map((contact) => (
                    <article className="identifier-card" key={contact.contactId}>
                      <div className="identifier-card-heading">
                        <div>
                          <span className="eyebrow">
                            {contact.purposeDisplayName} · {contact.channel}
                          </span>
                          <h3>{contact.maskedValue}</h3>
                          <p>{contact.verificationStatus}</p>
                        </div>
                        <div className="identifier-badges">
                          {contact.isPrimary && <span className="badge info">Primary</span>}
                          {contact.isPreferred && <span className="badge success">Preferred</span>}
                          <span className={`badge ${effectiveRecordBadge(contact.status)}`}>
                            {contact.status}
                          </span>
                        </div>
                      </div>
                      <dl className="identifier-details">
                        <div>
                          <dt>Effective period</dt>
                          <dd>
                            {new Date(contact.effectiveFrom).toLocaleString()} –{' '}
                            {contact.effectiveTo
                              ? new Date(contact.effectiveTo).toLocaleString()
                              : 'No end date'}
                          </dd>
                        </div>
                        <div>
                          <dt>Value handling</dt>
                          <dd>Confidential · masked projection</dd>
                        </div>
                        {contact.supersedesId && (
                          <div className="full-width">
                            <dt>Supersedes</dt>
                            <dd>{contact.supersedesId}</dd>
                          </div>
                        )}
                      </dl>
                      {contact.availableActions.length > 0 && (
                        <div className="identifier-card-actions" aria-label="Contact actions">
                          {contact.availableActions.includes('verify') && (
                            <button
                              className="text-action"
                              disabled={busy}
                              onClick={() => beginContactAction(contact, 'verify-contact')}
                            >
                              Verify
                            </button>
                          )}
                          {contact.availableActions.includes('supersede') && (
                            <button
                              className="text-action"
                              disabled={busy}
                              onClick={() => beginContactAction(contact, 'supersede-contact')}
                            >
                              Supersede
                            </button>
                          )}
                          {contact.availableActions.includes('end') && (
                            <button
                              className="text-action danger-text-action"
                              disabled={busy}
                              onClick={() => beginContactAction(contact, 'end-contact')}
                            >
                              End contact
                            </button>
                          )}
                        </div>
                      )}
                    </article>
                  ))}
                </div>
              )}
            </div>
            {!state.collection.canCreate && (
              <div className="readiness-boundary identifier-read-only" role="note">
                You have read-only address and contact access. Values remain masked, and lifecycle
                actions appear only when current permissions allow them.
              </div>
            )}
            <div className="form-actions">
              <button className="secondary-button" disabled={busy} onClick={load}>
                <RefreshCw size={17} aria-hidden="true" /> Reload latest
              </button>
            </div>
          </section>

          {action && (
            <section
              className="panel identifier-action-panel"
              aria-labelledby="contact-action-heading"
            >
              <div className="panel-heading">
                <div>
                  <h2 id="contact-action-heading">
                    {action.kind === 'create-address'
                      ? 'Add address'
                      : action.kind === 'supersede-address'
                        ? 'Supersede address'
                        : action.kind === 'end-address'
                          ? 'End address'
                          : action.kind === 'create-contact'
                            ? 'Add contact'
                            : action.kind === 'verify-contact'
                              ? 'Verify contact'
                              : action.kind === 'supersede-contact'
                                ? 'Supersede contact'
                                : 'End contact'}
                  </h2>
                  <p>
                    Supersession preserves immutable history. Future effective records remain
                    scheduled; contact replacements begin unverified.
                  </p>
                </div>
              </div>
              {issue && (
                <div className="alert error-alert" role="alert">
                  <CircleAlert size={18} aria-hidden="true" />
                  <div>
                    <strong>Address or contact action was not confirmed</strong>
                    <span>{issue}</span>
                  </div>
                </div>
              )}
              <form onSubmit={(event) => void submitAction(event)}>
                {action.kind === 'create-address' || action.kind === 'supersede-address' ? (
                  <AddressFields
                    busy={busy}
                    form={action.form}
                    lockedIdentity={action.kind === 'supersede-address'}
                    onChange={(form) => setAction({ ...action, form })}
                  />
                ) : action.kind === 'create-contact' || action.kind === 'supersede-contact' ? (
                  <ContactFields
                    busy={busy}
                    form={action.form}
                    lockedIdentity={action.kind === 'supersede-contact'}
                    purposes={state.collection.purposes}
                    onChange={(form) => setAction({ ...action, form })}
                  />
                ) : (
                  <div className="form-grid">
                    <div className="readiness-boundary full-width">
                      {'address' in action ? (
                        <>
                          <strong>{action.address.addressType} address</strong>
                          <br />
                          {action.address.addressLines.join(', ')}
                        </>
                      ) : (
                        <>
                          <strong>{action.contact.purposeDisplayName}</strong>
                          <br />
                          {action.contact.maskedValue} · {action.contact.channel}
                        </>
                      )}
                    </div>
                    <label className="full-width" htmlFor="contact-lifecycle-reason">
                      Reason
                      <textarea
                        id="contact-lifecycle-reason"
                        maxLength={500}
                        minLength={10}
                        required
                        disabled={busy}
                        value={action.reason}
                        onChange={(event) => setAction({ ...action, reason: event.target.value })}
                      />
                    </label>
                  </div>
                )}
                <div className="form-actions">
                  <button
                    className="secondary-button"
                    type="button"
                    disabled={busy}
                    onClick={cancelAction}
                  >
                    Cancel
                  </button>
                  <button className="primary-button" disabled={busy}>
                    {busy
                      ? 'Submitting...'
                      : action.kind === 'create-address'
                        ? 'Add address'
                        : action.kind === 'supersede-address'
                          ? 'Supersede address'
                          : action.kind === 'end-address'
                            ? 'End address'
                            : action.kind === 'create-contact'
                              ? 'Add contact'
                              : action.kind === 'verify-contact'
                                ? 'Verify contact'
                                : action.kind === 'supersede-contact'
                                  ? 'Supersede contact'
                                  : 'End contact'}
                  </button>
                </div>
              </form>
            </section>
          )}
        </>
      )}
    </>
  );
}

function ProfileScreen({ client, organizationId }: AdministrationScreenProps) {
  const [attempt, setAttempt] = useState(0);
  const [state, setState] = useState<ProfileState>({ phase: 'loading' });
  const [form, setForm] = useState<ProfileForm | null>(null);
  const [busy, setBusy] = useState(false);
  const [issue, setIssue] = useState('');
  const [saved, setSaved] = useState(false);
  const pendingAttempt = useRef<{ fingerprint: string; key: string } | null>(null);

  const load = useCallback(() => {
    setState({ phase: 'loading' });
    setIssue('');
    setSaved(false);
    pendingAttempt.current = null;
    setAttempt((value) => value + 1);
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    void client
      .getOrganizationProfile(organizationId, { signal: controller.signal })
      .then((result) => {
        if (controller.signal.aborted) return;
        if (!result.ok) {
          setState({ issue: failureMessage(result), phase: 'failure' });
        } else if (
          !validProfile(result.data, organizationId) ||
          result.etag !== expectedProfileEtag(result.data)
        ) {
          setState({
            issue: 'The server response omitted a valid profile or matching strong entity tag.',
            phase: 'failure',
          });
        } else {
          setState({ etag: result.etag, phase: 'ready', profile: result.data });
          setForm({
            countryCode: result.data.countryCode,
            displayName: result.data.displayName,
            legalName: result.data.legalName,
            locale: result.data.locale ?? '',
            organizationType: result.data.organizationType ?? '',
            reason: '',
            timezone: result.data.timezone,
            tradingName: result.data.tradingName,
          });
        }
      });
    return () => controller.abort();
  }, [attempt, client, organizationId]);

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (state.phase !== 'ready' || !state.profile.editable || !form || busy) return;
    setIssue('');
    setSaved(false);
    if (!organizationTypeKeys.has(form.organizationType)) {
      setIssue('Select an approved organization type before saving.');
      return;
    }
    const normalized: OrganizationProfileUpdateRequest = {
      countryCode: form.countryCode.trim().toUpperCase(),
      displayName: form.displayName.normalize('NFC').trim(),
      legalName: form.legalName.normalize('NFC').trim(),
      locale: form.locale.trim(),
      organizationType: form.organizationType as OrganizationType,
      reason: form.reason.normalize('NFC').trim(),
      timezone: form.timezone.trim(),
      tradingName: form.tradingName?.normalize('NFC').trim() || null,
    };
    const fingerprint = JSON.stringify([state.etag, normalized]);
    if (pendingAttempt.current?.fingerprint !== fingerprint) {
      pendingAttempt.current = {
        fingerprint,
        key: `organization-profile:${globalThis.crypto.randomUUID()}`,
      };
    }
    setBusy(true);
    const result = await client.updateOrganizationProfile(
      organizationId,
      normalized,
      state.etag,
      pendingAttempt.current.key,
    );
    setBusy(false);
    if (!result.ok) {
      setIssue(
        result.status === 412
          ? 'This profile changed after it was loaded. Reload the latest revision before saving.'
          : failureMessage(result),
      );
      if (result.status > 0 && result.status < 500 && result.kind !== 'contract') {
        pendingAttempt.current = null;
      }
      return;
    }
    if (
      !validProfile(result.data, organizationId) ||
      result.etag !== expectedProfileEtag(result.data)
    ) {
      setIssue(
        'The update may have completed, but its response was invalid. Retry the same save to recover the idempotent result.',
      );
      return;
    }
    pendingAttempt.current = null;
    setState({ etag: result.etag, phase: 'ready', profile: result.data });
    setForm({
      countryCode: result.data.countryCode,
      displayName: result.data.displayName,
      legalName: result.data.legalName,
      locale: result.data.locale ?? '',
      organizationType: result.data.organizationType ?? '',
      reason: '',
      timezone: result.data.timezone,
      tradingName: result.data.tradingName,
    });
    setSaved(true);
  };

  return (
    <>
      <PageHeading id="M1-07" />
      {state.phase === 'loading' ? (
        <LoadingPanel />
      ) : state.phase === 'failure' ? (
        <FailurePanel issue={state.issue} onRetry={load} />
      ) : !form ? (
        <FailurePanel issue="The profile form could not be initialized." onRetry={load} />
      ) : (
        <section className="panel">
          <div className="panel-heading">
            <div>
              <h2>Organization identity</h2>
              <p>
                Revision {state.profile.lockVersion} · Last updated{' '}
                {new Date(state.profile.updatedAt).toLocaleString()}
              </p>
            </div>
            <span className="badge neutral">{state.profile.lifecycleStatus}</span>
          </div>
          {saved && (
            <div className="alert success-alert" role="status">
              <Check size={18} aria-hidden="true" /> Organization profile saved with audit and
              outbox evidence.
            </div>
          )}
          {issue && (
            <div className="alert error-alert" role="alert">
              <CircleAlert size={18} aria-hidden="true" />
              <div>
                <strong>Profile was not confirmed as saved</strong>
                <span>{issue}</span>
              </div>
            </div>
          )}
          {!state.profile.editable && (
            <div className="readiness-boundary" role="note">
              You have read-only profile access. A current organization owner, administrator, or
              configuration editor must make changes.
            </div>
          )}
          <form onSubmit={(event) => void submit(event)}>
            <div className="form-grid">
              <label htmlFor="organization-legal-name">
                Legal name
                <input
                  id="organization-legal-name"
                  maxLength={200}
                  minLength={2}
                  required
                  readOnly={!state.profile.editable || busy}
                  value={form.legalName}
                  onChange={(event) => setForm({ ...form, legalName: event.target.value })}
                />
              </label>
              <label htmlFor="organization-display-name">
                Display name
                <input
                  id="organization-display-name"
                  maxLength={120}
                  minLength={2}
                  required
                  readOnly={!state.profile.editable || busy}
                  value={form.displayName}
                  onChange={(event) => setForm({ ...form, displayName: event.target.value })}
                />
              </label>
              <label htmlFor="organization-trading-name">
                Trading name (optional)
                <input
                  id="organization-trading-name"
                  maxLength={160}
                  minLength={2}
                  readOnly={!state.profile.editable || busy}
                  value={form.tradingName ?? ''}
                  onChange={(event) => setForm({ ...form, tradingName: event.target.value })}
                />
              </label>
              <label htmlFor="organization-type">
                Organization type
                <select
                  id="organization-type"
                  required
                  disabled={!state.profile.editable || busy}
                  value={form.organizationType}
                  onChange={(event) =>
                    setForm({
                      ...form,
                      organizationType: event.target.value as ProfileForm['organizationType'],
                    })
                  }
                >
                  <option value="">Select organization type</option>
                  {organizationTypes.map(([value, label]) => (
                    <option key={value} value={value}>
                      {label}
                    </option>
                  ))}
                </select>
              </label>
              <label htmlFor="organization-country-code">
                Country code
                <input
                  id="organization-country-code"
                  autoCapitalize="characters"
                  maxLength={2}
                  minLength={2}
                  pattern="[A-Za-z]{2}"
                  required
                  readOnly={!state.profile.editable || busy}
                  value={form.countryCode}
                  onChange={(event) => setForm({ ...form, countryCode: event.target.value })}
                />
              </label>
              <label htmlFor="organization-timezone">
                IANA timezone
                <input
                  id="organization-timezone"
                  maxLength={80}
                  required
                  readOnly={!state.profile.editable || busy}
                  value={form.timezone}
                  onChange={(event) => setForm({ ...form, timezone: event.target.value })}
                />
              </label>
              <label htmlFor="organization-locale">
                Locale
                <input
                  id="organization-locale"
                  maxLength={255}
                  minLength={2}
                  pattern="[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*"
                  placeholder="en-IN"
                  required
                  readOnly={!state.profile.editable || busy}
                  value={form.locale}
                  onChange={(event) => setForm({ ...form, locale: event.target.value })}
                />
              </label>
              {state.profile.editable && (
                <label className="full-width" htmlFor="organization-change-reason">
                  Reason for change
                  <textarea
                    id="organization-change-reason"
                    maxLength={500}
                    minLength={10}
                    required
                    disabled={busy}
                    value={form.reason}
                    onChange={(event) => setForm({ ...form, reason: event.target.value })}
                  />
                </label>
              )}
            </div>
            <div className="form-actions">
              <button className="secondary-button" type="button" disabled={busy} onClick={load}>
                Reload latest
              </button>
              {state.profile.editable && (
                <button className="primary-button" disabled={busy}>
                  {busy ? 'Saving...' : 'Save organization profile'}
                </button>
              )}
            </div>
          </form>
        </section>
      )}
    </>
  );
}

function InternationalSettingsScreen({ client, organizationId }: AdministrationScreenProps) {
  const [attempt, setAttempt] = useState(0);
  const [state, setState] = useState<
    | { phase: 'loading' }
    | { phase: 'failure'; issue: string }
    | { phase: 'ready'; data: OrganizationInternationalSettings; etag: string }
  >({ phase: 'loading' });
  const [form, setForm] = useState<InternationalSettingsScheduleRequest | null>(null);
  const [busy, setBusy] = useState(false);
  const [issue, setIssue] = useState('');
  const [saved, setSaved] = useState(false);
  const pendingAttempt = useRef<{ fingerprint: string; key: string } | null>(null);

  const reload = () => {
    setState({ phase: 'loading' });
    setIssue('');
    setSaved(false);
    pendingAttempt.current = null;
    setAttempt((value) => value + 1);
  };

  const populate = (data: OrganizationInternationalSettings) => {
    const current = data.versions.find(
      (version) => version.lifecycle === 'active' || version.lifecycle === 'default',
    )!;
    const tomorrow = new Date(Date.now() + 24 * 60 * 60 * 1000);
    tomorrow.setUTCSeconds(0, 0);
    setForm({
      countryCode: current.countryCode,
      currencyCode: current.currencyCode,
      effectiveFrom: tomorrow.toISOString(),
      language: current.language,
      locale: current.locale,
      reason: '',
      timezone: current.timezone,
      weekStart: current.weekStart,
    });
  };

  useEffect(() => {
    const controller = new AbortController();
    void client
      .getOrganizationInternationalSettings(organizationId, { signal: controller.signal })
      .then((result) => {
        if (controller.signal.aborted) return;
        if (!result.ok) {
          setState({ phase: 'failure', issue: failureMessage(result) });
        } else if (
          !validInternationalSettings(result.data, organizationId) ||
          result.etag !== expectedInternationalSettingsEtag(result.data)
        ) {
          setState({
            phase: 'failure',
            issue: 'The server response omitted a valid settings history or matching revision.',
          });
        } else {
          setState({ phase: 'ready', data: result.data, etag: result.etag });
          populate(result.data);
        }
      });
    return () => controller.abort();
  }, [attempt, client, organizationId]);

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (state.phase !== 'ready' || !state.data.canSchedule || !form || busy) return;
    const normalized: InternationalSettingsScheduleRequest = {
      ...form,
      countryCode: form.countryCode.trim().toUpperCase(),
      currencyCode: form.currencyCode.trim().toUpperCase(),
      language: form.language.trim(),
      locale: form.locale.trim(),
      reason: form.reason.normalize('NFC').trim(),
      timezone: form.timezone.trim(),
    };
    const fingerprint = JSON.stringify([state.etag, normalized]);
    if (pendingAttempt.current?.fingerprint !== fingerprint) {
      pendingAttempt.current = {
        fingerprint,
        key: `organization-settings:${globalThis.crypto.randomUUID()}`,
      };
    }
    setBusy(true);
    setIssue('');
    setSaved(false);
    const result = await client.scheduleOrganizationInternationalSettings(
      organizationId,
      normalized,
      state.etag,
      pendingAttempt.current.key,
    );
    setBusy(false);
    if (!result.ok) {
      setIssue(
        result.status === 412
          ? 'These settings changed after loading. Reload the latest revision before scheduling.'
          : failureMessage(result),
      );
      return;
    }
    if (
      !validInternationalSettings(result.data, organizationId) ||
      result.etag !== expectedInternationalSettingsEtag(result.data)
    ) {
      setIssue(
        'The schedule may have completed, but its response was invalid. Retry to recover it.',
      );
      return;
    }
    pendingAttempt.current = null;
    setState({ phase: 'ready', data: result.data, etag: result.etag });
    populate(result.data);
    setSaved(true);
  };

  return (
    <>
      <PageHeading id="M1-10" />
      {state.phase === 'loading' ? (
        <LoadingPanel />
      ) : state.phase === 'failure' ? (
        <FailurePanel issue={state.issue} onRetry={reload} />
      ) : (
        <>
          {issue && <div className="inline-alert error-alert">{issue}</div>}
          {saved && <div className="inline-alert success-alert">Settings version scheduled.</div>}
          <section className="content-panel">
            <div className="section-heading">
              <div>
                <span className="eyebrow">Effective history</span>
                <h2>Locale and scheduling defaults</h2>
              </div>
              <span className="status-badge">Revision {state.data.lockVersion}</span>
            </div>
            <div className="record-grid">
              {state.data.versions.map((version) => (
                <article className="record-card" key={version.settingsId ?? 'organization-default'}>
                  <div className="record-card-head">
                    <strong>{version.lifecycle}</strong>
                    <span className="status-badge">{version.source}</span>
                  </div>
                  <p>
                    {version.locale} · {version.timezone} · {version.currencyCode}
                  </p>
                  <p>
                    Week starts {version.weekStart.toLowerCase()} · effective{' '}
                    {new Date(version.effectiveFrom).toLocaleString()}
                  </p>
                  <small>
                    {version.formatPreview.sampleDate} · {version.formatPreview.sampleTime} ·{' '}
                    {version.formatPreview.sampleCurrency}
                  </small>
                </article>
              ))}
            </div>
          </section>
          <section className="content-panel">
            <div className="section-heading">
              <div>
                <span className="eyebrow">Schedule settings</span>
                <h2>Future international defaults</h2>
              </div>
            </div>
            {!state.data.canSchedule && (
              <div className="readiness-boundary">
                A future version is already scheduled. Its immutable history must take effect before
                another can be added.
              </div>
            )}
            {form && (
              <form onSubmit={(event) => void submit(event)}>
                <div className="form-grid">
                  {(['countryCode', 'timezone', 'locale', 'language', 'currencyCode'] as const).map(
                    (field) => (
                      <label key={field}>
                        {field}
                        <input
                          required
                          disabled={busy || !state.data.canSchedule}
                          value={form[field]}
                          onChange={(event) => setForm({ ...form, [field]: event.target.value })}
                        />
                      </label>
                    ),
                  )}
                  <label>
                    Week start
                    <select
                      disabled={busy || !state.data.canSchedule}
                      value={form.weekStart}
                      onChange={(event) =>
                        setForm({
                          ...form,
                          weekStart: event.target
                            .value as InternationalSettingsScheduleRequest['weekStart'],
                        })
                      }
                    >
                      {state.data.weekStarts.map((day) => (
                        <option key={day}>{day}</option>
                      ))}
                    </select>
                  </label>
                  <label>
                    Effective from
                    <input
                      type="datetime-local"
                      required
                      disabled={busy || !state.data.canSchedule}
                      value={form.effectiveFrom.slice(0, 16)}
                      onChange={(event) =>
                        setForm({
                          ...form,
                          effectiveFrom: new Date(event.target.value).toISOString(),
                        })
                      }
                    />
                  </label>
                  <label className="full-width">
                    Reason
                    <textarea
                      minLength={10}
                      maxLength={500}
                      required
                      disabled={busy || !state.data.canSchedule}
                      value={form.reason}
                      onChange={(event) => setForm({ ...form, reason: event.target.value })}
                    />
                  </label>
                </div>
                <div className="readiness-list">
                  {state.data.impactRules.map((rule) => (
                    <div className="readiness-row" key={rule.field}>
                      <strong>{rule.field}</strong>
                      <span>{rule.description}</span>
                    </div>
                  ))}
                </div>
                <div className="form-actions">
                  <button
                    className="secondary-button"
                    type="button"
                    disabled={busy}
                    onClick={reload}
                  >
                    Reload latest
                  </button>
                  <button className="primary-button" disabled={busy || !state.data.canSchedule}>
                    {busy ? 'Scheduling...' : 'Schedule settings'}
                  </button>
                </div>
              </form>
            )}
          </section>
        </>
      )}
    </>
  );
}

function validGovernanceDirectory(
  value: OrganizationGovernanceDirectory,
  organizationId: string,
): value is OrganizationGovernanceDirectory {
  return (
    value.organizationId === organizationId &&
    typeof value.canManage === 'boolean' &&
    instant(value.evaluatedAt) !== null &&
    value.responsibilityTypes?.join(',') === 'clinical,privacy,security,billing' &&
    Array.isArray(value.eligibleAssignees) &&
    value.eligibleAssignees.every(
      (assignee) =>
        uuidPattern.test(assignee.id) &&
        (assignee.type === 'membership' || assignee.type === 'external_contact') &&
        typeof assignee.display === 'string',
    ) &&
    Array.isArray(value.responsibilities) &&
    value.responsibilities.every(
      (responsibility) =>
        uuidPattern.test(responsibility.responsibilityId) &&
        value.responsibilityTypes.includes(responsibility.responsibilityType) &&
        (responsibility.escalationEmailMasked === null ||
          responsibility.escalationEmailMasked.includes('*')) &&
        (responsibility.escalationPhoneMasked === null ||
          responsibility.escalationPhoneMasked.includes('*')) &&
        (responsibility.escalationEmailMasked !== null ||
          responsibility.escalationPhoneMasked !== null) &&
        nonNegativeInteger(responsibility.lockVersion),
    )
  );
}

function GovernanceScreen({ client, organizationId }: AdministrationScreenProps) {
  const [attempt, setAttempt] = useState(0);
  const [state, setState] = useState<LoadState<OrganizationGovernanceDirectory>>({
    phase: 'loading',
  });
  const [form, setForm] = useState<GovernanceResponsibilityWriteRequest | null>(null);
  const [busy, setBusy] = useState(false);
  const [issue, setIssue] = useState('');

  useEffect(() => {
    const controller = new AbortController();
    void client
      .getOrganizationGovernanceDirectory(organizationId, { signal: controller.signal })
      .then((result) => {
        if (controller.signal.aborted) return;
        if (!result.ok) setState({ phase: 'failure', issue: failureMessage(result) });
        else if (!validGovernanceDirectory(result.data, organizationId))
          setState({
            phase: 'failure',
            issue: 'The server returned an invalid or unsafe governance projection.',
          });
        else {
          setState({ phase: 'ready', data: result.data });
          const assignee = result.data.eligibleAssignees[0];
          if (assignee)
            setForm({
              responsibilityType: 'clinical',
              membershipId: assignee.type === 'membership' ? assignee.id : null,
              externalContactId: assignee.type === 'external_contact' ? assignee.id : null,
              escalationEmail: '',
              escalationPhone: null,
              effectiveFrom: new Date().toISOString(),
              reason: '',
            });
        }
      });
    return () => controller.abort();
  }, [attempt, client, organizationId]);

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (state.phase !== 'ready' || !state.data.canManage || !form || busy) return;
    setBusy(true);
    setIssue('');
    const result = await client.createOrganizationGovernanceResponsibility(
      organizationId,
      {
        ...form,
        escalationEmail: form.escalationEmail?.trim().toLowerCase() || null,
        escalationPhone: form.escalationPhone?.trim() || null,
        reason: form.reason.trim(),
      },
      `organization-governance:${globalThis.crypto.randomUUID()}`,
    );
    setBusy(false);
    if (!result.ok) {
      setIssue(failureMessage(result));
      return;
    }
    if (!validGovernanceDirectory(result.data, organizationId)) {
      setIssue('The saved response did not preserve the confidential governance contract.');
      return;
    }
    setState({ phase: 'ready', data: result.data });
    setForm({ ...form, reason: '' });
  };

  return (
    <>
      <PageHeading id="M1-11" />
      {state.phase === 'loading' ? (
        <LoadingPanel />
      ) : state.phase === 'failure' ? (
        <FailurePanel
          issue={state.issue}
          onRetry={() => {
            setState({ phase: 'loading' });
            setAttempt((value) => value + 1);
          }}
        />
      ) : (
        <>
          {issue && <div className="inline-alert error-alert">{issue}</div>}
          <section className="content-panel">
            <div className="section-heading">
              <div>
                <span className="eyebrow">Required coverage</span>
                <h2>Governance responsibilities</h2>
              </div>
              <span className="status-badge">
                {
                  new Set(
                    state.data.responsibilities
                      .filter((item) => item.status === 'active')
                      .map((item) => item.responsibilityType),
                  ).size
                }{' '}
                / 4 active
              </span>
            </div>
            <div className="record-grid">
              {state.data.responsibilityTypes.map((type) => {
                const record = state.data.responsibilities.find(
                  (item) =>
                    item.responsibilityType === type &&
                    (item.status === 'active' || item.status === 'scheduled'),
                );
                return (
                  <article className="record-card" key={type}>
                    <div className="record-card-head">
                      <strong>{type}</strong>
                      <span className="status-badge">{record?.status ?? 'gap'}</span>
                    </div>
                    {record ? (
                      <>
                        <p>{record.assigneeDisplay}</p>
                        <small>
                          {record.escalationEmailMasked ?? record.escalationPhoneMasked} · revision{' '}
                          {record.lockVersion}
                        </small>
                      </>
                    ) : (
                      <p>Assign an eligible linked person or verified external contact.</p>
                    )}
                  </article>
                );
              })}
            </div>
          </section>
          {state.data.canManage && form && (
            <section className="content-panel">
              <div className="section-heading">
                <div>
                  <span className="eyebrow">Confidential assignment</span>
                  <h2>Assign responsibility</h2>
                </div>
              </div>
              <form onSubmit={(event) => void submit(event)}>
                <div className="form-grid">
                  <label>
                    Responsibility
                    <select
                      value={form.responsibilityType}
                      disabled={busy}
                      onChange={(event) =>
                        setForm({
                          ...form,
                          responsibilityType: event.target
                            .value as GovernanceResponsibilityWriteRequest['responsibilityType'],
                        })
                      }
                    >
                      {state.data.responsibilityTypes.map((type) => (
                        <option key={type}>{type}</option>
                      ))}
                    </select>
                  </label>
                  <label>
                    Eligible assignee
                    <select
                      value={form.membershipId ?? form.externalContactId ?? ''}
                      disabled={busy}
                      onChange={(event) => {
                        const assignee = state.data.eligibleAssignees.find(
                          (item) => item.id === event.target.value,
                        )!;
                        setForm({
                          ...form,
                          membershipId: assignee.type === 'membership' ? assignee.id : null,
                          externalContactId:
                            assignee.type === 'external_contact' ? assignee.id : null,
                        });
                      }}
                    >
                      {state.data.eligibleAssignees.map((assignee) => (
                        <option value={assignee.id} key={assignee.id}>
                          {assignee.display} · {assignee.type}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label>
                    Escalation email
                    <input
                      type="email"
                      required
                      disabled={busy}
                      value={form.escalationEmail ?? ''}
                      onChange={(event) =>
                        setForm({ ...form, escalationEmail: event.target.value })
                      }
                    />
                  </label>
                  <label>
                    Effective from
                    <input
                      type="datetime-local"
                      required
                      disabled={busy}
                      value={form.effectiveFrom.slice(0, 16)}
                      onChange={(event) =>
                        setForm({
                          ...form,
                          effectiveFrom: new Date(event.target.value).toISOString(),
                        })
                      }
                    />
                  </label>
                  <label className="full-width">
                    Reason
                    <textarea
                      required
                      minLength={10}
                      maxLength={500}
                      disabled={busy}
                      value={form.reason}
                      onChange={(event) => setForm({ ...form, reason: event.target.value })}
                    />
                  </label>
                </div>
                <div className="form-actions">
                  <button
                    className="primary-button"
                    disabled={busy || state.data.eligibleAssignees.length === 0}
                  >
                    {busy ? 'Assigning...' : 'Assign responsibility'}
                  </button>
                </div>
              </form>
            </section>
          )}
        </>
      )}
    </>
  );
}

function validFacilityDirectory(value: FacilityDirectory, organizationId: string) {
  return (
    value.organizationId === organizationId &&
    typeof value.canCreate === 'boolean' &&
    instant(value.evaluatedAt) !== null &&
    Array.isArray(value.facilityTypes) &&
    value.facilityTypes.every((type) => type.key.length > 0 && type.displayName.length > 0) &&
    Array.isArray(value.facilities) &&
    value.facilities.every(
      (facility) =>
        uuidPattern.test(facility.facilityId) &&
        /^[A-Z0-9][A-Z0-9_-]{1,31}$/.test(facility.facilityCode) &&
        ['draft', 'under_review', 'active', 'suspended', 'closed'].includes(facility.status) &&
        nonNegativeInteger(facility.lockVersion),
    )
  );
}

function validOrganizationUnitDirectory(
  value: OrganizationUnitDirectory,
  organizationId: string,
  facilityId: string,
) {
  const ids = new Set(value.units.map((unit) => unit.unitId));
  return (
    value.organizationId === organizationId &&
    value.facilityId === facilityId &&
    typeof value.canManage === 'boolean' &&
    typeof value.canManageLifecycle === 'boolean' &&
    instant(value.evaluatedAt) !== null &&
    value.units.every(
      (unit) =>
        uuidPattern.test(unit.unitId) &&
        (unit.parentId == null || ids.has(unit.parentId)) &&
        /^[A-Z0-9][A-Z0-9_-]{1,31}$/.test(unit.unitCode) &&
        ['department', 'unit'].includes(unit.unitType) &&
        ['draft', 'active', 'suspended', 'closed'].includes(unit.status) &&
        instant(unit.effectiveFrom) !== null &&
        (unit.effectiveTo == null || instant(unit.effectiveTo) !== null) &&
        nonNegativeInteger(unit.lockVersion),
    )
  );
}

function unitDepth(unitId: string, directory: OrganizationUnitDirectory) {
  const units = new Map(directory.units.map((unit) => [unit.unitId, unit]));
  const seen = new Set<string>();
  let current = units.get(unitId);
  let depth = 0;
  while (current?.parentId && depth < 8 && !seen.has(current.parentId)) {
    seen.add(current.parentId);
    depth += 1;
    current = units.get(current.parentId);
  }
  return depth;
}

function unitDescendsFrom(
  candidateId: string,
  ancestorId: string,
  directory: OrganizationUnitDirectory,
) {
  const units = new Map(directory.units.map((unit) => [unit.unitId, unit]));
  const seen = new Set<string>();
  let current = units.get(candidateId);
  while (current?.parentId && !seen.has(current.parentId)) {
    if (current.parentId === ancestorId) return true;
    seen.add(current.parentId);
    current = units.get(current.parentId);
  }
  return false;
}

function validServiceLocationDirectory(
  value: ServiceLocationDirectory,
  organizationId: string,
  facilityId: string,
) {
  const ids = new Set(value.locations.map((location) => location.locationId));
  return (
    value.organizationId === organizationId &&
    value.facilityId === facilityId &&
    typeof value.canManage === 'boolean' &&
    typeof value.canManageLifecycle === 'boolean' &&
    instant(value.evaluatedAt) !== null &&
    value.locations.every(
      (location) =>
        uuidPattern.test(location.locationId) &&
        (location.unitId == null || uuidPattern.test(location.unitId)) &&
        (location.parentId == null || ids.has(location.parentId)) &&
        /^[A-Z0-9][A-Z0-9_-]{1,31}$/.test(location.locationCode) &&
        ['physical', 'virtual'].includes(location.locationType) &&
        ((location.locationType === 'physical' &&
          location.addressId != null &&
          uuidPattern.test(location.addressId) &&
          location.virtualServiceType == null) ||
          (location.locationType === 'virtual' &&
            location.addressId == null &&
            typeof location.virtualServiceType === 'string' &&
            location.virtualServiceType.length > 0)) &&
        (location.capacity == null ||
          (Number.isInteger(location.capacity) &&
            location.capacity >= 1 &&
            location.capacity <= 100000)) &&
        ['draft', 'active', 'suspended', 'closed'].includes(location.status) &&
        instant(location.effectiveFrom) !== null &&
        (location.effectiveTo == null || instant(location.effectiveTo) !== null) &&
        instant(location.createdAt) !== null &&
        instant(location.updatedAt) !== null &&
        nonNegativeInteger(location.lockVersion),
    )
  );
}

function locationDepth(locationId: string, directory: ServiceLocationDirectory) {
  const locations = new Map(directory.locations.map((location) => [location.locationId, location]));
  const seen = new Set<string>();
  let current = locations.get(locationId);
  let depth = 0;
  while (current?.parentId && depth < 8 && !seen.has(current.parentId)) {
    seen.add(current.parentId);
    depth += 1;
    current = locations.get(current.parentId);
  }
  return depth;
}

function locationDescendsFrom(
  candidateId: string,
  ancestorId: string,
  directory: ServiceLocationDirectory,
) {
  const locations = new Map(directory.locations.map((location) => [location.locationId, location]));
  const seen = new Set<string>();
  let current = locations.get(candidateId);
  while (current?.parentId && !seen.has(current.parentId)) {
    if (current.parentId === ancestorId) return true;
    seen.add(current.parentId);
    current = locations.get(current.parentId);
  }
  return false;
}

function LocationScreen({ client, organizationId }: AdministrationScreenProps) {
  const now = new Date().toISOString();
  const [attempt, setAttempt] = useState(0);
  const [facilities, setFacilities] = useState<LoadState<FacilityDirectory>>({ phase: 'loading' });
  const [facilityId, setFacilityId] = useState('');
  const [directory, setDirectory] = useState<LoadState<ServiceLocationDirectory>>({
    phase: 'loading',
  });
  const [units, setUnits] = useState<LoadState<OrganizationUnitDirectory>>({
    phase: 'loading',
  });
  const [contacts, setContacts] = useState<ContactDirectoryState>({ phase: 'loading' });
  const [busy, setBusy] = useState(false);
  const [issue, setIssue] = useState('');
  const [editingLocationId, setEditingLocationId] = useState<string | null>(null);
  const [reparentingLocationId, setReparentingLocationId] = useState<string | null>(null);
  const [locationLifecycle, setLocationLifecycle] = useState<{
    action: 'activate' | 'suspend' | 'reactivate' | 'close';
    locationId: string;
  } | null>(null);
  const [locationLifecycleReason, setLocationLifecycleReason] = useState('');
  const [form, setForm] = useState<ServiceLocationCreateRequest>({
    unitId: null,
    parentId: null,
    addressId: null,
    locationCode: '',
    locationType: 'physical',
    name: '',
    virtualServiceType: null,
    capacity: null,
    accessibilityNotes: null,
    effectiveFrom: now,
    effectiveTo: null,
    reason: '',
  });
  const [editForm, setEditForm] = useState<ServiceLocationCreateRequest>(form);
  const [reparentForm, setReparentForm] = useState({
    parentId: null as string | null,
    effectiveFrom: now,
    reason: '',
  });
  useEffect(() => {
    const controller = new AbortController();
    void client
      .getFacilityDirectory(organizationId, {}, { signal: controller.signal })
      .then((result) => {
        if (controller.signal.aborted) return;
        if (!result.ok) setFacilities({ phase: 'failure', issue: failureMessage(result) });
        else if (!validFacilityDirectory(result.data, organizationId))
          setFacilities({
            phase: 'failure',
            issue: 'The server returned an invalid facility directory.',
          });
        else {
          setFacilities({ phase: 'ready', data: result.data });
          setFacilityId((current) =>
            result.data.facilities.some((facility) => facility.facilityId === current)
              ? current
              : (result.data.facilities[0]?.facilityId ?? ''),
          );
        }
      });
    return () => controller.abort();
  }, [attempt, client, organizationId]);
  useEffect(() => {
    const controller = new AbortController();
    void client
      .listOrganizationContacts(organizationId, { signal: controller.signal })
      .then((result) => {
        if (controller.signal.aborted) return;
        if (!result.ok) setContacts({ phase: 'failure', issue: failureMessage(result) });
        else if (!validContactCollection(result.data, organizationId))
          setContacts({
            phase: 'failure',
            issue: 'The server returned an invalid address directory.',
          });
        else setContacts({ phase: 'ready', collection: result.data });
      });
    return () => controller.abort();
  }, [attempt, client, organizationId]);
  useEffect(() => {
    if (!facilityId) return;
    const controller = new AbortController();
    void client
      .getServiceLocationDirectory(organizationId, facilityId, { signal: controller.signal })
      .then((result) => {
        if (controller.signal.aborted) return;
        if (!result.ok) setDirectory({ phase: 'failure', issue: failureMessage(result) });
        else if (!validServiceLocationDirectory(result.data, organizationId, facilityId))
          setDirectory({
            phase: 'failure',
            issue: 'The server returned an invalid location directory.',
          });
        else setDirectory({ phase: 'ready', data: result.data });
      });
    return () => controller.abort();
  }, [attempt, client, facilityId, organizationId]);
  useEffect(() => {
    if (!facilityId) return;
    const controller = new AbortController();
    void client
      .getOrganizationUnitDirectory(organizationId, facilityId, { signal: controller.signal })
      .then((result) => {
        if (controller.signal.aborted) return;
        if (!result.ok) setUnits({ phase: 'failure', issue: failureMessage(result) });
        else if (!validOrganizationUnitDirectory(result.data, organizationId, facilityId))
          setUnits({
            phase: 'failure',
            issue: 'The server returned an invalid organization-unit directory.',
          });
        else setUnits({ phase: 'ready', data: result.data });
      });
    return () => controller.abort();
  }, [attempt, client, facilityId, organizationId]);
  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!facilityId || directory.phase !== 'ready' || !directory.data.canManage || busy) return;
    setBusy(true);
    setIssue('');
    const physical = form.locationType === 'physical';
    const result = await client.createServiceLocationDraft(
      organizationId,
      facilityId,
      {
        ...form,
        addressId: physical ? form.addressId : null,
        virtualServiceType: physical ? null : form.virtualServiceType?.trim(),
        locationCode: form.locationCode.trim().toUpperCase(),
        name: form.name.trim(),
        accessibilityNotes: form.accessibilityNotes?.trim() || null,
        reason: form.reason.trim(),
      },
      `location-draft:${globalThis.crypto.randomUUID()}`,
    );
    setBusy(false);
    if (!result.ok) return setIssue(failureMessage(result));
    if (!validServiceLocationDirectory(result.data, organizationId, facilityId))
      return setIssue('The saved response did not preserve the location directory contract.');
    setDirectory({ phase: 'ready', data: result.data });
    setForm({ ...form, parentId: null, locationCode: '', name: '', reason: '' });
  };
  const submitEdit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!facilityId || directory.phase !== 'ready' || !editingLocationId || busy) return;
    const location = directory.data.locations.find(
      (candidate) => candidate.locationId === editingLocationId,
    );
    if (!location || location.status !== 'draft') return;
    const physical = editForm.locationType === 'physical';
    setBusy(true);
    setIssue('');
    const result = await client.updateServiceLocationDraft(
      organizationId,
      facilityId,
      location.locationId,
      {
        unitId: editForm.unitId,
        addressId: physical ? editForm.addressId : null,
        locationCode: editForm.locationCode.trim().toUpperCase(),
        locationType: editForm.locationType,
        name: editForm.name.trim(),
        virtualServiceType: physical ? null : editForm.virtualServiceType?.trim(),
        capacity: editForm.capacity,
        accessibilityNotes: editForm.accessibilityNotes?.trim() || null,
        effectiveFrom: editForm.effectiveFrom,
        effectiveTo: editForm.effectiveTo,
        reason: editForm.reason.trim(),
      },
      `"service-location:${location.locationId}:${location.lockVersion}"`,
      `location-update:${globalThis.crypto.randomUUID()}`,
    );
    setBusy(false);
    if (!result.ok) return setIssue(failureMessage(result));
    if (!validServiceLocationDirectory(result.data, organizationId, facilityId))
      return setIssue('The saved response did not preserve the location directory contract.');
    setDirectory({ phase: 'ready', data: result.data });
    setEditingLocationId(null);
  };
  const submitReparent = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!facilityId || directory.phase !== 'ready' || !reparentingLocationId || busy) return;
    const location = directory.data.locations.find(
      (candidate) => candidate.locationId === reparentingLocationId,
    );
    if (!location || location.status !== 'draft') return;
    setBusy(true);
    setIssue('');
    const result = await client.reparentServiceLocation(
      organizationId,
      facilityId,
      location.locationId,
      { ...reparentForm, reason: reparentForm.reason.trim() },
      `"service-location:${location.locationId}:${location.lockVersion}"`,
      `location-reparent:${globalThis.crypto.randomUUID()}`,
    );
    setBusy(false);
    if (!result.ok) return setIssue(failureMessage(result));
    if (!validServiceLocationDirectory(result.data, organizationId, facilityId))
      return setIssue('The saved response did not preserve the location directory contract.');
    setDirectory({ phase: 'ready', data: result.data });
    setReparentingLocationId(null);
  };
  const submitLocationLifecycle = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!facilityId || directory.phase !== 'ready' || !locationLifecycle || busy) return;
    const location = directory.data.locations.find(
      (candidate) => candidate.locationId === locationLifecycle.locationId,
    );
    if (!location || !directory.data.canManageLifecycle) return;
    const etag = `"service-location:${location.locationId}:${location.lockVersion}"`;
    const key = `location-${locationLifecycle.action}:${globalThis.crypto.randomUUID()}`;
    const reason = locationLifecycleReason.trim();
    setBusy(true);
    setIssue('');
    const result =
      locationLifecycle.action === 'activate'
        ? await client.activateServiceLocation(
            organizationId,
            facilityId,
            location.locationId,
            { reason },
            etag,
            key,
          )
        : locationLifecycle.action === 'suspend'
          ? await client.suspendServiceLocation(
              organizationId,
              facilityId,
              location.locationId,
              { reason },
              etag,
              key,
            )
          : locationLifecycle.action === 'reactivate'
            ? await client.reactivateServiceLocation(
                organizationId,
                facilityId,
                location.locationId,
                { reason },
                etag,
                key,
              )
            : await client.closeServiceLocation(
                organizationId,
                facilityId,
                location.locationId,
                { effectiveTo: new Date().toISOString(), reason },
                etag,
                key,
              );
    setBusy(false);
    if (!result.ok) return setIssue(failureMessage(result));
    if (!validServiceLocationDirectory(result.data, organizationId, facilityId))
      return setIssue('The saved response did not preserve the location directory contract.');
    setDirectory({ phase: 'ready', data: result.data });
    setLocationLifecycle(null);
    setLocationLifecycleReason('');
  };
  const currentAddresses =
    contacts.phase === 'ready'
      ? contacts.collection.addresses.filter((address) => {
          const effectiveFrom = instant(address.effectiveFrom);
          const effectiveTo = address.effectiveTo == null ? null : instant(address.effectiveTo);
          const currentTime = applicationStartedAt;
          return (
            address.status === 'active' &&
            effectiveFrom !== null &&
            effectiveFrom <= currentTime &&
            (effectiveTo === null || effectiveTo > currentTime)
          );
        })
      : [];
  const selectableUnits =
    units.phase === 'ready' ? units.data.units.filter((unit) => unit.status !== 'closed') : [];
  return (
    <>
      <PageHeading id="M1-15" />
      {facilities.phase === 'loading' ? (
        <LoadingPanel />
      ) : facilities.phase === 'failure' ? (
        <FailurePanel issue={facilities.issue} onRetry={() => setAttempt((value) => value + 1)} />
      ) : facilities.data.facilities.length === 0 ? (
        <section className="content-panel">
          <h2>No facility available</h2>
          <p>Create a facility before defining service locations.</p>
        </section>
      ) : (
        <>
          <section className="content-panel">
            <div className="section-heading">
              <div>
                <span className="eyebrow">Care network</span>
                <h2>Service locations</h2>
              </div>
            </div>
            <label>
              Facility
              <select
                value={facilityId}
                onChange={(event) => {
                  setDirectory({ phase: 'loading' });
                  setEditingLocationId(null);
                  setReparentingLocationId(null);
                  setLocationLifecycle(null);
                  setUnits({ phase: 'loading' });
                  setForm((current) => ({ ...current, unitId: null, parentId: null }));
                  setFacilityId(event.target.value);
                }}
              >
                {facilities.data.facilities.map((facility) => (
                  <option key={facility.facilityId} value={facility.facilityId}>
                    {facility.displayName} ({facility.status})
                  </option>
                ))}
              </select>
            </label>
          </section>
          {directory.phase === 'loading' ? (
            <LoadingPanel />
          ) : directory.phase === 'failure' ? (
            <FailurePanel
              issue={directory.issue}
              onRetry={() => {
                setDirectory({ phase: 'loading' });
                setAttempt((value) => value + 1);
              }}
            />
          ) : (
            <>
              {issue && <div className="inline-alert error-alert">{issue}</div>}
              {units.phase === 'failure' && (
                <div className="inline-alert error-alert">{units.issue}</div>
              )}
              {contacts.phase === 'failure' && (
                <div className="inline-alert error-alert">{contacts.issue}</div>
              )}
              {directory.data.canManage &&
                units.phase === 'ready' &&
                units.data.units.length === 0 && (
                  <div className="readiness-boundary" role="status">
                    No organization units are available. Locations may still be created at the
                    facility root.
                  </div>
                )}
              {directory.data.canManage &&
                contacts.phase === 'ready' &&
                currentAddresses.length === 0 && (
                  <div className="readiness-boundary" role="status">
                    No current address is available for a physical location. Add or activate an
                    address in M1-09, or create a virtual location.
                  </div>
                )}
              <section className="content-panel">
                <div className="section-heading">
                  <h2>Location directory</h2>
                  <span className="status-badge">{directory.data.locations.length} records</span>
                </div>
                {directory.data.locations.length === 0 ? (
                  <p>No service locations have been defined.</p>
                ) : (
                  <div className="record-list">
                    {directory.data.locations.map((location) => (
                      <article
                        className="record-card"
                        key={location.locationId}
                        style={{
                          marginLeft: `${locationDepth(location.locationId, directory.data) * 20}px`,
                        }}
                      >
                        <div>
                          <strong>{location.name}</strong>
                          <p>
                            {location.locationCode} · {location.locationType} · {location.status}
                          </p>
                        </div>
                        <div className="record-actions">
                          <span className="status-badge">
                            {location.capacity == null
                              ? 'No capacity'
                              : `Capacity ${location.capacity}`}
                          </span>
                          {directory.data.canManage && location.status === 'draft' && (
                            <>
                              <button
                                type="button"
                                onClick={() => {
                                  setEditingLocationId(location.locationId);
                                  setReparentingLocationId(null);
                                  setEditForm({
                                    unitId: location.unitId ?? null,
                                    parentId: location.parentId ?? null,
                                    addressId: location.addressId ?? null,
                                    locationCode: location.locationCode,
                                    locationType: location.locationType,
                                    name: location.name,
                                    virtualServiceType: location.virtualServiceType ?? null,
                                    capacity: location.capacity ?? null,
                                    accessibilityNotes: location.accessibilityNotes ?? null,
                                    effectiveFrom: location.effectiveFrom,
                                    effectiveTo: location.effectiveTo ?? null,
                                    reason: '',
                                  });
                                }}
                              >
                                Edit draft
                              </button>
                              <button
                                type="button"
                                onClick={() => {
                                  setReparentingLocationId(location.locationId);
                                  setEditingLocationId(null);
                                  setReparentForm({
                                    parentId: location.parentId ?? null,
                                    effectiveFrom: new Date().toISOString(),
                                    reason: '',
                                  });
                                }}
                              >
                                Change parent
                              </button>
                            </>
                          )}
                          {directory.data.canManageLifecycle && location.status === 'draft' && (
                            <button
                              type="button"
                              onClick={() =>
                                setLocationLifecycle({
                                  action: 'activate',
                                  locationId: location.locationId,
                                })
                              }
                            >
                              Activate
                            </button>
                          )}
                          {directory.data.canManageLifecycle && location.status === 'active' && (
                            <>
                              <button
                                type="button"
                                onClick={() =>
                                  setLocationLifecycle({
                                    action: 'suspend',
                                    locationId: location.locationId,
                                  })
                                }
                              >
                                Suspend
                              </button>
                              <button
                                type="button"
                                onClick={() =>
                                  setLocationLifecycle({
                                    action: 'close',
                                    locationId: location.locationId,
                                  })
                                }
                              >
                                Close
                              </button>
                            </>
                          )}
                          {directory.data.canManageLifecycle && location.status === 'suspended' && (
                            <>
                              <button
                                type="button"
                                onClick={() =>
                                  setLocationLifecycle({
                                    action: 'reactivate',
                                    locationId: location.locationId,
                                  })
                                }
                              >
                                Reactivate
                              </button>
                              <button
                                type="button"
                                onClick={() =>
                                  setLocationLifecycle({
                                    action: 'close',
                                    locationId: location.locationId,
                                  })
                                }
                              >
                                Close
                              </button>
                            </>
                          )}
                        </div>
                      </article>
                    ))}
                  </div>
                )}
              </section>
              {editingLocationId && (
                <section className="content-panel">
                  <h2>Edit location draft</h2>
                  <form onSubmit={(event) => void submitEdit(event)}>
                    <div className="form-grid">
                      <label>
                        Edit location code
                        <input
                          required
                          pattern="[A-Z0-9][A-Z0-9_-]{1,31}"
                          value={editForm.locationCode}
                          onChange={(event) =>
                            setEditForm({
                              ...editForm,
                              locationCode: event.target.value.toUpperCase(),
                            })
                          }
                        />
                      </label>
                      <label>
                        Edit type
                        <select
                          value={editForm.locationType}
                          onChange={(event) =>
                            setEditForm({
                              ...editForm,
                              locationType: event.target.value as 'physical' | 'virtual',
                              addressId: null,
                              virtualServiceType: null,
                            })
                          }
                        >
                          <option value="physical">Physical</option>
                          <option value="virtual">Virtual</option>
                        </select>
                      </label>
                      <label>
                        Edit name
                        <input
                          required
                          minLength={2}
                          maxLength={120}
                          value={editForm.name}
                          onChange={(event) =>
                            setEditForm({ ...editForm, name: event.target.value })
                          }
                        />
                      </label>
                      <label>
                        Edit organization unit (optional)
                        <select
                          disabled={units.phase !== 'ready'}
                          value={editForm.unitId ?? ''}
                          onChange={(event) =>
                            setEditForm({ ...editForm, unitId: event.target.value || null })
                          }
                        >
                          <option value="">No organization unit</option>
                          {selectableUnits.map((unit) => (
                            <option key={unit.unitId} value={unit.unitId}>
                              {unit.name} ({unit.unitCode}, {unit.status})
                            </option>
                          ))}
                        </select>
                      </label>
                      {editForm.locationType === 'physical' ? (
                        <label>
                          Edit physical address
                          <select
                            required
                            disabled={contacts.phase !== 'ready'}
                            value={editForm.addressId ?? ''}
                            onChange={(event) =>
                              setEditForm({ ...editForm, addressId: event.target.value || null })
                            }
                          >
                            <option value="">Select a current address</option>
                            {currentAddresses.map((address) => (
                              <option key={address.addressId} value={address.addressId}>
                                {address.addressType}: {address.addressLines.join(', ')},{' '}
                                {address.locality}
                              </option>
                            ))}
                          </select>
                        </label>
                      ) : (
                        <label>
                          Edit virtual service type
                          <input
                            required
                            minLength={2}
                            maxLength={80}
                            value={editForm.virtualServiceType ?? ''}
                            onChange={(event) =>
                              setEditForm({ ...editForm, virtualServiceType: event.target.value })
                            }
                          />
                        </label>
                      )}
                      <label>
                        Edit capacity (optional)
                        <input
                          type="number"
                          min={1}
                          max={100000}
                          value={editForm.capacity ?? ''}
                          onChange={(event) =>
                            setEditForm({
                              ...editForm,
                              capacity: event.target.value ? Number(event.target.value) : null,
                            })
                          }
                        />
                      </label>
                      <label>
                        Edit effective from
                        <input
                          required
                          type="datetime-local"
                          value={editForm.effectiveFrom.slice(0, 16)}
                          onChange={(event) =>
                            setEditForm({
                              ...editForm,
                              effectiveFrom: new Date(event.target.value).toISOString(),
                            })
                          }
                        />
                      </label>
                      <label className="full-width">
                        Edit accessibility notes
                        <textarea
                          maxLength={500}
                          value={editForm.accessibilityNotes ?? ''}
                          onChange={(event) =>
                            setEditForm({
                              ...editForm,
                              accessibilityNotes: event.target.value || null,
                            })
                          }
                        />
                      </label>
                      <label className="full-width">
                        Edit reason
                        <textarea
                          required
                          minLength={10}
                          maxLength={500}
                          value={editForm.reason}
                          onChange={(event) =>
                            setEditForm({ ...editForm, reason: event.target.value })
                          }
                        />
                      </label>
                    </div>
                    <div className="form-actions">
                      <button className="primary-button" disabled={busy}>
                        {busy ? 'Saving...' : 'Save location draft'}
                      </button>
                      <button type="button" onClick={() => setEditingLocationId(null)}>
                        Cancel
                      </button>
                    </div>
                  </form>
                </section>
              )}
              {reparentingLocationId && (
                <section className="content-panel">
                  <h2>Change location parent</h2>
                  <form onSubmit={(event) => void submitReparent(event)}>
                    <div className="form-grid">
                      <label>
                        New location parent
                        <select
                          value={reparentForm.parentId ?? ''}
                          onChange={(event) =>
                            setReparentForm({
                              ...reparentForm,
                              parentId: event.target.value || null,
                            })
                          }
                        >
                          <option value="">No parent</option>
                          {directory.data.locations
                            .filter(
                              (location) =>
                                location.status === 'draft' &&
                                location.locationId !== reparentingLocationId &&
                                !locationDescendsFrom(
                                  location.locationId,
                                  reparentingLocationId,
                                  directory.data,
                                ),
                            )
                            .map((location) => (
                              <option key={location.locationId} value={location.locationId}>
                                {location.name}
                              </option>
                            ))}
                        </select>
                      </label>
                      <label>
                        Parent change effective from
                        <input
                          required
                          type="datetime-local"
                          value={reparentForm.effectiveFrom.slice(0, 16)}
                          onChange={(event) =>
                            setReparentForm({
                              ...reparentForm,
                              effectiveFrom: new Date(event.target.value).toISOString(),
                            })
                          }
                        />
                      </label>
                      <label className="full-width">
                        Parent change reason
                        <textarea
                          required
                          minLength={10}
                          maxLength={500}
                          value={reparentForm.reason}
                          onChange={(event) =>
                            setReparentForm({ ...reparentForm, reason: event.target.value })
                          }
                        />
                      </label>
                    </div>
                    <div className="form-actions">
                      <button className="primary-button" disabled={busy}>
                        {busy ? 'Saving...' : 'Save location parent'}
                      </button>
                      <button type="button" onClick={() => setReparentingLocationId(null)}>
                        Cancel
                      </button>
                    </div>
                  </form>
                </section>
              )}
              {locationLifecycle && (
                <section className="content-panel">
                  <h2>
                    {locationLifecycle.action === 'close'
                      ? 'Close service location'
                      : `${locationLifecycle.action[0]!.toUpperCase()}${locationLifecycle.action.slice(1)} service location`}
                  </h2>
                  <p>
                    This high-assurance change requires current MFA and recent authentication. The
                    server enforces facility eligibility and parent/descendant ordering.
                  </p>
                  <form onSubmit={(event) => void submitLocationLifecycle(event)}>
                    <label>
                      Location lifecycle reason
                      <textarea
                        required
                        minLength={10}
                        maxLength={500}
                        value={locationLifecycleReason}
                        onChange={(event) => setLocationLifecycleReason(event.target.value)}
                      />
                    </label>
                    <div className="form-actions">
                      <button className="primary-button" disabled={busy}>
                        {busy
                          ? 'Saving...'
                          : `Confirm ${locationLifecycle.action === 'close' ? 'closure' : locationLifecycle.action}`}
                      </button>
                      <button type="button" onClick={() => setLocationLifecycle(null)}>
                        Cancel
                      </button>
                    </div>
                  </form>
                </section>
              )}
              {directory.data.canManage && (
                <section className="content-panel">
                  <h2>Add location draft</h2>
                  <form onSubmit={(event) => void submit(event)}>
                    <div className="form-grid">
                      <label>
                        Location code
                        <input
                          required
                          pattern="[A-Z0-9][A-Z0-9_-]{1,31}"
                          value={form.locationCode}
                          onChange={(event) =>
                            setForm({ ...form, locationCode: event.target.value.toUpperCase() })
                          }
                        />
                      </label>
                      <label>
                        Type
                        <select
                          value={form.locationType}
                          onChange={(event) =>
                            setForm({
                              ...form,
                              locationType: event.target.value as 'physical' | 'virtual',
                              addressId: null,
                              virtualServiceType: null,
                            })
                          }
                        >
                          <option value="physical">Physical</option>
                          <option value="virtual">Virtual</option>
                        </select>
                      </label>
                      <label>
                        Name
                        <input
                          required
                          minLength={2}
                          maxLength={120}
                          value={form.name}
                          onChange={(event) => setForm({ ...form, name: event.target.value })}
                        />
                      </label>
                      <label>
                        Parent location
                        <select
                          value={form.parentId ?? ''}
                          onChange={(event) =>
                            setForm({ ...form, parentId: event.target.value || null })
                          }
                        >
                          <option value="">No parent</option>
                          {directory.data.locations
                            .filter((location) => location.status === 'draft')
                            .map((location) => (
                              <option key={location.locationId} value={location.locationId}>
                                {location.name}
                              </option>
                            ))}
                        </select>
                      </label>
                      <label>
                        Organization unit (optional)
                        <select
                          disabled={units.phase !== 'ready'}
                          value={form.unitId ?? ''}
                          onChange={(event) =>
                            setForm({ ...form, unitId: event.target.value || null })
                          }
                        >
                          <option value="">No organization unit</option>
                          {selectableUnits.map((unit) => (
                            <option key={unit.unitId} value={unit.unitId}>
                              {unit.name} ({unit.unitCode}, {unit.status})
                            </option>
                          ))}
                        </select>
                      </label>
                      {form.locationType === 'physical' ? (
                        <label>
                          Physical address
                          <select
                            required
                            disabled={contacts.phase !== 'ready'}
                            value={form.addressId ?? ''}
                            onChange={(event) =>
                              setForm({ ...form, addressId: event.target.value || null })
                            }
                          >
                            <option value="">Select a current address</option>
                            {currentAddresses.map((address) => (
                              <option key={address.addressId} value={address.addressId}>
                                {address.addressType}: {address.addressLines.join(', ')},{' '}
                                {address.locality}
                              </option>
                            ))}
                          </select>
                        </label>
                      ) : (
                        <label>
                          Virtual service type
                          <input
                            required
                            minLength={2}
                            maxLength={80}
                            value={form.virtualServiceType ?? ''}
                            onChange={(event) =>
                              setForm({ ...form, virtualServiceType: event.target.value })
                            }
                          />
                        </label>
                      )}
                      <label>
                        Capacity (optional)
                        <input
                          type="number"
                          min={1}
                          max={100000}
                          value={form.capacity ?? ''}
                          onChange={(event) =>
                            setForm({
                              ...form,
                              capacity: event.target.value ? Number(event.target.value) : null,
                            })
                          }
                        />
                      </label>
                      <label>
                        Effective from
                        <input
                          required
                          type="datetime-local"
                          value={form.effectiveFrom.slice(0, 16)}
                          onChange={(event) =>
                            setForm({
                              ...form,
                              effectiveFrom: new Date(event.target.value).toISOString(),
                            })
                          }
                        />
                      </label>
                      <label className="full-width">
                        Accessibility notes
                        <textarea
                          maxLength={500}
                          value={form.accessibilityNotes ?? ''}
                          onChange={(event) =>
                            setForm({ ...form, accessibilityNotes: event.target.value || null })
                          }
                        />
                      </label>
                      <label className="full-width">
                        Creation reason
                        <textarea
                          required
                          minLength={10}
                          maxLength={500}
                          value={form.reason}
                          onChange={(event) => setForm({ ...form, reason: event.target.value })}
                        />
                      </label>
                    </div>
                    <div className="form-actions">
                      <button className="primary-button" disabled={busy}>
                        {busy ? 'Creating...' : 'Create location draft'}
                      </button>
                    </div>
                  </form>
                </section>
              )}
            </>
          )}
        </>
      )}
    </>
  );
}

function UnitHierarchyScreen({ client, organizationId }: AdministrationScreenProps) {
  const [attempt, setAttempt] = useState(0);
  const [facilities, setFacilities] = useState<LoadState<FacilityDirectory>>({ phase: 'loading' });
  const [facilityId, setFacilityId] = useState('');
  const [directory, setDirectory] = useState<LoadState<OrganizationUnitDirectory>>({
    phase: 'loading',
  });
  const [busy, setBusy] = useState(false);
  const [issue, setIssue] = useState('');
  const [editingUnitId, setEditingUnitId] = useState<string | null>(null);
  const [reparentingUnitId, setReparentingUnitId] = useState<string | null>(null);
  const [lifecycleChange, setLifecycleChange] = useState<{
    action: 'activate' | 'suspend' | 'reactivate' | 'close';
    unitId: string;
  } | null>(null);
  const [lifecycleReason, setLifecycleReason] = useState('');
  const [form, setForm] = useState<OrganizationUnitCreateRequest>({
    parentId: null,
    unitCode: '',
    unitType: 'department',
    name: '',
    effectiveFrom: new Date().toISOString(),
    effectiveTo: null,
    reason: '',
  });
  const [editForm, setEditForm] = useState<OrganizationUnitCreateRequest>(form);
  const [reparentForm, setReparentForm] = useState({
    parentId: null as string | null,
    effectiveFrom: new Date().toISOString(),
    reason: '',
  });
  useEffect(() => {
    const controller = new AbortController();
    void client
      .getFacilityDirectory(organizationId, {}, { signal: controller.signal })
      .then((result) => {
        if (controller.signal.aborted) return;
        if (!result.ok) setFacilities({ phase: 'failure', issue: failureMessage(result) });
        else if (!validFacilityDirectory(result.data, organizationId))
          setFacilities({
            phase: 'failure',
            issue: 'The server returned an invalid facility directory.',
          });
        else {
          setFacilities({ phase: 'ready', data: result.data });
          setFacilityId((current) =>
            result.data.facilities.some((facility) => facility.facilityId === current)
              ? current
              : (result.data.facilities[0]?.facilityId ?? ''),
          );
        }
      });
    return () => controller.abort();
  }, [attempt, client, organizationId]);
  useEffect(() => {
    if (!facilityId) return;
    const controller = new AbortController();
    void client
      .getOrganizationUnitDirectory(organizationId, facilityId, { signal: controller.signal })
      .then((result) => {
        if (controller.signal.aborted) return;
        if (!result.ok) setDirectory({ phase: 'failure', issue: failureMessage(result) });
        else if (!validOrganizationUnitDirectory(result.data, organizationId, facilityId))
          setDirectory({
            phase: 'failure',
            issue: 'The server returned an invalid unit hierarchy.',
          });
        else setDirectory({ phase: 'ready', data: result.data });
      });
    return () => controller.abort();
  }, [attempt, client, facilityId, organizationId]);
  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!facilityId || directory.phase !== 'ready' || !directory.data.canManage || busy) return;
    setBusy(true);
    setIssue('');
    const result = await client.createOrganizationUnitDraft(
      organizationId,
      facilityId,
      {
        ...form,
        unitCode: form.unitCode.trim().toUpperCase(),
        name: form.name.trim(),
        reason: form.reason.trim(),
      },
      `unit-draft:${globalThis.crypto.randomUUID()}`,
    );
    setBusy(false);
    if (!result.ok) return setIssue(failureMessage(result));
    if (!validOrganizationUnitDirectory(result.data, organizationId, facilityId))
      return setIssue('The saved response did not preserve the unit hierarchy contract.');
    setDirectory({ phase: 'ready', data: result.data });
    setForm({ ...form, parentId: null, unitCode: '', name: '', reason: '' });
  };
  const submitEdit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!facilityId || directory.phase !== 'ready' || !editingUnitId || busy) return;
    const unit = directory.data.units.find((candidate) => candidate.unitId === editingUnitId);
    if (!unit || unit.status !== 'draft') return;
    setBusy(true);
    setIssue('');
    const result = await client.updateOrganizationUnitDraft(
      organizationId,
      facilityId,
      unit.unitId,
      {
        ...editForm,
        parentId: unit.parentId ?? null,
        unitCode: editForm.unitCode.trim().toUpperCase(),
        name: editForm.name.trim(),
        reason: editForm.reason.trim(),
      },
      `"organization-unit:${unit.unitId}:${unit.lockVersion}"`,
      `unit-update:${globalThis.crypto.randomUUID()}`,
    );
    setBusy(false);
    if (!result.ok) return setIssue(failureMessage(result));
    if (!validOrganizationUnitDirectory(result.data, organizationId, facilityId))
      return setIssue('The saved response did not preserve the unit hierarchy contract.');
    setDirectory({ phase: 'ready', data: result.data });
    setEditingUnitId(null);
  };
  const submitReparent = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!facilityId || directory.phase !== 'ready' || !reparentingUnitId || busy) return;
    const unit = directory.data.units.find((candidate) => candidate.unitId === reparentingUnitId);
    if (!unit || unit.status !== 'draft') return;
    setBusy(true);
    setIssue('');
    const result = await client.reparentOrganizationUnit(
      organizationId,
      facilityId,
      unit.unitId,
      { ...reparentForm, reason: reparentForm.reason.trim() },
      `"organization-unit:${unit.unitId}:${unit.lockVersion}"`,
      `unit-reparent:${globalThis.crypto.randomUUID()}`,
    );
    setBusy(false);
    if (!result.ok) return setIssue(failureMessage(result));
    if (!validOrganizationUnitDirectory(result.data, organizationId, facilityId))
      return setIssue('The saved response did not preserve the unit hierarchy contract.');
    setDirectory({ phase: 'ready', data: result.data });
    setReparentingUnitId(null);
  };
  const submitLifecycle = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!facilityId || directory.phase !== 'ready' || !lifecycleChange || busy) return;
    const unit = directory.data.units.find(
      (candidate) => candidate.unitId === lifecycleChange.unitId,
    );
    if (!unit || !directory.data.canManageLifecycle) return;
    const etag = `"organization-unit:${unit.unitId}:${unit.lockVersion}"`;
    const key = `unit-${lifecycleChange.action}:${globalThis.crypto.randomUUID()}`;
    const reason = lifecycleReason.trim();
    setBusy(true);
    setIssue('');
    const result =
      lifecycleChange.action === 'activate'
        ? await client.activateOrganizationUnit(
            organizationId,
            facilityId,
            unit.unitId,
            { reason },
            etag,
            key,
          )
        : lifecycleChange.action === 'suspend'
          ? await client.suspendOrganizationUnit(
              organizationId,
              facilityId,
              unit.unitId,
              { reason },
              etag,
              key,
            )
          : lifecycleChange.action === 'reactivate'
            ? await client.reactivateOrganizationUnit(
                organizationId,
                facilityId,
                unit.unitId,
                { reason },
                etag,
                key,
              )
            : await client.closeOrganizationUnit(
                organizationId,
                facilityId,
                unit.unitId,
                { effectiveTo: new Date().toISOString(), reason },
                etag,
                key,
              );
    setBusy(false);
    if (!result.ok) return setIssue(failureMessage(result));
    if (!validOrganizationUnitDirectory(result.data, organizationId, facilityId))
      return setIssue('The saved response did not preserve the unit hierarchy contract.');
    setDirectory({ phase: 'ready', data: result.data });
    setLifecycleChange(null);
    setLifecycleReason('');
  };
  return (
    <>
      <PageHeading id="M1-14" />
      {facilities.phase === 'loading' ? (
        <LoadingPanel />
      ) : facilities.phase === 'failure' ? (
        <FailurePanel issue={facilities.issue} onRetry={() => setAttempt((value) => value + 1)} />
      ) : facilities.data.facilities.length === 0 ? (
        <section className="content-panel">
          <h2>No facility available</h2>
          <p>Create a facility before defining departments and units.</p>
        </section>
      ) : (
        <>
          <section className="content-panel">
            <div className="section-heading">
              <div>
                <span className="eyebrow">Care network</span>
                <h2>Department and unit hierarchy</h2>
              </div>
            </div>
            <label>
              Facility
              <select
                value={facilityId}
                onChange={(event) => {
                  setDirectory({ phase: 'loading' });
                  setEditingUnitId(null);
                  setReparentingUnitId(null);
                  setLifecycleChange(null);
                  setFacilityId(event.target.value);
                }}
              >
                {facilities.data.facilities.map((facility) => (
                  <option key={facility.facilityId} value={facility.facilityId}>
                    {facility.displayName} ({facility.status})
                  </option>
                ))}
              </select>
            </label>
          </section>
          {directory.phase === 'loading' ? (
            <LoadingPanel />
          ) : directory.phase === 'failure' ? (
            <FailurePanel
              issue={directory.issue}
              onRetry={() => {
                setDirectory({ phase: 'loading' });
                setAttempt((value) => value + 1);
              }}
            />
          ) : (
            <>
              {issue && <div className="inline-alert error-alert">{issue}</div>}
              <section className="content-panel">
                <div className="section-heading">
                  <h2>Hierarchy</h2>
                  <span className="status-badge">{directory.data.units.length} records</span>
                </div>
                {directory.data.units.length === 0 ? (
                  <p>No departments or units have been created for this facility.</p>
                ) : (
                  <div className="record-grid">
                    {directory.data.units.map((unit) => (
                      <article className="record-card" key={unit.unitId}>
                        <div className="record-card-head">
                          <strong>
                            {'— '.repeat(unitDepth(unit.unitId, directory.data))}
                            {unit.name}
                          </strong>
                          <span className="status-badge">{unit.status}</span>
                        </div>
                        <p>
                          {unit.unitCode} · {unit.unitType}
                        </p>
                        <small>
                          Depth {unitDepth(unit.unitId, directory.data) + 1} · revision{' '}
                          {unit.lockVersion}
                        </small>
                        {directory.data.canManage && unit.status === 'draft' && (
                          <div className="form-actions">
                            <button
                              type="button"
                              className="secondary-button"
                              onClick={() => {
                                setReparentingUnitId(null);
                                setEditingUnitId(unit.unitId);
                                setEditForm({
                                  parentId: unit.parentId ?? null,
                                  unitCode: unit.unitCode,
                                  unitType: unit.unitType,
                                  name: unit.name,
                                  effectiveFrom: unit.effectiveFrom,
                                  effectiveTo: unit.effectiveTo ?? null,
                                  reason: '',
                                });
                              }}
                            >
                              Edit draft
                            </button>
                            <button
                              type="button"
                              className="secondary-button"
                              onClick={() => {
                                setEditingUnitId(null);
                                setReparentingUnitId(unit.unitId);
                                setReparentForm({
                                  parentId: unit.parentId ?? null,
                                  effectiveFrom: new Date().toISOString(),
                                  reason: '',
                                });
                              }}
                            >
                              Change parent
                            </button>
                          </div>
                        )}
                        {directory.data.canManageLifecycle && unit.status !== 'closed' && (
                          <div className="form-actions">
                            {unit.status === 'draft' && (
                              <button
                                type="button"
                                className="secondary-button"
                                onClick={() => {
                                  setLifecycleReason('');
                                  setLifecycleChange({ action: 'activate', unitId: unit.unitId });
                                }}
                              >
                                Activate
                              </button>
                            )}
                            {unit.status === 'active' && (
                              <button
                                type="button"
                                className="secondary-button"
                                onClick={() => {
                                  setLifecycleReason('');
                                  setLifecycleChange({ action: 'suspend', unitId: unit.unitId });
                                }}
                              >
                                Suspend
                              </button>
                            )}
                            {unit.status === 'suspended' && (
                              <button
                                type="button"
                                className="secondary-button"
                                onClick={() => {
                                  setLifecycleReason('');
                                  setLifecycleChange({ action: 'reactivate', unitId: unit.unitId });
                                }}
                              >
                                Reactivate
                              </button>
                            )}
                            {['active', 'suspended'].includes(unit.status) && (
                              <button
                                type="button"
                                className="secondary-button"
                                onClick={() => {
                                  setLifecycleReason('');
                                  setLifecycleChange({ action: 'close', unitId: unit.unitId });
                                }}
                              >
                                Close
                              </button>
                            )}
                          </div>
                        )}
                      </article>
                    ))}
                  </div>
                )}
              </section>
              {editingUnitId && (
                <section className="content-panel">
                  <h2>Edit hierarchy draft</h2>
                  <form onSubmit={(event) => void submitEdit(event)}>
                    <div className="form-grid">
                      <label>
                        Edit unit code
                        <input
                          required
                          pattern="[A-Z0-9][A-Z0-9_-]{1,31}"
                          value={editForm.unitCode}
                          onChange={(event) =>
                            setEditForm({ ...editForm, unitCode: event.target.value.toUpperCase() })
                          }
                        />
                      </label>
                      <label>
                        Edit type
                        <select
                          value={editForm.unitType}
                          onChange={(event) =>
                            setEditForm({
                              ...editForm,
                              unitType: event.target.value as 'department' | 'unit',
                            })
                          }
                        >
                          <option value="department">Department</option>
                          <option value="unit">Unit</option>
                        </select>
                      </label>
                      <label>
                        Edit name
                        <input
                          required
                          minLength={2}
                          maxLength={120}
                          value={editForm.name}
                          onChange={(event) =>
                            setEditForm({ ...editForm, name: event.target.value })
                          }
                        />
                      </label>
                      <label>
                        Edit effective from
                        <input
                          required
                          type="datetime-local"
                          value={editForm.effectiveFrom.slice(0, 16)}
                          onChange={(event) =>
                            setEditForm({
                              ...editForm,
                              effectiveFrom: new Date(event.target.value).toISOString(),
                            })
                          }
                        />
                      </label>
                      <label className="full-width">
                        Edit reason
                        <textarea
                          required
                          minLength={10}
                          maxLength={500}
                          value={editForm.reason}
                          onChange={(event) =>
                            setEditForm({ ...editForm, reason: event.target.value })
                          }
                        />
                      </label>
                    </div>
                    <div className="form-actions">
                      <button className="primary-button" disabled={busy}>
                        {busy ? 'Saving...' : 'Save draft changes'}
                      </button>
                      <button type="button" onClick={() => setEditingUnitId(null)}>
                        Cancel
                      </button>
                    </div>
                  </form>
                </section>
              )}
              {reparentingUnitId && (
                <section className="content-panel">
                  <h2>Change draft parent</h2>
                  <form onSubmit={(event) => void submitReparent(event)}>
                    <div className="form-grid">
                      <label>
                        New parent
                        <select
                          value={reparentForm.parentId ?? ''}
                          onChange={(event) =>
                            setReparentForm({
                              ...reparentForm,
                              parentId: event.target.value || null,
                            })
                          }
                        >
                          <option value="">No parent</option>
                          {directory.data.units
                            .filter(
                              (unit) =>
                                unit.status === 'draft' &&
                                unit.unitId !== reparentingUnitId &&
                                !unitDescendsFrom(unit.unitId, reparentingUnitId, directory.data),
                            )
                            .map((unit) => (
                              <option key={unit.unitId} value={unit.unitId}>
                                {unit.name}
                              </option>
                            ))}
                        </select>
                      </label>
                      <label>
                        Parent change effective from
                        <input
                          required
                          type="datetime-local"
                          value={reparentForm.effectiveFrom.slice(0, 16)}
                          onChange={(event) =>
                            setReparentForm({
                              ...reparentForm,
                              effectiveFrom: new Date(event.target.value).toISOString(),
                            })
                          }
                        />
                      </label>
                      <label className="full-width">
                        Parent change reason
                        <textarea
                          required
                          minLength={10}
                          maxLength={500}
                          value={reparentForm.reason}
                          onChange={(event) =>
                            setReparentForm({ ...reparentForm, reason: event.target.value })
                          }
                        />
                      </label>
                    </div>
                    <div className="form-actions">
                      <button className="primary-button" disabled={busy}>
                        {busy ? 'Saving...' : 'Save parent change'}
                      </button>
                      <button type="button" onClick={() => setReparentingUnitId(null)}>
                        Cancel
                      </button>
                    </div>
                  </form>
                </section>
              )}
              {lifecycleChange && (
                <section className="content-panel">
                  <h2>
                    {lifecycleChange.action === 'close'
                      ? 'Close organization unit'
                      : `${lifecycleChange.action[0]!.toUpperCase()}${lifecycleChange.action.slice(1)} organization unit`}
                  </h2>
                  <p>
                    This high-assurance change requires current MFA and recent authentication. The
                    server will enforce parent/descendant ordering and facility eligibility.
                  </p>
                  <form onSubmit={(event) => void submitLifecycle(event)}>
                    <label>
                      Lifecycle reason
                      <textarea
                        required
                        minLength={10}
                        maxLength={500}
                        value={lifecycleReason}
                        onChange={(event) => setLifecycleReason(event.target.value)}
                      />
                    </label>
                    <div className="form-actions">
                      <button className="primary-button" disabled={busy}>
                        {busy
                          ? 'Saving...'
                          : `Confirm ${lifecycleChange.action === 'close' ? 'closure' : lifecycleChange.action}`}
                      </button>
                      <button type="button" onClick={() => setLifecycleChange(null)}>
                        Cancel
                      </button>
                    </div>
                  </form>
                </section>
              )}
              {directory.data.canManage && (
                <section className="content-panel">
                  <h2>Add hierarchy draft</h2>
                  <form onSubmit={(event) => void submit(event)}>
                    <div className="form-grid">
                      <label>
                        Unit code
                        <input
                          required
                          pattern="[A-Z0-9][A-Z0-9_-]{1,31}"
                          value={form.unitCode}
                          onChange={(event) =>
                            setForm({ ...form, unitCode: event.target.value.toUpperCase() })
                          }
                        />
                      </label>
                      <label>
                        Type
                        <select
                          value={form.unitType}
                          onChange={(event) =>
                            setForm({
                              ...form,
                              unitType: event.target.value as 'department' | 'unit',
                            })
                          }
                        >
                          <option value="department">Department</option>
                          <option value="unit">Unit</option>
                        </select>
                      </label>
                      <label>
                        Name
                        <input
                          required
                          minLength={2}
                          maxLength={120}
                          value={form.name}
                          onChange={(event) => setForm({ ...form, name: event.target.value })}
                        />
                      </label>
                      <label>
                        Parent
                        <select
                          value={form.parentId ?? ''}
                          onChange={(event) =>
                            setForm({ ...form, parentId: event.target.value || null })
                          }
                        >
                          <option value="">No parent</option>
                          {directory.data.units
                            .filter((unit) => unit.status === 'draft')
                            .map((unit) => (
                              <option key={unit.unitId} value={unit.unitId}>
                                {unit.name}
                              </option>
                            ))}
                        </select>
                      </label>
                      <label>
                        Effective from
                        <input
                          required
                          type="datetime-local"
                          value={form.effectiveFrom.slice(0, 16)}
                          onChange={(event) =>
                            setForm({
                              ...form,
                              effectiveFrom: new Date(event.target.value).toISOString(),
                            })
                          }
                        />
                      </label>
                      <label className="full-width">
                        Reason
                        <textarea
                          required
                          minLength={10}
                          maxLength={500}
                          value={form.reason}
                          onChange={(event) => setForm({ ...form, reason: event.target.value })}
                        />
                      </label>
                    </div>
                    <div className="form-actions">
                      <button className="primary-button" disabled={busy}>
                        {busy ? 'Creating...' : 'Add hierarchy draft'}
                      </button>
                    </div>
                  </form>
                </section>
              )}
            </>
          )}
        </>
      )}
    </>
  );
}

function FacilityScreen({ client, id, organizationId }: AdministrationScreenProps) {
  const [attempt, setAttempt] = useState(0);
  const [query, setQuery] = useState('');
  const [state, setState] = useState<LoadState<FacilityDirectory>>({ phase: 'loading' });
  const [busy, setBusy] = useState(false);
  const [issue, setIssue] = useState('');
  const [editingFacilityId, setEditingFacilityId] = useState<string | null>(null);
  const [submittingFacilityId, setSubmittingFacilityId] = useState<string | null>(null);
  const [submissionReason, setSubmissionReason] = useState('');
  const [lifecycleChange, setLifecycleChange] = useState<{
    facilityId: string;
    action: 'suspensions' | 'reactivations' | 'closures';
  } | null>(null);
  const [lifecycleReason, setLifecycleReason] = useState('');
  const [form, setForm] = useState<FacilityCreateRequest>({
    facilityCode: '',
    legalName: '',
    displayName: '',
    facilityType: 'care_site',
    addressId: null,
    contactId: null,
    timezone: null,
    reason: '',
  });
  useEffect(() => {
    const controller = new AbortController();
    void client
      .getFacilityDirectory(organizationId, query ? { query } : {}, { signal: controller.signal })
      .then((result) => {
        if (controller.signal.aborted) return;
        if (!result.ok) setState({ phase: 'failure', issue: failureMessage(result) });
        else if (!validFacilityDirectory(result.data, organizationId))
          setState({
            phase: 'failure',
            issue: 'The server returned an invalid facility directory.',
          });
        else {
          setState({ phase: 'ready', data: result.data });
          const firstType = result.data.facilityTypes[0];
          if (firstType) setForm((current) => ({ ...current, facilityType: firstType.key }));
        }
      });
    return () => controller.abort();
  }, [attempt, client, organizationId, query]);
  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (state.phase !== 'ready' || !state.data.canCreate || busy) return;
    setBusy(true);
    setIssue('');
    const body = {
      ...form,
      facilityCode: form.facilityCode.trim().toUpperCase(),
      legalName: form.legalName.trim(),
      displayName: form.displayName.trim(),
      timezone: form.timezone?.trim() || null,
      reason: form.reason.trim(),
    };
    const editingFacility = editingFacilityId
      ? state.data.facilities.find((facility) => facility.facilityId === editingFacilityId)
      : undefined;
    const result = editingFacility
      ? await client.updateFacilityDraft(
          organizationId,
          editingFacility.facilityId,
          body,
          `"facility:${editingFacility.facilityId}:${editingFacility.lockVersion}"`,
          `facility-update:${globalThis.crypto.randomUUID()}`,
        )
      : await client.createFacilityDraft(
          organizationId,
          body,
          `facility-draft:${globalThis.crypto.randomUUID()}`,
        );
    setBusy(false);
    if (!result.ok) {
      setIssue(failureMessage(result));
      return;
    }
    if (!validFacilityDirectory(result.data, organizationId)) {
      setIssue('The saved response did not preserve the facility contract.');
      return;
    }
    setState({ phase: 'ready', data: result.data });
    setEditingFacilityId(null);
    setForm({ ...form, facilityCode: '', legalName: '', displayName: '', reason: '' });
  };
  const submitForReview = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (state.phase !== 'ready' || !state.data.canCreate || busy || !submittingFacilityId) return;
    const facility = state.data.facilities.find(
      (candidate) => candidate.facilityId === submittingFacilityId,
    );
    if (!facility || facility.status !== 'draft') return;
    setBusy(true);
    setIssue('');
    const result = await client.submitFacilityDraft(
      organizationId,
      facility.facilityId,
      { reason: submissionReason.trim() },
      `"facility:${facility.facilityId}:${facility.lockVersion}"`,
      `facility-submit:${globalThis.crypto.randomUUID()}`,
    );
    setBusy(false);
    if (!result.ok) {
      setIssue(failureMessage(result));
      return;
    }
    if (!validFacilityDirectory(result.data, organizationId)) {
      setIssue('The submitted response did not preserve the facility contract.');
      return;
    }
    setState({ phase: 'ready', data: result.data });
    setSubmittingFacilityId(null);
    setSubmissionReason('');
  };
  const submitLifecycle = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (state.phase !== 'ready' || !state.data.canManageLifecycle || busy || !lifecycleChange)
      return;
    const facility = state.data.facilities.find(
      (candidate) => candidate.facilityId === lifecycleChange.facilityId,
    );
    if (!facility) return;
    setBusy(true);
    setIssue('');
    const result = await client.transitionFacilityLifecycle(
      organizationId,
      facility.facilityId,
      lifecycleChange.action,
      { fromState: facility.status, reason: lifecycleReason.trim() },
      `"facility:${facility.facilityId}:${facility.lockVersion}"`,
      `facility-${lifecycleChange.action}:${globalThis.crypto.randomUUID()}`,
    );
    setBusy(false);
    if (!result.ok) {
      setIssue(failureMessage(result));
      return;
    }
    if (!validFacilityDirectory(result.data, organizationId)) {
      setIssue('The lifecycle response did not preserve the facility contract.');
      return;
    }
    setState({ phase: 'ready', data: result.data });
    setLifecycleChange(null);
    setLifecycleReason('');
  };
  return (
    <>
      <PageHeading id={id === 'M1-13' ? 'M1-13' : 'M1-12'} />
      {state.phase === 'loading' ? (
        <LoadingPanel />
      ) : state.phase === 'failure' ? (
        <FailurePanel
          issue={state.issue}
          onRetry={() => {
            setState({ phase: 'loading' });
            setAttempt((v) => v + 1);
          }}
        />
      ) : (
        <>
          {issue && <div className="inline-alert error-alert">{issue}</div>}
          <section className="content-panel">
            <div className="section-heading">
              <div>
                <span className="eyebrow">Network directory</span>
                <h2>Facilities</h2>
              </div>
              <span className="status-badge">{state.data.facilities.length} records</span>
            </div>
            <label>
              Search facilities
              <input value={query} onChange={(e) => setQuery(e.target.value)} />
            </label>
            <div className="record-grid">
              {state.data.facilities.length === 0 ? (
                <p>No facilities match this view.</p>
              ) : (
                state.data.facilities.map((f) => (
                  <article className="record-card" key={f.facilityId}>
                    <div className="record-card-head">
                      <strong>{f.displayName}</strong>
                      <span className="status-badge">{f.status}</span>
                    </div>
                    <p>{f.legalName}</p>
                    <small>
                      {f.facilityCode} · {f.facilityType} · {f.timezone ?? 'Inherited timezone'}
                    </small>
                    {id === 'M1-13' && state.data.canManageLifecycle && f.status !== 'closed' && (
                      <div className="form-actions">
                        {f.status === 'under_review' && (
                          <span className="status-badge">Activate through M1-21 approval</span>
                        )}
                        {f.status === 'active' && (
                          <button
                            type="button"
                            onClick={() => {
                              setLifecycleChange({
                                facilityId: f.facilityId,
                                action: 'suspensions',
                              });
                              setLifecycleReason('');
                            }}
                          >
                            Suspend
                          </button>
                        )}
                        {f.status === 'suspended' && (
                          <button
                            type="button"
                            onClick={() => {
                              setLifecycleChange({
                                facilityId: f.facilityId,
                                action: 'reactivations',
                              });
                              setLifecycleReason('');
                            }}
                          >
                            Reactivate
                          </button>
                        )}
                        {(f.status === 'active' || f.status === 'suspended') && (
                          <button
                            type="button"
                            onClick={() => {
                              setLifecycleChange({ facilityId: f.facilityId, action: 'closures' });
                              setLifecycleReason('');
                            }}
                          >
                            Close
                          </button>
                        )}
                      </div>
                    )}
                    {state.data.canCreate && f.status === 'draft' && (
                      <div className="form-actions">
                        <button
                          className="secondary-button"
                          type="button"
                          onClick={() => {
                            setEditingFacilityId(f.facilityId);
                            setIssue('');
                            setForm({
                              facilityCode: f.facilityCode,
                              legalName: f.legalName,
                              displayName: f.displayName,
                              facilityType: f.facilityType,
                              addressId: null,
                              contactId: null,
                              timezone: f.timezone ?? null,
                              reason: '',
                            });
                          }}
                        >
                          Edit draft
                        </button>
                        <button
                          className="secondary-button"
                          type="button"
                          onClick={() => {
                            setSubmittingFacilityId(f.facilityId);
                            setSubmissionReason('');
                            setIssue('');
                          }}
                        >
                          Submit for review
                        </button>
                      </div>
                    )}
                    {submittingFacilityId === f.facilityId && (
                      <form
                        className="stacked-form"
                        onSubmit={(event) => void submitForReview(event)}
                      >
                        <label>
                          Submission reason
                          <textarea
                            required
                            minLength={10}
                            maxLength={500}
                            value={submissionReason}
                            onChange={(event) => setSubmissionReason(event.target.value)}
                          />
                        </label>
                        <p className="field-help">
                          Submission requires a validated active address and effective timezone.
                        </p>
                        <div className="form-actions">
                          <button className="primary-button" disabled={busy}>
                            {busy ? 'Submitting...' : 'Confirm submission'}
                          </button>
                          <button
                            className="secondary-button"
                            type="button"
                            disabled={busy}
                            onClick={() => {
                              setSubmittingFacilityId(null);
                              setSubmissionReason('');
                            }}
                          >
                            Cancel submission
                          </button>
                        </div>
                      </form>
                    )}
                  </article>
                ))
              )}
            </div>
          </section>
          {id === 'M1-13' && lifecycleChange && (
            <section className="content-panel">
              <h2>Confirm facility lifecycle change</h2>
              <p>
                This high-assurance action requires current MFA and recent authentication. The
                server rechecks address, hierarchy, operating hours, and assignment impact.
              </p>
              <form onSubmit={(event) => void submitLifecycle(event)}>
                <label>
                  Lifecycle reason
                  <textarea
                    required
                    minLength={10}
                    maxLength={500}
                    value={lifecycleReason}
                    onChange={(event) => setLifecycleReason(event.target.value)}
                  />
                </label>
                <div className="form-actions">
                  <button className="primary-button" disabled={busy}>
                    {busy ? 'Saving...' : 'Confirm lifecycle change'}
                  </button>
                  <button type="button" disabled={busy} onClick={() => setLifecycleChange(null)}>
                    Cancel
                  </button>
                </div>
              </form>
            </section>
          )}
          {state.data.canCreate && (
            <section className="content-panel">
              <h2>{editingFacilityId ? 'Edit facility draft' : 'Add facility draft'}</h2>
              <form onSubmit={(e) => void submit(e)}>
                <div className="form-grid">
                  <label>
                    Facility code
                    <input
                      required
                      pattern="[A-Z0-9][A-Z0-9_-]{1,31}"
                      value={form.facilityCode}
                      onChange={(e) =>
                        setForm({ ...form, facilityCode: e.target.value.toUpperCase() })
                      }
                    />
                  </label>
                  <label>
                    Type
                    <select
                      value={form.facilityType}
                      onChange={(e) => setForm({ ...form, facilityType: e.target.value })}
                    >
                      {state.data.facilityTypes.map((t) => (
                        <option value={t.key} key={t.key}>
                          {t.displayName}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label>
                    Legal name
                    <input
                      required
                      minLength={2}
                      maxLength={200}
                      value={form.legalName}
                      onChange={(e) => setForm({ ...form, legalName: e.target.value })}
                    />
                  </label>
                  <label>
                    Display name
                    <input
                      required
                      minLength={2}
                      maxLength={120}
                      value={form.displayName}
                      onChange={(e) => setForm({ ...form, displayName: e.target.value })}
                    />
                  </label>
                  <label>
                    Timezone override
                    <input
                      placeholder="Asia/Kolkata"
                      value={form.timezone ?? ''}
                      onChange={(e) => setForm({ ...form, timezone: e.target.value })}
                    />
                  </label>
                  <label className="full-width">
                    Reason
                    <textarea
                      required
                      minLength={10}
                      maxLength={500}
                      value={form.reason}
                      onChange={(e) => setForm({ ...form, reason: e.target.value })}
                    />
                  </label>
                </div>
                <div className="form-actions">
                  <button className="primary-button" disabled={busy}>
                    {busy
                      ? editingFacilityId
                        ? 'Saving...'
                        : 'Creating...'
                      : editingFacilityId
                        ? 'Save facility'
                        : 'Add facility'}
                  </button>
                  {editingFacilityId && (
                    <button
                      className="secondary-button"
                      type="button"
                      disabled={busy}
                      onClick={() => {
                        setEditingFacilityId(null);
                        setIssue('');
                        setForm({
                          facilityCode: '',
                          legalName: '',
                          displayName: '',
                          facilityType: state.data.facilityTypes[0]?.key ?? 'care_site',
                          addressId: null,
                          contactId: null,
                          timezone: null,
                          reason: '',
                        });
                      }}
                    >
                      Cancel edit
                    </button>
                  )}
                </div>
              </form>
            </section>
          )}
        </>
      )}
    </>
  );
}

type HoursTarget = {
  type: 'facility' | 'location';
  id: string;
  label: string;
  facilityId?: string;
};
type HoursIntervalDraft = { weekday: number; start: string; end: string; endsNextDay: boolean };
type HoursExceptionDraft = {
  localDate: string;
  closed: boolean;
  label: string;
  reasonCode: string;
  start: string;
  end: string;
  endsNextDay: boolean;
};

function minutes(value: string) {
  const [hour, minute] = value.split(':').map(Number);
  return hour! * 60 + minute!;
}

function OperatingHoursScreen({ client, organizationId }: AdministrationScreenProps) {
  const [attempt, setAttempt] = useState(0);
  const [state, setState] = useState<
    LoadState<{ overview: OperatingHoursOverview; targets: HoursTarget[] }>
  >({ phase: 'loading' });
  const [targetKey, setTargetKey] = useState('');
  const [timezone, setTimezone] = useState(
    Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC',
  );
  const [effectiveFrom, setEffectiveFrom] = useState(
    new Date(applicationStartedAt + 180000).toISOString().slice(0, 16),
  );
  const [effectiveTo, setEffectiveTo] = useState('');
  const [intervals, setIntervals] = useState<HoursIntervalDraft[]>([
    { weekday: 1, start: '09:00', end: '17:00', endsNextDay: false },
  ]);
  const [exceptions, setExceptions] = useState<HoursExceptionDraft[]>([]);
  const [reason, setReason] = useState('');
  const [issue, setIssue] = useState('');
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    const controller = new AbortController();
    const options = { signal: controller.signal };
    void Promise.all([
      client.getOperatingHoursDirectory(organizationId, options),
      client.getFacilityDirectory(organizationId, {}, options),
    ]).then(async ([overview, facilities]) => {
      if (controller.signal.aborted) return;
      if (!overview.ok) {
        setState({ phase: 'failure', issue: failureMessage(overview) });
        return;
      }
      if (!facilities.ok) {
        setState({ phase: 'failure', issue: failureMessage(facilities) });
        return;
      }
      const targets: HoursTarget[] = facilities.data.facilities.map((facility) => ({
        type: 'facility',
        id: facility.facilityId,
        label: `Facility · ${facility.displayName}`,
      }));
      const locationResults = await Promise.all(
        facilities.data.facilities.map((facility) =>
          client.getServiceLocationDirectory(organizationId, facility.facilityId, options),
        ),
      );
      if (controller.signal.aborted) return;
      for (const [index, result] of locationResults.entries())
        if (result.ok)
          for (const location of result.data.locations)
            if (location.status !== 'closed')
              targets.push({
                type: 'location',
                id: location.locationId,
                label: `Location · ${location.name}`,
                facilityId: facilities.data.facilities[index]?.facilityId,
              });
      setState({ phase: 'ready', data: { overview: overview.data, targets } });
      setTargetKey(
        (current) => current || (targets[0] ? `${targets[0].type}:${targets[0].id}` : ''),
      );
    });
    return () => controller.abort();
  }, [attempt, client, organizationId]);
  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (state.phase !== 'ready' || busy) return;
    const target = state.data.targets.find(
      (candidate) => `${candidate.type}:${candidate.id}` === targetKey,
    );
    if (!target) return;
    setBusy(true);
    setIssue('');
    const body = {
      timezone: timezone.trim(),
      effectiveFrom: new Date(effectiveFrom).toISOString(),
      effectiveTo: effectiveTo ? new Date(effectiveTo).toISOString() : null,
      intervals: intervals.map((item) => ({
        weekday: item.weekday,
        startMinute: minutes(item.start),
        endMinute: minutes(item.end),
        endsNextDay: item.endsNextDay,
      })),
      exceptions: exceptions.map((item) => ({
        localDate: item.localDate,
        closed: item.closed,
        label: item.label.trim(),
        reasonCode: item.reasonCode.trim(),
        intervals: item.closed
          ? []
          : [
              {
                startMinute: minutes(item.start),
                endMinute: minutes(item.end),
                endsNextDay: item.endsNextDay,
              },
            ],
      })),
      reason: reason.trim(),
    };
    const result = await client.replaceOperatingHours(
      organizationId,
      target.type,
      target.id,
      body,
      `hours-replace:${globalThis.crypto.randomUUID()}`,
    );
    setBusy(false);
    if (!result.ok) {
      setIssue(failureMessage(result));
      return;
    }
    setReason('');
    setAttempt((value) => value + 1);
    setState({ phase: 'loading' });
  };
  const batches = state.phase === 'ready' ? state.data.overview.batches : [];
  return (
    <>
      <PageHeading id="M1-16" />
      {state.phase === 'loading' ? (
        <LoadingPanel />
      ) : state.phase === 'failure' ? (
        <FailurePanel
          issue={state.issue}
          onRetry={() => {
            setState({ phase: 'loading' });
            setAttempt((value) => value + 1);
          }}
        />
      ) : (
        <>
          {issue && <div className="inline-alert error-alert">{issue}</div>}
          <section className="content-panel">
            <div className="panel-heading">
              <div>
                <h2>Atomic operating-hours batches</h2>
                <p>Weekly intervals and dated exceptions commit as one governed batch.</p>
              </div>
              <span className="status-badge">{batches.length} batches</span>
            </div>
            {batches.length === 0 ? (
              <p className="empty-state">No operating-hours batch exists yet.</p>
            ) : (
              <div className="record-grid">
                {batches.map((batch, index) => (
                  <LiveRecord key={String(batch.batchId ?? index)} value={batch} />
                ))}
              </div>
            )}
          </section>
          {Boolean(state.data.overview.canManage) && (
            <section className="content-panel">
              <h2>Save hours batch</h2>
              <form onSubmit={(event) => void submit(event)}>
                <div className="form-grid">
                  <label>
                    Target
                    <select
                      required
                      value={targetKey}
                      onChange={(event) => setTargetKey(event.target.value)}
                    >
                      <option value="">Select target</option>
                      {state.data.targets.map((target) => (
                        <option
                          key={`${target.type}:${target.id}`}
                          value={`${target.type}:${target.id}`}
                        >
                          {target.label}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label>
                    Timezone
                    <input
                      required
                      value={timezone}
                      onChange={(event) => setTimezone(event.target.value)}
                    />
                  </label>
                  <label>
                    Effective from
                    <input
                      required
                      type="datetime-local"
                      value={effectiveFrom}
                      onChange={(event) => setEffectiveFrom(event.target.value)}
                    />
                  </label>
                  <label>
                    Effective to
                    <input
                      type="datetime-local"
                      value={effectiveTo}
                      onChange={(event) => setEffectiveTo(event.target.value)}
                    />
                  </label>
                </div>
                <h3>Weekly intervals</h3>
                {intervals.map((item, index) => (
                  <div className="form-grid" key={index}>
                    <label>
                      Weekday
                      <select
                        value={item.weekday}
                        onChange={(event) =>
                          setIntervals((current) =>
                            current.map((value, position) =>
                              position === index
                                ? { ...value, weekday: Number(event.target.value) }
                                : value,
                            ),
                          )
                        }
                      >
                        {[
                          'Monday',
                          'Tuesday',
                          'Wednesday',
                          'Thursday',
                          'Friday',
                          'Saturday',
                          'Sunday',
                        ].map((day, position) => (
                          <option key={day} value={position + 1}>
                            {day}
                          </option>
                        ))}
                      </select>
                    </label>
                    <label>
                      Start
                      <input
                        required
                        type="time"
                        value={item.start}
                        onChange={(event) =>
                          setIntervals((current) =>
                            current.map((value, position) =>
                              position === index ? { ...value, start: event.target.value } : value,
                            ),
                          )
                        }
                      />
                    </label>
                    <label>
                      End
                      <input
                        required
                        type="time"
                        value={item.end}
                        onChange={(event) =>
                          setIntervals((current) =>
                            current.map((value, position) =>
                              position === index ? { ...value, end: event.target.value } : value,
                            ),
                          )
                        }
                      />
                    </label>
                    <label>
                      <input
                        type="checkbox"
                        checked={item.endsNextDay}
                        onChange={(event) =>
                          setIntervals((current) =>
                            current.map((value, position) =>
                              position === index
                                ? { ...value, endsNextDay: event.target.checked }
                                : value,
                            ),
                          )
                        }
                      />{' '}
                      Ends next day
                    </label>
                    <button
                      type="button"
                      disabled={intervals.length === 1}
                      onClick={() =>
                        setIntervals((current) =>
                          current.filter((_, position) => position !== index),
                        )
                      }
                    >
                      Remove interval
                    </button>
                  </div>
                ))}
                <button
                  type="button"
                  onClick={() =>
                    setIntervals((current) => [
                      ...current,
                      { weekday: 1, start: '09:00', end: '17:00', endsNextDay: false },
                    ])
                  }
                >
                  Add interval
                </button>
                <h3>Holiday exceptions</h3>
                {exceptions.map((item, index) => (
                  <div className="form-grid" key={index}>
                    <label>
                      Date
                      <input
                        required
                        type="date"
                        value={item.localDate}
                        onChange={(event) =>
                          setExceptions((current) =>
                            current.map((value, position) =>
                              position === index
                                ? { ...value, localDate: event.target.value }
                                : value,
                            ),
                          )
                        }
                      />
                    </label>
                    <label>
                      Label
                      <input
                        required
                        value={item.label}
                        onChange={(event) =>
                          setExceptions((current) =>
                            current.map((value, position) =>
                              position === index ? { ...value, label: event.target.value } : value,
                            ),
                          )
                        }
                      />
                    </label>
                    <label>
                      Reason code
                      <input
                        required
                        pattern="[a-z][a-z0-9_]*(\.[a-z0-9_]+)+"
                        value={item.reasonCode}
                        onChange={(event) =>
                          setExceptions((current) =>
                            current.map((value, position) =>
                              position === index
                                ? { ...value, reasonCode: event.target.value }
                                : value,
                            ),
                          )
                        }
                      />
                    </label>
                    <label>
                      <input
                        type="checkbox"
                        checked={item.closed}
                        onChange={(event) =>
                          setExceptions((current) =>
                            current.map((value, position) =>
                              position === index
                                ? { ...value, closed: event.target.checked }
                                : value,
                            ),
                          )
                        }
                      />{' '}
                      Closed all day
                    </label>
                    {!item.closed && (
                      <>
                        <label>
                          Start
                          <input
                            required
                            type="time"
                            value={item.start}
                            onChange={(event) =>
                              setExceptions((current) =>
                                current.map((value, position) =>
                                  position === index
                                    ? { ...value, start: event.target.value }
                                    : value,
                                ),
                              )
                            }
                          />
                        </label>
                        <label>
                          End
                          <input
                            required
                            type="time"
                            value={item.end}
                            onChange={(event) =>
                              setExceptions((current) =>
                                current.map((value, position) =>
                                  position === index
                                    ? { ...value, end: event.target.value }
                                    : value,
                                ),
                              )
                            }
                          />
                        </label>
                      </>
                    )}
                    <button
                      type="button"
                      onClick={() =>
                        setExceptions((current) =>
                          current.filter((_, position) => position !== index),
                        )
                      }
                    >
                      Remove exception
                    </button>
                  </div>
                ))}
                <button
                  type="button"
                  onClick={() =>
                    setExceptions((current) => [
                      ...current,
                      {
                        localDate: '',
                        closed: true,
                        label: '',
                        reasonCode: 'hours.holiday',
                        start: '09:00',
                        end: '17:00',
                        endsNextDay: false,
                      },
                    ])
                  }
                >
                  Add exception
                </button>
                <label>
                  Reason
                  <textarea
                    required
                    minLength={10}
                    maxLength={500}
                    value={reason}
                    onChange={(event) => setReason(event.target.value)}
                  />
                </label>
                <div className="form-actions">
                  <button className="primary-button" disabled={busy || !targetKey}>
                    {busy ? 'Saving...' : 'Save hours batch'}
                  </button>
                </div>
              </form>
            </section>
          )}
        </>
      )}
    </>
  );
}

function ServiceCatalogueScreen({ client, organizationId }: AdministrationScreenProps) {
  const [attempt, setAttempt] = useState(0);
  const [state, setState] = useState<
    LoadState<{
      directory: ServiceCatalogue;
      governance: OrganizationGovernanceDirectory;
    }>
  >({ phase: 'loading' });
  const [form, setForm] = useState({
    serviceCode: '',
    displayName: '',
    clinicalName: '',
    description: '',
    codingSystem: '',
    codingCode: '',
    ownerResponsibilityId: '',
    reason: '',
  });
  const [busy, setBusy] = useState(false);
  const [issue, setIssue] = useState('');
  const [editing, setEditing] = useState<ServiceDefinition | null>(null);
  const [action, setAction] = useState<{
    record: ServiceDefinition;
    kind: 'activations' | 'retirements';
  } | null>(null);
  const [actionReason, setActionReason] = useState('');
  useEffect(() => {
    const controller = new AbortController();
    const options = { signal: controller.signal };
    void Promise.all([
      client.getServiceCatalogue(organizationId, options),
      client.getOrganizationGovernanceDirectory(organizationId, options),
    ]).then(([directory, governance]) => {
      if (controller.signal.aborted) return;
      if (!directory.ok) {
        setState({
          phase: 'failure',
          issue: failureMessage(directory),
        });
        return;
      }
      if (!governance.ok) {
        setState({ phase: 'failure', issue: failureMessage(governance) });
        return;
      }
      setState({
        phase: 'ready',
        data: { directory: directory.data, governance: governance.data },
      });
    });
    return () => controller.abort();
  }, [attempt, client, organizationId]);
  const save = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (state.phase !== 'ready' || busy || !state.data.directory.canManage) return;
    setBusy(true);
    setIssue('');
    const body = {
      ...form,
      serviceCode: form.serviceCode.trim().toUpperCase(),
      displayName: form.displayName.trim(),
      clinicalName: form.clinicalName.trim() || null,
      description: form.description.trim() || null,
      codingSystem: form.codingSystem.trim() || null,
      codingCode: form.codingCode.trim() || null,
      ownerResponsibilityId: form.ownerResponsibilityId || null,
      reason: form.reason.trim(),
    };
    const result = editing
      ? await client.updateServiceDefinition(
          organizationId,
          editing.serviceId,
          body,
          `"service:${editing.serviceId}:${editing.lockVersion}"`,
          `service-update:${globalThis.crypto.randomUUID()}`,
        )
      : await client.createServiceDefinition(
          organizationId,
          body,
          `service-create:${globalThis.crypto.randomUUID()}`,
        );
    setBusy(false);
    if (!result.ok) {
      setIssue(failureMessage(result));
      return;
    }
    setForm({
      ...form,
      serviceCode: '',
      displayName: '',
      clinicalName: '',
      description: '',
      codingSystem: '',
      codingCode: '',
      reason: '',
    });
    setEditing(null);
    setState({ phase: 'loading' });
    setAttempt((v) => v + 1);
  };
  const transition = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!action || busy) return;
    setBusy(true);
    setIssue('');
    const result = await client.transitionServiceDefinition(
      organizationId,
      action.record.serviceId,
      action.kind,
      actionReason.trim(),
      `"service:${action.record.serviceId}:${action.record.lockVersion}"`,
      `service-${action.kind}:${globalThis.crypto.randomUUID()}`,
    );
    setBusy(false);
    if (!result.ok) {
      setIssue(failureMessage(result));
      return;
    }
    setAction(null);
    setActionReason('');
    setState({ phase: 'loading' });
    setAttempt((v) => v + 1);
  };
  const services = state.phase === 'ready' ? state.data.directory.services : [];
  return (
    <>
      <PageHeading id="M1-17" />
      {state.phase === 'loading' ? (
        <LoadingPanel />
      ) : state.phase === 'failure' ? (
        <FailurePanel
          issue={state.issue}
          onRetry={() => {
            setState({ phase: 'loading' });
            setAttempt((v) => v + 1);
          }}
        />
      ) : (
        <>
          {issue && <div className="inline-alert error-alert">{issue}</div>}
          <section className="content-panel">
            <div className="panel-heading">
              <div>
                <h2>Service catalogue</h2>
                <p>Stable coded definitions with clinical ownership and governed lifecycle.</p>
              </div>
              <span className="status-badge">{services.length} services</span>
            </div>
            <div className="record-grid">
              {services.map((service) => (
                <article className="record-card" key={service.serviceId}>
                  <div className="record-card-head">
                    <strong>{service.displayName}</strong>
                    <span className="status-badge">{service.status}</span>
                  </div>
                  <p>
                    {service.serviceCode}
                    {service.clinicalName ? ` · ${service.clinicalName}` : ''}
                  </p>
                  <small>
                    {service.codingSystem && service.codingCode
                      ? `${service.codingSystem}: ${service.codingCode}`
                      : 'No external coding'}
                  </small>
                  {Boolean(state.data.directory.canManage) && service.status === 'draft' && (
                    <button
                      type="button"
                      onClick={() => {
                        setEditing(service);
                        setForm({
                          serviceCode: service.serviceCode,
                          displayName: service.displayName,
                          clinicalName: service.clinicalName ?? '',
                          description: service.description ?? '',
                          codingSystem: service.codingSystem ?? '',
                          codingCode: service.codingCode ?? '',
                          ownerResponsibilityId: service.ownerResponsibilityId ?? '',
                          reason: '',
                        });
                      }}
                    >
                      Edit draft
                    </button>
                  )}
                  {Boolean(state.data.directory.canManageLifecycle) && (
                    <div className="form-actions">
                      {service.status === 'draft' && (
                        <button
                          type="button"
                          onClick={() => {
                            setAction({ record: service, kind: 'activations' });
                            setActionReason('');
                          }}
                        >
                          Activate
                        </button>
                      )}
                      {service.status === 'active' && (
                        <button
                          type="button"
                          onClick={() => {
                            setAction({ record: service, kind: 'retirements' });
                            setActionReason('');
                          }}
                        >
                          Retire
                        </button>
                      )}
                    </div>
                  )}
                </article>
              ))}
            </div>
          </section>
          {action && (
            <section className="content-panel">
              <h2>{action.kind === 'activations' ? 'Activate' : 'Retire'} service</h2>
              <form onSubmit={(event) => void transition(event)}>
                <label>
                  Impact reason
                  <textarea
                    required
                    minLength={10}
                    maxLength={500}
                    value={actionReason}
                    onChange={(event) => setActionReason(event.target.value)}
                  />
                </label>
                <div className="form-actions">
                  <button className="primary-button" disabled={busy}>
                    Confirm
                  </button>
                  <button type="button" onClick={() => setAction(null)}>
                    Cancel
                  </button>
                </div>
              </form>
            </section>
          )}
          {Boolean(state.data.directory.canManage) && (
            <section className="content-panel">
              <h2>{editing ? 'Edit service draft' : 'Add service'}</h2>
              <form onSubmit={(event) => void save(event)}>
                <div className="form-grid">
                  <label>
                    Service code
                    <input
                      required
                      pattern="[A-Z][A-Z0-9_.-]{1,39}"
                      value={form.serviceCode}
                      onChange={(event) =>
                        setForm({ ...form, serviceCode: event.target.value.toUpperCase() })
                      }
                    />
                  </label>
                  <label>
                    Display name
                    <input
                      required
                      minLength={2}
                      maxLength={120}
                      value={form.displayName}
                      onChange={(event) => setForm({ ...form, displayName: event.target.value })}
                    />
                  </label>
                  <label>
                    Clinical name
                    <input
                      maxLength={160}
                      value={form.clinicalName}
                      onChange={(event) => setForm({ ...form, clinicalName: event.target.value })}
                    />
                  </label>
                  <label>
                    Coding system
                    <input
                      maxLength={80}
                      value={form.codingSystem}
                      onChange={(event) => setForm({ ...form, codingSystem: event.target.value })}
                    />
                  </label>
                  <label>
                    Coding code
                    <input
                      maxLength={80}
                      value={form.codingCode}
                      onChange={(event) => setForm({ ...form, codingCode: event.target.value })}
                    />
                  </label>
                  <label>
                    Clinical owner
                    <select
                      value={form.ownerResponsibilityId}
                      onChange={(event) =>
                        setForm({ ...form, ownerResponsibilityId: event.target.value })
                      }
                    >
                      <option value="">No clinical owner</option>
                      {state.data.governance.responsibilities
                        .filter(
                          (item) =>
                            item.responsibilityType === 'clinical' && item.status === 'active',
                        )
                        .map((item) => (
                          <option key={item.responsibilityId} value={item.responsibilityId}>
                            {item.assigneeDisplay}
                          </option>
                        ))}
                    </select>
                  </label>
                  <label className="full-width">
                    Description
                    <textarea
                      maxLength={500}
                      value={form.description}
                      onChange={(event) => setForm({ ...form, description: event.target.value })}
                    />
                  </label>
                  <label className="full-width">
                    Reason
                    <textarea
                      required
                      minLength={10}
                      maxLength={500}
                      value={form.reason}
                      onChange={(event) => setForm({ ...form, reason: event.target.value })}
                    />
                  </label>
                </div>
                <div className="form-actions">
                  <button className="primary-button" disabled={busy}>
                    {busy ? 'Saving...' : editing ? 'Save draft' : 'Add service'}
                  </button>
                  {editing && (
                    <button type="button" onClick={() => setEditing(null)}>
                      Cancel edit
                    </button>
                  )}
                </div>
              </form>
            </section>
          )}
        </>
      )}
    </>
  );
}

function ServiceAssignmentScreen({ client, organizationId }: AdministrationScreenProps) {
  const [attempt, setAttempt] = useState(0);
  const [state, setState] = useState<
    LoadState<{
      directory: ServiceAssignmentDirectory;
      services: ServiceDefinition[];
      facilities: FacilityDirectory;
      locations: HoursTarget[];
    }>
  >({ phase: 'loading' });
  const [form, setForm] = useState({
    serviceId: '',
    facilityId: '',
    locationId: '',
    capacity: '',
    availabilityNotes: '',
    prerequisites: [] as string[],
    effectiveFrom: new Date(applicationStartedAt + 180000).toISOString().slice(0, 16),
    effectiveTo: '',
    reason: '',
  });
  const [busy, setBusy] = useState(false);
  const [issue, setIssue] = useState('');
  const [editing, setEditing] = useState<ServiceAssignment | null>(null);
  const [action, setAction] = useState<{
    record: ServiceAssignment;
    kind: 'activations' | 'suspensions' | 'endings' | 'cancellations';
  } | null>(null);
  const [actionReason, setActionReason] = useState('');
  useEffect(() => {
    const controller = new AbortController();
    const options = { signal: controller.signal };
    void Promise.all([
      client.getServiceAssignmentDirectory(organizationId, options),
      client.getServiceCatalogue(organizationId, options),
      client.getFacilityDirectory(organizationId, {}, options),
    ]).then(async ([directory, catalogue, facilities]) => {
      if (controller.signal.aborted) return;
      if (!directory.ok) {
        setState({
          phase: 'failure',
          issue: failureMessage(directory),
        });
        return;
      }
      if (!catalogue.ok) {
        setState({ phase: 'failure', issue: failureMessage(catalogue) });
        return;
      }
      if (!facilities.ok) {
        setState({ phase: 'failure', issue: failureMessage(facilities) });
        return;
      }
      const locations: HoursTarget[] = [];
      for (const facility of facilities.data.facilities) {
        const result = await client.getServiceLocationDirectory(
          organizationId,
          facility.facilityId,
          options,
        );
        if (result.ok)
          for (const location of result.data.locations)
            if (location.status === 'active')
              locations.push({
                type: 'location',
                id: location.locationId,
                facilityId: facility.facilityId,
                label: `${facility.displayName} · ${location.name}`,
              });
      }
      if (controller.signal.aborted) return;
      const services = catalogue.data.services;
      setState({
        phase: 'ready',
        data: { directory: directory.data, services, facilities: facilities.data, locations },
      });
      setForm((current) => ({
        ...current,
        serviceId:
          current.serviceId || services.find((item) => item.status === 'active')?.serviceId || '',
        facilityId:
          current.facilityId ||
          facilities.data.facilities.find((item) => item.status === 'active')?.facilityId ||
          '',
      }));
    });
    return () => controller.abort();
  }, [attempt, client, organizationId]);
  const save = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (state.phase !== 'ready' || busy || !state.data.directory.canManage) return;
    setBusy(true);
    setIssue('');
    const body = {
      serviceId: form.serviceId,
      facilityId: form.facilityId,
      locationId: form.locationId || null,
      capacity: form.capacity ? Number(form.capacity) : null,
      availabilityNotes: form.availabilityNotes.trim() || null,
      prerequisites: form.prerequisites,
      effectiveFrom: new Date(form.effectiveFrom).toISOString(),
      effectiveTo: form.effectiveTo ? new Date(form.effectiveTo).toISOString() : null,
      reason: form.reason.trim(),
    };
    const result = editing
      ? await client.updateServiceAssignment(
          organizationId,
          editing.assignmentId,
          body,
          `"service-assignment:${editing.assignmentId}:${editing.lockVersion}"`,
          `assignment-update:${globalThis.crypto.randomUUID()}`,
        )
      : await client.createServiceAssignment(
          organizationId,
          body,
          `assignment-create:${globalThis.crypto.randomUUID()}`,
        );
    setBusy(false);
    if (!result.ok) {
      setIssue(failureMessage(result));
      return;
    }
    setForm({ ...form, capacity: '', availabilityNotes: '', prerequisites: [], reason: '' });
    setEditing(null);
    setState({ phase: 'loading' });
    setAttempt((value) => value + 1);
  };
  const transition = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!action || busy) return;
    setBusy(true);
    setIssue('');
    const result = await client.transitionServiceAssignment(
      organizationId,
      action.record.assignmentId,
      action.kind,
      action.record.status,
      actionReason.trim(),
      `"service-assignment:${action.record.assignmentId}:${action.record.lockVersion}"`,
      `assignment-${action.kind}:${globalThis.crypto.randomUUID()}`,
    );
    setBusy(false);
    if (!result.ok) {
      setIssue(failureMessage(result));
      return;
    }
    setAction(null);
    setActionReason('');
    setState({ phase: 'loading' });
    setAttempt((value) => value + 1);
  };
  const assignments = state.phase === 'ready' ? state.data.directory.assignments : [];
  const serviceName = (id: string) =>
    state.phase === 'ready'
      ? (state.data.services.find((item) => item.serviceId === id)?.displayName ?? id)
      : id;
  const facilityName = (id: string) =>
    state.phase === 'ready'
      ? (state.data.facilities.facilities.find((item) => item.facilityId === id)?.displayName ?? id)
      : id;
  return (
    <>
      <PageHeading id="M1-18" />
      {state.phase === 'loading' ? (
        <LoadingPanel />
      ) : state.phase === 'failure' ? (
        <FailurePanel
          issue={state.issue}
          onRetry={() => {
            setState({ phase: 'loading' });
            setAttempt((value) => value + 1);
          }}
        />
      ) : (
        <>
          {issue && <div className="inline-alert error-alert">{issue}</div>}
          <section className="content-panel">
            <div className="panel-heading">
              <div>
                <h2>Facility services</h2>
                <p>
                  Effective, non-overlapping delivery assignments to eligible facilities and
                  locations.
                </p>
              </div>
              <span className="status-badge">{assignments.length} assignments</span>
            </div>
            <div className="record-grid">
              {assignments.map((record) => (
                <article className="record-card" key={record.assignmentId}>
                  <div className="record-card-head">
                    <strong>{serviceName(record.serviceId)}</strong>
                    <span className="status-badge">{record.status}</span>
                  </div>
                  <p>
                    {facilityName(record.facilityId)}
                    {record.locationId ? ' · selected location' : ''}
                  </p>
                  <small>
                    {record.capacity == null ? 'Unbounded capacity' : `Capacity ${record.capacity}`}{' '}
                    · {new Date(record.effectiveFrom).toLocaleString()}
                  </small>
                  {Boolean(state.data.directory.canManage) && record.status === 'scheduled' && (
                    <button
                      type="button"
                      onClick={() => {
                        setEditing(record);
                        setForm({
                          serviceId: record.serviceId,
                          facilityId: record.facilityId,
                          locationId: record.locationId ?? '',
                          capacity: record.capacity?.toString() ?? '',
                          availabilityNotes: record.availabilityNotes ?? '',
                          prerequisites: record.prerequisites,
                          effectiveFrom: new Date(record.effectiveFrom).toISOString().slice(0, 16),
                          effectiveTo: record.effectiveTo
                            ? new Date(record.effectiveTo).toISOString().slice(0, 16)
                            : '',
                          reason: '',
                        });
                      }}
                    >
                      Edit scheduled assignment
                    </button>
                  )}
                  {Boolean(state.data.directory.canManageLifecycle) && (
                    <div className="form-actions">
                      {record.status === 'scheduled' && (
                        <>
                          <button
                            type="button"
                            onClick={() => setAction({ record, kind: 'activations' })}
                          >
                            Activate now
                          </button>
                          <button
                            type="button"
                            onClick={() => setAction({ record, kind: 'cancellations' })}
                          >
                            Cancel
                          </button>
                        </>
                      )}
                      {record.status === 'active' && (
                        <>
                          <button
                            type="button"
                            onClick={() => setAction({ record, kind: 'suspensions' })}
                          >
                            Suspend
                          </button>
                          <button
                            type="button"
                            onClick={() => setAction({ record, kind: 'endings' })}
                          >
                            End
                          </button>
                        </>
                      )}
                      {record.status === 'suspended' && (
                        <button
                          type="button"
                          onClick={() => setAction({ record, kind: 'endings' })}
                        >
                          End
                        </button>
                      )}
                    </div>
                  )}
                </article>
              ))}
            </div>
          </section>
          {action && (
            <section className="content-panel">
              <h2>Confirm assignment lifecycle change</h2>
              <p>
                An ended or cancelled assignment is terminal. Recovery from suspension requires a
                new effective assignment.
              </p>
              <form onSubmit={(event) => void transition(event)}>
                <label>
                  Impact reason
                  <textarea
                    required
                    minLength={10}
                    maxLength={500}
                    value={actionReason}
                    onChange={(event) => setActionReason(event.target.value)}
                  />
                </label>
                <div className="form-actions">
                  <button className="primary-button" disabled={busy}>
                    Confirm
                  </button>
                  <button type="button" onClick={() => setAction(null)}>
                    Cancel
                  </button>
                </div>
              </form>
            </section>
          )}
          {Boolean(state.data.directory.canManage) && (
            <section className="content-panel">
              <h2>{editing ? 'Edit scheduled assignment' : 'Assign service'}</h2>
              <form onSubmit={(event) => void save(event)}>
                <div className="form-grid">
                  <label>
                    Active service
                    <select
                      required
                      value={form.serviceId}
                      onChange={(event) => setForm({ ...form, serviceId: event.target.value })}
                    >
                      <option value="">Select service</option>
                      {state.data.services
                        .filter((item) => item.status === 'active')
                        .map((item) => (
                          <option key={item.serviceId} value={item.serviceId}>
                            {item.displayName}
                          </option>
                        ))}
                    </select>
                  </label>
                  <label>
                    Active facility
                    <select
                      required
                      value={form.facilityId}
                      onChange={(event) =>
                        setForm({ ...form, facilityId: event.target.value, locationId: '' })
                      }
                    >
                      <option value="">Select facility</option>
                      {state.data.facilities.facilities
                        .filter((item) => item.status === 'active')
                        .map((item) => (
                          <option key={item.facilityId} value={item.facilityId}>
                            {item.displayName}
                          </option>
                        ))}
                    </select>
                  </label>
                  <label>
                    Location
                    <select
                      value={form.locationId}
                      onChange={(event) => setForm({ ...form, locationId: event.target.value })}
                    >
                      <option value="">Entire facility</option>
                      {state.data.locations
                        .filter((item) => item.facilityId === form.facilityId)
                        .map((item) => (
                          <option key={item.id} value={item.id}>
                            {item.label}
                          </option>
                        ))}
                    </select>
                  </label>
                  <label>
                    Capacity
                    <input
                      type="number"
                      min="1"
                      value={form.capacity}
                      onChange={(event) => setForm({ ...form, capacity: event.target.value })}
                    />
                  </label>
                  <label>
                    Effective from
                    <input
                      required
                      type="datetime-local"
                      value={form.effectiveFrom}
                      onChange={(event) => setForm({ ...form, effectiveFrom: event.target.value })}
                    />
                  </label>
                  <label>
                    Effective to
                    <input
                      type="datetime-local"
                      value={form.effectiveTo}
                      onChange={(event) => setForm({ ...form, effectiveTo: event.target.value })}
                    />
                  </label>
                  <label className="full-width">
                    Availability notes
                    <textarea
                      maxLength={500}
                      value={form.availabilityNotes}
                      onChange={(event) =>
                        setForm({ ...form, availabilityNotes: event.target.value })
                      }
                    />
                  </label>
                  <label className="full-width">
                    Prerequisites
                    <select
                      multiple
                      value={form.prerequisites}
                      onChange={(event) =>
                        setForm({
                          ...form,
                          prerequisites: Array.from(
                            event.target.selectedOptions,
                            (option) => option.value,
                          ),
                        })
                      }
                    >
                      <option value="appointment_required">Appointment required</option>
                      <option value="referral_required">Referral required</option>
                      <option value="authorization_required">Authorization required</option>
                      <option value="age_restriction">Age restriction</option>
                      <option value="accessibility_review">Accessibility review</option>
                    </select>
                  </label>
                  <label className="full-width">
                    Reason
                    <textarea
                      required
                      minLength={10}
                      maxLength={500}
                      value={form.reason}
                      onChange={(event) => setForm({ ...form, reason: event.target.value })}
                    />
                  </label>
                </div>
                <div className="form-actions">
                  <button className="primary-button" disabled={busy}>
                    {busy ? 'Saving...' : editing ? 'Save assignment' : 'Assign service'}
                  </button>
                  {editing && (
                    <button type="button" onClick={() => setEditing(null)}>
                      Cancel edit
                    </button>
                  )}
                </div>
              </form>
            </section>
          )}
        </>
      )}
    </>
  );
}

function IdentifierSchemeScreen({ client, organizationId }: AdministrationScreenProps) {
  const [attempt, setAttempt] = useState(0);
  const [state, setState] = useState<
    LoadState<{
      directory: IdentifierSchemeDirectory;
      facilities: FacilityDirectory;
      services: ServiceDefinition[];
    }>
  >({ phase: 'loading' });
  const [form, setForm] = useState({
    schemeKey: '',
    scopeType: 'organization',
    scopeId: '',
    description: '',
    prefix: '',
    pattern: '^[A-Z0-9]+$',
    alphabet: '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ',
    checkDigitAlgorithm: '',
    sequenceStart: '1',
    sequenceIncrement: '1',
    padding: '8',
    effectiveFrom: new Date(applicationStartedAt + 180000).toISOString().slice(0, 16),
    reason: '',
  });
  const [busy, setBusy] = useState(false);
  const [issue, setIssue] = useState('');
  const [versioning, setVersioning] = useState<IdentifierScheme | null>(null);
  useEffect(() => {
    const controller = new AbortController();
    const options = { signal: controller.signal };
    void Promise.all([
      client.getIdentifierSchemeDirectory(organizationId, options),
      client.getFacilityDirectory(organizationId, {}, options),
      client.getServiceCatalogue(organizationId, options),
    ]).then(([directory, facilities, services]) => {
      if (controller.signal.aborted) return;
      if (!directory.ok) {
        setState({
          phase: 'failure',
          issue: failureMessage(directory),
        });
        return;
      }
      if (!facilities.ok) {
        setState({ phase: 'failure', issue: failureMessage(facilities) });
        return;
      }
      if (!services.ok) {
        setState({ phase: 'failure', issue: failureMessage(services) });
        return;
      }
      setState({
        phase: 'ready',
        data: {
          directory: directory.data,
          facilities: facilities.data,
          services: services.data.services,
        },
      });
    });
    return () => controller.abort();
  }, [attempt, client, organizationId]);
  const save = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (state.phase !== 'ready' || busy || !state.data.directory.canManage) return;
    const start = Number(form.sequenceStart),
      increment = Number(form.sequenceIncrement),
      padding = Number(form.padding);
    setBusy(true);
    setIssue('');
    const body = {
      versionId: versioning?.versions[0]?.versionId ?? null,
      schemeKey: form.schemeKey.trim().toUpperCase(),
      scopeType: form.scopeType,
      scopeId: form.scopeType === 'organization' ? null : form.scopeId,
      description: form.description.trim() || null,
      prefix: form.prefix.trim(),
      pattern: form.pattern.trim(),
      alphabet: form.alphabet.trim(),
      checkDigitAlgorithm: form.checkDigitAlgorithm || null,
      sequenceStart: start,
      sequenceIncrement: increment,
      padding,
      effectiveFrom: new Date(form.effectiveFrom).toISOString(),
      reason: form.reason.trim(),
    };
    const latest = versioning?.versions[0];
    const result =
      versioning && latest
        ? await client.createIdentifierSchemeVersion(
            organizationId,
            versioning.schemeId,
            body,
            `"identifier-scheme:${versioning.schemeId}:${versioning.lockVersion}:${latest.versionId}:${latest.lockVersion}"`,
            `scheme-version-create:${globalThis.crypto.randomUUID()}`,
          )
        : await client.createIdentifierScheme(
            organizationId,
            body,
            `scheme-create:${globalThis.crypto.randomUUID()}`,
          );
    setBusy(false);
    if (!result.ok) {
      setIssue(failureMessage(result));
      return;
    }
    setForm({ ...form, schemeKey: '', description: '', reason: '' });
    setVersioning(null);
    setState({ phase: 'loading' });
    setAttempt((value) => value + 1);
  };
  const schemes = state.phase === 'ready' ? state.data.directory.schemes : [];
  return (
    <>
      <PageHeading id="M1-19" />
      {state.phase === 'loading' ? (
        <LoadingPanel />
      ) : state.phase === 'failure' ? (
        <FailurePanel
          issue={state.issue}
          onRetry={() => {
            setState({ phase: 'loading' });
            setAttempt((value) => value + 1);
          }}
        />
      ) : (
        <>
          {issue && <div className="inline-alert error-alert">{issue}</div>}
          <section className="content-panel">
            <div className="panel-heading">
              <div>
                <h2>Identifier schemes</h2>
                <p>
                  Immutable, scoped sequence versions. Activation is completed only through
                  independent M1-21 approval.
                </p>
              </div>
              <span className="status-badge">{schemes.length} schemes</span>
            </div>
            <div className="record-grid">
              {schemes.map((scheme) => (
                <article className="record-card" key={scheme.schemeId}>
                  <div className="record-card-head">
                    <strong>{scheme.schemeKey}</strong>
                    <span className="status-badge">{scheme.status}</span>
                  </div>
                  <p>
                    {scheme.scopeType}
                    {scheme.description ? ` · ${scheme.description}` : ''}
                  </p>
                  {scheme.versions.map((version) => (
                    <div key={version.versionId}>
                      <small>
                        v{version.versionNumber} · {version.status} ·{' '}
                        {version.previewSamples.join(', ')}
                      </small>
                    </div>
                  ))}
                  {Boolean(state.data.directory.canManage) &&
                    scheme.status !== 'retired' &&
                    !scheme.versions.some((version) => version.status === 'draft') && (
                      <button
                        type="button"
                        onClick={() => {
                          const latest = scheme.versions[0];
                          setVersioning(scheme);
                          setForm({
                            schemeKey: scheme.schemeKey,
                            scopeType: scheme.scopeType,
                            scopeId: scheme.scopeId ?? '',
                            description: scheme.description ?? '',
                            prefix: latest?.prefix ?? '',
                            pattern: latest?.pattern ?? '^[A-Z0-9]+$',
                            alphabet: latest?.alphabet ?? '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ',
                            checkDigitAlgorithm: latest?.checkDigitAlgorithm ?? '',
                            sequenceStart: latest
                              ? String(latest.sequenceStart + latest.sequenceIncrement)
                              : '1',
                            sequenceIncrement: String(latest?.sequenceIncrement ?? 1),
                            padding: String(latest?.padding ?? 8),
                            effectiveFrom: new Date(Date.now() + 180000).toISOString().slice(0, 16),
                            reason: '',
                          });
                        }}
                      >
                        Create next version
                      </button>
                    )}
                </article>
              ))}
            </div>
          </section>
          {Boolean(state.data.directory.canManage) && (
            <section className="content-panel">
              <h2>
                {versioning
                  ? `Create next ${versioning.schemeKey} version`
                  : 'Create scheme and first version'}
              </h2>
              <form onSubmit={(event) => void save(event)}>
                <div className="form-grid">
                  <label>
                    Scheme key
                    <input
                      required
                      disabled={Boolean(versioning)}
                      pattern="[A-Z][A-Z0-9_.-]{1,39}"
                      value={form.schemeKey}
                      onChange={(event) =>
                        setForm({ ...form, schemeKey: event.target.value.toUpperCase() })
                      }
                    />
                  </label>
                  <label>
                    Scope
                    <select
                      disabled={Boolean(versioning)}
                      value={form.scopeType}
                      onChange={(event) =>
                        setForm({ ...form, scopeType: event.target.value, scopeId: '' })
                      }
                    >
                      <option value="organization">Organization</option>
                      <option value="facility">Facility</option>
                      <option value="service">Service</option>
                    </select>
                  </label>
                  {form.scopeType !== 'organization' && (
                    <label>
                      Scope record
                      <select
                        required
                        value={form.scopeId}
                        onChange={(event) => setForm({ ...form, scopeId: event.target.value })}
                      >
                        <option value="">Select scope</option>
                        {form.scopeType === 'facility'
                          ? state.data.facilities.facilities.map((item) => (
                              <option key={item.facilityId} value={item.facilityId}>
                                {item.displayName}
                              </option>
                            ))
                          : state.data.services.map((item) => (
                              <option key={item.serviceId} value={item.serviceId}>
                                {item.displayName}
                              </option>
                            ))}
                      </select>
                    </label>
                  )}
                  <label>
                    Prefix
                    <input
                      maxLength={20}
                      value={form.prefix}
                      onChange={(event) => setForm({ ...form, prefix: event.target.value })}
                    />
                  </label>
                  <label>
                    Validation pattern
                    <input
                      required
                      value={form.pattern}
                      onChange={(event) => setForm({ ...form, pattern: event.target.value })}
                    />
                  </label>
                  <label>
                    Alphabet
                    <input
                      required
                      value={form.alphabet}
                      onChange={(event) => setForm({ ...form, alphabet: event.target.value })}
                    />
                  </label>
                  <label>
                    Check digit
                    <select
                      value={form.checkDigitAlgorithm}
                      onChange={(event) =>
                        setForm({ ...form, checkDigitAlgorithm: event.target.value })
                      }
                    >
                      <option value="">None</option>
                      <option value="luhn_mod_n">Luhn mod N</option>
                      <option value="mod_11">Mod 11</option>
                    </select>
                  </label>
                  <label>
                    Sequence start
                    <input
                      required
                      type="number"
                      min="0"
                      value={form.sequenceStart}
                      onChange={(event) => setForm({ ...form, sequenceStart: event.target.value })}
                    />
                  </label>
                  <label>
                    Increment
                    <input
                      required
                      type="number"
                      min="1"
                      max="1000000"
                      value={form.sequenceIncrement}
                      onChange={(event) =>
                        setForm({ ...form, sequenceIncrement: event.target.value })
                      }
                    />
                  </label>
                  <label>
                    Padding
                    <input
                      required
                      type="number"
                      min="1"
                      max="20"
                      value={form.padding}
                      onChange={(event) => setForm({ ...form, padding: event.target.value })}
                    />
                  </label>
                  <label>
                    Effective from
                    <input
                      required
                      type="datetime-local"
                      value={form.effectiveFrom}
                      onChange={(event) => setForm({ ...form, effectiveFrom: event.target.value })}
                    />
                  </label>
                  <label className="full-width">
                    Description
                    <textarea
                      maxLength={500}
                      value={form.description}
                      onChange={(event) => setForm({ ...form, description: event.target.value })}
                    />
                  </label>
                  <label className="full-width">
                    Reason
                    <textarea
                      required
                      minLength={10}
                      maxLength={500}
                      value={form.reason}
                      onChange={(event) => setForm({ ...form, reason: event.target.value })}
                    />
                  </label>
                </div>
                <div className="form-actions">
                  <button className="primary-button" disabled={busy}>
                    {busy ? 'Creating...' : versioning ? 'Create version' : 'Create scheme version'}
                  </button>
                  {versioning && (
                    <button type="button" onClick={() => setVersioning(null)}>
                      Cancel version
                    </button>
                  )}
                </div>
              </form>
            </section>
          )}
        </>
      )}
    </>
  );
}

function ConfigurationActivationScreen({ client, organizationId }: AdministrationScreenProps) {
  const [attempt, setAttempt] = useState(0);
  const [state, setState] = useState<LoadState<ConfigurationActivationDirectory>>({
    phase: 'loading',
  });
  const [form, setForm] = useState({
    changeSummary: '',
    reason: '',
    requestedEffectiveAt: new Date(applicationStartedAt + 180000).toISOString().slice(0, 16),
  });
  const [action, setAction] = useState<{
    record: ConfigurationActivation;
    kind: 'submit' | 'approve' | 'reject' | 'activate';
  } | null>(null);
  const [actionReason, setActionReason] = useState('');
  const [selectedChanges, setSelectedChanges] = useState<string[]>([]);
  const [decisionCode, setDecisionCode] = useState('configuration.reviewed');
  const [effectiveFrom, setEffectiveFrom] = useState(
    new Date(applicationStartedAt + 180000).toISOString().slice(0, 16),
  );
  const [busy, setBusy] = useState(false);
  const [issue, setIssue] = useState('');
  useEffect(() => {
    const controller = new AbortController();
    void client
      .getConfigurationActivationDirectory(organizationId, { signal: controller.signal })
      .then((result) => {
        if (controller.signal.aborted) return;
        if (!result.ok) setState({ phase: 'failure', issue: failureMessage(result) });
        else {
          setState({ phase: 'ready', data: result.data });
          setSelectedChanges(
            result.data.pendingChanges
              .filter((item) => item.changeType === 'activated')
              .map((item) => `${item.subjectType}:${item.subjectId}`),
          );
        }
      });
    return () => controller.abort();
  }, [attempt, client, organizationId]);
  const reload = () => {
    setState({ phase: 'loading' });
    setAttempt((value) => value + 1);
  };
  const validate = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (state.phase !== 'ready' || busy || !state.data.canValidate) return;
    const changeItems = state.data.pendingChanges
      .filter((item) => selectedChanges.includes(`${item.subjectType}:${item.subjectId}`))
      .map(({ subjectType, subjectId, expectedRevision, changeType }) => ({
        subjectType,
        subjectId,
        expectedRevision,
        changeType,
      }));
    if (changeItems.length === 0) {
      setIssue('Select at least one typed business revision to validate.');
      return;
    }
    setBusy(true);
    setIssue('');
    const result = await client.validateConfiguration(
      organizationId,
      {
        changeSummary: form.changeSummary.trim(),
        reason: form.reason.trim(),
        requestedEffectiveAt: new Date(form.requestedEffectiveAt).toISOString(),
        changeItems,
      },
      `configuration-validate:${globalThis.crypto.randomUUID()}`,
    );
    setBusy(false);
    if (!result.ok) {
      setIssue(failureMessage(result));
      return;
    }
    setForm({ ...form, changeSummary: '', reason: '' });
    reload();
  };
  const execute = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!action || busy) return;
    const { approvalId, resultDigest, validationResultId } = action.record;
    if (!validationResultId || !resultDigest) return;
    if (action.kind === 'activate' && !approvalId) return;
    setBusy(true);
    setIssue('');
    const record = action.record;
    const etag = `"configuration:${record.configurationId}:${record.lockVersion}"`;
    const key = `configuration-${action.kind}:${globalThis.crypto.randomUUID()}`;
    let result;
    if (action.kind === 'submit')
      result = await client.submitConfiguration(
        organizationId,
        record.configurationId,
        {
          resultId: validationResultId,
          resultDigest,
          reason: actionReason.trim(),
        },
        etag,
        key,
      );
    else if (action.kind === 'activate') {
      if (!approvalId) {
        setBusy(false);
        return;
      }
      result = await client.activateConfiguration(
        organizationId,
        record.configurationId,
        {
          approvalId,
          resultDigest,
          effectiveFrom: new Date(effectiveFrom).toISOString(),
          reason: actionReason.trim(),
        },
        etag,
        key,
      );
    } else
      result = await client.decideConfiguration(
        organizationId,
        record.configurationId,
        {
          resultId: validationResultId,
          resultDigest,
          approve: action.kind === 'approve',
          decisionCode: decisionCode.trim(),
          reason: actionReason.trim(),
        },
        etag,
        key,
      );
    setBusy(false);
    if (!result.ok) {
      setIssue(failureMessage(result));
      return;
    }
    setAction(null);
    setActionReason('');
    reload();
  };
  const configurations = state.phase === 'ready' ? state.data.configurations : [];
  return (
    <>
      <PageHeading id="M1-21" />
      {state.phase === 'loading' ? (
        <LoadingPanel />
      ) : state.phase === 'failure' ? (
        <FailurePanel issue={state.issue} onRetry={reload} />
      ) : (
        <>
          {issue && <div className="inline-alert error-alert">{issue}</div>}
          <section className="content-panel">
            <div className="panel-heading">
              <div>
                <h2>Review and activate</h2>
                <p>
                  Validation, maker submission, independent decision, and activation remain bound to
                  one exact digest.
                </p>
              </div>
              <span className="status-badge">{configurations.length} versions</span>
            </div>
            <div className="record-grid">
              {configurations.map((record) => (
                <article className="record-card" key={record.configurationId}>
                  <div className="record-card-head">
                    <strong>{record.displayNumber}</strong>
                    <span className="status-badge">{record.status}</span>
                  </div>
                  <p>{record.changeSummary}</p>
                  <small>
                    {record.blockerCount ?? 0} blockers · {record.warningCount ?? 0} warnings
                    {record.validationExpiresAt
                      ? ` · validation expires ${new Date(record.validationExpiresAt).toLocaleString()}`
                      : ''}
                  </small>
                  <div className="form-actions">
                    {record.canSubmit && (
                      <button
                        type="button"
                        onClick={() => {
                          setAction({ record, kind: 'submit' });
                          setActionReason('');
                        }}
                      >
                        Submit for approval
                      </button>
                    )}
                    {record.canApprove && (
                      <>
                        <button
                          type="button"
                          onClick={() => {
                            setAction({ record, kind: 'approve' });
                            setActionReason('');
                          }}
                        >
                          Approve
                        </button>
                        <button
                          type="button"
                          onClick={() => {
                            setAction({ record, kind: 'reject' });
                            setActionReason('');
                          }}
                        >
                          Reject
                        </button>
                      </>
                    )}
                    {record.canActivate && (
                      <button
                        type="button"
                        onClick={() => {
                          setAction({ record, kind: 'activate' });
                          setActionReason('');
                        }}
                      >
                        Activate
                      </button>
                    )}
                  </div>
                </article>
              ))}
            </div>
          </section>
          {action && (
            <section className="content-panel">
              <h2>
                {action.kind === 'submit'
                  ? 'Submit configuration'
                  : action.kind === 'activate'
                    ? 'Activate configuration'
                    : action.kind === 'approve'
                      ? 'Approve configuration'
                      : 'Reject configuration'}
              </h2>
              <p>
                MFA, recent authentication, actor separation, digest freshness, parent state, and
                readiness are rechecked by the server.
              </p>
              <form onSubmit={(event) => void execute(event)}>
                {(action.kind === 'approve' || action.kind === 'reject') && (
                  <label>
                    Decision code
                    <input
                      required
                      pattern="[a-z][a-z0-9._:-]*"
                      value={decisionCode}
                      onChange={(event) => setDecisionCode(event.target.value)}
                    />
                  </label>
                )}
                {action.kind === 'activate' && (
                  <label>
                    Effective from
                    <input
                      required
                      type="datetime-local"
                      value={effectiveFrom}
                      onChange={(event) => setEffectiveFrom(event.target.value)}
                    />
                  </label>
                )}
                <label>
                  Reason and warning acknowledgement
                  <textarea
                    required
                    minLength={10}
                    maxLength={500}
                    value={actionReason}
                    onChange={(event) => setActionReason(event.target.value)}
                  />
                </label>
                <div className="form-actions">
                  <button className="primary-button" disabled={busy}>
                    Confirm
                  </button>
                  <button type="button" onClick={() => setAction(null)}>
                    Cancel
                  </button>
                </div>
              </form>
            </section>
          )}
          {Boolean(state.data.canValidate) && (
            <section className="content-panel">
              <h2>Validate configuration candidate</h2>
              <p>
                The server derives the active parent, canonical tenant baseline, revisions, and
                result digest. The browser cannot provide them.
              </p>
              <form onSubmit={(event) => void validate(event)}>
                <fieldset>
                  <legend>Business revisions to transition atomically</legend>
                  {state.data.pendingChanges.map((item) => {
                    const key = `${item.subjectType}:${item.subjectId}`;
                    return (
                      <label key={key}>
                        <input
                          type="checkbox"
                          checked={selectedChanges.includes(key)}
                          onChange={(event) =>
                            setSelectedChanges((current) =>
                              event.target.checked
                                ? [...current, key]
                                : current.filter((value) => value !== key),
                            )
                          }
                        />{' '}
                        {item.label} · {humanize(item.subjectType)} · {item.currentStatus} →{' '}
                        {item.changeType}
                      </label>
                    );
                  })}
                </fieldset>
                <label>
                  Change summary
                  <input
                    required
                    minLength={2}
                    maxLength={500}
                    value={form.changeSummary}
                    onChange={(event) => setForm({ ...form, changeSummary: event.target.value })}
                  />
                </label>
                <label>
                  Requested effective time
                  <input
                    required
                    type="datetime-local"
                    value={form.requestedEffectiveAt}
                    onChange={(event) =>
                      setForm({ ...form, requestedEffectiveAt: event.target.value })
                    }
                  />
                </label>
                <label>
                  Validation reason
                  <textarea
                    required
                    minLength={10}
                    maxLength={500}
                    value={form.reason}
                    onChange={(event) => setForm({ ...form, reason: event.target.value })}
                  />
                </label>
                <button className="primary-button" disabled={busy}>
                  {busy ? 'Validating...' : 'Run validation'}
                </button>
              </form>
            </section>
          )}
        </>
      )}
    </>
  );
}

type EvidenceItem = ConfigurationHistoryItem | AuditEvidenceItem;

function EvidenceScreen({
  client,
  id,
  organizationId,
}: AdministrationScreenProps & { id: 'M1-22' | 'M1-23' }) {
  const [a, setAttempt] = useState(0);
  const exportPoll = useRef({ attempts: 0, signature: '' });
  const [state, setState] = useState<
    LoadState<{
      page: ConfigurationHistoryPage | AuditEvidencePage;
      exports: EvidenceExportDirectory;
    }>
  >({ phase: 'loading' });
  const [filters, setFilters] = useState({
    from: new Date(applicationStartedAt - 30 * 86400000).toISOString().slice(0, 16),
    to: new Date().toISOString().slice(0, 16),
    status: '',
    changeType: '',
    actorId: '',
    subjectType: '',
    subjectId: '',
    operation: '',
    eventName: '',
    schemaVersion: '',
    outcome: '',
    risk: '',
    correlationId: '',
  });
  const [cursor, setCursor] = useState('');
  const [selected, setSelected] = useState<string[]>([]);
  const [purpose, setPurpose] =
    useState<EvidenceExportRequest['purposeCode']>('configuration_review');
  const [legalBasis, setLegalBasis] = useState('governance.module1');
  const [format, setFormat] = useState<EvidenceExportRequest['format']>('csv');
  const [detailProjection, setDetailProjection] = useState(false);
  const [reason, setReason] = useState('');
  const [detail, setDetail] = useState<AuditEvidenceDetail | null>(null);
  const [download, setDownload] = useState<{
    url: string;
    filename: string;
    expiresAt: string;
  } | null>(null);
  const [action, setAction] = useState<{ job: EvidenceExportJob; authorize: boolean } | null>(null);
  const [busy, setBusy] = useState(false);
  const [pausedExportSignature, setPausedExportSignature] = useState('');
  const [issue, setIssue] = useState('');
  useEffect(() => {
    const controller = new AbortController();
    const query: Record<string, string | number | undefined> = {
      from: filters.from ? new Date(filters.from).toISOString() : undefined,
      to: filters.to ? new Date(filters.to).toISOString() : undefined,
      correlationId: filters.correlationId || undefined,
      actorId: filters.actorId || undefined,
      subjectType: filters.subjectType || undefined,
      subjectId: filters.subjectId || undefined,
      limit: 25,
      cursor: cursor || undefined,
      ...(id === 'M1-22'
        ? {
            status: filters.status || undefined,
            changeType: filters.changeType || undefined,
          }
        : {
            operation: filters.operation || undefined,
            eventName: filters.eventName || undefined,
            schemaVersion: filters.schemaVersion ? Number(filters.schemaVersion) : undefined,
            outcome: filters.outcome || undefined,
            risk: filters.risk || undefined,
          }),
    };
    const page =
      id === 'M1-22'
        ? client.queryConfigurationHistory(organizationId, query, { signal: controller.signal })
        : client.queryAuditEvidence(organizationId, query, { signal: controller.signal });
    void Promise.all([
      page,
      client.getEvidenceExportDirectory(organizationId, id === 'M1-23' ? 'audit' : 'history', {
        signal: controller.signal,
      }),
    ]).then(([projection, exports]) => {
      if (controller.signal.aborted) return;
      if (!projection.ok) {
        setState({
          phase: 'failure',
          issue: failureMessage(projection),
        });
        return;
      }
      if (!exports.ok) {
        setState({ phase: 'failure', issue: failureMessage(exports) });
        return;
      }
      setState({ phase: 'ready', data: { page: projection.data, exports: exports.data } });
    });
    return () => controller.abort();
  }, [a, client, cursor, filters, id, organizationId]);
  useEffect(() => {
    if (state.phase !== 'ready') return;
    const activeJobs = state.data.exports.jobs.filter((job) =>
      activeExportStatuses.has(job.status),
    );
    if (activeJobs.length === 0) {
      exportPoll.current = { attempts: 0, signature: '' };
      return;
    }
    const signature = activeJobs
      .map((job) => `${job.exportId}:${job.status}:${job.lockVersion}`)
      .sort()
      .join('|');
    if (signature !== exportPoll.current.signature) exportPoll.current = { attempts: 0, signature };
    const delay = exportPollDelays[exportPoll.current.attempts];
    if (delay === undefined) return;
    const timer = globalThis.setTimeout(() => {
      exportPoll.current.attempts += 1;
      if (exportPoll.current.attempts >= exportPollDelays.length)
        setPausedExportSignature(signature);
      setAttempt((value) => value + 1);
    }, delay);
    return () => globalThis.clearTimeout(timer);
  }, [state]);
  const apply = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setCursor('');
    setAttempt((value) => value + 1);
  };
  const requestExport = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (busy) return;
    if (!filters.from || !filters.to) {
      setIssue('Choose both export boundary timestamps.');
      return;
    }
    setBusy(true);
    setIssue('');
    const projection: EvidenceExportRequest['projection'] =
      id === 'M1-22'
        ? detailProjection
          ? 'history-detail-v1'
          : 'history-summary-v1'
        : detailProjection
          ? 'audit-detail-v1'
          : 'audit-summary-v1';
    const exportFilters: EvidenceExportRequest['filters'] = {
      from: new Date(filters.from).toISOString(),
      to: new Date(filters.to).toISOString(),
    };
    if (filters.actorId) exportFilters.actorId = filters.actorId;
    if (filters.subjectType) exportFilters.subjectType = filters.subjectType;
    if (filters.subjectId) exportFilters.subjectId = filters.subjectId;
    if (filters.correlationId) exportFilters.correlationId = filters.correlationId;
    if (id === 'M1-22') {
      if (filters.status) exportFilters.status = filters.status;
      if (filters.changeType) exportFilters.changeType = filters.changeType;
    } else {
      if (filters.operation) exportFilters.operation = filters.operation;
      if (filters.eventName) exportFilters.eventName = filters.eventName;
      if (filters.schemaVersion) exportFilters.schemaVersion = Number(filters.schemaVersion);
      if (filters.outcome === 'success' || filters.outcome === 'failure')
        exportFilters.outcome = filters.outcome;
      if (filters.risk === 'standard' || filters.risk === 'high' || filters.risk === 'restricted')
        exportFilters.risk = filters.risk;
    }
    const result = await client.requestEvidenceExport(
      organizationId,
      {
        projection,
        format,
        filters: exportFilters,
        purposeCode: purpose,
        legalBasisKey: legalBasis.trim(),
        reason: reason.trim(),
      },
      `evidence-export:${globalThis.crypto.randomUUID()}`,
    );
    setBusy(false);
    if (!result.ok) {
      setIssue(failureMessage(result));
      return;
    }
    setReason('');
    setAttempt((value) => value + 1);
  };
  const openDetail = async (item: EvidenceItem) => {
    if (!('eventId' in item) || busy) return;
    setBusy(true);
    setIssue('');
    const result = await client.accessAuditEvidenceDetail(
      organizationId,
      item.eventId,
      purpose,
      `audit-detail:${globalThis.crypto.randomUUID()}`,
    );
    setBusy(false);
    if (!result.ok) {
      setIssue(failureMessage(result));
      return;
    }
    setDetail(result.data);
  };
  const decide = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!action || busy) return;
    setBusy(true);
    setIssue('');
    const result = await client.decideEvidenceExport(
      organizationId,
      action.job.exportId,
      action.authorize,
      reason.trim(),
      `"evidence-export:${action.job.exportId}:${action.job.lockVersion}"`,
      `export-decision:${globalThis.crypto.randomUUID()}`,
    );
    setBusy(false);
    if (!result.ok) {
      setIssue(failureMessage(result));
      return;
    }
    setAction(null);
    setReason('');
    setAttempt((value) => value + 1);
  };
  const accessExport = async (job: EvidenceExportJob) => {
    if (busy || !reason.trim()) {
      setIssue('Enter a bounded access reason before requesting the download grant.');
      return;
    }
    setBusy(true);
    setIssue('');
    const result = await client.accessEvidenceExport(
      organizationId,
      job.exportId,
      purpose,
      reason.trim(),
      `"evidence-export:${job.exportId}:${job.lockVersion}"`,
      `export-access:${globalThis.crypto.randomUUID()}`,
    );
    setBusy(false);
    if (!result.ok) {
      setIssue(failureMessage(result));
      return;
    }
    setDownload({
      url: result.data.downloadUrl,
      filename: result.data.filename,
      expiresAt: result.data.expiresAt,
    });
    setReason('');
  };
  const items: EvidenceItem[] = state.phase === 'ready' ? state.data.page.items : [];
  const jobs = state.phase === 'ready' ? state.data.exports.jobs : [];
  const hasActiveExports = jobs.some((job) => activeExportStatuses.has(job.status));
  const activeExportSignature = jobs
    .filter((job) => activeExportStatuses.has(job.status))
    .map((job) => `${job.exportId}:${job.status}:${job.lockVersion}`)
    .sort()
    .join('|');
  const pollingPaused = hasActiveExports && pausedExportSignature === activeExportSignature;
  const refreshExportStatuses = () => {
    exportPoll.current = { attempts: 0, signature: '' };
    setPausedExportSignature('');
    setAttempt((value) => value + 1);
  };
  const compare = selected
    .map((key) =>
      items.find(
        (item) => ('configurationId' in item ? item.configurationId : item.eventId) === key,
      ),
    )
    .filter((item): item is EvidenceItem => item !== undefined);
  return (
    <>
      <PageHeading id={id} />
      {state.phase === 'loading' ? (
        <LoadingPanel />
      ) : state.phase === 'failure' ? (
        <FailurePanel issue={state.issue} onRetry={() => setAttempt((value) => value + 1)} />
      ) : (
        <>
          {issue && <div className="inline-alert error-alert">{issue}</div>}
          <section className="content-panel">
            <h2>{id === 'M1-22' ? 'Configuration history' : 'Audit evidence'}</h2>
            <form onSubmit={apply}>
              <div className="form-grid">
                <label>
                  From
                  <input
                    type="datetime-local"
                    value={filters.from}
                    onChange={(event) => setFilters({ ...filters, from: event.target.value })}
                  />
                </label>
                <label>
                  To
                  <input
                    type="datetime-local"
                    value={filters.to}
                    onChange={(event) => setFilters({ ...filters, to: event.target.value })}
                  />
                </label>
                {id === 'M1-22' ? (
                  <>
                    <label>
                      Status
                      <select
                        value={filters.status}
                        onChange={(event) => setFilters({ ...filters, status: event.target.value })}
                      >
                        <option value="">All</option>
                        {[
                          'draft',
                          'validated',
                          'submitted',
                          'approved',
                          'rejected',
                          'active',
                          'superseded',
                        ].map((value) => (
                          <option key={value}>{value}</option>
                        ))}
                      </select>
                    </label>
                    <label>
                      Change type
                      <select
                        value={filters.changeType}
                        onChange={(event) =>
                          setFilters({ ...filters, changeType: event.target.value })
                        }
                      >
                        <option value="">All</option>
                        {['activated', 'closed', 'retired', 'ended'].map((value) => (
                          <option key={value}>{value}</option>
                        ))}
                      </select>
                    </label>
                  </>
                ) : (
                  <>
                    <label>
                      Operation
                      <input
                        value={filters.operation}
                        onChange={(event) =>
                          setFilters({ ...filters, operation: event.target.value })
                        }
                      />
                    </label>
                    <label>
                      Event
                      <input
                        value={filters.eventName}
                        onChange={(event) =>
                          setFilters({ ...filters, eventName: event.target.value })
                        }
                      />
                    </label>
                    <label>
                      Schema version
                      <input
                        type="number"
                        min="1"
                        value={filters.schemaVersion}
                        onChange={(event) =>
                          setFilters({ ...filters, schemaVersion: event.target.value })
                        }
                      />
                    </label>
                    <label>
                      Outcome
                      <select
                        value={filters.outcome}
                        onChange={(event) =>
                          setFilters({ ...filters, outcome: event.target.value })
                        }
                      >
                        <option value="">All</option>
                        <option value="success">Success</option>
                        <option value="failure">Failure</option>
                      </select>
                    </label>
                    <label>
                      Risk
                      <select
                        value={filters.risk}
                        onChange={(event) => setFilters({ ...filters, risk: event.target.value })}
                      >
                        <option value="">All</option>
                        <option value="standard">Standard</option>
                        <option value="high">High</option>
                        <option value="restricted">Restricted</option>
                      </select>
                    </label>
                  </>
                )}
                <label>
                  Actor ID
                  <input
                    value={filters.actorId}
                    onChange={(event) => setFilters({ ...filters, actorId: event.target.value })}
                  />
                </label>
                <label>
                  Subject type
                  <input
                    value={filters.subjectType}
                    onChange={(event) =>
                      setFilters({ ...filters, subjectType: event.target.value })
                    }
                  />
                </label>
                <label>
                  Subject ID
                  <input
                    value={filters.subjectId}
                    onChange={(event) => setFilters({ ...filters, subjectId: event.target.value })}
                  />
                </label>
                <label>
                  Correlation ID
                  <input
                    value={filters.correlationId}
                    onChange={(event) =>
                      setFilters({ ...filters, correlationId: event.target.value })
                    }
                  />
                </label>
              </div>
              <button className="secondary-button">Apply filters</button>
            </form>
            <div className="record-grid">
              {items.length === 0 ? (
                <p className="empty-state">No evidence matches these filters.</p>
              ) : (
                items.map((item, index) => {
                  const key =
                    ('configurationId' in item ? item.configurationId : item.eventId) ||
                    String(index);
                  return (
                    <article className="record-card" key={key}>
                      <LiveRecord value={item} />
                      <div className="form-actions">
                        {id === 'M1-22' && (
                          <label>
                            <input
                              type="checkbox"
                              checked={selected.includes(key)}
                              disabled={!selected.includes(key) && selected.length >= 2}
                              onChange={(event) =>
                                setSelected((current) =>
                                  event.target.checked
                                    ? [...current, key]
                                    : current.filter((value) => value !== key),
                                )
                              }
                            />{' '}
                            Compare
                          </label>
                        )}
                        {id === 'M1-23' && (
                          <button type="button" onClick={() => void openDetail(item)}>
                            Open purpose-bound detail
                          </button>
                        )}
                      </div>
                    </article>
                  );
                })
              )}
            </div>
            {Boolean(state.data.page.hasMore) && (
              <button
                type="button"
                onClick={() => setCursor(String(state.data.page.nextCursor ?? ''))}
              >
                Next page
              </button>
            )}
          </section>
          {id === 'M1-22' && compare.length === 2 && (
            <section className="content-panel">
              <h2>Semantic comparison</h2>
              <table>
                <thead>
                  <tr>
                    <th>Field</th>
                    <th>Earlier</th>
                    <th>Later</th>
                  </tr>
                </thead>
                <tbody>
                  {Array.from(new Set(compare.flatMap(Object.keys)))
                    .filter((key) => !['organizationId'].includes(key))
                    .map((key) => (
                      <tr key={key}>
                        <th>{humanize(key)}</th>
                        <td>{JSON.stringify(objectField(compare[0]!, key))}</td>
                        <td>{JSON.stringify(objectField(compare[1]!, key))}</td>
                      </tr>
                    ))}
                </tbody>
              </table>
            </section>
          )}
          {detail && (
            <section className="content-panel">
              <h2>Audit detail</h2>
              <LiveRecord value={detail} />
              <button type="button" onClick={() => setDetail(null)}>
                Close detail
              </button>
            </section>
          )}
          <section className="content-panel">
            <div className="panel-heading">
              <div>
                <h2>Purpose-bound exports</h2>
                {hasActiveExports && (
                  <p aria-live="polite">
                    {pollingPaused
                      ? 'Automatic status refresh paused after the bounded retry window.'
                      : 'Export status will refresh automatically with bounded backoff.'}
                  </p>
                )}
              </div>
              <button type="button" onClick={refreshExportStatuses} disabled={busy}>
                Refresh statuses
              </button>
            </div>
            <div className="record-grid">
              {jobs.map((job) => (
                <article className="record-card" key={job.exportId}>
                  <div className="record-card-head">
                    <strong>{job.projection}</strong>
                    <span className="status-badge">{job.status}</span>
                  </div>
                  <p>
                    {job.format} · {job.purposeCode}
                  </p>
                  {job.canApprove && (
                    <div className="form-actions">
                      <button type="button" onClick={() => setAction({ job, authorize: true })}>
                        Authorize
                      </button>
                      <button type="button" onClick={() => setAction({ job, authorize: false })}>
                        Deny
                      </button>
                    </div>
                  )}
                  {job.canAccess && (
                    <button type="button" onClick={() => void accessExport(job)} disabled={busy}>
                      Create 10-minute download
                    </button>
                  )}
                </article>
              ))}
            </div>
            {download && (
              <div className="inline-alert success-alert">
                <a href={download.url} download={download.filename} rel="noreferrer">
                  Download {download.filename}
                </a>{' '}
                before {new Date(download.expiresAt).toLocaleTimeString()}.
              </div>
            )}
            {action ? (
              <form onSubmit={(event) => void decide(event)}>
                <label>
                  Decision reason
                  <textarea
                    required
                    minLength={10}
                    maxLength={500}
                    value={reason}
                    onChange={(event) => setReason(event.target.value)}
                  />
                </label>
                <button className="primary-button" disabled={busy}>
                  Confirm {action.authorize ? 'authorization' : 'denial'}
                </button>
                <button type="button" onClick={() => setAction(null)}>
                  Cancel
                </button>
              </form>
            ) : (
              Boolean(state.data.exports.canRequest) && (
                <form onSubmit={(event) => void requestExport(event)}>
                  <div className="form-grid">
                    <label>
                      Purpose
                      <select
                        value={purpose}
                        onChange={(event) => {
                          const value = event.target.value;
                          if (
                            value === 'configuration_review' ||
                            value === 'regulatory_evidence' ||
                            value === 'security_investigation' ||
                            value === 'data_correction'
                          )
                            setPurpose(value);
                        }}
                      >
                        {[
                          'configuration_review',
                          'regulatory_evidence',
                          'security_investigation',
                          'data_correction',
                        ].map((value) => (
                          <option key={value}>{value}</option>
                        ))}
                      </select>
                    </label>
                    <label>
                      Legal basis
                      <input
                        required
                        pattern="[a-z][a-z0-9._:-]{1,79}"
                        value={legalBasis}
                        onChange={(event) => setLegalBasis(event.target.value)}
                      />
                    </label>
                    <label>
                      Format
                      <select
                        value={format}
                        onChange={(event) => {
                          const value = event.target.value;
                          if (value === 'csv' || value === 'jsonl') setFormat(value);
                        }}
                      >
                        <option value="csv">CSV</option>
                        <option value="jsonl">JSON Lines</option>
                      </select>
                    </label>
                    <label>
                      <input
                        type="checkbox"
                        checked={detailProjection}
                        onChange={(event) => setDetailProjection(event.target.checked)}
                      />{' '}
                      Restricted detail projection
                    </label>
                    <label className="full-width">
                      Reason
                      <textarea
                        required
                        minLength={10}
                        maxLength={500}
                        value={reason}
                        onChange={(event) => setReason(event.target.value)}
                      />
                    </label>
                  </div>
                  <button className="primary-button" disabled={busy}>
                    Request export
                  </button>
                </form>
              )
            )}
          </section>
        </>
      )}
    </>
  );
}

function humanize(key: string) {
  return key.replace(/([A-Z])/g, ' $1').replace(/^./, (value) => value.toUpperCase());
}

function objectField(value: object, key: string): unknown {
  return Object.entries(value).find(([field]) => field === key)?.[1];
}

function LiveRecord({ value }: { value: object }) {
  const entries = Object.entries(value).filter(
    ([, field]) => field === null || ['boolean', 'number', 'string'].includes(typeof field),
  );
  const title = entries.find(([key]) => /name|displayNumber|code|eventName|schemeKey/i.test(key));
  const status = entries.find(([key]) => /status|outcome|risk/i.test(key));
  return (
    <article className="record-card">
      <div className="record-card-head">
        <strong>{String(title?.[1] ?? entries[0]?.[1] ?? 'Governed record')}</strong>
        {status && <span className="status-badge">{String(status[1])}</span>}
      </div>
      <dl className="record-details">
        {entries.slice(0, 10).map(([key, field]) => (
          <div key={key}>
            <dt>{humanize(key)}</dt>
            <dd>
              {field === null
                ? 'Not set'
                : typeof field === 'boolean'
                  ? field
                    ? 'Yes'
                    : 'No'
                  : String(field)}
            </dd>
          </div>
        ))}
      </dl>
    </article>
  );
}

export function AdministrationScreen(props: AdministrationScreenProps) {
  return (
    <Shell currentId={props.id} {...props.shell}>
      {props.id === 'M1-20' ? (
        <MembershipScreen {...props} />
      ) : props.id === 'M1-07' ? (
        <ProfileScreen {...props} />
      ) : props.id === 'M1-08' ? (
        <IdentifierScreen {...props} />
      ) : props.id === 'M1-09' ? (
        <ContactDirectoryScreen {...props} />
      ) : props.id === 'M1-10' ? (
        <InternationalSettingsScreen {...props} />
      ) : props.id === 'M1-11' ? (
        <GovernanceScreen {...props} />
      ) : props.id === 'M1-12' ? (
        <FacilityScreen {...props} />
      ) : props.id === 'M1-13' ? (
        <FacilityScreen {...props} />
      ) : props.id === 'M1-14' ? (
        <UnitHierarchyScreen {...props} />
      ) : props.id === 'M1-15' ? (
        <LocationScreen {...props} />
      ) : props.id === 'M1-16' ? (
        <OperatingHoursScreen {...props} />
      ) : props.id === 'M1-17' ? (
        <ServiceCatalogueScreen {...props} />
      ) : props.id === 'M1-18' ? (
        <ServiceAssignmentScreen {...props} />
      ) : props.id === 'M1-19' ? (
        <IdentifierSchemeScreen {...props} />
      ) : props.id === 'M1-21' ? (
        <ConfigurationActivationScreen {...props} />
      ) : props.id === 'M1-22' || props.id === 'M1-23' ? (
        <EvidenceScreen {...props} id={props.id} />
      ) : (
        <ReadinessScreen
          client={props.client}
          id={props.id}
          organizationId={props.organizationId}
        />
      )}
    </Shell>
  );
}
