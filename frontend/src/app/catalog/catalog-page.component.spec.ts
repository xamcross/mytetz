import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { CatalogPageComponent } from './catalog-page.component';
import { TopicSummary } from '../core/models';

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

  // Finding F8 of the design review. The introduction used to hold nine sentences and sit
  // between the header and the filter row, so a learner had to read about 100 words before the
  // search field. It now holds two sentences there, and the rest moves below the tile grid — see
  // the next test.
  it('shows a short, two-sentence introduction between the header and the filter row', async () => {
    const fixture = TestBed.createComponent(CatalogPageComponent);
    fixture.detectChanges();
    http.expectOne('/api/catalog/topics').flush([quantumPhysics]);
    await fixture.whenStable();
    fixture.detectChanges();

    const intro = fixture.nativeElement.querySelector('.catalog__intro') as HTMLElement | null;
    expect(intro, 'the introduction paragraph exists').toBeTruthy();
    expect(intro!.previousElementSibling, 'the paragraph sits right after the header').toBe(
      fixture.nativeElement.querySelector('.catalog__header'),
    );
    expect(intro!.nextElementSibling, 'the paragraph sits right before the filter row').toBe(
      fixture.nativeElement.querySelector('.catalog__filter'),
    );

    const text = (intro!.textContent ?? '').trim();
    const sentenceCount = (text.match(/[.!?]+(\s|$)/g) ?? []).length;
    expect(sentenceCount, 'the introduction keeps exactly two sentences').toBe(2);
    const wordCount = text.split(/\s+/).length;
    expect(wordCount, 'a two-sentence introduction stays short').toBeLessThanOrEqual(30);
  });

  it('keeps the long catalogue text on the page, below the tile grid', async () => {
    const fixture = TestBed.createComponent(CatalogPageComponent);
    fixture.detectChanges();
    http.expectOne('/api/catalog/topics').flush([quantumPhysics]);
    await fixture.whenStable();
    fixture.detectChanges();

    const topics = fixture.nativeElement.querySelector('.topics') as HTMLElement | null;
    const more = fixture.nativeElement.querySelector('.catalog__more') as HTMLElement | null;
    expect(more, 'the rest of the introduction stays in the DOM, for a crawler').toBeTruthy();
    expect(topics, 'the tile grid renders').toBeTruthy();
    expect(
      topics!.compareDocumentPosition(more!) & Node.DOCUMENT_POSITION_FOLLOWING,
      'the long text sits after the tile grid',
    ).toBeTruthy();

    const wordCount = (more!.textContent ?? '').trim().split(/\s+/).length;
    expect(
      wordCount,
      'the moved text still carries the bulk of the original introduction',
    ).toBeGreaterThanOrEqual(60);
  });

  // Issue #145. The page at `/` is the "dashboard" in every text a learner reads, and never the
  // "catalogue".
  it('names the page the dashboard in the long text, not the catalogue', async () => {
    const fixture = TestBed.createComponent(CatalogPageComponent);
    fixture.detectChanges();
    http.expectOne('/api/catalog/topics').flush([quantumPhysics]);
    await fixture.whenStable();
    fixture.detectChanges();

    const more = fixture.nativeElement.querySelector('.catalog__more') as HTMLElement;
    expect(more.textContent).toContain('The dashboard holds twelve subject areas');
    expect(fixture.nativeElement.textContent as string).not.toContain('catalogue');
  });

  it('names the page the dashboard when no topic matches at all', async () => {
    const fixture = TestBed.createComponent(CatalogPageComponent);
    fixture.detectChanges();
    http.expectOne('/api/catalog/topics').flush([]);
    await fixture.whenStable();
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('No topics yet.');
    expect(text).toContain('The dashboard is empty. Please come back later.');
    expect(text).not.toContain('catalogue');
  });

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

    // The old copy read `No topics match "{{ query() }}"`. The new copy states how large the
    // dashboard is, so the check moves to that wording rather than the old one.
    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Nothing under that name yet.');
    expect(text).toContain('The dashboard is');
    expect(text).not.toContain('catalogue');
  });

  // Finding F18 of the design review. Firefox draws no native clear control for `type="search"`,
  // and the old "Clear the filters" pill showed only once the result was already empty. A
  // learner with one match had no reset at all.
  it('shows Clear the filters next to the pill row once a query or a category is set', async () => {
    const fixture = TestBed.createComponent(CatalogPageComponent);
    fixture.detectChanges();
    http.expectOne('/api/catalog/topics').flush([quantumPhysics, microbiology]);
    await fixture.whenStable();
    fixture.detectChanges();

    const clearButton = (): HTMLButtonElement | null =>
      fixture.nativeElement.querySelector('.catalog__row .catalog__clear');

    expect(clearButton(), 'no reset shows while neither filter is set').toBeNull();

    const input = fixture.nativeElement.querySelector('#topic-filter') as HTMLInputElement;
    input.value = 'micro';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(clearButton(), 'a query alone shows the reset').not.toBeNull();

    input.value = '';
    input.dispatchEvent(new Event('input'));
    fixture.nativeElement.querySelector('button[data-category="Biology"]').click();
    fixture.detectChanges();
    expect(clearButton(), 'a category alone shows the reset').not.toBeNull();

    clearButton()!.click();
    fixture.detectChanges();
    expect(clearButton(), 'clearing both drops the reset again').toBeNull();
    expect(fixture.nativeElement.textContent as string).toContain('Microbiology');
    expect(fixture.nativeElement.textContent as string).toContain('Quantum Physics');
  });

  it('renders each topic tile as a plain link to its topic page, not a button', async () => {
    const fixture = TestBed.createComponent(CatalogPageComponent);
    fixture.detectChanges();
    http.expectOne('/api/catalog/topics').flush([quantumPhysics]);
    await fixture.whenStable();
    fixture.detectChanges();

    const link: HTMLAnchorElement = fixture.nativeElement.querySelector('a.topic__tile');
    expect(link, 'the tile is still a <button>, not an <a>').not.toBeNull();
    expect(link.getAttribute('href')).toBe('/topics/quantum-physics');
    expect(link.querySelector('h2')).not.toBeNull();
  });

  it('offers one pill per distinct category, with All first', async () => {
    // The categories are derived from the topics already on screen. The API returns `category`
    // with every topic, so this costs no request and no backend change.
    const fixture = TestBed.createComponent(CatalogPageComponent);
    fixture.detectChanges();
    http.expectOne('/api/catalog/topics').flush([quantumPhysics, microbiology]);
    await fixture.whenStable();
    fixture.detectChanges();

    const labels = Array.from(fixture.nativeElement.querySelectorAll('button[data-category]')).map(
      (b) => (b as HTMLElement).textContent?.trim(),
    );

    expect(labels).toEqual(['All', 'Biology', 'Physics']);
  });

  it('shows only the topics of the selected category', async () => {
    const fixture = TestBed.createComponent(CatalogPageComponent);
    fixture.detectChanges();
    http.expectOne('/api/catalog/topics').flush([quantumPhysics, microbiology]);
    await fixture.whenStable();
    fixture.detectChanges();

    fixture.nativeElement.querySelector('button[data-category="Biology"]').click();
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Microbiology');
    expect(text).not.toContain('Quantum Physics');
  });

  it('combines the category pill with the text query', async () => {
    const fixture = TestBed.createComponent(CatalogPageComponent);
    fixture.detectChanges();
    http.expectOne('/api/catalog/topics').flush([quantumPhysics, microbiology]);
    await fixture.whenStable();
    fixture.detectChanges();

    fixture.nativeElement.querySelector('button[data-category="Biology"]').click();
    fixture.detectChanges();

    const input = fixture.nativeElement.querySelector('#topic-filter') as HTMLInputElement;
    input.value = 'quantum';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    // Quantum Physics matches the query but not the category. Microbiology matches the category
    // but not the query. An AND leaves nothing. An OR would leave both, so this distinguishes the
    // two rather than merely showing that some filter runs.
    expect(fixture.nativeElement.querySelectorAll('.topic').length).toBe(0);
  });
});
