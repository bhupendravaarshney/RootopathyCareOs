import { Building2, LoaderCircle, LockKeyhole, ShieldCheck, UserRoundX } from 'lucide-react';
import { useState, type FormEvent, type ReactNode } from 'react';
import type { OrganizationAccess, User } from '../../api/generated';
import { SessionIssueAlert } from './SessionIssueAlert';
import type { SessionIssue } from './session-types';

export function IdentityFrame({
  children,
  homeHref = '#/M1-01',
  homeLabel = 'ROOTOPATHY CareOS sign in',
}: {
  children: ReactNode;
  homeHref?: string;
  homeLabel?: string;
}) {
  return (
    <div className="identity-layout">
      <header className="identity-header">
        <a className="brand" href={homeHref} aria-label={homeLabel}>
          <strong>ROOTOPATHY</strong>
          <span>CareOS secure access</span>
        </a>
      </header>
      <main className="identity-main" id="main-content">
        {children}
      </main>
    </div>
  );
}

export function IdentityMark({ children }: { children: ReactNode }) {
  return <div className="auth-mark">{children}</div>;
}

type LoginScreenProps = {
  busy: boolean;
  issue: SessionIssue | null;
  onLogin(credentials: { email: string; password: string }): Promise<void>;
};

export function LoginScreen({ busy, issue, onLogin }: LoginScreenProps) {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const submittedPassword = password;
    setPassword('');
    void onLogin({ email, password: submittedPassword });
  };

  return (
    <IdentityFrame>
      <section className="auth-panel panel" aria-labelledby="login-heading">
        <span className="eyebrow">M1-01</span>
        <IdentityMark>
          <ShieldCheck aria-hidden="true" />
        </IdentityMark>
        <h1 id="login-heading">Sign in to CareOS</h1>
        <p>Use your assigned account to continue to an authorized organization.</p>
        {issue && <SessionIssueAlert issue={issue} />}
        <form onSubmit={submit}>
          <label htmlFor="login-email">
            Email address
            <input
              id="login-email"
              type="email"
              autoComplete="username"
              maxLength={320}
              required
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              disabled={busy}
            />
          </label>
          <label htmlFor="login-password">
            Password
            <input
              id="login-password"
              type="password"
              autoComplete="current-password"
              maxLength={128}
              required
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              disabled={busy}
            />
          </label>
          <a className="text-action form-link" href="#/forgot-password">
            Forgot your password?
          </a>
          <button className="primary-button full-button" disabled={busy}>
            {busy ? 'Signing in…' : 'Sign in securely'}
          </button>
        </form>
        <p className="security-note">
          CareOS never stores session credentials in browser storage and does not retry sign-in
          attempts automatically.
        </p>
      </section>
    </IdentityFrame>
  );
}

type MfaChallengeScreenProps = {
  busy: boolean;
  issue: SessionIssue | null;
  onComplete(code: string): Promise<void>;
  onLogout(): Promise<void>;
  user: User;
};

export function MfaChallengeScreen({
  busy,
  issue,
  onComplete,
  onLogout,
  user,
}: MfaChallengeScreenProps) {
  const [code, setCode] = useState('');

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const submittedCode = code;
    setCode('');
    void onComplete(submittedCode);
  };

  return (
    <IdentityFrame>
      <section className="auth-panel panel" aria-labelledby="mfa-heading">
        <span className="eyebrow">M1-03</span>
        <IdentityMark>
          <LockKeyhole aria-hidden="true" />
        </IdentityMark>
        <h1 id="mfa-heading">Verify your identity</h1>
        <p>
          Primary credentials were accepted for <strong>{user.email}</strong>. Enter an
          authenticator or one-use recovery code.
        </p>
        {issue && <SessionIssueAlert issue={issue} />}
        <form onSubmit={submit}>
          <label htmlFor="mfa-code">
            Authentication code
            <input
              id="mfa-code"
              type="text"
              autoComplete="one-time-code"
              autoCapitalize="none"
              maxLength={32}
              required
              value={code}
              onChange={(event) => setCode(event.target.value)}
              disabled={busy}
            />
          </label>
          <button className="primary-button full-button" disabled={busy}>
            {busy ? 'Verifying…' : 'Verify securely'}
          </button>
          <button
            type="button"
            className="text-action centered-action"
            onClick={() => void onLogout()}
            disabled={busy}
          >
            Cancel and sign out
          </button>
        </form>
      </section>
    </IdentityFrame>
  );
}

type OrganizationSelectionScreenProps = {
  busy: boolean;
  issue: SessionIssue | null;
  onLogout(): Promise<void>;
  onSelect(organizationId: string): Promise<void>;
  organizations: OrganizationAccess[];
  user: User;
};

