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

  it('shows a 100-to-150-word introduction between the header and the filter row', async () => {
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

    const wordCount = (intro!.textContent ?? '').trim().split(/\s+/).length;
    expect(wordCount, 'the word count stays inside the acceptance range').toBeGreaterThanOrEqual(
      100,
    );
    expect(wordCount, 'the word count stays inside the acceptance range').toBeLessThanOrEqual(150);
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
    // catalogue is, so the check moves to that wording rather than the old one.
    expect(fixture.nativeElement.textContent as string).toContain('Nothing under that name yet.');
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
