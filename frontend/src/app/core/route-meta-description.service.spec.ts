import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Meta } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { RouteMetaDescriptionService } from './route-meta-description.service';

@Component({ selector: 'app-stub', template: '' })
class StubComponent {}

describe('RouteMetaDescriptionService', () => {
  let meta: Meta;
  let harness: RouterTestingHarness;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'a', component: StubComponent, data: { description: 'Description A.' } },
          { path: 'b', component: StubComponent, data: { description: 'Description B.' } },
          { path: 'c', component: StubComponent },
        ]),
      ],
    });
    meta = TestBed.inject(Meta);
    // Constructed by hand, the way `provideAppInitializer` in `app.config.ts` constructs it at
    // bootstrap: nothing else injects this service, so a test must do the same to make it run.
    TestBed.inject(RouteMetaDescriptionService);
    harness = await RouterTestingHarness.create();
  });

  const description = (): string | null => meta.getTag('name="description"')?.content ?? null;

  it('sets the description tag from the activated route on navigation', async () => {
    await harness.navigateByUrl('/a');
    expect(description()).toBe('Description A.');
  });

  it('replaces the tag when a later route sets its own description', async () => {
    await harness.navigateByUrl('/a');
    await harness.navigateByUrl('/b');
    expect(description()).toBe('Description B.');
  });

  it('leaves the previous description in place when a route sets none of its own', async () => {
    // Every real route in `app.routes.ts` sets a description or is `learn/:sessionId`, whose own
    // component sets a title but never touches this tag — so there is no real route this guards
    // today. It stays because the walk falls through to `null` for any future route that forgets
    // one, and a silently cleared tag is a worse failure than a stale one.
    await harness.navigateByUrl('/a');
    await harness.navigateByUrl('/c');
    expect(description()).toBe('Description A.');
  });
});
