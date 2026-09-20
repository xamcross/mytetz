import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { AccountPageComponent } from './account-page.component';
import { AccountStore } from '../core/account.store';
import { AccountView } from '../core/models';

// 2024-08-07T10:40:00.000Z. `allowance-meter.component.spec.ts` pins its own date-formatting
// specs against the same instant, so both files render the identical "August 7, 2024".
const FIXED_EPOCH_MILLIS = 1723027200000;

const active: AccountView = {
  email: 'learner@example.com',
  status: 'ACTIVE',
  trialEndsAtEpochMillis: null,
  currentPeriodEndsAtEpochMillis: FIXED_EPOCH_MILLIS,
  allowance: 25,
  remaining: 9,
  resetsAtEpochMillis: null,
};

// The account before a purchase completes. The post-purchase poll specs below start every mount
// with this view, then answer a later `GET /api/account` with [active] once the webhook lands.
const trialing: AccountView = {
  ...active,
  status: 'TRIALING',
  currentPeriodEndsAtEpochMillis: null,
};

// Issue #177. `ACTIVE` and `PAST_DUE` block a deletion — see `deletionBlocked` — so every spec
// below that opens the panel and drives the actual confirm-and-delete flow needs a status the
// backend lets through. `CANCELLED` still shows "Manage subscription" in the top row (see the
// status-badge spec below), and it does not renew, so it proves both things stay true together.
// A spec about the top row, or about "Delete account" itself always being present, uses `active`
// instead — the top row and that one button look the same for every status.
const deletable: AccountView = { ...active, status: 'CANCELLED' };

const signedOut = {
  code: 'SIGN_IN_REQUIRED',
  message: 'sign in to continue',
};

// Issue #106's own sentences (pull request #134). Issue #138 replaces every one of them with a
// short label. A test below proves none of these six strings ever shows again.
const OLD_STATUS_SENTENCES = [
  'Your trial is active.',
  'Your subscription is active.',
  'Your payment is overdue.',
  'Your subscription is cancelled.',
  'Your subscription has expired.',
  'We do not recognize this account status.',
];

