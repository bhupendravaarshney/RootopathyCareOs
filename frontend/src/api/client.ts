import type {
  AcceptInvitationData,
  AcceptInvitationResponse,
  AcceptInvitationResponses,
  ApproveMfaAdministrativeResetData,
  ApproveMfaAdministrativeResetResponse,
  ApproveMfaAdministrativeResetResponses,
  ApproveOrganizationMembershipChangeData,
  ApproveOrganizationMembershipChangeResponse,
  ApproveOrganizationMembershipChangeResponses,
  ApproveOrganizationOwnerTransferData,
  ApproveOrganizationOwnerTransferResponse,
  ApproveOrganizationOwnerTransferResponses,
  CompleteMfaChallengeData,
  CompleteMfaChallengeResponse,
  CompleteMfaChallengeResponses,
  CompletePasswordResetData,
  CompletePasswordResetResponse,
  CompletePasswordResetResponses,
  CsrfToken,
  ExecuteMfaAdministrativeResetData,
  ExecuteMfaAdministrativeResetResponse,
  ExecuteMfaAdministrativeResetResponses,
  ExecuteOrganizationMembershipChangeData,
  ExecuteOrganizationMembershipChangeResponse,
  ExecuteOrganizationMembershipChangeResponses,
  ExecuteOrganizationOwnerTransferData,
  ExecuteOrganizationOwnerTransferResponse,
  ExecuteOrganizationOwnerTransferResponses,
  GetAdministrationReadinessData,
  GetAdministrationReadinessResponse,
  GetAdministrationReadinessResponses,
  GetAuthenticationSessionData,
  GetAuthenticationSessionResponse,
  GetAuthenticationSessionResponses,
  GetOrganizationProfileData,
  GetOrganizationProfileResponse,
  GetOrganizationProfileResponses,
  GetSystemSummaryData,
  GetSystemSummaryResponse,
  GetSystemSummaryResponses,
  IssueCsrfTokenData,
  IssueCsrfTokenResponse,
  IssueCsrfTokenResponses,
  IssueInvitationData,
  IssueInvitationResponse,
  IssueInvitationResponses,
  ListPrototypeScreensData,
  ListPrototypeScreensResponse,
  ListPrototypeScreensResponses,
  ListOrganizationMembershipsData,
  ListOrganizationMembershipsResponse,
  ListOrganizationMembershipsResponses,
  ListSelectableOrganizationsData,
  ListSelectableOrganizationsResponse,
  ListSelectableOrganizationsResponses,
  LoginData,
  LoginResponse,
  LoginResponses,
  LogoutData,
  LogoutResponse,
  LogoutResponses,
  Problem,
  RegenerateRecoveryCodesData,
  RegenerateRecoveryCodesResponse,
  RegenerateRecoveryCodesResponses,
  RequestPasswordResetData,
  RequestPasswordResetResponses,
  RequestMfaAdministrativeResetData,
  RequestMfaAdministrativeResetResponse,
  RequestMfaAdministrativeResetResponses,
  RequestOrganizationMembershipChangeData,
  RequestOrganizationMembershipChangeResponse,
  RequestOrganizationMembershipChangeResponses,
  RequestOrganizationOwnerTransferData,
  RequestOrganizationOwnerTransferResponse,
  RequestOrganizationOwnerTransferResponses,
  RevokeInvitationData,
  RevokeInvitationResponse,
  RevokeInvitationResponses,
  SelectOrganizationData,
  SelectOrganizationResponse,
  SelectOrganizationResponses,
  StartMfaEnrollmentData,
  StartMfaEnrollmentResponse,
  StartMfaEnrollmentResponses,
  UpdateOrganizationProfileData,
  UpdateOrganizationProfileResponse,
  UpdateOrganizationProfileResponses,
  VerifyMfaEnrollmentData,
  VerifyMfaEnrollmentResponse,
  VerifyMfaEnrollmentResponses,
  VerifyRecentAuthenticationData,
  VerifyRecentAuthenticationResponse,
  VerifyRecentAuthenticationResponses,
} from './generated';

const ACCEPTED_RESPONSE_TYPES = 'application/json, application/problem+json';
const CORRELATION_ID_PATTERN = /^[A-Za-z0-9._:-]{1,128}$/;
const IDEMPOTENCY_KEY_PATTERN = /^[A-Za-z0-9._:-]{16,128}$/;
const STRONG_ETAG_PATTERN = /^"[A-Za-z0-9._:-]{1,128}"$/;
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const MEMBERSHIP_CURSOR_PATTERN = /^[A-Za-z0-9_-]{1,512}$/;
const MEMBERSHIP_ROLE_KEY_PATTERN = /^[a-z][a-z0-9]*([._:-][a-z0-9]+)*$/;
const MEMBERSHIP_ACCESS_STATES = new Set([
  'active',
  'scheduled',
  'suspended',
  'expired',
  'revoked',
]);
const MAX_RETRY_AFTER_SECONDS = 86_400;
const SESSION_EXPIRY_HEADER = 'X-CareOS-Session-Expires-In';
const MAX_SESSION_EXPIRY_SECONDS = 2_147_483_647;

type ResponseStatus<T> = Extract<keyof T, number>;

