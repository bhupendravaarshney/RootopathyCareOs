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
  return { correlationId, data, ok: true, status };
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
    completeMfaChallenge: async () => success(authenticatedSession),
    completePasswordReset: async () => success(undefined, 204),
    getAuthenticationSession: async () => success(authenticatedSession),
    listSelectableOrganizations: async () => success([selectedOrganization]),
    login: async () => success(authenticatedSession),
    logout: async () => success(undefined, 204),
    regenerateRecoveryCodes: async () =>
      success({ recoveryCodes: ['2345-6789-ABCD', 'EFGH-JKLM-NPQR'] }),
    requestPasswordReset: async () => success(undefined, 202),
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

  it('keeps invitation acceptance fail closed until its governed API exists', async () => {
    window.location.hash = '#/M1-02';
    render(<App client={sessionClient()} />);

    expect(
      await screen.findByRole('heading', { name: 'Invitation flow is not enabled' }),
    ).toBeInTheDocument();
    expect(
      screen.getByText(/issuance, account linkage, expiry, and acceptance policy/),
    ).toBeVisible();
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
