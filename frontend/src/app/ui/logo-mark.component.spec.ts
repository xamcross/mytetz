import { ComponentFixture, TestBed } from '@angular/core/testing';
import { LogoMarkComponent } from './logo-mark.component';

/**
 * The mark that sits left of the wordmark, and that the browser draws as the tab icon.
 *
 * Two rules govern it. The wordmark beside it already names the product, so the mark must stay
 * out of the accessibility tree. And `styles.css` says only it and `palette.spec.ts` state a
 * colour, so every paint on the mark must name a token.
 */
describe('LogoMarkComponent', () => {
  let fixture: ComponentFixture<LogoMarkComponent>;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [LogoMarkComponent] });
    fixture = TestBed.createComponent(LogoMarkComponent);
    fixture.detectChanges();
  });

  it('keeps the mark out of the accessibility tree, because the wordmark carries the name', () => {
    const svg = fixture.nativeElement.querySelector('svg');

    expect(svg.getAttribute('aria-hidden')).toBe('true');
    // Internet Explorer's descendant is gone, but Chromium still puts an SVG in the tab order
    // when it carries a link. `focusable` costs one attribute and removes the whole question.
    expect(svg.getAttribute('focusable')).toBe('false');
  });

  it('states no colour of its own, so the palette stays in one file', () => {
    const svg = fixture.nativeElement.querySelector('svg');
    const paints = [...svg.querySelectorAll('*')].flatMap((el: Element) =>
      ['fill', 'stroke']
        .map((name) => el.getAttribute(name))
        .filter((v): v is string => v !== null),
    );

    expect(paints.length).toBeGreaterThan(0);
    for (const paint of paints) {
      // `none` is an absence of paint, not a colour.
      if (paint === 'none') continue;
      expect(paint).toMatch(/^var\(--mt-[a-z-]+\)$/);
    }
  });

  it('draws on the 32-unit grid the icon file also uses', () => {
    const svg = fixture.nativeElement.querySelector('svg');

    expect(svg.getAttribute('viewBox')).toBe('0 0 32 32');
  });
});