const endpoints = {
  acceptInvitation: {
    path: '/api/v1/auth/invitation-acceptances' satisfies AcceptInvitationData['url'],
    successStatuses: [200, 201] satisfies readonly ResponseStatus<AcceptInvitationResponses>[],
  },
  approveMfaAdministrativeReset: {
    path: '/api/v1/organizations/{organizationId}/users/{targetUserId}/mfa-reset-requests/{approvalId}/approvals' satisfies ApproveMfaAdministrativeResetData['url'],
    successStatuses: [
      200,
    ] satisfies readonly ResponseStatus<ApproveMfaAdministrativeResetResponses>[],
  },
  approveOrganizationMembershipChange: {
    path: '/api/v1/organizations/{organizationId}/memberships/{membershipId}/change-requests/{approvalId}/approvals' satisfies ApproveOrganizationMembershipChangeData['url'],
    successStatuses: [
      200,
    ] satisfies readonly ResponseStatus<ApproveOrganizationMembershipChangeResponses>[],
  },
  approveOrganizationOwnerTransfer: {
    path: '/api/v1/organizations/{organizationId}/memberships/{membershipId}/owner-transfer-requests/{approvalId}/approvals' satisfies ApproveOrganizationOwnerTransferData['url'],
    successStatuses: [
      200,
    ] satisfies readonly ResponseStatus<ApproveOrganizationOwnerTransferResponses>[],
  },
  completeMfaChallenge: {
    path: '/api/v1/auth/mfa/challenges' satisfies CompleteMfaChallengeData['url'],
    successStatuses: [200] satisfies readonly ResponseStatus<CompleteMfaChallengeResponses>[],
  },
  completePasswordReset: {
    path: '/api/v1/auth/password-resets' satisfies CompletePasswordResetData['url'],
    successStatuses: [204] satisfies readonly ResponseStatus<CompletePasswordResetResponses>[],
  },
  executeMfaAdministrativeReset: {
    path: '/api/v1/organizations/{organizationId}/users/{targetUserId}/mfa-reset-requests/{approvalId}/executions' satisfies ExecuteMfaAdministrativeResetData['url'],
    successStatuses: [
      200,
    ] satisfies readonly ResponseStatus<ExecuteMfaAdministrativeResetResponses>[],
  },
  executeOrganizationMembershipChange: {
    path: '/api/v1/organizations/{organizationId}/memberships/{membershipId}/change-requests/{approvalId}/executions' satisfies ExecuteOrganizationMembershipChangeData['url'],
    successStatuses: [
      200,
    ] satisfies readonly ResponseStatus<ExecuteOrganizationMembershipChangeResponses>[],
  },
  executeOrganizationOwnerTransfer: {
    path: '/api/v1/organizations/{organizationId}/memberships/{membershipId}/owner-transfer-requests/{approvalId}/executions' satisfies ExecuteOrganizationOwnerTransferData['url'],
    successStatuses: [
      200,
    ] satisfies readonly ResponseStatus<ExecuteOrganizationOwnerTransferResponses>[],
  },
  getAdministrationReadiness: {
    path: '/api/v1/organizations/{organizationId}/setup-readiness' satisfies GetAdministrationReadinessData['url'],
    successStatuses: [200] satisfies readonly ResponseStatus<GetAdministrationReadinessResponses>[],
  },
  getAuthenticationSession: {
    path: '/api/v1/auth/session' satisfies GetAuthenticationSessionData['url'],
    successStatuses: [200] satisfies readonly ResponseStatus<GetAuthenticationSessionResponses>[],
  },
  getOrganizationProfile: {
    path: '/api/v1/organizations/{organizationId}/profile' satisfies GetOrganizationProfileData['url'],
    successStatuses: [200] satisfies readonly ResponseStatus<GetOrganizationProfileResponses>[],
  },
  getSystemSummary: {
    path: '/api/public/system-summary' satisfies GetSystemSummaryData['url'],
    successStatuses: [200] satisfies readonly ResponseStatus<GetSystemSummaryResponses>[],
  },
  issueCsrfToken: {
    path: '/api/v1/auth/csrf' satisfies IssueCsrfTokenData['url'],
    successStatuses: [200] satisfies readonly ResponseStatus<IssueCsrfTokenResponses>[],
  },
  issueInvitation: {
    path: '/api/v1/organizations/{organizationId}/invitations' satisfies IssueInvitationData['url'],
    successStatuses: [201] satisfies readonly ResponseStatus<IssueInvitationResponses>[],
  },
  listPrototypeScreens: {
    path: '/api/public/prototype-screens' satisfies ListPrototypeScreensData['url'],
    successStatuses: [200] satisfies readonly ResponseStatus<ListPrototypeScreensResponses>[],
  },
  listOrganizationMemberships: {
    path: '/api/v1/organizations/{organizationId}/memberships' satisfies ListOrganizationMembershipsData['url'],
    successStatuses: [
      200,
    ] satisfies readonly ResponseStatus<ListOrganizationMembershipsResponses>[],
  },
  listSelectableOrganizations: {
    path: '/api/v1/organizations' satisfies ListSelectableOrganizationsData['url'],
    successStatuses: [
      200,
    ] satisfies readonly ResponseStatus<ListSelectableOrganizationsResponses>[],
  },
  login: {
    path: '/api/v1/auth/login' satisfies LoginData['url'],
    successStatuses: [200, 202] satisfies readonly ResponseStatus<LoginResponses>[],
  },
  logout: {
    path: '/api/v1/auth/logout' satisfies LogoutData['url'],
    successStatuses: [204] satisfies readonly ResponseStatus<LogoutResponses>[],
  },
  regenerateRecoveryCodes: {
    path: '/api/v1/auth/mfa/recovery-codes' satisfies RegenerateRecoveryCodesData['url'],
    successStatuses: [200] satisfies readonly ResponseStatus<RegenerateRecoveryCodesResponses>[],
  },
  requestPasswordReset: {
    path: '/api/v1/auth/password-reset-requests' satisfies RequestPasswordResetData['url'],
    successStatuses: [202] satisfies readonly ResponseStatus<RequestPasswordResetResponses>[],
  },
  requestMfaAdministrativeReset: {
    path: '/api/v1/organizations/{organizationId}/users/{targetUserId}/mfa-reset-requests' satisfies RequestMfaAdministrativeResetData['url'],
    successStatuses: [
      201,
    ] satisfies readonly ResponseStatus<RequestMfaAdministrativeResetResponses>[],
  },
  requestOrganizationMembershipChange: {
    path: '/api/v1/organizations/{organizationId}/memberships/{membershipId}/change-requests' satisfies RequestOrganizationMembershipChangeData['url'],
    successStatuses: [
      201,
    ] satisfies readonly ResponseStatus<RequestOrganizationMembershipChangeResponses>[],
  },
  requestOrganizationOwnerTransfer: {
    path: '/api/v1/organizations/{organizationId}/memberships/{membershipId}/owner-transfer-requests' satisfies RequestOrganizationOwnerTransferData['url'],
    successStatuses: [
      201,
    ] satisfies readonly ResponseStatus<RequestOrganizationOwnerTransferResponses>[],
  },
  revokeInvitation: {
    path: '/api/v1/organizations/{organizationId}/invitations/{invitationId}/revocations' satisfies RevokeInvitationData['url'],
    successStatuses: [200] satisfies readonly ResponseStatus<RevokeInvitationResponses>[],
  },
  selectOrganization: {
    path: '/api/v1/auth/organization-selections' satisfies SelectOrganizationData['url'],
    successStatuses: [200] satisfies readonly ResponseStatus<SelectOrganizationResponses>[],
  },
  startMfaEnrollment: {
    path: '/api/v1/auth/mfa/enrollments' satisfies StartMfaEnrollmentData['url'],
    successStatuses: [200] satisfies readonly ResponseStatus<StartMfaEnrollmentResponses>[],
  },
  verifyMfaEnrollment: {
    path: '/api/v1/auth/mfa/enrollments/verification' satisfies VerifyMfaEnrollmentData['url'],
    successStatuses: [200] satisfies readonly ResponseStatus<VerifyMfaEnrollmentResponses>[],
  },
  verifyRecentAuthentication: {
    path: '/api/v1/auth/recent-authentications' satisfies VerifyRecentAuthenticationData['url'],
    successStatuses: [204] satisfies readonly ResponseStatus<VerifyRecentAuthenticationResponses>[],
  },
  updateOrganizationProfile: {
    path: '/api/v1/organizations/{organizationId}/profile' satisfies UpdateOrganizationProfileData['url'],
    successStatuses: [200] satisfies readonly ResponseStatus<UpdateOrganizationProfileResponses>[],
  },
} as const;

