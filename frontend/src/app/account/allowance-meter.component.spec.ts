import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { AllowanceMeterComponent } from './allowance-meter.component';
import { AccountStore } from '../core/account.store';
import { AccountView } from '../core/models';

// 2024-08-07T10:40:00.000Z. Chosen so the formatted date ("August 7, 2024") and the formatted
// time ("10:40 AM") are both fixed values, and neither depends on the machine's own time zone —
// the component formats in UTC for exactly this reason.
const FIXED_EPOCH_MILLIS = 1723027200000;

const trialing: AccountView = {
  email: 'learner@example.com',
  status: 'TRIALING',
  trialEndsAtEpochMillis: FIXED_EPOCH_MILLIS,
  currentPeriodEndsAtEpochMillis: null,
  allowance: 40,
  remaining: 17,
  resetsAtEpochMillis: null,
};

const active: AccountView = {
  email: 'learner@example.com',
  status: 'ACTIVE',
  trialEndsAtEpochMillis: null,
  currentPeriodEndsAtEpochMillis: null,
  allowance: 25,
  remaining: 9,
  resetsAtEpochMillis: FIXED_EPOCH_MILLIS,
};

// `allowance: 12, remaining: 3` on purpose, and not zero: a `NONE`/`EXPIRED` row must show no
// digits at all, even though the account genuinely carries non-zero numbers underneath. Zero
// values would let a regression that renders them slip past unnoticed.
const none: AccountView = {
  email: 'learner@example.com',
  status: 'NONE',
  trialEndsAtEpochMillis: null,
  currentPeriodEndsAtEpochMillis: null,
  allowance: 12,
  remaining: 3,
  resetsAtEpochMillis: null,
};

const expired: AccountView = { ...none, status: 'EXPIRED' };

