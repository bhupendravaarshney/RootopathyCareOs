import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page, type Route } from '@playwright/test';

const user = {
  displayName: 'Asha Verma',
  email: 'asha@example.test',
  id: '11111111-1111-4111-8111-111111111111',
};
const organization = {
  displayName: 'North Clinic',
  id: '22222222-2222-4222-8222-222222222222',
  roleKeys: ['organization_owner'],
  selected: true,
  status: 'active',
};
const readinessEvaluatedAt = new Date(Date.now() - 60_000);
readinessEvaluatedAt.setMilliseconds(0);
const readinessExpiresAt = new Date(readinessEvaluatedAt.getTime() + 15 * 60_000);
const readinessGate = (
  key: string,
  label: string,
  outcome: 'blocked' | 'complete' | 'not_applicable' | 'warning',
  detail: string,
  href: string,
) => ({
  detail,
  evidenceReferences: [],
  href,
  key,
  label,
  outcome,
  reasonCode: 'm1.readiness.test_fixture',
  remediationCode: 'm1.remediation.test_fixture',
  version: 'm1-readiness-v1',
});
const organizationReadiness = {
  activeMemberships: 2,
  blockedGates: 10,
  catalogueVersion: 'm1-readiness-v1',
  completedGates: 3,
  draftFacilityCount: 1,
  evaluatedAt: readinessEvaluatedAt.toISOString(),
  expiresAt: readinessExpiresAt.toISOString(),
  facilityCount: 1,
  gates: [
    readinessGate(
      'organization.profile.complete',
      'Organization profile',
      'complete',
      'The approved organization profile is complete at the current revision.',
      '#/M1-07',
    ),
    readinessGate(
      'organization.identifier.primary_verified',
      'Primary registration identifier',
      'blocked',
      'A verified primary registration identifier is required.',
      '#/M1-08',
    ),
    readinessGate(
      'organization.contact.coverage',
      'Address and contact coverage',
      'blocked',
      'Registered address and operational contact coverage are missing.',
      '#/M1-09',
    ),
    readinessGate(
      'organization.governance.coverage',
      'Governance responsibility coverage',
      'blocked',
      'Required governance responsibilities are missing.',
      '#/M1-11',
    ),
    readinessGate(
      'access.final_owner',
      'Final owner protection',
      'complete',
      'An active indefinite owner remains after every open demotion.',
      '#/M1-20',
    ),
    readinessGate(
      'access.mfa_enforced',
      'Mandatory-role MFA',
      'complete',
      'Every mandatory-role account has MFA enabled.',
      '#/M1-03',
    ),
    readinessGate(
      'network.facility.minimum',
      'Minimum eligible facility',
      'blocked',
      'No eligible facility has complete approved configuration.',
      '#/M1-12',
    ),
    readinessGate(
      'network.hierarchy.valid',
      'Network hierarchy',
      'blocked',
      'The network hierarchy evaluator is unavailable.',
      '#/M1-14',
    ),
    readinessGate(
      'network.hours.valid',
      'Operating hours',
      'blocked',
      'Approved operating-hours evaluation is unavailable.',
      '#/M1-16',
    ),
    readinessGate(
      'service.catalogue.active',
      'Active service catalogue',
      'blocked',
      'An eligible service catalogue is unavailable.',
      '#/M1-17',
    ),
    readinessGate(
      'service.assignment.valid',
      'Service assignments',
      'warning',
      'Service delivery has not been declared.',
      '#/M1-18',
    ),
    readinessGate(
      'identifier.scheme.active',
      'Identifier scheme',
      'not_applicable',
      'Identifier issuance is not declared.',
      '#/M1-19',
    ),
    readinessGate(
      'configuration.integrity',
      'Configuration integrity',
      'blocked',
      'Versioned configuration integrity is unavailable.',
      '#/M1-21',
    ),
    readinessGate(
      'governance.registry.active',
      'Governance registries',
      'blocked',
      'The full approved registry set is not active.',
      '#/M1-21',
    ),
    readinessGate(
      'platform.dependencies.ready',
      'Required platform dependencies',
      'blocked',
      'Fresh required dependency readiness is unavailable.',
      '#/M1-21',
    ),
  ],
  lifecycleStatus: 'active',
  notApplicableGates: 1,
  organizationId: organization.id,
  organizationRevision: 4,
  totalGates: 15,
  warningGates: 1,
};
const organizationProfile = {
  countryCode: 'IN',
  displayName: 'North Clinic',
  editable: true,
  legalName: 'North Clinic Private Limited',
  lifecycleStatus: 'active',
  locale: 'en-IN',
  lockVersion: 4,
  organizationId: organization.id,
  organizationType: 'care_provider',
  timezone: 'Asia/Kolkata',
  tradingName: null,
  updatedAt: '2026-09-16T08:00:00Z',
};
const organizationIdentifier = {
  assigningAuthority: 'National Provider Registry',
  availableActions: ['edit', 'verify'],
  createdAt: '2026-09-16T08:00:00Z',
  effectiveFrom: '2026-09-16T08:00:00Z',
  effectiveTo: null,
  evidenceReference: null,
  expiryDate: null,
  identifierId: '44444444-4444-4444-8444-444444444444',
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
const organizationIdentifiers = {
  canCreate: true,
  items: [organizationIdentifier],
  organizationId: organization.id,
  types: [
    {
      displayName: 'Registration identifier',
      jurisdictionCountryCode: null,
      key: 'registration',
      primaryRequired: true,
    },
  ],
};
const organizationAddress = {
  addressId: '66666666-6666-4666-8666-666666666666',
  addressLines: ['42 Care Street', 'Andheri East'],
  addressType: 'registered',
  availableActions: ['supersede', 'end'],
  countryCode: 'IN',
  createdAt: '2026-09-16T08:00:00Z',
  effectiveFrom: '2026-09-16T08:00:00Z',
  effectiveTo: null,
  isPrimary: true,
  locality: 'Mumbai',
  lockVersion: 0,
  postcode: '400069',
  region: 'Maharashtra',
  status: 'active',
  supersedesId: null,
  updatedAt: '2026-09-16T08:00:00Z',
  validationSource: 'Approved postal source',
  validationStatus: 'validated',
};
const organizationContact = {
  availableActions: ['verify', 'supersede', 'end'],
  channel: 'email',
  contactId: '77777777-7777-4777-8777-777777777770',
  createdAt: '2026-09-16T08:00:00Z',
  effectiveFrom: '2026-09-16T08:00:00Z',
  effectiveTo: null,
  isPreferred: true,
  isPrimary: true,
  lockVersion: 0,
  maskedValue: 'o***@***.org',
  purpose: 'operational',
  purposeDisplayName: 'Operational contact',
  status: 'active',
  supersedesId: null,
  updatedAt: '2026-09-16T08:00:00Z',
  verificationStatus: 'unverified',
};
const organizationContacts = {
  addressTypes: ['registered', 'postal', 'service', 'billing'],
  addresses: [organizationAddress],
  canCreate: true,
  contacts: [organizationContact],
  organizationId: organization.id,
  purposes: [
    {
      displayName: 'Operational contact',
      key: 'operational',
      publicProjectionAllowed: false,
    },
  ],
};
const organizationInternationalSettings = {
  canSchedule: true,
  editable: true,
  evaluatedAt: '2026-09-18T08:00:00Z',
  impactRules: [
    ['countryCode', 'jurisdiction_impact', 'warning'],
    ['timezone', 'timezone_impact', 'warning'],
    ['locale', 'locale_format_impact', 'information'],
    ['language', 'language_impact', 'information'],
    ['currencyCode', 'currency_impact', 'warning'],
    ['weekStart', 'week_start_impact', 'information'],
  ].map(([field, code, severity]) => ({
    code: `m1.settings.${code}`,
    description: `${field} uses the approved impact rule.`,
    field,
    severity,
  })),
  lockVersion: 4,
  organizationId: organization.id,
  versions: [
    {
      countryCode: 'IN',
      currencyCode: 'INR',
      effectiveFrom: '2026-09-16T08:00:00Z',
      effectiveTo: null,
      language: 'en',
      lifecycle: 'default',
      locale: 'en-IN',
      lockVersion: 4,
      settingsId: null,
      source: 'organization_default',
      supersedesId: null,
      timezone: 'Asia/Kolkata',
      updatedAt: '2026-09-16T08:00:00Z',
      weekStart: 'SUNDAY',
      formatPreview: {
        localeLibraryDerived: true,
        sampleCurrency: '₹1,234.56',
        sampleDate: '15 Jun 2030',
        sampleNumber: '12,34,567.89',
        sampleTime: '7:15 pm',
      },
    },
  ],
  weekStarts: ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'],
};
const organizationGovernance = {
  canManage: true,
  eligibleAssignees: [
    { id: '77777777-7777-4777-8777-777777777777', type: 'membership', display: 'Ravi Shah' },
  ],
  evaluatedAt: '2026-09-18T08:00:00Z',
  organizationId: organization.id,
  responsibilities: [],
  responsibilityTypes: ['clinical', 'privacy', 'security', 'billing'],
};
const facilityDirectory = {
  organizationId: organization.id,
  canCreate: true,
  evaluatedAt: '2026-09-18T08:00:00Z',
  facilityTypes: [{ key: 'care_site', displayName: 'Care site' }],
  facilities: [
    {
      facilityId: '33333333-3333-4333-8333-333333333333',
      facilityCode: 'CARE-01',
      legalName: 'Care One Facility Limited',
      displayName: 'Care One',
      facilityType: 'care_site',
      timezone: 'Asia/Kolkata',
      status: 'draft',
      lockVersion: 0,
      createdAt: '2026-09-18T08:00:00Z',
      updatedAt: '2026-09-18T08:00:00Z',
    },
  ],
};
const unitDirectory = {
  organizationId: organization.id,
  facilityId: facilityDirectory.facilities[0].facilityId,
  canManage: true,
  canManageLifecycle: true,
  evaluatedAt: '2026-09-20T10:00:00Z',
  units: [
    {
      unitId: '44444444-4444-4444-8444-444444444444',
      unitCode: 'CLINICAL',
      unitType: 'department',
      name: 'Clinical Services',
      effectiveFrom: '2026-09-20T00:00:00Z',
      status: 'draft',
      lockVersion: 0,
      createdAt: '2026-09-20T10:00:00Z',
      updatedAt: '2026-09-20T10:00:00Z',
    },
    {
      unitId: '55555555-5555-4555-8555-555555555555',
      parentId: '44444444-4444-4444-8444-444444444444',
      unitCode: 'CARDIOLOGY',
      unitType: 'unit',
      name: 'Cardiology',
      effectiveFrom: '2026-09-20T00:00:00Z',
      status: 'active',
      lockVersion: 2,
      createdAt: '2026-09-20T10:00:00Z',
      updatedAt: '2026-09-20T10:00:00Z',
    },
  ],
};
const locationDirectory = {
  organizationId: organization.id,
  facilityId: facilityDirectory.facilities[0].facilityId,
  canManage: true,
  canManageLifecycle: true,
  evaluatedAt: '2026-09-20T10:00:00Z',
  locations: [
    {
      locationId: '66666666-6666-4666-8666-666666666666',
      addressId: '77777777-7777-4777-8777-777777777777',
      locationCode: 'MAIN_CLINIC',
      locationType: 'physical',
      name: 'Main clinic',
      capacity: 20,
      effectiveFrom: '2026-09-20T00:00:00Z',
      status: 'draft',
      lockVersion: 0,
      createdAt: '2026-09-20T10:00:00Z',
      updatedAt: '2026-09-20T10:00:00Z',
    },
  ],
};
const organizationMemberships = {
  asOf: '2026-09-17T05:30:00Z',
  availableActions: [
    'issueInvitation',
    'approveMembershipChange',
    'executeMembershipChange',
    'approveOwnerTransfer',
    'executeOwnerTransfer',
  ],
  items: [
    {
      accessState: 'active',
      accountStatus: 'active',
      availableActions: [
        'requestMfaReset',
        'requestRoleChange',
        'requestRevocation',
        'requestOwnerTransfer',
      ],
      displayName: 'Ravi Shah',
      effectiveFrom: '2026-08-01T06:00:00Z',
      effectiveTo: null,
      email: 'ravi.shah@example.test',
      finalOwner: false,
      lockVersion: 0,
      membershipId: '77777777-7777-4777-8777-777777777777',
      mfaEnabled: true,
      roleDisplayName: 'Security administrator',
      roleKey: 'security_administrator',
      roleStatus: 'active',
      userId: '88888888-8888-4888-8888-888888888888',
    },
  ],
  organizationId: organization.id,
  page: { hasMore: false, limit: 25, nextCursor: null },
};
const liveAdministrationEvaluatedAt = '2026-09-21T12:00:00Z';
const operatingHoursOverview = {
  organizationId: organization.id,
  canManage: true,
  batches: [],
  evaluatedAt: liveAdministrationEvaluatedAt,
};
const serviceCatalogue = {
  organizationId: organization.id,
  canManage: true,
  canManageLifecycle: true,
  services: [],
  evaluatedAt: liveAdministrationEvaluatedAt,
};
const serviceAssignments = {
  organizationId: organization.id,
  canManage: true,
  canManageLifecycle: true,
  assignments: [],
  evaluatedAt: liveAdministrationEvaluatedAt,
};
const identifierSchemes = {
  organizationId: organization.id,
  canManage: true,
  canActivate: false,
  canRetire: false,
  schemes: [],
  evaluatedAt: liveAdministrationEvaluatedAt,
};
const configurationActivations = {
  organizationId: organization.id,
  canValidate: true,
  canSubmit: true,
  canApprove: true,
  canActivate: true,
  pendingChanges: [],
  configurations: [],
  evaluatedAt: liveAdministrationEvaluatedAt,
};
const configurationHistory = {
  organizationId: organization.id,
  asOf: liveAdministrationEvaluatedAt,
  items: [],
  pageSize: 25,
  hasMore: false,
  nextCursor: null,
};
const auditEvidence = {
  organizationId: organization.id,
  asOf: liveAdministrationEvaluatedAt,
  items: [],
  pageSize: 25,
  hasMore: false,
  nextCursor: null,
};
const evidenceExports = {
  organizationId: organization.id,
  canRequest: true,
  canApprove: true,
  canAccess: true,
  jobs: [],
  evaluatedAt: liveAdministrationEvaluatedAt,
};
const authenticatedSession = {
  mfaEnabled: false,
  mfaRequired: false,
  recentAuthentication: true,
  state: 'authenticated',
  user,
};

function workforceScreen(screenId: string) {
  const rowId = `33333333-3333-4333-8333-${screenId.slice(3).padStart(12, '0')}`;
  return {
    organizationId: organization.id,
    screenId,
    title: screenId === 'M2-01' ? 'Workforce dashboard' : `Server-governed ${screenId}`,
    purpose: 'Render the current minimum-necessary workforce projection.',
    generatedAt: '2026-09-25T08:00:00Z',
    metrics: [{ key: 'authorized', label: 'Authorized records', value: 1, tone: 'info' }],
    columns: [{ key: 'primary', label: 'Record' }],
    rows: [
      {
        id: rowId,
        memberId: '44444444-4444-4444-8444-444444444444',
        status: 'active',
        revision: 1,
        etag: `"m2:${screenId}:${rowId}:1"`,
        values: { primary: `Server projection ${screenId}` },
        allowedActionKeys: [],
      },
    ],
    actions: [
      {
        key: 'open-timeline',
        label: 'Open lifecycle timeline',
        style: 'link',
        targetRequired: false,
        ifMatchRequired: false,
        reasonRequired: false,
        href: '#/M2-29',
        fields: [],
      },
    ],
    notices: [],
    nextCursor: null,
    pageSize: 25,
  };
}

const routeGroups = [
  { count: 23, module: 'M1', start: 5 },
  { count: 29, module: 'M2', start: 1 },
  { count: 27, module: 'COS', start: 1 },
];
const routeSweepTimeout = 120_000;

async function jsonResponse(
  route: Route,
  data: unknown,
  status = 200,
  sessionExpiresIn?: string,
  responseHeaders: Record<string, string> = {},
) {
  const correlationId = route.request().headers()['x-correlation-id'] ?? 'playwright-session';
  const authenticatedState =
    typeof data === 'object' &&
    data !== null &&
    'state' in data &&
    (data.state === 'authenticated' ||
      data.state === 'mfa_required' ||
      data.state === 'mfa_enrollment_required');
  await route.fulfill({
    body: JSON.stringify(data),
    headers: {
      'Cache-Control': 'no-store',
      'Content-Type': 'application/json',
      'X-Correlation-Id': correlationId,
      ...(sessionExpiresIn || authenticatedState
        ? { 'X-CareOS-Session-Expires-In': sessionExpiresIn ?? '1800' }
        : {}),
      ...responseHeaders,
    },
    status,
  });
}

async function emptyResponse(route: Route, status = 204) {
  const correlationId = route.request().headers()['x-correlation-id'] ?? 'playwright-session';
  await route.fulfill({ headers: { 'X-Correlation-Id': correlationId }, status });
}

async function mockAuthenticatedSession(page: Page) {
  await page.route('**/api/v1/auth/session', (route) => jsonResponse(route, authenticatedSession));
  await page.route('**/api/v1/organizations', (route) => jsonResponse(route, [organization]));
  await page.route(`**/api/v1/organizations/${organization.id}/setup-readiness`, (route) =>
    jsonResponse(route, organizationReadiness),
  );
  await page.route(`**/api/v1/organizations/${organization.id}/profile`, (route) =>
    jsonResponse(route, organizationProfile, 200, undefined, {
      ETag: '"organization-profile:4"',
    }),
  );
  await page.route(`**/api/v1/organizations/${organization.id}/identifiers**`, (route) =>
    jsonResponse(route, organizationIdentifiers),
  );
  await page.route(`**/api/v1/organizations/${organization.id}/contacts**`, (route) =>
    jsonResponse(route, organizationContacts),
  );
  await page.route(`**/api/v1/organizations/${organization.id}/international-settings`, (route) =>
    jsonResponse(route, organizationInternationalSettings, 200, undefined, {
      ETag: '"organization-international-settings:4"',
    }),
  );
  await page.route(
    `**/api/v1/organizations/${organization.id}/governance-responsibilities**`,
    (route) => jsonResponse(route, organizationGovernance),
  );
  await page.route(`**/api/v1/organizations/${organization.id}/facilities**`, (route) =>
    jsonResponse(route, facilityDirectory, route.request().method() === 'POST' ? 201 : 200),
  );
  await page.route(`**/api/v1/organizations/${organization.id}/facilities/*/units**`, (route) =>
    jsonResponse(route, unitDirectory, route.request().method() === 'POST' ? 201 : 200),
  );
  await page.route(`**/api/v1/organizations/${organization.id}/facilities/*/locations**`, (route) =>
    jsonResponse(route, locationDirectory, route.request().method() === 'POST' ? 201 : 200),
  );
  await page.route(`**/api/v1/organizations/${organization.id}/memberships**`, (route) =>
    jsonResponse(route, organizationMemberships),
  );
  await page.route(`**/api/v1/organizations/${organization.id}/operating-hours**`, (route) =>
    jsonResponse(route, operatingHoursOverview),
  );
  await page.route(`**/api/v1/organizations/${organization.id}/services**`, (route) =>
    jsonResponse(route, serviceCatalogue, route.request().method() === 'POST' ? 201 : 200),
  );
  await page.route(`**/api/v1/organizations/${organization.id}/service-assignments**`, (route) =>
    jsonResponse(route, serviceAssignments, route.request().method() === 'POST' ? 201 : 200),
  );
  await page.route(`**/api/v1/organizations/${organization.id}/identifier-schemes**`, (route) =>
    jsonResponse(route, identifierSchemes, route.request().method() === 'POST' ? 201 : 200),
  );
  await page.route(
    `**/api/v1/organizations/${organization.id}/configuration-activations`,
    (route) => jsonResponse(route, configurationActivations),
  );
  await page.route(`**/api/v1/organizations/${organization.id}/configuration-history**`, (route) =>
    jsonResponse(route, configurationHistory),
  );
  await page.route(`**/api/v1/organizations/${organization.id}/audit-evidence**`, (route) =>
    jsonResponse(route, auditEvidence),
  );
  await page.route(`**/api/v1/organizations/${organization.id}/evidence-exports**`, (route) =>
    jsonResponse(route, evidenceExports, route.request().method() === 'POST' ? 201 : 200),
  );
  await page.route(`**/api/v1/organizations/${organization.id}/workforce/screens/*`, (route) => {
    const screenId = new URL(route.request().url()).pathname.split('/').at(-1) ?? '';
    return jsonResponse(route, workforceScreen(screenId));
  });
}

async function expectNoSeriousViolations(page: Page, label: string) {
  const results = await new AxeBuilder({ page }).analyze();
  const seriousViolations = results.violations.filter((item) =>
    ['critical', 'serious'].includes(item.impact ?? ''),
  );
  expect(seriousViolations, `${label} has critical or serious Axe violations`).toEqual([]);
}

async function expectNoDocumentHorizontalOverflow(page: Page, label: string) {
  const widths = await page.evaluate(() => {
    const viewport = document.documentElement.clientWidth;
    const offenders = Array.from(document.querySelectorAll<HTMLElement>('body *'))
      .map((element) => {
        const bounds = element.getBoundingClientRect();
        return {
          bounds: [Math.round(bounds.left), Math.round(bounds.right)],
          className: element.className,
          clientWidth: element.clientWidth,
          scrollWidth: element.scrollWidth,
          tagName: element.tagName,
        };
      })
      .filter(({ bounds }) => (bounds[1] ?? 0) > viewport + 1)
      .slice(0, 8);
    return {
      body: document.body.scrollWidth,
      viewport,
      document: document.documentElement.scrollWidth,
      offenders,
    };
  });
  expect(
    widths.document,
    `${label} overflows the document viewport: ${JSON.stringify(widths.offenders)}`,
  ).toBeLessThanOrEqual(widths.viewport);
  expect(widths.body, `${label} overflows the body viewport`).toBeLessThanOrEqual(widths.viewport);
}

for (const { module, count, start } of routeGroups) {
  test(`${module} protected prototype routes render without serious accessibility violations or document overflow`, async ({
    page,
  }) => {
    test.setTimeout(routeSweepTimeout);
    await mockAuthenticatedSession(page);
    const ids = Array.from(
      { length: count - start + 1 },
      (_, index) => `${module}-${String(index + start).padStart(2, '0')}`,
    );

    for (const id of ids) {
      await page.goto(`/#/${id}`);
      await expect(page.getByText(id).first()).toBeVisible();
      await expect(page.locator('main h1')).toBeVisible();
      if (module === 'M2') {
        await expect(page.getByText(`Server projection ${id}`, { exact: true })).toBeVisible();
        await expect(page.getByText('Server governed', { exact: true })).toBeVisible();
      }
      await expectNoDocumentHorizontalOverflow(page, id);
      await expectNoSeriousViolations(page, id);
    }
  });
}

test('M2-24 requires fresh server impact before submitting governed offboarding', async ({
  page,
}) => {
  await mockAuthenticatedSession(page);
  await page.unroute(`**/api/v1/organizations/${organization.id}/workforce/screens/*`);
  const base = workforceScreen('M2-24');
  const projection = {
    ...base,
    title: 'Offboarding',
    rows: base.rows.map((row) => ({
      ...row,
      allowedActionKeys: ['request-offboarding'],
    })),
    actions: [
      {
        key: 'request-offboarding',
        label: 'Request offboarding',
        style: 'primary',
        targetRequired: true,
        ifMatchRequired: true,
        reasonRequired: true,
        href: null,
        fields: [
          {
            key: 'accessAction',
            label: 'Access action',
            inputType: 'select',
            required: true,
            help: 'Revoke active access at the approved effective time.',
            options: [
              {
                value: 'revoke_at_effective',
                label: 'Revoke at effective time',
              },
            ],
          },
        ],
      },
    ],
  };
  const row = projection.rows[0]!;
  const impactToken = 'impact_token_'.padEnd(64, 'x');
  let previewRequests = 0;
  let actionRequests = 0;

  await page.route('**/api/v1/auth/csrf', (route) =>
    jsonResponse(route, {
      headerName: 'X-XSRF-TOKEN',
      parameterName: '_csrf',
      token: 'playwright-csrf-token-1234567890',
    }),
  );
  await page.route(
    `**/api/v1/organizations/${organization.id}/workforce/screens/M2-24**`,
    async (route) => {
      const request = route.request();
      const path = new URL(request.url()).pathname;
      if (request.method() === 'GET') {
        await jsonResponse(route, projection);
        return;
      }
      expect(request.headers()['x-xsrf-token']).toBe('playwright-csrf-token-1234567890');
      expect(request.headers()['if-match']).toBe(row.etag);
      if (path.endsWith('/actions/request-offboarding/impact-preview')) {
        previewRequests += 1;
        expect(request.postDataJSON()).toEqual({
          decision: null,
          evidenceIds: [],
          fields: { accessAction: 'revoke_at_effective' },
          memberId: row.memberId,
          reason: 'Approved workforce transition CARE-42',
          targetId: row.id,
        });
        await jsonResponse(route, {
          screenId: 'M2-24',
          actionKey: 'request-offboarding',
          targetId: row.id,
          revision: row.revision,
          digest: 'b'.repeat(64),
          token: impactToken,
          expiresAt: '2099-09-25T08:10:00Z',
          blocked: false,
          items: [
            {
              code: 'active_access_revoked',
              tone: 'impact',
              detail: 'Two active role memberships will be revoked.',
              affectedCount: 2,
            },
          ],
        });
        return;
      }
      if (path.endsWith('/actions/request-offboarding')) {
        actionRequests += 1;
        expect(request.headers()['idempotency-key']).toMatch(
          /^m2:request-offboarding:[0-9a-f-]{36}$/,
        );
        expect(request.postDataJSON()).toEqual({
          decision: null,
          evidenceIds: [],
          fields: { accessAction: 'revoke_at_effective' },
          impactToken,
          memberId: row.memberId,
          reason: 'Approved workforce transition CARE-42',
          targetId: row.id,
        });
        await jsonResponse(route, {
          ...projection,
          generatedAt: '2026-09-25T08:01:00Z',
          rows: [
            {
              ...row,
              status: 'offboarding',
              revision: 2,
              etag: `"m2:M2-24:${row.id}:2"`,
            },
          ],
        });
        return;
      }
      await route.abort('failed');
    },
  );

  await page.goto('/#/M2-24');
  await page.getByLabel('Select Server projection M2-24').check();
  await page.getByRole('button', { name: 'Request offboarding' }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await page.getByLabel('Access action').selectOption('revoke_at_effective');
  await page.getByLabel('Reason').fill('Approved workforce transition CARE-42');
  await page.getByRole('button', { name: 'Review impact' }).click();
  await expect(page.getByRole('heading', { name: 'Review before confirmation' })).toBeVisible();
  expect(previewRequests).toBe(1);

  await page.getByRole('dialog').getByRole('button', { name: 'Request offboarding' }).click();
  await expect(
    page.getByRole('status').filter({ hasText: 'server-confirmed result' }),
  ).toBeVisible();
  expect(actionRequests).toBe(1);
  await expectNoSeriousViolations(page, 'M2-24 governed offboarding');
});

test('live activation and remaining synthetic screens expose honest action boundaries', async ({
  page,
}) => {
  await mockAuthenticatedSession(page);

  await page.goto('/#/M1-21');
  await expect(page.getByRole('heading', { name: 'Review and activate' })).toBeVisible();
  await expect(page.getByText('0 versions')).toBeVisible();
  await expect(page.getByRole('button', { name: /Review.*unavailable/ })).toHaveCount(0);

  await page.goto('/#/COS-27');
  await expect(page.getByText('Synthetic patient')).toBeVisible();
  await expect(page.getByLabel('Clinical note')).toHaveAttribute('readonly', '');
  await expect(page.getByRole('button', { name: /Save draft.*unavailable/ })).toBeDisabled();
  await expect(
    page.getByRole('button', { name: /Confirm and continue.*unavailable/ }),
  ).toBeDisabled();
  const pagination = page.getByRole('navigation', { name: 'Prototype pagination' });
  await expect(pagination.getByRole('link', { name: /COS-27/ })).toHaveCount(0);
  await expect(pagination.getByText('COS-27')).toHaveAttribute('aria-disabled', 'true');
  await expectNoDocumentHorizontalOverflow(page, 'honest synthetic prototype boundary');
  await expectNoSeriousViolations(page, 'honest synthetic prototype boundary');
});

test('M1-22 refreshes an active export with bounded polling and stops at ready', async ({
  page,
}) => {
  await mockAuthenticatedSession(page);
  await page.unroute(`**/api/v1/organizations/${organization.id}/evidence-exports**`);
  let directoryRequests = 0;
  let allowReady = false;
  const exportId = '99999999-9999-4999-8999-999999999999';
  await page.route(`**/api/v1/organizations/${organization.id}/evidence-exports**`, (route) => {
    ++directoryRequests;
    const ready = allowReady;
    return jsonResponse(route, {
      ...evidenceExports,
      jobs: [
        {
          exportId,
          requesterId: user.id,
          projection: 'history-summary-v1',
          format: 'csv',
          purposeCode: 'configuration_review',
          status: ready ? 'ready' : 'authorized',
          canApprove: false,
          canAccess: ready,
          approvalId: null,
          approverId: null,
          rowCount: ready ? 0 : null,
          byteCount: ready ? 0 : null,
          artifactDigest: ready ? 'a'.repeat(64) : null,
          readyAt: ready ? '2026-09-21T12:00:02Z' : null,
          expiresAt: ready ? '2026-09-22T12:00:02Z' : null,
          failureCode: null,
          lockVersion: ready ? 2 : 1,
          createdAt: liveAdministrationEvaluatedAt,
          updatedAt: ready ? '2026-09-21T12:00:02Z' : liveAdministrationEvaluatedAt,
        },
      ],
    });
  });

  await page.goto('/#/M1-22');
  await expect(
    page.getByText('Export status will refresh automatically with bounded backoff.'),
  ).toBeVisible();
  const requestsBeforeReady = directoryRequests;
  allowReady = true;
  await expect.poll(() => directoryRequests).toBeGreaterThan(requestsBeforeReady);
  await expect(page.getByRole('button', { name: 'Create 10-minute download' })).toBeVisible();
  await expect
    .poll(async () => {
      const count = directoryRequests;
      await page.waitForTimeout(1_250);
      return directoryRequests - count;
    })
    .toBe(0);
});

test('M1-08 verifies and supersedes governed identifiers with exact revision evidence', async ({
  page,
}) => {
  await mockAuthenticatedSession(page);
  await page.unroute(`**/api/v1/organizations/${organization.id}/identifiers**`);
  let verificationRequests = 0;
  let supersessionRequests = 0;
  let superseded = false;
  const replacementIdentifier = {
    ...organizationIdentifier,
    availableActions: ['revoke', 'supersede'],
    evidenceReference: 'NPR-REPLACEMENT-2026-1042',
    identifierId: '55555555-5555-4555-8555-555555555555',
    isPrimary: false,
    lockVersion: 2,
    status: 'verified',
    value: 'REG-IN-REPLACEMENT',
    verificationStatus: 'verified',
  };
  await page.route('**/api/v1/auth/csrf', (route) =>
    jsonResponse(route, {
      headerName: 'X-XSRF-TOKEN',
      parameterName: '_csrf',
      token: 'identifier-csrf-token-123456',
    }),
  );
  await page.route(`**/api/v1/organizations/${organization.id}/identifiers**`, async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (request.method() === 'GET') {
      await jsonResponse(
        route,
        superseded
          ? {
              ...organizationIdentifiers,
              items: [
                {
                  ...replacementIdentifier,
                  availableActions: ['supersede'],
                  isPrimary: true,
                  lockVersion: 3,
                  supersedesId: organizationIdentifier.identifierId,
                },
                {
                  ...organizationIdentifier,
                  availableActions: [],
                  evidenceReference: 'NPR-CASE-2026-1042',
                  lockVersion: 2,
                  status: 'superseded',
                  verificationStatus: 'verified',
                },
              ],
            }
          : { ...organizationIdentifiers, items: [organizationIdentifier, replacementIdentifier] },
      );
      return;
    }
    if (
      request.method() === 'POST' &&
      path.endsWith(`/${organizationIdentifier.identifierId}/verifications`)
    ) {
      ++verificationRequests;
      expect(request.headers()['if-match']).toBe(
        `"organization-identifier:${organizationIdentifier.identifierId}:0"`,
      );
      expect(request.headers()['idempotency-key']).toMatch(/^organization-identifier:/);
      expect(request.headers()['x-xsrf-token']).toBe('identifier-csrf-token-123456');
      expect(request.postDataJSON()).toEqual({
        evidenceReference: 'NPR-CASE-2026-1042',
        reason: 'Authority verification completed CARE-1042',
      });
      await jsonResponse(
        route,
        {
          ...organizationIdentifier,
          availableActions: ['supersede'],
          evidenceReference: 'NPR-CASE-2026-1042',
          lockVersion: 1,
          status: 'verified',
          updatedAt: '2026-09-16T09:00:00Z',
          verificationStatus: 'verified',
        },
        200,
        undefined,
        {
          ETag: `"organization-identifier:${organizationIdentifier.identifierId}:1"`,
        },
      );
      return;
    }
    if (
      request.method() === 'POST' &&
      path.endsWith(`/${organizationIdentifier.identifierId}/supersessions`)
    ) {
      ++supersessionRequests;
      expect(request.headers()['if-match']).toBe(
        `"organization-identifier:${organizationIdentifier.identifierId}:1"`,
      );
      expect(request.headers()['idempotency-key']).toMatch(/^organization-identifier:/);
      expect(request.headers()['x-xsrf-token']).toBe('identifier-csrf-token-123456');
      expect(request.postDataJSON()).toEqual({
        reason: 'Verified replacement approved CARE-1042',
        replacementEtag: `"organization-identifier:${replacementIdentifier.identifierId}:2"`,
        replacementId: replacementIdentifier.identifierId,
      });
      superseded = true;
      await jsonResponse(
        route,
        {
          ...organizationIdentifier,
          availableActions: [],
          evidenceReference: 'NPR-CASE-2026-1042',
          lockVersion: 2,
          status: 'superseded',
          verificationStatus: 'verified',
        },
        200,
        undefined,
        {
          ETag: `"organization-identifier:${organizationIdentifier.identifierId}:2"`,
        },
      );
      return;
    }
    await route.abort('failed');
  });

  await page.goto('/#/M1-08');
  await expect(page.getByRole('heading', { name: 'REG-IN-0042' })).toBeVisible();
  await page.getByRole('button', { name: 'Verify', exact: true }).click();
  await page.getByLabel('Verification evidence reference').fill('NPR-CASE-2026-1042');
  await page
    .getByLabel('Reason', { exact: true })
    .fill('Authority verification completed CARE-1042');
  await page.getByRole('button', { name: 'Verify identifier' }).click();

  await expect(page.getByRole('status')).toContainText(
    'Identifier verified with authority evidence',
  );
  await expect(page.getByText('NPR-CASE-2026-1042')).toBeVisible();
  expect(verificationRequests).toBe(1);

  const originalCard = page
    .getByRole('heading', { name: organizationIdentifier.value })
    .locator('xpath=ancestor::article');
  await originalCard.getByRole('button', { name: 'Supersede' }).click();
  await expect(page.getByLabel('Verified replacement')).toHaveValue(
    replacementIdentifier.identifierId,
  );
  await page.getByLabel('Reason', { exact: true }).fill('Verified replacement approved CARE-1042');
  await page.getByRole('button', { name: 'Supersede identifier' }).click();

  await expect(page.getByRole('status')).toContainText(
    'Identifier superseded by the verified replacement',
  );
  await expect(
    page
      .getByRole('heading', { name: replacementIdentifier.value })
      .locator('xpath=ancestor::article'),
  ).toContainText(new RegExp(`Supersedes\\s*${organizationIdentifier.value}`));
  expect(supersessionRequests).toBe(1);
  await expectNoDocumentHorizontalOverflow(page, 'M1-08 governed identifiers');
  await expectNoSeriousViolations(page, 'M1-08 governed identifiers');
});

test('M1-09 verifies masked contacts and supersedes addresses with immutable lineage', async ({
  page,
}) => {
  await mockAuthenticatedSession(page);
  await page.unroute(`**/api/v1/organizations/${organization.id}/contacts**`);
  let contactVerified = false;
  let addressSuperseded = false;
  let verificationRequests = 0;
  let supersessionRequests = 0;
  const replacementAddress = {
    ...organizationAddress,
    addressId: '66666666-6666-4666-8666-666666666667',
    addressLines: ['84 Care Avenue', 'Andheri East'],
    supersedesId: organizationAddress.addressId,
  };
  await page.route('**/api/v1/auth/csrf', (route) =>
    jsonResponse(route, {
      headerName: 'X-XSRF-TOKEN',
      parameterName: '_csrf',
      token: 'contact-csrf-token-123456',
    }),
  );
  await page.route(`**/api/v1/organizations/${organization.id}/contacts**`, async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (request.method() === 'GET') {
      await jsonResponse(route, {
        ...organizationContacts,
        addresses: addressSuperseded
          ? [
              replacementAddress,
              {
                ...organizationAddress,
                availableActions: [],
                lockVersion: 1,
                status: 'superseded',
                updatedAt: '2026-09-18T09:00:00Z',
              },
            ]
          : [organizationAddress],
        contacts: [
          contactVerified
            ? {
                ...organizationContact,
                availableActions: ['supersede', 'end'],
                lockVersion: 1,
                updatedAt: '2026-09-18T08:30:00Z',
                verificationStatus: 'verified',
              }
            : organizationContact,
        ],
      });
      return;
    }
    if (
      request.method() === 'POST' &&
      path.endsWith(`/${organizationContact.contactId}/verifications`)
    ) {
      ++verificationRequests;
      expect(request.headers()['if-match']).toBe(
        `"organization-contact:${organizationContact.contactId}:0"`,
      );
      expect(request.headers()['idempotency-key']).toMatch(/^organization-contact:/);
      expect(request.headers()['x-xsrf-token']).toBe('contact-csrf-token-123456');
      expect(request.postDataJSON()).toEqual({
        reason: 'Verified operational mailbox ownership CARE-2002',
      });
      contactVerified = true;
      await jsonResponse(
        route,
        {
          ...organizationContact,
          availableActions: ['supersede', 'end'],
          lockVersion: 1,
          updatedAt: '2026-09-18T08:30:00Z',
          verificationStatus: 'verified',
        },
        200,
        undefined,
        { ETag: `"organization-contact:${organizationContact.contactId}:1"` },
      );
      return;
    }
    await route.abort('failed');
  });
  await page.route(`**/api/v1/organizations/${organization.id}/addresses**`, async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (
      request.method() === 'POST' &&
      path.endsWith(`/${organizationAddress.addressId}/supersessions`)
    ) {
      ++supersessionRequests;
      expect(request.headers()['if-match']).toBe(
        `"organization-address:${organizationAddress.addressId}:0"`,
      );
      expect(request.headers()['idempotency-key']).toMatch(/^organization-contact:/);
      expect(request.headers()['x-xsrf-token']).toBe('contact-csrf-token-123456');
      expect(request.postDataJSON()).toMatchObject({
        addressLines: ['84 Care Avenue', 'Andheri East'],
        addressType: 'registered',
        isPrimary: true,
        reason: 'Approved registered address replacement CARE-2003',
      });
      addressSuperseded = true;
      await jsonResponse(route, replacementAddress, 201, undefined, {
        ETag: `"organization-address:${replacementAddress.addressId}:0"`,
      });
      return;
    }
    await route.abort('failed');
  });

  await page.goto('/#/M1-09');
  await expect(
    page.getByRole('heading', { name: organizationAddress.addressLines.join(', ') }),
  ).toBeVisible();
  await expect(page.getByRole('heading', { name: organizationContact.maskedValue })).toBeVisible();
  await expect(page.getByText('operations@example.org')).toHaveCount(0);

  const contactCard = page
    .getByRole('heading', { name: organizationContact.maskedValue })
    .locator('xpath=ancestor::article');
  await contactCard.getByRole('button', { name: 'Verify' }).click();
  await page
    .getByLabel('Reason', { exact: true })
    .fill('Verified operational mailbox ownership CARE-2002');
  await page.getByRole('button', { name: 'Verify contact' }).click();
  await expect(page.getByRole('status')).toContainText('Contact verification recorded');
  expect(verificationRequests).toBe(1);

  const addressCard = page
    .getByRole('heading', { name: organizationAddress.addressLines.join(', ') })
    .locator('xpath=ancestor::article');
  await addressCard.getByRole('button', { name: 'Supersede' }).click();
  await page.getByLabel('Address line 1').fill('84 Care Avenue');
  await page
    .getByLabel('Reason', { exact: true })
    .fill('Approved registered address replacement CARE-2003');
  await page.getByRole('button', { name: 'Supersede address' }).click();
  await expect(page.getByRole('status')).toContainText('Address superseded with immutable lineage');
  await expect(
    page.getByRole('heading', { name: replacementAddress.addressLines.join(', ') }),
  ).toBeVisible();
  expect(supersessionRequests).toBe(1);
  await expect(page.getByText('operations@example.org')).toHaveCount(0);
  await expectNoDocumentHorizontalOverflow(page, 'M1-09 governed addresses and contacts');
  await expectNoSeriousViolations(page, 'M1-09 governed addresses and contacts');
});

