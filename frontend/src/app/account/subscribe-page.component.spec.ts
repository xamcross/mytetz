import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { SubscribePageComponent } from './subscribe-page.component';
import { AccountStore } from '../core/account.store';
import { AccountView, BillingPlansView } from '../core/models';

const PLANS: BillingPlansView = {
  priceUsdPerMonth: 12,
  trialDays: 7,
  trialGenerations: 40,
  subscriberDailyExplains: 25,
};

// Numbers that differ from every default, so a test that reads them off the page proves the page
// reads the response and not a hard-coded fallback — the same rule `FaqRoutesTest`'s own
// "a page built with non-default billingConfig values" pins on the backend.
const NON_DEFAULT_PLANS: BillingPlansView = {
  priceUsdPerMonth: 19,
  trialDays: 9,
  trialGenerations: 55,
  subscriberDailyExplains: 30,
};

function accountView(overrides: Partial<AccountView> = {}): AccountView {
  return {
    email: 'learner@example.com',
    status: 'TRIALING',
    trialEndsAtEpochMillis: null,
    currentPeriodEndsAtEpochMillis: null,
    allowance: 40,
    remaining: 12,
    resetsAtEpochMillis: null,
    ...overrides,
  };
}

describe('SubscribePageComponent', () => {
  let fixture: ComponentFixture<SubscribePageComponent>;
  let store: AccountStore;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [SubscribePageComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    fixture = TestBed.createComponent(SubscribePageComponent);
    store = TestBed.inject(AccountStore);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  const text = (): string => fixture.nativeElement.textContent as string;

  /** Mounts the page and answers the `GET /api/billing/plans` request its constructor fires. */
  async function mount(plans: BillingPlansView = PLANS): Promise<void> {
    fixture.detectChanges();
    http.expectOne('/api/billing/plans').flush(plans);
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('has exactly one h1', async () => {
    await mount();

    expect(fixture.nativeElement.querySelectorAll('h1').length).toBe(1);
  });

  it('shows a skeleton while the plans are loading, and no skeleton once they land', async () => {
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.mt-skeleton')).not.toBeNull();

    http.expectOne('/api/billing/plans').flush(PLANS);
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.mt-skeleton')).toBeNull();
  });

  it('shows an error with a retry control when the plans request fails, and retry re-sends it', async () => {
    fixture.detectChanges();
    http.expectOne('/api/billing/plans').flush(null, { status: 500, statusText: 'Server Error' });
    await fixture.whenStable();
    fixture.detectChanges();

    const alert = fixture.nativeElement.querySelector('[role="alert"]') as HTMLElement;
    expect(alert.textContent).toContain('Could not load the plans');
    const retry = alert.querySelector('button') as HTMLButtonElement;

    retry.click();
    http.expectOne('/api/billing/plans').flush(PLANS);
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeNull();
  });

  it('states the price, the daily allowance and the trial numbers from the response, in tokens', async () => {
    store.view.set(accountView());
    await mount(NON_DEFAULT_PLANS);

    const body = text();
    expect(body).toContain('$19 each month');
    expect(body).toContain('30 tokens each day');
    expect(body).toContain('55 tokens over 9 days');
    // The hard-coded defaults must not leak through if the response is ever ignored.
    expect(body).not.toContain('$12 each month');
    expect(body).not.toContain('25 tokens each day');
    expect(body).not.toContain('40 tokens over 7 days');
  });

  it('states what a token is exactly once', async () => {
    store.view.set(accountView());
    await mount();

    const matches = text().match(/One token pays for one new explanation or one quiz\./g) ?? [];
    expect(matches.length).toBe(1);
  });

  it('links to the terms', async () => {
    store.view.set(accountView());
    await mount();

    const terms = fixture.nativeElement.querySelector('a[href="/terms"]') as HTMLAnchorElement;
    expect(terms).toBeTruthy();
  });

  describe('with no account', () => {
    it('shows a sign-in link in place of the Subscribe button, and still offers the free plan', async () => {
      store.view.set(null);
      await mount();

      const signIn = fixture.nativeElement.querySelector('a[href="/auth"]') as HTMLAnchorElement;
      expect(signIn).toBeTruthy();
      expect(signIn.textContent).toContain('Sign in to subscribe');
      expect(fixture.nativeElement.querySelector('button[type="button"]')).toBeNull();
      expect(text()).toContain('Stay on the free plan');
    });
  });

  describe.each(['TRIALING', 'NONE', 'EXPIRED', 'SOME_UNKNOWN_STATUS'])(
    'with the status %s',
    (status) => {
      it('shows both cards, with a real Subscribe button', async () => {
        store.view.set(accountView({ status }));
        await mount();

        const button = fixture.nativeElement.querySelector(
          '[data-action="subscribe"]',
        ) as HTMLButtonElement;
        expect(button).toBeTruthy();
        expect(button.textContent).toContain('Subscribe');
        expect(text()).toContain('Stay on the free plan');
      });
    },
  );

  describe.each(['ACTIVE', 'PAST_DUE', 'CANCELLED'])('with the status %s', (status) => {
    it('shows no Subscribe control anywhere on the page, and a link to the account page', async () => {
      store.view.set(accountView({ status }));
      await mount();

      expect(fixture.nativeElement.querySelector('[data-action="subscribe"]')).toBeNull();
      expect(text()).not.toContain('Stay on the free plan');
      expect(text()).toContain('You already have a subscription');
      const manage = fixture.nativeElement.querySelector('a[href="/account"]') as HTMLAnchorElement;
      expect(manage).toBeTruthy();
      expect(manage.textContent).toContain('Manage subscription');
    });
  });

  describe('the checkout call', () => {
    function subscribeButton(): HTMLButtonElement {
      return fixture.nativeElement.querySelector('[data-action="subscribe"]') as HTMLButtonElement;
    }

    beforeEach(async () => {
      store.view.set(accountView());
      await mount();
    });

    it('sends exactly one POST /api/billing/checkout on a click, and follows the returned link', async () => {
      const redirect = vi.spyOn(fixture.componentInstance, 'redirect').mockImplementation(() => {});

      subscribeButton().click();

      const req = http.expectOne('/api/billing/checkout');
      expect(req.request.method).toBe('POST');
      req.flush({ url: 'https://checkout.freemius.com/product/1/plan/2/' });
      await fixture.whenStable();

      expect(redirect).toHaveBeenCalledWith('https://checkout.freemius.com/product/1/plan/2/');
    });

    it('marks Subscribe busy, with a label that names the work, while the request runs', async () => {
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

    it('a second click while the call is in flight sends no second request', () => {
      vi.spyOn(fixture.componentInstance, 'redirect').mockImplementation(() => {});
      const button = subscribeButton();

      button.click();
      fixture.detectChanges();
      button.click();

      http.expectOne('/api/billing/checkout');
    });

    it('shows an error with role="alert" and re-enables the button when the call fails', async () => {
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

    it('refuses a link that does not start with https://, and never redirects', async () => {
      const redirect = vi.spyOn(fixture.componentInstance, 'redirect').mockImplementation(() => {});

      subscribeButton().click();
      http.expectOne('/api/billing/checkout').flush({ url: 'http://not-secure.example.com/x' });
      await fixture.whenStable();
      fixture.detectChanges();

      expect(redirect).not.toHaveBeenCalled();
      expect(fixture.nativeElement.querySelector('[role="alert"]')?.textContent).toContain(
        'Could not start checkout',
      );
    });
  });
});

/**
 * `AllowanceMeterComponent.subscribe`'s test once mocked `Router.navigate` — a spy that hid a
 * real defect. This block instead drives a real navigation to `/subscribe` and a real click on
 * "Stay on the free plan", through `RouterTestingHarness`, and reads `Router.url` back, so a
 * mistaken guard clause or a wrong-typed `routerLink` shows up as a real, wrong destination.
 */
describe('SubscribePageComponent — "Stay on the free plan", with the real router', () => {
  let harness: RouterTestingHarness;
  let http: HttpTestingController;
  let router: Router;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([
          { path: 'account', component: SubscribePageComponent },
          { path: 'subscribe', component: SubscribePageComponent },
        ]),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    harness = await RouterTestingHarness.create();
  });

  afterEach(() => http.verify());

  it('goes back to the in-app page the learner came from, and sends no request', async () => {
    await harness.navigateByUrl('/account');
    http.expectOne('/api/billing/plans').flush(PLANS);
    await harness.fixture.whenStable();

    await harness.navigateByUrl('/subscribe');
    http.expectOne('/api/billing/plans').flush(PLANS);
    await harness.fixture.whenStable();
    harness.detectChanges();

    const stay = harness.routeNativeElement?.querySelector(
      '[data-action="stay-on-free-plan"]',
    ) as HTMLAnchorElement;
    stay.click();
    await harness.fixture.whenStable();
    harness.detectChanges();

    expect(router.url).toBe('/account');
    // The route table reuses SubscribePageComponent for both paths, purely so this test needs no
    // second stub component — landing back on "/account" mounts a fresh instance, which fires its
    // own GET /api/billing/plans in turn. This flush is that request, and not evidence that
    // clicking "Stay on the free plan" itself sent one — that claim is `http.verify()` in
    // `afterEach`, over every request this test did not expect and did not flush here.
    http.expectOne('/api/billing/plans').flush(PLANS);
    await harness.fixture.whenStable();
  });

  it('goes to / when the learner opened the page directly, with no previous in-app page', async () => {
    await harness.navigateByUrl('/subscribe');
    http.expectOne('/api/billing/plans').flush(PLANS);
    await harness.fixture.whenStable();
    harness.detectChanges();

    const stay = harness.routeNativeElement?.querySelector(
      '[data-action="stay-on-free-plan"]',
    ) as HTMLAnchorElement;
    expect(stay.getAttribute('href')).toBe('/');
  });
});