describe('AllowanceMeterComponent', () => {
  let fixture: ComponentFixture<AllowanceMeterComponent>;
  let store: AccountStore;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [AllowanceMeterComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    fixture = TestBed.createComponent(AllowanceMeterComponent);
    store = TestBed.inject(AccountStore);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  const text = (): string => fixture.nativeElement.textContent as string;

  it('the meter shows remaining of allowance, and names the unit', () => {
    store.view.set(active);
    fixture.detectChanges();

    // Asserted as one relation, not as two separate `toContain` calls: two separate checks pass
    // just as well against `{{ allowance }} of {{ remaining }}` swapped, because both numbers are
    // still somewhere in the text. Only the joined string proves the order.
    //
    // Issue #139. "9 of 25 left" names no unit — the owner's own words were "9 of 25 what,
    // bananas?" — so the count now reads "9 of 25 tokens left".
    expect(text()).toContain('9 of 25 tokens left');
  });

  /**
   * Issue #139. `remaining` of 1 keeps the plural "tokens" after the total: the word follows
   * `allowance`, the total pool, and not `remaining`, the count that is genuinely singular here.
   * "1 of 40 token left" would misname the pool of 40 as one token.
   */
  it('keeps "tokens" after the total even when remaining is 1', () => {
    store.view.set({ ...active, remaining: 1 });
    fixture.detectChanges();

    expect(text()).toContain('1 of 25 tokens left');
  });

  it('the meter names the trial end in a trial', () => {
    store.view.set(trialing);
    fixture.detectChanges();

    expect(text()).toContain('August 7, 2024');
  });

  /**
   * Issue #133. Below 768px, a CSS rule hides the period word ("today", "in your trial") inside
   * the header only, so the row reads "12 of 40 left" and fits. jsdom applies no CSS, so this
   * spec cannot see that rule fire — it instead proves the one fact a screen reader depends on:
   * the sentence a screen reader announces stays whole in the markup, in a `.mt-sr-only` element,
   * with no width and no status to hide it behind.
   */
  it('carries the full count sentence for a screen reader, with no status word missing', () => {
    store.view.set(trialing);
    fixture.detectChanges();

    const srOnly = fixture.nativeElement.querySelector(
      '.allowance-meter__count .mt-sr-only',
    ) as HTMLElement;
    expect(srOnly.textContent).toContain('17 of 40 tokens left in your trial');
  });

  it('the meter renders nothing when signed out', () => {
    store.view.set(null);
    fixture.detectChanges();

    expect(text().trim()).toBe('');
  });

  it('the meter says nothing about a reset when there is no window', () => {
    store.view.set({ ...active, resetsAtEpochMillis: null });
    fixture.detectChanges();

    // A naive formatter fed `null` would print one of these three, depending on how it got
    // there — `Date.toLocaleTimeString` on `null` coerces to the epoch and prints a 1970 time, a
    // template interpolation of the raw value prints the word "null", and an arithmetic slip
    // prints "NaN". None of the three may reach the learner; the honest answer is silence.
    expect(text()).not.toContain('null');
    expect(text()).not.toContain('NaN');
    expect(text()).not.toContain('1970');
  });

  it('the meter fills once the account loads', async () => {
    // Drives the real `load()`, and not a hand-set signal. A spec that sets `view` directly is
    // exactly what let the original defect through: every unit passed while the running app never
    // called `load()` at all, because none of them exercised the path that does.
    const loaded = store.load();
    http.expectOne('/api/account').flush(active);
    await loaded;
    fixture.detectChanges();

    expect(text()).toContain('9 of 25');
  });

  /**
   * Animation I. The count ticks — a brief lift and a small scale-up — when `remaining` changes
   * from one real value to another. Cleared by its own `animationend`, and not by a timer: a
   * timer in a zoneless component needs its own destroy guard, and `animationend` needs none —
   * the same rule animation A's `landed` class follows in `focus-card.component.ts`.
   */
  describe('animation I, the count tick', () => {
    function countEl(): HTMLElement {
      return fixture.nativeElement.querySelector('.allowance-meter__count') as HTMLElement;
    }

    it('does not tick on the first render', () => {
      store.view.set(active);
      fixture.detectChanges();

      expect(countEl().classList).not.toContain('allowance-meter__count--tick');
    });

    it('ticks once remaining changes from one real value to another', () => {
      store.view.set(active);
      fixture.detectChanges();

      store.view.set({ ...active, remaining: active.remaining - 1 });
      fixture.detectChanges();

      expect(countEl().classList).toContain('allowance-meter__count--tick');
    });

    it('does not tick when the view changes but remaining stays the same', () => {
      store.view.set(active);
      fixture.detectChanges();

      store.view.set({ ...active });
      fixture.detectChanges();

      expect(countEl().classList).not.toContain('allowance-meter__count--tick');
    });

    it('clears the tick once its own animation ends, and ignores an unrelated one', () => {
      store.view.set(active);
      fixture.detectChanges();
      store.view.set({ ...active, remaining: active.remaining - 1 });
      fixture.detectChanges();
      expect(countEl().classList).toContain('allowance-meter__count--tick');

      countEl().dispatchEvent(
        Object.assign(new Event('animationend'), { animationName: 'some-other-animation' }),
      );
      fixture.detectChanges();
      expect(countEl().classList).toContain('allowance-meter__count--tick');

      countEl().dispatchEvent(
        Object.assign(new Event('animationend'), { animationName: 'meter-tick' }),
      );
      fixture.detectChanges();
      expect(countEl().classList).not.toContain('allowance-meter__count--tick');
    });
  });

  it('the meter says nothing about a trial end when there is none', () => {
    // The counterpart to the reset-text guard above, for the trial branch: a `TRIALING` row whose
    // `trialEndsAtEpochMillis` is `null` must print no sentinel value either.
    store.view.set({ ...trialing, trialEndsAtEpochMillis: null });
    fixture.detectChanges();

    expect(text()).not.toContain('null');
    expect(text()).not.toContain('NaN');
    expect(text()).not.toContain('1970');
  });

  it('the meter shows no "Invalid Date" when the trial-end key is absent from the wire', () => {
    // The route serializer once omitted `trialEndsAtEpochMillis` from the wire body of a trial
    // learner, because the field's value equalled its declared default — see issue #89. This
    // test builds that exact shape: the key is absent, and not `null`. It proves the meter
    // treats an absent key the same safe way.
    const { trialEndsAtEpochMillis: _omittedTrialEnd, ...withoutTrialEndKey } = trialing;
    store.view.set(withoutTrialEndKey as unknown as AccountView);
    fixture.detectChanges();

    expect(text()).not.toContain('Invalid Date');
  });

  it('the meter shows no "Invalid Date" when the reset key is absent from the wire', () => {
    const { resetsAtEpochMillis: _omittedReset, ...withoutResetKey } = active;
    store.view.set(withoutResetKey as unknown as AccountView);
    fixture.detectChanges();

    expect(text()).not.toContain('Invalid Date');
  });

  it('a NONE status shows no counts', () => {
    store.view.set(none);
    fixture.detectChanges();

    // No digit at all — not `allowance`, not `remaining`, neither the real 12 nor 3 above nor a
    // fallback zero. There is nothing true to count for an account with no active allowance.
    expect(text()).not.toMatch(/\d/);
    expect(text()).toContain('Subscribe');
  });

  it('an EXPIRED status shows no counts', () => {
    store.view.set(expired);
    fixture.detectChanges();

    expect(text()).not.toMatch(/\d/);
    expect(text()).toContain('Subscribe');
  });

  /**
   * Issue #137 moves the checkout call out of this component and onto the plan screen at
   * `/subscribe`. Every "Subscribe" control here is now a plain link, proven with the real
   * `Router` — through `provideRouter` for the `href` a visitor sees with no click, and through
   * `RouterTestingHarness` for the real navigation a click runs — and never through a spy on
   * `Router.navigate`, which hid a real defect in this repository before.
   *
   * These tests replace `a click on Subscribe in the meter starts the checkout`, `a second click
   * while the checkout call runs sends no second request`, `marks Subscribe busy, with a label
   * that names the work, while the request runs`, and `a failed checkout call shows a message and
   * enables the button again` — every one of which asserted a `POST /api/billing/checkout` call
   * this component no longer makes.
   */
  function subscribeLink(): HTMLAnchorElement {
    return fixture.nativeElement.querySelector('.allowance-meter__subscribe') as HTMLAnchorElement;
  }

  it('an EXPIRED status shows a Subscribe link to /subscribe, and sends no checkout request', () => {
    store.view.set(expired);
    fixture.detectChanges();

    expect(subscribeLink().getAttribute('href')).toBe('/subscribe');
    // http.verify() in afterEach proves this renders no checkout request on its own.
  });

  it('a learner in trial gets a second Subscribe link, scoped to the header, next to the count', () => {
    store.view.set(trialing);
    fixture.detectChanges();

    const trialLink = fixture.nativeElement.querySelector(
      '.allowance-meter__subscribe--trial',
    ) as HTMLAnchorElement;
    expect(trialLink.getAttribute('href')).toBe('/subscribe');
    expect(trialLink.textContent?.trim()).toBe('Subscribe');
  });

  it('an active learner gets no second Subscribe link — only a metered account in trial does', () => {
    store.view.set(active);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.allowance-meter__subscribe--trial')).toBeNull();
  });
});

/**
 * `Router.navigate` mocked with a spy already hid a real defect in this repository once (see the
 * class doc comment above `AccountPageComponent`'s own real-router describe block). This block
 * drives an actual click through `RouterTestingHarness`, on the real `Router`, so a wrong
 * `routerLink` target shows up as a real, wrong destination and not as an unexamined method call.
 */
describe('AllowanceMeterComponent — with the real router', () => {
  let harness: RouterTestingHarness;
  let http: HttpTestingController;
  let router: Router;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        // Both paths render this component itself: the meter is the thing under test, and the
        // destination its own link points to, so one stub component covers both without a second
        // one.
        provideRouter([
          { path: '', component: AllowanceMeterComponent },
          { path: 'subscribe', component: AllowanceMeterComponent },
        ]),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    TestBed.inject(AccountStore).view.set(expired);
    harness = await RouterTestingHarness.create('/');
  });

  afterEach(() => http.verify());

  it('a click on Subscribe opens /subscribe, and sends no checkout request', async () => {
    const link = harness.routeNativeElement?.querySelector(
      '.allowance-meter__subscribe',
    ) as HTMLAnchorElement;
    link.click();
    await harness.fixture.whenStable();

    expect(router.url).toBe('/subscribe');
    // http.verify() in afterEach proves the click sent no request of its own.
  });
});