test('M1-20 renders authorized membership data and permission-projected actions', async ({
  page,
}) => {
  await mockAuthenticatedSession(page);
  await page.goto('/#/M1-20');

  await expect(page.getByRole('heading', { name: 'Administrator access' })).toBeVisible();
  await expect(page.getByText('Ravi Shah')).toBeVisible();
  await expect(page.getByText('ravi.shah@example.test')).toBeVisible();
  await expect(page.getByText('Synthetic prototype')).toHaveCount(0);
  await expect(page.getByRole('link', { name: 'Invite administrator' })).toHaveAttribute(
    'href',
    '#/M1-02',
  );
  await expect(page.getByRole('link', { name: 'Request MFA reset' })).toHaveAttribute(
    'href',
    '#/M1-03',
  );
  await expect(page.getByRole('button', { name: 'Change role' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Promote to owner' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Governed access change' })).toBeVisible();
  await expect(page.getByText(/Facility-scoped grants remain unavailable/)).toBeVisible();

  const filteredRequest = page.waitForRequest((request) =>
    request.url().includes('memberships?search=Ravi&state=active'),
  );
  await page.getByLabel('Name or email').fill('Ravi');
  await page.getByLabel('Access state').selectOption('active');
  await page.getByRole('button', { name: 'Apply filters' }).click();
  await filteredRequest;
  await expect(page.getByText('Ravi Shah')).toBeVisible();

  const memberships = page.getByRole('region', { name: 'Organization memberships' });
  const viewportWidth = page.viewportSize()?.width ?? 0;
  if (viewportWidth <= 760) {
    await expect(
      memberships.getByRole('list', { name: 'Organization membership cards' }),
    ).toBeVisible();
    await expect(memberships.getByRole('table')).toHaveCount(0);
  } else {
    await expect(memberships.getByRole('table')).toBeVisible();
    await expect(
      memberships.getByRole('list', { name: 'Organization membership cards' }),
    ).toHaveCount(0);
  }
  await memberships.focus();
  await expect(memberships).toBeFocused();
  await expectNoDocumentHorizontalOverflow(page, 'M1-20 authorized memberships');
  await expectNoSeriousViolations(page, 'M1-20 authorized memberships');
});

