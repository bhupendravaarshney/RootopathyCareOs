import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';

const candidateUrl = new URL(
  '../../../candidate-inputs/module-2/01-screen-mockups.html',
  import.meta.url,
).href;

test('Module 2 candidate mockup is complete, responsive, accessible, and review-only', async ({
  page,
}) => {
  await page.goto(candidateUrl);

  await expect(page.locator('meta[name="careos:status"]')).toHaveAttribute(
    'content',
    'CANDIDATE_FOR_APPROVAL',
  );
  await expect(page.locator('.candidate-pill')).toContainText('CANDIDATE FOR APPROVAL');
  await expect(page.locator('.boundary')).toContainText('performs no real save');
  await expect(page.locator('#screen-nav button')).toHaveCount(29);

  const viewport = page.viewportSize();
  expect(viewport).not.toBeNull();
  expect([1440, 1024, 768, 390, 320]).toContain(viewport?.width);

  if ((viewport?.width ?? 0) <= 760) {
    await page.getByRole('button', { name: 'Open screen navigation' }).click();
    await expect(page.locator('#screen-navigation')).toHaveClass(/open/);
  }

  await page.getByRole('button', { name: /M2-29.*Lifecycle and evidence timeline/ }).click();
  await expect(
    page.getByRole('heading', { level: 1, name: 'Lifecycle and evidence timeline' }),
  ).toBeFocused();
  await expect(page.getByText('Minimum necessary', { exact: true })).toBeVisible();

  await page.locator('#state-select').selectOption('conflict');
  await expect(page.getByRole('heading', { level: 2, name: 'The record changed' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Review recovery behavior' })).toBeVisible();

  await page.locator('#state-select').selectOption('default');
  const action = page.getByRole('button', { name: 'Request timeline export' }).first();
  await action.click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await expect(
    page.getByRole('heading', { level: 2, name: 'Request timeline export' }),
  ).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog')).toBeHidden();
  await expect(action).toBeFocused();

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
