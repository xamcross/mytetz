import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { CatalogPageComponent } from './catalog-page.component';
import { SessionView, TopicSummary } from '../core/models';

const quantumPhysics: TopicSummary = {
  slug: 'quantum-physics',
  title: 'Quantum Physics',
  category: 'Physics',
  summary: 'Small things.',
};

const microbiology: TopicSummary = {
  slug: 'microbiology',
  title: 'Microbiology',
  category: 'Biology',
  summary: 'Tiny living things.',
};

// A complete SessionView, matching the real contract exactly — see Problem G: the brief's own
// fixture omitted topicSlug/rootNodeId/currentNodeId, which SessionView requires. Any test that
// silently narrowed to a partial object would stop pinning the real shape the backend sends.
function sessionFixture(overrides: Partial<SessionView> = {}): SessionView {
  return {
    sessionId: 's1',
    topicSlug: 'quantum-physics',
    rootNodeId: 'n1',
    currentNodeId: 'n1',
    nodes: [],
    explanations: { k1: 'Quantum mechanics describes small things.' },
    ...overrides,
  };
}

describe('CatalogPageComponent', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [CatalogPageComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lists topics returned by the API', async () => {
    const fixture = TestBed.createComponent(CatalogPageComponent);
    fixture.detectChanges();

    http.expectOne('/api/catalog/topics').flush([quantumPhysics]);
    await fixture.whenStable();
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Quantum Physics');
    // The brief's own test only pins the title. A component that rendered the wrong topic's
    // category/summary next to a correct title would still pass that check, so this also pins
    // that the other two TopicSummary fields reached the DOM.
    expect(text).toContain('Physics');
    expect(text).toContain('Small things.');
  });

  it('shows a loading state before the topics response arrives, then clears it', async () => {
    const fixture = TestBed.createComponent(CatalogPageComponent);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent as string).toContain('Loading');

    http.expectOne('/api/catalog/topics').flush([quantumPhysics]);
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent as string).not.toContain('Loading');
  });

  it('shows a retryable error when the topic list fails to load', async () => {
    const fixture = TestBed.createComponent(CatalogPageComponent);
    fixture.detectChanges();

    http.expectOne('/api/catalog/topics').flush(null, { status: 500, statusText: 'Server Error' });
    await fixture.whenStable();
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text.toLowerCase()).toContain('could not load');

    const retry = fixture.nativeElement.querySelector(
      'button.banner__retry-button',
    ) as HTMLButtonElement;
    expect(retry).toBeTruthy();
    retry.click();

    http.expectOne('/api/catalog/topics').flush([quantumPhysics]);
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent as string).toContain('Quantum Physics');
  });

  it('filters topics client-side, without issuing another request', async () => {
    const fixture = TestBed.createComponent(CatalogPageComponent);
    fixture.detectChanges();
    http.expectOne('/api/catalog/topics').flush([quantumPhysics, microbiology]);
    await fixture.whenStable();
    fixture.detectChanges();

    const input = fixture.nativeElement.querySelector('#topic-filter') as HTMLInputElement;
    input.value = 'micro';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Microbiology');
    expect(text).not.toContain('Quantum Physics');

    // http.verify() in afterEach fails the test if a second GET went out for this keystroke —
    // that's what proves filtering happened client-side rather than by re-querying the server.
  });

  it('shows a message when the filter matches nothing', async () => {
    const fixture = TestBed.createComponent(CatalogPageComponent);
    fixture.detectChanges();
    http.expectOne('/api/catalog/topics').flush([quantumPhysics]);
    await fixture.whenStable();
    fixture.detectChanges();

    const input = fixture.nativeElement.querySelector('#topic-filter') as HTMLInputElement;
    input.value = 'nonexistent-topic-xyz';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent as string).toContain('No topics match');
  });

  it('creates a session and navigates on selection', async () => {
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    const fixture = TestBed.createComponent(CatalogPageComponent);
    fixture.detectChanges();
    http.expectOne('/api/catalog/topics').flush([quantumPhysics]);
    await fixture.whenStable();
    fixture.detectChanges();

    fixture.nativeElement.querySelector('button[data-slug="quantum-physics"]').click();
    const req = http.expectOne('/api/sessions');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ topicSlug: 'quantum-physics' });
    req.flush(sessionFixture());
    await fixture.whenStable();

    expect(navigate).toHaveBeenCalledWith(['/learn', 's1']);
  });

  it('shows a starting indicator and disables every topic button while a session is being created', async () => {
    vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const fixture = TestBed.createComponent(CatalogPageComponent);
    fixture.detectChanges();
    http.expectOne('/api/catalog/topics').flush([quantumPhysics, microbiology]);
    await fixture.whenStable();
    fixture.detectChanges();

    const quantumButton = fixture.nativeElement.querySelector(
      'button[data-slug="quantum-physics"]',
    ) as HTMLButtonElement;
    const microButton = fixture.nativeElement.querySelector(
      'button[data-slug="microbiology"]',
    ) as HTMLButtonElement;

    quantumButton.click();
    fixture.detectChanges();

    expect(quantumButton.disabled).toBe(true);
    expect(microButton.disabled).toBe(true);
    expect(fixture.nativeElement.textContent as string).toContain('Starting');

    http.expectOne('/api/sessions').flush(sessionFixture());
    await fixture.whenStable();
    fixture.detectChanges();

    // Critical fix (post-review): buttons stay disabled once navigation has *succeeded*, not
    // just while it's pending. The component is about to be torn down by the route change, so
    // there is nothing left to re-enable for — and re-enabling here is exactly what let a click
    // land in the gap between "session created" and "route actually swapped" and fire a second,
    // paid createSession. See the next test for that gap pinned directly.
    expect(quantumButton.disabled).toBe(true);
    expect(microButton.disabled).toBe(true);
  });

  it('ignores a second click while a session creation is already pending', async () => {
    vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const fixture = TestBed.createComponent(CatalogPageComponent);
    fixture.detectChanges();
    http.expectOne('/api/catalog/topics').flush([quantumPhysics]);
    await fixture.whenStable();
    fixture.detectChanges();

    const button = fixture.nativeElement.querySelector(
      'button[data-slug="quantum-physics"]',
    ) as HTMLButtonElement;
    button.click();
    button.click();
    button.click();

    // If a second request had gone out, expectOne would throw here for matching more than one
    // pending request — that failure IS the assertion that the guard works.
    http.expectOne('/api/sessions').flush(sessionFixture());
    await fixture.whenStable();
  });

  it('does not start a second session for a click landing after the response but before navigation settles', async () => {
    // This is the window the post-review Critical fix closes: `createSession` has already
    // resolved (the session exists, a slot has been spent) but `router.navigate()` — standing in
    // for, e.g., a slow fetch of Task 1.16's lazy reader chunk — has not resolved yet. A click in
    // that exact gap must not fire a second `POST /api/sessions`.
    let resolveNavigate!: (value: boolean) => void;
    const pendingNavigate = new Promise<boolean>((resolve) => {
      resolveNavigate = resolve;
    });
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockReturnValue(pendingNavigate);

    const fixture = TestBed.createComponent(CatalogPageComponent);
    fixture.detectChanges();
    http.expectOne('/api/catalog/topics').flush([quantumPhysics]);
    await fixture.whenStable();
    fixture.detectChanges();

    const button = fixture.nativeElement.querySelector(
      'button[data-slug="quantum-physics"]',
    ) as HTMLButtonElement;
    button.click();

    http.expectOne('/api/sessions').flush(sessionFixture());
    // Let the createSession promise's continuation run — including the synchronous call into
    // router.navigate() — without waiting on navigate's own still-unresolved promise.
    await Promise.resolve();
    await Promise.resolve();
    fixture.detectChanges();

    // Made explicit rather than inferred from tick count (post-review hardening): navigate() has
    // genuinely been reached and is the thing still pending, not just "some number of microtasks
    // happened to elapse."
    expect(navigate).toHaveBeenCalledWith(['/learn', 's1']);

    // The session exists and navigate() has been called but is still pending. A click here must
    // be a no-op: if it weren't, it would have issued a second, unflushed POST /api/sessions,
    // and http.verify() below would fail on it.
    button.click();
    http.verify();

    resolveNavigate(true);
    await fixture.whenStable();
  });

  it('surfaces a message and a working retry when navigation to the new session fails', async () => {
    // A rejected navigate() is what a real NavigationError looks like (confirmed by reading
    // @angular/router's own NavigationTransitions source) — e.g. a failed fetch of Task 1.16's
    // lazy reader chunk. The session already exists server-side by this point, so silently doing
    // nothing here would look exactly like the click never registered, except a slot (and
    // possibly a model call) was already spent.
    const navigate = vi
      .spyOn(TestBed.inject(Router), 'navigate')
      .mockRejectedValueOnce(new Error('failed to load chunk reader-page-component'));
    const fixture = TestBed.createComponent(CatalogPageComponent);
    fixture.detectChanges();
    http.expectOne('/api/catalog/topics').flush([quantumPhysics]);
    await fixture.whenStable();
    fixture.detectChanges();

    const button = fixture.nativeElement.querySelector(
      'button[data-slug="quantum-physics"]',
    ) as HTMLButtonElement;
    button.click();
    http.expectOne('/api/sessions').flush(sessionFixture());
    await fixture.whenStable();
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Your session was created, but the reader could not load.');
    // The topic tile stays disabled (post-review fix — see `tilesLocked`): a session for it
    // already exists, so re-enabling it would let a click run createSession a *second* time for
    // the same topic. The `title` attribute states why, since a disabled control with no stated
    // reason is its own small "nothing happened".
    expect(button.disabled).toBe(true);
    expect(button.title).toContain('Resolve the message above');

    const retry = fixture.nativeElement.querySelector(
      'button.banner__retry-button',
    ) as HTMLButtonElement;
    expect(retry).toBeTruthy();

    // Retrying must re-navigate to the session that already exists, never re-run createSession —
    // doing the latter would spend a second slot on a topic the learner already has a session
    // for. http.verify() below fails if any unexpected /api/sessions request appeared.
    navigate.mockResolvedValueOnce(true);
    retry.click();
    await fixture.whenStable();

    expect(navigate).toHaveBeenLastCalledWith(['/learn', 's1']);
    http.verify();
  });

  it('keeps every tile locked after a navigation failure, so clicking the original topic does not start a second session', async () => {
    // The narrower race found in the second review round: failNavigation clears pendingSlug so
    // the "Try again" action becomes reachable, but a session for the clicked topic already
    // exists at that point. If clearing pendingSlug alone re-enabled the tiles, clicking the
    // *original* topic again — not just some other one — would spend a second slot on a session
    // that already exists.
    vi.spyOn(TestBed.inject(Router), 'navigate').mockRejectedValueOnce(
      new Error('failed to load chunk reader-page-component'),
    );
    const fixture = TestBed.createComponent(CatalogPageComponent);
    fixture.detectChanges();
    http.expectOne('/api/catalog/topics').flush([quantumPhysics, microbiology]);
    await fixture.whenStable();
    fixture.detectChanges();

    const quantumButton = fixture.nativeElement.querySelector(
      'button[data-slug="quantum-physics"]',
    ) as HTMLButtonElement;
    const microButton = fixture.nativeElement.querySelector(
      'button[data-slug="microbiology"]',
    ) as HTMLButtonElement;

    quantumButton.click();
    http.expectOne('/api/sessions').flush(sessionFixture());
    await fixture.whenStable();
    fixture.detectChanges();

    // Both tiles are locked, not just the one that failed — a fresh, unrelated topic offers no
    // way around the stuck reopen either.
    expect(quantumButton.disabled).toBe(true);
    expect(microButton.disabled).toBe(true);

    quantumButton.click();
    microButton.click();

    // Pinned as the brief for this fix round asked: if either click had gone through, it would
    // have issued a second, unflushed POST /api/sessions here, and http.verify() would fail on
    // it before we ever get to resolve the pending reopen below.
    http.verify();
  });

  it('shows the retry wait for a 429 RATE_LIMITED refusal (Task 1.11 session limiter)', async () => {
    const fixture = TestBed.createComponent(CatalogPageComponent);
    fixture.detectChanges();
    http.expectOne('/api/catalog/topics').flush([quantumPhysics]);
    await fixture.whenStable();
    fixture.detectChanges();

    fixture.nativeElement.querySelector('button[data-slug="quantum-physics"]').click();
    http.expectOne('/api/sessions').flush(
      {
        code: 'RATE_LIMITED',
        message: 'too many sessions started; try again later',
        retryAfter: 3600,
      },
      { status: 429, statusText: 'Too Many Requests' },
    );
    await fixture.whenStable();
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('too many sessions started; try again later');
    expect(text).toContain('Try again in 1 hour.');
  });

  it('shows a degraded-service message with no retry countdown for a 503 SPEND_LIMIT refusal (Task 1.12 breaker)', async () => {
    const fixture = TestBed.createComponent(CatalogPageComponent);
    fixture.detectChanges();
    http.expectOne('/api/catalog/topics').flush([quantumPhysics]);
    await fixture.whenStable();
    fixture.detectChanges();

    fixture.nativeElement.querySelector('button[data-slug="quantum-physics"]').click();
    http.expectOne('/api/sessions').flush(
      {
        code: 'SPEND_LIMIT',
        message: 'new explanations are paused for today; cached ones still work',
        retryAfter: null,
      },
      { status: 503, statusText: 'Service Unavailable' },
    );
    await fixture.whenStable();
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('new explanations are paused for today; cached ones still work');
    // A rate limit says "try later"; a spend limit says the service is degraded — no invented
    // wait time should be shown for the latter, since the server gave none.
    expect(text).not.toContain('Try again in');
  });

  // QUOTA_EXCEEDED's retryAfter ranges across the whole day (1–86400s, per Task 1.8's quota
  // window), unlike RATE_LIMITED's fixed 3600 — so the singular/plural and unit-rollover
  // boundaries in formatRetryAfter are all reachable in production, not just theoretical. Each
  // case below is a value on one side of a rollover (seconds→minutes at 60, minutes→hours at 60
  // minutes) plus the exact boundary itself.
  it.each([
    [59, '59 seconds'],
    [60, '1 minute'],
    [61, '2 minutes'],
    [3599, '1 hour'],
    [3601, '2 hours'],
  ])(
    'formats a %i-second QUOTA_EXCEEDED retryAfter as "Try again in %s."',
    async (retryAfterSeconds, expectedDuration) => {
      const fixture = TestBed.createComponent(CatalogPageComponent);
      fixture.detectChanges();
      http.expectOne('/api/catalog/topics').flush([quantumPhysics]);
      await fixture.whenStable();
      fixture.detectChanges();

      fixture.nativeElement.querySelector('button[data-slug="quantum-physics"]').click();
      http.expectOne('/api/sessions').flush(
        {
          code: 'QUOTA_EXCEEDED',
          message: "you have used today's allowance of new explanations",
          retryAfter: retryAfterSeconds,
        },
        { status: 429, statusText: 'Too Many Requests' },
      );
      await fixture.whenStable();
      fixture.detectChanges();

      expect(fixture.nativeElement.textContent as string).toContain(
        `Try again in ${expectedDuration}.`,
      );
    },
  );

  it('re-enables selection after a refusal, so the learner can pick a different topic', async () => {
    const fixture = TestBed.createComponent(CatalogPageComponent);
    fixture.detectChanges();
    http.expectOne('/api/catalog/topics').flush([quantumPhysics]);
    await fixture.whenStable();
    fixture.detectChanges();

    const button = fixture.nativeElement.querySelector(
      'button[data-slug="quantum-physics"]',
    ) as HTMLButtonElement;
    button.click();
    http
      .expectOne('/api/sessions')
      .flush(
        { code: 'SPEND_LIMIT', message: 'paused', retryAfter: null },
        { status: 503, statusText: 'Service Unavailable' },
      );
    await fixture.whenStable();
    fixture.detectChanges();

    expect(button.disabled).toBe(false);
  });
});
