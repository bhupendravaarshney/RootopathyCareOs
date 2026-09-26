import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';

const candidateUrl = new URL(
  '../../../candidate-inputs/module-3/01-screen-mockups.html',
  import.meta.url,
).href;

test('Module 3 candidate mockup is complete, responsive, accessible, and review-only', async ({
  page,
}) => {
  await page.goto(candidateUrl);

  await expect(page.locator('meta[name="careos:status"]')).toHaveAttribute(
    'content',
    'CANDIDATE_FOR_APPROVAL',
  );
  await expect(page.locator('.candidate-pill')).toContainText('CANDIDATE FOR APPROVAL');
  await expect(page.locator('.boundary')).toContainText('No real patient data');
  await expect(page.locator('.boundary')).toContainText('Never auto-merge');
  await expect(page.locator('#screen-nav button')).toHaveCount(16);

  const viewport = page.viewportSize();
  expect(viewport).not.toBeNull();
  expect([1440, 1024, 768, 390, 320]).toContain(viewport?.width);

  if ((viewport?.width ?? 0) <= 760) {
    await page.getByRole('button', { name: 'Open screen navigation' }).click();
    await expect(page.locator('#screen-navigation')).toHaveClass(/open/);
  }

  await page.getByRole('button', { name: /P3-15.*Merge review/ }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Merge review' })).toBeFocused();
  await expect(page.getByText('No routine unmerge', { exact: true })).toBeVisible();
  await expect(page.getByText('Awaiting independent checker', { exact: true })).toBeVisible();

  await page.locator('#state-select').selectOption('conflict');
  await expect(page.getByRole('heading', { level: 2, name: 'The record changed' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Review recovery behavior' })).toBeVisible();

  await page.locator('#state-select').selectOption('default');
  const mergeAction = page
    .getByRole('button', { name: 'Request independent merge decision' })
    .first();
  await mergeAction.click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await expect(
    page.getByRole('heading', { level: 2, name: 'Request independent merge decision' }),
  ).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog')).toBeHidden();
  await expect(mergeAction).toBeFocused();

  if ((viewport?.width ?? 0) <= 760) {
    await page.getByRole('button', { name: 'Open screen navigation' }).click();
  }
  await page.getByRole('button', { name: /P3-16.*Identity and audit timeline/ }).click();
  await expect(
    page.getByRole('heading', { level: 1, name: 'Identity and audit timeline' }),
  ).toBeFocused();
  await expect(page.getByText('Minimum necessary', { exact: true })).toBeVisible();

  const documentWidths = await page.evaluate(() => ({
    body: document.body.scrollWidth,
    document: document.documentElement.scrollWidth,
    viewport: window.innerWidth,
  }));
  expect(documentWidths.body).toBeLessThanOrEqual(documentWidths.viewport);
  expect(documentWidths.document).toBeLessThanOrEqual(documentWidths.viewport);

  const seriousViolations = (await new AxeBuilder({ page }).analyze()).violations.filter(
    ({ impact }) => impact === 'serious' || impact === 'critical',
  );
  expect(seriousViolations).toEqual([]);
});
