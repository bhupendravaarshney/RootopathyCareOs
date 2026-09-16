import { describe, expect, it, vi } from 'vitest';

import { createCareOsApiClient } from './client';

const correlationIds = ['request-1', 'request-2', 'request-3', 'request-4'];

function correlationIdFactory() {
  let index = 0;
  return () => correlationIds[index++] ?? `request-${index}`;
}

function jsonResponse(
  body: unknown,
  options: { correlationId?: string; headers?: HeadersInit; status?: number } = {},
) {
  const headers = new Headers(options.headers);
  headers.set('Content-Type', 'application/json');
  headers.set('X-Correlation-Id', options.correlationId ?? 'server-correlation');
  return new Response(JSON.stringify(body), {
    headers,
    status: options.status ?? 200,
  });
}

function emptyResponse(status: number, correlationId = 'server-correlation') {
  return new Response(null, {
    headers: { 'X-Correlation-Id': correlationId },
    status,
  });
}

function mockFetch(...responses: Response[]) {
  const fetcher = vi.fn<Fetch>();
  for (const response of responses) {
    fetcher.mockResolvedValueOnce(response);
  }
  return fetcher;
}

type Fetch = typeof fetch;

describe('CareOsApiClient', () => {
  it('sends credentialed correlated reads and exposes a strong ETag', async () => {
    const fetcher = mockFetch(
      jsonResponse(
        {
          edition: 'Foundation',
          generatedAt: '2026-09-14T00:00:00Z',
          modules: ['M1'],
          product: 'CareOS',
          screenCount: 79,
        },
        { headers: { ETag: '"summary-v1"' } },
      ),
    );
    const client = createCareOsApiClient({
      baseUrl: '/api',
      correlationIdFactory: correlationIdFactory(),
      fetch: fetcher,
    });

    const result = await client.getSystemSummary();

    expect(result).toMatchObject({
      correlationId: 'server-correlation',
      etag: '"summary-v1"',
      ok: true,
      status: 200,
    });
    expect(fetcher).toHaveBeenCalledTimes(1);
    const [url, init] = fetcher.mock.calls[0]!;
    const headers = new Headers(init?.headers);
    expect(url).toBe('/api/public/system-summary');
    expect(init?.credentials).toBe('include');
    expect(init?.method).toBe('GET');
    expect(headers.get('Accept')).toContain('application/problem+json');
    expect(headers.get('X-Correlation-Id')).toBe('request-1');
  });

  it('publishes a checked server session deadline without depending on the browser clock', async () => {
    const fetcher = mockFetch(
      jsonResponse(
        {
          mfaEnabled: false,
          recentAuthentication: true,
          state: 'authenticated',
          user: {
            displayName: 'CareOS User',
            email: 'user@example.test',
            id: '8cbabf2c-203c-4723-8b02-866d682adf92',
          },
        },
        { headers: { 'X-CareOS-Session-Expires-In': '120' } },
      ),
    );
    const client = createCareOsApiClient({
      baseUrl: '/api',
      correlationIdFactory: correlationIdFactory(),
      fetch: fetcher,
      now: () => 10_000,
    });
    const listener = vi.fn();
    const unsubscribe = client.subscribeSessionLifecycle(listener);

    const result = await client.getAuthenticationSession();

    expect(result).toMatchObject({ ok: true, sessionExpiresAt: 130_000 });
    expect(listener).toHaveBeenCalledWith({ expiresAt: 130_000, type: 'deadline' });
    unsubscribe();
  });

  it('publishes session invalidation on any unauthorized API response', async () => {
    const unauthorized = jsonResponse(
      {
        code: 'session-expired',
        correlationId: 'expired-correlation',
        detail: 'Sign in again.',
        instance: '/api/v1/organizations',
        status: 401,
        title: 'Session expired',
        type: 'about:blank',
      },
      { status: 401 },
    );
    unauthorized.headers.set('Content-Type', 'application/problem+json');
    const client = createCareOsApiClient({
      baseUrl: '/api',
      correlationIdFactory: correlationIdFactory(),
      fetch: mockFetch(unauthorized),
    });
    const listener = vi.fn();
    client.subscribeSessionLifecycle(listener);

    const result = await client.listSelectableOrganizations();

    expect(result).toMatchObject({ kind: 'http', ok: false, status: 401 });
    expect(listener).toHaveBeenCalledOnce();
    expect(listener).toHaveBeenCalledWith({ type: 'invalidated' });
  });

  it('bootstraps CSRF before an unsafe request and uses the returned header', async () => {
    const fetcher = mockFetch(
      jsonResponse(
        {
          headerName: 'X-XSRF-TOKEN',
          parameterName: '_csrf',
          token: 'valid-csrf-token-123456',
        },
        { correlationId: 'csrf-correlation' },
      ),
      jsonResponse(
        {
          mfaEnabled: false,
          recentAuthentication: true,
          state: 'authenticated',
          user: {
            displayName: 'CareOS User',
            email: 'user@example.test',
            id: '8cbabf2c-203c-4723-8b02-866d682adf92',
          },
        },
        { correlationId: 'login-correlation', status: 202 },
      ),
    );
    const client = createCareOsApiClient({
      baseUrl: '/api',
      correlationIdFactory: correlationIdFactory(),
      fetch: fetcher,
    });

    const result = await client.login({
      email: 'user@example.test',
      password: 'not-logged-or-exposed',
    });

    expect(result).toMatchObject({
      correlationId: 'login-correlation',
      ok: true,
      status: 202,
    });
    expect(fetcher).toHaveBeenCalledTimes(2);
    const [csrfUrl, csrfInit] = fetcher.mock.calls[0]!;
    const [loginUrl, loginInit] = fetcher.mock.calls[1]!;
    const loginHeaders = new Headers(loginInit?.headers);
    expect(csrfUrl).toBe('/api/v1/auth/csrf');
    expect(csrfInit?.credentials).toBe('include');
    expect(loginUrl).toBe('/api/v1/auth/login');
    expect(loginInit?.credentials).toBe('include');
    expect(loginHeaders.get('X-XSRF-TOKEN')).toBe('valid-csrf-token-123456');
    expect(loginHeaders.get('X-Correlation-Id')).toBe('request-2');
    expect(loginInit?.body).toBe(
      JSON.stringify({ email: 'user@example.test', password: 'not-logged-or-exposed' }),
    );
  });

  it('fails closed without sending a mutation when CSRF data is malformed', async () => {
    const fetcher = mockFetch(
      jsonResponse({
        headerName: 'Unexpected-Header',
        parameterName: '_csrf',
        token: 'valid-csrf-token-123456',
      }),
    );
    const client = createCareOsApiClient({
      baseUrl: '/api',
      correlationIdFactory: correlationIdFactory(),
      fetch: fetcher,
    });

    const result = await client.logout();

    expect(result).toMatchObject({
      kind: 'contract',
      ok: false,
      problem: { code: 'invalid_api_response' },
      status: 502,
    });
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it('treats a null CSRF payload as contract failure instead of throwing', async () => {
    const fetcher = mockFetch(jsonResponse(null));
    const client = createCareOsApiClient({
      baseUrl: '/api',
      correlationIdFactory: correlationIdFactory(),
      fetch: fetcher,
    });

    await expect(client.logout()).resolves.toMatchObject({
      kind: 'contract',
      ok: false,
      responseStatus: 200,
      status: 502,
    });
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it('surfaces a bounded Retry-After problem and never retries a mutation', async () => {
    const problem = {
      code: 'authentication_throttled',
      correlationId: 'problem-correlation',
      detail: 'Try again later.',
      instance: '/api/v1/auth/login',
      status: 429,
      title: 'Too many requests',
      type: 'https://careos.example/problems/authentication-throttled',
    };
    const throttled = jsonResponse(problem, {
      correlationId: 'server-throttle-correlation',
      headers: {
        'Content-Type': 'application/problem+json',
        'Retry-After': '12',
      },
      status: 429,
    });
    throttled.headers.set('Content-Type', 'application/problem+json');
    const fetcher = mockFetch(
      jsonResponse({
        headerName: 'X-XSRF-TOKEN',
        parameterName: '_csrf',
        token: 'valid-csrf-token-123456',
      }),
      throttled,
    );
    const client = createCareOsApiClient({
      baseUrl: '/api',
      correlationIdFactory: correlationIdFactory(),
      fetch: fetcher,
    });

    const result = await client.login({
      email: 'user@example.test',
      password: 'not-logged-or-exposed',
    });

    expect(result).toMatchObject({
      correlationId: 'server-throttle-correlation',
      kind: 'http',
      ok: false,
      retryAfterSeconds: 12,
      status: 429,
    });
    expect(fetcher).toHaveBeenCalledTimes(2);
  });

  it('sends a caller-owned idempotency key on governed invitation issuance', async () => {
    const fetcher = mockFetch(
      jsonResponse({
        headerName: 'X-XSRF-TOKEN',
        parameterName: '_csrf',
        token: 'valid-csrf-token-123456',
      }),
      jsonResponse(
        {
          expiresAt: '2026-09-16T12:00:00Z',
          invitationId: '77777777-7777-4777-8777-777777777777',
          roleKey: 'organization_viewer',
          status: 'pending',
        },
        { status: 201 },
      ),
    );
    const client = createCareOsApiClient({
      baseUrl: '/api',
      correlationIdFactory: correlationIdFactory(),
      fetch: fetcher,
    });

    const result = await client.issueInvitation(
      '22222222-2222-4222-8222-222222222222',
      {
        displayName: 'Invited User',
        email: 'invited@example.test',
        reason: 'Approved access request CARE-42',
        roleKey: 'organization_viewer',
      },
      'invite:11111111-1111-4111-8111-111111111111',
    );

    expect(result).toMatchObject({ ok: true, status: 201 });
    expect(fetcher).toHaveBeenCalledTimes(2);
    const [url, init] = fetcher.mock.calls[1]!;
    const headers = new Headers(init?.headers);
    expect(url).toBe('/api/v1/organizations/22222222-2222-4222-8222-222222222222/invitations');
    expect(headers.get('Idempotency-Key')).toBe('invite:11111111-1111-4111-8111-111111111111');
    expect(headers.get('X-XSRF-TOKEN')).toBe('valid-csrf-token-123456');
  });

  it('reads and conditionally updates the organization profile with exact transport evidence', async () => {
    const organizationId = '22222222-2222-4222-8222-222222222222';
    const profile = {
      countryCode: 'IN',
      displayName: 'North Clinic',
      legalName: 'North Clinic Private Limited',
      lifecycleStatus: 'draft',
      lockVersion: 4,
      organizationId,
      timezone: 'Asia/Kolkata',
      updatedAt: '2026-09-16T08:00:00Z',
    };
    const fetcher = mockFetch(
      jsonResponse(profile, { headers: { ETag: '"organization-profile:4"' } }),
      jsonResponse({
        headerName: 'X-XSRF-TOKEN',
        parameterName: '_csrf',
        token: 'valid-csrf-token-123456',
      }),
      jsonResponse(
        { ...profile, displayName: 'North Care Network', lockVersion: 5 },
        { headers: { ETag: '"organization-profile:5"' } },
      ),
    );
    const client = createCareOsApiClient({
      baseUrl: '/api',
      correlationIdFactory: correlationIdFactory(),
      fetch: fetcher,
    });

    await expect(client.getOrganizationProfile(organizationId)).resolves.toMatchObject({
      etag: '"organization-profile:4"',
      ok: true,
    });
    await expect(
      client.updateOrganizationProfile(
        organizationId,
        {
          countryCode: 'IN',
          displayName: 'North Care Network',
          legalName: 'North Clinic Private Limited',
          reason: 'Approved legal identity review CARE-42',
          timezone: 'Asia/Kolkata',
        },
        '"organization-profile:4"',
        'profile:11111111-1111-4111-8111-111111111111',
      ),
    ).resolves.toMatchObject({ etag: '"organization-profile:5"', ok: true, status: 200 });

    expect(fetcher).toHaveBeenCalledTimes(3);
    const [readUrl, readInit] = fetcher.mock.calls[0]!;
    const [updateUrl, updateInit] = fetcher.mock.calls[2]!;
    const updateHeaders = new Headers(updateInit?.headers);
    expect(readUrl).toBe(`/api/v1/organizations/${organizationId}/profile`);
    expect(readInit?.method).toBe('GET');
    expect(updateUrl).toBe(`/api/v1/organizations/${organizationId}/profile`);
    expect(updateInit?.method).toBe('PUT');
    expect(updateHeaders.get('If-Match')).toBe('"organization-profile:4"');
    expect(updateHeaders.get('Idempotency-Key')).toBe(
      'profile:11111111-1111-4111-8111-111111111111',
    );
    expect(updateHeaders.get('X-XSRF-TOKEN')).toBe('valid-csrf-token-123456');
  });

  it('rejects a weak or malformed profile precondition before CSRF bootstrap', () => {
    const fetcher = mockFetch();
    const client = createCareOsApiClient({ baseUrl: '/api', fetch: fetcher });

    expect(() =>
      client.updateOrganizationProfile(
        '22222222-2222-4222-8222-222222222222',
        {
          countryCode: 'IN',
          displayName: 'North Clinic',
          legalName: 'North Clinic Private Limited',
          reason: 'Approved identity review',
          timezone: 'Asia/Kolkata',
        },
        'W/"organization-profile:4"',
        'profile:11111111-1111-4111-8111-111111111111',
      ),
    ).toThrow(/strong entity tag/);
    expect(fetcher).not.toHaveBeenCalled();
  });

  it('rejects malformed invitation route identifiers and idempotency keys before fetching', () => {
    const fetcher = mockFetch();
    const client = createCareOsApiClient({ baseUrl: '/api', fetch: fetcher });
    const request = {
      displayName: 'Invited User',
      email: 'invited@example.test',
      reason: 'Approved access request',
      roleKey: 'organization_viewer',
    };

    expect(() => client.issueInvitation('not-a-uuid', request, 'valid-key-value-1234')).toThrow(
      /organizationId must be a UUID/,
    );
    expect(() =>
      client.issueInvitation('22222222-2222-4222-8222-222222222222', request, 'too-short'),
    ).toThrow(/idempotency key/);
    expect(fetcher).not.toHaveBeenCalled();
  });

  it('sends all governed MFA reset transitions to their exact tenant paths', async () => {
    const csrfPayload = {
      headerName: 'X-XSRF-TOKEN',
      parameterName: '_csrf',
      token: 'valid-csrf-token-123456',
    };
    const organizationId = '22222222-2222-4222-8222-222222222222';
    const targetUserId = '88888888-8888-4888-8888-888888888888';
    const approvalId = '99999999-9999-4999-8999-999999999999';
    const expiresAt = '2026-09-16T12:00:00Z';
    const fetcher = mockFetch(
      jsonResponse(csrfPayload),
      jsonResponse({ approvalId, expiresAt, status: 'pending', targetUserId }, { status: 201 }),
      jsonResponse(csrfPayload),
      jsonResponse({ approvalId, expiresAt, status: 'approved', targetUserId }),
      jsonResponse(csrfPayload),
      jsonResponse({ approvalId, expiresAt, status: 'reset', targetUserId }),
    );
    const client = createCareOsApiClient({
      baseUrl: '/api',
      correlationIdFactory: correlationIdFactory(),
      fetch: fetcher,
    });

    await expect(
      client.requestMfaAdministrativeReset(
        organizationId,
        targetUserId,
        { reason: 'Verified support case CARE-42' },
        'mfa-request:11111111-1111-4111-8111-111111111111',
      ),
    ).resolves.toMatchObject({ ok: true, status: 201 });
    await expect(
      client.approveMfaAdministrativeReset(
        organizationId,
        targetUserId,
        approvalId,
        { reason: 'Independent identity verification completed' },
        'mfa-approve:11111111-1111-4111-8111-111111111111',
      ),
    ).resolves.toMatchObject({ ok: true, status: 200 });
    await expect(
      client.executeMfaAdministrativeReset(
        organizationId,
        targetUserId,
        approvalId,
        { reason: 'Verified support case CARE-42' },
        'mfa-execute:11111111-1111-4111-8111-111111111111',
      ),
    ).resolves.toMatchObject({ ok: true, status: 200 });

    const mutationCalls = [fetcher.mock.calls[1]!, fetcher.mock.calls[3]!, fetcher.mock.calls[5]!];
    expect(mutationCalls.map(([url]) => url)).toEqual([
      `/api/v1/organizations/${organizationId}/users/${targetUserId}/mfa-reset-requests`,
      `/api/v1/organizations/${organizationId}/users/${targetUserId}/mfa-reset-requests/${approvalId}/approvals`,
      `/api/v1/organizations/${organizationId}/users/${targetUserId}/mfa-reset-requests/${approvalId}/executions`,
    ]);
    expect(
      mutationCalls.map(([, init]) => new Headers(init?.headers).get('Idempotency-Key')),
    ).toEqual([
      'mfa-request:11111111-1111-4111-8111-111111111111',
      'mfa-approve:11111111-1111-4111-8111-111111111111',
      'mfa-execute:11111111-1111-4111-8111-111111111111',
    ]);
  });

  it('rejects malformed MFA reset route identifiers before fetching', () => {
    const fetcher = mockFetch();
    const client = createCareOsApiClient({ baseUrl: '/api', fetch: fetcher });

    expect(() =>
      client.requestMfaAdministrativeReset(
        '22222222-2222-4222-8222-222222222222',
        'not-a-user-id',
        { reason: 'Verified support case' },
        'mfa-request:11111111-1111-4111-8111-111111111111',
      ),
    ).toThrow(/targetUserId must be a UUID/);
    expect(() =>
      client.approveMfaAdministrativeReset(
        '22222222-2222-4222-8222-222222222222',
        '88888888-8888-4888-8888-888888888888',
        'not-an-approval-id',
        { reason: 'Independent verification completed' },
        'mfa-approve:11111111-1111-4111-8111-111111111111',
      ),
    ).toThrow(/approvalId must be a UUID/);
    expect(fetcher).not.toHaveBeenCalled();
  });

  it('turns undocumented success payloads into safe contract failures', async () => {
    const fetcher = mockFetch(emptyResponse(200));
    const client = createCareOsApiClient({
      baseUrl: '/api',
      correlationIdFactory: correlationIdFactory(),
      fetch: fetcher,
    });

    const result = await client.listPrototypeScreens();

    expect(result).toMatchObject({
      kind: 'contract',
      ok: false,
      problem: {
        code: 'invalid_api_response',
        detail: 'The API success response did not contain JSON.',
      },
      responseStatus: 200,
      status: 502,
    });
  });

  it('does not leak transport exception details', async () => {
    const fetcher = vi.fn<Fetch>().mockRejectedValue(new Error('private upstream detail'));
    const client = createCareOsApiClient({
      baseUrl: '/api',
      correlationIdFactory: correlationIdFactory(),
      fetch: fetcher,
    });

    const result = await client.getAuthenticationSession();

    expect(result).toMatchObject({
      kind: 'network',
      ok: false,
      problem: { code: 'network_error' },
      status: 0,
    });
    expect(JSON.stringify(result)).not.toContain('private upstream detail');
  });

  it('rejects credentialed clear-text remote API configuration', () => {
    expect(() =>
      createCareOsApiClient({
        baseUrl: 'http://careos.example/api',
        fetch: mockFetch(),
      }),
    ).toThrow(/require HTTPS/);

    expect(() =>
      createCareOsApiClient({
        baseUrl: 'http://localhost:8080/api',
        fetch: mockFetch(),
      }),
    ).not.toThrow();
  });
});
