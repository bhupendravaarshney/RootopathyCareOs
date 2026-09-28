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

const workforceOrganizationId = '22222222-2222-4222-8222-222222222222';
const workforceRowId = '33333333-3333-4333-8333-333333333333';
const workforceMemberId = '44444444-4444-4444-8444-444444444444';

function workforceScreenFixture() {
  return {
    organizationId: workforceOrganizationId,
    screenId: 'M2-24',
    title: 'Offboarding',
    purpose: 'Execute a governed workforce lifecycle.',
    generatedAt: '2026-09-25T08:00:00Z',
    metrics: [{ key: 'active', label: 'Active', value: 1, tone: 'info' }],
    columns: [{ key: 'primary', label: 'Member' }],
    rows: [
      {
        id: workforceRowId,
        memberId: workforceMemberId,
        status: 'active',
        revision: 7,
        etag: `"m2:M2-24:${workforceRowId}:7"`,
        values: { primary: 'Synthetic workforce member' },
        allowedActionKeys: [],
      },
    ],
    actions: [],
    notices: [],
    nextCursor: null,
    pageSize: 25,
  };
}

const schedulingOrganizationId = '55555555-5555-4555-8555-555555555555';
const schedulingRequestId = '66666666-6666-4666-8666-666666666666';
const schedulingPatientId = '77777777-7777-4777-8777-777777777777';
const schedulingAppointmentId = '88888888-8888-4888-8888-888888888888';

function schedulingScreenFixture() {
  return {
    actions: [
      {
        fields: [],
        href: null,
        ifMatchRequired: true,
        key: 'confirm-appointment',
        label: 'Confirm appointment',
        reasonRequired: true,
        style: 'primary',
        targetRequired: true,
      },
    ],
    columns: [{ key: 'primary', label: 'Request' }],
    generatedAt: '2026-09-26T08:00:00Z',
    metrics: [{ key: 'held', label: 'Held', tone: 'warning', value: 1 }],
    nextCursor: null,
    notices: [],
    organizationId: schedulingOrganizationId,
    pageSize: 25,
    purpose: 'Confirm an exact held scheduling request.',
    rows: [
      {
        allowedActionKeys: ['confirm-appointment'],
        appointmentId: null,
        etag: `"m4:P4-11:${schedulingRequestId}:3"`,
        id: schedulingRequestId,
        patientId: schedulingPatientId,
        revision: 3,
        status: 'held',
        values: { primary: 'Synthetic appointment request' },
      },
    ],
    screenId: 'P4-11',
    title: 'Confirmation',
  };
}

const encounterOrganizationId = '99999999-9999-4999-8999-999999999999';
const encounterId = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const encounterEpisodeId = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
const encounterPatientId = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc';
const encounterAppointmentId = 'dddddddd-dddd-4ddd-8ddd-dddddddddddd';

function encounterScreenFixture() {
  return {
    actions: [
      {
        fields: [],
        href: null,
        ifMatchRequired: true,
        key: 'complete-encounter',
        label: 'Complete encounter',
        reasonRequired: true,
        style: 'primary',
        targetRequired: true,
      },
    ],
    columns: [{ key: 'primary', label: 'Encounter' }],
    generatedAt: '2026-09-26T09:00:00Z',
    metrics: [{ key: 'active', label: 'Active', tone: 'info', value: 1 }],
    nextCursor: null,
    notices: [],
    organizationId: encounterOrganizationId,
    pageSize: 25,
    purpose: 'Review exact encounter lifecycle context.',
    rows: [
      {
        allowedActionKeys: ['complete-encounter'],
        appointmentId: encounterAppointmentId,
        encounterId,
        episodeId: encounterEpisodeId,
        etag: `"m5:P5-03:${encounterId}:8"`,
        id: encounterId,
        patientId: encounterPatientId,
        revision: 8,
        status: 'in_progress',
        values: { primary: 'Synthetic encounter' },
      },
    ],
    screenId: 'P5-03',
    title: 'Patient and appointment context',
  };
}

const assessmentOrganizationId = 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee';
const assessmentSessionId = 'ffffffff-ffff-4fff-8fff-ffffffffffff';
const assessmentEncounterId = '12121212-1212-4121-8121-121212121212';
const assessmentPatientId = '34343434-3434-4343-8343-343434343434';

function assessmentScreenFixture() {
  return {
    actions: [
      {
        fields: [],
        href: null,
        ifMatchRequired: true,
        key: 'complete-assessment',
        label: 'Complete assessment',
        reasonRequired: true,
        style: 'primary',
        targetRequired: true,
      },
    ],
    columns: [{ key: 'primary', label: 'Patient' }],
    generatedAt: '2026-09-28T09:00:00Z',
    metrics: [{ key: 'active', label: 'Active', tone: 'info', value: 1 }],
    nextCursor: null,
    notices: [],
    organizationId: assessmentOrganizationId,
    pageSize: 25,
    purpose: 'Confirm the governed clinical assessment closeout.',
    rows: [
      {
        allowedActionKeys: ['complete-assessment'],
        assessmentSessionId,
        encounterId: assessmentEncounterId,
        etag: `"m6:COS-27:${assessmentSessionId}:9"`,
        id: assessmentSessionId,
        patientId: assessmentPatientId,
        revision: 9,
        status: 'signed',
        values: { primary: 'Synthetic assessment' },
      },
    ],
    screenId: 'COS-27',
    title: 'Confirm and close',
  };
}

const documentOrganizationId = '56565656-5656-4565-8565-565656565656';
const documentId = '78787878-7878-4787-8787-787878787878';
const documentVersionId = '90909090-9090-4909-8909-909090909090';
const documentPatientId = '23232323-2323-4232-8232-232323232323';
const diagnosticReportId = '45454545-4545-4454-8454-454545454545';

function documentScreenFixture(screenId = 'P7-04') {
  return {
    actions: [
      {
        fields: [],
        href: null,
        ifMatchRequired: true,
        key: 'classify-document',
        label: 'Append classification',
        reasonRequired: true,
        style: 'primary',
        targetRequired: true,
      },
    ],
    columns: [{ key: 'primary', label: 'Document' }],
    generatedAt: '2026-09-28T10:00:00Z',
    metrics: [{ key: 'clean', label: 'Clean', tone: 'success', value: 1 }],
    nextCursor: null,
    notices: [],
    organizationId: documentOrganizationId,
    pageSize: 25,
    purpose: 'Review immutable document evidence.',
    rows: [
      {
        allowedActionKeys: ['classify-document'],
        diagnosticReportId,
        documentId,
        documentVersionId,
        etag: `"m7:${screenId}:${documentId}:4"`,
        id: documentId,
        patientId: documentPatientId,
        revision: 4,
        status: 'clean',
        values: { primary: 'Synthetic laboratory report' },
      },
    ],
    screenId,
    title: 'Classification and metadata',
  };
}

