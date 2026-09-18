import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { careOsApi, type ApiFailure, type SessionLifecycleEvent } from '../../api/client';
import type {
  InvitationAcceptance,
  InvitationMutation,
  MfaEnrollment,
  MfaResetMutation,
  OrganizationAccess,
  SessionState,
  User,
} from '../../api/generated';
import { SessionContext } from './session-context';
import type {
  SessionAction,
  SessionClient,
  SessionContextValue,
  SessionIssue,
  SessionMachine,
} from './session-types';

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const MAX_TIMER_DELAY_MS = 2_147_000_000;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isUser(value: unknown): value is User {
  return (
    isRecord(value) &&
    typeof value.id === 'string' &&
    UUID_PATTERN.test(value.id) &&
    typeof value.email === 'string' &&
    value.email.length > 0 &&
    typeof value.displayName === 'string' &&
    value.displayName.trim().length > 0
  );
}

function validSession(value: unknown): value is SessionState {
  if (
    !isRecord(value) ||
    typeof value.recentAuthentication !== 'boolean' ||
    typeof value.mfaEnabled !== 'boolean' ||
    typeof value.mfaRequired !== 'boolean'
  ) {
    return false;
  }
  if (value.state === 'anonymous') {
    return (
      value.user === null &&
      value.recentAuthentication === false &&
      value.mfaEnabled === false &&
      value.mfaRequired === false
    );
  }
  if (value.state === 'mfa_required') {
    return isUser(value.user) && value.mfaEnabled && !value.recentAuthentication;
  }
  if (value.state === 'mfa_enrollment_required') {
    return (
      isUser(value.user) && !value.mfaEnabled && value.mfaRequired && value.recentAuthentication
    );
  }
  return (
    value.state === 'authenticated' &&
    isUser(value.user) &&
    (!value.mfaRequired || value.mfaEnabled)
  );
}

function isOrganization(value: unknown): value is OrganizationAccess {
  return (
    isRecord(value) &&
    typeof value.id === 'string' &&
    UUID_PATTERN.test(value.id) &&
    typeof value.displayName === 'string' &&
    value.displayName.trim().length > 0 &&
    (value.status === 'draft' || value.status === 'active') &&
    Array.isArray(value.roleKeys) &&
    value.roleKeys.every((role) => typeof role === 'string' && role.length > 0) &&
    typeof value.selected === 'boolean'
  );
}

function validOrganizations(value: unknown): value is OrganizationAccess[] {
  if (!Array.isArray(value) || !value.every(isOrganization)) {
    return false;
  }
  const ids = new Set(value.map((organization) => organization.id));
  return (
    ids.size === value.length && value.filter((organization) => organization.selected).length <= 1
  );
}

function validMfaEnrollment(value: unknown): value is MfaEnrollment {
  return (
    isRecord(value) &&
    typeof value.secret === 'string' &&
    value.secret.length >= 16 &&
    typeof value.provisioningUri === 'string' &&
    value.provisioningUri.startsWith('otpauth://totp/')
  );
}

