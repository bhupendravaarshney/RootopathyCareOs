import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';

const candidateUrl = new URL(
  '../../../candidate-inputs/module-1/01-screen-mockups.html',
  import.meta.url,
).href;

test('Module 1 candidate mockup is complete, responsive, and review-only', async ({ page }) => {
  await page.goto(candidateUrl);

  await expect(page.getByText('CANDIDATE FOR APPROVAL', { exact: true })).toBeVisible();
  await expect(page.locator('.candidate-banner')).toContainText('no data is saved or sent');
  await expect(page.locator('[data-screen]')).toHaveCount(23);

  const viewport = page.viewportSize();
  expect(viewport).not.toBeNull();
  expect([1440, 1024, 768, 390, 320]).toContain(viewport?.width);

  if ((viewport?.width ?? 0) <= 760) {
    await page.getByRole('button', { name: 'Open screen navigation' }).click();
    await expect(page.locator('#sidebar')).toHaveClass(/open/);
  }

  await page.locator('[data-screen="M1-23"]').click();
  await expect(page.getByRole('heading', { level: 1, name: 'Audit log' })).toBeFocused();
  await expect(page.locator('#screen-operation')).toHaveText('evidence.audit.read');

  await page.locator('#state-select').selectOption('conflict');
  await expect(
    page.getByRole('heading', { level: 2, name: 'A newer revision is available' }),
  ).toBeVisible();
  await expect(page.getByRole('button', { name: 'Reload current revision' })).toBeVisible();

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
