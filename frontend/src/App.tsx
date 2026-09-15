import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import type { SessionClient } from './features/session/session-types';
import { SessionProvider } from './features/session/SessionProvider';
import { useSession } from './features/session/session-context';
import {
  MfaAdministrationScreen,
  PasswordResetCompletionScreen,
  PasswordResetRequestScreen,
} from './features/session/IdentitySecurityScreens';
import {
  InvitationUnavailableScreen,
  LoginScreen,
  MfaChallengeScreen,
  NoOrganizationScreen,
  OrganizationSelectionScreen,
  SessionFailureScreen,
  SessionLoadingScreen,
} from './features/session/SessionScreens';
import { SessionIssueAlert } from './features/session/SessionIssueAlert';
import { PrototypeScreenPage } from './pages/PrototypeScreenPage';

type HashRoute = { id: string; resetToken?: string };

function readHashRoute(hash = window.location.hash): HashRoute {
  const value = hash.replace(/^#\/?/, '');
  const queryStart = value.indexOf('?');
  const path = queryStart >= 0 ? value.slice(0, queryStart) : value;
  const id = path.toUpperCase() || 'M1-05';
  if (id !== 'RESET-PASSWORD' || queryStart < 0) {
    return { id };
  }
  const token = new URLSearchParams(value.slice(queryStart + 1)).get('token');
  return token ? { id, resetToken: token } : { id };
}

const identityRoutes = new Set(['M1-01', 'M1-02', 'M1-03', 'M1-04']);

function RoutedApp() {
  const [route, setRoute] = useState<HashRoute>(readHashRoute);
  const resetTokenRetired = useRef(false);
  const { id, resetToken } = route;
  const {
    actionIssue,
    completeMfa,
    completePasswordReset,
    dismissActionIssue,
    login,
    logout,
    machine,
    pendingAction,
    regenerateRecoveryCodes,
    requestPasswordReset,
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
        !resetTokenRetired.current &&
        eventRoute.id === 'RESET-PASSWORD' &&
        Boolean(eventRoute.resetToken) &&
        currentRoute.id === 'RESET-PASSWORD' &&
        !currentRoute.resetToken;
      if (currentRoute.resetToken) {
        resetTokenRetired.current = false;
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
  if (id === 'M1-02') {
    return <InvitationUnavailableScreen authenticated={machine.phase === 'ready'} />;
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
        onLogout={logout}
        onRegenerateRecoveryCodes={regenerateRecoveryCodes}
        onStartEnrollment={startMfaEnrollment}
        onVerifyEnrollment={verifyMfaEnrollment}
        onVerifyRecentAuthentication={verifyRecentAuthentication}
        pendingAction={pendingAction}
        recentAuthentication={machine.recentAuthentication}
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