test('M1-20 submits an exact governed membership role-change request', async ({ page }) => {
  await mockAuthenticatedSession(page);
  await page.unroute(`**/api/v1/organizations/${organization.id}/memberships**`);
  await page.route('**/api/v1/auth/csrf', (route) =>
    jsonResponse(route, {
      headerName: 'X-XSRF-TOKEN',
      parameterName: '_csrf',
      token: 'playwright-csrf-token-123456',
    }),
  );
  let capturedHeaders: Record<string, string> = {};
  let capturedBody: unknown;
  await page.route(`**/api/v1/organizations/${organization.id}/memberships**`, async (route) => {
    if (route.request().method() === 'GET') {
      await jsonResponse(route, organizationMemberships);
      return;
    }
    capturedHeaders = route.request().headers();
    capturedBody = route.request().postDataJSON();
    await jsonResponse(
      route,
      {
        approvalId: '99999999-9999-4999-8999-999999999999',
        changeType: 'role_change',
        expiresAt: '2026-09-17T12:00:00Z',
        fromRoleKey: 'security_administrator',
        lockVersion: 0,
        membershipId: '77777777-7777-4777-8777-777777777777',
        status: 'pending',
        targetUserId: '88888888-8888-4888-8888-888888888888',
        toRoleKey: 'organization_viewer',
      },
      201,
    );
  });

  await page.goto('/#/M1-20');
  await page.getByRole('button', { name: 'Change role' }).click();
  await page.getByLabel('Reason').fill('Approved least-privilege role adjustment');
  await page.getByRole('button', { name: 'Submit governed step' }).click();

  await expect(page.getByRole('status')).toContainText('pending');
  expect(capturedHeaders['if-match']).toBe(
    '"organization-membership:77777777-7777-4777-8777-777777777777:0"',
  );
  expect(capturedHeaders['idempotency-key']).toMatch(/^membership-request_role_change:/);
  expect(capturedBody).toEqual({
    changeType: 'role_change',
    reason: 'Approved least-privilege role adjustment',
    toRoleKey: 'organization_viewer',
  });
  await expectNoDocumentHorizontalOverflow(page, 'M1-20 membership change workflow');
  await expectNoSeriousViolations(page, 'M1-20 membership change workflow');
});

