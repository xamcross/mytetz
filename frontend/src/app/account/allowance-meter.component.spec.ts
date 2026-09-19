import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
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
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    fixture = TestBed.createComponent(AllowanceMeterComponent);
    store = TestBed.inject(AccountStore);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  const text = (): string => fixture.nativeElement.textContent as string;

  it('the meter shows remaining of allowance', () => {
    store.view.set(active);
    fixture.detectChanges();

    // Asserted as one relation, not as two separate `toContain` calls: two separate checks pass
    // just as well against `{{ allowance }} of {{ remaining }}` swapped, because both numbers are
    // still somewhere in the text. Only the joined string proves the order.
    expect(text()).toContain('9 of 25');
  });

  it('the meter names the trial end in a trial', () => {
    store.view.set(trialing);
    fixture.detectChanges();

    expect(text()).toContain('August 7, 2024');
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

  function subscribeButton(): HTMLButtonElement {
    return fixture.nativeElement.querySelector('.allowance-meter__subscribe') as HTMLButtonElement;
  }

  it('a click on Subscribe in the meter starts the checkout', async () => {
    store.view.set(expired);
    fixture.detectChanges();
    const redirect = vi.spyOn(fixture.componentInstance, 'redirect').mockImplementation(() => {});

    subscribeButton().click();

    const req = http.expectOne('/api/billing/checkout');
    expect(req.request.method).toBe('POST');
    req.flush({
      url: 'https://checkout.freemius.com/product/1/plan/2/?user_email=a%40b.com&readonly_user=true',
    });
    await fixture.whenStable();

    // The server built the URL. This component only follows the URL. It never builds one itself.
    expect(redirect).toHaveBeenCalledWith(
      'https://checkout.freemius.com/product/1/plan/2/?user_email=a%40b.com&readonly_user=true',
    );
  });

  it('a second click while the checkout call runs sends no second request', () => {
    store.view.set(expired);
    fixture.detectChanges();
    vi.spyOn(fixture.componentInstance, 'redirect').mockImplementation(() => {});

    const button = subscribeButton();
    button.click();
    fixture.detectChanges();
    button.click();

    // One request only. The second click lands while `subscribing` is still true.
    http.expectOne('/api/billing/checkout');
  });

  it('marks Subscribe busy, with a label that names the work, while the request runs', async () => {
    // Finding F7, animation J.
    store.view.set(expired);
    fixture.detectChanges();
    vi.spyOn(fixture.componentInstance, 'redirect').mockImplementation(() => {});

    const button = subscribeButton();
    button.click();
    fixture.detectChanges();

    expect(button.getAttribute('aria-busy')).toBe('true');
    expect(button.textContent).toContain('Opening checkout…');
    expect(button.disabled).toBe(true);

    http.expectOne('/api/billing/checkout').flush({ url: 'https://example.com/checkout' });
    await fixture.whenStable();
  });

  it('a failed checkout call shows a message and enables the button again', async () => {
    store.view.set(expired);
    fixture.detectChanges();
    vi.spyOn(fixture.componentInstance, 'redirect').mockImplementation(() => {});

    subscribeButton().click();

    http
      .expectOne('/api/billing/checkout')
      .flush(null, { status: 500, statusText: 'Server Error' });
    await fixture.whenStable();
    fixture.detectChanges();

    const alert = fixture.nativeElement.querySelector('[role="alert"]') as HTMLElement;
    expect(alert.textContent).toContain('Could not start checkout');
    expect(subscribeButton().disabled).toBe(false);
  });
});
