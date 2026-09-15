import { KeyRound, ShieldCheck, Smartphone } from 'lucide-react';
import { useState, type FormEvent } from 'react';
import type { MfaEnrollment, User } from '../../api/generated';
import { SessionIssueAlert } from './SessionIssueAlert';
import { IdentityFrame, IdentityMark } from './SessionScreens';
import type { SessionAction, SessionIssue } from './session-types';

const MAX_PASSWORD_BYTES = 72;
const MAX_RESET_TOKEN_LENGTH = 512;

type PasswordResetRequestScreenProps = {
  busy: boolean;
  issue: SessionIssue | null;
  onRequest(email: string): Promise<boolean>;
};

export function PasswordResetRequestScreen({
  busy,
  issue,
  onRequest,
}: PasswordResetRequestScreenProps) {
  const [email, setEmail] = useState('');
  const [accepted, setAccepted] = useState(false);

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const submittedEmail = email;
    setEmail('');
    if (await onRequest(submittedEmail)) {
      setAccepted(true);
    }
  };

  return (
    <IdentityFrame>
      <section className="auth-panel panel" aria-labelledby="password-request-heading">
        <IdentityMark>
          <KeyRound aria-hidden="true" />
        </IdentityMark>
        <h1 id="password-request-heading">Reset your password</h1>
        {accepted ? (
          <>
            <p className="success-callout" role="status">
              If an active account matches that address, CareOS has sent a one-time reset link.
            </p>
            <p>For privacy, this confirmation is the same whether or not an account exists.</p>
            <a className="primary-button full-button button-link" href="#/M1-01">
              Return to sign in
            </a>
          </>
        ) : (
          <>
            <p>
              Enter your account email. The one-time link expires after the server-configured
              recovery window.
            </p>
            {issue && <SessionIssueAlert issue={issue} />}
            <form onSubmit={(event) => void submit(event)}>
              <label htmlFor="password-reset-email">
                Email address
                <input
                  id="password-reset-email"
                  type="email"
                  autoComplete="email"
                  maxLength={320}
                  required
                  value={email}
                  onChange={(event) => setEmail(event.target.value)}
                  disabled={busy}
                />
              </label>
              <button className="primary-button full-button" disabled={busy}>
                {busy ? 'Requesting link...' : 'Request reset link'}
              </button>
              <a className="text-action centered-action button-link" href="#/M1-01">
                Back to sign in
              </a>
            </form>
          </>
        )}
      </section>
    </IdentityFrame>
  );
}

type PasswordResetCompletionScreenProps = {
  busy: boolean;
  issue: SessionIssue | null;
  onComplete(token: string, newPassword: string): Promise<boolean>;
  onForgetToken(): void;
  token?: string;
};