test('M1-20 submits an exact governed owner-promotion request', async ({ page }) => {
  await mockAuthenticatedSession(page);
  await page.unroute(`**/api/v1/organizations/${organization.id}/memberships**`);
  await page.route('**/api/v1/auth/csrf', (route) =>
    jsonResponse(route, {
      headerName: 'X-XSRF-TOKEN',
      parameterName: '_csrf',
      token: 'playwright-owner-csrf-token-123456',
    }),
  );
  let capturedHeaders: Record<string, string> = {};
  let capturedBody: unknown;
  await page.route(`**/api/v1/organizations/${organization.id}/memberships**`, async (route) => {
    if (route.request().method() === 'GET') {
      await jsonResponse(route, organizationMemberships);
      return;
    }
    capturedHeaders = route.request().headers();
    capturedBody = route.request().postDataJSON();
    await jsonResponse(
      route,
      {
        approvalId: '99999999-9999-4999-8999-999999999999',
        changeType: 'owner_promotion',
        expiresAt: '2026-09-17T12:00:00Z',
        fromRoleKey: 'security_administrator',
        lockVersion: 0,
        membershipId: '77777777-7777-4777-8777-777777777777',
        status: 'pending',
        targetUserId: '88888888-8888-4888-8888-888888888888',
        toRoleKey: 'organization_owner',
      },
      201,
    );
  });

  await page.goto('/#/M1-20');
  await page.getByRole('button', { name: 'Promote to owner' }).click();
  await expect(page.getByLabel('New role')).toHaveValue('organization_owner');
  await page.getByLabel('Reason').fill('Approved owner succession promotion request');
  await page.getByRole('button', { name: 'Submit governed step' }).click();

  await expect(page.getByRole('status')).toContainText('pending');
  expect(capturedHeaders['if-match']).toBe(
    '"organization-membership:77777777-7777-4777-8777-777777777777:0"',
  );
  expect(capturedHeaders['idempotency-key']).toMatch(/^membership-request_owner_transfer:/);
  expect(capturedBody).toEqual({
    reason: 'Approved owner succession promotion request',
    toRoleKey: 'organization_owner',
  });
  await expectNoDocumentHorizontalOverflow(page, 'M1-20 owner promotion workflow');
  await expectNoSeriousViolations(page, 'M1-20 owner promotion workflow');
});

