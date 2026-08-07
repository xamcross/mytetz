import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ApiService } from '../core/api.service';
import { SignInPanelComponent } from './sign-in-panel.component';

describe('SignInPanelComponent', () => {
  let api: ApiService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [SignInPanelComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(ApiService);
  });

  function create(): ComponentFixture<SignInPanelComponent> {
    const fixture = TestBed.createComponent(SignInPanelComponent);
    fixture.detectChanges();
    return fixture;
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
});