const RECOVERY_CODE_PATTERN =
  /^[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{4}(?:-[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{4}){2}$/;

function validRecoveryCodes(value: unknown): value is { recoveryCodes: string[] } {
  if (
    !isRecord(value) ||
    !Array.isArray(value.recoveryCodes) ||
    value.recoveryCodes.length === 0 ||
    !value.recoveryCodes.every(
      (code): code is string => typeof code === 'string' && RECOVERY_CODE_PATTERN.test(code),
    )
  ) {
    return false;
  }
  return new Set(value.recoveryCodes).size === value.recoveryCodes.length;
}

function validInvitationMutation(value: unknown): value is InvitationMutation {
  return (
    isRecord(value) &&
    typeof value.invitationId === 'string' &&
    UUID_PATTERN.test(value.invitationId) &&
    (value.status === 'pending' || value.status === 'revoked') &&
    typeof value.roleKey === 'string' &&
    value.roleKey.length > 0 &&
    typeof value.expiresAt === 'string' &&
    Number.isFinite(Date.parse(value.expiresAt))
  );
}

function validInvitationAcceptance(value: unknown): value is InvitationAcceptance {
  return (
    isRecord(value) &&
    typeof value.invitationId === 'string' &&
    UUID_PATTERN.test(value.invitationId) &&
    typeof value.organizationId === 'string' &&
    UUID_PATTERN.test(value.organizationId) &&
    typeof value.userId === 'string' &&
    UUID_PATTERN.test(value.userId) &&
    typeof value.roleKey === 'string' &&
    value.roleKey.length > 0 &&
    (value.accountLink === 'existing' || value.accountLink === 'created')
  );
}

function validMfaResetMutation(value: unknown): value is MfaResetMutation {
  return (
    isRecord(value) &&
    typeof value.approvalId === 'string' &&
    UUID_PATTERN.test(value.approvalId) &&
    typeof value.targetUserId === 'string' &&
    UUID_PATTERN.test(value.targetUserId) &&
    (value.status === 'pending' || value.status === 'approved' || value.status === 'reset') &&
    typeof value.expiresAt === 'string' &&
    Number.isFinite(Date.parse(value.expiresAt))
  );
}

type FullyAuthenticatedMachine =
  | Extract<SessionMachine, { phase: 'no_organization' }>
  | Extract<SessionMachine, { phase: 'ready' }>
  | Extract<SessionMachine, { phase: 'selecting_organization' }>;

function isFullyAuthenticated(machine: SessionMachine): machine is FullyAuthenticatedMachine {
  return (
    machine.phase === 'no_organization' ||
    machine.phase === 'ready' ||
    machine.phase === 'selecting_organization'
  );
}

function hasAuthenticatedSession(machine: SessionMachine): boolean {
  return (
    machine.phase === 'mfa_required' ||
    machine.phase === 'mfa_enrollment_required' ||
    isFullyAuthenticated(machine)
  );
}

function issueFromFailure(failure: ApiFailure): SessionIssue {
  return {
    code: failure.problem.code,
    correlationId: failure.correlationId,
    detail: failure.problem.detail,
    ...(failure.retryAfterSeconds === undefined
      ? {}
      : { retryAfterSeconds: failure.retryAfterSeconds }),
    title: failure.problem.title,
  };
}

function contractIssue(correlationId: string, detail: string): SessionIssue {
  return {
    code: 'invalid_api_response',
    correlationId,
    detail,
    title: 'Invalid API response',
  };
}

type SessionProviderProps = {
  children: ReactNode;
  client?: SessionClient;
};

export function SessionProvider({ children, client = careOsApi }: SessionProviderProps) {
  const [machine, setMachine] = useState<SessionMachine>({ phase: 'loading', reason: 'session' });
  const [pendingAction, setPendingAction] = useState<SessionAction | null>(null);
  const [actionIssue, setActionIssue] = useState<SessionIssue | null>(null);
  const generation = useRef(0);
  const identityOperation = useRef(0);
  const expiryTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const sessionDeadline = useRef<number | null>(null);
  const machineRef = useRef(machine);
  const pendingActionRef = useRef(pendingAction);
  const resumeRevalidationInFlight = useRef(false);

  useEffect(() => {
    machineRef.current = machine;
    pendingActionRef.current = pendingAction;
  }, [machine, pendingAction]);

  const clearSessionDeadline = useCallback(() => {
    sessionDeadline.current = null;
    if (expiryTimer.current !== null) {
      clearTimeout(expiryTimer.current);
      expiryTimer.current = null;
    }
  }, []);

  const lockEndedSession = useCallback(() => {
    clearSessionDeadline();
    ++generation.current;
    ++identityOperation.current;
    setMachine({ phase: 'anonymous' });
    setPendingAction(null);
    setActionIssue({
      code: 'session-expired',
      correlationId: 'unavailable',
      detail: 'Your session ended or was revoked. Sign in again to continue.',
      title: 'Session ended',
    });
  }, [clearSessionDeadline]);

  const scheduleSessionDeadline = useCallback(
    (expiresAt: number): boolean => {
      if (!Number.isSafeInteger(expiresAt) || expiresAt <= 0) {
        return false;
      }
      clearSessionDeadline();
      sessionDeadline.current = expiresAt;

      const checkDeadline = () => {
        const remaining = expiresAt - Date.now();
        if (remaining <= 0) {
          lockEndedSession();
          return;
        }
        expiryTimer.current = setTimeout(checkDeadline, Math.min(remaining, MAX_TIMER_DELAY_MS));
      };
      checkDeadline();
      return expiresAt > Date.now();
    },
    [clearSessionDeadline, lockEndedSession],
  );

  useEffect(
    () =>
      client.subscribeSessionLifecycle((event: SessionLifecycleEvent) => {
        if (event.type === 'invalidated') {
          if (sessionDeadline.current !== null || hasAuthenticatedSession(machineRef.current)) {
            lockEndedSession();
          }
          return;
        }
        if (sessionDeadline.current !== null) {
          scheduleSessionDeadline(event.expiresAt);
        }
      }),
    [client, lockEndedSession, scheduleSessionDeadline],
  );

  useEffect(() => clearSessionDeadline, [clearSessionDeadline]);

  const updateAuthenticationIndicators = useCallback(
    (updates: { mfaEnabled?: boolean; recentAuthentication?: boolean }) => {
      setMachine((current) => {
        if (!isFullyAuthenticated(current)) {
          return current;
        }
        return { ...current, ...updates };
      });
    },
    [],
  );

  const handleAuthenticatedActionFailure = useCallback(
    (failure: ApiFailure) => {
      if (failure.status === 401) {
        lockEndedSession();
      } else {
        if (failure.status === 428) {
          updateAuthenticationIndicators({ recentAuthentication: false });
        }
        setActionIssue(issueFromFailure(failure));
      }
      setPendingAction(null);
    },
    [lockEndedSession, updateAuthenticationIndicators],
  );

  const failOnInvalidActionResponse = useCallback(
    (correlationId: string, detail: string) => {
      clearSessionDeadline();
      setMachine({ issue: contractIssue(correlationId, detail), phase: 'failure' });
      setPendingAction(null);
    },
    [clearSessionDeadline],
  );

  const resolveAuthenticatedSession = useCallback(
    async (
      session: SessionState,
      correlationId: string,
      operation: number,
      expiresAt: number | undefined,
      signal?: AbortSignal,
    ) => {
      if (!validSession(session)) {
        clearSessionDeadline();
        setMachine({
          issue: contractIssue(
            correlationId,
            'The session response did not contain a valid authentication state.',
          ),
          phase: 'failure',
        });
        setPendingAction(null);
        return;
      }

      if (session.state === 'anonymous') {
        clearSessionDeadline();
        setMachine({ phase: 'anonymous' });
        setPendingAction(null);
        return;
      }
      if (expiresAt === undefined || !scheduleSessionDeadline(expiresAt)) {
        clearSessionDeadline();
        setMachine({
          issue: contractIssue(
            correlationId,
            'The authenticated session response omitted a valid server expiry deadline.',
          ),
          phase: 'failure',
        });
        setPendingAction(null);
        return;
      }
      const user = session.user;
      if (!user) {
        setMachine({
          issue: contractIssue(
            correlationId,
            'The authenticated session response omitted its user identity.',
          ),
          phase: 'failure',
        });
        setPendingAction(null);
        return;
      }
      if (session.state === 'mfa_required') {
        setMachine({ phase: 'mfa_required', user });
        setPendingAction(null);
        return;
      }
      if (session.state === 'mfa_enrollment_required') {
        setMachine({ phase: 'mfa_enrollment_required', user });
        setPendingAction(null);
        return;
      }

      setMachine({ phase: 'loading', reason: 'organizations' });
      const organizationsResult = await client.listSelectableOrganizations({ signal });
      if (signal?.aborted || operation !== generation.current) {
        return;
      }
      if (!organizationsResult.ok) {
        if (organizationsResult.status === 401) {
          lockEndedSession();
        } else {
          clearSessionDeadline();
          setMachine({ issue: issueFromFailure(organizationsResult), phase: 'failure' });
        }
        setPendingAction(null);
        return;
      }
      if (!validOrganizations(organizationsResult.data)) {
        clearSessionDeadline();
        setMachine({
          issue: contractIssue(
            organizationsResult.correlationId,
            'The organization response was malformed or contained an ambiguous selection.',
          ),
          phase: 'failure',
        });
        setPendingAction(null);
        return;
      }

      const selectedOrganization = organizationsResult.data.find(
        (organization) => organization.selected,
      );
      if (selectedOrganization) {
        setMachine({
          mfaEnabled: session.mfaEnabled,
          organizations: organizationsResult.data,
          phase: 'ready',
          recentAuthentication: session.recentAuthentication,
          selectedOrganization,
          user,
        });
      } else if (organizationsResult.data.length > 0) {
        setMachine({
          mfaEnabled: session.mfaEnabled,
          organizations: organizationsResult.data,
          phase: 'selecting_organization',
          recentAuthentication: session.recentAuthentication,
          user,
        });
      } else {
        setMachine({
          mfaEnabled: session.mfaEnabled,
          phase: 'no_organization',
          recentAuthentication: session.recentAuthentication,
          user,
        });
      }
      setPendingAction(null);
    },
    [clearSessionDeadline, client, lockEndedSession, scheduleSessionDeadline],
  );

  const bootstrap = useCallback(
    async (signal?: AbortSignal) => {
      const operation = ++generation.current;
      setActionIssue(null);
      setPendingAction(null);
      setMachine({ phase: 'loading', reason: 'session' });
      const result = await client.getAuthenticationSession({ signal });
      if (signal?.aborted || operation !== generation.current) {
        return;
      }
      if (!result.ok) {
        if (result.kind === 'aborted') {
          return;
        }
        clearSessionDeadline();
        setMachine({ issue: issueFromFailure(result), phase: 'failure' });
        return;
      }
      await resolveAuthenticatedSession(
        result.data,
        result.correlationId,
        operation,
        result.sessionExpiresAt,
        signal,
      );
    },
    [clearSessionDeadline, client, resolveAuthenticatedSession],
  );

  useEffect(() => {
    const controller = new AbortController();
    queueMicrotask(() => {
      if (!controller.signal.aborted) {
        void bootstrap(controller.signal);
      }
    });
    return () => {
      controller.abort();
    };
  }, [bootstrap]);

  useEffect(() => {
    const revalidateOnResume = () => {
      if (
        !hasAuthenticatedSession(machineRef.current) ||
        machineRef.current.phase === 'mfa_enrollment_required' ||
        pendingActionRef.current !== null ||
        resumeRevalidationInFlight.current
      ) {
        return;
      }
      const deadline = sessionDeadline.current;
      if (deadline === null || deadline <= Date.now()) {
        lockEndedSession();
        return;
      }
      resumeRevalidationInFlight.current = true;
      void bootstrap().finally(() => {
        resumeRevalidationInFlight.current = false;
      });
    };
    const onVisibilityChange = () => {
      if (document.visibilityState === 'visible') {
        revalidateOnResume();
      }
    };
    const onPageShow = (event: PageTransitionEvent) => {
      if (event.persisted) {
        revalidateOnResume();
      }
    };
    document.addEventListener('visibilitychange', onVisibilityChange);
    window.addEventListener('focus', revalidateOnResume);
    window.addEventListener('online', revalidateOnResume);
    window.addEventListener('pageshow', onPageShow);
    return () => {
      document.removeEventListener('visibilitychange', onVisibilityChange);
      window.removeEventListener('focus', revalidateOnResume);
      window.removeEventListener('online', revalidateOnResume);
      window.removeEventListener('pageshow', onPageShow);
    };
  }, [bootstrap, lockEndedSession]);

  const login = useCallback(
    async (credentials: { email: string; password: string }) => {
      if (machine.phase !== 'anonymous' || pendingAction) {
        return;
      }
      const operation = ++generation.current;
      setActionIssue(null);
      setPendingAction('login');
      const result = await client.login(credentials);
      if (operation !== generation.current) {
        return;
      }
      if (!result.ok) {
        setActionIssue(issueFromFailure(result));
        setPendingAction(null);
        return;
      }
      if (!validSession(result.data) || result.data.state === 'anonymous') {
        setActionIssue(
          contractIssue(
            result.correlationId,
            'The login response did not contain an authenticated, pending-MFA, or required-enrollment session.',
          ),
        );
        setPendingAction(null);
        return;
      }
      await resolveAuthenticatedSession(
        result.data,
        result.correlationId,
        operation,
        result.sessionExpiresAt,
      );
    },
    [client, machine.phase, pendingAction, resolveAuthenticatedSession],
  );

  const completeMfa = useCallback(
    async (code: string) => {
      if (machine.phase !== 'mfa_required' || pendingAction) {
        return;
      }
      const operation = ++generation.current;
      setActionIssue(null);
      setPendingAction('complete-mfa');
      const result = await client.completeMfaChallenge({ code });
      if (operation !== generation.current) {
        return;
      }
      if (!result.ok) {
        if (result.status === 401) {
          lockEndedSession();
        } else {
          setActionIssue(issueFromFailure(result));
        }
        setPendingAction(null);
        return;
      }
      if (!validSession(result.data) || result.data.state !== 'authenticated') {
        setActionIssue(
          contractIssue(
            result.correlationId,
            'The MFA response did not contain a fully authenticated session.',
          ),
        );
        setPendingAction(null);
        return;
      }
      await resolveAuthenticatedSession(
        result.data,
        result.correlationId,
        operation,
        result.sessionExpiresAt,
      );
    },
    [client, lockEndedSession, machine.phase, pendingAction, resolveAuthenticatedSession],
  );

  const requestPasswordReset = useCallback(
    async (email: string) => {
      if (pendingAction) {
        return false;
      }
      const operation = ++identityOperation.current;
      setActionIssue(null);
      setPendingAction('request-password-reset');
      const result = await client.requestPasswordReset({ email });
      if (operation !== identityOperation.current) {
        return false;
      }
      if (!result.ok) {
        setActionIssue(issueFromFailure(result));
        setPendingAction(null);
        return false;
      }
      setPendingAction(null);
      return true;
    },
    [client, pendingAction],
  );

  const completePasswordReset = useCallback(
    async (token: string, newPassword: string) => {
      if (pendingAction) {
        return false;
      }
      const operation = ++identityOperation.current;
      setActionIssue(null);
      setPendingAction('complete-password-reset');
      const result = await client.completePasswordReset({ newPassword, token });
      if (operation !== identityOperation.current) {
        return false;
      }
      if (!result.ok) {
        setActionIssue(issueFromFailure(result));
        setPendingAction(null);
        return false;
      }
      ++generation.current;
      clearSessionDeadline();
      setMachine({ phase: 'anonymous' });
      setPendingAction(null);
      return true;
    },
    [clearSessionDeadline, client, pendingAction],
  );

  const acceptInvitation = useCallback(
    async (token: string, newPassword?: string) => {
      if (pendingAction) {
        return null;
      }
      const operation = ++identityOperation.current;
      setActionIssue(null);
      setPendingAction('accept-invitation');
      const result = await client.acceptInvitation({
        token,
        ...(newPassword === undefined ? {} : { newPassword }),
      });
      if (operation !== identityOperation.current) {
        return null;
      }
      if (!result.ok) {
        if (result.status === 401 && hasAuthenticatedSession(machine)) {
          lockEndedSession();
        } else {
          setActionIssue(issueFromFailure(result));
          setPendingAction(null);
        }
        return null;
      }
      if (!validInvitationAcceptance(result.data)) {
        failOnInvalidActionResponse(
          result.correlationId,
          'The invitation-acceptance response did not contain a valid account link.',
        );
        return null;
      }
      setPendingAction(null);
      return result.data;
    },
    [client, failOnInvalidActionResponse, lockEndedSession, machine, pendingAction],
  );

  const issueInvitation = useCallback(
    async ({
      idempotencyKey,
      ...request
    }: Parameters<SessionContextValue['issueInvitation']>[0]) => {
      if (machine.phase !== 'ready' || pendingAction) {
        return null;
      }
      const operation = ++identityOperation.current;
      setActionIssue(null);
      setPendingAction('issue-invitation');
      const result = await client.issueInvitation(
        machine.selectedOrganization.id,
        request,
        idempotencyKey,
      );
      if (operation !== identityOperation.current) {
        return null;
      }
      if (!result.ok) {
        handleAuthenticatedActionFailure(result);
        return null;
      }
      if (!validInvitationMutation(result.data) || result.data.status !== 'pending') {
        failOnInvalidActionResponse(
          result.correlationId,
          'The invitation-issuance response did not contain a valid pending invitation.',
        );
        return null;
      }
      setPendingAction(null);
      return result.data;
    },
    [client, failOnInvalidActionResponse, handleAuthenticatedActionFailure, machine, pendingAction],
  );

  const revokeInvitation = useCallback(
    async ({
      idempotencyKey,
      invitationId,
      ...request
    }: Parameters<SessionContextValue['revokeInvitation']>[0]) => {
      if (machine.phase !== 'ready' || pendingAction) {
        return null;
      }
      const operation = ++identityOperation.current;
      setActionIssue(null);
      setPendingAction('revoke-invitation');
      const result = await client.revokeInvitation(
        machine.selectedOrganization.id,
        invitationId,
        request,
        idempotencyKey,
      );
      if (operation !== identityOperation.current) {
        return null;
      }
      if (!result.ok) {
        handleAuthenticatedActionFailure(result);
        return null;
      }
      if (
        !validInvitationMutation(result.data) ||
        result.data.status !== 'revoked' ||
        result.data.invitationId !== invitationId
      ) {
        failOnInvalidActionResponse(
          result.correlationId,
          'The invitation-revocation response did not confirm the requested invitation.',
        );
        return null;
      }
      setPendingAction(null);
      return result.data;
    },
    [client, failOnInvalidActionResponse, handleAuthenticatedActionFailure, machine, pendingAction],
  );

  const requestMfaAdministrativeReset = useCallback(
    async ({
      idempotencyKey,
      targetUserId,
      ...request
    }: Parameters<SessionContextValue['requestMfaAdministrativeReset']>[0]) => {
      if (machine.phase !== 'ready' || pendingAction) {
        return null;
      }
      const operation = ++identityOperation.current;
      setActionIssue(null);
      setPendingAction('request-mfa-administrative-reset');
      const result = await client.requestMfaAdministrativeReset(
        machine.selectedOrganization.id,
        targetUserId,
        request,
        idempotencyKey,
      );
      if (operation !== identityOperation.current) {
        return null;
      }
      if (!result.ok) {
        handleAuthenticatedActionFailure(result);
        return null;
      }
      if (
        !validMfaResetMutation(result.data) ||
        result.data.status !== 'pending' ||
        result.data.targetUserId !== targetUserId
      ) {
        failOnInvalidActionResponse(
          result.correlationId,
          'The MFA-reset response did not contain a valid pending approval request.',
        );
        return null;
      }
      setPendingAction(null);
      return result.data;
    },
    [client, failOnInvalidActionResponse, handleAuthenticatedActionFailure, machine, pendingAction],
  );

  const approveMfaAdministrativeReset = useCallback(
    async ({
      approvalId,
      idempotencyKey,
      targetUserId,
      ...request
    }: Parameters<SessionContextValue['approveMfaAdministrativeReset']>[0]) => {
      if (machine.phase !== 'ready' || pendingAction) {
        return null;
      }
      const operation = ++identityOperation.current;
      setActionIssue(null);
      setPendingAction('approve-mfa-administrative-reset');
      const result = await client.approveMfaAdministrativeReset(
        machine.selectedOrganization.id,
        targetUserId,
        approvalId,
        request,
        idempotencyKey,
      );
      if (operation !== identityOperation.current) {
        return null;
      }
      if (!result.ok) {
        handleAuthenticatedActionFailure(result);
        return null;
      }
      if (
        !validMfaResetMutation(result.data) ||
        result.data.status !== 'approved' ||
        result.data.targetUserId !== targetUserId ||
        result.data.approvalId !== approvalId
      ) {
        failOnInvalidActionResponse(
          result.correlationId,
          'The MFA-reset response did not confirm the requested independent approval.',
        );
        return null;
      }
      setPendingAction(null);
      return result.data;
    },
    [client, failOnInvalidActionResponse, handleAuthenticatedActionFailure, machine, pendingAction],
  );

  const executeMfaAdministrativeReset = useCallback(
    async ({
      approvalId,
      idempotencyKey,
      targetUserId,
      ...request
    }: Parameters<SessionContextValue['executeMfaAdministrativeReset']>[0]) => {
      if (machine.phase !== 'ready' || pendingAction) {
        return null;
      }
      const operation = ++identityOperation.current;
      setActionIssue(null);
      setPendingAction('execute-mfa-administrative-reset');
      const result = await client.executeMfaAdministrativeReset(
        machine.selectedOrganization.id,
        targetUserId,
        approvalId,
        request,
        idempotencyKey,
      );
      if (operation !== identityOperation.current) {
        return null;
      }
      if (!result.ok) {
        handleAuthenticatedActionFailure(result);
        return null;
      }
      if (
        !validMfaResetMutation(result.data) ||
        result.data.status !== 'reset' ||
        result.data.targetUserId !== targetUserId ||
        result.data.approvalId !== approvalId
      ) {
        failOnInvalidActionResponse(
          result.correlationId,
          'The MFA-reset response did not confirm the requested reset.',
        );
        return null;
      }
      setPendingAction(null);
      return result.data;
    },
    [client, failOnInvalidActionResponse, handleAuthenticatedActionFailure, machine, pendingAction],
  );

  const verifyRecentAuthentication = useCallback(
    async (credentials: { password: string; secondFactor?: string }) => {
      if (!isFullyAuthenticated(machine) || pendingAction) {
        return false;
      }
      const operation = ++identityOperation.current;
      setActionIssue(null);
      setPendingAction('verify-recent-authentication');
      const result = await client.verifyRecentAuthentication(credentials);
      if (operation !== identityOperation.current) {
        return false;
      }
      if (!result.ok) {
        handleAuthenticatedActionFailure(result);
        return false;
      }
      updateAuthenticationIndicators({ recentAuthentication: true });
      setPendingAction(null);
      return true;
    },
    [
      client,
      handleAuthenticatedActionFailure,
      machine,
      pendingAction,
      updateAuthenticationIndicators,
    ],
  );

  const startMfaEnrollment = useCallback(
    async (label?: string) => {
      if (
        (machine.phase !== 'mfa_enrollment_required' && !isFullyAuthenticated(machine)) ||
        (isFullyAuthenticated(machine) && (!machine.recentAuthentication || machine.mfaEnabled)) ||
        pendingAction
      ) {
        return null;
      }
      const operation = ++identityOperation.current;
      setActionIssue(null);
      setPendingAction('start-mfa-enrollment');
      const normalizedLabel = label?.trim();
      const result = await client.startMfaEnrollment(
        normalizedLabel ? { label: normalizedLabel } : {},
      );
      if (operation !== identityOperation.current) {
        return null;
      }
      if (!result.ok) {
        handleAuthenticatedActionFailure(result);
        return null;
      }
      if (!validMfaEnrollment(result.data)) {
        failOnInvalidActionResponse(
          result.correlationId,
          'The MFA-enrollment response did not contain valid one-time setup material.',
        );
        return null;
      }
      setPendingAction(null);
      return result.data;
    },
    [client, failOnInvalidActionResponse, handleAuthenticatedActionFailure, machine, pendingAction],
  );

  const verifyMfaEnrollment = useCallback(
    async (code: string) => {
      if (
        (machine.phase !== 'mfa_enrollment_required' && !isFullyAuthenticated(machine)) ||
        (isFullyAuthenticated(machine) && !machine.recentAuthentication) ||
        pendingAction
      ) {
        return null;
      }
      const operation = ++identityOperation.current;
      setActionIssue(null);
      setPendingAction('verify-mfa-enrollment');
      const result = await client.verifyMfaEnrollment({ code });
      if (operation !== identityOperation.current) {
        return null;
      }
      if (!result.ok) {
        handleAuthenticatedActionFailure(result);
        return null;
      }
      if (!validRecoveryCodes(result.data)) {
        failOnInvalidActionResponse(
          result.correlationId,
          'The MFA-verification response did not contain valid unique recovery codes.',
        );
        return null;
      }
      updateAuthenticationIndicators({ mfaEnabled: true });
      setPendingAction(null);
      return result.data.recoveryCodes;
    },
    [
      client,
      failOnInvalidActionResponse,
      handleAuthenticatedActionFailure,
      machine,
      pendingAction,
      updateAuthenticationIndicators,
    ],
  );

  const regenerateRecoveryCodes = useCallback(async () => {
    if (
      !isFullyAuthenticated(machine) ||
      !machine.recentAuthentication ||
      !machine.mfaEnabled ||
      pendingAction
    ) {
      return null;
    }
    const operation = ++identityOperation.current;
    setActionIssue(null);
    setPendingAction('regenerate-recovery-codes');
    const result = await client.regenerateRecoveryCodes();
    if (operation !== identityOperation.current) {
      return null;
    }
    if (!result.ok) {
      handleAuthenticatedActionFailure(result);
      return null;
    }
    if (!validRecoveryCodes(result.data)) {
      failOnInvalidActionResponse(
        result.correlationId,
        'The recovery-code response did not contain valid unique one-time codes.',
      );
      return null;
    }
    setPendingAction(null);
    return result.data.recoveryCodes;
  }, [
    client,
    failOnInvalidActionResponse,
    handleAuthenticatedActionFailure,
    machine,
    pendingAction,
  ]);

  const selectOrganization = useCallback(
    async (organizationId: string) => {
      if (
        (machine.phase !== 'selecting_organization' && machine.phase !== 'ready') ||
        pendingAction
      ) {
        return;
      }
      const previous = machine;
      if (!previous.organizations.some((organization) => organization.id === organizationId)) {
        setActionIssue({
          code: 'organization_not_selectable',
          correlationId: 'unavailable',
          detail: 'Choose an organization returned for this authenticated account.',
          title: 'Organization unavailable',
        });
        return;
      }

      const operation = ++generation.current;
      setActionIssue(null);
      setPendingAction('select-organization');
      const result = await client.selectOrganization({ organizationId });
      if (operation !== generation.current) {
        return;
      }
      if (!result.ok) {
        if (result.status === 401) {
          lockEndedSession();
        } else {
          setActionIssue(issueFromFailure(result));
        }
        setPendingAction(null);
        return;
      }
      if (
        !isOrganization(result.data) ||
        !result.data.selected ||
        result.data.id !== organizationId
      ) {
        setActionIssue(
          contractIssue(
            result.correlationId,
            'The organization-selection response did not confirm the requested organization.',
          ),
        );
        setPendingAction(null);
        return;
      }

      const organizations = previous.organizations.map((organization) =>
        organization.id === organizationId ? result.data : { ...organization, selected: false },
      );
      setMachine({
        mfaEnabled: previous.mfaEnabled,
        organizations,
        phase: 'ready',
        recentAuthentication: previous.recentAuthentication,
        selectedOrganization: result.data,
        user: previous.user,
      });
      setPendingAction(null);
    },
    [client, lockEndedSession, machine, pendingAction],
  );

  const logout = useCallback(async () => {
    if (machine.phase === 'anonymous' || machine.phase === 'loading' || pendingAction) {
      return;
    }
    const operation = ++generation.current;
    setActionIssue(null);
    setPendingAction('logout');
    const result = await client.logout();
    if (operation !== generation.current) {
      return;
    }
    if (result.ok || result.status === 401) {
      clearSessionDeadline();
      setMachine({ phase: 'anonymous' });
      setPendingAction(null);
      return;
    }
    setActionIssue(issueFromFailure(result));
    setPendingAction(null);
  }, [clearSessionDeadline, client, machine.phase, pendingAction]);

  const dismissActionIssue = useCallback(() => setActionIssue(null), []);

  const value = useMemo<SessionContextValue>(
    () => ({
      acceptInvitation,
      actionIssue,
      approveMfaAdministrativeReset,
      completeMfa,
      completePasswordReset,
      dismissActionIssue,
      executeMfaAdministrativeReset,
      login,
      logout,
      issueInvitation,
      machine,
      pendingAction,
      regenerateRecoveryCodes,
      requestPasswordReset,
      requestMfaAdministrativeReset,
      revokeInvitation,
      retryBootstrap: bootstrap,
      selectOrganization,
      startMfaEnrollment,
      verifyMfaEnrollment,
      verifyRecentAuthentication,
    }),
    [
      acceptInvitation,
      actionIssue,
      approveMfaAdministrativeReset,
      bootstrap,
      completeMfa,
      completePasswordReset,
      dismissActionIssue,
      executeMfaAdministrativeReset,
      login,
      logout,
      issueInvitation,
      machine,
      pendingAction,
      regenerateRecoveryCodes,
      requestPasswordReset,
      requestMfaAdministrativeReset,
      revokeInvitation,
      selectOrganization,
      startMfaEnrollment,
      verifyMfaEnrollment,
      verifyRecentAuthentication,
    ],
  );

  return <SessionContext value={value}>{children}</SessionContext>;
}