test('an unknown protected route fails closed and offers a safe recovery path', async ({
  page,
}) => {
  await mockAuthenticatedSession(page);

  await page.goto('/#/M1-99/forged?return=M1-05');

  const heading = page.getByRole('heading', { name: 'Page not found' });
  await expect(heading).toBeVisible();
  await expect(heading).toBeFocused();
  await expect(page.getByText(/No business screen was loaded/)).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Administration dashboard' })).toHaveCount(0);
  await expectNoDocumentHorizontalOverflow(page, 'unknown protected route');
  await expectNoSeriousViolations(page, 'unknown protected route');

  await page.getByRole('link', { name: 'Return to administration dashboard' }).click();
  await expect(page).toHaveURL(/#\/M1-05$/);
  await expect(page.getByRole('heading', { name: 'Administration dashboard' })).toBeVisible();
});

test('identity and governed invitation states are accessible', async ({ page }) => {
  test.setTimeout(routeSweepTimeout);
  let state: 'anonymous' | 'authenticated' | 'mfa_required' = 'anonymous';
  let selected = false;
  await page.route('**/api/v1/auth/session', (route) =>
    jsonResponse(route, {
      mfaEnabled: state === 'mfa_required',
      mfaRequired: false,
      recentAuthentication: state === 'authenticated',
      state,
      user: state === 'anonymous' ? null : user,
    }),
  );
  await page.route('**/api/v1/organizations', (route) =>
    jsonResponse(route, [{ ...organization, selected }]),
  );

  await page.goto('/#/M1-01');
  const loginHeading = page.getByRole('heading', { name: 'Sign in to CareOS' });
  await expect(loginHeading).toBeVisible();
  await expect(loginHeading).toBeFocused();
  const identitySkipLink = page.getByRole('link', { name: 'Skip to main content' });
  const identityUrl = page.url();
  await identitySkipLink.focus();
  await expect(identitySkipLink).toBeVisible();
  await identitySkipLink.press('Enter');
  await expect(page.locator('#main-content')).toBeFocused();
  expect(page.url()).toBe(identityUrl);
  await expectNoDocumentHorizontalOverflow(page, 'M1-01 anonymous login');
  await expectNoSeriousViolations(page, 'M1-01 anonymous login');

  await page.goto('/#/forgot-password');
  const resetRequestHeading = page.getByRole('heading', { name: 'Reset your password' });
  await expect(resetRequestHeading).toBeVisible();
  await expect(resetRequestHeading).toBeFocused();
  await expectNoDocumentHorizontalOverflow(page, 'password-reset request');
  await expectNoSeriousViolations(page, 'password-reset request');

  await page.goto('/#/reset-password?token=Case_Sensitive-Token');
  const resetCompletionHeading = page.getByRole('heading', { name: 'Choose a new password' });
  await expect(resetCompletionHeading).toBeVisible();
  await expect(resetCompletionHeading).toBeFocused();
  await expectNoDocumentHorizontalOverflow(page, 'password-reset completion');
  await expectNoSeriousViolations(page, 'password-reset completion');

  await page.getByLabel('New password', { exact: true }).fill('new-secure-password-27');
  const resetConfirmation = page.getByLabel('Confirm new password', { exact: true });
  await resetConfirmation.fill('different-password-28');
  await page.getByRole('button', { name: 'Change password' }).click();
  const resetValidation = page.getByRole('alert');
  await expect(resetValidation).toBeFocused();
  await expect(resetConfirmation).toHaveAttribute('aria-invalid', 'true');
  await expect(resetConfirmation).toHaveAttribute('aria-describedby', 'confirm-password-error');
  const resetValidationUrl = page.url();
  await resetValidation.getByRole('link', { name: /does not match/ }).click();
  await expect(resetConfirmation).toBeFocused();
  expect(page.url()).toBe(resetValidationUrl);
  await expectNoDocumentHorizontalOverflow(page, 'password-reset validation');
  await expectNoSeriousViolations(page, 'password-reset validation');

  await page.goto('/#/accept-invitation?token=Case_Sensitive-Invitation-Token-1234567890');
  const invitationHeading = page.getByRole('heading', { name: 'Accept your CareOS invitation' });
  await expect(invitationHeading).toBeVisible();
  await expect(invitationHeading).toBeFocused();
  await expectNoDocumentHorizontalOverflow(page, 'M1-02 invitation acceptance');
  await expectNoSeriousViolations(page, 'M1-02 invitation acceptance');

  await page.getByLabel('New password', { exact: true }).fill('new-secure-password-27');
  const invitationConfirmation = page.getByLabel('Confirm new password', { exact: true });
  await invitationConfirmation.fill('different-password-28');
  await page.getByRole('button', { name: 'Accept invitation' }).click();
  const invitationValidation = page.getByRole('alert');
  await expect(invitationValidation).toBeFocused();
  await expect(invitationConfirmation).toHaveAttribute('aria-invalid', 'true');
  await expect(invitationConfirmation).toHaveAttribute(
    'aria-describedby',
    'invitation-password-confirmation-error',
  );
  const invitationValidationUrl = page.url();
  await invitationValidation.getByRole('link', { name: /does not match/ }).click();
  await expect(invitationConfirmation).toBeFocused();
  expect(page.url()).toBe(invitationValidationUrl);
  await expectNoDocumentHorizontalOverflow(page, 'M1-02 invitation validation');
  await expectNoSeriousViolations(page, 'M1-02 invitation validation');

  state = 'mfa_required';
  await page.goto('/?identity=mfa#/M1-03');
  const mfaChallengeHeading = page.getByRole('heading', { name: 'Verify your identity' });
  await expect(mfaChallengeHeading).toBeVisible();
  await expect(mfaChallengeHeading).toBeFocused();
  await expectNoDocumentHorizontalOverflow(page, 'M1-03 MFA challenge');
  await expectNoSeriousViolations(page, 'M1-03 MFA challenge');

  state = 'authenticated';
  selected = false;
  await page.goto('/?identity=organization#/M1-04');
  const organizationHeading = page.getByRole('heading', { name: 'Choose an organization' });
  await expect(organizationHeading).toBeVisible();
  await expect(organizationHeading).toBeFocused();
  await expectNoDocumentHorizontalOverflow(page, 'M1-04 organization selection');
  await expectNoSeriousViolations(page, 'M1-04 organization selection');

  selected = true;
  await page.goto('/?identity=invitations#/M1-02');
  const invitationAdministrationHeading = page.getByRole('heading', {
    name: 'Organization invitations',
  });
  await expect(invitationAdministrationHeading).toBeVisible();
  await expect(invitationAdministrationHeading).toBeFocused();
  await expectNoDocumentHorizontalOverflow(page, 'M1-02 invitation administration');
  await expectNoSeriousViolations(page, 'M1-02 invitation administration');

  await page.goto('/?identity=mfa-administration#/M1-03');
  const mfaAdministrationHeading = page.getByRole('heading', {
    name: 'Multi-factor authentication',
  });
  await expect(mfaAdministrationHeading).toBeVisible();
  await expect(mfaAdministrationHeading).toBeFocused();
  await expectNoDocumentHorizontalOverflow(page, 'M1-03 MFA administration');
  await expectNoSeriousViolations(page, 'M1-03 MFA administration');
});

test('mandatory-role MFA enrollment keeps the workspace locked through recovery-code storage', async ({
  page,
}) => {
  let enrolled = false;
  let organizationRequests = 0;
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const method = request.method();

    if (method === 'GET' && path === '/api/v1/auth/session') {
      await jsonResponse(
        route,
        enrolled
          ? { ...authenticatedSession, mfaEnabled: true, mfaRequired: true }
          : {
              mfaEnabled: false,
              mfaRequired: true,
              recentAuthentication: true,
              state: 'mfa_enrollment_required',
              user,
            },
      );
      return;
    }
    if (method === 'GET' && path === '/api/v1/auth/csrf') {
      await jsonResponse(route, {
        headerName: 'X-XSRF-TOKEN',
        parameterName: '_csrf',
        token: 'mandatory-enrollment-csrf-token',
      });
      return;
    }
    if (method === 'POST' && path === '/api/v1/auth/mfa/enrollments') {
      expect(request.postDataJSON()).toEqual({ label: 'Asha Verma authenticator' });
      await jsonResponse(route, {
        provisioningUri:
          'otpauth://totp/ROOTOPATHY%20CareOS:asha@example.test?secret=ABCDEFGHIJKLMNOP',
        secret: 'ABCDEFGHIJKLMNOP',
      });
      return;
    }
    if (method === 'POST' && path === '/api/v1/auth/mfa/enrollments/verification') {
      expect(request.postDataJSON()).toEqual({ code: '654321' });
      enrolled = true;
      await jsonResponse(route, {
        recoveryCodes: ['2345-6789-ABCD', 'EFGH-JKLM-NPQR'],
      });
      return;
    }
    if (method === 'GET' && path === '/api/v1/organizations') {
      ++organizationRequests;
      await jsonResponse(route, [organization]);
      return;
    }
    if (method === 'GET' && path.endsWith('/setup-readiness')) {
      await jsonResponse(route, organizationReadiness);
      return;
    }
    await jsonResponse(
      route,
      {
        code: 'unexpected-request',
        correlationId: 'mandatory-enrollment-test',
        detail: `Unexpected ${method} ${path}`,
        status: 404,
        title: 'Unexpected request',
        type: 'about:blank',
      },
      404,
    );
  });

  await page.goto('/#/M1-05');
  await expect(
    page.getByRole('heading', { name: 'Set up multi-factor authentication' }),
  ).toBeVisible();
  await expect(page.getByText(/workspace access remains locked/i)).toBeVisible();
  expect(organizationRequests).toBe(0);

  await page.getByRole('button', { name: 'Set up authenticator' }).click();
  await expect(page.getByLabel('One-time authenticator setup key')).toContainText(
    'ABCDEFGHIJKLMNOP',
  );
  await page.getByLabel('Six-digit authenticator code').fill('654321');
  await page.getByRole('button', { name: 'Confirm and enable MFA' }).click();

  await expect(page.getByText('2345-6789-ABCD')).toBeVisible();
  expect(organizationRequests).toBe(0);
  await expectNoDocumentHorizontalOverflow(page, 'mandatory MFA recovery codes');
  await expectNoSeriousViolations(page, 'mandatory MFA recovery codes');

  await page.getByRole('button', { name: 'I have stored these codes securely' }).click();
  await expect(page.getByRole('heading', { name: 'Administration dashboard' })).toBeVisible();
  expect(organizationRequests).toBe(1);
});

