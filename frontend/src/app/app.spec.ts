import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';
import { routes } from './app.routes';
import { AccountStore } from './core/account.store';
import { AccountView } from './core/models';

/**
 * The root's own guard.
 *
 * The root had almost no test until the Candy work. That gap once let the whole app ship with no
 * `<router-outlet>`: every component test passed, and the running site showed the scaffold and
 * nothing else. Playwright found it. These tests are the cheaper guard.
 */
describe('App', () => {
  let http: HttpTestingController;
  /** Stubbed in every test, not only the one about it: without this, `ngOnInit`'s new account load
   * would send a real `GET /api/account` that no other test in this file flushes, and `afterEach`'s
   * `http.verify()` would fail on it. */
  let loadAccount: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter(routes)],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    loadAccount = vi.spyOn(TestBed.inject(AccountStore), 'load').mockResolvedValue(undefined);
  });

  afterEach(() => http.verify());

  /** Creates the root, answers the health request the way `answer` says, and settles the view. */
  async function render(answer: (request: TestRequest) => void): Promise<ComponentFixture<App>> {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    answer(http.expectOne('/api/health'));
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  const label = (fixture: ComponentFixture<App>): string | null =>
    (fixture.nativeElement.querySelector('.dot') as HTMLElement).getAttribute('aria-label');

  it('renders the shell and the router outlet', async () => {
    const fixture = await render((r) => r.flush({ status: 'ok', mongo: true }));

    expect(fixture.nativeElement.querySelector('app-shell')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('router-outlet')).not.toBeNull();
    // The scaffold said "backend: ok" in words. The dot says it now.
    expect(fixture.nativeElement.textContent).not.toContain('backend:');
  });

  it('reports a healthy backend', async () => {
    expect(label(await render((r) => r.flush({ status: 'ok', mongo: true })))).toBe('Backend ok');
  });

  it('reports a backend that answers without Mongo as degraded', async () => {
    expect(label(await render((r) => r.flush({ status: 'ok', mongo: false })))).toBe(
      'Backend degraded',
    );
  });

  it('reports a backend that does not answer as unreachable', async () => {
    // A transport failure, which is what a reader on a dead connection actually meets. A thrown
    // stub would prove the catch runs but not that it runs for the real reason.
    expect(label(await render((r) => r.error(new ProgressEvent('error'))))).toBe(
      'Backend unreachable',
    );
  });

  it('the app loads the account when it starts', async () => {
    await render((r) => r.flush({ status: 'ok', mongo: true }));

    expect(loadAccount).toHaveBeenCalledTimes(1);
  });

  it('carries a real account load from App down through the shell into the meter', async () => {
    // Every other spec in this file stubs `load()`, and the shell's and the meter's own specs
    // each drive one link of this chain with a hand-set signal. None of the three crosses the
    // whole seam with a real `GET /api/account` reaching real rendered text — this spec is that
    // one crossing.
    loadAccount.mockRestore();

    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();

    const view: AccountView = {
      email: 'learner@example.com',
      status: 'ACTIVE',
      trialEndsAtEpochMillis: null,
      currentPeriodEndsAtEpochMillis: null,
      allowance: 25,
      remaining: 9,
      resetsAtEpochMillis: null,
    };
    http.expectOne('/api/account').flush(view);
    http.expectOne('/api/health').flush({ status: 'ok', mongo: true });
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('9 of 25');
  });
});
