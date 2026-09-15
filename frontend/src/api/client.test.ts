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