export function PasswordResetCompletionScreen({
  busy,
  issue,
  onComplete,
  onForgetToken,
  token,
}: PasswordResetCompletionScreenProps) {
  const [newPassword, setNewPassword] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [validationIssue, setValidationIssue] = useState('');
  const [complete, setComplete] = useState(false);
  const usableToken =
    typeof token === 'string' && token.length > 0 && token.length <= MAX_RESET_TOKEN_LENGTH;

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setValidationIssue('');
    if (!usableToken) {
      return;
    }
    if (newPassword !== confirmation) {
      setValidationIssue('The password confirmation does not match.');
      return;
    }
    const byteLength = new TextEncoder().encode(newPassword).byteLength;
    if (newPassword.length < 12 || byteLength > MAX_PASSWORD_BYTES) {
      setValidationIssue('Use at least 12 characters and no more than 72 UTF-8 bytes.');
      return;
    }

    const submittedPassword = newPassword;
    setNewPassword('');
    setConfirmation('');
    if (await onComplete(token, submittedPassword)) {
      onForgetToken();
      setComplete(true);
    }
  };

  return (
    <IdentityFrame>
      <section className="auth-panel panel" aria-labelledby="password-completion-heading">
        <IdentityMark>
          <KeyRound aria-hidden="true" />
        </IdentityMark>
        <h1 id="password-completion-heading">
          {complete ? 'Password reset complete' : 'Choose a new password'}
        </h1>
        {complete ? (
          <>
            <p className="success-callout" role="status">
              Your password was changed and all existing sessions were revoked.
            </p>
            <a className="primary-button full-button button-link" href="#/M1-01">
              Sign in with the new password
            </a>
          </>
        ) : !usableToken ? (
          <>
            <p className="warning-callout" role="alert">
              This reset link is missing its one-time token or is malformed.
            </p>
            <a className="primary-button full-button button-link" href="#/forgot-password">
              Request a new reset link
            </a>
          </>
        ) : (
          <>
            <p>
              The new password must differ from the current password and contain 12 to 72 UTF-8
              bytes.
            </p>
            {issue && <SessionIssueAlert issue={issue} />}
            {validationIssue && (
              <p className="warning-callout" role="alert">
                {validationIssue}
              </p>
            )}
            <form onSubmit={(event) => void submit(event)}>
              <label htmlFor="new-password">
                New password
                <input
                  id="new-password"
                  type="password"
                  autoComplete="new-password"
                  minLength={12}
                  maxLength={128}
                  required
                  value={newPassword}
                  onChange={(event) => setNewPassword(event.target.value)}
                  disabled={busy}
                />
              </label>
              <label htmlFor="confirm-password">
                Confirm new password
                <input
                  id="confirm-password"
                  type="password"
                  autoComplete="new-password"
                  minLength={12}
                  maxLength={128}
                  required
                  value={confirmation}
                  onChange={(event) => setConfirmation(event.target.value)}
                  disabled={busy}
                />
              </label>
              <button className="primary-button full-button" disabled={busy}>
                {busy ? 'Changing password...' : 'Change password'}
              </button>
            </form>
          </>
        )}
      </section>
    </IdentityFrame>
  );
}

type MfaAdministrationScreenProps = {
  issue: SessionIssue | null;
  mfaEnabled: boolean;
  onLogout(): Promise<void>;
  onRegenerateRecoveryCodes(): Promise<string[] | null>;
  onStartEnrollment(label?: string): Promise<MfaEnrollment | null>;
  onVerifyEnrollment(code: string): Promise<string[] | null>;
  onVerifyRecentAuthentication(credentials: {
    password: string;
    secondFactor?: string;
  }): Promise<boolean>;
  pendingAction: SessionAction | null;
  recentAuthentication: boolean;
  user: User;
};

type MfaView =
  | { kind: 'overview' }
  | { enrollment: MfaEnrollment; kind: 'enrollment' }
  | { codes: string[]; kind: 'recovery_codes'; source: 'enrollment' | 'regeneration' };

export function MfaAdministrationScreen({
  issue,
  mfaEnabled,
  onLogout,
  onRegenerateRecoveryCodes,
  onStartEnrollment,
  onVerifyEnrollment,
  onVerifyRecentAuthentication,
  pendingAction,
  recentAuthentication,
  user,
}: MfaAdministrationScreenProps) {
  const busy = pendingAction !== null;

  return (
    <IdentityFrame homeHref="#/M1-05" homeLabel="ROOTOPATHY CareOS workspace home">
      <section
        className="auth-panel security-workflow-panel panel"
        aria-labelledby="mfa-administration-heading"
      >
        <span className="eyebrow">M1-03</span>
        <IdentityMark>
          <ShieldCheck aria-hidden="true" />
        </IdentityMark>
        <h1 id="mfa-administration-heading">Multi-factor authentication</h1>
        <p>
          Manage the authenticator and one-use recovery codes for <strong>{user.email}</strong>.
          Sensitive setup material exists in memory only while this screen is open.
        </p>
        {issue && <SessionIssueAlert issue={issue} />}

        {!recentAuthentication ? (
          <RecentAuthenticationForm
            busy={pendingAction === 'verify-recent-authentication'}
            mfaEnabled={mfaEnabled}
            onVerify={onVerifyRecentAuthentication}
          />
        ) : (
          <RecentlyAuthenticatedMfaControls
            mfaEnabled={mfaEnabled}
            onRegenerateRecoveryCodes={onRegenerateRecoveryCodes}
            onStartEnrollment={onStartEnrollment}
            onVerifyEnrollment={onVerifyEnrollment}
            pendingAction={pendingAction}
            user={user}
          />
        )}

        <div className="security-footer-actions">
          <a className="text-action button-link" href="#/M1-05">
            Return to workspace
          </a>
          <button
            type="button"
            className="text-action"
            disabled={busy}
            onClick={() => void onLogout()}
          >
            Sign out
          </button>
        </div>
      </section>
    </IdentityFrame>
  );
}

