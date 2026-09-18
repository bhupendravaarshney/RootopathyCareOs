import { AlertTriangle, Check, CircleAlert, Minus, RefreshCw, ShieldCheck } from 'lucide-react';
import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import type { ApiFailure } from '../../api/client';
import type {
  AdministrationReadiness,
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
  id: 'M1-05' | 'M1-06' | 'M1-07' | 'M1-08' | 'M1-09' | 'M1-10' | 'M1-11' | 'M1-12' | 'M1-20';
  organizationId: string;
  shell: ShellSessionProps;
};

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
          This is a live server projection. Persisted validation, evidence review, and activation
          remain unavailable until the M1-21 workflow is implemented.
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

function FacilityScreen({ client, organizationId }: AdministrationScreenProps) {
  const [attempt, setAttempt] = useState(0);
  const [query, setQuery] = useState('');
  const [state, setState] = useState<LoadState<FacilityDirectory>>({ phase: 'loading' });
  const [busy, setBusy] = useState(false);
  const [issue, setIssue] = useState('');
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
    const result = await client.createFacilityDraft(
      organizationId,
      {
        ...form,
        facilityCode: form.facilityCode.trim().toUpperCase(),
        legalName: form.legalName.trim(),
        displayName: form.displayName.trim(),
        timezone: form.timezone?.trim() || null,
        reason: form.reason.trim(),
      },
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
    setForm({ ...form, facilityCode: '', legalName: '', displayName: '', reason: '' });
  };
  return (
    <>
      <PageHeading id="M1-12" />
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
                  </article>
                ))
              )}
            </div>
          </section>
          {state.data.canCreate && (
            <section className="content-panel">
              <h2>Add facility draft</h2>
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
                    {busy ? 'Creating...' : 'Add facility'}
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
