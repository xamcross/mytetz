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
    const navigate = vi.spyOn(router, 'navigate');
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

    expect(quantumButton.disabled).toBe(false);
    expect(microButton.disabled).toBe(false);
  });

  it('ignores a second click while a session creation is already pending', async () => {
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