test('password recovery uses generic responses and consumes a scrubbed one-time token', async ({
  page,
}) => {
  const resetToken = 'Case_Sensitive-Token';
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const method = request.method();

    if (method === 'GET' && path === '/api/v1/auth/session') {
      await jsonResponse(route, {
        mfaEnabled: false,
        mfaRequired: false,
        recentAuthentication: false,
        state: 'anonymous',
        user: null,
      });
      return;
    }
    if (method === 'GET' && path === '/api/v1/auth/csrf') {
      await jsonResponse(route, {
        headerName: 'X-XSRF-TOKEN',
        parameterName: '_csrf',
        token: 'password-recovery-csrf-token',
      });
      return;
    }
    if (method === 'POST' && path === '/api/v1/auth/password-reset-requests') {
      expect(request.postDataJSON()).toEqual({ email: 'person@example.test' });
      expect(request.headers()['x-xsrf-token']).toBe('password-recovery-csrf-token');
      await emptyResponse(route, 202);
      return;
    }
    if (method === 'POST' && path === '/api/v1/auth/password-resets') {
      expect(request.postDataJSON()).toEqual({
        newPassword: 'new-secure-password-27',
        token: resetToken,
      });
      expect(request.headers()['x-xsrf-token']).toBe('password-recovery-csrf-token');
      await emptyResponse(route);
      return;
    }
    await route.abort('failed');
  });

  await page.goto('/#/forgot-password');
  await page.getByLabel('Email address').fill('person@example.test');
  await page.getByRole('button', { name: 'Request reset link' }).click();
  await expect(page.getByText(/If an active account matches that address/)).toBeVisible();

  await page.goto(`/#/reset-password?token=${encodeURIComponent(resetToken)}`);
  await expect(page).toHaveURL(/#\/reset-password$/);
  await page.getByLabel('New password', { exact: true }).fill('new-secure-password-27');
  await page.getByLabel('Confirm new password').fill('new-secure-password-27');
  await page.getByRole('button', { name: 'Change password' }).click();
  await expect(page.getByRole('heading', { name: 'Password reset complete' })).toBeVisible();
});

