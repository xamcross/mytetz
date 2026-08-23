import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
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
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    fixture = TestBed.createComponent(AccountPageComponent);
    store = TestBed.inject(AccountStore);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

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
    // A return from checkout is an ordinary navigation to this route. The browser boots the app
    // fresh, and this component mounts. This class reads no query parameter — see its own doc
    // comment. A plain `GET /api/account` on mount, with its result landing in the store, is the
    // whole proof.
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

  it('the account page reports a failed account read', async () => {
    // `AccountStore.error` is set on any non-401 failure. Before this page existed, no template
    // rendered it — see the class doc comment. A learner who reads a stale meter needs to know
    // the last refresh failed.
    await mount((req) => req.flush(null, { status: 500, statusText: 'Server Error' }));

    expect(text()).toContain('Could not load your account');
  });

  it('the account page offers sign out everywhere', async () => {
    await mount((req) => req.flush(active));

    const button = fixture.nativeElement.querySelector(
      '[data-action="sign-out-everywhere"]',
    ) as HTMLButtonElement;
    button.click();

    const signOutReq = http.expectOne('/api/auth/sign-out-all');
    expect(signOutReq.request.method).toBe('POST');
    signOutReq.flush(null, { status: 204, statusText: 'No Content' });
    await fixture.whenStable();

    // A successful sign-out-everywhere also clears this browser's own cookie. `AuthRoutes.kt`
    // calls `clearSessionCookie` on this route. The next account read then answers 401, and the
    // store's own rule clears the view: a 401 means signed out.
    http.expectOne('/api/account').flush(signedOut, { status: 401, statusText: '' });
    await fixture.whenStable();

    expect(store.view()).toBeNull();
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

  it('manage subscription and delete account are present and inert', async () => {
    // Neither button has a backend route to call yet — see the class doc comment. This test
    // clicks both buttons and checks that no request goes out. A check of the markup alone is
    // not enough proof.
    await mount((req) => req.flush(active));

    const manage = fixture.nativeElement.querySelector(
      '[data-action="manage-subscription"]',
    ) as HTMLButtonElement;
    const del = fixture.nativeElement.querySelector(
      '[data-action="delete-account"]',
    ) as HTMLButtonElement;

    expect(manage.disabled).toBe(true);
    expect(del.disabled).toBe(true);

    manage.click();
    del.click();

    http.expectNone('/api/billing/checkout');
    http.expectNone('/api/account/delete');
  });
});
