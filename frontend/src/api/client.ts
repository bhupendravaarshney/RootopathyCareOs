import type {
  CompleteMfaChallengeData,
  CompleteMfaChallengeResponse,
  CompleteMfaChallengeResponses,
  CompletePasswordResetData,
  CompletePasswordResetResponse,
  CompletePasswordResetResponses,
  CsrfToken,
  GetAuthenticationSessionData,
  GetAuthenticationSessionResponse,
  GetAuthenticationSessionResponses,
  GetSystemSummaryData,
  GetSystemSummaryResponse,
  GetSystemSummaryResponses,
  IssueCsrfTokenData,
  IssueCsrfTokenResponse,
  IssueCsrfTokenResponses,
  ListPrototypeScreensData,
  ListPrototypeScreensResponse,
  ListPrototypeScreensResponses,
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
  SelectOrganizationData,
  SelectOrganizationResponse,
  SelectOrganizationResponses,
  StartMfaEnrollmentData,
  StartMfaEnrollmentResponse,
  StartMfaEnrollmentResponses,
  VerifyMfaEnrollmentData,
  VerifyMfaEnrollmentResponse,
  VerifyMfaEnrollmentResponses,
  VerifyRecentAuthenticationData,
  VerifyRecentAuthenticationResponse,
  VerifyRecentAuthenticationResponses,
} from './generated';

const ACCEPTED_RESPONSE_TYPES = 'application/json, application/problem+json';
const CORRELATION_ID_PATTERN = /^[A-Za-z0-9._:-]{1,128}$/;
const STRONG_ETAG_PATTERN = /^"[A-Za-z0-9._:-]{1,128}"$/;
const MAX_RETRY_AFTER_SECONDS = 86_400;

type ResponseStatus<T> = Extract<keyof T, number>;

const endpoints = {
  completeMfaChallenge: {
    path: '/api/v1/auth/mfa/challenges' satisfies CompleteMfaChallengeData['url'],
    successStatuses: [200] satisfies readonly ResponseStatus<CompleteMfaChallengeResponses>[],
  },
  completePasswordReset: {
    path: '/api/v1/auth/password-resets' satisfies CompletePasswordResetData['url'],
    successStatuses: [204] satisfies readonly ResponseStatus<CompletePasswordResetResponses>[],
  },
  getAuthenticationSession: {
    path: '/api/v1/auth/session' satisfies GetAuthenticationSessionData['url'],
    successStatuses: [200] satisfies readonly ResponseStatus<GetAuthenticationSessionResponses>[],
  },
  getSystemSummary: {
    path: '/api/public/system-summary' satisfies GetSystemSummaryData['url'],
    successStatuses: [200] satisfies readonly ResponseStatus<GetSystemSummaryResponses>[],
  },
  issueCsrfToken: {
    path: '/api/v1/auth/csrf' satisfies IssueCsrfTokenData['url'],
    successStatuses: [200] satisfies readonly ResponseStatus<IssueCsrfTokenResponses>[],
  },
  listPrototypeScreens: {
    path: '/api/public/prototype-screens' satisfies ListPrototypeScreensData['url'],
    successStatuses: [200] satisfies readonly ResponseStatus<ListPrototypeScreensResponses>[],
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

export type CareOsApiClientOptions = {
  baseUrl?: string;
  correlationIdFactory?: () => string;
  fetch?: FetchImplementation;
};

type RequestDescriptor = {
  body?: unknown;
  method: 'GET' | 'POST';
  path: string;
  responseBody: 'empty' | 'json';
  signal?: AbortSignal;
  successStatuses: readonly number[];
};

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

  constructor(options: CareOsApiClientOptions = {}) {
    this.#baseUrl = normalizeBaseUrl(
      options.baseUrl ?? import.meta.env.VITE_API_BASE_URL ?? '/api',
    );
    this.#correlationIdFactory = options.correlationIdFactory ?? defaultCorrelationId;
    this.#fetch = options.fetch ?? globalThis.fetch.bind(globalThis);
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

    try {
      const response = await this.#fetch(`${this.#baseUrl}${descriptor.path}`, {
        body: descriptor.body === undefined ? undefined : JSON.stringify(descriptor.body),
        credentials: 'include',
        headers,
        method: descriptor.method,
        signal: descriptor.signal,
      });
      return await this.#resultFromResponse<T>(response, descriptor, correlationId);
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
  ): Promise<ApiResult<T>> {
    const responseCorrelationId = response.headers.get('X-Correlation-Id');
    const correlationId =
      responseCorrelationId && CORRELATION_ID_PATTERN.test(responseCorrelationId)
        ? responseCorrelationId
        : requestCorrelationId;
    const retryAfter = retryAfterSeconds(response.headers);
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
      status: response.status,
    };
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

  async #mutation<T>(descriptor: Omit<RequestDescriptor, 'method'>): Promise<ApiResult<T>> {
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

    try {
      const response = await this.#fetch(`${this.#baseUrl}${descriptor.path}`, {
        body: descriptor.body === undefined ? undefined : JSON.stringify(descriptor.body),
        credentials: 'include',
        headers,
        method: 'POST',
        signal: descriptor.signal,
      });
      return await this.#resultFromResponse<T>(
        response,
        { ...descriptor, method: 'POST' },
        correlationId,
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
