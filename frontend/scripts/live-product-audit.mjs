import { chromium } from '@playwright/test';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';

const baseUrl = process.env.CAREOS_LIVE_BASE_URL ?? 'http://localhost:4173';
const email = process.env.CAREOS_LIVE_ADMIN_EMAIL ?? 'owner@rootopathy.test';
const password = process.env.CAREOS_LIVE_ADMIN_PASSWORD ?? 'CareOS-Local-Only-Change-Me';
const outputDirectory = path.resolve('test-results/live-product-audit');

const routeRanges = [
  ['M2', 29],
  ['P3', 16],
  ['P4', 15],
  ['P5', 12],
  ['COS', 27],
  ['P7', 11],
  ['P8', 10],
  ['P9', 12],
  ['P10', 9],
  ['P11', 11],
  ['P12', 10],
  ['P13', 10],
];
const routes = [
  'M1-02',
  'M1-03',
  ...Array.from({ length: 19 }, (_, index) => `M1-${String(index + 5).padStart(2, '0')}`),
  ...routeRanges.flatMap(([prefix, count]) =>
    Array.from({ length: count }, (_, index) => `${prefix}-${String(index + 1).padStart(2, '0')}`),
  ),
];
const protectedRoutes = new Set(['M1-23', 'M2-12', 'M2-27']);
const screenshotRoutes = new Set(['M1-05', 'M2-01', 'P3-01', 'P11-01']);
const internalCode = /\b(?:M\d+|P\d+|COS)(?:-\d{2})?\b/g;

await mkdir(outputDirectory, { recursive: true });
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
const apiFailures = [];
const consoleErrors = [];
let activeRoute = 'authentication';

page.on('response', (response) => {
  if (response.url().includes('/api/') && response.status() >= 400) {
    apiFailures.push({ route: activeRoute, status: response.status(), url: response.url() });
  }
});
page.on('console', (message) => {
  if (message.type() === 'error') {
    consoleErrors.push({ route: activeRoute, text: message.text() });
  }
});

async function settle() {
  await page
    .waitForFunction(
      () =>
        !document.querySelector('main .spin') &&
        !document.querySelector('main .spinner') &&
        !document.querySelector('main [aria-busy="true"]'),
      undefined,
      { timeout: 5_000 },
    )
    .catch(() => undefined);
  await page.waitForTimeout(40);
}