test('recent authentication gates MFA enrollment and one-time recovery-code display', async ({
  page,
}) => {
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const method = request.method();

    if (method === 'GET' && path === '/api/v1/auth/session') {
      await jsonResponse(route, {
        ...authenticatedSession,
        recentAuthentication: false,
      });
      return;
    }
    if (method === 'GET' && path === '/api/v1/organizations') {
      await jsonResponse(route, [organization]);
      return;
    }
    if (method === 'GET' && path === '/api/v1/auth/csrf') {
      await jsonResponse(route, {
        headerName: 'X-XSRF-TOKEN',
        parameterName: '_csrf',
        token: 'mfa-administration-csrf-token',
      });
      return;
    }
    if (method === 'POST' && path === '/api/v1/auth/recent-authentications') {
      expect(request.postDataJSON()).toEqual({ password: 'current-password' });
      await emptyResponse(route);
      return;
    }
    if (method === 'POST' && path === '/api/v1/auth/mfa/enrollments') {
      expect(request.postDataJSON()).toEqual({ label: 'Asha Verma authenticator' });
      await jsonResponse(route, {
        provisioningUri:
          'otpauth://totp/ROOTOPATHY%20CareOS:asha@example.test?secret=ABCDEFGHIJKLMNOP',
        secret: 'ABCDEFGHIJKLMNOP',
      });
      return;
    }
    if (method === 'POST' && path === '/api/v1/auth/mfa/enrollments/verification') {
      expect(request.postDataJSON()).toEqual({ code: '654321' });
      await jsonResponse(route, {
        recoveryCodes: ['2345-6789-ABCD', 'EFGH-JKLM-NPQR'],
      });
      return;
    }
    await route.abort('failed');
  });

  await page.goto('/#/M1-03');
  await page.getByLabel('Current password').fill('current-password');
  await page.getByRole('button', { name: 'Verify identity' }).click();
  await expect(page.getByText('Authenticator not enabled')).toBeVisible();
  await page.getByRole('button', { name: 'Set up authenticator' }).click();
  await expect(page.getByLabel('One-time authenticator setup key')).toHaveText('ABCDEFGHIJKLMNOP');
  await page.getByLabel('Six-digit authenticator code').fill('654321');
  await page.getByRole('button', { name: 'Confirm and enable MFA' }).click();
  await expect(page.getByText('2345-6789-ABCD')).toBeVisible();
  await page.getByRole('button', { name: 'I have stored these codes securely' }).click();
  await expect(page.getByText('Authenticator enabled')).toBeVisible();
  await expect(page.getByText('2345-6789-ABCD')).toHaveCount(0);
});

test('administrator MFA reset uses the checked maker-checker transition client', async ({
  page,
}) => {
  const targetUserId = '88888888-8888-4888-8888-888888888888';
  const approvalId = '99999999-9999-4999-8999-999999999999';
  const expiresAt = '2026-09-16T12:00:00Z';
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const method = request.method();
    if (method === 'GET' && path === '/api/v1/auth/session') {
      await jsonResponse(route, authenticatedSession);
      return;
    }
    if (method === 'GET' && path === '/api/v1/organizations') {
      await jsonResponse(route, [organization]);
      return;
    }
    if (method === 'GET' && path === '/api/v1/auth/csrf') {
      await jsonResponse(route, {
        headerName: 'X-XSRF-TOKEN',
        parameterName: '_csrf',
        token: 'mfa-reset-csrf-token',
      });
      return;
    }
    const requestPath = `/api/v1/organizations/${organization.id}/users/${targetUserId}/mfa-reset-requests`;
    if (method === 'POST' && path === requestPath) {
      expect(request.postDataJSON()).toEqual({
        reason: 'Verified lost authenticator on support case CARE-42',
      });
      expect(request.headers()['idempotency-key']).toMatch(/^mfa-request:[0-9a-f-]{36}$/);
      await jsonResponse(route, { approvalId, expiresAt, status: 'pending', targetUserId }, 201);
      return;
    }
    if (method === 'POST' && path === `${requestPath}/${approvalId}/approvals`) {
      expect(request.postDataJSON()).toEqual({
        reason: 'Identity and support case independently verified',
      });
      expect(request.headers()['idempotency-key']).toMatch(/^mfa-approve:[0-9a-f-]{36}$/);
      await jsonResponse(route, { approvalId, expiresAt, status: 'approved', targetUserId });
      return;
    }
    if (method === 'POST' && path === `${requestPath}/${approvalId}/executions`) {
      expect(request.postDataJSON()).toEqual({
        reason: 'Verified lost authenticator on support case CARE-42',
      });
      expect(request.headers()['idempotency-key']).toMatch(/^mfa-execute:[0-9a-f-]{36}$/);
      await jsonResponse(route, { approvalId, expiresAt, status: 'reset', targetUserId });
      return;
    }
    await route.abort('failed');
  });

  await page.goto('/#/M1-03');
  await page.getByLabel('Target user ID').fill(targetUserId);
  await page.getByLabel('Reset reason').fill('Verified lost authenticator on support case CARE-42');
  await page.getByRole('button', { name: 'Request independent approval' }).click();
  await expect(page.getByText(/Workflow status:/)).toContainText('pending');

  await page.getByLabel('Workflow action').selectOption('approve');
  await page
    .getByLabel('Independent decision reason')
    .fill('Identity and support case independently verified');
  await page.getByRole('button', { name: 'Approve as independent checker' }).click();
  await expect(page.getByText(/Workflow status:/)).toContainText('approved');

  await page.getByLabel('Workflow action').selectOption('execute');
  await page.getByLabel('Reset reason').fill('Verified lost authenticator on support case CARE-42');
  await page.getByRole('button', { name: 'Execute approved reset' }).click();
  await expect(page.getByText(/Workflow status:/)).toContainText('reset');
  await expectNoSeriousViolations(page, 'M1-03 administrative MFA reset');
});