function documentAccessFixture() {
  const accessIntentId = '67676767-6767-4676-8676-676767676767';
  return {
    accessIntentId,
    accessPath: `/api/v1/organizations/${documentOrganizationId}/documents/${documentId}/versions/${documentVersionId}/accesses/${accessIntentId}?purposeKey=result_review`,
    byteCount: 128,
    documentId,
    documentVersionId,
    expiresAt: '2026-09-28T10:01:00Z',
    mediaType: 'application/pdf',
    purposeKey: 'result_review',
    sha256: 'a'.repeat(64),
  };
}

const aiOrganizationId = '89898989-8989-4898-8989-898989898989';
const aiSessionId = 'abababab-abab-4bab-8bab-abababababab';
const aiEncounterId = 'cdcdcdcd-cdcd-4dcd-8dcd-cdcdcdcdcdcd';
const aiPatientId = 'efefefef-efef-4fef-8fef-efefefefefef';

function aiScreenFixture() {
  return {
    actions: [
      {
        fields: [
          {
            inputType: 'select',
            key: 'decision',
            label: 'Decision',
            options: [
              { label: 'Accept', value: 'accepted' },
              { label: 'Reject', value: 'rejected' },
            ],
            required: true,
          },
        ],
        href: null,
        ifMatchRequired: true,
        key: 'decide-output',
        label: 'Record clinician decision',
        reasonRequired: true,
        style: 'primary',
        targetRequired: true,
      },
    ],
    columns: [{ key: 'patient', label: 'Patient' }],
    generatedAt: '2026-09-28T11:00:00Z',
    metrics: [{ key: 'draftReady', label: 'Drafts awaiting review', tone: 'warning', value: 1 }],
    nextCursor: null,
    notices: [
      {
        detail: 'No output is accepted automatically.',
        title: 'AI output is always a draft',
        tone: 'warning',
      },
    ],
    organizationId: aiOrganizationId,
    pageSize: 25,
    purpose: 'Explicitly decide the latest AI draft.',
    rows: [
      {
        allowedActionKeys: ['decide-output'],
        encounterId: aiEncounterId,
        etag: `"m8:P8-09:${aiSessionId}:4"`,
        id: aiSessionId,
        patientId: aiPatientId,
        revision: 4,
        status: 'draft_ready',
        values: { patient: '••••9999' },
      },
    ],
    screenId: 'P8-09',
    title: 'Clinician review and approval',
  };
}

const carePlanOrganizationId = '16161616-1616-4616-8616-161616161616';
const carePlanId = '17171717-1717-4717-8717-171717171717';
const carePlanVersionId = '18181818-1818-4818-8818-181818181818';
const carePlanEncounterId = '19191919-1919-4919-8919-191919191919';
const carePlanPatientId = '20202020-2020-4020-8020-202020202020';

function carePlanScreenFixture() {
  return {
    actions: [
      {
        fields: [],
        href: null,
        ifMatchRequired: true,
        key: 'submit-plan',
        label: 'Submit for review',
        reasonRequired: true,
        style: 'primary',
        targetRequired: true,
      },
    ],
    columns: [{ key: 'planTitle', label: 'Plan' }],
    generatedAt: '2026-09-28T12:00:00Z',
    metrics: [{ key: 'draft', label: 'Draft plans', tone: 'warning', value: 1 }],
    nextCursor: null,
    notices: [],
    organizationId: carePlanOrganizationId,
    pageSize: 25,
    purpose: 'Freeze the exact complete plan version for accountable review.',
    rows: [
      {
        allowedActionKeys: ['submit-plan'],
        carePlanVersionId,
        encounterId: carePlanEncounterId,
        etag: `"m9:P9-10:${carePlanId}:5"`,
        id: carePlanId,
        patientId: carePlanPatientId,
        revision: 5,
        status: 'draft',
        values: { planTitle: 'Coordinated recovery plan' },
      },
    ],
    screenId: 'P9-10',
    title: 'Clinician approval',
  };
}

const followupOrganizationId = '21212121-2121-4121-8121-212121212121';
const followupPlanId = '22222221-2221-4221-8221-222222222221';
const followupEventId = '23232321-2321-4321-8321-232323232321';
const followupEncounterId = '24242421-2421-4421-8421-242424242421';
const followupPatientId = '25252521-2521-4521-8521-252525252521';

function followupScreenFixture() {
  return {
    actions: [
      {
        fields: [],
        href: null,
        ifMatchRequired: true,
        key: 'record-measurement',
        label: 'Record measurement',
        reasonRequired: true,
        style: 'primary',
        targetRequired: true,
      },
    ],
    columns: [{ key: 'eventType', label: 'Event' }],
    generatedAt: '2026-09-28T12:30:00Z',
    metrics: [{ key: 'pending', label: 'Pending follow-ups', tone: 'warning', value: 1 }],
    nextCursor: null,
    notices: [],
    organizationId: followupOrganizationId,
    pageSize: 25,
    purpose: 'Record attributed outcome evidence.',
    rows: [
      {
        allowedActionKeys: ['record-measurement'],
        encounterId: followupEncounterId,
        etag: `"m10:P10-04:${followupEventId}:0"`,
        followupEventId,
        followupPlanId,
        id: followupEventId,
        patientId: followupPatientId,
        revision: 0,
        status: 'scheduled',
        values: { eventType: 'scheduled' },
      },
    ],
    screenId: 'P10-04',
    title: 'Measures',
  };
}

const billingOrganizationId = '31313131-3131-4131-8131-313131313131';
const billingInvoiceId = '32323232-3232-4232-8232-323232323232';
const billingPatientId = '33333332-3332-4332-8332-333333333332';

function billingScreenFixture() {
  return {
    actions: [
      {
        fields: [],
        href: null,
        ifMatchRequired: true,
        key: 'record-payment',
        label: 'Record payment',
        reasonRequired: true,
        style: 'primary',
        targetRequired: true,
      },
    ],
    columns: [{ key: 'balanceMinor', label: 'Balance' }],
    generatedAt: '2026-09-28T13:00:00Z',
    metrics: [{ key: 'openInvoices', label: 'Open invoices', tone: 'warning', value: 1 }],
    nextCursor: null,
    notices: [],
    organizationId: billingOrganizationId,
    pageSize: 25,
    purpose: 'Record exact non-card settlement evidence.',
    rows: [
      {
        allowedActionKeys: ['record-payment'],
        etag: `"m11:P11-06:${billingInvoiceId}:2"`,
        id: billingInvoiceId,
        invoiceId: billingInvoiceId,
        patientId: billingPatientId,
        revision: 2,
        status: 'partially_paid',
        values: { balanceMinor: '18600', currency: 'INR' },
      },
    ],
    screenId: 'P11-06',
    title: 'Payment',
  };
}

