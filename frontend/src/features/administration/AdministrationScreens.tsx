import { AlertTriangle, Check, CircleAlert, Minus, RefreshCw, ShieldCheck } from 'lucide-react';
import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import type { ApiFailure } from '../../api/client';
import type {
  AdministrationReadiness,
  MembershipChangeMutation,
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
  id: 'M1-05' | 'M1-06' | 'M1-07' | 'M1-20';
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

export function AdministrationScreen(props: AdministrationScreenProps) {
  return (
    <Shell currentId={props.id} {...props.shell}>
      {props.id === 'M1-20' ? (
        <MembershipScreen {...props} />
      ) : props.id === 'M1-07' ? (
        <ProfileScreen {...props} />
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
