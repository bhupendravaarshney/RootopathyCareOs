import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import type { SessionClient } from './features/session/session-types';
import { SessionProvider } from './features/session/SessionProvider';
import { useSession } from './features/session/session-context';
import {
  InvitationAcceptanceScreen,
  InvitationAdministrationScreen,
  MfaAdministrationScreen,
  PasswordResetCompletionScreen,
  PasswordResetRequestScreen,
} from './features/session/IdentitySecurityScreens';
import {
  LoginScreen,
  MfaChallengeScreen,
  NoOrganizationScreen,
  OrganizationSelectionScreen,
  SessionFailureScreen,
  SessionLoadingScreen,
} from './features/session/SessionScreens';
import { SessionIssueAlert } from './features/session/SessionIssueAlert';
import { PrototypeScreenPage } from './pages/PrototypeScreenPage';

type HashRoute = { id: string; invitationToken?: string; resetToken?: string };

function readHashRoute(hash = window.location.hash): HashRoute {
  const value = hash.replace(/^#\/?/, '');
  const queryStart = value.indexOf('?');
  const path = queryStart >= 0 ? value.slice(0, queryStart) : value;
  const id = path.toUpperCase() || 'M1-05';
  if (queryStart < 0 || (id !== 'RESET-PASSWORD' && id !== 'ACCEPT-INVITATION')) {
    return { id };
  }
  const token = new URLSearchParams(value.slice(queryStart + 1)).get('token');
  if (!token) {
    return { id };
  }
  return id === 'RESET-PASSWORD' ? { id, resetToken: token } : { id, invitationToken: token };
}

const identityRoutes = new Set(['M1-01', 'M1-02', 'M1-03', 'M1-04']);

function RoutedApp() {
  const [route, setRoute] = useState<HashRoute>(readHashRoute);
  const invitationTokenRetired = useRef(false);
  const resetTokenRetired = useRef(false);
  const { id, invitationToken, resetToken } = route;
  const {
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
    retryBootstrap,
    selectOrganization,
    startMfaEnrollment,
    verifyMfaEnrollment,
    verifyRecentAuthentication,
  } = useSession();

  useEffect(() => {
    const onHashChange = (event: HashChangeEvent) => {
      const eventRoute = readHashRoute(new URL(event.newURL).hash);
      const currentRoute = readHashRoute();
      const tokenWasJustScrubbed =
        (!resetTokenRetired.current &&
          eventRoute.id === 'RESET-PASSWORD' &&
          Boolean(eventRoute.resetToken) &&
          currentRoute.id === 'RESET-PASSWORD' &&
          !currentRoute.resetToken) ||
        (!invitationTokenRetired.current &&
          eventRoute.id === 'ACCEPT-INVITATION' &&
          Boolean(eventRoute.invitationToken) &&
          currentRoute.id === 'ACCEPT-INVITATION' &&
          !currentRoute.invitationToken);
      if (currentRoute.resetToken) {
        resetTokenRetired.current = false;
      }
      if (currentRoute.invitationToken) {
        invitationTokenRetired.current = false;
      }
      setRoute(tokenWasJustScrubbed ? eventRoute : currentRoute);
    };
    window.addEventListener('hashchange', onHashChange);
    return () => window.removeEventListener('hashchange', onHashChange);
  }, []);
  useLayoutEffect(() => {
    if (id === 'RESET-PASSWORD' && resetToken) {
      const scrubbedUrl = `${window.location.pathname}${window.location.search}#/reset-password`;
      window.history.replaceState(window.history.state, '', scrubbedUrl);
    }
  }, [id, resetToken]);
  useLayoutEffect(() => {
    if (id === 'ACCEPT-INVITATION' && invitationToken) {
      const scrubbedUrl = `${window.location.pathname}${window.location.search}#/accept-invitation`;
      window.history.replaceState(window.history.state, '', scrubbedUrl);
    }
  }, [id, invitationToken]);
  useEffect(() => {
    dismissActionIssue();
  }, [dismissActionIssue, id]);
  useEffect(() => {
    if (machine.phase === 'ready' && id !== 'M1-02' && id !== 'M1-03' && identityRoutes.has(id)) {
      window.location.hash = '#/M1-05';
    }
  }, [id, machine.phase]);

  if (id === 'FORGOT-PASSWORD') {
    return (
      <PasswordResetRequestScreen
        busy={pendingAction === 'request-password-reset'}
        issue={actionIssue}
        onRequest={requestPasswordReset}
      />
    );
  }
  if (id === 'RESET-PASSWORD') {
    return (
      <PasswordResetCompletionScreen
        busy={pendingAction === 'complete-password-reset'}
        issue={actionIssue}
        onComplete={completePasswordReset}
        onForgetToken={() => {
          resetTokenRetired.current = true;
          setRoute((current) => (current.id === 'RESET-PASSWORD' ? { id: current.id } : current));
        }}
        token={resetToken}
      />
    );
  }
  if (id === 'ACCEPT-INVITATION') {
    const authenticatedEmail =
      machine.phase === 'ready' ||
      machine.phase === 'selecting_organization' ||
      machine.phase === 'no_organization'
        ? machine.user.email
        : undefined;
    return (
      <InvitationAcceptanceScreen
        authenticatedEmail={authenticatedEmail}
        busy={pendingAction === 'accept-invitation'}
        issue={actionIssue}
        onAccept={acceptInvitation}
        onForgetToken={() => {
          invitationTokenRetired.current = true;
          setRoute((current) =>
            current.id === 'ACCEPT-INVITATION' ? { id: current.id } : current,
          );
        }}
        token={invitationToken}
      />
    );
  }
  if (machine.phase === 'loading') {
    return <SessionLoadingScreen reason={machine.reason} />;
  }
  if (machine.phase === 'failure') {
    return <SessionFailureScreen issue={machine.issue} onRetry={retryBootstrap} />;
  }
  if (machine.phase === 'anonymous') {
    return <LoginScreen busy={pendingAction === 'login'} issue={actionIssue} onLogin={login} />;
  }
  if (machine.phase === 'mfa_required') {
    return (
      <MfaChallengeScreen
        busy={pendingAction !== null}
        issue={actionIssue}
        onComplete={completeMfa}
        onLogout={logout}
        user={machine.user}
      />
    );
  }
  if (id === 'M1-03') {
    return (
      <MfaAdministrationScreen
        issue={actionIssue}
        mfaEnabled={machine.mfaEnabled}
        onApproveAdministrativeReset={approveMfaAdministrativeReset}
        onExecuteAdministrativeReset={executeMfaAdministrativeReset}
        onLogout={logout}
        onRegenerateRecoveryCodes={regenerateRecoveryCodes}
        onRequestAdministrativeReset={requestMfaAdministrativeReset}
        onStartEnrollment={startMfaEnrollment}
        onVerifyEnrollment={verifyMfaEnrollment}
        onVerifyRecentAuthentication={verifyRecentAuthentication}
        pendingAction={pendingAction}
        recentAuthentication={machine.recentAuthentication}
        selectedOrganization={machine.phase === 'ready' ? machine.selectedOrganization : undefined}
        user={machine.user}
      />
    );
  }
  if (machine.phase === 'selecting_organization') {
    return (
      <OrganizationSelectionScreen
        busy={pendingAction !== null}
        issue={actionIssue}
        onLogout={logout}
        onSelect={selectOrganization}
        organizations={machine.organizations}
        user={machine.user}
      />
    );
  }
  if (machine.phase === 'no_organization') {
    return (
      <NoOrganizationScreen
        busy={pendingAction === 'logout'}
        issue={actionIssue}
        onLogout={logout}
        user={machine.user}
      />
    );
  }
  if (id === 'M1-02') {
    return (
      <InvitationAdministrationScreen
        busyAction={pendingAction}
        issue={actionIssue}
        onIssue={issueInvitation}
        onRevoke={revokeInvitation}
        organization={machine.selectedOrganization}
        recentAuthentication={machine.recentAuthentication}
      />
    );
  }

  const screenId = identityRoutes.has(id) ? 'M1-05' : id;
  const sessionNotice = actionIssue ? (
    <SessionIssueAlert issue={actionIssue} onDismiss={dismissActionIssue} />
  ) : undefined;
  return (
    <PrototypeScreenPage
      id={screenId}
      shell={{
        actorDisplayName: machine.user.displayName,
        organizations: machine.organizations.map((organization) => ({
          id: organization.id,
          label: organization.displayName,
        })),
        onLogout: logout,
        onSelectOrganization: selectOrganization,
        selectedOrganizationId: machine.selectedOrganization.id,
        sessionBusy: pendingAction !== null,
        sessionNotice,
        signingOut: pendingAction === 'logout',
      }}
    />
  );
}

export default function App({ client }: { client?: SessionClient }) {
  return (
    <SessionProvider client={client}>
      <RoutedApp />
    </SessionProvider>
  );
}
