import { test, expect } from '@playwright/test';
import { accountView, stubAccount } from './support';

/**
 * Issue #177, end to end. `POST /api/account/delete` now refuses a deletion while a subscription
 * can still renew. The top action row, and the "Delete account" button, look the same for every
 * status. Opening the delete panel is what shows the rule, for `ACTIVE` and `PAST_DUE`: the
 * sentence "Cancel your subscription first.", the panel's own "Manage subscription" control, and
 * the existing "Cancel" control — never the confirm button.
 *
 * The narrow and wide widths mirror `layout.spec.ts`'s own `WIDTHS.narrow` and `WIDTHS.wide` —
 * 390px and 1360px — the two sizes design review measures against.
 */
const WIDTHS = {
  narrow: { width: 390, height: 844 },
  wide: { width: 1360, height: 900 },
} as const;

test('the top row and "Delete account" look the same for an active subscriber as for any other learner', async ({
  page,
}) => {
  await stubAccount(page, accountView({ status: 'ACTIVE' }));
  await page.goto('/account');

  await expect(page.getByText('Cancel your subscription first.')).toHaveCount(0);
  await expect(
    page.locator('.account-page__actions [data-action="manage-subscription"]'),
  ).toBeVisible();
  await expect(page.locator('[data-action="delete-account"]')).toBeVisible();
});

test('opening the delete panel for an active subscriber shows the blocked form, not the confirm dialog', async ({
  page,
}) => {
  await stubAccount(page, accountView({ status: 'ACTIVE' }));
  await page.goto('/account');

  await page.locator('[data-action="delete-account"]').click();

  await expect(page.getByText('Cancel your subscription first.')).toBeVisible();
  await expect(page.locator('[data-action="manage-subscription-from-delete"]')).toBeVisible();
  await expect(page.locator('[data-action="delete-account-cancel"]')).toBeVisible();
  await expect(page.locator('[data-action="delete-account-confirm"]')).toHaveCount(0);
  await expect(page.getByText('This permanently deletes')).toHaveCount(0);
});

test('opening the delete panel for a learner whose deletion would proceed shows the confirm button', async ({
  page,
}) => {
  await stubAccount(
    page,
    accountView({ status: 'TRIALING', currentPeriodEndsAtEpochMillis: null }),
  );
  await page.goto('/account');

  const del = page.locator('[data-action="delete-account"]');
  await expect(del).toBeVisible();

  await del.click();
  await expect(page.getByText('This cannot be undone')).toBeVisible();
  await expect(page.getByText('Cancel your subscription first.')).toHaveCount(0);
});

for (const status of ['ACTIVE', 'PAST_DUE'] as const) {
  for (const [sizeName, size] of Object.entries(WIDTHS)) {
    test(`the open delete panel at ${sizeName} (${size.width}px) for ${status}: no overflow, the same panel padding as the confirm panel on main`, async ({
      page,
    }) => {
      await page.setViewportSize(size);
      await stubAccount(page, accountView({ status }));
      await page.goto('/account');
      await page.locator('[data-action="delete-account"]').click();

      const sentence = page.getByText('Cancel your subscription first.');
      await expect(sentence).toBeVisible();
      const manage = page.locator('[data-action="manage-subscription-from-delete"]');
      await expect(manage).toBeVisible();
      const cancel = page.locator('[data-action="delete-account-cancel"]');
      await expect(cancel).toBeVisible();

      const overflow = await page.evaluate(() => ({
        scroll: document.documentElement.scrollWidth,
        client: document.documentElement.clientWidth,
      }));
      expect(
        overflow.scroll,
        `document.documentElement.scrollWidth=${overflow.scroll} must not exceed clientWidth=${overflow.client} at ${size.width}px`,
      ).toBeLessThanOrEqual(overflow.client);

      // `.account-page__confirm` is the same class the ordinary confirm panel already uses on
      // main — this panel reuses it, and this assertion is what proves the padding did not
      // change for either form of the panel.
      const panel = page.locator('.account-page__confirm');
      const panelPadding = await panel.evaluate((el) => getComputedStyle(el).padding);
      expect(
        panelPadding,
        'the open panel keeps the same padding as the confirm panel on main',
      ).toBe('20px 24px');

      const sentenceBox = await sentence.boundingBox();
      const manageBox = await manage.boundingBox();
      if (sentenceBox && manageBox) {
        // They share a visual row when their vertical ranges overlap — `flex-wrap` stacks them
        // onto two lines at 390px instead, where this overlap test correctly finds none.
        const rowsOverlap =
          sentenceBox.y < manageBox.y + manageBox.height &&
          manageBox.y < sentenceBox.y + sentenceBox.height;
        if (rowsOverlap) {
          const sentenceCentre = sentenceBox.y + sentenceBox.height / 2;
          const manageCentre = manageBox.y + manageBox.height / 2;
          expect(
            Math.abs(sentenceCentre - manageCentre),
            `sentence centre ${sentenceCentre} and Manage subscription centre ${manageCentre} must sit on one line when they share a row`,
          ).toBeLessThan(2);
        }
      }
    });
  }
}
