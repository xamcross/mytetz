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

  beforeEach(async () => {
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
          useValue: ((...args) => script(...args)) satisfies ExplainStreamFn,
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
  });

  afterEach(() => http.verify());

  async function open(): Promise<ReaderPageComponent> {
    const component = await harness.navigateByUrl('/learn/s1', ReaderPageComponent);
    http.expectOne('/api/sessions/s1').flush(view);
    await harness.fixture.whenStable();
    harness.detectChanges();
    return component;
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
    const range = document.createRange();
    range.setStart(bodyEl.firstChild as Text, 4);
    range.setEnd(bodyEl.firstChild as Text, 11);
    const selection = window.getSelection();
    selection?.removeAllRanges();
    selection?.addRange(range);
    bodyEl.dispatchEvent(new Event('mouseup'));
    harness.detectChanges();

    harness.routeNativeElement?.querySelector<HTMLButtonElement>('[data-verb="EXPLAIN"]')?.click();
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
    selection?.removeAllRanges();
  });

  it('shows a dismissible banner with a retry for a failed generation', async () => {
    script = async function* (): AsyncGenerator<ExplainEvent> {
      throw new ExplainStreamError('GENERATION_FAILED', 'could not generate', null, true);
    };
    const component = await open();

    await component.store.explain({ text: 'pillars', start: 4, end: 11 }, 'EXPLAIN');
    harness.detectChanges();

    expect(text()).toContain('could not generate');
    // The partial-answer case says so, rather than leaving the learner to wonder where the prose
    // that was on screen a moment ago went.
    expect(text()).toContain('was discarded');

    const banner = harness.routeNativeElement?.querySelector('.banner') as HTMLElement;
    expect(banner.querySelector('.banner__retry-button')).toBeTruthy();

    banner.querySelector<HTMLButtonElement>('.banner__dismiss')?.click();
    harness.detectChanges();
    expect(harness.routeNativeElement?.querySelector('.banner')).toBeNull();
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
});
