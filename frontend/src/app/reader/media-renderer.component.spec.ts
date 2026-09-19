import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ImageMedia, Media } from '../core/models';
import { MediaRendererComponent } from './media-renderer.component';

const IMAGE: ImageMedia = {
  imageUrl: 'https://upload.wikimedia.org/example.jpg',
  title: 'Example.jpg',
  license: 'CC BY-SA 4.0',
  attributionHtml: 'By Example Author',
  commonsPageUrl: 'https://commons.wikimedia.org/wiki/File:Example.jpg',
};

describe('MediaRendererComponent', () => {
  let fixture: ComponentFixture<MediaRendererComponent>;

  const media = (overrides: Partial<Media> = {}): Media => ({
    diagram: {
      kind: 'SVG',
      source: '<svg viewBox="0 0 10 10"><title>A circle</title><circle cx="5" cy="5" r="4"/></svg>',
    },
    image: null,
    ...overrides,
  });

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [MediaRendererComponent] });
    fixture = TestBed.createComponent(MediaRendererComponent);
  });

  const diagramImg = (): HTMLImageElement =>
    fixture.nativeElement.querySelector('img.media__diagram');
  const photoImg = (): HTMLImageElement | null =>
    fixture.nativeElement.querySelector('img.media__image-photo');
  const attribution = (): HTMLElement | null =>
    fixture.nativeElement.querySelector('[data-testid="attribution"]');
  const zoomButton = (): HTMLButtonElement =>
    fixture.nativeElement.querySelector('button.media__zoom');

  it('renders the diagram as an image with an accessible alt text taken from the SVG title', () => {
    fixture.componentRef.setInput('media', media());
    fixture.detectChanges();
    expect(diagramImg().getAttribute('src')).toContain('data:image/svg+xml');
    expect(diagramImg().getAttribute('alt')).toBe('A circle');
  });

  it('falls back to a fixed alt text when the SVG carries no title', () => {
    fixture.componentRef.setInput(
      'media',
      media({ diagram: { kind: 'SVG', source: '<svg><circle cx="1" cy="1" r="1"/></svg>' } }),
    );
    fixture.detectChanges();
    expect(diagramImg().getAttribute('alt')).toBeTruthy();
  });

  it('falls back to the fixed alt text, and never to the parser’s own error text, when the SVG is not well formed', () => {
    // A missing closing tag, the same malformed shape SvgSanitizerTest pins on the server side.
    // DOMParser never throws for this; it returns a document whose root carries a <parsererror>
    // element instead, in both jsdom (this spec) and a real Chromium (checked by hand against the
    // real DOM, not assumed): jsdom names it under the Mozilla parsererror namespace, and Chromium
    // under the XHTML namespace, but `querySelector('parsererror')` matches either one because a
    // plain CSS selector ignores namespaces.
    fixture.componentRef.setInput(
      'media',
      media({
        diagram: { kind: 'SVG', source: '<svg><circle cx="1" cy="1" r="1"></svg>' },
      }),
    );
    fixture.detectChanges();
    const alt = diagramImg().getAttribute('alt') ?? '';
    expect(alt).toBe('Diagram');
    expect(alt.toLowerCase()).not.toContain('error');
  });

  it('binds the diagram through an ordinary [src] with no DomSanitizer bypass', () => {
    // Decision 7's own boundary: a data: URL must pass Angular's normal [src] binding, unbypassed.
    // A bypassed URL would still render; this proves the value reaches the DOM through the ordinary
    // property binding, which is the one guarantee this test can make from outside the sanitiser.
    fixture.componentRef.setInput('media', media());
    fixture.detectChanges();
    const src = diagramImg().getAttribute('src') ?? '';
    expect(src.startsWith('data:image/svg+xml')).toBe(true);
    expect(
      decodeURIComponent(src.replace(/^data:image\/svg\+xml(;charset=utf-8)?,/, '')),
    ).toContain('<circle');
  });

  it('shows no image block when there is no image', () => {
    fixture.componentRef.setInput('media', media());
    fixture.detectChanges();
    expect(photoImg()).toBeNull();
    expect(attribution()).toBeNull();
  });

  it('renders the image, its attribution, and its license link together, whenever an image is present', () => {
    // The issue's own licence obligation: the UI always shows the attribution next to the image.
    // This asserts both halves in one test, on the model the issue itself asks for.
    fixture.componentRef.setInput('media', media({ image: IMAGE }));
    fixture.detectChanges();

    const photo = photoImg();
    expect(photo).not.toBeNull();
    expect(photo!.getAttribute('src')).toBe(IMAGE.imageUrl);
    expect(photo!.getAttribute('alt')).toBe(IMAGE.title);
    expect(photo!.getAttribute('loading')).toBe('lazy');
    expect(photo!.getAttribute('referrerpolicy')).toBe('no-referrer');

    const box = attribution();
    expect(box).not.toBeNull();
    expect(box!.textContent).toContain(IMAGE.license);
    expect(box!.textContent).toContain('By Example Author');

    const link: HTMLAnchorElement | null = box!.querySelector('a');
    expect(link).not.toBeNull();
    expect(link!.getAttribute('href')).toBe(IMAGE.commonsPageUrl);
    expect(link!.getAttribute('target')).toBe('_blank');
    expect(link!.getAttribute('rel')).toBe('noopener noreferrer');
  });

  it('sanitises the attribution on the real DOM: the link survives, no script runs, no handler attribute survives', () => {
    // No spy stands in for the sanitiser here. This binds a hostile value through the component's
    // real [innerHTML] and reads the real DOM Angular produced.
    fixture.componentRef.setInput(
      'media',
      media({
        image: {
          ...IMAGE,
          attributionHtml:
            'By <a href="https://example.org">A</a>' +
            '<img src="x" onerror="window.__pwned = true">' +
            '<script>window.__pwned = true;</script>',
        },
      }),
    );
    fixture.detectChanges();

    const box = attribution()!;
    // Scoped to the attribution span, not the whole caption: the caption also holds this
    // component's own, separate license link to `commonsPageUrl`, which is not the value under
    // test here.
    const sanitised: HTMLElement = box.querySelector('.media__attribution')!;
    const link: HTMLAnchorElement | null = sanitised.querySelector('a');
    expect(link).not.toBeNull();
    expect(link!.getAttribute('href')).toBe('https://example.org');
    expect(link!.textContent).toBe('A');

    expect(sanitised.querySelector('script')).toBeNull();
    expect(sanitised.querySelectorAll('*').length).toBeGreaterThan(0);
    for (const el of Array.from(sanitised.querySelectorAll('*'))) {
      for (const name of el.getAttributeNames()) {
        expect(name.toLowerCase().startsWith('on')).toBe(false);
      }
    }
  });

  it('offers a zoom control that toggles the diagram between fit and larger, and back', () => {
    fixture.componentRef.setInput('media', media());
    fixture.detectChanges();

    const button = zoomButton();
    const frame = (): HTMLElement => fixture.nativeElement.querySelector('.media__diagram-frame');

    expect(button.getAttribute('aria-pressed')).toBe('false');
    expect(frame().classList.contains('media__diagram-frame--zoomed')).toBe(false);

    button.click();
    fixture.detectChanges();
    expect(button.getAttribute('aria-pressed')).toBe('true');
    expect(frame().classList.contains('media__diagram-frame--zoomed')).toBe(true);

    button.click();
    fixture.detectChanges();
    expect(button.getAttribute('aria-pressed')).toBe('false');
    expect(frame().classList.contains('media__diagram-frame--zoomed')).toBe(false);
  });
});
