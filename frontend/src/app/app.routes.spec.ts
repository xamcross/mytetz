import { TestBed } from '@angular/core/testing';
import { Title } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { routes } from './app.routes';

/**
 * Issue #35's own acceptance criteria: every route names itself in the tab, not the generic
 * `index.html` default. Each test below drives a real navigation through the app's own route
 * table and reads `document.title` back from Angular's `Title` service, the same one the default
 * `TitleStrategy` writes to.
 */
describe('app.routes titles', () => {
  let http: HttpTestingController;
  let title: Title;
  let harness: RouterTestingHarness;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [provideRouter(routes), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    title = TestBed.inject(Title);
    harness = await RouterTestingHarness.create();
  });

  afterEach(() => http.verify());

  it('/ carries the product line', async () => {
    await harness.navigateByUrl('/');
    http.expectOne('/api/catalog/topics').flush([]);
    await harness.fixture.whenStable();

    expect(title.getTitle()).toBe('mytetz: understand hard topics one sentence at a time');
  });

  it('/privacy carries the page name', async () => {
    await harness.navigateByUrl('/privacy');
    expect(title.getTitle()).toBe('Privacy policy | mytetz');
  });

  it('/terms carries the heading the page shows, not the route segment', async () => {
    await harness.navigateByUrl('/terms');
    expect(title.getTitle()).toBe('Terms of service | mytetz');
  });

  it('/imprint carries the page name', async () => {
    await harness.navigateByUrl('/imprint');
    expect(title.getTitle()).toBe('Imprint | mytetz');
  });

  it('/account carries the page name', async () => {
    await harness.navigateByUrl('/account');
    http
      .expectOne('/api/account')
      .flush(
        { code: 'SIGN_IN_REQUIRED', message: 'sign in to continue' },
        { status: 401, statusText: '' },
      );
    await harness.fixture.whenStable();

    expect(title.getTitle()).toBe('Your account | mytetz');
  });

  it('/auth carries the page name', async () => {
    // A visit with no `auth` reason navigates straight back to `/`, so this test picks one of the
    // three reasons the route actually shows — see `AuthLandingComponent.message`.
    await harness.navigateByUrl('/auth?auth=failed');
    expect(title.getTitle()).toBe('Sign-in | mytetz');
  });

  it('/learn/:sessionId carries the topic once the session and the catalogue answer', async () => {
    await harness.navigateByUrl('/learn/s1');
    http.expectOne('/api/sessions/s1').flush({
      sessionId: 's1',
      topicSlug: 'quantum-physics',
      rootNodeId: 'n0',
      currentNodeId: 'n0',
      nodes: [
        {
          nodeId: 'n0',
          parentNodeId: null,
          explanationKey: 'k0',
          span: '',
          verb: 'SEED',
          variant: 0,
          depth: 0,
        },
      ],
      explanations: { k0: 'Quantum mechanics is odd.' },
    });
    await harness.fixture.whenStable();
    harness.detectChanges();

    // Before the catalogue answers, the reader still shows a title: the slug-derived fallback that
    // `ReaderPageComponent.topicLabel` falls back to.
    expect(title.getTitle()).toBe('Quantum Physics | mytetz');

    http.expectOne('/api/catalog/topics/quantum-physics').flush({
      slug: 'quantum-physics',
      title: 'Quantum Physics',
      category: 'Physics',
      summary: '',
    });
    await harness.fixture.whenStable();
    harness.detectChanges();

    expect(title.getTitle()).toBe('Quantum Physics | mytetz');
  });
});
