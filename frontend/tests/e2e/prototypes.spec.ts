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
  roleKeys: ['organization-member'],
  selected: true,
  status: 'active',
};
const organizationReadiness = {
  activeMemberships: 2,
  completedGates: 2,
  draftFacilityCount: 1,
  facilityCount: 1,
  gates: [
    {
      detail: 'Legal and display identity are recorded.',
      href: '#/M1-07',
      key: 'organization-profile',
      label: 'Organization profile',
      status: 'complete',
    },
    {
      detail: 'An effective owner is present.',
      href: '#/M1-20',
      key: 'administrator-access',
      label: 'Administrator access',
      status: 'complete',
    },
    {
      detail: 'Activation policy is not approved.',
      href: '#/M1-21',
      key: 'activation',
      label: 'Review and activate',
      status: 'blocked',
    },
  ],
  lifecycleStatus: 'active',
  organizationId: organization.id,
  totalGates: 3,
};
const organizationProfile = {
  countryCode: 'IN',
  displayName: 'North Clinic',
  legalName: 'North Clinic Private Limited',
  lifecycleStatus: 'active',
  lockVersion: 4,
  organizationId: organization.id,
  timezone: 'Asia/Kolkata',
  updatedAt: '2026-09-16T08:00:00Z',
};
const authenticatedSession = {
  mfaEnabled: false,
  recentAuthentication: true,
  state: 'authenticated',
  user,
};
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
    (data.state === 'authenticated' || data.state === 'mfa_required');
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
      await expectNoDocumentHorizontalOverflow(page, id);
      await expectNoSeriousViolations(page, id);
    }
  });
}

test('synthetic screens expose honest action boundaries and usable local filters', async ({
  page,
}) => {
  await mockAuthenticatedSession(page);

  await page.goto('/#/M1-12');
  await expect(page.getByRole('complementary', { name: 'Synthetic prototype only' })).toContainText(
    'Do not enter real personal or clinical information',
  );
  await expect(page.getByRole('button', { name: /Open.*unavailable/ })).toHaveCount(4);
  for (const action of await page.getByRole('button', { name: /Open.*unavailable/ }).all()) {
    await expect(action).toBeDisabled();
  }
  await expect(page.getByRole('link', { name: 'Synthetic facility A' })).toHaveCount(0);
  const recordsRegion = page.getByRole('region', { name: 'Facilities records' });
  await recordsRegion.focus();
  await expect(recordsRegion).toBeFocused();

  const clearFilters = page.getByRole('button', { name: 'Clear filters' });
  await expect(clearFilters).toBeDisabled();
  await page.getByLabel('Search', { exact: true }).fill('governance');
  await expect(clearFilters).toBeEnabled();
  await expect(page.getByText('Showing 1 of 4 synthetic records')).toBeVisible();
  await expect(page.getByText('Synthetic governance group')).toBeVisible();
  await clearFilters.click();
  await expect(page.getByLabel('Search', { exact: true })).toHaveValue('');
  await expect(page.getByText('Showing 4 of 4 synthetic records')).toBeVisible();

  await page.goto('/#/M1-08');
  await expect(page.getByLabel('Record name')).toHaveAttribute('readonly', '');
  await expect(page.getByRole('combobox', { name: 'Type' })).toBeDisabled();
  await expect(page.getByLabel('Reason for change')).toHaveAttribute('readonly', '');
  await expect(page.getByRole('button', { name: /Save draft.*unavailable/ })).toBeDisabled();
  await expect(page.getByRole('button', { name: /Save and continue.*unavailable/ })).toBeDisabled();
  await expect(page.getByText(/Prototype interaction saved locally/)).toHaveCount(0);

  await page.goto('/#/M1-21');
  await expect(page.getByRole('button', { name: /Review.*unavailable/ })).toHaveCount(4);
  for (const action of await page.getByRole('button', { name: /Review.*unavailable/ }).all()) {
    await expect(action).toBeDisabled();
  }

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
      recentAuthentication: state === 'authenticated',
      state,
      user: state === 'anonymous' ? null : user,
    }),
  );
  await page.route('**/api/v1/organizations', (route) =>
    jsonResponse(route, [{ ...organization, selected }]),
  );

  await page.goto('/#/M1-01');
  await expect(page.getByRole('heading', { name: 'Sign in to CareOS' })).toBeVisible();
  await expectNoDocumentHorizontalOverflow(page, 'M1-01 anonymous login');
  await expectNoSeriousViolations(page, 'M1-01 anonymous login');

  await page.goto('/#/forgot-password');
  await expect(page.getByRole('heading', { name: 'Reset your password' })).toBeVisible();
  await expectNoDocumentHorizontalOverflow(page, 'password-reset request');
  await expectNoSeriousViolations(page, 'password-reset request');

  await page.goto('/#/reset-password?token=Case_Sensitive-Token');
  await expect(page.getByRole('heading', { name: 'Choose a new password' })).toBeVisible();
  await expectNoDocumentHorizontalOverflow(page, 'password-reset completion');
  await expectNoSeriousViolations(page, 'password-reset completion');

  await page.goto('/#/accept-invitation?token=Case_Sensitive-Invitation-Token-1234567890');
  await expect(page.getByRole('heading', { name: 'Accept your CareOS invitation' })).toBeVisible();
  await expectNoDocumentHorizontalOverflow(page, 'M1-02 invitation acceptance');
  await expectNoSeriousViolations(page, 'M1-02 invitation acceptance');

  state = 'mfa_required';
  await page.goto('/?identity=mfa#/M1-03');
  await expect(page.getByRole('heading', { name: 'Verify your identity' })).toBeVisible();
  await expectNoDocumentHorizontalOverflow(page, 'M1-03 MFA challenge');
  await expectNoSeriousViolations(page, 'M1-03 MFA challenge');

  state = 'authenticated';
  selected = false;
  await page.goto('/?identity=organization#/M1-04');
  await expect(page.getByRole('heading', { name: 'Choose an organization' })).toBeVisible();
  await expectNoDocumentHorizontalOverflow(page, 'M1-04 organization selection');
  await expectNoSeriousViolations(page, 'M1-04 organization selection');

  selected = true;
  await page.goto('/?identity=invitations#/M1-02');
  await expect(page.getByRole('heading', { name: 'Organization invitations' })).toBeVisible();
  await expectNoDocumentHorizontalOverflow(page, 'M1-02 invitation administration');
  await expectNoSeriousViolations(page, 'M1-02 invitation administration');

  await page.goto('/?identity=mfa-administration#/M1-03');
  await expect(page.getByRole('heading', { name: 'Multi-factor authentication' })).toBeVisible();
  await expectNoDocumentHorizontalOverflow(page, 'M1-03 MFA administration');
  await expectNoSeriousViolations(page, 'M1-03 MFA administration');
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
  await page.getByRole('button', { name: 'Set up an authenticator' }).click();
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
        reason: 'Approved identity review CARE-42',
        timezone: 'Asia/Kolkata',
      });
      await jsonResponse(
        route,
        { ...organizationProfile, displayName: 'North Care Network', lockVersion: 5 },
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
          ? { mfaEnabled: false, recentAuthentication: false, state: 'anonymous', user: null }
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
  await page.getByRole('button', { name: 'Sign in securely' }).click();

  await expect(page.getByRole('heading', { name: 'Choose an organization' })).toBeVisible();
  await page.getByRole('button', { name: 'Continue to workspace' }).click();
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