function relativeEndpointPath(path: `/api/${string}`): string {
  return path.slice('/api'.length);
}

type FetchImplementation = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>;

export type ApiFailureKind = 'aborted' | 'contract' | 'http' | 'network';

export type ApiSuccess<T> = {
  correlationId: string;
  data: T;
  etag?: string;
  ok: true;
  sessionExpiresAt?: number;
  status: number;
};

export type ApiFailure = {
  correlationId: string;
  kind: ApiFailureKind;
  ok: false;
  problem: Problem;
  responseStatus?: number;
  retryAfterSeconds?: number;
  status: number;
};

export type ApiResult<T> = ApiSuccess<T> | ApiFailure;

export type ApiRequestOptions = {
  signal?: AbortSignal;
};

export type SessionLifecycleEvent =
  { expiresAt: number; type: 'deadline' } | { type: 'invalidated' };

export type CareOsApiClientOptions = {
  baseUrl?: string;
  correlationIdFactory?: () => string;
  fetch?: FetchImplementation;
  now?: () => number;
};

type RequestDescriptor = {
  body?: unknown;
  idempotencyKey?: string;
  ifMatch?: string;
  method: 'GET' | 'POST' | 'PUT';
  path: string;
  responseBody: 'empty' | 'json';
  signal?: AbortSignal;
  successStatuses: readonly number[];
};

function requireUuid(value: string, name: string): string {
  if (!UUID_PATTERN.test(value)) {
    throw new Error(`${name} must be a UUID.`);
  }
  return value;
}

function requireIdempotencyKey(value: string): string {
  if (!IDEMPOTENCY_KEY_PATTERN.test(value)) {
    throw new Error(
      'The idempotency key must contain 16 to 128 letters, digits, periods, underscores, colons, or hyphens.',
    );
  }
  return value;
}

function requireStrongEtag(value: string): string {
  if (!STRONG_ETAG_PATTERN.test(value)) {
    throw new Error('If-Match must contain a strong entity tag from the latest response.');
  }
  return value;
}

type OrganizationMembershipQuery = NonNullable<ListOrganizationMembershipsData['query']>;

function organizationMembershipQuery(query: OrganizationMembershipQuery): string {
  const parameters = new URLSearchParams();
  if (query.search !== undefined) {
    if (typeof query.search !== 'string') {
      throw new Error('Membership search must be text.');
    }
    const search = query.search.trim().normalize('NFC');
    const length = Array.from(search).length;
    const hasControlCharacter = Array.from(search).some((character) => {
      const code = character.charCodeAt(0);
      return code <= 31 || code === 127;
    });
    if (length < 2 || length > 100 || hasControlCharacter) {
      throw new Error('Membership search must contain 2 to 100 valid characters.');
    }
    parameters.set('search', search);
  }
  if (query.state !== undefined) {
    if (typeof query.state !== 'string' || !MEMBERSHIP_ACCESS_STATES.has(query.state)) {
      throw new Error('Membership state is not allowed.');
    }
    parameters.set('state', query.state);
  }
  if (query.roleKey !== undefined) {
    if (
      typeof query.roleKey !== 'string' ||
      query.roleKey.length > 100 ||
      !MEMBERSHIP_ROLE_KEY_PATTERN.test(query.roleKey)
    ) {
      throw new Error('Membership role key has an invalid format.');
    }
    parameters.set('roleKey', query.roleKey);
  }
  if (query.limit !== undefined) {
    if (!Number.isInteger(query.limit) || query.limit < 1 || query.limit > 100) {
      throw new Error('Membership page limit must be an integer from 1 to 100.');
    }
    parameters.set('limit', String(query.limit));
  }
  if (query.cursor !== undefined) {
    if (typeof query.cursor !== 'string' || !MEMBERSHIP_CURSOR_PATTERN.test(query.cursor)) {
      throw new Error('Membership cursor has an invalid format.');
    }
    parameters.set('cursor', query.cursor);
  }
  const serialized = parameters.toString();
  return serialized ? `?${serialized}` : '';
}

