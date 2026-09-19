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

const signedOut = {
  code: 'SIGN_IN_REQUIRED',
  message: 'sign in to continue',
};

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

  it('the account page shows the status and the period end', async () => {
    await mount((req) => req.flush(active));

    expect(text()).toContain('ACTIVE');
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

  it('the account page shows the status of a trial learner', async () => {
    await mount((req) => req.flush(trialing));

    expect(text()).toContain('TRIALING');
  });

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

  it.each(['TRIALING', 'EXPIRED'])('manage subscription is absent for %s', async (status) => {
    await mount((req) => req.flush({ ...active, status }));

    expect(fixture.nativeElement.querySelector('[data-action="manage-subscription"]')).toBeNull();
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
    await mount((req) => req.flush(active));

    const actions = fixture.nativeElement.querySelector('.account-page__actions');
    const manage = actions.querySelector('[data-action="manage-subscription"]');
    const terms = actions.querySelector('a[href="/terms"]') as HTMLAnchorElement;

    expect(terms).toBeTruthy();
    expect(terms.textContent).toContain('Terms');
    expect(manage.compareDocumentPosition(terms) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('delete account opens a confirmation panel instead of sending a request at once', async () => {
    await mount((req) => req.flush(active));

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

  it('cancelling the confirmation panel sends no request and closes it', async () => {
    await mount((req) => req.flush(active));

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

  it('confirming deletion posts to the delete route and ends signed out', async () => {
    await mount((req) => req.flush(active));

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
    await mount((req) => req.flush(active));

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
    expect(store.view()).toEqual(active);
  });

  it('a failed delete request reports a generic error and leaves the account in place', async () => {
    await mount((req) => req.flush(active));

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
    expect(store.view()).toEqual(active);
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
    expect(text()).toContain('ACTIVE');
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
