import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ApiService } from '../core/api.service';
import { AuthConfig } from '../core/models';
import { TURNSTILE_SCRIPT_URL, TurnstileApi } from './turnstile';
import { SignInPanelComponent } from './sign-in-panel.component';

const NO_TURNSTILE: AuthConfig = {
  turnstileSiteKey: null,
  googleEnabled: true,
  magicLinkEnabled: true,
};

describe('SignInPanelComponent', () => {
  let api: ApiService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [SignInPanelComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(ApiService);
    // Every existing test in this file predates `GET /api/auth/config`. Stubbed here, once, to a
    // deployment with no Turnstile secret: the panel's prior behaviour exactly. Those tests then
    // need no change of their own. Tests about the widget itself override this.
    vi.spyOn(api, 'authConfig').mockResolvedValue(NO_TURNSTILE);
  });

  afterEach(() => {
    delete window.turnstile;
    document.head.querySelector(`script[src="${TURNSTILE_SCRIPT_URL}"]`)?.remove();
  });

  function create(): ComponentFixture<SignInPanelComponent> {
    const fixture = TestBed.createComponent(SignInPanelComponent);
    fixture.detectChanges();
    return fixture;
  }

  /** Runs enough render and stability cycles for `loadConfig()`'s promise, and the `viewChild` it
   * gates, to both settle. One pass resolves the config, and shows the widget container in the
   * template. A second pass lets the render effect see that container appear. */
  async function settle(fixture: ComponentFixture<SignInPanelComponent>): Promise<void> {
    for (let i = 0; i < 2; i++) {
      await fixture.whenStable();
      fixture.detectChanges();
    }
  }

  /** Fills the email field and submits the form, as a learner using a keyboard would. */
  async function submitWith(fixture: ComponentFixture<SignInPanelComponent>, email: string) {
    const input = fixture.nativeElement.querySelector('#sign-in-email') as HTMLInputElement;
    input.value = email;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    const form = fixture.nativeElement.querySelector('form') as HTMLFormElement;
    form.dispatchEvent(new Event('submit', { cancelable: true }));
    await fixture.whenStable();
    fixture.detectChanges();
  }

  /**
   * Stubs the config to a site key. Settles the fixture until the widget script has been
   * requested, then completes the load the way the real Cloudflare script would: it defines
   * `window.turnstile`, and fires the script's `load` event. Returns the fake API, for a test to
   * inspect how `render()` was called, and a `solve()` helper that invokes the captured callback
   * with a token — the moment a learner would actually complete the challenge.
   */
  async function createWithWidget(siteKey = 'test-site-key'): Promise<{
    fixture: ComponentFixture<SignInPanelComponent>;
    api: TurnstileApi;
    solve: (token: string) => void;
  }> {
    vi.spyOn(api, 'authConfig').mockResolvedValue({ ...NO_TURNSTILE, turnstileSiteKey: siteKey });
    const fixture = create();
    await settle(fixture);

    const script = document.head.querySelector<HTMLScriptElement>(
      `script[src="${TURNSTILE_SCRIPT_URL}"]`,
    );
    if (!script) throw new Error('fixture error: the panel never requested the Turnstile script');

    const turnstileApi: TurnstileApi = { render: vi.fn(() => 'widget-1'), reset: vi.fn() };
    window.turnstile = turnstileApi;
    script.dispatchEvent(new Event('load'));
    await settle(fixture);

    const renderCall = (turnstileApi.render as ReturnType<typeof vi.fn>).mock.calls[0];
    if (!renderCall) throw new Error('fixture error: the widget was never rendered');
    const solve = (token: string) => {
      (renderCall[1] as { callback: (t: string) => void }).callback(token);
      fixture.detectChanges();
    };

    return { fixture, api: turnstileApi, solve };
  }

  it('the panel shows the sent state after a request', async () => {
    vi.spyOn(api, 'requestMagicLink').mockResolvedValue(undefined);
    const fixture = create();

    await submitWith(fixture, 'learner@example.com');

    expect(fixture.nativeElement.textContent).toContain('Check your email');
    expect(fixture.nativeElement.querySelector('#sign-in-email')).toBeNull();
  });

  it('the panel never says whether the address was known', async () => {
    const spy = vi.spyOn(api, 'requestMagicLink').mockResolvedValue(undefined);

    // The backend answers 204 for both a known and an unknown address, and this panel must read
    // the same either way — the property this test exists to pin.
    const known = create();
    await submitWith(known, 'known@example.com');
    const knownText = known.nativeElement.textContent as string;

    const unknown = create();
    await submitWith(unknown, 'unknown@example.com');
    const unknownText = unknown.nativeElement.textContent as string;

    expect(knownText).toBe(unknownText);
    expect(spy).toHaveBeenCalledTimes(2);
  });

  it('the panel names a rate limit rather than the connection', async () => {
    // AuthRoutes.kt answers 429 RATE_LIMITED on this route when either of its two limiters trips.
    // "Check your connection" is wrong advice for a learner who is not offline — the request did
    // reach the server, and the server said no.
    vi.spyOn(api, 'requestMagicLink').mockRejectedValue(
      new HttpErrorResponse({
        status: 429,
        error: { code: 'RATE_LIMITED', message: 'too many sign-in requests; try again later' },
      }),
    );
    const fixture = create();

    await submitWith(fixture, 'learner@example.com');

    expect(fixture.nativeElement.textContent).toContain('Too many requests');
    expect(fixture.nativeElement.textContent).not.toContain('connection');
  });

  it('the panel refuses an empty address without calling the api', async () => {
    const spy = vi.spyOn(api, 'requestMagicLink');
    const fixture = create();

    await submitWith(fixture, '   ');

    expect(spy).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('Enter an email address.');
  });

  it('the google button targets the google route', () => {
    const fixture = create();

    const link = fixture.nativeElement.querySelector('a') as HTMLAnchorElement;
    expect(link.getAttribute('href')).toBe('/api/auth/google');
  });

  // ------------------------------------------------------------------ the turnstile widget

  it('renders no widget container and requests no script when the config carries a null site key', async () => {
    const fixture = create();
    await settle(fixture);

    expect(fixture.nativeElement.querySelector('[data-testid="turnstile-container"]')).toBeNull();
    expect(document.head.querySelector(`script[src="${TURNSTILE_SCRIPT_URL}"]`)).toBeNull();
  });

  it('requests the script and renders exactly one widget when the config carries a site key', async () => {
    const { api: turnstileApi } = await createWithWidget('a-real-site-key');

    expect(turnstileApi.render).toHaveBeenCalledTimes(1);
    const [, options] = (turnstileApi.render as ReturnType<typeof vi.fn>).mock.calls[0];
    expect((options as { sitekey: string }).sitekey).toBe('a-real-site-key');
  });

  it('sends the solved token as turnstileToken on the magic-link request', async () => {
    const { fixture, solve } = await createWithWidget();
    const spy = vi.spyOn(api, 'requestMagicLink').mockResolvedValue(undefined);
    solve('a-solved-token');

    await submitWith(fixture, 'learner@example.com');

    expect(spy).toHaveBeenCalledWith('learner@example.com', 'a-solved-token');
  });

  it('submits with no turnstileToken before the widget has been solved', async () => {
    const { fixture } = await createWithWidget();
    const spy = vi.spyOn(api, 'requestMagicLink').mockResolvedValue(undefined);

    await submitWith(fixture, 'learner@example.com');

    expect(spy).toHaveBeenCalledWith('learner@example.com', null);
  });

  it('appends the solved token to the google link as a query parameter', async () => {
    const { fixture, solve } = await createWithWidget();

    solve('a-solved-token');

    const link = fixture.nativeElement.querySelector('a') as HTMLAnchorElement;
    expect(link.getAttribute('href')).toBe('/api/auth/google?turnstileToken=a-solved-token');
  });

  it('resets the widget and shows the server message on a TURNSTILE_FAILED answer', async () => {
    const { fixture, api: turnstileApi, solve } = await createWithWidget();
    solve('a-spent-token');
    vi.spyOn(api, 'requestMagicLink').mockRejectedValue(
      new HttpErrorResponse({
        status: 403,
        error: {
          code: 'TURNSTILE_FAILED',
          message: 'verification failed; reload the page and try again',
        },
      }),
    );

    await submitWith(fixture, 'learner@example.com');

    expect(fixture.nativeElement.textContent).toContain(
      'verification failed; reload the page and try again',
    );
    expect(turnstileApi.reset).toHaveBeenCalledWith('widget-1');
    // No fresh solve has replaced the token yet. The google link must not still carry it.
    const link = fixture.nativeElement.querySelector('a') as HTMLAnchorElement;
    expect(link.getAttribute('href')).toBe('/api/auth/google');
  });
});
