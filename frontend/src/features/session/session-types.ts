import type { CareOsApiClient } from '../../api/client';
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

export type SessionClient = Pick<
  CareOsApiClient,
  | 'acceptInvitation'
  | 'approveMfaAdministrativeReset'
  | 'completeMfaChallenge'
  | 'completePasswordReset'
  | 'getAuthenticationSession'
  | 'executeMfaAdministrativeReset'
  | 'issueInvitation'
  | 'listSelectableOrganizations'
  | 'login'
  | 'logout'
  | 'regenerateRecoveryCodes'
  | 'requestPasswordReset'
  | 'requestMfaAdministrativeReset'
  | 'revokeInvitation'
  | 'selectOrganization'
  | 'startMfaEnrollment'
  | 'subscribeSessionLifecycle'
  | 'verifyMfaEnrollment'
  | 'verifyRecentAuthentication'
>;

export type SessionIssue = {
  code: string;
  correlationId: string;
  detail: string;
  retryAfterSeconds?: number;
  title: string;
};

export type SessionMachine =
  | { phase: 'loading'; reason: 'organizations' | 'session' }
  | { phase: 'anonymous' }
  | { phase: 'mfa_required'; user: User }
  | {
      mfaEnabled: boolean;
      phase: 'selecting_organization';
      organizations: OrganizationAccess[];
      recentAuthentication: boolean;
      user: User;
    }
  | { mfaEnabled: boolean; phase: 'no_organization'; recentAuthentication: boolean; user: User }
  | {
      mfaEnabled: boolean;
      phase: 'ready';
      organizations: OrganizationAccess[];
      recentAuthentication: boolean;
      selectedOrganization: OrganizationAccess;
      user: User;
    }
  | { issue: SessionIssue; phase: 'failure' };

export type SessionAction =
  | 'accept-invitation'
  | 'approve-mfa-administrative-reset'
  | 'complete-mfa'
  | 'complete-password-reset'
  | 'login'
  | 'logout'
  | 'regenerate-recovery-codes'
  | 'request-password-reset'
  | 'request-mfa-administrative-reset'
  | 'issue-invitation'
  | 'revoke-invitation'
  | 'execute-mfa-administrative-reset'
  | 'select-organization'
  | 'start-mfa-enrollment'
  | 'verify-mfa-enrollment'
  | 'verify-recent-authentication';

export type SessionContextValue = {
  acceptInvitation(token: string, newPassword?: string): Promise<InvitationAcceptance | null>;
  actionIssue: SessionIssue | null;
  approveMfaAdministrativeReset(request: {
    approvalId: string;
    idempotencyKey: string;
    reason: string;
    targetUserId: string;
  }): Promise<MfaResetMutation | null>;
  completeMfa(code: string): Promise<void>;
  completePasswordReset(token: string, newPassword: string): Promise<boolean>;
  dismissActionIssue(): void;
  login(credentials: { email: string; password: string }): Promise<void>;
  logout(): Promise<void>;
  machine: SessionMachine;
  pendingAction: SessionAction | null;
  regenerateRecoveryCodes(): Promise<string[] | null>;
  requestPasswordReset(email: string): Promise<boolean>;
  requestMfaAdministrativeReset(request: {
    idempotencyKey: string;
    reason: string;
    targetUserId: string;
  }): Promise<MfaResetMutation | null>;
  issueInvitation(
    invitation: InvitationIssueRequest & { idempotencyKey: string },
  ): Promise<InvitationMutation | null>;
  revokeInvitation(
    invitation: InvitationRevocationRequest & {
      idempotencyKey: string;
      invitationId: string;
    },
  ): Promise<InvitationMutation | null>;
  executeMfaAdministrativeReset(request: {
    approvalId: string;
    idempotencyKey: string;
    reason: string;
    targetUserId: string;
  }): Promise<MfaResetMutation | null>;
  retryBootstrap(): Promise<void>;
  selectOrganization(organizationId: string): Promise<void>;
  startMfaEnrollment(label?: string): Promise<MfaEnrollment | null>;
  verifyMfaEnrollment(code: string): Promise<string[] | null>;
  verifyRecentAuthentication(credentials: {
    password: string;
    secondFactor?: string;
  }): Promise<boolean>;
};
