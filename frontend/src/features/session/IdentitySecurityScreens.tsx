import { KeyRound, ShieldCheck, Smartphone, UserPlus } from 'lucide-react';
import { useRef, useState, type FormEvent } from 'react';
import type {
  InvitationAcceptance,
  InvitationIssueRequest,
  InvitationMutation,
  InvitationRevocationRequest,
  MfaEnrollment,
  MfaResetMutation,
  OrganizationAccess,
  User,
} from '../../api/generated';
import { IdentityFormIssueAlert } from './IdentityFormIssueAlert';
import { SessionIssueAlert } from './SessionIssueAlert';
import { IdentityFrame, IdentityMark } from './SessionScreens';
import type { SessionAction, SessionIssue } from './session-types';

const MAX_PASSWORD_BYTES = 72;
const MAX_RESET_TOKEN_LENGTH = 512;
const MIN_INVITATION_TOKEN_LENGTH = 32;

type FieldValidationIssue = {
  fieldId: string;
  message: string;
};

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
  const [validationIssue, setValidationIssue] = useState<FieldValidationIssue | null>(null);
  const [complete, setComplete] = useState(false);
  const usableToken =
    typeof token === 'string' && token.length > 0 && token.length <= MAX_RESET_TOKEN_LENGTH;

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setValidationIssue(null);
    if (!usableToken) {
      return;
    }
    if (newPassword !== confirmation) {
      setValidationIssue({
        fieldId: 'confirm-password',
        message: 'The password confirmation does not match.',
      });
      return;
    }
    const byteLength = new TextEncoder().encode(newPassword).byteLength;
    if (newPassword.length < 12 || byteLength > MAX_PASSWORD_BYTES) {
      setValidationIssue({
        fieldId: 'new-password',
        message: 'Use at least 12 characters and no more than 72 UTF-8 bytes.',
      });
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
            <IdentityFormIssueAlert
              message="This reset link is missing its one-time token or is malformed."
              title="This reset link cannot be used"
            />
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
            {validationIssue && <IdentityFormIssueAlert {...validationIssue} />}
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
                  aria-describedby={
                    validationIssue?.fieldId === 'new-password' ? 'new-password-error' : undefined
                  }
                  aria-invalid={validationIssue?.fieldId === 'new-password'}
                  value={newPassword}
                  onChange={(event) => {
                    setNewPassword(event.target.value);
                    if (validationIssue?.fieldId === 'new-password') {
                      setValidationIssue(null);
                    }
                  }}
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
                  aria-describedby={
                    validationIssue?.fieldId === 'confirm-password'
                      ? 'confirm-password-error'
                      : undefined
                  }
                  aria-invalid={validationIssue?.fieldId === 'confirm-password'}
                  value={confirmation}
                  onChange={(event) => {
                    setConfirmation(event.target.value);
                    if (validationIssue?.fieldId === 'confirm-password') {
                      setValidationIssue(null);
                    }
                  }}
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

type InvitationAcceptanceScreenProps = {
  authenticatedEmail?: string;
  busy: boolean;
  issue: SessionIssue | null;
  onAccept(token: string, newPassword?: string): Promise<InvitationAcceptance | null>;
  onForgetToken(): void;
  token?: string;
};

export function InvitationAcceptanceScreen({
  authenticatedEmail,
  busy,
  issue,
  onAccept,
  onForgetToken,
  token,
}: InvitationAcceptanceScreenProps) {
  const [newPassword, setNewPassword] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [validationIssue, setValidationIssue] = useState<FieldValidationIssue | null>(null);
  const [acceptance, setAcceptance] = useState<InvitationAcceptance | null>(null);
  const usableToken =
    typeof token === 'string' &&
    token.length >= MIN_INVITATION_TOKEN_LENGTH &&
    token.length <= MAX_RESET_TOKEN_LENGTH;
  const creatingAccount = authenticatedEmail === undefined;

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setValidationIssue(null);
    if (!usableToken) {
      return;
    }
    let submittedPassword: string | undefined;
    if (creatingAccount) {
      if (newPassword !== confirmation) {
        setValidationIssue({
          fieldId: 'invitation-password-confirmation',
          message: 'The password confirmation does not match.',
        });
        return;
      }
      const byteLength = new TextEncoder().encode(newPassword).byteLength;
      if (newPassword.length < 12 || byteLength > MAX_PASSWORD_BYTES) {
        setValidationIssue({
          fieldId: 'invitation-password',
          message: 'Use at least 12 characters and no more than 72 UTF-8 bytes.',
        });
        return;
      }
      submittedPassword = newPassword;
    }

    setNewPassword('');
    setConfirmation('');
    const result = await onAccept(token, submittedPassword);
    if (result) {
      onForgetToken();
      setAcceptance(result);
    }
  };

  return (
    <IdentityFrame>
      <section className="auth-panel panel" aria-labelledby="invitation-acceptance-heading">
        <span className="eyebrow">M1-02</span>
        <IdentityMark>
          <UserPlus aria-hidden="true" />
        </IdentityMark>
        <h1 id="invitation-acceptance-heading">
          {acceptance ? 'Invitation accepted' : 'Accept your CareOS invitation'}
        </h1>
        {acceptance ? (
          <>
            <p className="success-callout" role="status">
              Organization access was linked to your {acceptance.accountLink} account.
            </p>
            <p>
              The one-time invitation is consumed. Role: <strong>{acceptance.roleKey}</strong>.
            </p>
            <a className="primary-button full-button button-link" href="#/M1-01">
              {acceptance.accountLink === 'created' ? 'Sign in to CareOS' : 'Continue to CareOS'}
            </a>
          </>
        ) : !usableToken ? (
          <>
            <IdentityFormIssueAlert
              message="This invitation link is missing its one-time token or is malformed."
              title="This invitation link cannot be used"
            />
            <a className="primary-button full-button button-link" href="#/M1-01">
              Continue to sign in
            </a>
          </>
        ) : (
          <>
            <p>
              {creatingAccount
                ? 'Choose a password to create the invited account. If this email already belongs to an account, sign in first and reopen the invitation link.'
                : `Accept this invitation as ${authenticatedEmail}. The server will reject a token addressed to any other account.`}
            </p>
            {issue && <SessionIssueAlert issue={issue} />}
            {validationIssue && <IdentityFormIssueAlert {...validationIssue} />}
            <form onSubmit={(event) => void submit(event)}>
              {creatingAccount && (
                <>
                  <label htmlFor="invitation-password">
                    New password
                    <input
                      id="invitation-password"
                      type="password"
                      autoComplete="new-password"
                      minLength={12}
                      maxLength={128}
                      required
                      aria-describedby={
                        validationIssue?.fieldId === 'invitation-password'
                          ? 'invitation-password-error'
                          : undefined
                      }
                      aria-invalid={validationIssue?.fieldId === 'invitation-password'}
                      value={newPassword}
                      onChange={(event) => {
                        setNewPassword(event.target.value);
                        if (validationIssue?.fieldId === 'invitation-password') {
                          setValidationIssue(null);
                        }
                      }}
                      disabled={busy}
                    />
                  </label>
                  <label htmlFor="invitation-password-confirmation">
                    Confirm new password
                    <input
                      id="invitation-password-confirmation"
                      type="password"
                      autoComplete="new-password"
                      minLength={12}
                      maxLength={128}
                      required
                      aria-describedby={
                        validationIssue?.fieldId === 'invitation-password-confirmation'
                          ? 'invitation-password-confirmation-error'
                          : undefined
                      }
                      aria-invalid={validationIssue?.fieldId === 'invitation-password-confirmation'}
                      value={confirmation}
                      onChange={(event) => {
                        setConfirmation(event.target.value);
                        if (validationIssue?.fieldId === 'invitation-password-confirmation') {
                          setValidationIssue(null);
                        }
                      }}
                      disabled={busy}
                    />
                  </label>
                </>
              )}
              <button className="primary-button full-button" disabled={busy}>
                {busy ? 'Accepting invitation...' : 'Accept invitation'}
              </button>
            </form>
            <p className="security-note">
              The token is removed from browser history after this page captures it and is sent only
              to the checked acceptance endpoint.
            </p>
          </>
        )}
      </section>
    </IdentityFrame>
  );
}

