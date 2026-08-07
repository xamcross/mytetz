import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
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
        provideRouter([
          { path: '', component: DummyRootComponent },
          { path: 'auth', component: AuthLandingComponent },
        ]),
      ],
    });
    router = TestBed.inject(Router);
    harness = await RouterTestingHarness.create();
  });

  it('the landing reads the expired reason', async () => {
    await harness.navigateByUrl('/auth?auth=expired', AuthLandingComponent);
    harness.detectChanges();

    expect(harness.routeNativeElement?.textContent).toContain(
      'That link has expired or was already used.',
    );
  });

  it('the landing shows the failed reason', async () => {
    await harness.navigateByUrl('/auth?auth=failed', AuthLandingComponent);
    harness.detectChanges();

    expect(harness.routeNativeElement?.textContent).toContain('Sign-in did not complete.');
  });

  it('the landing redirects when the reason is absent', async () => {
    await harness.navigateByUrl('/auth', AuthLandingComponent);
    harness.detectChanges();
    await harness.fixture.whenStable();

    expect(router.url).toBe('/');
  });

  it('the landing redirects for a reason it does not recognise', async () => {
    await harness.navigateByUrl('/auth?auth=something-else', AuthLandingComponent);
    harness.detectChanges();
    await harness.fixture.whenStable();

    expect(router.url).toBe('/');
  });
});
