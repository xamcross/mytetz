import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { AuthLandingComponent } from './auth-landing.component';

/** Stands in for the catalogue at `/`, which `AuthLandingComponent`'s redirect targets. */
@Component({ selector: 'app-dummy-root', template: 'catalogue' })
class DummyRootComponent {}

describe('AuthLandingComponent', () => {
  let harness: RouterTestingHarness;
  let router: Router;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([
          { path: '', component: DummyRootComponent },
          { path: 'auth', component: AuthLandingComponent },
        ]),
      ],
    });
    router = TestBed.inject(Router);
    harness = await RouterTestingHarness.create();
  });

  /**
   * Asserts the error card and the sign-in panel for one reason value.
   *
   * Every reason still leaves email sign-in open — see the class comment — so the panel belongs
   * under the message for each one. The check also proves the order a screen reader needs: the
   * alert comes first in the DOM, then the panel, and the page holds one `<main>` only.
   */
  async function expectAlertAboveThePanel(url: string, text: string): Promise<void> {
    await harness.navigateByUrl(url, AuthLandingComponent);
    harness.detectChanges();

    const root = harness.routeNativeElement;
    const alert = root?.querySelector('[role="alert"]');
    const panel = root?.querySelector('app-sign-in-panel');

    expect(alert?.textContent).toContain(text);
    expect(panel?.querySelector('#sign-in-email')).toBeTruthy();
    expect(
      alert && panel && alert.compareDocumentPosition(panel) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(root?.querySelectorAll('main').length).toBe(1);
  }

  it('the landing reads the expired reason, with the sign-in panel below it', async () => {
    await expectAlertAboveThePanel(
      '/auth?auth=expired',
      'That link has expired or was already used.',
    );
  });

  it('the landing shows the failed reason, with the sign-in panel below it', async () => {
    await expectAlertAboveThePanel('/auth?auth=failed', 'Sign-in did not complete.');
  });

  it('the landing shows the unavailable reason, with the sign-in panel below it', async () => {
    await expectAlertAboveThePanel(
      '/auth?auth=unavailable',
      'Google sign-in is not available right now. Use email instead.',
    );
  });

  it('the landing shows the sign-in panel alone when the reason is absent', async () => {
    await harness.navigateByUrl('/auth', AuthLandingComponent);
    harness.detectChanges();
    await harness.fixture.whenStable();

    expect(router.url).toBe('/auth');
    const root = harness.routeNativeElement;
    expect(root?.querySelector('app-sign-in-panel')).toBeTruthy();
    expect(root?.querySelector('[role="alert"]')).toBeNull();
    expect(root?.querySelectorAll('main').length).toBe(1);
  });

  it('the landing renders nothing for a reason it does not recognise, before it redirects', async () => {
    await harness.navigateByUrl('/auth?auth=something-else', AuthLandingComponent);
    harness.detectChanges();

    const root = harness.routeNativeElement;
    expect(root?.querySelector('main')).toBeNull();
    expect(root?.querySelector('[role="alert"]')).toBeNull();
    expect(root?.querySelector('app-sign-in-panel')).toBeNull();

    await harness.fixture.whenStable();
    expect(router.url).toBe('/');
  });
});