const reportingOrganizationId = '41414141-4141-4141-8141-414141414141';
const reportingRunId = '42424242-4242-4242-8242-424242424242';

function reportingScreenFixture() {
  return {
    actions: [
      {
        fields: [],
        href: null,
        ifMatchRequired: true,
        key: 'request-report-export',
        label: 'Request export',
        reasonRequired: true,
        style: 'primary',
        targetRequired: true,
      },
    ],
    columns: [{ key: 'reportFamily', label: 'Report family' }],
    generatedAt: '2026-09-28T14:00:00Z',
    metrics: [{ key: 'completedRuns', label: 'Completed runs', tone: 'success', value: 1 }],
    nextCursor: null,
    notices: [],
    organizationId: reportingOrganizationId,
    pageSize: 25,
    purpose: 'Request an exact aggregate report export.',
    rows: [
      {
        allowedActionKeys: ['request-report-export'],
        etag: `"m12:P12-09:${reportingRunId}:1"`,
        id: reportingRunId,
        reportRunId: reportingRunId,
        revision: 1,
        status: 'completed',
        values: { artifact: 'report_run', reportFamily: 'operational' },
      },
    ],
    screenId: 'P12-09',
    title: 'Scheduled exports',
  };
}

const integrationOrganizationId = '51515151-5151-4151-8151-515151515151';
const integrationConnectionId = '52525252-5252-4252-8252-525252525252';

function integrationScreenFixture() {
  return {
    actions: [
      {
        fields: [],
        href: null,
        ifMatchRequired: true,
        key: 'validate-connection',
        label: 'Validate configuration',
        reasonRequired: true,
        style: 'primary',
        targetRequired: true,
      },
    ],
    columns: [{ key: 'providerKind', label: 'Provider kind' }],
    generatedAt: '2026-09-28T15:00:00Z',
    metrics: [{ key: 'configuredConnections', label: 'Configured', tone: 'info', value: 1 }],
    nextCursor: null,
    notices: [],
    organizationId: integrationOrganizationId,
    pageSize: 25,
    purpose: 'Review secret-free integration configuration.',
    rows: [
      {
        allowedActionKeys: ['validate-connection'],
        connectionId: integrationConnectionId,
        etag: `"m13:P13-02:${integrationConnectionId}:0"`,
        id: integrationConnectionId,
        revision: 0,
        status: 'draft',
        values: { providerKind: 'fhir', profilePackage: 'careos.base' },
      },
    ],
    screenId: 'P13-02',
    title: 'FHIR endpoints',
  };
}

function validCsrfResponse() {
  return jsonResponse({
    headerName: 'X-XSRF-TOKEN',
    parameterName: '_csrf',
    token: 'valid-csrf-token-123456',
  });
}

