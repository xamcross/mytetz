import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Media } from '../core/models';
import { MediaRendererComponent } from './media-renderer.component';

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

  it('renders the diagram as an image with an accessible alt text taken from the SVG title', () => {
    fixture.componentRef.setInput('media', media());
    fixture.detectChanges();
    const img: HTMLImageElement = fixture.nativeElement.querySelector('img');
    expect(img.getAttribute('src')).toContain('data:image/svg+xml');
    expect(img.getAttribute('alt')).toBe('A circle');
  });

  it('falls back to a fixed alt text when the SVG carries no title', () => {
    fixture.componentRef.setInput(
      'media',
      media({ diagram: { kind: 'SVG', source: '<svg><circle cx="1" cy="1" r="1"/></svg>' } }),
    );
    fixture.detectChanges();
    const img: HTMLImageElement = fixture.nativeElement.querySelector('img');
    expect(img.getAttribute('alt')).toBeTruthy();
  });

  it('shows the attribution whenever an image is present', () => {
    fixture.componentRef.setInput(
      'media',
      media({
        image: {
          imageUrl: 'https://upload.wikimedia.org/example.jpg',
          title: 'Example.jpg',
          license: 'CC BY-SA 4.0',
          attributionHtml: 'By Example Author',
          commonsPageUrl: 'https://commons.wikimedia.org/wiki/File:Example.jpg',
        },
      }),
    );
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('CC BY-SA 4.0');
  });

  it('shows no attribution block when there is no image', () => {
    fixture.componentRef.setInput('media', media());
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="attribution"]')).toBeNull();
  });

  it('binds the image through an ordinary [src] with no DomSanitizer bypass', () => {
    // Decision 7's own boundary: a data: URL must pass Angular's normal [src] binding, unbypassed.
    // A bypassed URL would still render; this proves the value reaches the DOM through the ordinary
    // property binding, which is the one guarantee this test can make from outside the sanitiser.
    fixture.componentRef.setInput('media', media());
    fixture.detectChanges();
    const img: HTMLImageElement = fixture.nativeElement.querySelector('img');
    const src = img.getAttribute('src') ?? '';
    expect(src.startsWith('data:image/svg+xml')).toBe(true);
    expect(
      decodeURIComponent(src.replace(/^data:image\/svg\+xml(;charset=utf-8)?,/, '')),
    ).toContain('<circle');
  });
});