export function OrganizationSelectionScreen({
  busy,
  issue,
  onLogout,
  onSelect,
  organizations,
  user,
}: OrganizationSelectionScreenProps) {
  const [organizationId, setOrganizationId] = useState(organizations[0]?.id ?? '');

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (organizationId) {
      void onSelect(organizationId);
    }
  };

  return (
    <IdentityFrame>
      <section
        className="auth-panel organization-panel panel"
        aria-labelledby="organization-heading"
      >
        <span className="eyebrow">M1-04</span>
        <IdentityMark>
          <Building2 aria-hidden="true" />
        </IdentityMark>
        <h1 id="organization-heading">Choose an organization</h1>
        <p>
          Signed in as <strong>{user.displayName}</strong>. Only current server-authorized
          memberships are shown.
        </p>
        {issue && <SessionIssueAlert issue={issue} />}
        <form onSubmit={submit}>
          <fieldset className="organization-options" disabled={busy}>
            <legend>Select the organization context for this session</legend>
            {organizations.map((organization) => (
              <label key={organization.id} className="organization-option">
                <input
                  type="radio"
                  name="organization"
                  value={organization.id}
                  checked={organizationId === organization.id}
                  onChange={() => setOrganizationId(organization.id)}
                />
                <span>
                  <strong>{organization.displayName}</strong>
                  <small>
                    {organization.status === 'active' ? 'Active' : 'Draft'} organization
                  </small>
                </span>
              </label>
            ))}
          </fieldset>
          <button className="primary-button full-button" disabled={busy || !organizationId}>
            {busy ? 'Selecting…' : 'Continue to workspace'}
          </button>
          <button
            type="button"
            className="text-action centered-action"
            onClick={() => void onLogout()}
            disabled={busy}
          >
            Sign out
          </button>
        </form>
      </section>
    </IdentityFrame>
  );
}

export function SessionLoadingScreen({ reason }: { reason: 'organizations' | 'session' }) {
  return (
    <IdentityFrame>
      <section
        className="auth-panel panel loading-panel"
        aria-labelledby="loading-heading"
        role="status"
      >
        <IdentityMark>
          <LoaderCircle className="spinner" aria-hidden="true" />
        </IdentityMark>
        <h1 id="loading-heading">
          {reason === 'session' ? 'Checking your session' : 'Loading organization access'}
        </h1>
        <p>Please wait while CareOS verifies server-side access.</p>
      </section>
    </IdentityFrame>
  );
}

export function SessionFailureScreen({
  issue,
  onRetry,
}: {
  issue: SessionIssue;
  onRetry(): Promise<void>;
}) {
  return (
    <IdentityFrame>
      <section className="auth-panel panel" aria-labelledby="session-failure-heading">
        <IdentityMark>
          <UserRoundX aria-hidden="true" />
        </IdentityMark>
        <h1 id="session-failure-heading">CareOS access is unavailable</h1>
        <p>The workspace remains locked because the session could not be verified.</p>
        <SessionIssueAlert issue={issue} />
        <button className="primary-button full-button" onClick={() => void onRetry()}>
          Check session again
        </button>
      </section>
    </IdentityFrame>
  );
}

export function NoOrganizationScreen({
  busy,
  issue,
  onLogout,
  user,
}: {
  busy: boolean;
  issue: SessionIssue | null;
  onLogout(): Promise<void>;
  user: User;
}) {
  return (
    <IdentityFrame>
      <section className="auth-panel panel" aria-labelledby="no-organization-heading">
        <IdentityMark>
          <UserRoundX aria-hidden="true" />
        </IdentityMark>
        <h1 id="no-organization-heading">No organization access</h1>
        <p>
          <strong>{user.email}</strong> is authenticated but has no current organization membership.
          Ask an authorized administrator to review access.
        </p>
        {issue && <SessionIssueAlert issue={issue} />}
        <button
          className="secondary-button full-button"
          onClick={() => void onLogout()}
          disabled={busy}
        >
          {busy ? 'Signing out…' : 'Sign out'}
        </button>
      </section>
    </IdentityFrame>
  );
}

export function InvitationUnavailableScreen({ authenticated }: { authenticated: boolean }) {
  return (
    <IdentityFrame>
      <section className="auth-panel panel" aria-labelledby="invitation-heading">
        <span className="eyebrow">M1-02</span>
        <IdentityMark>
          <ShieldCheck aria-hidden="true" />
        </IdentityMark>
        <h1 id="invitation-heading">Invitation flow is not enabled</h1>
        <p>
          Governed invitation issuance, account linkage, expiry, and acceptance policy must be
          approved before this route can accept a token.
        </p>
        <a
          className="primary-button full-button button-link"
          href={authenticated ? '#/M1-05' : '#/M1-01'}
        >
          {authenticated ? 'Return to workspace' : 'Continue to sign in'}
        </a>
      </section>
    </IdentityFrame>
  );
}
