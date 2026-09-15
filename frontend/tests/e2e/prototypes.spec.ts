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
const routeSweepTimeout = 60_000;

async function jsonResponse(route: Route, data: unknown, status = 200, sessionExpiresIn?: string) {
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
}

async function expectNoSeriousViolations(page: Page, label: string) {
  const results = await new AxeBuilder({ page }).analyze();
  const seriousViolations = results.violations.filter((item) =>
    ['critical', 'serious'].includes(item.impact ?? ''),
  );
  expect(seriousViolations, `${label} has critical or serious Axe violations`).toEqual([]);
}

for (const { module, count, start } of routeGroups) {
  test(`${module} protected prototype routes render without serious accessibility violations`, async ({
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
      await expectNoSeriousViolations(page, id);
    }
  });
}

test('identity and fail-closed invitation states are accessible', async ({ page }) => {
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
  await expectNoSeriousViolations(page, 'M1-01 anonymous login');

  await page.goto('/#/forgot-password');
  await expect(page.getByRole('heading', { name: 'Reset your password' })).toBeVisible();
  await expectNoSeriousViolations(page, 'password-reset request');

  await page.goto('/#/reset-password?token=Case_Sensitive-Token');
  await expect(page.getByRole('heading', { name: 'Choose a new password' })).toBeVisible();
  await expectNoSeriousViolations(page, 'password-reset completion');

  await page.goto('/#/M1-02');
  await expect(page.getByRole('heading', { name: 'Invitation flow is not enabled' })).toBeVisible();
  await expectNoSeriousViolations(page, 'M1-02 unavailable invitation');

  state = 'mfa_required';
  await page.goto('/?identity=mfa#/M1-03');
  await expect(page.getByRole('heading', { name: 'Verify your identity' })).toBeVisible();
  await expectNoSeriousViolations(page, 'M1-03 MFA challenge');

  state = 'authenticated';
  selected = false;
  await page.goto('/?identity=organization#/M1-04');
  await expect(page.getByRole('heading', { name: 'Choose an organization' })).toBeVisible();
  await expectNoSeriousViolations(page, 'M1-04 organization selection');

  selected = true;
  await page.goto('/?identity=mfa-administration#/M1-03');
  await expect(page.getByRole('heading', { name: 'Multi-factor authentication' })).toBeVisible();
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
  if (isMobile) {
    await page.getByRole('button', { name: 'Open navigation' }).click();
  }
  await page.getByRole('link', { name: 'M2', exact: true }).click();
  await expect(page).toHaveURL(/M2-01/);
  await expect(page.locator('main h1')).toHaveText('Workforce dashboard');
});

test('an idle workspace locks at the server deadline without polling', async ({ page }) => {
  let sessionRequests = 0;
  await page.route('**/api/v1/auth/session', async (route) => {
    sessionRequests += 1;
    await jsonResponse(route, authenticatedSession, 200, '1');
  });
  await page.route('**/api/v1/organizations', (route) => jsonResponse(route, [organization]));

  await page.goto('/#/M1-05');
  await expect(page.getByRole('heading', { name: 'Administration dashboard' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Sign in to CareOS' })).toBeVisible({
    timeout: 3_000,
  });
  await expect(page.getByRole('alert')).toContainText('Session ended');
  expect(sessionRequests).toBe(1);
});