function RecentlyAuthenticatedMfaControls({
  mfaEnabled,
  onRegenerateRecoveryCodes,
  onStartEnrollment,
  onVerifyEnrollment,
  pendingAction,
  user,
}: {
  mfaEnabled: boolean;
  onRegenerateRecoveryCodes(): Promise<string[] | null>;
  onStartEnrollment(label?: string): Promise<MfaEnrollment | null>;
  onVerifyEnrollment(code: string): Promise<string[] | null>;
  pendingAction: SessionAction | null;
  user: User;
}) {
  const [view, setView] = useState<MfaView>({ kind: 'overview' });
  const [replaceConfirmed, setReplaceConfirmed] = useState(false);
  const busy = pendingAction !== null;

  const beginEnrollment = async () => {
    const enrollment = await onStartEnrollment(`${user.displayName} authenticator`);
    if (enrollment) {
      setView({ enrollment, kind: 'enrollment' });
    }
  };

  const replaceRecoveryCodes = async () => {
    const codes = await onRegenerateRecoveryCodes();
    if (codes) {
      setReplaceConfirmed(false);
      setView({ codes, kind: 'recovery_codes', source: 'regeneration' });
    }
  };

  if (view.kind === 'enrollment') {
    return (
      <EnrollmentPanel
        busy={pendingAction === 'verify-mfa-enrollment'}
        enrollment={view.enrollment}
        onCancel={() => setView({ kind: 'overview' })}
        onVerify={async (code) => {
          const codes = await onVerifyEnrollment(code);
          if (codes) {
            setView({ codes, kind: 'recovery_codes', source: 'enrollment' });
          }
        }}
      />
    );
  }
  if (view.kind === 'recovery_codes') {
    return (
      <RecoveryCodesPanel
        codes={view.codes}
        onStored={() => setView({ kind: 'overview' })}
        source={view.source}
      />
    );
  }

  return (
    <div className="security-overview">
      <div className="security-status" role="status">
        <Smartphone aria-hidden="true" />
        <span>
          <strong>{mfaEnabled ? 'Authenticator enabled' : 'Authenticator not enabled'}</strong>
          <small>Your identity was recently verified for this protected action.</small>
        </span>
      </div>
      {mfaEnabled ? (
        <div className="security-action-card">
          <h2>Replace recovery codes</h2>
          <p>Generating a new set immediately revokes every unused code from the previous set.</p>
          <label className="confirmation-check">
            <input
              type="checkbox"
              checked={replaceConfirmed}
              onChange={(event) => setReplaceConfirmed(event.target.checked)}
              disabled={busy}
            />
            <span>I understand that my previous recovery codes will stop working.</span>
          </label>
          <button
            className="primary-button full-button"
            disabled={busy || !replaceConfirmed}
            onClick={() => void replaceRecoveryCodes()}
          >
            {pendingAction === 'regenerate-recovery-codes'
              ? 'Replacing codes...'
              : 'Replace recovery codes'}
          </button>
        </div>
      ) : (
        <div className="security-action-card">
          <h2>Set up an authenticator</h2>
          <p>
            CareOS will show a one-time setup key. Enrollment is not active until a valid six-digit
            authenticator code is confirmed.
          </p>
          <button
            className="primary-button full-button"
            disabled={busy}
            onClick={() => void beginEnrollment()}
          >
            {pendingAction === 'start-mfa-enrollment'
              ? 'Creating setup...'
              : 'Set up an authenticator'}
          </button>
        </div>
      )}
      <p className="security-note">
        MFA disable and administrator reset are intentionally unavailable until their governed
        support policy is approved.
      </p>
    </div>
  );
}