describe('CareOsApiClient', () => {
  it('sends credentialed correlated reads and exposes a strong ETag', async () => {
    const fetcher = mockFetch(
      jsonResponse(
        {
          edition: 'Foundation',
          generatedAt: '2026-09-14T00:00:00Z',
          modules: ['M1'],
          product: 'CareOS',
          screenCount: 185,
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

  it('sends bounded Module 2 screen queries and accepts an exactly bound projection', async () => {
    const fetcher = mockFetch(jsonResponse(workforceScreenFixture()));
    const client = createCareOsApiClient({
      baseUrl: '/api',
      correlationIdFactory: correlationIdFactory(),
      fetch: fetcher,
    });

    const result = await client.getWorkforceScreen(workforceOrganizationId, 'M2-24', {
      memberId: workforceMemberId,
      q: '  care team  ',
      status: 'active',
      limit: 25,
      cursor: 'cursor_1',
    });

    expect(result).toMatchObject({ ok: true, status: 200 });
    const [url, init] = fetcher.mock.calls[0]!;
    expect(url).toBe(
      '/api/v1/organizations/' +
        workforceOrganizationId +
        '/workforce/screens/M2-24?memberId=' +
        workforceMemberId +
        '&q=care+team&status=active&limit=25&cursor=cursor_1',
    );
    expect(init?.method).toBe('GET');
    expect(init?.credentials).toBe('include');
    expect(new Headers(init?.headers).get('X-Correlation-Id')).toBe('request-1');
  });

  it('binds a workforce impact preview to the selected row revision and strong ETag', async () => {
    const response = {
      screenId: 'M2-24',
      actionKey: 'request-offboarding',
      targetId: workforceRowId,
      revision: 7,
      digest: 'b'.repeat(64),
      token: 'c'.repeat(64),
      expiresAt: '2099-09-25T08:10:00Z',
      blocked: false,
      items: [
        {
          code: 'historical_attribution_preserved',
          tone: 'impact',
          detail: 'Historical attribution remains available.',
          affectedCount: 1,
        },
      ],
    };
    const fetcher = mockFetch(validCsrfResponse(), jsonResponse(response));
    const client = createCareOsApiClient({
      baseUrl: '/api',
      correlationIdFactory: correlationIdFactory(),
      fetch: fetcher,
    });
    const request = {
      targetId: workforceRowId,
      memberId: workforceMemberId,
      reason: 'Approved workforce transition CARE-42',
      fields: { accessAction: 'revoke_at_effective' },
      evidenceIds: [],
    };
    const etag = '"m2:M2-24:' + workforceRowId + ':7"';

    const result = await client.previewWorkforceImpact(
      workforceOrganizationId,
      'M2-24',
      'request-offboarding',
      request,
      etag,
      7,
    );

    expect(result).toMatchObject({ ok: true, status: 200 });
    expect(fetcher).toHaveBeenCalledTimes(2);
    const [url, init] = fetcher.mock.calls[1]!;
    const headers = new Headers(init?.headers);
    expect(url).toBe(
      '/api/v1/organizations/' +
        workforceOrganizationId +
        '/workforce/screens/M2-24/actions/request-offboarding/impact-preview',
    );
    expect(init?.method).toBe('POST');
    expect(init?.body).toBe(JSON.stringify(request));
    expect(headers.get('If-Match')).toBe(etag);
    expect(headers.get('X-XSRF-TOKEN')).toBe('valid-csrf-token-123456');
  });

  it('normalizes and purpose-binds restricted workforce evidence access', async () => {
    const evidenceId = '55555555-5555-4555-8555-555555555555';
    const response = {
      evidenceId,
      memberId: workforceMemberId,
      occurredAt: '2026-09-25T08:00:00Z',
      actorId: workforceRowId,
      actorKind: 'service',
      operation: 'm2.offboarding.execute',
      eventName: 'workforce.offboarding.completed',
      schemaVersion: 1,
      subjectType: 'workforce_offboarding_request',
      subjectId: '66666666-6666-4666-8666-666666666666',
      correlationId: 'workforce-evidence-transport-001',
      projection: 'member-evidence-detail-v1',
      purposeCode: 'workforce_operations',
      redactionPolicyVersion: 'workforce-evidence-v1',
      payload: { state: 'completed' },
    };
    const fetcher = mockFetch(validCsrfResponse(), jsonResponse(response));
    const client = createCareOsApiClient({
      baseUrl: '/api',
      correlationIdFactory: correlationIdFactory(),
      fetch: fetcher,
    });

    const result = await client.accessWorkforceEvidence(
      workforceOrganizationId,
      evidenceId,
      {
        memberId: workforceMemberId,
        projection: 'member-evidence-detail-v1',
        purposeCode: 'workforce_operations',
        reason: '  Review approved workforce transition evidence  ',
      },
      'evidence-access:11111111-1111-4111-8111-111111111111',
    );

    expect(result).toMatchObject({ ok: true, status: 200 });
    const [url, init] = fetcher.mock.calls[1]!;
    const headers = new Headers(init?.headers);
    expect(url).toBe(
      '/api/v1/organizations/' +
        workforceOrganizationId +
        '/workforce/evidence/' +
        evidenceId +
        '/accesses',
    );
    expect(init?.method).toBe('POST');
    expect(headers.get('Idempotency-Key')).toBe(
      'evidence-access:11111111-1111-4111-8111-111111111111',
    );
    expect(JSON.parse(String(init?.body))).toEqual({
      memberId: workforceMemberId,
      projection: 'member-evidence-detail-v1',
      purposeCode: 'workforce_operations',
      reason: 'Review approved workforce transition evidence',
    });
  });

  it('sends bounded Module 4 context queries and accepts an exactly bound projection', async () => {
    const fetcher = mockFetch(jsonResponse(schedulingScreenFixture()));
    const client = createCareOsApiClient({
      baseUrl: '/api',
      correlationIdFactory: correlationIdFactory(),
      fetch: fetcher,
    });

    const result = await client.getSchedulingScreen(schedulingOrganizationId, 'P4-11', {
      appointmentId: schedulingAppointmentId,
      limit: 25,
      patientId: schedulingPatientId,
      q: '  physiotherapy  ',
      requestId: schedulingRequestId,
      status: 'held',
    });

    expect(result).toMatchObject({ ok: true, status: 200 });
    const [url, init] = fetcher.mock.calls[0]!;
    expect(url).toBe(
      `/api/v1/organizations/${schedulingOrganizationId}/scheduling/screens/P4-11` +
        `?patientId=${schedulingPatientId}&appointmentId=${schedulingAppointmentId}` +
        `&requestId=${schedulingRequestId}&q=physiotherapy&status=held&limit=25`,
    );
    expect(init?.method).toBe('GET');
    expect(init?.credentials).toBe('include');
  });

  it('submits a revision and idempotency-bound Module 4 action', async () => {
    const fetcher = mockFetch(validCsrfResponse(), jsonResponse(schedulingScreenFixture()));
    const client = createCareOsApiClient({
      baseUrl: '/api',
      correlationIdFactory: correlationIdFactory(),
      fetch: fetcher,
    });
    const body = {
      appointmentId: null,
      fields: {},
      patientId: schedulingPatientId,
      reason: 'Confirmed after reviewing the exact held slot.',
      requestId: schedulingRequestId,
      targetId: schedulingRequestId,
    };
    const etag = `"m4:P4-11:${schedulingRequestId}:3"`;

    const result = await client.performSchedulingAction(
      schedulingOrganizationId,
      'P4-11',
      'confirm-appointment',
      body,
      etag,
      'm4:confirm:test-request-0001',
    );

    expect(result).toMatchObject({ ok: true, status: 200 });
    const [url, init] = fetcher.mock.calls[1]!;
    const headers = new Headers(init?.headers);
    expect(url).toBe(
      `/api/v1/organizations/${schedulingOrganizationId}/scheduling/screens/P4-11/actions/confirm-appointment`,
    );
    expect(init?.method).toBe('POST');
    expect(headers.get('If-Match')).toBe(etag);
    expect(headers.get('Idempotency-Key')).toBe('m4:confirm:test-request-0001');
    expect(headers.get('X-XSRF-TOKEN')).toBe('valid-csrf-token-123456');
    expect(JSON.parse(String(init?.body))).toEqual(body);
  });

  it('sends bounded Module 5 context queries and accepts an exactly bound projection', async () => {
    const fetcher = mockFetch(jsonResponse(encounterScreenFixture()));
    const client = createCareOsApiClient({
      baseUrl: '/api',
      correlationIdFactory: correlationIdFactory(),
      fetch: fetcher,
    });

    const result = await client.getEncounterScreen(encounterOrganizationId, 'P5-03', {
      appointmentId: encounterAppointmentId,
      encounterId,
      episodeId: encounterEpisodeId,
      limit: 25,
      patientId: encounterPatientId,
      q: '  follow-up  ',
      status: 'in_progress',
    });

    expect(result).toMatchObject({ ok: true, status: 200 });
    const [url, init] = fetcher.mock.calls[0]!;
    expect(url).toBe(
      `/api/v1/organizations/${encounterOrganizationId}/encounters/screens/P5-03` +
        `?patientId=${encounterPatientId}&episodeId=${encounterEpisodeId}` +
        `&encounterId=${encounterId}&appointmentId=${encounterAppointmentId}` +
        '&q=follow-up&status=in_progress&limit=25',
    );
    expect(init?.method).toBe('GET');
    expect(init?.credentials).toBe('include');
  });

  it('submits a revision and idempotency-bound Module 5 action', async () => {
    const fetcher = mockFetch(validCsrfResponse(), jsonResponse(encounterScreenFixture()));
    const client = createCareOsApiClient({
      baseUrl: '/api',
      correlationIdFactory: correlationIdFactory(),
      fetch: fetcher,
    });
    const body = {
      appointmentId: encounterAppointmentId,
      encounterId,
      episodeId: encounterEpisodeId,
      fields: {},
      patientId: encounterPatientId,
      reason: 'Completed after reviewing signed notes and safety tasks.',
      targetId: encounterId,
    };
    const etag = `"m5:P5-03:${encounterId}:8"`;

    const result = await client.performEncounterAction(
      encounterOrganizationId,
      'P5-03',
      'complete-encounter',
      body,
      etag,
      'm5:complete:test-request-0001',
    );

    expect(result).toMatchObject({ ok: true, status: 200 });
    const [url, init] = fetcher.mock.calls[1]!;
    const headers = new Headers(init?.headers);
    expect(url).toBe(
      `/api/v1/organizations/${encounterOrganizationId}/encounters/screens/P5-03/actions/complete-encounter`,
    );
    expect(init?.method).toBe('POST');
    expect(headers.get('If-Match')).toBe(etag);
    expect(headers.get('Idempotency-Key')).toBe('m5:complete:test-request-0001');
    expect(headers.get('X-XSRF-TOKEN')).toBe('valid-csrf-token-123456');
    expect(JSON.parse(String(init?.body))).toEqual(body);
  });

  it('sends bounded Module 6 context queries and accepts an exactly bound projection', async () => {
    const fetcher = mockFetch(jsonResponse(assessmentScreenFixture()));
    const client = createCareOsApiClient({
      baseUrl: '/api',
      correlationIdFactory: correlationIdFactory(),
      fetch: fetcher,
    });

    const result = await client.getAssessmentScreen(assessmentOrganizationId, 'COS-27', {
      assessmentSessionId,
      encounterId: assessmentEncounterId,
      limit: 25,
      patientId: assessmentPatientId,
      q: '  closeout  ',
      status: 'signed',
    });

    expect(result).toMatchObject({ ok: true, status: 200 });
    const [url, init] = fetcher.mock.calls[0]!;
    expect(url).toBe(
      `/api/v1/organizations/${assessmentOrganizationId}/assessments/screens/COS-27` +
        `?patientId=${assessmentPatientId}&encounterId=${assessmentEncounterId}` +
        `&assessmentSessionId=${assessmentSessionId}&q=closeout&status=signed&limit=25`,
    );
    expect(init?.method).toBe('GET');
    expect(init?.credentials).toBe('include');
  });

  it('submits a revision and idempotency-bound Module 6 action', async () => {
    const fetcher = mockFetch(validCsrfResponse(), jsonResponse(assessmentScreenFixture()));
    const client = createCareOsApiClient({
      baseUrl: '/api',
      correlationIdFactory: correlationIdFactory(),
      fetch: fetcher,
    });
    const body = {
      assessmentSessionId,
      encounterId: assessmentEncounterId,
      fields: {},
      patientId: assessmentPatientId,
      reason: 'Completed after review of signatures and all safety evidence.',
      targetId: assessmentSessionId,
    };
    const etag = `"m6:COS-27:${assessmentSessionId}:9"`;

    const result = await client.performAssessmentAction(
      assessmentOrganizationId,
      'COS-27',
      'complete-assessment',
      body,
      etag,
      'm6:complete:test-request-0001',
    );

    expect(result).toMatchObject({ ok: true, status: 200 });
    const [url, init] = fetcher.mock.calls[1]!;
    const headers = new Headers(init?.headers);
    expect(url).toBe(
      `/api/v1/organizations/${assessmentOrganizationId}/assessments/screens/COS-27/actions/complete-assessment`,
    );
    expect(init?.method).toBe('POST');
    expect(headers.get('If-Match')).toBe(etag);
    expect(headers.get('Idempotency-Key')).toBe('m6:complete:test-request-0001');
    expect(headers.get('X-XSRF-TOKEN')).toBe('valid-csrf-token-123456');
    expect(JSON.parse(String(init?.body))).toEqual(body);
  });

  it('sends bounded Module 7 context queries and validates the exact projection', async () => {
    const fetcher = mockFetch(jsonResponse(documentScreenFixture()));
    const client = createCareOsApiClient({
      baseUrl: '/api',
      correlationIdFactory: correlationIdFactory(),
      fetch: fetcher,
    });

    const result = await client.getDocumentScreen(documentOrganizationId, 'P7-04', {
      diagnosticReportId,
      documentId,
      limit: 25,
      patientId: documentPatientId,
      q: '  laboratory  ',
      status: 'clean',
    });

    expect(result).toMatchObject({ ok: true, status: 200 });
    const [url, init] = fetcher.mock.calls[0]!;
    expect(url).toBe(
      `/api/v1/organizations/${documentOrganizationId}/documents/screens/P7-04` +
        `?patientId=${documentPatientId}&documentId=${documentId}` +
        `&diagnosticReportId=${diagnosticReportId}&q=laboratory&status=clean&limit=25`,
    );
    expect(init?.method).toBe('GET');
    expect(init?.credentials).toBe('include');
  });

  it('submits a revision and idempotency-bound Module 7 action', async () => {
    const fetcher = mockFetch(validCsrfResponse(), jsonResponse(documentScreenFixture()));
    const client = createCareOsApiClient({
      baseUrl: '/api',
      correlationIdFactory: correlationIdFactory(),
      fetch: fetcher,
    });
    const body = {
      diagnosticReportId,
      documentId,
      documentVersionId,
      fields: { categoryKey: 'laboratory' },
      patientId: documentPatientId,
      reason: 'Classified against the verified source and patient context.',
      targetId: documentId,
    };
    const etag = `"m7:P7-04:${documentId}:4"`;

    const result = await client.performDocumentAction(
      documentOrganizationId,
      'P7-04',
      'classify-document',
      body,
      etag,
      'm7:classify:test-request-0001',
    );

    expect(result).toMatchObject({ ok: true, status: 200 });
    const [url, init] = fetcher.mock.calls[1]!;
    const headers = new Headers(init?.headers);
    expect(url).toBe(
      `/api/v1/organizations/${documentOrganizationId}/documents/screens/P7-04/actions/classify-document`,
    );
    expect(init?.method).toBe('POST');
    expect(headers.get('If-Match')).toBe(etag);
    expect(headers.get('Idempotency-Key')).toBe('m7:classify:test-request-0001');
    expect(headers.get('X-XSRF-TOKEN')).toBe('valid-csrf-token-123456');
    expect(JSON.parse(String(init?.body))).toEqual(body);
  });

  it('sends bounded Module 8 context queries and validates the exact projection', async () => {
    const fetcher = mockFetch(jsonResponse(aiScreenFixture()));
    const client = createCareOsApiClient({
      baseUrl: '/api',
      correlationIdFactory: correlationIdFactory(),
      fetch: fetcher,
    });

    const result = await client.getAiScreen(aiOrganizationId, 'P8-09', {
      aiSessionId,
      encounterId: aiEncounterId,
      limit: 25,
      patientId: aiPatientId,
      q: '  review  ',
      status: 'draft_ready',
    });

    expect(result).toMatchObject({ ok: true, status: 200 });
    const [url, init] = fetcher.mock.calls[0]!;
    expect(url).toBe(
      `/api/v1/organizations/${aiOrganizationId}/ai/screens/P8-09` +
        `?patientId=${aiPatientId}&encounterId=${aiEncounterId}&aiSessionId=${aiSessionId}` +
        '&q=review&status=draft_ready&limit=25',
    );
    expect(init?.method).toBe('GET');
    expect(init?.credentials).toBe('include');
  });

  it('submits a revision and idempotency-bound Module 8 clinician decision', async () => {
    const fetcher = mockFetch(validCsrfResponse(), jsonResponse(aiScreenFixture()));
    const client = createCareOsApiClient({
      baseUrl: '/api',
      correlationIdFactory: correlationIdFactory(),
      fetch: fetcher,
    });
    const body = {
      fields: {
        decision: 'accepted',
        reviewerPractitionerId: '12121212-1212-4212-8212-121212121212',
      },
      reason: 'Clinician reviewed the exact draft and all cited source evidence.',
      targetId: aiSessionId,
    };
    const etag = `"m8:P8-09:${aiSessionId}:4"`;

    const result = await client.performAiAction(
      aiOrganizationId,
      'P8-09',
      'decide-output',
      body,
      etag,
      'm8:decide:test-request-0001',
    );

    expect(result).toMatchObject({ ok: true, status: 200 });
    const [url, init] = fetcher.mock.calls[1]!;
    const headers = new Headers(init?.headers);
    expect(url).toBe(
      `/api/v1/organizations/${aiOrganizationId}/ai/screens/P8-09/actions/decide-output`,
    );
    expect(init?.method).toBe('POST');
    expect(headers.get('If-Match')).toBe(etag);
    expect(headers.get('Idempotency-Key')).toBe('m8:decide:test-request-0001');
    expect(headers.get('X-XSRF-TOKEN')).toBe('valid-csrf-token-123456');
    expect(JSON.parse(String(init?.body))).toEqual(body);
  });

  it('sends bounded Module 9 context queries and validates the exact plan projection', async () => {
    const fetcher = mockFetch(jsonResponse(carePlanScreenFixture()));
    const client = createCareOsApiClient({
      baseUrl: '/api',
      correlationIdFactory: correlationIdFactory(),
      fetch: fetcher,
    });

    const result = await client.getCarePlanScreen(carePlanOrganizationId, 'P9-10', {
      carePlanId,
      encounterId: carePlanEncounterId,
      limit: 25,
      patientId: carePlanPatientId,
      q: '  coordinated  ',
      status: 'draft',
    });

    expect(result).toMatchObject({ ok: true, status: 200 });
    const [url, init] = fetcher.mock.calls[0]!;
    expect(url).toBe(
      `/api/v1/organizations/${carePlanOrganizationId}/care-plans/screens/P9-10` +
        `?patientId=${carePlanPatientId}&encounterId=${carePlanEncounterId}` +
        `&carePlanId=${carePlanId}&q=coordinated&status=draft&limit=25`,
    );
    expect(init?.method).toBe('GET');
    expect(init?.credentials).toBe('include');
  });

  it('submits a revision and idempotency-bound Module 9 plan transition', async () => {
    const fetcher = mockFetch(validCsrfResponse(), jsonResponse(carePlanScreenFixture()));
    const client = createCareOsApiClient({
      baseUrl: '/api',
      correlationIdFactory: correlationIdFactory(),
      fetch: fetcher,
    });
    const body = {
      fields: {},
      reason: 'Complete ownership, consent and safety evidence reviewed.',
      targetId: carePlanId,
    };
    const etag = `"m9:P9-10:${carePlanId}:5"`;

    const result = await client.performCarePlanAction(
      carePlanOrganizationId,
      'P9-10',
      'submit-plan',
      body,
      etag,
      'm9:submit:test-request-0001',
    );

    expect(result).toMatchObject({ ok: true, status: 200 });
    const [url, init] = fetcher.mock.calls[1]!;
    const headers = new Headers(init?.headers);
    expect(url).toBe(
      `/api/v1/organizations/${carePlanOrganizationId}/care-plans/screens/P9-10/actions/submit-plan`,
    );
    expect(init?.method).toBe('POST');
    expect(headers.get('If-Match')).toBe(etag);
    expect(headers.get('Idempotency-Key')).toBe('m9:submit:test-request-0001');
    expect(headers.get('X-XSRF-TOKEN')).toBe('valid-csrf-token-123456');
    expect(JSON.parse(String(init?.body))).toEqual(body);
  });

  it('sends bounded Module 10 context queries and validates exact follow-up evidence', async () => {
    const fetcher = mockFetch(jsonResponse(followupScreenFixture()));
    const client = createCareOsApiClient({
      baseUrl: '/api',
      correlationIdFactory: correlationIdFactory(),
      fetch: fetcher,
    });

    const result = await client.getFollowupScreen(followupOrganizationId, 'P10-04', {
      encounterId: followupEncounterId,
      followupPlanId,
      limit: 25,
      patientId: followupPatientId,
      q: '  recovery  ',
      status: 'scheduled',
    });

    expect(result).toMatchObject({ ok: true, status: 200 });
    const [url, init] = fetcher.mock.calls[0]!;
    expect(url).toBe(
      `/api/v1/organizations/${followupOrganizationId}/followups/screens/P10-04` +
        `?patientId=${followupPatientId}&encounterId=${followupEncounterId}` +
        `&followupPlanId=${followupPlanId}&q=recovery&status=scheduled&limit=25`,
    );
    expect(init?.method).toBe('GET');
    expect(init?.credentials).toBe('include');
  });

  it('submits revision-bound Module 10 measurement evidence', async () => {
    const fetcher = mockFetch(validCsrfResponse(), jsonResponse(followupScreenFixture()));
    const client = createCareOsApiClient({
      baseUrl: '/api',
      correlationIdFactory: correlationIdFactory(),
      fetch: fetcher,
    });
    const body = {
      fields: {
        numericValue: '15',
        outcomeDefinitionId: '26262621-2621-4621-8621-262626262621',
      },
      reason: 'Record and evaluate the attributed outcome measurement.',
      targetId: followupEventId,
    };
    const etag = `"m10:P10-04:${followupEventId}:0"`;

    const result = await client.performFollowupAction(
      followupOrganizationId,
      'P10-04',
      'record-measurement',
      body,
      etag,
      'm10:measure:test-request-0001',
    );

    expect(result).toMatchObject({ ok: true, status: 200 });
    const [url, init] = fetcher.mock.calls[1]!;
    const headers = new Headers(init?.headers);
    expect(url).toBe(
      `/api/v1/organizations/${followupOrganizationId}/followups/screens/P10-04/actions/record-measurement`,
    );
    expect(init?.method).toBe('POST');
    expect(headers.get('If-Match')).toBe(etag);
    expect(headers.get('Idempotency-Key')).toBe('m10:measure:test-request-0001');
    expect(headers.get('X-XSRF-TOKEN')).toBe('valid-csrf-token-123456');
    expect(JSON.parse(String(init?.body))).toEqual(body);
  });

  it('sends bounded Module 11 invoice context and validates financial evidence', async () => {
    const fetcher = mockFetch(jsonResponse(billingScreenFixture()));
    const client = createCareOsApiClient({
      baseUrl: '/api',
      correlationIdFactory: correlationIdFactory(),
      fetch: fetcher,
    });

    const result = await client.getBillingScreen(billingOrganizationId, 'P11-06', {
      invoiceId: billingInvoiceId,
      limit: 25,
      patientId: billingPatientId,
      q: '  invoice  ',
      status: 'partially_paid',
    });

    expect(result).toMatchObject({ ok: true, status: 200 });
    const [url, init] = fetcher.mock.calls[0]!;
    expect(url).toBe(
      `/api/v1/organizations/${billingOrganizationId}/billing/screens/P11-06` +
        `?patientId=${billingPatientId}&invoiceId=${billingInvoiceId}` +
        `&q=invoice&status=partially_paid&limit=25`,
    );
    expect(init?.method).toBe('GET');
    expect(init?.credentials).toBe('include');
  });

  it('submits revision-bound Module 11 settlement evidence and rejects card fields', async () => {
    const fetcher = mockFetch(validCsrfResponse(), jsonResponse(billingScreenFixture()));
    const client = createCareOsApiClient({
      baseUrl: '/api',
      correlationIdFactory: correlationIdFactory(),
      fetch: fetcher,
    });
    const body = {
      fields: {
        amountMinor: '5000',
        occurredAt: '2026-09-28T13:00:00Z',
        paymentReference: 'BANK-SETTLEMENT-0001',
        source: 'manual_bank',
      },
      reason: 'Record verified bank settlement evidence for this invoice.',
      targetId: billingInvoiceId,
    };
    const etag = `"m11:P11-06:${billingInvoiceId}:2"`;

    const result = await client.performBillingAction(
      billingOrganizationId,
      'P11-06',
      'record-payment',
      body,
      etag,
      'm11:payment:test-request-0001',
    );

    expect(result).toMatchObject({ ok: true, status: 200 });
    const [url, init] = fetcher.mock.calls[1]!;
    const headers = new Headers(init?.headers);
    expect(url).toBe(
      `/api/v1/organizations/${billingOrganizationId}/billing/screens/P11-06/actions/record-payment`,
    );
    expect(init?.method).toBe('POST');
    expect(headers.get('If-Match')).toBe(etag);
    expect(headers.get('Idempotency-Key')).toBe('m11:payment:test-request-0001');
    expect(JSON.parse(String(init?.body))).toEqual(body);

    expect(() =>
      client.performBillingAction(
        billingOrganizationId,
        'P11-06',
        'record-payment',
        {
          fields: { paymentReference: '4111 1111 1111 1111' },
          reason: 'This prohibited field must never leave the browser.',
          targetId: billingInvoiceId,
        },
        etag,
        'm11:card:test-request-0001',
      ),
    ).toThrow('prohibited');
    expect(fetcher).toHaveBeenCalledTimes(2);
  });

  it('sends bounded Module 12 exact-run context and validates aggregate evidence', async () => {
    const fetcher = mockFetch(jsonResponse(reportingScreenFixture()));
    const client = createCareOsApiClient({
      baseUrl: '/api',
      correlationIdFactory: correlationIdFactory(),
      fetch: fetcher,
    });

    const result = await client.getReportingScreen(reportingOrganizationId, 'P12-09', {
      limit: 25,
      q: '  operational  ',
      reportRunId: reportingRunId,
      status: 'completed',
    });

    expect(result).toMatchObject({ ok: true, status: 200 });
    const [url, init] = fetcher.mock.calls[0]!;
    expect(url).toBe(
      `/api/v1/organizations/${reportingOrganizationId}/reporting/screens/P12-09` +
        `?reportRunId=${reportingRunId}&q=operational&status=completed&limit=25`,
    );
    expect(init?.method).toBe('GET');
    expect(init?.credentials).toBe('include');
  });

  it('submits revision-bound Module 12 export requests', async () => {
    const fetcher = mockFetch(validCsrfResponse(), jsonResponse(reportingScreenFixture()));
    const client = createCareOsApiClient({
      baseUrl: '/api',
      correlationIdFactory: correlationIdFactory(),
      fetch: fetcher,
    });
    const body = {
      fields: { format: 'csv' },
      reason: 'Request the exact aggregate report for governance review.',
      targetId: reportingRunId,
    };
    const etag = `"m12:P12-09:${reportingRunId}:1"`;

    const result = await client.performReportingAction(
      reportingOrganizationId,
      'P12-09',
      'request-report-export',
      body,
      etag,
      'm12:export:test-request-0001',
    );

    expect(result).toMatchObject({ ok: true, status: 200 });
    const [url, init] = fetcher.mock.calls[1]!;
    const headers = new Headers(init?.headers);
    expect(url).toBe(
      `/api/v1/organizations/${reportingOrganizationId}/reporting/screens/P12-09/actions/request-report-export`,
    );
    expect(init?.method).toBe('POST');
    expect(headers.get('If-Match')).toBe(etag);
    expect(headers.get('Idempotency-Key')).toBe('m12:export:test-request-0001');
    expect(JSON.parse(String(init?.body))).toEqual(body);
  });

  it('sends bounded Module 13 exact-connection context and validates payload-free evidence', async () => {
    const fetcher = mockFetch(jsonResponse(integrationScreenFixture()));
    const client = createCareOsApiClient({
      baseUrl: '/api',
      correlationIdFactory: correlationIdFactory(),
      fetch: fetcher,
    });

    const result = await client.getIntegrationScreen(integrationOrganizationId, 'P13-02', {
      connectionId: integrationConnectionId,
      limit: 25,
      q: '  fhir  ',
      status: 'draft',
    });

    expect(result).toMatchObject({ ok: true, status: 200 });
    const [url, init] = fetcher.mock.calls[0]!;
    expect(url).toBe(
      `/api/v1/organizations/${integrationOrganizationId}/integrations/screens/P13-02` +
        `?connectionId=${integrationConnectionId}&q=fhir&status=draft&limit=25`,
    );
    expect(init?.method).toBe('GET');
    expect(init?.credentials).toBe('include');
  });

  it('submits revision-bound Module 13 actions and blocks raw payload fields', async () => {
    const fetcher = mockFetch(validCsrfResponse(), jsonResponse(integrationScreenFixture()));
    const client = createCareOsApiClient({
      baseUrl: '/api',
      correlationIdFactory: correlationIdFactory(),
      fetch: fetcher,
    });
    const body = {
      fields: {},
      reason: 'Validate this exact version-pinned FHIR connection.',
      targetId: integrationConnectionId,
    };
    const etag = `"m13:P13-02:${integrationConnectionId}:0"`;

    const result = await client.performIntegrationAction(
      integrationOrganizationId,
      'P13-02',
      'validate-connection',
      body,
      etag,
      'm13:connection:test-request-0001',
    );

    expect(result).toMatchObject({ ok: true, status: 200 });
    const [url, init] = fetcher.mock.calls[1]!;
    const headers = new Headers(init?.headers);
    expect(url).toBe(
      `/api/v1/organizations/${integrationOrganizationId}/integrations/screens/P13-02/actions/validate-connection`,
    );
    expect(init?.method).toBe('POST');
    expect(headers.get('If-Match')).toBe(etag);
    expect(headers.get('Idempotency-Key')).toBe('m13:connection:test-request-0001');
    expect(JSON.parse(String(init?.body))).toEqual(body);

    expect(() =>
      client.performIntegrationAction(
        integrationOrganizationId,
        'P13-08',
        'create-connection',
        {
          fields: { webhookBody: '{"patient":"raw"}' },
          reason: 'This prohibited payload must never leave the browser.',
        },
        undefined,
        'm13:payload:test-request-0001',
      ),
    ).toThrow('must not contain secrets, tokens, or raw payloads');
    expect(fetcher).toHaveBeenCalledTimes(2);
  });

  it('uploads Module 7 files as CSRF-protected multipart data without forcing a content type', async () => {
    const fetcher = mockFetch(
      validCsrfResponse(),
      jsonResponse(documentScreenFixture('P7-03'), { status: 201 }),
    );
    const client = createCareOsApiClient({
      baseUrl: '/api',
      correlationIdFactory: correlationIdFactory(),
      fetch: fetcher,
    });
    const file = new File([new Uint8Array([37, 80, 68, 70, 45, 49])], 'result.pdf', {
      type: 'application/pdf',
    });

    const result = await client.uploadDocument(
      documentOrganizationId,
      {
        documentTypeKey: 'diagnostic_report',
        patientId: documentPatientId,
        reason: 'Upload the verified synthetic diagnostic report.',
        sha256: 'a'.repeat(64),
        sourceKey: 'test_laboratory',
        title: 'Synthetic laboratory report',
      },
      file,
      undefined,
      'm7:upload:test-request-0001',
    );

    expect(result).toMatchObject({ ok: true, status: 201 });
    const [url, init] = fetcher.mock.calls[1]!;
    const headers = new Headers(init?.headers);
    expect(url).toBe(`/api/v1/organizations/${documentOrganizationId}/documents`);
    expect(init?.body).toBeInstanceOf(FormData);
    expect(headers.get('Content-Type')).toBeNull();
    expect(headers.get('Idempotency-Key')).toBe('m7:upload:test-request-0001');
    expect(headers.get('X-XSRF-TOKEN')).toBe('valid-csrf-token-123456');
  });

  it('accepts a revision-bound 200 response when Module 7 appends a replacement version', async () => {
    const fetcher = mockFetch(validCsrfResponse(), jsonResponse(documentScreenFixture('P7-03')));
    const client = createCareOsApiClient({
      baseUrl: '/api',
      correlationIdFactory: correlationIdFactory(),
      fetch: fetcher,
    });
    const file = new File([new Uint8Array([37, 80, 68, 70, 45, 50])], 'result-v2.pdf', {
      type: 'application/pdf',
    });
    const etag = `"m7:P7-03:${documentId}:4"`;

    const result = await client.uploadDocument(
      documentOrganizationId,
      {
        documentTypeKey: 'diagnostic_report',
        patientId: documentPatientId,
        reason: 'Append the corrected replacement diagnostic report.',
        replacementDocumentId: documentId,
        sha256: 'b'.repeat(64),
        sourceKey: 'test_laboratory',
        title: 'Corrected synthetic laboratory report',
      },
      file,
      etag,
      'm7:replace:test-request-0001',
    );

    expect(result).toMatchObject({ ok: true, status: 200 });
    const headers = new Headers(fetcher.mock.calls[1]?.[1]?.headers);
    expect(headers.get('If-Match')).toBe(etag);
  });

  it('creates an exact purpose-bound Module 7 access intent', async () => {
    const fetcher = mockFetch(validCsrfResponse(), jsonResponse(documentAccessFixture()));
    const client = createCareOsApiClient({
      baseUrl: '/api',
      correlationIdFactory: correlationIdFactory(),
      fetch: fetcher,
    });

    const result = await client.createDocumentAccess(
      documentOrganizationId,
      documentId,
      {
        documentVersionId,
        purposeKey: 'result_review',
        reason: 'Review the clean diagnostic result evidence.',
      },
      'm7:access:test-request-0001',
    );

    expect(result).toMatchObject({ ok: true, status: 200 });
    const [url, init] = fetcher.mock.calls[1]!;
    expect(url).toBe(
      `/api/v1/organizations/${documentOrganizationId}/documents/${documentId}/accesses`,
    );
    expect(JSON.parse(String(init?.body))).toEqual({
      documentVersionId,
      purposeKey: 'result_review',
      reason: 'Review the clean diagnostic result evidence.',
    });
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