test('administration readiness uses the exact approved catalogue and distinct dashboard views', async ({
  page,
}) => {
  await mockAuthenticatedSession(page);

  await page.goto('/#/M1-05');
  await expect(page.getByRole('heading', { name: 'Administration dashboard' })).toBeVisible();
  await expect(page.getByText('3/15')).toBeVisible();
  await expect(page.getByText('10 blockers · 1 warnings')).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Priority exceptions' })).toBeVisible();
  await expect(page.locator('.check-list > div')).toHaveCount(5);
  await expectNoDocumentHorizontalOverflow(page, 'M1-05 readiness dashboard');
  await expectNoSeriousViolations(page, 'M1-05 readiness dashboard');

  await page.getByRole('link', { name: 'Review full checklist' }).click();
  await expect(page).toHaveURL(/#\/M1-06$/);
  await expect(page.getByRole('heading', { name: 'Setup checklist' })).toBeVisible();
  await expect(page.locator('.check-list > div')).toHaveCount(15);
  await expect(
    page.getByText('This live server projection feeds the persisted M1-21 validation', {
      exact: false,
    }),
  ).toBeVisible();
  await expect(page.getByRole('link', { name: 'Not applicable' })).toHaveAttribute(
    'href',
    '#/M1-19',
  );
  await expectNoDocumentHorizontalOverflow(page, 'M1-06 approved readiness catalogue');
  await expectNoSeriousViolations(page, 'M1-06 approved readiness catalogue');
});

test('organization profile update preserves CSRF, idempotency, and strong revision evidence', async ({
  page,
}) => {
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const method = request.method();
    if (method === 'GET' && path === '/api/v1/auth/session') {
      await jsonResponse(route, authenticatedSession);
      return;
    }
    if (method === 'GET' && path === '/api/v1/organizations') {
      await jsonResponse(route, [organization]);
      return;
    }
    if (method === 'GET' && path === `/api/v1/organizations/${organization.id}/profile`) {
      await jsonResponse(route, organizationProfile, 200, undefined, {
        ETag: '"organization-profile:4"',
      });
      return;
    }
    if (method === 'GET' && path === '/api/v1/auth/csrf') {
      await jsonResponse(route, {
        headerName: 'X-XSRF-TOKEN',
        parameterName: '_csrf',
        token: 'organization-profile-csrf-token',
      });
      return;
    }
    if (method === 'PUT' && path === `/api/v1/organizations/${organization.id}/profile`) {
      expect(request.headers()['x-xsrf-token']).toBe('organization-profile-csrf-token');
      expect(request.headers()['if-match']).toBe('"organization-profile:4"');
      expect(request.headers()['idempotency-key']).toMatch(/^organization-profile:[0-9a-f-]{36}$/);
      expect(request.postDataJSON()).toEqual({
        countryCode: 'IN',
        displayName: 'North Care Network',
        legalName: 'North Clinic Private Limited',
        locale: 'en-GB',
        organizationType: 'care_network',
        reason: 'Approved identity review CARE-42',
        timezone: 'Asia/Kolkata',
        tradingName: 'North Care',
      });
      await jsonResponse(
        route,
        {
          ...organizationProfile,
          displayName: 'North Care Network',
          locale: 'en-GB',
          lockVersion: 5,
          organizationType: 'care_network',
          tradingName: 'North Care',
        },
        200,
        undefined,
        { ETag: '"organization-profile:5"' },
      );
      return;
    }
    await route.abort('failed');
  });

  await page.goto('/#/M1-07');
  await page.getByLabel('Display name').fill('North Care Network');
  await page.getByLabel('Trading name (optional)').fill('North Care');
  await page.getByLabel('Organization type').selectOption('care_network');
  await page.getByLabel('Locale').fill('en-GB');
  await page.getByLabel('Reason for change').fill('Approved identity review CARE-42');
  await page.getByRole('button', { name: 'Save organization profile' }).click();
  await expect(page.getByRole('status')).toContainText('Organization profile saved');
  await expect(page.getByText(/Revision 5/)).toBeVisible();
  await expectNoSeriousViolations(page, 'M1-07 organization profile update');
});

test('anonymous login, organization selection, and logout use the checked browser client', async ({
  page,
}) => {
  let sessionState: 'anonymous' | 'authenticated' = 'anonymous';
  let organizationSelected = false;

  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const method = request.method();

    if (method === 'GET' && path === '/api/v1/auth/session') {
      await jsonResponse(
        route,
        sessionState === 'anonymous'
          ? {
              mfaEnabled: false,
              mfaRequired: false,
              recentAuthentication: false,
              state: 'anonymous',
              user: null,
            }
          : authenticatedSession,
      );
      return;
    }
    if (method === 'GET' && path === '/api/v1/auth/csrf') {
      await jsonResponse(route, {
        headerName: 'X-XSRF-TOKEN',
        parameterName: '_csrf',
        token: 'playwright-csrf-token-1234567890',
      });
      return;
    }
    if (method === 'POST' && path === '/api/v1/auth/login') {
      expect(request.postDataJSON()).toEqual({
        email: 'asha@example.test',
        password: 'test-password',
      });
      expect(request.headers()['x-xsrf-token']).toBe('playwright-csrf-token-1234567890');
      sessionState = 'authenticated';
      await jsonResponse(route, authenticatedSession);
      return;
    }
    if (method === 'GET' && path === '/api/v1/organizations') {
      await jsonResponse(route, [{ ...organization, selected: organizationSelected }]);
      return;
    }
    if (method === 'GET' && path === `/api/v1/organizations/${organization.id}/setup-readiness`) {
      await jsonResponse(route, organizationReadiness);
      return;
    }
    if (method === 'POST' && path === '/api/v1/auth/organization-selections') {
      expect(request.postDataJSON()).toEqual({ organizationId: organization.id });
      organizationSelected = true;
      await jsonResponse(route, organization);
      return;
    }
    if (method === 'POST' && path === '/api/v1/auth/logout') {
      sessionState = 'anonymous';
      organizationSelected = false;
      await emptyResponse(route);
      return;
    }
    await route.abort('failed');
  });

  await page.goto('/#/M1-05');
  await expect(page.getByRole('heading', { name: 'Sign in to CareOS' })).toBeVisible();
  await page.getByLabel('Email address').fill('asha@example.test');
  await page.getByLabel('Password').fill('test-password');
  await page.getByRole('button', { name: 'Continue securely' }).click();

  await expect(page.getByRole('heading', { name: 'Choose an organization' })).toBeVisible();
  await page.getByRole('button', { name: 'Open workspace' }).click();
  await expect(page.getByRole('heading', { name: 'Administration dashboard' })).toBeVisible();
  await page.locator('.topbar').getByRole('button', { name: 'Sign out' }).click();
  await expect(page.getByRole('heading', { name: 'Sign in to CareOS' })).toBeVisible();
});

test('workspace navigation and mobile menu are usable', async ({ page, isMobile }) => {
  await mockAuthenticatedSession(page);
  await page.goto('/#/M1-05');
  const viewport = page.viewportSize();
  if (!viewport) {
    throw new Error('The responsive browser project must declare a viewport.');
  }
  expect([1440, 1024, 768, 390, 320]).toContain(viewport.width);
  const usesDrawer = viewport.width <= 760;
  expect(isMobile).toBe(usesDrawer);
  const workspaceHeading = page.getByRole('heading', { name: 'Administration dashboard' });
  await expect(workspaceHeading).toBeFocused();
  const workspaceSkipLink = page.getByRole('link', { name: 'Skip to main content' });
  const workspaceUrl = page.url();
  await workspaceSkipLink.focus();
  await expect(workspaceSkipLink).toBeVisible();
  await workspaceSkipLink.press('Enter');
  await expect(page.locator('#main-content')).toBeFocused();
  expect(page.url()).toBe(workspaceUrl);
  if (usesDrawer) {
    await expect(page.getByRole('button', { name: 'Open navigation' })).toBeVisible();
    await page.getByRole('button', { name: 'Open navigation' }).click();
    await expect(page.locator('.sidebar')).toHaveClass(/is-open/);
  } else {
    await expect(page.getByRole('button', { name: 'Open navigation' })).toBeHidden();
    await expect(page.locator('.sidebar')).toBeVisible();
  }
  await page.getByRole('link', { name: 'M2', exact: true }).click();
  await expect(page).toHaveURL(/M2-01/);
  await expect(page.locator('main h1')).toHaveText('Workforce dashboard');
  await expect(page.locator('main h1')).toBeFocused();
  if (usesDrawer) {
    await expect(page.locator('.sidebar')).not.toHaveClass(/is-open/);
  }
  await expectNoDocumentHorizontalOverflow(page, `workspace navigation at ${viewport.width}px`);
});

test('an idle workspace locks at the server deadline without polling', async ({ page }) => {
  let sessionRequests = 0;
  await page.route('**/api/v1/auth/session', async (route) => {
    sessionRequests += 1;
    await jsonResponse(route, authenticatedSession, 200, '1');
  });
  await page.route('**/api/v1/organizations', (route) => jsonResponse(route, [organization]));
  await page.route(`**/api/v1/organizations/${organization.id}/setup-readiness`, (route) =>
    jsonResponse(route, organizationReadiness),
  );

  await page.goto('/#/M1-05');
  await expect(page.getByRole('heading', { name: 'Administration dashboard' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Sign in to CareOS' })).toBeVisible({
    timeout: 3_000,
  });
  await expect(page.getByRole('alert')).toContainText('Session ended');
  expect(sessionRequests).toBe(1);
});
