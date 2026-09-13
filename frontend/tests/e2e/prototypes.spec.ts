import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';

const routeGroups = [
  { module: 'M1', count: 23 },
  { module: 'M2', count: 29 },
  { module: 'COS', count: 27 },
];

for (const { module, count } of routeGroups) {
  test(`${module} prototype routes render without serious accessibility violations`, async ({
    page,
  }) => {
    const ids = Array.from(
      { length: count },
      (_, index) => `${module}-${String(index + 1).padStart(2, '0')}`,
    );

    for (const id of ids) {
      await page.goto(`/#/${id}`);
      await expect(page.getByText(id).first()).toBeVisible();
      await expect(page.locator('main h1')).toBeVisible();
      const results = await new AxeBuilder({ page }).analyze();
      const seriousViolations = results.violations.filter((item) =>
        ['critical', 'serious'].includes(item.impact ?? ''),
      );
      expect(seriousViolations, `${id} has critical or serious Axe violations`).toEqual([]);
    }
  });
}

test('workspace navigation and mobile menu are usable', async ({ page, isMobile }) => {
  await page.goto('/#/M1-05');
  if (isMobile) {
    await page.getByRole('button', { name: 'Open navigation' }).click();
  }
  await page.getByRole('link', { name: 'M2', exact: true }).click();
  await expect(page).toHaveURL(/M2-01/);
  await expect(page.locator('main h1')).toHaveText('Workforce dashboard');
});
