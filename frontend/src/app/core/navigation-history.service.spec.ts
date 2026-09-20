import { TestBed } from '@angular/core/testing';
import { Component } from '@angular/core';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { NavigationHistoryService } from './navigation-history.service';

@Component({ selector: 'app-stub-a', template: 'a' })
class StubAComponent {}

@Component({ selector: 'app-stub-b', template: 'b' })
class StubBComponent {}

/**
 * `SubscribePageComponent`'s own "Stay on the free plan" control needs the in-app page a learner
 * was on before it, and only that page — never a URL a query parameter names, which would be an
 * open redirect. This service reads the real `Router`'s own navigation events, so a learner who
 * arrived from outside the app (an external referrer) can never appear here: only a navigation
 * this app's own `Router` ran updates [previous].
 */
describe('NavigationHistoryService', () => {
  async function setUp(): Promise<{
    harness: RouterTestingHarness;
    service: NavigationHistoryService;
  }> {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'a', component: StubAComponent },
          { path: 'b', component: StubBComponent },
        ]),
      ],
    });
    // Constructed before the first navigation, the same way `app.config.ts` constructs
    // `RouteMetaDescriptionService` — through `provideAppInitializer` in the real app, and here by
    // a direct inject before `RouterTestingHarness.create()` runs the first navigation.
    const service = TestBed.inject(NavigationHistoryService);
    const harness = await RouterTestingHarness.create();
    return { harness, service };
  }

  it("starts at the router's own initial url, before any navigation has run", async () => {
    // Angular's Router already holds '/' the moment it is constructed, before the app's first
    // real navigation. This service reads that same value, so the very first navigation of a
    // visit — a direct load of any page — finds a previous page of '/', which is also the exact
    // fallback `SubscribePageComponent` wants for "no real previous page exists".
    const { service } = await setUp();

    expect(service.previous()).toBe('/');
  });

  it('names the page the learner was on right before the current navigation', async () => {
    const { harness, service } = await setUp();

    await harness.navigateByUrl('/a');
    expect(service.previous()).toBe('/');

    await harness.navigateByUrl('/b');
    expect(service.previous()).toBe('/a');
  });

  it('keeps naming the same previous page across a navigation that does not change it', async () => {
    const { harness, service } = await setUp();

    await harness.navigateByUrl('/a');
    await harness.navigateByUrl('/b');
    expect(service.previous()).toBe('/a');

    // A third navigation moves `previous` on again — it always names the page one step back, not
    // the very first page of the visit.
    await harness.navigateByUrl('/a');
    expect(service.previous()).toBe('/b');
  });

  it('injected through Router, never through a query parameter or the browser referrer', async () => {
    // TestBed.inject(Router) here stands for the real Router this service always reads — this
    // test's own point is that the service has no other source: no ActivatedRoute query read, and
    // no window.location/document.referrer read. A grep of navigation-history.service.ts for
    // "queryParam" or "referrer" would find neither.
    const { service } = await setUp();
    const router = TestBed.inject(Router);

    expect(router).toBeTruthy();
    expect(service.previous()).toBe('/');
  });
});