describe('AccountPageComponent', () => {
  let fixture: ComponentFixture<AccountPageComponent>;
  let store: AccountStore;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [AccountPageComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    fixture = TestBed.createComponent(AccountPageComponent);
    store = TestBed.inject(AccountStore);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    // A no-op when a test never installs fake timers. `vi.useFakeTimers()` is scoped to one test
    // below, and this line keeps the next test's real `setTimeout` from a leftover fake clock.
    vi.useRealTimers();
    http.verify();
  });

  const text = (): string => fixture.nativeElement.textContent as string;

  /**
   * Mounts the page and answers the `GET /api/account` that `ngOnInit` fires.
   *
   * Every test needs this call. The component always loads the account on init. The test named
   * `returning from checkout refreshes the account view` below proves this. A test that never
   * answers this request fails `afterEach`'s `http.verify()`, even a test that does not care
   * about the load result.
   */
  async function mount(respond: (req: TestRequest) => void): Promise<void> {
    fixture.detectChanges();
    respond(http.expectOne('/api/account'));
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('the account page shows a loading state before the first load answers', () => {
    fixture.detectChanges();

    expect(text()).toContain('Loading your account');

    // Answers the request `ngOnInit` fired, so `afterEach`'s `http.verify()` passes. This test
    // does not need the result, only the state before it lands.
    http.expectOne('/api/account').flush(active);
  });

  it('the account page draws a skeleton card before the first answer', () => {
    // Finding F14. The catalogue and the reader each draw a skeleton while their first request
    // runs. The account page drew nothing at all. This proves the same shape now appears here:
    // one raised card with four placeholder rows.
    fixture.detectChanges();

    const skeleton = fixture.nativeElement.querySelector('.account-page__skeleton');
    expect(skeleton).not.toBeNull();
    expect(skeleton.classList).toContain('mt-card--raised');
    expect(skeleton.querySelectorAll('.mt-skeleton').length).toBe(4);

    http.expectOne('/api/account').flush(active);
  });

  it('removes the skeleton once the first answer lands', async () => {
    await mount((req) => req.flush(active));

    expect(fixture.nativeElement.querySelector('.account-page__skeleton')).toBeNull();
  });

  it('returning from checkout refreshes the account view', async () => {
    // A return from checkout with no `action` query parameter is an ordinary navigation to this
    // route. The browser boots the app fresh, and this component mounts. A plain `GET
    // /api/account` on mount, with its result landing in the store, is the whole proof. The
    // describe block below covers the case where Freemius's `action` parameter is present.
    await mount((req) => {
      expect(req.request.method).toBe('GET');
      req.flush(active);
    });

    expect(store.view()).toEqual(active);
  });

  it('the account page shows the email', async () => {
    await mount((req) => req.flush(active));

    expect(text()).toContain('learner@example.com');
  });

  it('the account page shows the status label, and the period end', async () => {
    // Issue #138. The owner decided on 2026-09-20 that a status is a short label, not a
    // sentence. The row shows "PREMIUM" for an active learner, and never the wire value — see
    // the describe block below for every status.
    await mount((req) => req.flush(active));

    const badge = fixture.nativeElement.querySelector('.account-page__status-badge');
    expect(badge?.textContent?.trim()).toBe('PREMIUM');
    expect(text()).not.toContain('Your subscription is active.');
    expect(text()).not.toContain('ACTIVE');
    expect(text()).toContain('August 7, 2024');
  });

  it('the account page shows no period end when the account carries none', async () => {
    // A naive formatter given `null` could print the word "null". It could also coerce the value
    // to the year 1970. `allowance-meter.component.spec.ts` guards against the same failure for
    // the trial end and the reset. A `TRIALING` account has no period end yet, so silence is the
    // honest answer here.
    await mount((req) =>
      req.flush({ ...active, status: 'TRIALING', currentPeriodEndsAtEpochMillis: null }),
    );

    expect(text()).not.toContain('null');
    expect(text()).not.toContain('1970');
  });

  it('the account page shows the status label for a trial learner', async () => {
    await mount((req) => req.flush(trialing));

    const badge = fixture.nativeElement.querySelector('.account-page__status-badge');
    expect(badge?.textContent?.trim()).toBe('TRIAL');
    expect(text()).not.toContain('Your trial is active.');
    expect(text()).not.toContain('TRIALING');
  });

  it.each([
    ['TRIALING', 'TRIAL', null],
    ['ACTIVE', 'PREMIUM', null],
    ['PAST_DUE', 'PREMIUM', 'Payment overdue.'],
    ['CANCELLED', 'PREMIUM', 'Cancelled.'],
    ['EXPIRED', 'FREE', null],
    ['SOME_FUTURE_STATUS', 'FREE', null],
  ])(
    'issue #138: status %s shows the label %j, never a sentence or the raw value',
    async (status, label, secondLine) => {
      await mount((req) => req.flush({ ...active, status }));

      const badge = fixture.nativeElement.querySelector('.account-page__status-badge');
      expect(badge?.textContent?.trim()).toBe(label);
      expect(text()).not.toContain(status);
      for (const oldSentence of OLD_STATUS_SENTENCES) {
        expect(text()).not.toContain(oldSentence);
      }

      const detail = fixture.nativeElement.querySelector('.account-page__status-detail');
      if (secondLine === null) {
        expect(detail).toBeNull();
      } else {
        expect(detail?.textContent?.trim()).toBe(secondLine);
      }
    },
  );

  it('the account page shows no "Invalid Date" when the wire body has no period-end key', async () => {
    // The route serializer once omitted `currentPeriodEndsAtEpochMillis` from the wire body of a
    // trial learner, because the field's value equalled its declared default — see issue #89.
    // This test flushes that exact shape: the key is absent, and not `null`. It proves the page
    // treats an absent key the same safe way.
    const { currentPeriodEndsAtEpochMillis: _omittedPeriodEnd, ...bodyWithNoPeriodEndKey } =
      trialing;
    await mount((req) => req.flush(bodyWithNoPeriodEndKey));

    expect(text()).not.toContain('Invalid Date');
    expect(text()).not.toContain('Current period ends');
  });

  it('the account page reports a failed account read', async () => {
    // `AccountStore.error` is set on any non-401 failure. Before this page existed, no template
    // rendered it — see the class doc comment. A learner who reads a stale meter needs to know
    // the last refresh failed.
    await mount((req) => req.flush(null, { status: 500, statusText: 'Server Error' }));

    expect(text()).toContain('Could not load your account');
  });

  it('the account page offers no sign out everywhere control', async () => {
    // Issue #88 removes the control. The monetization spec listed it in slice B4, "Hardening". The
    // owner decided that the page is clearer without it, and accepted the cost: a learner can no
    // longer end a session on another device from this page. `AuthRoutes.kt` keeps the route for a
    // later security page or an operator, and no page in this app calls it.
    await mount((req) => req.flush(active));

    expect(fixture.nativeElement.querySelector('[data-action="sign-out-everywhere"]')).toBeNull();
  });

  it('sign out posts to the single-session route', async () => {
    await mount((req) => req.flush(active));

    const button = fixture.nativeElement.querySelector(
      '[data-action="sign-out"]',
    ) as HTMLButtonElement;
    button.click();

    const req = http.expectOne('/api/auth/sign-out');
    expect(req.request.method).toBe('POST');
    req.flush(null, { status: 204, statusText: 'No Content' });
    await fixture.whenStable();

    http.expectOne('/api/account').flush(signedOut, { status: 401, statusText: '' });
    await fixture.whenStable();

    expect(store.view()).toBeNull();
  });

  it('reports a failed sign-out without losing the account view', async () => {
    await mount((req) => req.flush(active));

    const button = fixture.nativeElement.querySelector(
      '[data-action="sign-out"]',
    ) as HTMLButtonElement;
    button.click();

    http.expectOne('/api/auth/sign-out').flush(null, { status: 500, statusText: 'Server Error' });
    await fixture.whenStable();
    fixture.detectChanges();

    expect(text()).toContain('Could not sign out');
    expect(store.view()).toEqual(active);
  });

  it.each(['ACTIVE', 'PAST_DUE', 'CANCELLED'])(
    'manage subscription is enabled for %s and opens the vendor portal',
    async (status) => {
      await mount((req) => req.flush({ ...active, status }));
      const redirect = vi.spyOn(fixture.componentInstance, 'redirect').mockImplementation(() => {});

      const manage = fixture.nativeElement.querySelector(
        '[data-action="manage-subscription"]',
      ) as HTMLButtonElement;
      expect(manage.disabled).toBe(false);

      manage.click();

      const req = http.expectOne('/api/billing/portal');
      expect(req.request.method).toBe('POST');
      req.flush({ url: 'https://example.freemius.com/portal?token=abc' });
      await fixture.whenStable();

      expect(redirect).toHaveBeenCalledWith('https://example.freemius.com/portal?token=abc');
    },
  );

  it('marks "Manage subscription" busy, with a label that names the work, while its request runs', async () => {
    // Finding F7, animation J. A busy control once faded to 55% opacity and said nothing about
    // what it was doing. It now carries aria-busy and a label naming the work while the request
    // is in flight, and stays disabled — so a second click cannot send a second request.
    await mount((req) => req.flush(active));

    const manage = fixture.nativeElement.querySelector(
      '[data-action="manage-subscription"]',
    ) as HTMLButtonElement;
    manage.click();
    fixture.detectChanges();

    expect(manage.getAttribute('aria-busy')).toBe('true');
    expect(manage.textContent).toContain('Opening the portal…');
    expect(manage.disabled).toBe(true);

    http.expectOne('/api/billing/portal').flush({ url: 'https://example.freemius.com/portal' });
    await fixture.whenStable();
  });

  it.each(['TRIALING', 'EXPIRED'])('manage subscription is absent for %s', async (status) => {
    await mount((req) => req.flush({ ...active, status }));

    expect(fixture.nativeElement.querySelector('[data-action="manage-subscription"]')).toBeNull();
  });

  /**
   * Issue #137. A signed-in learner with no live subscription always has a path to the plan
   * screen. `subscribeVisible` is the exact complement of `manageVisible` (see its own KDoc), so
   * this covers `TRIALING`, `NONE`, `EXPIRED` and an unknown status in one pass.
   */
  it.each(['TRIALING', 'NONE', 'EXPIRED', 'SOME_UNKNOWN_STATUS'])(
    'subscribe is present for %s, and links to /subscribe',
    async (status) => {
      await mount((req) => req.flush({ ...active, status }));

      const subscribe = fixture.nativeElement.querySelector(
        '[data-action="subscribe"]',
      ) as HTMLAnchorElement;
      expect(subscribe).toBeTruthy();
      expect(subscribe.getAttribute('href')).toBe('/subscribe');
      // Never both at once: a subscriber must not read a control that offers a second checkout.
      expect(fixture.nativeElement.querySelector('[data-action="manage-subscription"]')).toBeNull();
    },
  );

  it.each(['ACTIVE', 'PAST_DUE', 'CANCELLED'])('subscribe is absent for %s', async (status) => {
    await mount((req) => req.flush({ ...active, status }));

    expect(fixture.nativeElement.querySelector('[data-action="subscribe"]')).toBeNull();
  });

  it('a failed portal request shows a message and keeps the learner on the page', async () => {
    await mount((req) => req.flush(active));

    const manage = fixture.nativeElement.querySelector(
      '[data-action="manage-subscription"]',
    ) as HTMLButtonElement;
    manage.click();

    http.expectOne('/api/billing/portal').flush(null, { status: 500, statusText: 'Server Error' });
    await fixture.whenStable();
    fixture.detectChanges();

    expect(text()).toContain('Could not open the customer portal');
    expect(store.view()).toEqual(active);
  });

  it('a rate-limited portal request shows its own message', async () => {
    await mount((req) => req.flush(active));

    const manage = fixture.nativeElement.querySelector(
      '[data-action="manage-subscription"]',
    ) as HTMLButtonElement;
    manage.click();

    http
      .expectOne('/api/billing/portal')
      .flush(
        { code: 'RATE_LIMITED', message: 'too many portal requests; try again shortly' },
        { status: 429, statusText: 'Too Many Requests' },
      );
    await fixture.whenStable();
    fixture.detectChanges();

    expect(text()).toContain('Too many requests. Wait a few minutes and try again.');
    expect(text()).not.toContain('Could not open the customer portal');
    expect(store.view()).toEqual(active);
  });

  it('links to the terms next to the subscribe control', async () => {
    // ACTIVE: issue #177 changes nothing about the top row. "Manage subscription" stays there for
    // every status that already showed it.
    await mount((req) => req.flush(active));

    const actions = fixture.nativeElement.querySelector('.account-page__actions');
    const manage = actions.querySelector('[data-action="manage-subscription"]');
    const terms = actions.querySelector('a[href="/terms"]') as HTMLAnchorElement;

    expect(terms).toBeTruthy();
    expect(terms.textContent).toContain('Terms');
    expect(manage.compareDocumentPosition(terms) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('draws Terms as a plain link, and not a pill', async () => {
    // Finding F15. A destructive control once sat beside a navigation link at the same visual
    // weight. Terms drops the pill classes, so it reads as a plain link.
    await mount((req) => req.flush(active));

    const terms = fixture.nativeElement.querySelector('a[href="/terms"]') as HTMLAnchorElement;
    expect(terms.classList).not.toContain('mt-pill');
    expect(terms.classList).not.toContain('mt-pill--ghost');
  });

  it('puts delete account in its own block, below a divider, with no heading', async () => {
    // Finding F15 put "Delete account" in its own block, below a divider, under the heading
    // "Close your account". Issue #140 removes the heading: the button already names the action,
    // so a heading above it repeats the same words. The DOM order still proves the block comes
    // after the primary row and not before it. ACTIVE: this button shows for every status — issue
    // #177 changes only what opens once a learner clicks it.
    await mount((req) => req.flush(active));

    const actions = fixture.nativeElement.querySelector('.account-page__actions');
    const signOut = fixture.nativeElement.querySelector('[data-action="sign-out"]');
    const divider = fixture.nativeElement.querySelector('.account-page__divider');
    const del = fixture.nativeElement.querySelector('[data-action="delete-account"]');

    expect(actions.querySelector('[data-action="delete-account"]')).toBeNull();
    expect(text()).not.toContain('Close your account');
    expect(fixture.nativeElement.querySelector('.account-page__danger-heading')).toBeNull();
    expect(
      signOut.compareDocumentPosition(divider) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(divider.compareDocumentPosition(del) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it.each(['ACTIVE', 'PAST_DUE'])('"Delete account" shows for %s, as on main', async (status) => {
    await mount((req) => req.flush({ ...active, status }));

    expect(fixture.nativeElement.querySelector('[data-action="delete-account"]')).not.toBeNull();
    // The blocked sentence appears only once the learner asks to delete — not before.
    expect(text()).not.toContain('Cancel your subscription first.');
  });

  // Issue #177. `POST /api/account/delete` answers `409 SUBSCRIPTION_ACTIVE` for ACTIVE and
  // PAST_DUE. Opening the delete panel for either status shows that rule in place of the confirm
  // dialog, and never a heading, and never the "This permanently deletes…" text.
  it.each(['ACTIVE', 'PAST_DUE'])(
    'opening the delete panel for %s shows "Cancel your subscription first.", its own Manage subscription control, and Cancel — never the confirm button',
    async (status) => {
      await mount((req) => req.flush({ ...active, status }));

      (
        fixture.nativeElement.querySelector('[data-action="delete-account"]') as HTMLButtonElement
      ).click();
      fixture.detectChanges();

      expect(text()).toContain('Cancel your subscription first.');
      expect(text()).not.toContain('This permanently deletes');
      expect(
        fixture.nativeElement.querySelector('[data-action="delete-account-confirm"]'),
      ).toBeNull();
      const panelManage = fixture.nativeElement.querySelector(
        '[data-action="manage-subscription-from-delete"]',
      ) as HTMLButtonElement;
      expect(panelManage).not.toBeNull();
      expect(panelManage.textContent).toContain('Manage subscription');
      expect(
        fixture.nativeElement.querySelector('[data-action="delete-account-cancel"]'),
      ).not.toBeNull();
    },
  );

  it.each(['TRIALING', 'CANCELLED', 'EXPIRED', 'NONE'])(
    'opening the delete panel for %s shows the confirm button, and never the subscription sentence',
    async (status) => {
      await mount((req) => req.flush({ ...active, status }));

      (
        fixture.nativeElement.querySelector('[data-action="delete-account"]') as HTMLButtonElement
      ).click();
      fixture.detectChanges();

      expect(text()).not.toContain('Cancel your subscription first.');
      expect(text()).toContain('This permanently deletes');
      expect(
        fixture.nativeElement.querySelector('[data-action="delete-account-confirm"]'),
      ).not.toBeNull();
      expect(
        fixture.nativeElement.querySelector('[data-action="manage-subscription-from-delete"]'),
      ).toBeNull();
    },
  );

  it('cancelling the blocked panel closes it, with no request sent', async () => {
    await mount((req) => req.flush({ ...active, status: 'ACTIVE' }));

    (
      fixture.nativeElement.querySelector('[data-action="delete-account"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();
    (
      fixture.nativeElement.querySelector(
        '[data-action="delete-account-cancel"]',
      ) as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    http.expectNone('/api/account/delete');
    http.expectNone('/api/billing/portal');
    expect(text()).not.toContain('Cancel your subscription first.');
    expect(fixture.nativeElement.querySelector('[data-action="delete-account"]')).not.toBeNull();
  });

  it('a click on Manage subscription inside the blocked panel opens the vendor portal', async () => {
    await mount((req) => req.flush({ ...active, status: 'ACTIVE' }));
    const redirect = vi.spyOn(fixture.componentInstance, 'redirect').mockImplementation(() => {});

    (
      fixture.nativeElement.querySelector('[data-action="delete-account"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();
    (
      fixture.nativeElement.querySelector(
        '[data-action="manage-subscription-from-delete"]',
      ) as HTMLButtonElement
    ).click();

    http
      .expectOne('/api/billing/portal')
      .flush({ url: 'https://example.freemius.com/portal?token=abc' });
    await fixture.whenStable();

    expect(redirect).toHaveBeenCalledWith('https://example.freemius.com/portal?token=abc');
  });

  it('both Manage subscription controls share one busy state', async () => {
    // Issue #177. The top row and the open panel each carry their own `data-action`, so a test —
    // or a screen reader — can tell them apart, but both call the same method and read the same
    // `openingPortal` signal, so a click on either one disables both while the request is in
    // flight.
    await mount((req) => req.flush({ ...active, status: 'ACTIVE' }));

    (
      fixture.nativeElement.querySelector('[data-action="delete-account"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();
    const topManage = fixture.nativeElement.querySelector(
      '[data-action="manage-subscription"]',
    ) as HTMLButtonElement;
    const panelManage = fixture.nativeElement.querySelector(
      '[data-action="manage-subscription-from-delete"]',
    ) as HTMLButtonElement;
    expect(topManage).not.toBeNull();
    expect(panelManage).not.toBeNull();

    panelManage.click();
    fixture.detectChanges();

    for (const button of [topManage, panelManage]) {
      expect(button.getAttribute('aria-busy')).toBe('true');
      expect(button.disabled).toBe(true);
    }

    http.expectOne('/api/billing/portal').flush({ url: 'https://example.freemius.com/portal' });
    await fixture.whenStable();
  });

  it('delete account opens a confirmation panel instead of sending a request at once', async () => {
    await mount((req) => req.flush(deletable));

    const del = fixture.nativeElement.querySelector(
      '[data-action="delete-account"]',
    ) as HTMLButtonElement;
    del.click();
    fixture.detectChanges();

    http.expectNone('/api/account/delete');
    expect(
      fixture.nativeElement.querySelector('[data-action="delete-account-confirm"]'),
    ).not.toBeNull();
    expect(text()).toContain('This cannot be undone');
  });

  // Issue #145. The page at `/` is the "dashboard" in every text a learner reads, and never the
  // "catalogue".
  it('the confirmation panel names the dashboard, not the catalogue', async () => {
    await mount((req) => req.flush(deletable));

    (
      fixture.nativeElement.querySelector('[data-action="delete-account"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    expect(text()).toContain('those stay on the site for other learners');
    expect(text()).not.toContain('catalogue');
  });

  it('cancelling the confirmation panel sends no request and closes it', async () => {
    await mount((req) => req.flush(deletable));

    (
      fixture.nativeElement.querySelector('[data-action="delete-account"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();
    (
      fixture.nativeElement.querySelector(
        '[data-action="delete-account-cancel"]',
      ) as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    http.expectNone('/api/account/delete');
    expect(
      fixture.nativeElement.querySelector('[data-action="delete-account-confirm"]'),
    ).toBeNull();
    expect(fixture.nativeElement.querySelector('[data-action="delete-account"]')).not.toBeNull();
  });

  it('marks the delete confirm button busy, with a label that names the work, while its request runs', async () => {
    // Finding F7, animation J. Same rule as "Manage subscription" above.
    await mount((req) => req.flush(deletable));

    (
      fixture.nativeElement.querySelector('[data-action="delete-account"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();
    const confirm = fixture.nativeElement.querySelector(
      '[data-action="delete-account-confirm"]',
    ) as HTMLButtonElement;
    confirm.click();
    fixture.detectChanges();

    expect(confirm.getAttribute('aria-busy')).toBe('true');
    expect(confirm.textContent).toContain('Deleting…');
    expect(confirm.disabled).toBe(true);

    http.expectOne('/api/account/delete').flush(null, { status: 204, statusText: 'No Content' });
    await fixture.whenStable();
    http.expectOne('/api/account').flush(signedOut, { status: 401, statusText: '' });
    await fixture.whenStable();
  });

  it('confirming deletion posts to the delete route and ends signed out', async () => {
    await mount((req) => req.flush(deletable));

    (
      fixture.nativeElement.querySelector('[data-action="delete-account"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();
    (
      fixture.nativeElement.querySelector(
        '[data-action="delete-account-confirm"]',
      ) as HTMLButtonElement
    ).click();

    const deleteReq = http.expectOne('/api/account/delete');
    expect(deleteReq.request.method).toBe('POST');
    deleteReq.flush(null, { status: 204, statusText: 'No Content' });
    await fixture.whenStable();

    // The same rule `signOut` follows: the server clears the cookie, the next read answers 401,
    // and the store clears the view on that 401 — no separate "deleted" state to keep in step.
    http.expectOne('/api/account').flush(signedOut, { status: 401, statusText: '' });
    await fixture.whenStable();

    expect(store.view()).toBeNull();
  });

  it('a stale-session refusal tells the learner to sign in again', async () => {
    await mount((req) => req.flush(deletable));

    (
      fixture.nativeElement.querySelector('[data-action="delete-account"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();
    (
      fixture.nativeElement.querySelector(
        '[data-action="delete-account-confirm"]',
      ) as HTMLButtonElement
    ).click();

    http
      .expectOne('/api/account/delete')
      .flush(
        { code: 'CONFIRMATION_REQUIRED', message: 'sign in again' },
        { status: 403, statusText: 'Forbidden' },
      );
    await fixture.whenStable();
    fixture.detectChanges();

    expect(text()).toContain('Sign in again');
    // The account view must survive a refused deletion — nothing was deleted.
    expect(store.view()).toEqual(deletable);
  });

  it('a failed delete request reports a generic error and leaves the account in place', async () => {
    await mount((req) => req.flush(deletable));

    (
      fixture.nativeElement.querySelector('[data-action="delete-account"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();
    (
      fixture.nativeElement.querySelector(
        '[data-action="delete-account-confirm"]',
      ) as HTMLButtonElement
    ).click();

    http.expectOne('/api/account/delete').flush(null, { status: 500, statusText: 'Server Error' });
    await fixture.whenStable();
    fixture.detectChanges();

    expect(text()).toContain('Could not delete your account');
    expect(store.view()).toEqual(deletable);
  });

  // Issue #177. `POST /api/account/delete` answers `409 SUBSCRIPTION_ACTIVE` when a webhook moves
  // the status to ACTIVE or PAST_DUE after this page loaded, but before the learner confirms. The
  // panel must stay open, and re-render as the blocked form — not close back to "Delete account".
  it('a 409 refusal keeps the panel open, and it re-renders as the blocked form', async () => {
    await mount((req) => req.flush(deletable));

    (
      fixture.nativeElement.querySelector('[data-action="delete-account"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();
    (
      fixture.nativeElement.querySelector(
        '[data-action="delete-account-confirm"]',
      ) as HTMLButtonElement
    ).click();

    http.expectOne('/api/account/delete').flush(
      {
        code: 'SUBSCRIPTION_ACTIVE',
        message: 'cancel your subscription before you delete your account',
      },
      { status: 409, statusText: 'Conflict' },
    );
    await fixture.whenStable();
    http.expectOne('/api/account').flush({ ...active, status: 'ACTIVE' });
    await fixture.whenStable();
    fixture.detectChanges();

    // The panel is still open: no "Delete account" button to reopen it, and the alertdialog card
    // is still on the page — just showing the blocked form now, and not the confirm dialog.
    expect(fixture.nativeElement.querySelector('[data-action="delete-account"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('[role="alertdialog"]')).not.toBeNull();
    expect(text()).toContain('Cancel your subscription first.');
    expect(
      fixture.nativeElement.querySelector('[data-action="manage-subscription-from-delete"]'),
    ).not.toBeNull();
    expect(
      fixture.nativeElement.querySelector('[data-action="delete-account-confirm"]'),
    ).toBeNull();
    expect(
      fixture.nativeElement.querySelector('[data-action="delete-account-cancel"]'),
    ).not.toBeNull();
  });

  // Issue #177. CANCELLED does not renew, so it never blocks a deletion — but it can still carry
  // paid days a learner has not used yet. The confirm dialog adds one sentence for that case.
  it('the confirm dialog warns about paid access ending, for CANCELLED with a future period end', async () => {
    const future = Date.now() + 1_000_000;
    await mount((req) =>
      req.flush({ ...active, status: 'CANCELLED', currentPeriodEndsAtEpochMillis: future }),
    );

    (
      fixture.nativeElement.querySelector('[data-action="delete-account"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    expect(text()).toContain('This cannot be undone.');
    expect(text()).toContain('Your paid access also ends now.');
  });

  it('the confirm dialog adds no warning for CANCELLED with a period end already past', async () => {
    const past = Date.now() - 1_000_000;
    await mount((req) =>
      req.flush({ ...active, status: 'CANCELLED', currentPeriodEndsAtEpochMillis: past }),
    );

    (
      fixture.nativeElement.querySelector('[data-action="delete-account"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    expect(text()).not.toContain('Your paid access also ends now.');
  });

  it('the confirm dialog adds no paid-access warning for a status other than CANCELLED', async () => {
    const future = Date.now() + 1_000_000;
    await mount((req) =>
      req.flush({ ...active, status: 'EXPIRED', currentPeriodEndsAtEpochMillis: future }),
    );

    (
      fixture.nativeElement.querySelector('[data-action="delete-account"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    expect(text()).not.toContain('Your paid access also ends now.');
  });

  it('a visit with no action parameter makes one request and starts no poll', async () => {
    vi.useFakeTimers();
    await mount((req) => req.flush(active));

    // 30 seconds is the poll's own outer limit — see the describe block below. No poll started,
    // so this elapsed time must cause no second request.
    await vi.advanceTimersByTimeAsync(30000);

    http.expectNone('/api/account');
  });

  it('does not touch the address bar when there is no query string to remove', async () => {
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate');

    await mount((req) => req.flush(active));

    expect(navigate).not.toHaveBeenCalled();
  });
});

/**
 * `action=purchase` on the return URL is the hint that Freemius's webhook may still be in
 * flight — see the class doc comment on `AccountPageComponent`. Each spec below stands in for
 * that race: the first `GET /api/account` answers the pre-purchase view, and a later one answers
 * [active].
 */
describe('AccountPageComponent — the post-purchase poll', () => {
  let fixture: ComponentFixture<AccountPageComponent>;
  let store: AccountStore;
  let http: HttpTestingController;
  let navigate: ReturnType<typeof vi.spyOn>;

  // Freemius's real return URL carries several other values beside `action` — `amount` and
  // `email` stand in for them here. Nothing in the page reads any of them; see the test named for
  // that below.
  const queryParams = { action: 'purchase', amount: '19.99', email: 'other-inbox@example.com' };

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [AccountPageComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: { queryParams, queryParamMap: convertToParamMap(queryParams) },
          },
        },
      ],
    });
    fixture = TestBed.createComponent(AccountPageComponent);
    store = TestBed.inject(AccountStore);
    http = TestBed.inject(HttpTestingController);
    // `provideRouter([])` holds no route at all, so a real `Router.navigate` call has nothing to
    // match. Every test here mocks it, the same way `catalog-page.component.spec.ts` mocks
    // `Router.navigate` for a page that calls it — see the test named for the call itself below.
    navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
    http.verify();
  });

  const text = (): string => fixture.nativeElement.textContent as string;

  /**
   * Settles every microtask still queued, without `whenStable`.
   *
   * `whenStable` is a trap here. It waits on Angular's pending-task count, which clears the
   * moment a flushed `HttpTestingController` request settles — one tick before `ngOnInit`'s own
   * continuation (the `Router.navigate` call, then the poll decision) actually runs. A fixed
   * small number of bare microtask turns drains that remaining continuation instead.
   */
  async function settle(): Promise<void> {
    for (let i = 0; i < 4; i++) await Promise.resolve();
  }

  /** Mounts the page and answers the `GET /api/account` that `ngOnInit` fires. Mirrors the
   * `mount` helper above — see its own comment. */
  async function mount(respond: (req: TestRequest) => void): Promise<void> {
    fixture.detectChanges();
    respond(http.expectOne('/api/account'));
    await fixture.whenStable();
    await settle();
    fixture.detectChanges();
  }

  it('shows the new allowance once the webhook lands, with no action from the learner', async () => {
    await mount((req) => req.flush(trialing));

    expect(store.view()).toEqual(trialing);
    expect(text()).toContain('We are waiting for the payment confirmation');

    await vi.advanceTimersByTimeAsync(2000);
    http.expectOne('/api/account').flush(active);
    await settle();
    fixture.detectChanges();

    expect(store.view()).toEqual(active);
    const badge = fixture.nativeElement.querySelector('.account-page__status-badge');
    expect(badge?.textContent?.trim()).toBe('PREMIUM');
  });

  it('stops the poll once the status changes, and asks the server no more', async () => {
    await mount((req) => req.flush(trialing));

    await vi.advanceTimersByTimeAsync(2000);
    http.expectOne('/api/account').flush(active);
    await settle();
    fixture.detectChanges();

    expect(text()).not.toContain('We are waiting for the payment confirmation');

    // The poll already stopped when the status changed. Advancing well past both another
    // interval and the whole 30-second limit must send no further request.
    await vi.advanceTimersByTimeAsync(30000);

    http.expectNone('/api/account');
  });

  it('stops after 30 seconds with no change, and tells the learner to check back', async () => {
    await mount((req) => req.flush(trialing));

    // 14 polls of 2 seconds cover 28 seconds. Each answers the same unchanged status, so none
    // stops the poll early.
    for (let i = 0; i < 14; i++) {
      await vi.advanceTimersByTimeAsync(2000);
      http.expectOne('/api/account').flush(trialing);
      await settle();
    }
    fixture.detectChanges();
    expect(text()).toContain('We are waiting for the payment confirmation');

    // The 30-second limit lands on this next tick. It stops the poll before a 15th request goes
    // out.
    await vi.advanceTimersByTimeAsync(2000);
    fixture.detectChanges();

    http.expectNone('/api/account');
    expect(text()).toContain('The confirmation is not here yet');
    // The learner takes no action to reach this message, so a screen reader must announce it on
    // its own — the same reason the waiting message above carries the same role.
    const timedOut = fixture.nativeElement.querySelector('.account-page__poll-status');
    expect(timedOut?.getAttribute('role')).toBe('status');
  });

  it('stops the poll when the page is destroyed', async () => {
    await mount((req) => req.flush(trialing));

    fixture.destroy();

    await vi.advanceTimersByTimeAsync(30000);

    http.expectNone('/api/account');
  });

  it('removes the query string from the address bar after the first load', async () => {
    await mount((req) => req.flush(trialing));

    expect(navigate).toHaveBeenCalledWith([], { queryParams: {}, replaceUrl: true });
  });

  it('ignores every other Freemius parameter, and shows only what the server answers', async () => {
    await mount((req) => req.flush(trialing));

    expect(text()).not.toContain('19.99');
    expect(text()).not.toContain('other-inbox@example.com');
  });
});

/**
 * The describe block above answers `ActivatedRoute` with a fixed value, and mocks
 * `Router.navigate` — a spy that never runs a real navigation. This describe block uses the real
 * router instead, through `RouterTestingHarness`, because only a real navigation can show a real
 * bug: `Router.navigate([], { queryParams: {}, replaceUrl: true })` completes and reuses this
 * component, and the router replaces `ActivatedRoute.snapshot` with the snapshot of the cleared
 * URL. A read of `route.snapshot.queryParamMap` made *after* that navigation therefore always
 * finds no `action` parameter, on the very return trip the parameter was meant for.
 */
describe('AccountPageComponent — with the real router', () => {
  let harness: RouterTestingHarness;
  let http: HttpTestingController;
  let router: Router;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([{ path: 'account', component: AccountPageComponent }]),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    vi.useFakeTimers();
    harness = await RouterTestingHarness.create();
  });

  afterEach(() => {
    vi.useRealTimers();
    http.verify();
  });

  /** Settles the microtasks `whenStable` leaves behind. See the comment on the other `settle`,
   * above the `AccountPageComponent — the post-purchase poll` describe block, for the trap
   * itself. A real navigation runs through more microtask turns than a mocked one, so this
   * `settle` loops longer than that one does. */
  async function settle(): Promise<void> {
    for (let i = 0; i < 50; i++) await Promise.resolve();
  }

  it('polls again after the router clears the return URL, and leaves no query string behind', async () => {
    await harness.navigateByUrl(
      '/account?action=purchase&email=learner%40example.com',
      AccountPageComponent,
    );
    http.expectOne('/api/account').flush(trialing);
    await harness.fixture.whenStable();
    await settle();
    harness.detectChanges();

    // The address bar is already clear, proving the real `Router.navigate` call completed before
    // this line — the same navigation that swaps out `ActivatedRoute.snapshot`.
    expect(router.url).toBe('/account');

    await vi.advanceTimersByTimeAsync(2000);

    // The poll must survive that swap. On the present code it does not: `maybeStartPoll` reads
    // `route.snapshot` again, after the swap, finds no `action` parameter, and never starts the
    // poll — so this second request never appears.
    http.expectOne('/api/account').flush(active);
  });
});

/**
 * Its own describe block, with the real clock: the block above runs under `vi.useFakeTimers()`
 * for its poll-timing tests, and a real navigation triggered by a DOM click — rather than by
 * `RouterTestingHarness.navigateByUrl` directly — needs the real clock's own microtask queue to
 * settle. Issue #137's Subscribe control is a plain `routerLink`, so this is the same proof
 * `AllowanceMeterComponent`'s own "with the real router" block gives its Subscribe link.
 */
describe('AccountPageComponent — Subscribe, with the real router', () => {
  let harness: RouterTestingHarness;
  let http: HttpTestingController;
  let router: Router;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([
          { path: 'account', component: AccountPageComponent },
          { path: 'subscribe', component: AccountPageComponent },
        ]),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    harness = await RouterTestingHarness.create();
  });

  afterEach(() => http.verify());

  it('a click on Subscribe opens /subscribe, for a learner in trial', async () => {
    await harness.navigateByUrl('/account', AccountPageComponent);
    http.expectOne('/api/account').flush(trialing);
    await harness.fixture.whenStable();
    harness.detectChanges();

    const subscribe = harness.routeNativeElement?.querySelector(
      '[data-action="subscribe"]',
    ) as HTMLAnchorElement;
    subscribe.click();
    await harness.fixture.whenStable();

    expect(router.url).toBe('/subscribe');
    // The route table reuses AccountPageComponent for both paths, so landing on "/subscribe"
    // fires its own GET /api/account in turn — this flush is that request, and not evidence that
    // the click itself sent one. http.verify() in afterEach proves the click sent no other
    // request of its own.
    http.expectOne('/api/account').flush(active);
    await harness.fixture.whenStable();
  });
});
