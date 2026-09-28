import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { careOsApi } from './api/client';
import { screens } from './data/screens';
import { AdministrationScreen } from './features/administration/AdministrationScreens';
import type { AdministrationClient } from './features/administration/administration-types';
import type { SessionClient } from './features/session/session-types';
import { SessionProvider } from './features/session/SessionProvider';
import { useSession } from './features/session/session-context';
import {
  InvitationAcceptanceScreen,
  InvitationAdministrationScreen,
  MfaAdministrationScreen,
  MfaEnrollmentRequiredScreen,
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
import { PatientRegistryScreenPage } from './features/patient/PatientRegistryScreens';
import type { PatientRegistryClient } from './features/patient/patient-types';
import { SchedulingScreenPage } from './features/scheduling/SchedulingScreens';
import type { SchedulingClient } from './features/scheduling/scheduling-types';
import { EncounterScreenPage } from './features/encounter/EncounterScreens';
import type { EncounterClient } from './features/encounter/encounter-types';
import { AssessmentScreenPage } from './features/assessment/AssessmentScreens';
import type { AssessmentClient } from './features/assessment/assessment-types';
import { DocumentScreenPage } from './features/document/DocumentScreens';
import type { DocumentClient } from './features/document/document-types';
import { AiScreenPage } from './features/ai/AiScreens';
import type { AiClient } from './features/ai/ai-types';
import type { CarePlanClient } from './features/careplan/care-plan-types';
import type { FollowupClient } from './features/followup/followup-types';
import type { BillingClient } from './features/billing/billing-types';
import type { ReportingClient } from './features/reporting/reporting-types';
import type { IntegrationClient } from './features/integration/integration-types';
import { WorkforceScreenPage } from './features/workforce/WorkforceScreens';
import type { WorkforceClient } from './features/workforce/workforce-types';
import { PrototypeScreenPage } from './pages/PrototypeScreenPage';
import { RouteNotFoundPage } from './pages/RouteNotFoundPage';

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
const registeredScreenIds = new Set(screens.map((screen) => screen.id));
type ApplicationClient = SessionClient &
  AdministrationClient &
  AiClient &
  Partial<CarePlanClient> &
  Partial<FollowupClient> &
  Partial<BillingClient> &
  Partial<ReportingClient> &
  Partial<IntegrationClient> &
  WorkforceClient &
  PatientRegistryClient &
  SchedulingClient &
  EncounterClient &
  AssessmentClient &
  DocumentClient;

function RoutedApp({
  administrationClient,
  aiClient,
  assessmentClient,
  billingClient,
  reportingClient,
  integrationClient,
  carePlanClient,
  documentClient,
  encounterClient,
  followupClient,
  patientClient,
  schedulingClient,
  workforceClient,
}: {
  administrationClient: AdministrationClient;
  aiClient: AiClient;
  assessmentClient: AssessmentClient;
  billingClient: BillingClient;
  reportingClient: ReportingClient;
  integrationClient: IntegrationClient;
  carePlanClient: CarePlanClient;
  documentClient: DocumentClient;
  encounterClient: EncounterClient;
  followupClient: FollowupClient;
  patientClient: PatientRegistryClient;
  schedulingClient: SchedulingClient;
  workforceClient: WorkforceClient;
}) {
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
  if (machine.phase === 'mfa_enrollment_required') {
    return (
      <MfaEnrollmentRequiredScreen
        issue={actionIssue}
        onContinue={retryBootstrap}
        onLogout={logout}
        onStartEnrollment={startMfaEnrollment}
        onVerifyEnrollment={verifyMfaEnrollment}
        pendingAction={pendingAction}
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
  const shell = {
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
  };
  if (!registeredScreenIds.has(screenId)) {
    return <RouteNotFoundPage shell={shell} />;
  }
  if (screenId.startsWith('M2-')) {
    return (
      <WorkforceScreenPage
        key={`${machine.selectedOrganization.id}:${screenId}`}
        client={workforceClient}
        id={screenId}
        organizationId={machine.selectedOrganization.id}
        shell={shell}
      />
    );
  }
  if (screenId.startsWith('P3-')) {
    return (
      <PatientRegistryScreenPage
        key={`${machine.selectedOrganization.id}:${screenId}`}
        client={patientClient}
        id={screenId}
        organizationId={machine.selectedOrganization.id}
        shell={shell}
      />
    );
  }
  if (screenId.startsWith('P4-')) {
    return (
      <SchedulingScreenPage
        key={`${machine.selectedOrganization.id}:${screenId}`}
        client={schedulingClient}
        id={screenId}
        organizationId={machine.selectedOrganization.id}
        shell={shell}
      />
    );
  }
  if (screenId.startsWith('P5-')) {
    return (
      <EncounterScreenPage
        key={`${machine.selectedOrganization.id}:${screenId}`}
        client={encounterClient}
        id={screenId}
        organizationId={machine.selectedOrganization.id}
        shell={shell}
      />
    );
  }
  if (screenId.startsWith('COS-')) {
    return (
      <AssessmentScreenPage
        key={`${machine.selectedOrganization.id}:${screenId}`}
        client={assessmentClient}
        id={screenId}
        organizationId={machine.selectedOrganization.id}
        shell={shell}
      />
    );
  }
  if (screenId.startsWith('P7-')) {
    return (
      <DocumentScreenPage
        key={`${machine.selectedOrganization.id}:${screenId}`}
        client={documentClient}
        id={screenId}
        organizationId={machine.selectedOrganization.id}
        shell={shell}
      />
    );
  }
  if (screenId.startsWith('P8-')) {
    return (
      <AiScreenPage
        key={`${machine.selectedOrganization.id}:${screenId}`}
        client={aiClient}
        id={screenId}
        organizationId={machine.selectedOrganization.id}
        shell={shell}
      />
    );
  }
  if (screenId.startsWith('P9-')) {
    return (
      <AiScreenPage
        key={`${machine.selectedOrganization.id}:${screenId}`}
        client={carePlanClient}
        id={screenId}
        mode="care-plan"
        organizationId={machine.selectedOrganization.id}
        shell={shell}
      />
    );
  }
  if (screenId.startsWith('P10-')) {
    return (
      <AiScreenPage
        key={`${machine.selectedOrganization.id}:${screenId}`}
        client={followupClient}
        id={screenId}
        mode="followup"
        organizationId={machine.selectedOrganization.id}
        shell={shell}
      />
    );
  }
  if (screenId.startsWith('P11-')) {
    return (
      <AiScreenPage
        key={`${machine.selectedOrganization.id}:${screenId}`}
        client={billingClient}
        id={screenId}
        mode="billing"
        organizationId={machine.selectedOrganization.id}
        shell={shell}
      />
    );
  }
  if (screenId.startsWith('P12-')) {
    return (
      <AiScreenPage
        key={`${machine.selectedOrganization.id}:${screenId}`}
        client={reportingClient}
        id={screenId}
        mode="reporting"
        organizationId={machine.selectedOrganization.id}
        shell={shell}
      />
    );
  }
  if (screenId.startsWith('P13-')) {
    return (
      <AiScreenPage
        key={`${machine.selectedOrganization.id}:${screenId}`}
        client={integrationClient}
        id={screenId}
        mode="integration"
        organizationId={machine.selectedOrganization.id}
        shell={shell}
      />
    );
  }
  if (
    screenId === 'M1-05' ||
    screenId === 'M1-06' ||
    screenId === 'M1-07' ||
    screenId === 'M1-08' ||
    screenId === 'M1-09' ||
    screenId === 'M1-10' ||
    screenId === 'M1-11' ||
    screenId === 'M1-12' ||
    screenId === 'M1-13' ||
    screenId === 'M1-14' ||
    screenId === 'M1-15' ||
    screenId === 'M1-16' ||
    screenId === 'M1-17' ||
    screenId === 'M1-18' ||
    screenId === 'M1-19' ||
    screenId === 'M1-21' ||
    screenId === 'M1-22' ||
    screenId === 'M1-23' ||
    screenId === 'M1-20'
  ) {
    return (
      <AdministrationScreen
        key={machine.selectedOrganization.id}
        client={administrationClient}
        id={screenId}
        organizationId={machine.selectedOrganization.id}
        shell={shell}
      />
    );
  }
  return <PrototypeScreenPage id={screenId} shell={shell} />;
}

export default function App({ client }: { client?: ApplicationClient }) {
  const resolvedClient = client ?? careOsApi;
  const resolvedCarePlanClient: CarePlanClient =
    resolvedClient.getCarePlanScreen && resolvedClient.performCarePlanAction
      ? (resolvedClient as CarePlanClient)
      : careOsApi;
  const resolvedFollowupClient: FollowupClient =
    resolvedClient.getFollowupScreen && resolvedClient.performFollowupAction
      ? (resolvedClient as FollowupClient)
      : careOsApi;
  const resolvedBillingClient: BillingClient =
    resolvedClient.getBillingScreen && resolvedClient.performBillingAction
      ? (resolvedClient as BillingClient)
      : careOsApi;
  const resolvedReportingClient: ReportingClient =
    resolvedClient.getReportingScreen && resolvedClient.performReportingAction
      ? (resolvedClient as ReportingClient)
      : careOsApi;
  const resolvedIntegrationClient: IntegrationClient =
    resolvedClient.getIntegrationScreen && resolvedClient.performIntegrationAction
      ? (resolvedClient as IntegrationClient)
      : careOsApi;
  return (
    <SessionProvider client={resolvedClient}>
      <RoutedApp
        administrationClient={resolvedClient}
        aiClient={resolvedClient}
        assessmentClient={resolvedClient}
        billingClient={resolvedBillingClient}
        reportingClient={resolvedReportingClient}
        integrationClient={resolvedIntegrationClient}
        carePlanClient={resolvedCarePlanClient}
        documentClient={resolvedClient}
        encounterClient={resolvedClient}
        followupClient={resolvedFollowupClient}
        patientClient={resolvedClient}
        schedulingClient={resolvedClient}
        workforceClient={resolvedClient}
      />
    </SessionProvider>
  );
}
