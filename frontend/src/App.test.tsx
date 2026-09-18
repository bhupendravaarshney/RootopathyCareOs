import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import type { ApiFailure, ApiResult } from './api/client';
import type {
  AdministrationReadiness,
  OrganizationAccess,
  OrganizationMembershipPage,
  OrganizationProfile,
  ReadinessGate,
  SessionState,
} from './api/generated';
import App from './App';
import { findScreen, screens } from './data/screens';
import type { AdministrationClient } from './features/administration/administration-types';
import type { SessionClient } from './features/session/session-types';

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
type ApplicationClient = SessionClient & AdministrationClient;

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
    getAdministrationReadiness: async () => success(readiness),
    getAuthenticationSession: async () => success(authenticatedSession),
    getOrganizationProfile: async () => ({
      ...success(organizationProfile),
      etag: '"organization-profile:4"',
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
    listOrganizationMemberships: async () => success(membershipPage),
    listSelectableOrganizations: async () => success([selectedOrganization]),
    login: async () => success(authenticatedSession),
    logout: async () => success(undefined, 204),
    regenerateRecoveryCodes: async () =>
      success({ recoveryCodes: ['2345-6789-ABCD', 'EFGH-JKLM-NPQR'] }),
    requestPasswordReset: async () => success(undefined, 202),
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
    verifyMfaEnrollment: async () =>
      success({ recoveryCodes: ['2345-6789-ABCD', 'EFGH-JKLM-NPQR'] }),
    verifyRecentAuthentication: async () => success(undefined, 204),
    ...overrides,
  } as ApplicationClient;
}

describe('CareOS frontend session boundary', () => {
  beforeEach(() => {
    window.location.hash = '#/M1-05';
  });

  it('registers all M1, M2 and COS screens', () => {
    expect(screens).toHaveLength(79);
    expect(new Set(screens.map((item) => item.id)).size).toBe(79);
    expect(findScreen('M1-01').purpose).toBe(
      'Authenticate securely and continue to the requested authorized workspace.',
    );
    expect(findScreen('M1-02').title).toBe('Invitations');
    expect(findScreen('M1-03').title).toBe('Multi-factor authentication');
    expect(findScreen('M1-04').purpose).toBe(
      'Choose one currently authorized organization workspace.',
    );
    expect(() => findScreen('M1-99')).toThrow('does not contain M1-99');
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

  it('labels synthetic list data and limits interaction to honest local filtering', async () => {
    window.location.hash = '#/M1-12';
    render(<App client={sessionClient()} />);

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Facilities' }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('complementary', { name: 'Synthetic prototype only' }),
    ).toHaveTextContent('Do not enter real personal or clinical information');
    expect(screen.getAllByRole('button', { name: /Open.*unavailable/ })).toHaveLength(4);
    screen.getAllByRole('button', { name: /Open.*unavailable/ }).forEach((button) => {
      expect(button).toBeDisabled();
    });
    expect(screen.queryByRole('link', { name: 'Synthetic facility A' })).not.toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Facilities records' })).toHaveAttribute(
      'tabindex',
      '0',
    );

    const clearFilters = screen.getByRole('button', { name: 'Clear filters' });
    expect(clearFilters).toBeDisabled();
    fireEvent.change(screen.getByLabelText('Search'), { target: { value: 'governance' } });
    expect(clearFilters).toBeEnabled();
    expect(screen.getByText('Showing 1 of 4 synthetic records')).toBeInTheDocument();
    expect(screen.getByText('Synthetic governance group')).toBeInTheDocument();
    expect(screen.queryByText('Synthetic facility A')).not.toBeInTheDocument();

    fireEvent.click(clearFilters);
    expect(screen.getByLabelText('Search')).toHaveValue('');
    expect(screen.getByLabelText('Status')).toHaveValue('all');
    expect(screen.getByLabelText('Scope')).toHaveValue('all');
    expect(screen.getByText('Showing 4 of 4 synthetic records')).toBeInTheDocument();
  });

  it('does not simulate persistence for an unapproved generic form', async () => {
    window.location.hash = '#/M1-08';
    render(<App client={sessionClient()} />);

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Registration and identifiers' }),
    ).toBeInTheDocument();
    expect(screen.getByLabelText('Record name')).toHaveAttribute('readonly');
    expect(screen.getByLabelText('Type')).toBeDisabled();
    expect(screen.getByLabelText('Reason for change')).toHaveAttribute('readonly');
    expect(screen.getByRole('button', { name: /Save draft.*unavailable/ })).toBeDisabled();
    expect(screen.getByRole('button', { name: /Save and continue.*unavailable/ })).toBeDisabled();
    expect(screen.queryByText(/Prototype interaction saved locally/)).not.toBeInTheDocument();
  });

  it('keeps synthetic clinical actions and terminal pagination non-activatable', async () => {
    window.location.hash = '#/COS-27';
    render(<App client={sessionClient()} />);

    expect(
      await screen.findByRole('heading', { level: 1, name: findScreen('COS-27').title }),
    ).toBeInTheDocument();
    expect(screen.getByText('Synthetic patient')).toBeInTheDocument();
    expect(screen.getByLabelText('Clinical note')).toHaveAttribute('readonly');
    expect(screen.getByRole('button', { name: /Save draft.*unavailable/ })).toBeDisabled();
    expect(
      screen.getByRole('button', { name: /Confirm and continue.*unavailable/ }),
    ).toBeDisabled();

    const pagination = screen.getByRole('navigation', { name: 'Prototype pagination' });
    expect(within(pagination).queryByRole('link', { name: /COS-27/ })).not.toBeInTheDocument();
    expect(within(pagination).getByText('COS-27')).toHaveAttribute('aria-disabled', 'true');
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