type InvitationAdministrationScreenProps = {
  busyAction: SessionAction | null;
  issue: SessionIssue | null;
  onIssue(
    invitation: InvitationIssueRequest & { idempotencyKey: string },
  ): Promise<InvitationMutation | null>;
  onRevoke(
    invitation: InvitationRevocationRequest & {
      idempotencyKey: string;
      invitationId: string;
    },
  ): Promise<InvitationMutation | null>;
  organization: OrganizationAccess;
  recentAuthentication: boolean;
};

function idempotencyKey(scope: string): string {
  if (!globalThis.crypto?.randomUUID) {
    throw new Error('Secure browser randomness is required for governed mutations.');
  }
  return `${scope}:${globalThis.crypto.randomUUID()}`;
}

export function InvitationAdministrationScreen({
  busyAction,
  issue,
  onIssue,
  onRevoke,
  organization,
  recentAuthentication,
}: InvitationAdministrationScreenProps) {
  const [email, setEmail] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [roleKey, setRoleKey] = useState('organization_viewer');
  const [reason, setReason] = useState('');
  const [revocationReason, setRevocationReason] = useState('');
  const [invitation, setInvitation] = useState<InvitationMutation | null>(null);
  const [localIssue, setLocalIssue] = useState('');
  const issueAttempt = useRef<{ fingerprint: string; key: string } | null>(null);
  const revokeAttempt = useRef<{ fingerprint: string; key: string } | null>(null);
  const busy = busyAction !== null;

  const submitIssue = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setLocalIssue('');
    const request = {
      displayName: displayName.trim(),
      email: email.trim(),
      reason: reason.trim(),
      roleKey,
    };
    const fingerprint = JSON.stringify(request);
    try {
      if (issueAttempt.current?.fingerprint !== fingerprint) {
        issueAttempt.current = { fingerprint, key: idempotencyKey('invite') };
      }
      const result = await onIssue({
        ...request,
        idempotencyKey: issueAttempt.current.key,
      });
      if (result) {
        issueAttempt.current = null;
        revokeAttempt.current = null;
        setEmail('');
        setDisplayName('');
        setReason('');
        setRevocationReason('');
        setInvitation(result);
      }
    } catch (error) {
      setLocalIssue(error instanceof Error ? error.message : 'Invitation could not be prepared.');
    }
  };

  const submitRevoke = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!invitation || invitation.status !== 'pending') {
      return;
    }
    setLocalIssue('');
    const request = {
      invitationId: invitation.invitationId,
      reason: revocationReason.trim(),
    };
    const fingerprint = JSON.stringify(request);
    try {
      if (revokeAttempt.current?.fingerprint !== fingerprint) {
        revokeAttempt.current = { fingerprint, key: idempotencyKey('revoke') };
      }
      const result = await onRevoke({
        ...request,
        idempotencyKey: revokeAttempt.current.key,
      });
      if (result) {
        revokeAttempt.current = null;
        setRevocationReason('');
        setInvitation(result);
      }
    } catch (error) {
      setLocalIssue(error instanceof Error ? error.message : 'Revocation could not be prepared.');
    }
  };

  return (
    <IdentityFrame homeHref="#/M1-05" homeLabel="ROOTOPATHY CareOS workspace home">
      <section
        className="auth-panel security-workflow-panel panel"
        aria-labelledby="invitation-administration-heading"
      >
        <span className="eyebrow">M1-02</span>
        <IdentityMark>
          <UserPlus aria-hidden="true" />
        </IdentityMark>
        <h1 id="invitation-administration-heading">Organization invitations</h1>
        <p>
          Issue access to <strong>{organization.displayName}</strong>. The server enforces your
          role's delegation ceiling and records the stated reason.
        </p>
        {issue && <SessionIssueAlert issue={issue} />}
        {localIssue && (
          <IdentityFormIssueAlert
            message={localIssue}
            title="The invitation could not be prepared"
          />
        )}

        {!recentAuthentication ? (
          <div className="security-action-card">
            <h2>Recent verification required</h2>
            <p>Verify your password and second factor before issuing or revoking access.</p>
            <a className="primary-button full-button button-link" href="#/M1-03">
              Verify identity
            </a>
          </div>
        ) : invitation ? (
          <div className="security-action-card">
            <h2>{invitation.status === 'pending' ? 'Invitation issued' : 'Invitation revoked'}</h2>
            <p className="success-callout" role="status">
              {invitation.status === 'pending'
                ? 'A one-time link was sent by the configured notification channel.'
                : 'The invitation token can no longer be accepted.'}
            </p>
            <p>
              Role: <strong>{invitation.roleKey}</strong>
              <br />
              Expires: <time dateTime={invitation.expiresAt}>{invitation.expiresAt}</time>
              <br />
              Reference: {invitation.invitationId}
            </p>
            {invitation.status === 'pending' && (
              <form onSubmit={(event) => void submitRevoke(event)}>
                <label htmlFor="invitation-revocation-reason">
                  Revocation reason
                  <textarea
                    id="invitation-revocation-reason"
                    maxLength={2000}
                    required
                    value={revocationReason}
                    onChange={(event) => setRevocationReason(event.target.value)}
                    disabled={busy}
                  />
                </label>
                <button className="secondary-button full-button" disabled={busy}>
                  {busyAction === 'revoke-invitation' ? 'Revoking...' : 'Revoke invitation'}
                </button>
              </form>
            )}
            <button
              type="button"
              className="text-action centered-action"
              disabled={busy}
              onClick={() => setInvitation(null)}
            >
              Issue another invitation
            </button>
          </div>
        ) : (
          <form onSubmit={(event) => void submitIssue(event)}>
            <label htmlFor="invitation-email">
              Email address
              <input
                id="invitation-email"
                type="email"
                autoComplete="off"
                maxLength={320}
                required
                value={email}
                onChange={(event) => setEmail(event.target.value)}
                disabled={busy}
              />
            </label>
            <label htmlFor="invitation-display-name">
              Display name
              <input
                id="invitation-display-name"
                type="text"
                autoComplete="off"
                maxLength={160}
                required
                value={displayName}
                onChange={(event) => setDisplayName(event.target.value)}
                disabled={busy}
              />
            </label>
            <label htmlFor="invitation-role">
              Role
              <select
                id="invitation-role"
                value={roleKey}
                onChange={(event) => setRoleKey(event.target.value)}
                disabled={busy}
              >
                <option value="organization_viewer">Organization viewer</option>
                <option value="configuration_editor">Configuration editor</option>
                <option value="configuration_approver">Configuration approver</option>
                <option value="organization_administrator">Organization administrator</option>
                <option value="security_administrator">Security administrator</option>
                <option value="auditor">Auditor</option>
                <option value="export_approver">Export approver</option>
              </select>
            </label>
            <label htmlFor="invitation-reason">
              Access reason
              <textarea
                id="invitation-reason"
                maxLength={2000}
                required
                value={reason}
                onChange={(event) => setReason(event.target.value)}
                disabled={busy}
              />
            </label>
            <button className="primary-button full-button" disabled={busy}>
              {busyAction === 'issue-invitation'
                ? 'Inviting administrator...'
                : 'Invite administrator'}
            </button>
          </form>
        )}

        <div className="security-footer-actions">
          <a className="text-action button-link" href="#/M1-05">
            Return to workspace
          </a>
        </div>
      </section>
    </IdentityFrame>
  );
}

