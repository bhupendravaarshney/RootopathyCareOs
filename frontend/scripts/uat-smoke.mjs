import { chromium } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';

if (process.env.CAREOS_UAT_ALLOW_MUTATION !== 'true') {
  throw new Error(
    'UAT smoke creates synthetic governed records. Set CAREOS_UAT_ALLOW_MUTATION=true explicitly.',
  );
}

const baseUrl = process.env.CAREOS_UAT_BASE_URL ?? 'http://localhost:4173';
const parsedBaseUrl = new URL(baseUrl);
const localHostnames = new Set(['localhost', '127.0.0.1', '::1']);
if (
  !localHostnames.has(parsedBaseUrl.hostname) &&
  process.env.CAREOS_UAT_ALLOW_NONLOCAL !== 'true'
) {
  throw new Error(
    'Refusing to mutate a non-local environment without CAREOS_UAT_ALLOW_NONLOCAL=true.',
  );
}

const email = process.env.CAREOS_UAT_ADMIN_EMAIL ?? 'owner@rootopathy.test';
const password = process.env.CAREOS_UAT_ADMIN_PASSWORD ?? 'CareOS-Local-Only-Change-Me';
const outputDirectory = path.resolve('test-results/uat-smoke');
const repositoryRoot = path.resolve('..');
const commit =
  process.env.GITHUB_SHA ??
  execFileSync('git', ['rev-parse', 'HEAD'], {
    cwd: repositoryRoot,
    encoding: 'utf8',
  }).trim();
const marker = `${Date.now().toString().slice(-8)}-${randomUUID().slice(0, 6)}`;
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const apiFailures = [];
const consoleErrors = [];
const expectedFailures = [];
const created = [];
const actionChecks = [
  ['M1-02', 'button', 'Invite administrator'],
  ['M1-12', 'button', 'Add facility'],
  ['M1-14', 'button', 'Add hierarchy draft'],
  ['M1-15', 'button', 'Create location draft'],
  ['M2-03', 'button', 'Start pathway'],
  ['P3-03', 'button', 'Start registration'],
  ['P4-04', 'button', 'Start request'],
  ['P5-01', 'link', 'Open encounter'],
  ['COS-01', 'button', 'Start assessment'],
  ['P7-03', 'button', 'Upload document'],
  ['P8-01', 'button', 'Launch AI session'],
  ['P9-02', 'button', 'Create coordinated plan'],
  ['P10-01', 'button', 'Create monitoring plan'],
  ['P11-02', 'button', 'Create price book'],
  ['P12-02', 'button', 'Run report'],
  ['P13-02', 'button', 'Create connection'],
];

await mkdir(outputDirectory, { recursive: true });
const browser = await chromium.launch({ headless: true });
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
await context.tracing.start({ screenshots: true, snapshots: true, sources: true });
const page = await context.newPage();
let activeStep = 'authentication';

page.on('response', (response) => {
  if (response.url().includes('/api/') && response.status() >= 400) {
    apiFailures.push({ step: activeStep, status: response.status(), url: response.url() });
  }
});
page.on('console', (message) => {
  if (message.type() === 'error') {
    consoleErrors.push({ step: activeStep, text: message.text() });
  }
});

async function settle() {
  await page
    .waitForFunction(
      () =>
        !document.querySelector('main .spin') && !document.querySelector('main [aria-busy="true"]'),
      undefined,
      { timeout: 10_000 },
    )
    .catch(() => undefined);
}

async function visit(route) {
  activeStep = route;
  await page.goto(`${baseUrl}/#/${route}`, { waitUntil: 'domcontentloaded' });
  await page.locator('h1').first().waitFor({ timeout: 10_000 });
  await settle();
}

async function visibleAction(route, role, name) {
  await visit(route);
  const actionPanel = page.locator('.workforce-actions');
  const action =
    (await actionPanel.count()) > 0
      ? actionPanel.getByRole(role, { name, exact: true })
      : page.locator('main').getByRole(role, { name, exact: true });
  await action.first().waitFor({ state: 'visible' });
  return { action: name, route };
}

async function submitAndReadProjection(dialog, buttonName, urlFragment) {
  const responsePromise = page.waitForResponse(
    (response) => response.request().method() === 'POST' && response.url().includes(urlFragment),
    { timeout: 15_000 },
  );
  await dialog.getByRole('button', { name: buttonName, exact: true }).click();
  const response = await responsePromise;
  if (!response.ok()) {
    throw new Error(`${buttonName} returned unexpected HTTP ${response.status()}.`);
  }
  return response.json();
}

