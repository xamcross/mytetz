import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { ReaderPageComponent } from './reader-page.component';
import { EXPLAIN_STREAM, ExplainStreamFn } from './session.store';
import { SessionView } from '../core/models';
import { ExplainEvent, ExplainStreamError } from '../core/sse.client';

const view: SessionView = {
  sessionId: 's1',
  topicSlug: 'quantum-physics',
  rootNodeId: 'n0',
  currentNodeId: 'n1',
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
    {
      nodeId: 'n1',
      parentNodeId: 'n0',
      explanationKey: 'k1',
      span: 'fundamental physical theory',
      verb: 'EXPLAIN',
      variant: 0,
      depth: 1,
    },
  ],
  explanations: { k0: 'Quantum mechanics is odd.', k1: 'The pillars of modern physics.' },
};

const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve, 0));

describe('ReaderPageComponent', () => {
  let http: HttpTestingController;
  let harness: RouterTestingHarness;
  let script: ExplainStreamFn;
  /** The `AbortSignal` handed to each `explainStream` call, so a test can assert what happens to an
   * in-flight generation when the reader goes away. */
  let signals: Array<AbortSignal | undefined>;

  beforeEach(async () => {
    signals = [];
    script = () => {
      throw new Error('this test triggered an explain without installing a stream script');
    };
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'learn/:sessionId', component: ReaderPageComponent }]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: EXPLAIN_STREAM,
          useValue: ((sessionId, body, signal) => {
            signals.push(signal);
            return script(sessionId, body, signal);
          }) satisfies ExplainStreamFn,
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
  });

  afterEach(() => http.verify());

  /**
   * Opens the reader on `/learn/{response.sessionId}` and answers both requests that a load makes.
   *
   * The store reads the session, then reads the curated topic title from the catalogue. The second
   * request is not awaited by the store, so it is flushed here. [title] of `null` answers 404,
   * which is what a topic that a curator unpublished returns.
   *
   * Keyed off [response]'s own id, rather than a hard-coded `'s1'`, so a test that opens more than
   * one session — moving from one failing code to another, say — can reuse the one harness the
   * suite already has: `RouterTestingHarness` allows only one harness for each test, and Angular
   * reuses this component across a parameter-only route change (see the test below named for
   * exactly that), so a second, distinctly-id'd `open()` call is what a second load needs.
   */
  async function open(
    response: SessionView = view,
    title: string | null = 'Quantum Physics',
  ): Promise<ReaderPageComponent> {
    const component = await harness.navigateByUrl(
      `/learn/${response.sessionId}`,
      ReaderPageComponent,
    );
    http.expectOne(`/api/sessions/${response.sessionId}`).flush(response);
    await harness.fixture.whenStable();
    flushTopic(response.topicSlug, title);
    await harness.fixture.whenStable();
    harness.detectChanges();
    return component;
  }

  function flushTopic(slug: string, title: string | null): void {
    const request = http.expectOne(`/api/catalog/topics/${slug}`);
    if (title === null) {
      request.flush(
        { code: 'NOT_FOUND', message: 'no such topic' },
        { status: 404, statusText: '' },
      );
    } else {
      request.flush({ slug, title, category: 'Physics', summary: 'A summary.' });
    }
  }

  /** Highlights `pillars` in the focus card and presses a verb. */
  function highlightAndExplain(): void {
    const bodyEl = harness.routeNativeElement?.querySelector('.focus__body') as HTMLElement;
    const range = document.createRange();
    range.setStart(bodyEl.firstChild as Text, 4);
    range.setEnd(bodyEl.firstChild as Text, 11);
    const selection = window.getSelection();
    selection?.removeAllRanges();
    selection?.addRange(range);
    bodyEl.dispatchEvent(new Event('mouseup'));
    harness.detectChanges();
    const explain =
      harness.routeNativeElement?.querySelector<HTMLButtonElement>('[data-verb="EXPLAIN"]');
    if (!explain) throw new Error('the verb picker did not open for the highlighted phrase');
    explain.click();
    selection?.removeAllRanges();
  }

  const text = (): string => harness.routeNativeElement?.textContent ?? '';

  it('loads the session named in the route and composes rail, breadcrumb and card', async () => {
    await open();

    expect(text()).toContain('The pillars of modern physics.');
    // The breadcrumb's root crumb stands in for the topic — the root node's own span is empty.
    expect(text()).toContain('Quantum Physics');
    expect(harness.routeNativeElement?.querySelectorAll('.trail__item').length).toBe(2);
  });

  it('moves the focus from the trail rail without another request', async () => {
    await open();

    harness.routeNativeElement?.querySelector<HTMLButtonElement>('[data-node-id="n0"]')?.click();
    harness.detectChanges();

    expect(text()).toContain('Quantum mechanics is odd.');
    // afterEach's http.verify() fails if navigating the trail issued a request.
  });

  it('explains the highlighted phrase when a verb is pressed', async () => {
    script = async function* () {
      yield { event: 'delta', data: { t: 'Because ' } } satisfies ExplainEvent;
      yield { event: 'delta', data: { t: 'they are.' } } satisfies ExplainEvent;
      yield { event: 'done', data: { contentKey: 'k2', grounded: true } } satisfies ExplainEvent;
    };
    await open();
    const bodyEl = harness.routeNativeElement?.querySelector('.focus__body') as HTMLElement;

    highlightAndExplain();
    await tick();
    harness.detectChanges();

    // Streamed prose is on screen before the session is re-read, and it is *not* inside the
    // selectable root — the offsets a mid-stream selection would produce index a string no stored
    // body matches.
    expect(text()).toContain('Because they are.');
    expect(bodyEl.textContent).toBe('The pillars of modern physics.');

    const explained: SessionView = {
      ...view,
      currentNodeId: 'n2',
      nodes: [
        ...view.nodes,
        {
          nodeId: 'n2',
          parentNodeId: 'n1',
          explanationKey: 'k2',
          span: 'pillars',
          verb: 'EXPLAIN',
          variant: 0,
          depth: 2,
        },
      ],
      explanations: { ...view.explanations, k2: 'Because they are.' },
    };
    http.expectOne('/api/sessions/s1').flush(explained);
    await harness.fixture.whenStable();
    harness.detectChanges();

    expect(harness.routeNativeElement?.querySelectorAll('.crumb').length).toBe(3);
    expect(
      (harness.routeNativeElement?.querySelector('.focus__body') as HTMLElement).textContent,
    ).toBe('Because they are.');
  });

  it('banners a failed re-read over the still-readable session, rather than replacing the page', async () => {
    script = async function* () {
      yield { event: 'delta', data: { t: 'Because they are.' } } satisfies ExplainEvent;
      yield { event: 'done', data: { contentKey: 'k2', grounded: true } } satisfies ExplainEvent;
    };
    await open();

    highlightAndExplain();
    await tick();
    http.expectOne('/api/sessions/s1').flush(null, { status: 500, statusText: 'Server Error' });
    await harness.fixture.whenStable();
    harness.detectChanges();

    // The generation succeeded and was paid for; only the GET that would have picked up the new
    // node failed. The session on screen is stale, not wrong — so the failure belongs in a banner
    // over a working reader, not on a page that replaces it.
    //
    // This is a load-kind failure, which is what makes the test worth having: keying the full-page
    // branch on `kind` instead of "is there a session to show" passes every other test in this file
    // and blanks the reader here.
    expect(harness.routeNativeElement?.querySelector('.banner')).toBeTruthy();
    expect(text()).toContain('could not be re-read');
    expect(text()).toContain('The pillars of modern physics.');
    expect(harness.routeNativeElement?.querySelector('.focus__body')).toBeTruthy();
    expect(harness.routeNativeElement?.querySelector('.banner__back')).toBeNull();

    // And the recovery is the idempotent GET, not a second generation.
    harness.routeNativeElement?.querySelector<HTMLButtonElement>('.banner__retry-button')?.click();
    http.expectOne('/api/sessions/s1').flush(view);
    await harness.fixture.whenStable();
    flushTopic(view.topicSlug, 'Quantum Physics');
    await harness.fixture.whenStable();
    harness.detectChanges();

    expect(signals.length).toBe(1);
    expect(harness.routeNativeElement?.querySelector('.banner')).toBeNull();
  });

  it('abandons an in-flight generation when the reader is destroyed', async () => {
    let release!: () => void;
    const held = new Promise<void>((resolve) => (release = resolve));
    script = async function* () {
      yield { event: 'delta', data: { t: 'Because ' } } satisfies ExplainEvent;
      await held;
      yield { event: 'done', data: { contentKey: 'k2', grounded: true } } satisfies ExplainEvent;
    };
    await open();

    highlightAndExplain();
    await tick();
    expect(signals[0]?.aborted).toBe(false);

    harness.fixture.destroy();
    expect(signals[0]?.aborted).toBe(true);

    // The learner has gone. Nothing may be written for them afterwards — and in particular the
    // `done` below must not fire a re-fetch of a session nobody is looking at, which afterEach's
    // http.verify() is what proves.
    release();
    await tick();
  });

  it("drops the previous session's streamed prose when the route moves to another session", async () => {
    let release!: () => void;
    const held = new Promise<void>((resolve) => (release = resolve));
    script = async function* () {
      yield {
        event: 'delta',
        data: { t: 'Half an answer about session one.' },
      } satisfies ExplainEvent;
      await held;
      yield { event: 'done', data: { contentKey: 'k2', grounded: true } } satisfies ExplainEvent;
    };
    const first = await open();

    highlightAndExplain();
    await tick();
    harness.detectChanges();
    expect(text()).toContain('Half an answer about session one.');

    // A parameter-only route change: Angular reuses the component, so nothing is destroyed and the
    // store survives with another session's generation still streaming into it.
    const second = await harness.navigateByUrl('/learn/s2', ReaderPageComponent);
    http.expectOne('/api/sessions/s2').flush({
      ...view,
      sessionId: 's2',
      topicSlug: 'microbiology',
      explanations: { ...view.explanations, k1: 'Cells are small.' },
    });
    await harness.fixture.whenStable();
    flushTopic('microbiology', 'Microbiology');
    await harness.fixture.whenStable();
    harness.detectChanges();

    expect(second).toBe(first);
    expect(text()).toContain('Cells are small.');
    // The half-answer belongs to a session the learner has left; under this session's breadcrumb it
    // is simply another topic's text.
    expect(text()).not.toContain('Half an answer about session one.');
    // And the new session is not left claiming to be generating something.
    expect(text()).not.toContain('Generating…');

    // The abandoned generation unwinds last. It must not re-fetch s1 — afterEach's http.verify()
    // is what proves that.
    release();
    await tick();
  });

  it.each([
    ['evolution-by-natural-selection', 'Evolution by Natural Selection', 'Evolution By Natural'],
    ['supply-and-demand', 'Supply and Demand', 'Supply And Demand'],
  ])(
    'renders %s as the catalogue titles it, not a word-by-word capitalisation',
    async (slug, title, naive) => {
      // The only two of the 29 published slugs in `backend/catalog/.../topics.json` where a plain
      // per-word capitalisation disagrees with the curated title — established by running both
      // transforms over the whole file, not by eye. Both appear in the root crumb and the root rail
      // row. Kept as two literal cases rather than a loop over the real file: `topics.json` lives
      // outside `src`, so importing it here would pull a backend resource across the build's own
      // include scope to assert something a fixed pair already pins.
      // The catalogue answers 404, so this exercises the FALLBACK. That is what these two cases
      // were always about: the derivation has to agree with the curated title, because a session
      // on an unpublished topic still loads while `GET /api/catalog/topics/{slug}` does not answer.
      await open({ ...view, topicSlug: slug }, null);

      expect(text()).toContain(title);
      expect(text()).not.toContain(naive);
    },
  );

  it('prefers the curated topic title over the one derived from the slug', async () => {
    // The reader rebuilt the title from the slug and never asked the catalogue for it, while
    // `GET /api/catalog/topics/{slug}` held the curated title and had no client at all. The
    // derivation cannot produce this title: it capitalises each word and knows no punctuation.
    await open({ ...view, topicSlug: 'dna-and-rna' }, 'DNA and RNA');

    expect(text()).toContain('DNA and RNA');
    expect(text()).not.toContain('Dna and Rna');
  });

  it('shows a dismissible banner with a retry for a failed generation', async () => {
    script = async function* (): AsyncGenerator<ExplainEvent> {
      // Prose really does reach the screen before this fails — the grounding validator rejecting a
      // finished body is the shape that does this — so the withdrawal notice is accurate here.
      yield { event: 'delta', data: { t: 'Because ' } } satisfies ExplainEvent;
      throw new ExplainStreamError('GENERATION_FAILED', 'could not generate', null, true);
    };
    const component = await open();

    await component.store.explain({ text: 'pillars', start: 4, end: 11 }, 'EXPLAIN');
    harness.detectChanges();

    expect(text()).toContain('could not generate');
    expect(text()).toContain('was discarded');
    expect(text()).not.toContain('Because ');

    const banner = harness.routeNativeElement?.querySelector('.banner') as HTMLElement;
    expect(banner.querySelector('.banner__retry-button')).toBeTruthy();

    banner.querySelector<HTMLButtonElement>('.banner__dismiss')?.click();
    harness.detectChanges();
    expect(harness.routeNativeElement?.querySelector('.banner')).toBeNull();
  });

  it('does not tell the learner prose was withdrawn when none was ever shown', async () => {
    script = async function* (): AsyncGenerator<ExplainEvent> {
      // meta, then error: the ordinary shape of a model call that fails, and the one that carries
      // `partiallyStreamed: true` with an empty card behind it.
      yield { event: 'meta', data: { contentKey: 'k2', cached: false } } satisfies ExplainEvent;
      throw new ExplainStreamError('GENERATION_FAILED', 'could not generate', null, true);
    };
    const component = await open();

    await component.store.explain({ text: 'pillars', start: 4, end: 11 }, 'EXPLAIN');
    harness.detectChanges();

    expect(text()).toContain('could not generate');
    expect(text()).not.toContain('was discarded');
  });

  it('renders a 404 as a dead end with a way back, not as a retry', async () => {
    await harness.navigateByUrl('/learn/missing', ReaderPageComponent);
    http
      .expectOne('/api/sessions/missing')
      .flush({ code: 'NOT_FOUND', message: 'no such session' }, { status: 404, statusText: '' });
    await harness.fixture.whenStable();
    harness.detectChanges();

    expect(text()).toContain('could not be opened');
    // No Retry: the same request will 404 again. A way back to the catalogue is the only useful
    // action, and the message must not resolve "gone" versus "not yours" — Task 1.12 made those
    // indistinguishable on purpose.
    expect(harness.routeNativeElement?.querySelector('.banner__retry-button')).toBeNull();
    expect(harness.routeNativeElement?.querySelector('a[href="/"]')).toBeTruthy();
    expect(text()).not.toContain('The pillars of modern physics.');
  });

  it('the reader shows the panel on SIGN_IN_REQUIRED', async () => {
    script = async function* (): AsyncGenerator<ExplainEvent> {
      throw new ExplainStreamError('SIGN_IN_REQUIRED', 'sign in to keep going', null, false);
    };
    const component = await open();

    await component.store.explain({ text: 'pillars', start: 4, end: 11 }, 'EXPLAIN');
    harness.detectChanges();

    expect(harness.routeNativeElement?.querySelector('app-sign-in-panel')).toBeTruthy();
    // The panel replaces the focus card outright — it is not a banner layered over it.
    expect(harness.routeNativeElement?.querySelector('.focus')).toBeNull();
  });

  it('the reader keeps the trail while the panel is open', async () => {
    script = async function* (): AsyncGenerator<ExplainEvent> {
      throw new ExplainStreamError('SIGN_IN_REQUIRED', 'sign in to keep going', null, false);
    };
    const component = await open();

    await component.store.explain({ text: 'pillars', start: 4, end: 11 }, 'EXPLAIN');
    harness.detectChanges();

    expect(harness.routeNativeElement?.querySelector('app-sign-in-panel')).toBeTruthy();
    // Losing the learner's trail at the moment they are asked to sign in is the one outcome this
    // task must not ship — both the rail and the breadcrumb stay rendered alongside the panel.
    expect(harness.routeNativeElement?.querySelectorAll('.trail__item').length).toBe(2);
    expect(harness.routeNativeElement?.querySelector('.crumbs')).toBeTruthy();
  });

  it('TRIAL_EXHAUSTED opens the subscribe panel', async () => {
    script = async function* (): AsyncGenerator<ExplainEvent> {
      throw new ExplainStreamError('TRIAL_EXHAUSTED', 'your trial ran out', null, false);
    };
    const component = await open();

    await component.store.explain({ text: 'pillars', start: 4, end: 11 }, 'EXPLAIN');
    harness.detectChanges();

    expect(harness.routeNativeElement?.querySelector('app-wall-panel')).toBeTruthy();
  });

  it('SUBSCRIPTION_REQUIRED opens the subscribe panel', async () => {
    script = async function* (): AsyncGenerator<ExplainEvent> {
      throw new ExplainStreamError(
        'SUBSCRIPTION_REQUIRED',
        'a subscription is required',
        null,
        false,
      );
    };
    const component = await open();

    await component.store.explain({ text: 'pillars', start: 4, end: 11 }, 'EXPLAIN');
    harness.detectChanges();

    expect(harness.routeNativeElement?.querySelector('app-wall-panel')).toBeTruthy();
  });

  it('QUOTA_EXCEEDED shows the wait message and not the subscribe panel', async () => {
    script = async function* (): AsyncGenerator<ExplainEvent> {
      throw new ExplainStreamError('QUOTA_EXCEEDED', 'the daily quota is spent', 3600, false);
    };
    const component = await open();

    await component.store.explain({ text: 'pillars', start: 4, end: 11 }, 'EXPLAIN');
    harness.detectChanges();

    // The one outcome this whole task exists to rule out: a QUOTA_EXCEEDED learner — whose pool
    // does roll over — must keep reading the wait message, and never the subscribe wall meant for
    // the two codes whose pool does not.
    expect(text()).toContain('Try again in 1 hour');
    expect(harness.routeNativeElement?.querySelector('app-wall-panel')).toBeNull();
  });

  it('the trail stays visible behind every panel', async () => {
    const codes = ['SIGN_IN_REQUIRED', 'TRIAL_EXHAUSTED', 'SUBSCRIPTION_REQUIRED'];
    for (const [index, code] of codes.entries()) {
      // A distinct session id for each code, on the one harness this test is allowed: it is what
      // makes each iteration a genuine parameter-only route change, so the reader's own reuse of
      // this component instance — proven separately below — is exercised by this loop rather than
      // sidestepped by it.
      script = async function* (): AsyncGenerator<ExplainEvent> {
        throw new ExplainStreamError(code, 'refused', null, false);
      };
      const component = await open({ ...view, sessionId: `s${index + 1}` });

      await component.store.explain({ text: 'pillars', start: 4, end: 11 }, 'EXPLAIN');
      harness.detectChanges();

      expect(harness.routeNativeElement?.querySelectorAll('.trail__item').length).toBe(2);
    }
  });

  it('a subscribe code raises no banner', async () => {
    const codes = ['TRIAL_EXHAUSTED', 'SUBSCRIPTION_REQUIRED'];
    for (const [index, code] of codes.entries()) {
      script = async function* (): AsyncGenerator<ExplainEvent> {
        throw new ExplainStreamError(code, 'refused', null, false);
      };
      const component = await open({ ...view, sessionId: `s${index + 1}` });

      await component.store.explain({ text: 'pillars', start: 4, end: 11 }, 'EXPLAIN');
      harness.detectChanges();

      // A code that opens a panel must not also raise a banner — the panel already says what
      // happened, and a banner on top of it would say it twice.
      expect(harness.routeNativeElement?.querySelector('.banner')).toBeNull();
    }
  });

  it('SIGN_IN_REQUIRED still opens the sign-in panel and not the subscribe panel', async () => {
    // A regression guard on the existing branch: adding the two subscribe codes to
    // `subscribeRequired` must not pull SIGN_IN_REQUIRED along with them.
    script = async function* (): AsyncGenerator<ExplainEvent> {
      throw new ExplainStreamError('SIGN_IN_REQUIRED', 'sign in to keep going', null, false);
    };
    const component = await open();

    await component.store.explain({ text: 'pillars', start: 4, end: 11 }, 'EXPLAIN');
    harness.detectChanges();

    expect(harness.routeNativeElement?.querySelector('app-sign-in-panel')).toBeTruthy();
    expect(harness.routeNativeElement?.querySelector('app-wall-panel')).toBeNull();
  });
});
