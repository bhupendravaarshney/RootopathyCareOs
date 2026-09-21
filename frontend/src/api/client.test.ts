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
          mfaRequired: false,
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
          mfaRequired: false,
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
      editable: true,
      legalName: 'North Clinic Private Limited',
      lifecycleStatus: 'draft',
      locale: 'en-IN',
      lockVersion: 4,
      organizationId,
      organizationType: 'care_provider',
      timezone: 'Asia/Kolkata',
      tradingName: null,
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
          locale: 'en-IN',
          organizationType: 'care_network',
          reason: 'Approved legal identity review CARE-42',
          timezone: 'Asia/Kolkata',
          tradingName: 'North Care',
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

  it('lists memberships with an opaque checked cursor and allow-listed filters', async () => {
    const organizationId = '22222222-2222-4222-8222-222222222222';
    const fetcher = mockFetch(
      jsonResponse({
        asOf: '2026-09-17T05:30:00Z',
        availableActions: ['issueInvitation'],
        items: [],
        organizationId,
        page: { hasMore: false, limit: 25, nextCursor: null },
      }),
    );
    const client = createCareOsApiClient({
      baseUrl: '/api',
      correlationIdFactory: correlationIdFactory(),
      fetch: fetcher,
    });

    await expect(
      client.listOrganizationMemberships(organizationId, {
        cursor: 'signed_cursor-1',
        limit: 25,
        roleKey: 'security_administrator',
        search: '  Asha Verma  ',
        state: 'active',
      }),
    ).resolves.toMatchObject({ ok: true, status: 200 });

    const [url, init] = fetcher.mock.calls[0]!;
    const headers = new Headers(init?.headers);
    expect(url).toBe(
      `/api/v1/organizations/${organizationId}/memberships?search=Asha+Verma&state=active&roleKey=security_administrator&limit=25&cursor=signed_cursor-1`,
    );
    expect(init?.credentials).toBe('include');
    expect(init?.method).toBe('GET');
    expect(headers.get('X-Correlation-Id')).toBe('request-1');

    expect(() =>
      client.listOrganizationMemberships(organizationId, { cursor: 'not a cursor' }),
    ).toThrow(/cursor has an invalid format/i);
    expect(fetcher).toHaveBeenCalledOnce();
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
          locale: 'en-IN',
          organizationType: 'care_provider',
          reason: 'Approved identity review',
          timezone: 'Asia/Kolkata',
          tradingName: null,
        },
        'W/"organization-profile:4"',
        'profile:11111111-1111-4111-8111-111111111111',
      ),
    ).toThrow(/strong entity tag/);
    expect(fetcher).not.toHaveBeenCalled();
  });

  it('sends organization identifier reads and governed lifecycle mutations to exact paths', async () => {
    const organizationId = '22222222-2222-4222-8222-222222222222';
    const identifierId = '44444444-4444-4444-8444-444444444444';
    const replacementId = '55555555-5555-4555-8555-555555555555';
    const identifier = {
      assigningAuthority: 'National Provider Registry',
      availableActions: ['edit', 'verify'],
      createdAt: '2026-09-16T08:00:00Z',
      effectiveFrom: '2026-09-16T08:00:00Z',
      effectiveTo: null,
      evidenceReference: null,
      expiryDate: null,
      identifierId,
      identifierType: 'registration',
      isPrimary: true,
      issueDate: '2026-09-01',
      jurisdictionCountryCode: 'IN',
      lockVersion: 0,
      status: 'draft',
      supersedesId: null,
      typeDisplayName: 'Registration identifier',
      updatedAt: '2026-09-16T08:00:00Z',
      value: 'REG-IN-0042',
      verificationStatus: 'unverified',
    };
    const csrfPayload = {
      headerName: 'X-XSRF-TOKEN',
      parameterName: '_csrf',
      token: 'valid-csrf-token-123456',
    };
    const mutationHeaders = {
      ETag: `"organization-identifier:${identifierId}:0"`,
    };
    const fetcher = mockFetch(
      jsonResponse({
        canCreate: true,
        items: [identifier],
        organizationId,
        types: [
          {
            displayName: 'Registration identifier',
            jurisdictionCountryCode: null,
            key: 'registration',
            primaryRequired: true,
          },
        ],
      }),
      jsonResponse(csrfPayload),
      jsonResponse(identifier, { headers: mutationHeaders, status: 201 }),
      jsonResponse(csrfPayload),
      jsonResponse(identifier, { headers: mutationHeaders }),
      jsonResponse(csrfPayload),
      jsonResponse(identifier, { headers: mutationHeaders }),
      jsonResponse(csrfPayload),
      jsonResponse(identifier, { headers: mutationHeaders }),
      jsonResponse(csrfPayload),
      jsonResponse(identifier, { headers: mutationHeaders }),
    );
    const client = createCareOsApiClient({
      baseUrl: '/api',
      correlationIdFactory: correlationIdFactory(),
      fetch: fetcher,
    });
    const write = {
      assigningAuthority: 'National Provider Registry',
      effectiveFrom: '2026-09-16T08:00:00Z',
      effectiveTo: null,
      expiryDate: null,
      identifierType: 'registration',
      isPrimary: true,
      issueDate: '2026-09-01',
      jurisdictionCountryCode: 'IN',
      reason: 'Approved registration intake CARE-42',
      value: 'REG-IN-0042',
    };
    const etag = `"organization-identifier:${identifierId}:0"`;
    const replacementEtag = `"organization-identifier:${replacementId}:2"`;

    await expect(client.listOrganizationIdentifiers(organizationId)).resolves.toMatchObject({
      ok: true,
      status: 200,
    });
    await expect(
      client.createOrganizationIdentifier(
        organizationId,
        write,
        'identifier-create:11111111-1111-4111-8111-111111111111',
      ),
    ).resolves.toMatchObject({ etag, ok: true, status: 201 });
    await expect(
      client.updateOrganizationIdentifier(
        organizationId,
        identifierId,
        write,
        etag,
        'identifier-update:11111111-1111-4111-8111-111111111111',
      ),
    ).resolves.toMatchObject({ etag, ok: true, status: 200 });
    await expect(
      client.verifyOrganizationIdentifier(
        organizationId,
        identifierId,
        {
          evidenceReference: 'NPR-CASE-2026-1042',
          reason: 'Authority verification completed CARE-42',
        },
        etag,
        'identifier-verify:11111111-1111-4111-8111-111111111111',
      ),
    ).resolves.toMatchObject({ etag, ok: true, status: 200 });
    await expect(
      client.revokeOrganizationIdentifier(
        organizationId,
        identifierId,
        { reason: 'Approved revocation request CARE-42' },
        etag,
        'identifier-revoke:11111111-1111-4111-8111-111111111111',
      ),
    ).resolves.toMatchObject({ etag, ok: true, status: 200 });
    await expect(
      client.supersedeOrganizationIdentifier(
        organizationId,
        identifierId,
        {
          reason: 'Approved verified replacement CARE-42',
          replacementEtag,
          replacementId,
        },
        etag,
        'identifier-supersede:11111111-1111-4111-8111-111111111111',
      ),
    ).resolves.toMatchObject({ etag, ok: true, status: 200 });

    expect(fetcher).toHaveBeenCalledTimes(11);
    expect(fetcher.mock.calls.map(([url]) => url)).toEqual([
      `/api/v1/organizations/${organizationId}/identifiers`,
      '/api/v1/auth/csrf',
      `/api/v1/organizations/${organizationId}/identifiers`,
      '/api/v1/auth/csrf',
      `/api/v1/organizations/${organizationId}/identifiers/${identifierId}`,
      '/api/v1/auth/csrf',
      `/api/v1/organizations/${organizationId}/identifiers/${identifierId}/verifications`,
      '/api/v1/auth/csrf',
      `/api/v1/organizations/${organizationId}/identifiers/${identifierId}/revocations`,
      '/api/v1/auth/csrf',
      `/api/v1/organizations/${organizationId}/identifiers/${identifierId}/supersessions`,
    ]);
    expect(fetcher.mock.calls.map(([, init]) => init?.method)).toEqual([
      'GET',
      'GET',
      'POST',
      'GET',
      'PUT',
      'GET',
      'POST',
      'GET',
      'POST',
      'GET',
      'POST',
    ]);
    for (const index of [2, 4, 6, 8, 10]) {
      const [, init] = fetcher.mock.calls[index]!;
      const headers = new Headers(init?.headers);
      expect(headers.get('Idempotency-Key')).toMatch(/^identifier-/);
      expect(headers.get('X-XSRF-TOKEN')).toBe('valid-csrf-token-123456');
    }
    for (const index of [4, 6, 8, 10]) {
      const [, init] = fetcher.mock.calls[index]!;
      expect(new Headers(init?.headers).get('If-Match')).toBe(etag);
    }
    expect(JSON.parse(fetcher.mock.calls[10]![1]?.body as string)).toEqual({
      reason: 'Approved verified replacement CARE-42',
      replacementEtag,
      replacementId,
    });
  });

  it('uses the exact governed address and contact paths with strong revisions', async () => {
    const organizationId = '22222222-2222-4222-8222-222222222222';
    const addressId = '66666666-6666-4666-8666-666666666666';
    const contactId = '77777777-7777-4777-8777-777777777770';
    const csrfPayload = {
      headerName: 'X-XSRF-TOKEN',
      parameterName: '_csrf',
      token: 'valid-csrf-token-123456',
    };
    const address = {
      addressId,
      addressLines: ['42 Care Street'],
      addressType: 'registered',
      availableActions: ['supersede', 'end'],
      countryCode: 'IN',
      createdAt: '2026-09-18T08:00:00Z',
      effectiveFrom: '2026-09-18T08:00:00Z',
      effectiveTo: null,
      isPrimary: true,
      locality: 'Mumbai',
      lockVersion: 0,
      postcode: '400069',
      region: 'Maharashtra',
      status: 'active',
      supersedesId: null,
      updatedAt: '2026-09-18T08:00:00Z',
      validationSource: null,
      validationStatus: 'unvalidated',
    };
    const contact = {
      availableActions: ['verify', 'supersede', 'end'],
      channel: 'email',
      contactId,
      createdAt: '2026-09-18T08:00:00Z',
      effectiveFrom: '2026-09-18T08:00:00Z',
      effectiveTo: null,
      isPreferred: true,
      isPrimary: true,
      lockVersion: 0,
      maskedValue: 'o***@***.org',
      purpose: 'operational',
      purposeDisplayName: 'Operational contact',
      status: 'active',
      supersedesId: null,
      updatedAt: '2026-09-18T08:00:00Z',
      verificationStatus: 'unverified',
    };
    const directory = {
      addressTypes: ['registered', 'postal', 'service', 'billing'],
      addresses: [address],
      canCreate: true,
      contacts: [contact],
      organizationId,
      purposes: [
        {
          displayName: 'Operational contact',
          key: 'operational',
          publicProjectionAllowed: false,
        },
      ],
    };
    const addressEtag = `"organization-address:${addressId}:0"`;
    const contactEtag = `"organization-contact:${contactId}:0"`;
    const fetcher = mockFetch(
      jsonResponse(directory),
      jsonResponse(csrfPayload),
      jsonResponse(address, { headers: { ETag: addressEtag }, status: 201 }),
      jsonResponse(csrfPayload),
      jsonResponse(address, { headers: { ETag: addressEtag }, status: 201 }),
      jsonResponse(csrfPayload),
      jsonResponse(address, { headers: { ETag: addressEtag } }),
      jsonResponse(csrfPayload),
      jsonResponse(contact, { headers: { ETag: contactEtag }, status: 201 }),
      jsonResponse(csrfPayload),
      jsonResponse(contact, { headers: { ETag: contactEtag } }),
      jsonResponse(csrfPayload),
      jsonResponse(contact, { headers: { ETag: contactEtag }, status: 201 }),
      jsonResponse(csrfPayload),
      jsonResponse(contact, { headers: { ETag: contactEtag } }),
    );
    const client = createCareOsApiClient({ baseUrl: '/api', fetch: fetcher });
    const addressWrite = {
      addressLines: ['42 Care Street'],
      addressType: 'registered' as const,
      countryCode: 'IN',
      effectiveFrom: '2026-09-18T08:00:00Z',
      effectiveTo: null,
      isPrimary: true,
      locality: 'Mumbai',
      postcode: '400069',
      reason: 'Approved registered address intake',
      region: 'Maharashtra',
      validationSource: null,
      validationStatus: 'unvalidated' as const,
    };
    const contactWrite = {
      channel: 'email' as const,
      effectiveFrom: '2026-09-18T08:00:00Z',
      effectiveTo: null,
      isPreferred: true,
      isPrimary: true,
      purpose: 'operational',
      reason: 'Approved operational contact intake',
      value: 'operations@example.org',
    };

    await expect(client.listOrganizationContacts(organizationId)).resolves.toMatchObject({
      ok: true,
      status: 200,
    });
    await expect(
      client.createOrganizationAddress(
        organizationId,
        addressWrite,
        'address-create:11111111-1111-4111-8111-111111111111',
      ),
    ).resolves.toMatchObject({ etag: addressEtag, ok: true, status: 201 });
    await expect(
      client.supersedeOrganizationAddress(
        organizationId,
        addressId,
        addressWrite,
        addressEtag,
        'address-supersede:11111111-1111-4111-8111-111111111111',
      ),
    ).resolves.toMatchObject({ etag: addressEtag, ok: true, status: 201 });
    await expect(
      client.endOrganizationAddress(
        organizationId,
        addressId,
        { reason: 'Approved address ending request' },
        addressEtag,
        'address-ending:11111111-1111-4111-8111-111111111111',
      ),
    ).resolves.toMatchObject({ etag: addressEtag, ok: true, status: 200 });
    await expect(
      client.createOrganizationContact(
        organizationId,
        contactWrite,
        'contact-create:11111111-1111-4111-8111-111111111111',
      ),
    ).resolves.toMatchObject({ etag: contactEtag, ok: true, status: 201 });
    await expect(
      client.verifyOrganizationContact(
        organizationId,
        contactId,
        { reason: 'Approved contact verification' },
        contactEtag,
        'contact-verify:11111111-1111-4111-8111-111111111111',
      ),
    ).resolves.toMatchObject({ etag: contactEtag, ok: true, status: 200 });
    await expect(
      client.supersedeOrganizationContact(
        organizationId,
        contactId,
        contactWrite,
        contactEtag,
        'contact-supersede:11111111-1111-4111-8111-111111111111',
      ),
    ).resolves.toMatchObject({ etag: contactEtag, ok: true, status: 201 });
    await expect(
      client.endOrganizationContact(
        organizationId,
        contactId,
        { reason: 'Approved contact ending request' },
        contactEtag,
        'contact-ending:11111111-1111-4111-8111-111111111111',
      ),
    ).resolves.toMatchObject({ etag: contactEtag, ok: true, status: 200 });

    expect(fetcher.mock.calls.map(([url]) => url)).toEqual([
      `/api/v1/organizations/${organizationId}/contacts`,
      '/api/v1/auth/csrf',
      `/api/v1/organizations/${organizationId}/addresses`,
      '/api/v1/auth/csrf',
      `/api/v1/organizations/${organizationId}/addresses/${addressId}/supersessions`,
      '/api/v1/auth/csrf',
      `/api/v1/organizations/${organizationId}/addresses/${addressId}/endings`,
      '/api/v1/auth/csrf',
      `/api/v1/organizations/${organizationId}/contacts`,
      '/api/v1/auth/csrf',
      `/api/v1/organizations/${organizationId}/contacts/${contactId}/verifications`,
      '/api/v1/auth/csrf',
      `/api/v1/organizations/${organizationId}/contacts/${contactId}/supersessions`,
      '/api/v1/auth/csrf',
      `/api/v1/organizations/${organizationId}/contacts/${contactId}/endings`,
    ]);
    for (const index of [4, 6, 10, 12, 14]) {
      expect(new Headers(fetcher.mock.calls[index]![1]?.headers).get('If-Match')).toMatch(
        /^"organization-(address|contact):/,
      );
    }
    expect(JSON.parse(fetcher.mock.calls[8]![1]?.body as string)).toEqual(contactWrite);
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

  it('sends governed membership transitions with exact route and revision evidence', async () => {
    const csrfPayload = {
      headerName: 'X-XSRF-TOKEN',
      parameterName: '_csrf',
      token: 'valid-csrf-token-123456',
    };
    const organizationId = '22222222-2222-4222-8222-222222222222';
    const membershipId = '77777777-7777-4777-8777-777777777777';
    const targetUserId = '88888888-8888-4888-8888-888888888888';
    const approvalId = '99999999-9999-4999-8999-999999999999';
    const expiresAt = '2026-09-17T12:00:00Z';
    const mutation = {
      approvalId,
      changeType: 'role_change' as const,
      expiresAt,
      fromRoleKey: 'security_administrator',
      lockVersion: 4,
      membershipId,
      targetUserId,
      toRoleKey: 'organization_viewer',
    };
    const fetcher = mockFetch(
      jsonResponse(csrfPayload),
      jsonResponse({ ...mutation, status: 'pending' }, { status: 201 }),
      jsonResponse(csrfPayload),
      jsonResponse({ ...mutation, status: 'approved' }),
      jsonResponse(csrfPayload),
      jsonResponse({ ...mutation, lockVersion: 5, status: 'changed' }),
    );
    const client = createCareOsApiClient({
      baseUrl: '/api',
      correlationIdFactory: correlationIdFactory(),
      fetch: fetcher,
    });

    await expect(
      client.requestOrganizationMembershipChange(
        organizationId,
        membershipId,
        {
          changeType: 'role_change',
          reason: 'Approved least-privilege role adjustment',
          toRoleKey: 'organization_viewer',
        },
        `"organization-membership:${membershipId}:4"`,
        'membership-request:11111111-1111-4111-8111-111111111111',
      ),
    ).resolves.toMatchObject({ ok: true, status: 201 });
    await expect(
      client.approveOrganizationMembershipChange(
        organizationId,
        membershipId,
        approvalId,
        { reason: 'Independent access review completed' },
        'membership-approve:11111111-1111-4111-8111-111111111111',
      ),
    ).resolves.toMatchObject({ ok: true, status: 200 });
    await expect(
      client.executeOrganizationMembershipChange(
        organizationId,
        membershipId,
        approvalId,
        { reason: 'Approved least-privilege role adjustment' },
        'membership-execute:11111111-1111-4111-8111-111111111111',
      ),
    ).resolves.toMatchObject({ ok: true, status: 200 });

    const mutationCalls = [fetcher.mock.calls[1]!, fetcher.mock.calls[3]!, fetcher.mock.calls[5]!];
    expect(mutationCalls.map(([url]) => url)).toEqual([
      `/api/v1/organizations/${organizationId}/memberships/${membershipId}/change-requests`,
      `/api/v1/organizations/${organizationId}/memberships/${membershipId}/change-requests/${approvalId}/approvals`,
      `/api/v1/organizations/${organizationId}/memberships/${membershipId}/change-requests/${approvalId}/executions`,
    ]);
    expect(new Headers(mutationCalls[0]![1]?.headers).get('If-Match')).toBe(
      `"organization-membership:${membershipId}:4"`,
    );
    expect(
      mutationCalls.map(([, init]) => new Headers(init?.headers).get('Idempotency-Key')),
    ).toEqual([
      'membership-request:11111111-1111-4111-8111-111111111111',
      'membership-approve:11111111-1111-4111-8111-111111111111',
      'membership-execute:11111111-1111-4111-8111-111111111111',
    ]);
    expect(fetcher).toHaveBeenCalledTimes(6);
  });

  it('sends governed owner transitions through their distinct checked routes', async () => {
    const csrfPayload = {
      headerName: 'X-XSRF-TOKEN',
      parameterName: '_csrf',
      token: 'valid-csrf-token-123456',
    };
    const organizationId = '22222222-2222-4222-8222-222222222222';
    const membershipId = '77777777-7777-4777-8777-777777777777';
    const targetUserId = '88888888-8888-4888-8888-888888888888';
    const approvalId = '99999999-9999-4999-8999-999999999999';
    const mutation = {
      approvalId,
      changeType: 'owner_promotion' as const,
      expiresAt: '2026-09-17T12:00:00Z',
      fromRoleKey: 'security_administrator',
      lockVersion: 4,
      membershipId,
      targetUserId,
      toRoleKey: 'organization_owner',
    };
    const fetcher = mockFetch(
      jsonResponse(csrfPayload),
      jsonResponse({ ...mutation, status: 'pending' }, { status: 201 }),
      jsonResponse(csrfPayload),
      jsonResponse({ ...mutation, status: 'approved' }),
      jsonResponse(csrfPayload),
      jsonResponse({ ...mutation, lockVersion: 5, status: 'transferred' }),
    );
    const client = createCareOsApiClient({
      baseUrl: '/api',
      correlationIdFactory: correlationIdFactory(),
      fetch: fetcher,
    });

    await expect(
      client.requestOrganizationOwnerTransfer(
        organizationId,
        membershipId,
        {
          reason: 'Approved owner succession promotion request',
          toRoleKey: 'organization_owner',
        },
        `"organization-membership:${membershipId}:4"`,
        'owner-request:11111111-1111-4111-8111-111111111111',
      ),
    ).resolves.toMatchObject({ ok: true, status: 201 });
    await expect(
      client.approveOrganizationOwnerTransfer(
        organizationId,
        membershipId,
        approvalId,
        { reason: 'Independent owner succession review completed' },
        'owner-approve:11111111-1111-4111-8111-111111111111',
      ),
    ).resolves.toMatchObject({ ok: true, status: 200 });
    await expect(
      client.executeOrganizationOwnerTransfer(
        organizationId,
        membershipId,
        approvalId,
        { reason: 'Approved owner succession promotion request' },
        'owner-execute:11111111-1111-4111-8111-111111111111',
      ),
    ).resolves.toMatchObject({ ok: true, status: 200 });

    const mutationCalls = [fetcher.mock.calls[1]!, fetcher.mock.calls[3]!, fetcher.mock.calls[5]!];
    expect(mutationCalls.map(([url]) => url)).toEqual([
      `/api/v1/organizations/${organizationId}/memberships/${membershipId}/owner-transfer-requests`,
      `/api/v1/organizations/${organizationId}/memberships/${membershipId}/owner-transfer-requests/${approvalId}/approvals`,
      `/api/v1/organizations/${organizationId}/memberships/${membershipId}/owner-transfer-requests/${approvalId}/executions`,
    ]);
    expect(new Headers(mutationCalls[0]![1]?.headers).get('If-Match')).toBe(
      `"organization-membership:${membershipId}:4"`,
    );
    expect(
      mutationCalls.map(([, init]) => new Headers(init?.headers).get('Idempotency-Key')),
    ).toEqual([
      'owner-request:11111111-1111-4111-8111-111111111111',
      'owner-approve:11111111-1111-4111-8111-111111111111',
      'owner-execute:11111111-1111-4111-8111-111111111111',
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

  it('sends checked organization-unit mutations through their distinct routes', async () => {
    const organizationId = '22222222-2222-4222-8222-222222222222';
    const facilityId = '33333333-3333-4333-8333-333333333333';
    const unitId = '44444444-4444-4444-8444-444444444444';
    const etag = `"organization-unit:${unitId}:3"`;
    const csrfPayload = {
      headerName: 'X-XSRF-TOKEN',
      parameterName: '_csrf',
      token: 'valid-csrf-token-123456',
    };
    const directory = {
      organizationId,
      facilityId,
      canManage: true,
      evaluatedAt: '2026-09-20T12:00:00Z',
      units: [],
    };
    const fetcher = mockFetch(
      jsonResponse(csrfPayload),
      jsonResponse(directory),
      jsonResponse(csrfPayload),
      jsonResponse(directory),
      jsonResponse(csrfPayload),
      jsonResponse(directory),
      jsonResponse(csrfPayload),
      jsonResponse(directory),
      jsonResponse(csrfPayload),
      jsonResponse(directory),
      jsonResponse(csrfPayload),
      jsonResponse(directory),
    );
    const client = createCareOsApiClient({
      baseUrl: '/api',
      correlationIdFactory: correlationIdFactory(),
      fetch: fetcher,
    });

    await expect(
      client.updateOrganizationUnitDraft(
        organizationId,
        facilityId,
        unitId,
        {
          parentId: null,
          unitCode: 'CLINICAL',
          unitType: 'department',
          name: 'Clinical Operations',
          effectiveFrom: '2026-09-20T00:00:00Z',
          effectiveTo: null,
          reason: 'Rename the approved clinical hierarchy draft',
        },
        etag,
        'unit-update:11111111-1111-4111-8111-111111111111',
      ),
    ).resolves.toMatchObject({ ok: true, status: 200 });
    await expect(
      client.reparentOrganizationUnit(
        organizationId,
        facilityId,
        unitId,
        {
          parentId: null,
          effectiveFrom: '2026-09-20T12:00:00Z',
          reason: 'Move the approved hierarchy draft to the root',
        },
        etag,
        'unit-reparent:11111111-1111-4111-8111-111111111111',
      ),
    ).resolves.toMatchObject({ ok: true, status: 200 });
    await expect(
      client.activateOrganizationUnit(
        organizationId,
        facilityId,
        unitId,
        { reason: 'Activate the approved hierarchy in parent order' },
        etag,
        'unit-activate:11111111-1111-4111-8111-111111111111',
      ),
    ).resolves.toMatchObject({ ok: true, status: 200 });
    await expect(
      client.suspendOrganizationUnit(
        organizationId,
        facilityId,
        unitId,
        { reason: 'Suspend the approved hierarchy in descendant order' },
        etag,
        'unit-suspend:11111111-1111-4111-8111-111111111111',
      ),
    ).resolves.toMatchObject({ ok: true, status: 200 });
    await expect(
      client.reactivateOrganizationUnit(
        organizationId,
        facilityId,
        unitId,
        { reason: 'Reactivate the approved hierarchy in parent order' },
        etag,
        'unit-reactivate:11111111-1111-4111-8111-111111111111',
      ),
    ).resolves.toMatchObject({ ok: true, status: 200 });
    await expect(
      client.closeOrganizationUnit(
        organizationId,
        facilityId,
        unitId,
        {
          effectiveTo: '2026-09-20T12:00:00Z',
          reason: 'Close the approved hierarchy in descendant order',
        },
        etag,
        'unit-close:11111111-1111-4111-8111-111111111111',
      ),
    ).resolves.toMatchObject({ ok: true, status: 200 });

    const mutations = [
      fetcher.mock.calls[1]!,
      fetcher.mock.calls[3]!,
      fetcher.mock.calls[5]!,
      fetcher.mock.calls[7]!,
      fetcher.mock.calls[9]!,
      fetcher.mock.calls[11]!,
    ];
    expect(mutations.map(([url]) => url)).toEqual([
      `/api/v1/organizations/${organizationId}/facilities/${facilityId}/units/${unitId}`,
      `/api/v1/organizations/${organizationId}/facilities/${facilityId}/units/${unitId}/reparentings`,
      `/api/v1/organizations/${organizationId}/facilities/${facilityId}/units/${unitId}/activations`,
      `/api/v1/organizations/${organizationId}/facilities/${facilityId}/units/${unitId}/suspensions`,
      `/api/v1/organizations/${organizationId}/facilities/${facilityId}/units/${unitId}/reactivations`,
      `/api/v1/organizations/${organizationId}/facilities/${facilityId}/units/${unitId}/closures`,
    ]);
    expect(mutations.map(([, init]) => init?.method)).toEqual([
      'PUT',
      'POST',
      'POST',
      'POST',
      'POST',
      'POST',
    ]);
    expect(mutations.map(([, init]) => new Headers(init?.headers).get('If-Match'))).toEqual([
      etag,
      etag,
      etag,
      etag,
      etag,
      etag,
    ]);
    expect(mutations.map(([, init]) => new Headers(init?.headers).get('Idempotency-Key'))).toEqual([
      'unit-update:11111111-1111-4111-8111-111111111111',
      'unit-reparent:11111111-1111-4111-8111-111111111111',
      'unit-activate:11111111-1111-4111-8111-111111111111',
      'unit-suspend:11111111-1111-4111-8111-111111111111',
      'unit-reactivate:11111111-1111-4111-8111-111111111111',
      'unit-close:11111111-1111-4111-8111-111111111111',
    ]);
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

  it('rejects malformed live-administration success bodies at the transport boundary', async () => {
    const organizationId = '22222222-2222-4222-8222-222222222222';
    const client = createCareOsApiClient({
      baseUrl: '/api',
      correlationIdFactory: correlationIdFactory(),
      fetch: mockFetch(
        jsonResponse({
          organizationId,
          canManage: true,
          batches: [],
          evaluatedAt: 'not-an-instant',
        }),
      ),
    });

    const result = await client.getOperatingHoursDirectory(organizationId);

    expect(result).toMatchObject({
      kind: 'contract',
      ok: false,
      problem: {
        code: 'invalid_api_response',
        detail: 'The API success response did not match the checked response contract.',
      },
      responseStatus: 200,
      status: 502,
    });
  });

  it('accepts checked nullable identifier-scheme projections with preview samples', async () => {
    const organizationId = '22222222-2222-4222-8222-222222222222';
    const client = createCareOsApiClient({
      baseUrl: '/api',
      correlationIdFactory: correlationIdFactory(),
      fetch: mockFetch(
        jsonResponse({
          organizationId,
          canManage: true,
          canActivate: false,
          canRetire: false,
          schemes: [
            {
              schemeId: '33333333-3333-4333-8333-333333333333',
              schemeKey: 'PATIENT_ID',
              scopeType: 'organization',
              scopeId: null,
              description: null,
              status: 'draft',
              lockVersion: 0,
              versions: [
                {
                  versionId: '44444444-4444-4444-8444-444444444444',
                  versionNumber: 1,
                  prefix: 'P',
                  pattern: '^[A-Z0-9]+$',
                  alphabet: '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ',
                  checkDigitAlgorithm: null,
                  sequenceStart: 1,
                  sequenceIncrement: 1,
                  padding: 8,
                  previewSamples: ['P00000001'],
                  effectiveFrom: '2026-09-21T12:00:00Z',
                  status: 'draft',
                  lockVersion: 0,
                },
              ],
            },
          ],
          evaluatedAt: '2026-09-21T12:00:00Z',
        }),
      ),
    });

    const result = await client.getIdentifierSchemeDirectory(organizationId);

    expect(result).toMatchObject({ ok: true, status: 200 });
    if (result.ok)
      expect(result.data.schemes[0]?.versions[0]?.previewSamples).toEqual(['P00000001']);
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