let report;
try {
  await visit('M1-05');
  await page.getByRole('heading', { name: 'Sign in to CareOS' }).waitFor();
  await page.getByLabel('Email address').fill(email);
  await page.getByLabel('Password').fill(password);
  await page.getByRole('button', { name: 'Continue securely' }).click();
  const organizationHeading = page.getByRole('heading', { name: 'Choose an organization' });
  const dashboardHeading = page.getByRole('heading', { name: 'Administration dashboard' });
  await organizationHeading.or(dashboardHeading).waitFor();
  if (await organizationHeading.isVisible()) {
    await page.getByRole('button', { name: 'Open workspace' }).click();
  }
  await dashboardHeading.waitFor();

  const availableActions = [];
  for (const [route, role, name] of actionChecks) {
    availableActions.push(await visibleAction(route, role, name));
  }

  activeStep = 'create-service-draft';
  await visit('M1-17');
  const serviceCode = `UAT${marker.replaceAll('-', '').toUpperCase()}`;
  const serviceName = `Synthetic UAT consultation ${marker}`;
  await page.getByLabel('Service code').fill(serviceCode);
  await page.getByLabel('Display name').fill(serviceName);
  await page
    .getByLabel('Description')
    .fill('Synthetic service created by the UAT readiness smoke test.');
  await page
    .getByLabel('Reason')
    .fill('Verify governed service draft creation for local UAT readiness.');
  await page.getByRole('button', { name: 'Add service', exact: true }).click();
  await page.getByText(serviceName, { exact: true }).waitFor();
  created.push({ kind: 'service-draft', reference: serviceCode });
  await page.screenshot({
    fullPage: true,
    path: path.join(outputDirectory, 'service-draft-created.png'),
  });

  activeStep = 'create-workforce-pathway';
  await visit('M2-03');
  for (const sequence of [1, 2]) {
    const displayName = `Synthetic Tester ${marker}-${sequence}`;
    await page.getByRole('button', { name: 'Start pathway', exact: true }).click();
    const workforceDialog = page.getByRole('dialog');
    await workforceDialog.getByLabel('Pathway').selectOption('non_clinical');
    await workforceDialog.getByLabel('Legal given name').fill('Synthetic');
    await workforceDialog.getByLabel('Legal family name').fill(`Tester ${marker}-${sequence}`);
    await workforceDialog.getByLabel('Display name').fill(displayName);
    await workforceDialog
      .getByLabel('Work email')
      .fill(`uat.${marker}.${sequence}@rootopathy.test`);
    await workforceDialog.getByLabel('Account access intent').selectOption('none_required');
    await workforceDialog
      .getByLabel('Reason')
      .fill('Verify successive workforce onboarding creation for local UAT readiness.');
    await workforceDialog.getByRole('button', { name: 'Start pathway', exact: true }).click();
    await page.getByRole('status').filter({ hasText: 'completed' }).first().waitFor();
    created.push({ kind: 'workforce-pathway', reference: displayName });
  }
  await page.screenshot({
    fullPage: true,
    path: path.join(outputDirectory, 'workforce-pathway-created.png'),
  });

  activeStep = 'create-normal-registration';
  await visit('P3-03');
  const registrationSource = `uat_${marker}`;
  await page.getByRole('button', { name: 'Start registration', exact: true }).click();
  const registrationDialog = page.getByRole('dialog');
  await registrationDialog.getByLabel('Registration source').fill(registrationSource);
  await registrationDialog.getByLabel('Supplier relationship').fill('direct_care');
  await registrationDialog.getByLabel('Purpose').fill('treatment');
  await registrationDialog.getByLabel('Urgent temporary pathway').selectOption('false');
  await registrationDialog
    .getByLabel(/^Reason/)
    .fill('Verify normal patient registration start for local UAT readiness.');
  await submitAndReadProjection(
    registrationDialog,
    'Start registration',
    '/patients/screens/P3-03/actions/start-registration',
  );
  await page.getByRole('status').filter({ hasText: 'completed' }).waitFor();
  created.push({ kind: 'patient-registration-run', reference: registrationSource });
  await page.screenshot({
    fullPage: true,
    path: path.join(outputDirectory, 'normal-registration-created.png'),
  });

  activeStep = 'complete-patient-registration';
  await visit('P3-04');
  let registrationRow = page.locator('tbody tr').filter({ hasText: registrationSource }).first();
  await registrationRow.getByRole('radio').check();

  await page.getByRole('button', { name: 'Run bounded search', exact: true }).click();
  const searchDialog = page.getByRole('dialog');
  await searchDialog.getByLabel('Official or supplied name').fill(`Synthetic Patient ${marker}`);
  await searchDialog
    .getByLabel(/^Reason/)
    .fill('Verify duplicate search before creating a synthetic UAT patient.');
  await submitAndReadProjection(
    searchDialog,
    'Run bounded search',
    '/patients/screens/P3-04/actions/search-duplicates',
  );
  await page.getByRole('status').filter({ hasText: 'completed' }).waitFor();

  await page.getByRole('button', { name: 'Record disposition', exact: true }).click();
  const dispositionDialog = page.getByRole('dialog');
  await dispositionDialog.getByLabel('Disposition').selectOption('create_new');
  await dispositionDialog.getByLabel('Official given name').fill('Synthetic');
  await dispositionDialog.getByLabel('Official family name').fill(`Patient ${marker}`);
  await dispositionDialog.getByLabel('Name to use').fill(`UAT Patient ${marker}`);
  await dispositionDialog.getByLabel('Provenance code').fill('uat_smoke');
  await dispositionDialog
    .getByLabel(/^Reason/)
    .fill('Create a synthetic UAT patient after the governed duplicate search.');
  const dispositionProjection = await submitAndReadProjection(
    dispositionDialog,
    'Record disposition',
    '/patients/screens/P3-04/actions/record-registration-disposition',
  );
  const draftPatientId = dispositionProjection.rows?.find((row) => row.patientId)?.patientId;
  if (!draftPatientId || !uuidPattern.test(draftPatientId)) {
    throw new Error('Registration disposition did not return a valid patient identifier.');
  }
  await page.getByRole('status').filter({ hasText: 'completed' }).waitFor();

  await visit('P3-12');
  registrationRow = page.locator('tbody tr').filter({ hasText: registrationSource }).first();
  await registrationRow.getByRole('radio').check();
  await page.getByRole('button', { name: 'Run registration validation', exact: true }).click();
  const validationDialog = page.getByRole('dialog');
  await validationDialog
    .getByLabel(/^Reason/)
    .fill('Validate the exact synthetic registration revision before submission.');
  await submitAndReadProjection(
    validationDialog,
    'Run registration validation',
    '/patients/screens/P3-12/actions/validate-registration',
  );
  await page.getByRole('status').filter({ hasText: 'completed' }).waitFor();

  await page.getByRole('button', { name: 'Register patient', exact: true }).click();
  const submitRegistrationDialog = page.getByRole('dialog');
  await submitRegistrationDialog
    .getByLabel(/^Reason/)
    .fill('Complete the validated synthetic patient registration for UAT.');
  const completedProjection = await submitAndReadProjection(
    submitRegistrationDialog,
    'Register patient',
    '/patients/screens/P3-12/actions/submit-registration',
  );
  const patientId = completedProjection.rows?.find((row) => row.patientId)?.patientId;
  if (!patientId || !uuidPattern.test(patientId) || patientId !== draftPatientId) {
    throw new Error('Completed registration did not retain the governed patient identifier.');
  }
  created.push({ kind: 'registered-patient', reference: patientId });
  await page.screenshot({
    fullPage: true,
    path: path.join(outputDirectory, 'patient-registration-completed.png'),
  });

  activeStep = 'upload-private-document';
  await visit('P7-03');
  await page.getByRole('button', { name: 'Upload document', exact: true }).click();
  const uploadDialog = page.getByRole('dialog');
  await uploadDialog.getByLabel('Patient').fill(patientId);
  await uploadDialog.getByLabel('Document title').fill(`Synthetic UAT note ${marker}`);
  await uploadDialog.getByLabel('Document type').selectOption('clinical_note');
  await uploadDialog.getByLabel('Source').fill('uat_smoke');
  await uploadDialog.getByLabel('File').setInputFiles({
    buffer: Buffer.from(`Synthetic CareOS UAT document ${marker}\n`, 'utf8'),
    mimeType: 'text/plain',
    name: `careos-uat-${marker}.txt`,
  });
  await uploadDialog
    .getByLabel(/^Reason/)
    .fill('Verify exact private quarantine upload for the synthetic UAT patient.');
  const documentProjection = await submitAndReadProjection(
    uploadDialog,
    'Upload document',
    '/documents',
  );
  const uploadedDocument = documentProjection.rows?.find(
    (row) => row.patientId === patientId && row.status === 'quarantined',
  );
  if (!uploadedDocument) {
    throw new Error('Document upload did not return a quarantined patient-linked version.');
  }
  created.push({
    kind: 'quarantined-document',
    reference: uploadedDocument.documentId ?? uploadedDocument.id,
  });
  await page.getByRole('status').filter({ hasText: 'completed' }).waitFor();
  await page.screenshot({
    fullPage: true,
    path: path.join(outputDirectory, 'document-quarantined.png'),
  });

  await visit('P3-03');
  activeStep = 'urgent-registration-fail-closed';
  await page.getByRole('button', { name: 'Start registration', exact: true }).click();
  const urgentDialog = page.getByRole('dialog');
  await urgentDialog.getByLabel('Registration source').fill('urgent_care');
  await urgentDialog.getByLabel('Supplier relationship').fill('direct_care');
  await urgentDialog.getByLabel('Purpose').fill('urgent_care');
  await urgentDialog.getByLabel('Urgent temporary pathway').selectOption('true');
  await urgentDialog.getByLabel('Urgent reason code').fill('identity_unavailable');
  await urgentDialog
    .getByLabel(/^Reason/)
    .fill('Verify urgent temporary identity remains fail closed without its worker.');
  await urgentDialog.getByRole('button', { name: 'Start registration', exact: true }).click();
  const urgentIssue = urgentDialog.getByRole('alert');
  await urgentIssue.getByText(/reconciliation worker/i).waitFor();
  expectedFailures.push({
    outcome: 'DENIED',
    safeguard: 'urgent-temporary-identity-reconciliation-worker',
  });
  await page.screenshot({
    fullPage: true,
    path: path.join(outputDirectory, 'urgent-registration-denied.png'),
  });

  const expectedApiFailures = apiFailures.filter(
    (failure) => failure.step === 'urgent-registration-fail-closed' && failure.status === 409,
  );
  const unexpectedApiFailures = apiFailures.filter(
    (failure) => !expectedApiFailures.includes(failure),
  );
  const expectedConsoleErrors = consoleErrors.filter(
    (entry) =>
      entry.step === 'urgent-registration-fail-closed' &&
      entry.text.includes('Failed to load resource') &&
      entry.text.includes('409'),
  );
  const unexpectedConsoleErrors = consoleErrors.filter(
    (entry) => !expectedConsoleErrors.includes(entry),
  );
  if (
    expectedApiFailures.length !== 1 ||
    unexpectedApiFailures.length > 0 ||
    unexpectedConsoleErrors.length > 0
  ) {
    throw new Error('UAT smoke observed an unexpected API or console outcome.');
  }

  report = {
    status: 'PASS',
    baseUrl,
    commit,
    generatedAt: new Date().toISOString(),
    marker,
    syntheticDataOnly: true,
    availableActions,
    created,
    expectedFailures,
    expectedApiFailures,
    unexpectedApiFailures,
    unexpectedConsoleErrors,
  };
} catch (error) {
  await page
    .screenshot({ fullPage: true, path: path.join(outputDirectory, 'failure.png') })
    .catch(() => undefined);
  report = {
    status: 'FAIL',
    baseUrl,
    commit,
    generatedAt: new Date().toISOString(),
    failedAtStep: activeStep,
    marker,
    syntheticDataOnly: true,
    created,
    apiFailures,
    consoleErrors,
    error: error instanceof Error ? { message: error.message, name: error.name } : String(error),
  };
  process.exitCode = 1;
} finally {
  await writeFile(
    path.join(outputDirectory, 'report.json'),
    `${JSON.stringify(report, null, 2)}\n`,
  );
  await context.tracing
    .stop({ path: path.join(outputDirectory, 'trace.zip') })
    .catch(() => undefined);
  await context.close();
  await browser.close();
}

process.stdout.write(
  `${JSON.stringify(
    {
      status: report.status,
      actionChecks: report.availableActions?.length ?? 0,
      created: report.created?.length ?? 0,
      expectedFailClosedChecks: report.expectedFailures?.length ?? 0,
      unexpectedApiFailures: report.unexpectedApiFailures?.length ?? apiFailures.length,
      unexpectedConsoleErrors: report.unexpectedConsoleErrors?.length ?? consoleErrors.length,
    },
    null,
    2,
  )}\n`,
);
