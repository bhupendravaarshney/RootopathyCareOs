import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ApiFailure, ApiResult } from './api/client';
import type { OrganizationAccess, SessionState } from './api/generated';
import App from './App';
import { screens } from './data/screens';
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
  roleKeys: ['organization-member'],
  selected: true,
  status: 'active',
};
const otherOrganization: OrganizationAccess = {
  displayName: 'South Clinic',
  id: '33333333-3333-4333-8333-333333333333',
  roleKeys: ['organization-member'],
  selected: false,
  status: 'draft',
};
const anonymousSession: SessionState = {
  mfaEnabled: false,
  recentAuthentication: false,
  state: 'anonymous',
  user: null,
};
const authenticatedSession: SessionState = {
  mfaEnabled: false,
  recentAuthentication: true,
  state: 'authenticated',
  user,
};
const mfaSession: SessionState = {
  mfaEnabled: true,
  recentAuthentication: false,
  state: 'mfa_required',
  user,
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

function sessionClient(overrides: Partial<SessionClient> = {}): SessionClient {
  return {
    acceptInvitation: async () =>
      success(
        {
          accountLink: 'created',
          invitationId: '77777777-7777-4777-8777-777777777777',
          organizationId: selectedOrganization.id,
          roleKey: 'organization_member',
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
    completeMfaChallenge: async () => success(authenticatedSession),
    completePasswordReset: async () => success(undefined, 204),
    executeMfaAdministrativeReset: async (_organizationId, targetUserId, approvalId) =>
      success({
        approvalId,
        expiresAt: '2026-09-16T12:00:00Z',
        status: 'reset',
        targetUserId,
      }),
    getAuthenticationSession: async () => success(authenticatedSession),
    issueInvitation: async () =>
      success(
        {
          expiresAt: '2026-09-16T12:00:00Z',
          invitationId: '77777777-7777-4777-8777-777777777777',
          roleKey: 'organization_member',
          status: 'pending',
        },
        201,
      ),
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
    revokeInvitation: async () =>
      success({
        expiresAt: '2026-09-16T12:00:00Z',
        invitationId: '77777777-7777-4777-8777-777777777777',
        roleKey: 'organization_member',
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
    verifyMfaEnrollment: async () =>
      success({ recoveryCodes: ['2345-6789-ABCD', 'EFGH-JKLM-NPQR'] }),
    verifyRecentAuthentication: async () => success(undefined, 204),
    ...overrides,
  } as SessionClient;
}

describe('CareOS frontend session boundary', () => {
  beforeEach(() => {
    window.location.hash = '#/M1-05';
  });

  it('registers all M1, M2 and COS screens', () => {
    expect(screens).toHaveLength(79);
    expect(new Set(screens.map((item) => item.id)).size).toBe(79);
  });

  it('renders the protected workspace from validated server identity and organization state', async () => {
    render(<App client={sessionClient()} />);

    expect(
      await screen.findByRole('heading', { name: 'Administration dashboard' }),
    ).toBeInTheDocument();
    expect(screen.getAllByText('Asha Verma')).toHaveLength(2);
    expect(screen.getByLabelText('Current organization')).toHaveValue(selectedOrganization.id);
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
    fireEvent.click(screen.getByRole('button', { name: 'Sign in securely' }));

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
    fireEvent.click(screen.getByRole('button', { name: 'Continue to workspace' }));

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
          roleKey: 'organization_member',
          status: 'pending',
        },
        201,
      ),
    );
    const revokeInvitation = vi.fn<SessionClient['revokeInvitation']>(async () =>
      success({
        expiresAt: '2026-09-16T12:00:00Z',
        invitationId: '77777777-7777-4777-8777-777777777777',
        roleKey: 'organization_member',
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
    fireEvent.click(screen.getByRole('button', { name: 'Issue invitation' }));

    await waitFor(() => expect(issueInvitation).toHaveBeenCalledOnce());
    expect(issueInvitation.mock.calls[0]?.[0]).toBe(selectedOrganization.id);
    expect(issueInvitation.mock.calls[0]?.[1]).toEqual({
      displayName: 'New User',
      email: 'new.user@example.test',
      reason: 'Approved onboarding request CARE-42',
      roleKey: 'organization_member',
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
          roleKey: 'organization_member',
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

    expect(await screen.findByRole('alert')).toHaveTextContent('missing its one-time token');
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
    fireEvent.click(screen.getByRole('button', { name: 'Set up an authenticator' }));

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