function RecentAuthenticationForm({
  busy,
  mfaEnabled,
  onVerify,
}: {
  busy: boolean;
  mfaEnabled: boolean;
  onVerify(credentials: { password: string; secondFactor?: string }): Promise<boolean>;
}) {
  const [password, setPassword] = useState('');
  const [secondFactor, setSecondFactor] = useState('');

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const submittedPassword = password;
    const submittedSecondFactor = secondFactor.trim();
    setPassword('');
    setSecondFactor('');
    void onVerify({
      password: submittedPassword,
      ...(submittedSecondFactor ? { secondFactor: submittedSecondFactor } : {}),
    });
  };

  return (
    <form className="security-action-card" onSubmit={submit}>
      <h2>Verify this protected action</h2>
      <p>Confirm your current credentials before CareOS reveals or replaces MFA material.</p>
      <label htmlFor="recent-authentication-password">
        Current password
        <input
          id="recent-authentication-password"
          type="password"
          autoComplete="current-password"
          maxLength={128}
          required
          value={password}
          onChange={(event) => setPassword(event.target.value)}
          disabled={busy}
        />
      </label>
      <label htmlFor="recent-authentication-factor">
        Authenticator or recovery code {mfaEnabled ? '' : '(not required yet)'}
        <input
          id="recent-authentication-factor"
          type="text"
          autoComplete="one-time-code"
          autoCapitalize="none"
          maxLength={32}
          required={mfaEnabled}
          value={secondFactor}
          onChange={(event) => setSecondFactor(event.target.value)}
          disabled={busy}
        />
      </label>
      <button className="primary-button full-button" disabled={busy}>
        {busy ? 'Verifying...' : 'Verify identity'}
      </button>
    </form>
  );
}

function EnrollmentPanel({
  busy,
  enrollment,
  onCancel,
  onVerify,
}: {
  busy: boolean;
  enrollment: MfaEnrollment;
  onCancel(): void;
  onVerify(code: string): Promise<void>;
}) {
  const [code, setCode] = useState('');

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const submittedCode = code;
    setCode('');
    void onVerify(submittedCode);
  };

  return (
    <div className="security-action-card sensitive-output">
      <h2>Connect your authenticator</h2>
      <p>
        Add this setup key to an authenticator app under ROOTOPATHY CareOS, then enter the current
        six-digit code. Starting again replaces this pending setup.
      </p>
      <code className="setup-secret" aria-label="One-time authenticator setup key">
        {enrollment.secret}
      </code>
      <a className="secondary-button button-link" href={enrollment.provisioningUri}>
        Open in an authenticator app
      </a>
      <form onSubmit={submit}>
        <label htmlFor="mfa-enrollment-code">
          Six-digit authenticator code
          <input
            id="mfa-enrollment-code"
            type="text"
            inputMode="numeric"
            autoComplete="one-time-code"
            pattern="[0-9]{6}"
            maxLength={6}
            required
            value={code}
            onChange={(event) => setCode(event.target.value)}
            disabled={busy}
          />
        </label>
        <button className="primary-button full-button" disabled={busy}>
          {busy ? 'Confirming...' : 'Confirm and enable MFA'}
        </button>
        <button
          type="button"
          className="text-action centered-action"
          onClick={onCancel}
          disabled={busy}
        >
          Cancel this browser setup
        </button>
      </form>
    </div>
  );
}

function RecoveryCodesPanel({
  codes,
  onStored,
  source,
}: {
  codes: string[];
  onStored(): void;
  source: 'enrollment' | 'regeneration';
}) {
  return (
    <div
      className="security-action-card sensitive-output"
      role="region"
      aria-labelledby="recovery-codes-heading"
    >
      <h2 id="recovery-codes-heading">Store these recovery codes now</h2>
      <p>
        {source === 'enrollment'
          ? 'MFA is enabled. '
          : 'Your previous recovery codes are revoked. '}
        Each new code works once, and CareOS will not show this plaintext set again.
      </p>
      <ol className="recovery-code-grid" aria-label="One-use recovery codes">
        {codes.map((code) => (
          <li key={code}>
            <code>{code}</code>
          </li>
        ))}
      </ol>
      <button className="primary-button full-button" onClick={onStored}>
        I have stored these codes securely
      </button>
    </div>
  );
}
