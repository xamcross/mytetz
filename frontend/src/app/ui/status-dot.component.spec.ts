import { readFileSync } from 'node:fs';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { BackendState, StatusDotComponent } from './status-dot.component';

describe('StatusDotComponent', () => {
  let fixture: ComponentFixture<StatusDotComponent>;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [StatusDotComponent] });
    fixture = TestBed.createComponent(StatusDotComponent);
  });

  const dot = (state: BackendState): HTMLElement => {
    fixture.componentRef.setInput('state', state);
    fixture.detectChanges();
    return fixture.nativeElement.querySelector('.dot');
  };

  it('states the result in words as well as in colour', () => {
    // The colour alone is not the signal. A reader who cannot tell teal from red still needs the
    // answer, so the words carry it and the colour repeats it.
    expect(dot('ok').getAttribute('aria-label')).toBe('Backend ok');
    expect(dot('degraded').getAttribute('aria-label')).toBe('Backend degraded');
    expect(dot('unreachable').getAttribute('aria-label')).toBe('Backend unreachable');
    expect(dot('checking').getAttribute('aria-label')).toBe('Backend: checking');
  });

  it('gives each result its own modifier class', () => {
    expect(dot('ok').classList.contains('dot--ok')).toBe(true);
    expect(dot('degraded').classList.contains('dot--degraded')).toBe(true);
    expect(dot('unreachable').classList.contains('dot--unreachable')).toBe(true);
    expect(dot('checking').classList.contains('dot--checking')).toBe(true);
  });

  it('repeats the label in the title, so a pointer reaches it too', () => {
    expect(dot('degraded').getAttribute('title')).toBe('Backend degraded');
  });
});

/**
 * Issue #103, section 3.2. The colour of a status dot is not enough: the amber "degraded" fill
 * measures 1.44:1 on --mt-surface, and the faint "checking" fill measures 2.75:1, so a shape is
 * the second signal a colour-blind or low-vision learner can still tell apart.
 *
 * jsdom has no layout engine and does not resolve a gradient or a clip-path, so a computed style
 * cannot prove a shape (see `e2e/layout.spec.ts`'s own doc comment on this point). Each rule is
 * therefore read from the real, shipped file as text, the same method `styles.spec.ts` uses.
 */
describe('the shape of each backend state', () => {
  const source = readFileSync('src/app/ui/status-dot.component.ts', 'utf8');

  /** The body of the first CSS rule for `selector`. */
  function rule(selector: string): string {
    const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const match = source.match(new RegExp(`${escaped}\\s*\\{([^}]*)\\}`));
    if (!match) throw new Error(`status-dot.component.ts must declare a rule for ${selector}`);
    return match[1];
  }

  it('keeps every dot at a 14px footprint: a 10px box plus a 2px border on each side', () => {
    const base = rule('.dot');
    expect(base).toMatch(/width:\s*10px/);
    expect(base).toMatch(/height:\s*10px/);
    expect(base).toMatch(/border:\s*2px solid/);
  });

  it('draws ok as a filled disc, with no clip-path and no gradient of its own', () => {
    const ok = rule('.dot--ok');
    expect(ok).not.toMatch(/clip-path/);
    expect(ok).not.toMatch(/gradient/);
  });

  it('draws checking as a ring, hollow at the centre', () => {
    expect(rule('.dot--checking')).toMatch(/radial-gradient/);
  });

  it('draws degraded as a triangle', () => {
    expect(rule('.dot--degraded')).toMatch(/clip-path:\s*polygon/);
  });

  it('draws unreachable as a cross, from two crossed bars', () => {
    expect(rule('.dot--unreachable::before')).toMatch(/rotate\(45deg\)/);
    expect(rule('.dot--unreachable::after')).toMatch(/rotate\(-45deg\)/);
  });

  it('gives every state its own shape: no two states share a rule', () => {
    const shapes = [
      rule('.dot--ok'),
      rule('.dot--checking'),
      rule('.dot--degraded'),
      rule('.dot--unreachable'),
    ];
    expect(new Set(shapes).size).toBe(shapes.length);
  });
});