function normalizeBaseUrl(value: string): string {
  const candidate = value.trim();
  if (!candidate || candidate.includes('?') || candidate.includes('#')) {
    throw new Error('The CareOS API base URL must not contain a query or fragment.');
  }

  const hasApiSuffix = (path: string) => path.replace(/\/+$/, '').endsWith('/api');

  if (candidate.startsWith('/')) {
    if (
      candidate.startsWith('//') ||
      candidate.split('/').some((segment) => segment === '.' || segment === '..') ||
      !hasApiSuffix(candidate)
    ) {
      throw new Error('The CareOS API base URL must be a local path ending in /api.');
    }
    return candidate.replace(/\/+$/, '');
  }

  let parsed: URL;
  try {
    parsed = new URL(candidate);
  } catch {
    throw new Error('The CareOS API base URL must be absolute or begin with /.');
  }

  const localHttpHosts = new Set(['127.0.0.1', '[::1]', 'localhost']);
  const secure = parsed.protocol === 'https:';
  const explicitLocalDevelopment =
    parsed.protocol === 'http:' && localHttpHosts.has(parsed.hostname);
  if (!secure && !explicitLocalDevelopment) {
    throw new Error('Absolute CareOS API URLs require HTTPS outside local development.');
  }
  if (parsed.username || parsed.password || !hasApiSuffix(parsed.pathname)) {
    throw new Error('The CareOS API base URL must end in /api and contain no credentials.');
  }

  parsed.pathname = parsed.pathname.replace(/\/+$/, '');
  return parsed.toString().replace(/\/$/, '');
}

function defaultCorrelationId(): string {
  if (!globalThis.crypto?.randomUUID) {
    throw new Error('CareOS requires crypto.randomUUID() for request correlation.');
  }
  return globalThis.crypto.randomUUID();
}