type MfaAdministrationScreenProps = {
  issue: SessionIssue | null;
  mfaEnabled: boolean;
  onApproveAdministrativeReset(request: {
    approvalId: string;
    idempotencyKey: string;
    reason: string;
    targetUserId: string;
  }): Promise<MfaResetMutation | null>;
  onExecuteAdministrativeReset(request: {
    approvalId: string;
    idempotencyKey: string;
    reason: string;
    targetUserId: string;
  }): Promise<MfaResetMutation | null>;
  onLogout(): Promise<void>;
  onRegenerateRecoveryCodes(): Promise<string[] | null>;
  onRequestAdministrativeReset(request: {
    idempotencyKey: string;
    reason: string;
    targetUserId: string;
  }): Promise<MfaResetMutation | null>;
  onStartEnrollment(label?: string): Promise<MfaEnrollment | null>;
  onVerifyEnrollment(code: string): Promise<string[] | null>;
  onVerifyRecentAuthentication(credentials: {
    password: string;
    secondFactor?: string;
  }): Promise<boolean>;
  pendingAction: SessionAction | null;
  recentAuthentication: boolean;
  selectedOrganization?: OrganizationAccess;
  user: User;
};

type MfaView =
  | { kind: 'overview' }
  | { enrollment: MfaEnrollment; kind: 'enrollment' }
  | { codes: string[]; kind: 'recovery_codes'; source: 'enrollment' | 'regeneration' };