try {
  await page.goto(`${baseUrl}/#/M1-05`, { waitUntil: 'domcontentloaded' });
  await page.getByRole('heading', { name: 'Sign in to CareOS' }).waitFor();
  await page.getByLabel('Email address').fill(email);
  await page.getByLabel('Password').fill(password);
  await page.getByRole('button', { name: 'Continue securely' }).click();
  const organizationHeading = page.getByRole('heading', { name: 'Choose an organization' });
  const dashboardHeading = page.getByRole('heading', { name: 'Administration dashboard' });
  await organizationHeading.or(dashboardHeading).waitFor();
  const organizationSelectionShown = await organizationHeading.isVisible();
  if (organizationSelectionShown) {
    await page.getByRole('button', { name: 'Open workspace' }).click();
    await dashboardHeading.waitFor();
  }
  await settle();

  const authenticationChecks = {
    login: true,
    organizationSelection: organizationSelectionShown,
    administratorName: await page.locator('.user-label').textContent(),
    demoBannerVisible: await page.getByText('Local demo administrator').isVisible(),
    workspaceLinks: await page.locator('.administrator-workspace-grid a').count(),
  };
  const routeResults = [];

  for (const route of routes) {
    activeRoute = route;
    await page.evaluate((screenId) => {
      window.location.hash = `#/${screenId}`;
    }, route);
    await page.waitForFunction(
      (screenId) => window.location.hash.toUpperCase() === `#/${screenId}`,
      route,
    );
    await page.locator('h1').first().waitFor({ timeout: 5_000 });
    await settle();

    const purposePrompt = (await page.locator('main .workforce-purpose-panel').count()) > 0;
    if (purposePrompt) {
      await page
        .getByLabel('Access reason')
        .fill('Approved local QA audit of the workforce administration view.');
      await page.getByRole('button', { name: 'Continue to protected view' }).click();
      await settle();
    }

    const visiblePageText = await page.locator('body').innerText();
    const exposedCodes = [...new Set(visiblePageText.match(internalCode) ?? [])];
    const result = {
      route,
      title: (await page.locator('h1').first().textContent())?.trim() ?? '',
      alerts: await page.locator('main [role="alert"]').count(),
      demoPreview: (await page.locator('main .local-demo-data').count()) > 0,
      liveRows: await page.locator('main .workforce-table tbody tr').count(),
      purposePrompt,
      exposedCodes,
    };
    routeResults.push(result);

    if (screenshotRoutes.has(route)) {
      await page.screenshot({
        fullPage: true,
        path: path.join(outputDirectory, `${route}.png`),
      });
    }
  }

  await page.waitForTimeout(250);
  const unexpectedApiFailures = apiFailures.filter(
    (failure) => !(failure.status === 428 && protectedRoutes.has(failure.route)),
  );
  const exposedCodeRoutes = routeResults.filter((result) => result.exposedCodes.length > 0);
  const unexpectedConsoleErrors = consoleErrors.filter(
    (entry) =>
      !(
        protectedRoutes.has(entry.route) &&
        entry.text.includes('Failed to load resource') &&
        entry.text.includes('428')
      ),
  );
  const moduleRoutesWithoutContent = routeResults.filter(
    (result) =>
      !result.route.startsWith('M1-') &&
      !protectedRoutes.has(result.route) &&
      !result.demoPreview &&
      result.liveRows === 0 &&
      !result.purposePrompt,
  );
  const report = {
    baseUrl,
    generatedAt: new Date().toISOString(),
    authenticationChecks,
    routesVisited: routeResults.length,
    routesWithLiveRows: routeResults.filter((result) => result.liveRows > 0).length,
    routesWithDemoPreviews: routeResults.filter((result) => result.demoPreview).length,
    routesWithPurposePrompts: routeResults.filter((result) => result.purposePrompt).length,
    expectedProtectedResponses: apiFailures.filter(
      (failure) => failure.status === 428 && protectedRoutes.has(failure.route),
    ),
    unexpectedApiFailures,
    consoleErrors,
    unexpectedConsoleErrors,
    exposedCodeRoutes,
    moduleRoutesWithoutContent,
    routeResults,
  };
  await writeFile(
    path.join(outputDirectory, 'report.json'),
    `${JSON.stringify(report, null, 2)}\n`,
  );
  process.stdout.write(
    `${JSON.stringify(
      {
        authenticationChecks,
        expectedProtectedConsoleMessages: consoleErrors.length - unexpectedConsoleErrors.length,
        expectedProtectedResponses: report.expectedProtectedResponses.length,
        exposedCodeRoutes: exposedCodeRoutes.length,
        moduleRoutesWithoutContent: moduleRoutesWithoutContent.length,
        routesVisited: report.routesVisited,
        routesWithDemoPreviews: report.routesWithDemoPreviews,
        routesWithLiveRows: report.routesWithLiveRows,
        routesWithPurposePrompts: report.routesWithPurposePrompts,
        unexpectedApiFailures: unexpectedApiFailures.length,
        unexpectedConsoleErrors: unexpectedConsoleErrors.length,
      },
      null,
      2,
    )}\n`,
  );
  if (
    !authenticationChecks.demoBannerVisible ||
    authenticationChecks.workspaceLinks !== 13 ||
    unexpectedApiFailures.length > 0 ||
    unexpectedConsoleErrors.length > 0 ||
    exposedCodeRoutes.length > 0 ||
    moduleRoutesWithoutContent.length > 0
  ) {
    process.exitCode = 1;
  }
} finally {
  await browser.close();
}