function clientProblem(
  status: number,
  code: string,
  title: string,
  detail: string,
  instance: string,
  correlationId: string,
): Problem {
  return {
    code,
    correlationId,
    detail,
    instance,
    status,
    title,
    type: 'about:blank',
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function parseProblem(value: unknown): Problem | undefined {
  if (!isRecord(value)) {
    return undefined;
  }

  const requiredStrings = ['type', 'title', 'detail', 'instance', 'code', 'correlationId'] as const;
  if (
    !requiredStrings.every((field) => typeof value[field] === 'string') ||
    typeof value.status !== 'number' ||
    !Number.isInteger(value.status) ||
    value.status < 400 ||
    value.status > 599
  ) {
    return undefined;
  }

  let errors: Problem['errors'];
  if (value.errors !== undefined) {
    if (
      !Array.isArray(value.errors) ||
      !value.errors.every(
        (error) =>
          isRecord(error) &&
          typeof error.path === 'string' &&
          typeof error.code === 'string' &&
          typeof error.message === 'string',
      )
    ) {
      return undefined;
    }
    errors = value.errors.map((error) => ({
      code: error.code as string,
      message: error.message as string,
      path: error.path as string,
    }));
  }

  return {
    code: value.code as string,
    correlationId: value.correlationId as string,
    detail: value.detail as string,
    ...(errors ? { errors } : {}),
    instance: value.instance as string,
    status: value.status as number,
    title: value.title as string,
    type: value.type as string,
  };
}

function retryAfterSeconds(headers: Headers): number | undefined {
  const value = headers.get('Retry-After')?.trim();
  if (!value || !/^\d{1,5}$/.test(value)) {
    return undefined;
  }
  const seconds = Number(value);
  return seconds >= 1 && seconds <= MAX_RETRY_AFTER_SECONDS ? seconds : undefined;
}

function sessionExpirySeconds(headers: Headers): number | undefined {
  const value = headers.get(SESSION_EXPIRY_HEADER)?.trim();
  if (!value || !/^\d{1,10}$/.test(value)) {
    return undefined;
  }
  const seconds = Number(value);
  return seconds <= MAX_SESSION_EXPIRY_SECONDS ? seconds : undefined;
}

function responseMediaType(headers: Headers): string {
  return headers.get('Content-Type')?.split(';', 1)[0]?.trim().toLowerCase() ?? '';
}

function validCsrfToken(value: unknown): value is CsrfToken {
  return (
    isRecord(value) &&
    value.headerName === 'X-XSRF-TOKEN' &&
    value.parameterName === '_csrf' &&
    typeof value.token === 'string' &&
    value.token.length >= 16 &&
    value.token.length <= 512 &&
    value.token.trim() === value.token &&
    !Array.from(value.token).some((character) => {
      const code = character.charCodeAt(0);
      return code <= 31 || code === 127;
    })
  );
}

function wasAborted(error: unknown): boolean {
  return isRecord(error) && error.name === 'AbortError';
}

export class CareOsApiClient {
  readonly #baseUrl: string;
  readonly #correlationIdFactory: () => string;
  readonly #fetch: FetchImplementation;
  readonly #now: () => number;
  readonly #sessionLifecycleListeners = new Set<(event: SessionLifecycleEvent) => void>();

  constructor(options: CareOsApiClientOptions = {}) {
    this.#baseUrl = normalizeBaseUrl(
      options.baseUrl ?? import.meta.env.VITE_API_BASE_URL ?? '/api',
    );
    this.#correlationIdFactory = options.correlationIdFactory ?? defaultCorrelationId;
    this.#fetch = options.fetch ?? globalThis.fetch.bind(globalThis);
    this.#now = options.now ?? Date.now;
  }

  subscribeSessionLifecycle(listener: (event: SessionLifecycleEvent) => void): () => void {
    this.#sessionLifecycleListeners.add(listener);
    return () => this.#sessionLifecycleListeners.delete(listener);
  }

  async #request<T>(descriptor: RequestDescriptor): Promise<ApiResult<T>> {
    const correlationId = this.#correlationIdFactory();
    if (!CORRELATION_ID_PATTERN.test(correlationId)) {
      throw new Error('The correlation ID factory returned an invalid value.');
    }

    const headers = new Headers({
      Accept: ACCEPTED_RESPONSE_TYPES,
      'X-Correlation-Id': correlationId,
    });
    if (descriptor.body !== undefined) {
      headers.set('Content-Type', 'application/json');
    }
    if (descriptor.idempotencyKey !== undefined) {
      headers.set('Idempotency-Key', descriptor.idempotencyKey);
    }
    if (descriptor.ifMatch !== undefined) {
      headers.set('If-Match', descriptor.ifMatch);
    }

    try {
      const requestStartedAt = this.#now();
      const response = await this.#fetch(`${this.#baseUrl}${descriptor.path}`, {
        body: descriptor.body === undefined ? undefined : JSON.stringify(descriptor.body),
        credentials: 'include',
        headers,
        method: descriptor.method,
        signal: descriptor.signal,
      });
      return await this.#resultFromResponse<T>(
        response,
        descriptor,
        correlationId,
        requestStartedAt,
      );
    } catch (error) {
      const aborted = wasAborted(error);
      return {
        correlationId,
        kind: aborted ? 'aborted' : 'network',
        ok: false,
        problem: clientProblem(
          0,
          aborted ? 'request_aborted' : 'network_error',
          aborted ? 'Request cancelled' : 'Network request failed',
          aborted
            ? 'The request was cancelled before it completed.'
            : 'CareOS could not reach the API. No server error details are available.',
          descriptor.path,
          correlationId,
        ),
        status: 0,
      };
    }
  }

  async #resultFromResponse<T>(
    response: Response,
    descriptor: RequestDescriptor,
    requestCorrelationId: string,
    requestStartedAt: number,
  ): Promise<ApiResult<T>> {
    const responseCorrelationId = response.headers.get('X-Correlation-Id');
    const correlationId =
      responseCorrelationId && CORRELATION_ID_PATTERN.test(responseCorrelationId)
        ? responseCorrelationId
        : requestCorrelationId;
    const retryAfter = retryAfterSeconds(response.headers);
    const sessionExpiresAt = this.#publishSessionLifecycle(response, requestStartedAt);
    const text = await response.text();

    if (!responseCorrelationId || !CORRELATION_ID_PATTERN.test(responseCorrelationId)) {
      return this.#contractFailure(
        response.status,
        descriptor.path,
        correlationId,
        'The API response omitted its required correlation identifier.',
        retryAfter,
      );
    }

    if (!response.ok) {
      const mediaType = responseMediaType(response.headers);
      let parsed: unknown;
      try {
        parsed = text ? JSON.parse(text) : undefined;
      } catch {
        parsed = undefined;
      }
      const problem = mediaType === 'application/problem+json' ? parseProblem(parsed) : undefined;
      if (!problem) {
        return this.#contractFailure(
          response.status,
          descriptor.path,
          correlationId,
          'The API error response did not match the checked problem contract.',
          retryAfter,
        );
      }

      return {
        correlationId,
        kind: 'http',
        ok: false,
        problem: {
          ...problem,
          correlationId,
          status: response.status,
        },
        ...(retryAfter === undefined ? {} : { retryAfterSeconds: retryAfter }),
        status: response.status,
      };
    }

    if (!descriptor.successStatuses.includes(response.status)) {
      return this.#contractFailure(
        response.status,
        descriptor.path,
        correlationId,
        'The API returned an undocumented success status.',
        retryAfter,
      );
    }

    if (descriptor.responseBody === 'empty') {
      if (text.trim()) {
        return this.#contractFailure(
          response.status,
          descriptor.path,
          correlationId,
          'The API returned a body for an empty response.',
          retryAfter,
        );
      }
      return {
        correlationId,
        data: undefined as T,
        ok: true,
        ...(sessionExpiresAt === undefined ? {} : { sessionExpiresAt }),
        status: response.status,
      };
    }

    const mediaType = responseMediaType(response.headers);
    if (!text || (mediaType !== 'application/json' && !mediaType.endsWith('+json'))) {
      return this.#contractFailure(
        response.status,
        descriptor.path,
        correlationId,
        'The API success response did not contain JSON.',
        retryAfter,
      );
    }

    let data: T;
    try {
      data = JSON.parse(text) as T;
    } catch {
      return this.#contractFailure(
        response.status,
        descriptor.path,
        correlationId,
        'The API success response contained invalid JSON.',
        retryAfter,
      );
    }

    const etag = response.headers.get('ETag');
    return {
      correlationId,
      data,
      ...(etag && STRONG_ETAG_PATTERN.test(etag) ? { etag } : {}),
      ok: true,
      ...(sessionExpiresAt === undefined ? {} : { sessionExpiresAt }),
      status: response.status,
    };
  }

  #publishSessionLifecycle(response: Response, requestStartedAt: number): number | undefined {
    if (response.status === 401) {
      this.#notifySessionLifecycle({ type: 'invalidated' });
      return undefined;
    }
    const expiresInSeconds = sessionExpirySeconds(response.headers);
    if (expiresInSeconds === undefined) {
      return undefined;
    }
    const expiresAt = requestStartedAt + expiresInSeconds * 1_000;
    this.#notifySessionLifecycle({ expiresAt, type: 'deadline' });
    return expiresAt;
  }

  #notifySessionLifecycle(event: SessionLifecycleEvent): void {
    for (const listener of this.#sessionLifecycleListeners) {
      try {
        listener(event);
      } catch {
        // A UI observer cannot change the checked transport result.
      }
    }
  }

  #contractFailure(
    responseStatus: number,
    instance: string,
    correlationId: string,
    detail: string,
    retryAfter: number | undefined,
  ): ApiFailure {
    const status = responseStatus >= 400 && responseStatus <= 599 ? responseStatus : 502;
    return {
      correlationId,
      kind: 'contract',
      ok: false,
      problem: clientProblem(
        status,
        'invalid_api_response',
        'Invalid API response',
        detail,
        instance,
        correlationId,
      ),
      ...(responseStatus === status ? {} : { responseStatus }),
      ...(retryAfter === undefined ? {} : { retryAfterSeconds: retryAfter }),
      status,
    };
  }

  async #mutation<T>(
    descriptor: Omit<RequestDescriptor, 'method'> & { method?: 'POST' | 'PUT' },
  ): Promise<ApiResult<T>> {
    const csrf = await this.issueCsrfToken({ signal: descriptor.signal });
    if (!csrf.ok) {
      return csrf;
    }
    if (!validCsrfToken(csrf.data)) {
      return this.#contractFailure(
        csrf.status,
        descriptor.path,
        csrf.correlationId,
        'The CSRF bootstrap response was invalid, so the mutation was not sent.',
        undefined,
      );
    }

    const correlationId = this.#correlationIdFactory();
    if (!CORRELATION_ID_PATTERN.test(correlationId)) {
      throw new Error('The correlation ID factory returned an invalid value.');
    }
    const headers = new Headers({
      Accept: ACCEPTED_RESPONSE_TYPES,
      'X-Correlation-Id': correlationId,
      [csrf.data.headerName]: csrf.data.token,
    });
    if (descriptor.body !== undefined) {
      headers.set('Content-Type', 'application/json');
    }
    if (descriptor.idempotencyKey !== undefined) {
      headers.set('Idempotency-Key', descriptor.idempotencyKey);
    }
    if (descriptor.ifMatch !== undefined) {
      headers.set('If-Match', descriptor.ifMatch);
    }

    try {
      const method = descriptor.method ?? 'POST';
      const requestStartedAt = this.#now();
      const response = await this.#fetch(`${this.#baseUrl}${descriptor.path}`, {
        body: descriptor.body === undefined ? undefined : JSON.stringify(descriptor.body),
        credentials: 'include',
        headers,
        method,
        signal: descriptor.signal,
      });
      return await this.#resultFromResponse<T>(
        response,
        { ...descriptor, method },
        correlationId,
        requestStartedAt,
      );
    } catch (error) {
      const aborted = wasAborted(error);
      return {
        correlationId,
        kind: aborted ? 'aborted' : 'network',
        ok: false,
        problem: clientProblem(
          0,
          aborted ? 'request_aborted' : 'network_error',
          aborted ? 'Request cancelled' : 'Network request failed',
          aborted
            ? 'The request was cancelled before it completed.'
            : 'CareOS could not reach the API. The mutation was not retried automatically.',
          descriptor.path,
          correlationId,
        ),
        status: 0,
      };
    }
  }

  listPrototypeScreens(options: ApiRequestOptions = {}) {
    return this.#request<ListPrototypeScreensResponse>({
      method: 'GET',
      path: relativeEndpointPath(endpoints.listPrototypeScreens.path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.listPrototypeScreens.successStatuses,
    });
  }

  getSystemSummary(options: ApiRequestOptions = {}) {
    return this.#request<GetSystemSummaryResponse>({
      method: 'GET',
      path: relativeEndpointPath(endpoints.getSystemSummary.path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.getSystemSummary.successStatuses,
    });
  }

  issueCsrfToken(options: ApiRequestOptions = {}) {
    return this.#request<IssueCsrfTokenResponse>({
      method: 'GET',
      path: relativeEndpointPath(endpoints.issueCsrfToken.path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.issueCsrfToken.successStatuses,
    });
  }

  getAuthenticationSession(options: ApiRequestOptions = {}) {
    return this.#request<GetAuthenticationSessionResponse>({
      method: 'GET',
      path: relativeEndpointPath(endpoints.getAuthenticationSession.path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.getAuthenticationSession.successStatuses,
    });
  }

  login(body: LoginData['body'], options: ApiRequestOptions = {}) {
    return this.#mutation<LoginResponse>({
      body,
      path: relativeEndpointPath(endpoints.login.path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.login.successStatuses,
    });
  }

  logout(options: ApiRequestOptions = {}) {
    return this.#mutation<LogoutResponse>({
      path: relativeEndpointPath(endpoints.logout.path),
      responseBody: 'empty',
      signal: options.signal,
      successStatuses: endpoints.logout.successStatuses,
    });
  }

  requestPasswordReset(body: RequestPasswordResetData['body'], options: ApiRequestOptions = {}) {
    return this.#mutation<void>({
      body,
      path: relativeEndpointPath(endpoints.requestPasswordReset.path),
      responseBody: 'empty',
      signal: options.signal,
      successStatuses: endpoints.requestPasswordReset.successStatuses,
    });
  }

  completePasswordReset(body: CompletePasswordResetData['body'], options: ApiRequestOptions = {}) {
    return this.#mutation<CompletePasswordResetResponse>({
      body,
      path: relativeEndpointPath(endpoints.completePasswordReset.path),
      responseBody: 'empty',
      signal: options.signal,
      successStatuses: endpoints.completePasswordReset.successStatuses,
    });
  }

  acceptInvitation(body: AcceptInvitationData['body'], options: ApiRequestOptions = {}) {
    return this.#mutation<AcceptInvitationResponse>({
      body,
      path: relativeEndpointPath(endpoints.acceptInvitation.path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.acceptInvitation.successStatuses,
    });
  }

  issueInvitation(
    organizationId: string,
    body: IssueInvitationData['body'],
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const path = endpoints.issueInvitation.path.replace(
      '{organizationId}',
      requireUuid(organizationId, 'organizationId'),
    ) as `/api/v1/organizations/${string}/invitations`;
    return this.#mutation<IssueInvitationResponse>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.issueInvitation.successStatuses,
    });
  }

  revokeInvitation(
    organizationId: string,
    invitationId: string,
    body: RevokeInvitationData['body'],
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const path = endpoints.revokeInvitation.path
      .replace('{organizationId}', requireUuid(organizationId, 'organizationId'))
      .replace(
        '{invitationId}',
        requireUuid(invitationId, 'invitationId'),
      ) as `/api/v1/organizations/${string}/invitations/${string}/revocations`;
    return this.#mutation<RevokeInvitationResponse>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.revokeInvitation.successStatuses,
    });
  }

  requestMfaAdministrativeReset(
    organizationId: string,
    targetUserId: string,
    body: RequestMfaAdministrativeResetData['body'],
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const path = endpoints.requestMfaAdministrativeReset.path
      .replace('{organizationId}', requireUuid(organizationId, 'organizationId'))
      .replace(
        '{targetUserId}',
        requireUuid(targetUserId, 'targetUserId'),
      ) as `/api/v1/organizations/${string}/users/${string}/mfa-reset-requests`;
    return this.#mutation<RequestMfaAdministrativeResetResponse>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.requestMfaAdministrativeReset.successStatuses,
    });
  }

  approveMfaAdministrativeReset(
    organizationId: string,
    targetUserId: string,
    approvalId: string,
    body: ApproveMfaAdministrativeResetData['body'],
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const path = endpoints.approveMfaAdministrativeReset.path
      .replace('{organizationId}', requireUuid(organizationId, 'organizationId'))
      .replace('{targetUserId}', requireUuid(targetUserId, 'targetUserId'))
      .replace(
        '{approvalId}',
        requireUuid(approvalId, 'approvalId'),
      ) as `/api/v1/organizations/${string}/users/${string}/mfa-reset-requests/${string}/approvals`;
    return this.#mutation<ApproveMfaAdministrativeResetResponse>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.approveMfaAdministrativeReset.successStatuses,
    });
  }

  executeMfaAdministrativeReset(
    organizationId: string,
    targetUserId: string,
    approvalId: string,
    body: ExecuteMfaAdministrativeResetData['body'],
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const path = endpoints.executeMfaAdministrativeReset.path
      .replace('{organizationId}', requireUuid(organizationId, 'organizationId'))
      .replace('{targetUserId}', requireUuid(targetUserId, 'targetUserId'))
      .replace(
        '{approvalId}',
        requireUuid(approvalId, 'approvalId'),
      ) as `/api/v1/organizations/${string}/users/${string}/mfa-reset-requests/${string}/executions`;
    return this.#mutation<ExecuteMfaAdministrativeResetResponse>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.executeMfaAdministrativeReset.successStatuses,
    });
  }

  requestOrganizationMembershipChange(
    organizationId: string,
    membershipId: string,
    body: RequestOrganizationMembershipChangeData['body'],
    ifMatch: string,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const path = endpoints.requestOrganizationMembershipChange.path
      .replace('{organizationId}', requireUuid(organizationId, 'organizationId'))
      .replace(
        '{membershipId}',
        requireUuid(membershipId, 'membershipId'),
      ) as `/api/v1/organizations/${string}/memberships/${string}/change-requests`;
    return this.#mutation<RequestOrganizationMembershipChangeResponse>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      ifMatch: requireStrongEtag(ifMatch),
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.requestOrganizationMembershipChange.successStatuses,
    });
  }

  approveOrganizationMembershipChange(
    organizationId: string,
    membershipId: string,
    approvalId: string,
    body: ApproveOrganizationMembershipChangeData['body'],
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const path = endpoints.approveOrganizationMembershipChange.path
      .replace('{organizationId}', requireUuid(organizationId, 'organizationId'))
      .replace('{membershipId}', requireUuid(membershipId, 'membershipId'))
      .replace(
        '{approvalId}',
        requireUuid(approvalId, 'approvalId'),
      ) as `/api/v1/organizations/${string}/memberships/${string}/change-requests/${string}/approvals`;
    return this.#mutation<ApproveOrganizationMembershipChangeResponse>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.approveOrganizationMembershipChange.successStatuses,
    });
  }

  executeOrganizationMembershipChange(
    organizationId: string,
    membershipId: string,
    approvalId: string,
    body: ExecuteOrganizationMembershipChangeData['body'],
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const path = endpoints.executeOrganizationMembershipChange.path
      .replace('{organizationId}', requireUuid(organizationId, 'organizationId'))
      .replace('{membershipId}', requireUuid(membershipId, 'membershipId'))
      .replace(
        '{approvalId}',
        requireUuid(approvalId, 'approvalId'),
      ) as `/api/v1/organizations/${string}/memberships/${string}/change-requests/${string}/executions`;
    return this.#mutation<ExecuteOrganizationMembershipChangeResponse>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.executeOrganizationMembershipChange.successStatuses,
    });
  }

  requestOrganizationOwnerTransfer(
    organizationId: string,
    membershipId: string,
    body: RequestOrganizationOwnerTransferData['body'],
    ifMatch: string,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const path = endpoints.requestOrganizationOwnerTransfer.path
      .replace('{organizationId}', requireUuid(organizationId, 'organizationId'))
      .replace(
        '{membershipId}',
        requireUuid(membershipId, 'membershipId'),
      ) as `/api/v1/organizations/${string}/memberships/${string}/owner-transfer-requests`;
    return this.#mutation<RequestOrganizationOwnerTransferResponse>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      ifMatch: requireStrongEtag(ifMatch),
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.requestOrganizationOwnerTransfer.successStatuses,
    });
  }

  approveOrganizationOwnerTransfer(
    organizationId: string,
    membershipId: string,
    approvalId: string,
    body: ApproveOrganizationOwnerTransferData['body'],
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const path = endpoints.approveOrganizationOwnerTransfer.path
      .replace('{organizationId}', requireUuid(organizationId, 'organizationId'))
      .replace('{membershipId}', requireUuid(membershipId, 'membershipId'))
      .replace(
        '{approvalId}',
        requireUuid(approvalId, 'approvalId'),
      ) as `/api/v1/organizations/${string}/memberships/${string}/owner-transfer-requests/${string}/approvals`;
    return this.#mutation<ApproveOrganizationOwnerTransferResponse>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.approveOrganizationOwnerTransfer.successStatuses,
    });
  }

  executeOrganizationOwnerTransfer(
    organizationId: string,
    membershipId: string,
    approvalId: string,
    body: ExecuteOrganizationOwnerTransferData['body'],
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const path = endpoints.executeOrganizationOwnerTransfer.path
      .replace('{organizationId}', requireUuid(organizationId, 'organizationId'))
      .replace('{membershipId}', requireUuid(membershipId, 'membershipId'))
      .replace(
        '{approvalId}',
        requireUuid(approvalId, 'approvalId'),
      ) as `/api/v1/organizations/${string}/memberships/${string}/owner-transfer-requests/${string}/executions`;
    return this.#mutation<ExecuteOrganizationOwnerTransferResponse>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.executeOrganizationOwnerTransfer.successStatuses,
    });
  }

  startMfaEnrollment(body: StartMfaEnrollmentData['body'] = {}, options: ApiRequestOptions = {}) {
    return this.#mutation<StartMfaEnrollmentResponse>({
      body,
      path: relativeEndpointPath(endpoints.startMfaEnrollment.path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.startMfaEnrollment.successStatuses,
    });
  }

  verifyMfaEnrollment(body: VerifyMfaEnrollmentData['body'], options: ApiRequestOptions = {}) {
    return this.#mutation<VerifyMfaEnrollmentResponse>({
      body,
      path: relativeEndpointPath(endpoints.verifyMfaEnrollment.path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.verifyMfaEnrollment.successStatuses,
    });
  }

  completeMfaChallenge(body: CompleteMfaChallengeData['body'], options: ApiRequestOptions = {}) {
    return this.#mutation<CompleteMfaChallengeResponse>({
      body,
      path: relativeEndpointPath(endpoints.completeMfaChallenge.path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.completeMfaChallenge.successStatuses,
    });
  }

  verifyRecentAuthentication(
    body: VerifyRecentAuthenticationData['body'],
    options: ApiRequestOptions = {},
  ) {
    return this.#mutation<VerifyRecentAuthenticationResponse>({
      body,
      path: relativeEndpointPath(endpoints.verifyRecentAuthentication.path),
      responseBody: 'empty',
      signal: options.signal,
      successStatuses: endpoints.verifyRecentAuthentication.successStatuses,
    });
  }

  regenerateRecoveryCodes(options: ApiRequestOptions = {}) {
    return this.#mutation<RegenerateRecoveryCodesResponse>({
      path: relativeEndpointPath(endpoints.regenerateRecoveryCodes.path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.regenerateRecoveryCodes.successStatuses,
    });
  }

  listSelectableOrganizations(options: ApiRequestOptions = {}) {
    return this.#request<ListSelectableOrganizationsResponse>({
      method: 'GET',
      path: relativeEndpointPath(endpoints.listSelectableOrganizations.path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.listSelectableOrganizations.successStatuses,
    });
  }

  listOrganizationMemberships(
    organizationId: string,
    query: OrganizationMembershipQuery = {},
    options: ApiRequestOptions = {},
  ) {
    const path = endpoints.listOrganizationMemberships.path.replace(
      '{organizationId}',
      requireUuid(organizationId, 'organizationId'),
    ) as `/api/v1/organizations/${string}/memberships`;
    return this.#request<ListOrganizationMembershipsResponse>({
      method: 'GET',
      path: `${relativeEndpointPath(path)}${organizationMembershipQuery(query)}`,
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.listOrganizationMemberships.successStatuses,
    });
  }

  getAdministrationReadiness(organizationId: string, options: ApiRequestOptions = {}) {
    const path = endpoints.getAdministrationReadiness.path.replace(
      '{organizationId}',
      requireUuid(organizationId, 'organizationId'),
    ) as `/api/v1/organizations/${string}/setup-readiness`;
    return this.#request<GetAdministrationReadinessResponse>({
      method: 'GET',
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.getAdministrationReadiness.successStatuses,
    });
  }

  getOrganizationProfile(organizationId: string, options: ApiRequestOptions = {}) {
    const path = endpoints.getOrganizationProfile.path.replace(
      '{organizationId}',
      requireUuid(organizationId, 'organizationId'),
    ) as `/api/v1/organizations/${string}/profile`;
    return this.#request<GetOrganizationProfileResponse>({
      method: 'GET',
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.getOrganizationProfile.successStatuses,
    });
  }

  updateOrganizationProfile(
    organizationId: string,
    body: UpdateOrganizationProfileData['body'],
    ifMatch: string,
    idempotencyKey: string,
    options: ApiRequestOptions = {},
  ) {
    const path = endpoints.updateOrganizationProfile.path.replace(
      '{organizationId}',
      requireUuid(organizationId, 'organizationId'),
    ) as `/api/v1/organizations/${string}/profile`;
    return this.#mutation<UpdateOrganizationProfileResponse>({
      body,
      idempotencyKey: requireIdempotencyKey(idempotencyKey),
      ifMatch: requireStrongEtag(ifMatch),
      method: 'PUT',
      path: relativeEndpointPath(path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.updateOrganizationProfile.successStatuses,
    });
  }

  selectOrganization(body: SelectOrganizationData['body'], options: ApiRequestOptions = {}) {
    return this.#mutation<SelectOrganizationResponse>({
      body,
      path: relativeEndpointPath(endpoints.selectOrganization.path),
      responseBody: 'json',
      signal: options.signal,
      successStatuses: endpoints.selectOrganization.successStatuses,
    });
  }
}

export function createCareOsApiClient(options: CareOsApiClientOptions = {}) {
  return new CareOsApiClient(options);
}

export const careOsApi = createCareOsApiClient();
