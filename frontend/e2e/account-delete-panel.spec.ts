import { test, expect } from '@playwright/test';
import { accountView, stubAccount } from './support';

/**
 * Issue #177, end to end. `POST /api/account/delete` now refuses a deletion while a subscription
 * can still renew, and the delete panel shows that rule up front for `ACTIVE` and `PAST_DUE`: the
 * sentence "Cancel your subscription first." next to "Manage subscription", in place of the
 * confirm flow.
 *
 * The narrow and wide widths mirror `layout.spec.ts`'s own `WIDTHS.narrow` and `WIDTHS.wide` —
 * 390px and 1360px — the two sizes design review measures against.
 */
const WIDTHS = {
  narrow: { width: 390, height: 844 },
  wide: { width: 1360, height: 900 },
} as const;

test('the delete panel shows the cancellation sentence and Manage subscription for an active subscriber, and no delete button', async ({
  page,
}) => {
  await stubAccount(page, accountView({ status: 'ACTIVE' }));
  await page.goto('/account');

  await expect(page.getByText('Cancel your subscription first.')).toBeVisible();
  await expect(page.locator('[data-action="manage-subscription"]')).toBeVisible();
  await expect(page.locator('[data-action="delete-account"]')).toHaveCount(0);
});

test('the delete panel shows the confirm button for a learner whose deletion would proceed', async ({
  page,
}) => {
  await stubAccount(
    page,
    accountView({ status: 'TRIALING', currentPeriodEndsAtEpochMillis: null }),
  );
  await page.goto('/account');

  await expect(page.getByText('Cancel your subscription first.')).toHaveCount(0);
  const del = page.locator('[data-action="delete-account"]');
  await expect(del).toBeVisible();

  await del.click();
  await expect(page.getByText('This cannot be undone')).toBeVisible();
});

for (const status of ['ACTIVE', 'PAST_DUE'] as const) {
  for (const [sizeName, size] of Object.entries(WIDTHS)) {
    test(`the delete panel at ${sizeName} (${size.width}px) for ${status}: no overflow, and the card keeps its padding`, async ({
      page,
    }) => {
      await page.setViewportSize(size);
      await stubAccount(page, accountView({ status }));
      await page.goto('/account');

      const sentence = page.getByText('Cancel your subscription first.');
      await expect(sentence).toBeVisible();
      const manage = page.locator('[data-action="manage-subscription"]');
      await expect(manage).toBeVisible();

      const overflow = await page.evaluate(() => ({
        scroll: document.documentElement.scrollWidth,
        client: document.documentElement.clientWidth,
      }));
      expect(
        overflow.scroll,
        `document.documentElement.scrollWidth=${overflow.scroll} must not exceed clientWidth=${overflow.client} at ${size.width}px`,
      ).toBeLessThanOrEqual(overflow.client);

      const card = page.locator('.account-page__card');
      const cardPadding = await card.evaluate((el) => getComputedStyle(el).padding);
      expect(cardPadding, 'the card keeps the same padding as before this issue').toBe('32px 36px');

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