export function MfaAdministrationScreen({
  issue,
  mfaEnabled,
  onApproveAdministrativeReset,
  onExecuteAdministrativeReset,
  onLogout,
  onRegenerateRecoveryCodes,
  onRequestAdministrativeReset,
  onStartEnrollment,
  onVerifyEnrollment,
  onVerifyRecentAuthentication,
  pendingAction,
  recentAuthentication,
  selectedOrganization,
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

        {recentAuthentication && selectedOrganization && (
          <MfaAdministrativeResetPanel
            busyAction={pendingAction}
            onApprove={onApproveAdministrativeReset}
            onExecute={onExecuteAdministrativeReset}
            onRequest={onRequestAdministrativeReset}
            organization={selectedOrganization}
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

type MfaEnrollmentRequiredScreenProps = {
  issue: SessionIssue | null;
  onContinue(): Promise<void>;
  onLogout(): Promise<void>;
  onStartEnrollment(label?: string): Promise<MfaEnrollment | null>;
  onVerifyEnrollment(code: string): Promise<string[] | null>;
  pendingAction: SessionAction | null;
  user: User;
};

export function MfaEnrollmentRequiredScreen({
  issue,
  onContinue,
  onLogout,
  onStartEnrollment,
  onVerifyEnrollment,
  pendingAction,
  user,
}: MfaEnrollmentRequiredScreenProps) {
  return (
    <IdentityFrame>
      <section
        className="auth-panel security-workflow-panel panel"
        aria-labelledby="mfa-enrollment-required-heading"
      >
        <span className="eyebrow">M1-03 / Required</span>
        <IdentityMark>
          <ShieldCheck aria-hidden="true" />
        </IdentityMark>
        <h1 id="mfa-enrollment-required-heading">Set up multi-factor authentication</h1>
        <p>
          An active access role for <strong>{user.email}</strong> requires MFA. Your password was
          verified, but workspace access remains locked until an authenticator is confirmed.
        </p>
        {issue && <SessionIssueAlert issue={issue} />}
        <RecentlyAuthenticatedMfaControls
          mfaEnabled={false}
          onEnrollmentStored={() => void onContinue()}
          onRegenerateRecoveryCodes={async () => null}
          onStartEnrollment={onStartEnrollment}
          onVerifyEnrollment={onVerifyEnrollment}
          pendingAction={pendingAction}
          user={user}
        />
        <div className="security-footer-actions">
          <button
            type="button"
            className="text-action"
            disabled={pendingAction !== null}
            onClick={() => void onLogout()}
          >
            Cancel and sign out
          </button>
        </div>
      </section>
    </IdentityFrame>
  );
}

function RecentlyAuthenticatedMfaControls({
  mfaEnabled,
  onEnrollmentStored,
  onRegenerateRecoveryCodes,
  onStartEnrollment,
  onVerifyEnrollment,
  pendingAction,
  user,
}: {
  mfaEnabled: boolean;
  onEnrollmentStored?(): void;
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
        onStored={() => {
          if (view.source === 'enrollment' && onEnrollmentStored) {
            onEnrollmentStored();
          } else {
            setView({ kind: 'overview' });
          }
        }}
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
              : 'Set up authenticator'}
          </button>
        </div>
      )}
      <p className="security-note">
        Self-service MFA disable remains unavailable. Administrator reset uses a separate,
        time-bounded maker-checker workflow and never reveals the target's MFA material.
      </p>
    </div>
  );
}

type AdministrativeResetAction = 'approve' | 'execute' | 'request';

function MfaAdministrativeResetPanel({
  busyAction,
  onApprove,
  onExecute,
  onRequest,
  organization,
}: {
  busyAction: SessionAction | null;
  onApprove(request: {
    approvalId: string;
    idempotencyKey: string;
    reason: string;
    targetUserId: string;
  }): Promise<MfaResetMutation | null>;
  onExecute(request: {
    approvalId: string;
    idempotencyKey: string;
    reason: string;
    targetUserId: string;
  }): Promise<MfaResetMutation | null>;
  onRequest(request: {
    idempotencyKey: string;
    reason: string;
    targetUserId: string;
  }): Promise<MfaResetMutation | null>;
  organization: OrganizationAccess;
}) {
  const [action, setAction] = useState<AdministrativeResetAction>('request');
  const [approvalId, setApprovalId] = useState('');
  const [targetUserId, setTargetUserId] = useState('');
  const [reason, setReason] = useState('');
  const [outcome, setOutcome] = useState<MfaResetMutation | null>(null);
  const [localIssue, setLocalIssue] = useState('');
  const attempt = useRef<{
    action: AdministrativeResetAction;
    fingerprint: string;
    key: string;
  } | null>(null);
  const busy = busyAction !== null;

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setLocalIssue('');
    const normalizedTarget = targetUserId.trim();
    const normalizedApproval = approvalId.trim();
    const normalizedReason = reason.trim();
    const fingerprint = JSON.stringify({
      action,
      approvalId: normalizedApproval,
      reason: normalizedReason,
      targetUserId: normalizedTarget,
    });
    try {
      if (attempt.current?.fingerprint !== fingerprint || attempt.current.action !== action) {
        attempt.current = { action, fingerprint, key: idempotencyKey(`mfa-${action}`) };
      }
      const request = {
        idempotencyKey: attempt.current.key,
        reason: normalizedReason,
        targetUserId: normalizedTarget,
      };
      const result =
        action === 'request'
          ? await onRequest(request)
          : action === 'approve'
            ? await onApprove({ ...request, approvalId: normalizedApproval })
            : await onExecute({ ...request, approvalId: normalizedApproval });
      if (result) {
        attempt.current = null;
        setOutcome(result);
        setApprovalId(result.approvalId);
      }
    } catch (error) {
      setLocalIssue(
        error instanceof Error ? error.message : 'The MFA reset action could not be prepared.',
      );
    }
  };

  const actionLabel =
    action === 'request'
      ? 'Request independent approval'
      : action === 'approve'
        ? 'Approve as independent checker'
        : 'Execute approved reset';
  const pending =
    busyAction === 'request-mfa-administrative-reset' ||
    busyAction === 'approve-mfa-administrative-reset' ||
    busyAction === 'execute-mfa-administrative-reset';

  return (
    <section className="security-action-card" aria-labelledby="administrative-mfa-reset-heading">
      <h2 id="administrative-mfa-reset-heading">Administrator MFA reset</h2>
      <p>
        Governed action for <strong>{organization.displayName}</strong>. One administrator requests
        the reset, a different authorized administrator approves it, and only the original requester
        can execute that exact request.
      </p>
      {localIssue && (
        <IdentityFormIssueAlert
          message={localIssue}
          title="The MFA reset action could not be prepared"
        />
      )}
      {outcome && (
        <p className="success-callout" role="status">
          Workflow status: <strong>{outcome.status}</strong>
          <br />
          Approval reference: {outcome.approvalId}
          <br />
          Expires: <time dateTime={outcome.expiresAt}>{outcome.expiresAt}</time>
        </p>
      )}
      <form onSubmit={(event) => void submit(event)}>
        <label htmlFor="administrative-mfa-reset-action">
          Workflow action
          <select
            id="administrative-mfa-reset-action"
            value={action}
            onChange={(event) => {
              setAction(event.target.value as AdministrativeResetAction);
              setOutcome(null);
            }}
            disabled={busy}
          >
            <option value="request">Request reset</option>
            <option value="approve">Independently approve</option>
            <option value="execute">Execute approved reset</option>
          </select>
        </label>
        <label htmlFor="administrative-mfa-target-user">
          Target user ID
          <input
            id="administrative-mfa-target-user"
            type="text"
            inputMode="text"
            autoComplete="off"
            pattern="[0-9a-fA-F-]{36}"
            maxLength={36}
            required
            value={targetUserId}
            onChange={(event) => setTargetUserId(event.target.value)}
            disabled={busy}
          />
        </label>
        {action !== 'request' && (
          <label htmlFor="administrative-mfa-approval-id">
            Approval reference
            <input
              id="administrative-mfa-approval-id"
              type="text"
              inputMode="text"
              autoComplete="off"
              pattern="[0-9a-fA-F-]{36}"
              maxLength={36}
              required
              value={approvalId}
              onChange={(event) => setApprovalId(event.target.value)}
              disabled={busy}
            />
          </label>
        )}
        <label htmlFor="administrative-mfa-reset-reason">
          {action === 'approve' ? 'Independent decision reason' : 'Reset reason'}
          <textarea
            id="administrative-mfa-reset-reason"
            maxLength={2000}
            required
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            disabled={busy}
          />
        </label>
        {action === 'execute' && (
          <p className="security-note">
            Execution must use the exact reason entered by the original requester. The approval is
            consumed atomically if the reset succeeds.
          </p>
        )}
        <button className="secondary-button full-button" disabled={busy}>
          {pending ? 'Submitting governed action...' : actionLabel}
        </button>
      </form>
    </section>
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
