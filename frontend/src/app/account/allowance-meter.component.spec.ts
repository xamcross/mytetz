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

  it('the meter says nothing about a trial end when there is none', () => {
    // The counterpart to the reset-text guard above, for the trial branch: a `TRIALING` row whose
    // `trialEndsAtEpochMillis` is `null` must print no sentinel value either.
    store.view.set({ ...trialing, trialEndsAtEpochMillis: null });
    fixture.detectChanges();

    expect(text()).not.toContain('null');
    expect(text()).not.toContain('NaN');
    expect(text()).not.toContain('1970');
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

  it('the subscribe button in the meter is inert', () => {
    // Task 13 wires this button to `POST /api/billing/checkout`. That route does not exist yet,
    // so a click today must call nothing.
    store.view.set(none);
    fixture.detectChanges();

    const button = fixture.nativeElement.querySelector(
      '.allowance-meter__subscribe',
    ) as HTMLButtonElement;
    button.click();

    http.expectNone('/api/billing/checkout');
  });
});
