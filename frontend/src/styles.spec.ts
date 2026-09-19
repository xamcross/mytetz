import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

/**
 * The proof for the tokens and the rules of issue #102: the motion tokens, the shadow tokens, the
 * hover and press and disabled state of `.mt-pill`, the underline rule, and the one shared
 * `.mt-sr-only` helper.
 *
 * The method matches `src/app/ui/palette.spec.ts`: it reads the real, shipped `styles.css` and
 * checks the file's text for a rule, instead of copying a value into the test. A change that
 * drops a rule then fails here, and not only in a browser.
 */
const css = readFileSync('src/styles.css', 'utf8');

/** The body of the `:root` block, where every token lives. */
function rootBlock(source: string): string {
  const root = source.match(/:root\s*\{([^}]*)\}/);
  if (!root) throw new Error('the stylesheet must declare a :root block');
  return root[1];
}

/** The value of one custom property inside a token block, for example `--mt-dur-press`. */
function readToken(block: string, name: string): string {
  const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const declaration = block.match(new RegExp(`${escaped}\\s*:\\s*([^;]+);`));
  if (!declaration) throw new Error(`:root must declare ${name}`);
  return declaration[1].trim();
}

/** True when the file declares at least one rule whose selector list holds `selector`. */
function hasSelector(source: string, selector: string): boolean {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return new RegExp(`(^|[,{}]|\\s)${escaped}(\\s|,|\\{)`, 'm').test(source);
}

/**
 * True when a class answers `:hover`, directly or through a `:not(...)` guard that excludes one
 * of its own modifier classes. `.picker__verb` and `.trail__item` take this second form, because
 * their coral or teal modifier needs a different hover rule and not a plain background change.
 */
function hasHoverRule(source: string, className: string): boolean {
  const escaped = className.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return new RegExp(`${escaped}(:not\\([^)]*\\))?:hover`).test(source);
}

/** Every `.ts` file under a directory, walked without a third-party glob package. */
function listTsFiles(dir: string): string[] {
  const found: string[] = [];
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) {
      found.push(...listTsFiles(full));
    } else if (entry.endsWith('.ts')) {
      found.push(full);
    }
  }
  return found;
}

describe('the motion tokens of styles.css', () => {
  const root = rootBlock(css);

  const durations: ReadonlyArray<[string, string]> = [
    ['--mt-dur-press', '90ms'],
    ['--mt-dur-state', '160ms'],
    ['--mt-dur-panel', '240ms'],
    ['--mt-dur-route', '320ms'],
  ];
  for (const [name, value] of durations) {
    it(`sets ${name} to ${value}`, () => {
      expect(readToken(root, name)).toBe(value);
    });
  }

  const distances: ReadonlyArray<[string, string]> = [
    ['--mt-move-press', '2px'],
    ['--mt-move-near', '4px'],
    ['--mt-move-far', '8px'],
  ];
  for (const [name, value] of distances) {
    it(`sets ${name} to ${value}`, () => {
      expect(readToken(root, name)).toBe(value);
    });
  }

  it('declares the four easing tokens', () => {
    for (const name of ['--mt-ease-out', '--mt-ease-in', '--mt-ease-press', '--mt-ease-settle']) {
      expect(readToken(root, name)).toMatch(/^cubic-bezier\(/);
    }
  });
});

describe('the shadow tokens of styles.css', () => {
  const root = rootBlock(css);

  it('names the press shadow', () => {
    expect(readToken(root, '--mt-press')).toBe('0 2px 0 var(--mt-border)');
  });
  it('names the coral press shadow', () => {
    expect(readToken(root, '--mt-press-coral')).toBe('0 2px 0 var(--mt-coral-deep)');
  });
  it('names the teal press shadow', () => {
    expect(readToken(root, '--mt-press-teal')).toBe('0 2px 0 var(--mt-teal-deep)');
  });
  it('names the hover lift', () => {
    expect(readToken(root, '--mt-lift-hover')).toBe('0 5px 0 var(--mt-border)');
  });
});

describe('the reduced-motion token override', () => {
  it('replaces the blanket rule with a duration and a distance of zero', () => {
    const block = css.match(/@media \(prefers-reduced-motion: reduce\)\s*\{([\s\S]*)\}\s*$/);
    if (!block) throw new Error('styles.css must keep one prefers-reduced-motion block');
    const body = block[1];
    expect(body).toMatch(/--mt-dur-press:\s*1ms/);
    expect(body).toMatch(/--mt-dur-state:\s*1ms/);
    expect(body).toMatch(/--mt-dur-panel:\s*1ms/);
    expect(body).toMatch(/--mt-dur-route:\s*1ms/);
    expect(body).toMatch(/--mt-move-press:\s*0px/);
    expect(body).toMatch(/--mt-move-near:\s*0px/);
    expect(body).toMatch(/--mt-move-far:\s*0px/);
  });

  it('keeps an explicit rule for the skeleton, the caret and the band', () => {
    const block = css.match(/@media \(prefers-reduced-motion: reduce\)\s*\{([\s\S]*)\}\s*$/);
    if (!block) throw new Error('styles.css must keep one prefers-reduced-motion block');
    expect(hasSelector(block[1], '.mt-skeleton')).toBe(true);
    expect(hasSelector(block[1], '.focus__caret')).toBe(true);
    expect(hasSelector(block[1], '.focus__band')).toBe(true);
  });

  it('does not blank every animation and every transition on the page', () => {
    // The old rule set `animation-duration` and `transition-duration` on a wildcard selector.
    // That silenced an animation that carries meaning, which is the defect issue #102 fixes.
    // The reset block earlier in the file also selects `*, *::before, *::after`, so the check
    // reads only the reduced-motion block and not the whole file.
    const block = css.match(/@media \(prefers-reduced-motion: reduce\)\s*\{([\s\S]*)\}\s*$/);
    if (!block) throw new Error('styles.css must keep one prefers-reduced-motion block');
    expect(block[1]).not.toMatch(/animation-duration|transition-duration/);
  });
});

describe('.mt-pill answers hover, press and the disabled state', () => {
  const variants = ['.mt-pill', '.mt-pill--coral', '.mt-pill--teal', '.mt-pill--ghost'];

  for (const variant of variants) {
    it(`gives ${variant} a hover rule`, () => {
      expect(hasSelector(css, `${variant}:hover:not(:disabled)`)).toBe(true);
    });

    it(`gives ${variant} an active rule`, () => {
      expect(hasSelector(css, `${variant}:active:not(:disabled)`)).toBe(true);
    });
  }

  it('gives every pill a disabled rule, once, on the base class', () => {
    // A modifier class never repeats this: `.mt-pill:disabled` reaches every variant through the
    // cascade, because each variant keeps the `mt-pill` class on the element.
    expect(hasSelector(css, '.mt-pill:disabled')).toBe(true);
  });
});

describe('a link with the class .mt-pill', () => {
  it('clears the browser default underline', () => {
    const rule = css.match(/a\.mt-pill\s*\{([^}]*)\}/);
    if (!rule) throw new Error('styles.css must declare a rule for a.mt-pill');
    expect(rule[1]).toMatch(/text-decoration:\s*none/);
  });
});

describe('the screen-reader-only helper', () => {
  it('declares .mt-sr-only exactly once in styles.css', () => {
    const matches = css.match(/\.mt-sr-only\s*\{/g) ?? [];
    expect(matches.length).toBe(1);
  });

  it('holds no component copy of the old local classes', () => {
    const files = listTsFiles('src/app');
    const offenders = files.filter((f) =>
      readFileSync(f, 'utf8').includes('clip-path: inset(50%)'),
    );
    expect(offenders).toEqual([]);
  });

  it('replaced .visually-hidden and .catalog__label in every template', () => {
    const files = listTsFiles('src/app');
    const offenders = files.filter((f) => {
      const text = readFileSync(f, 'utf8');
      return /class="[^"]*\b(visually-hidden|catalog__label)\b/.test(text);
    });
    expect(offenders).toEqual([]);
  });
});

describe('a hover state for the controls that are not .mt-pill', () => {
  it('gives .picker__verb a hover rule', () => {
    const text = readFileSync('src/app/ui/verb-picker.component.ts', 'utf8');
    expect(hasHoverRule(text, '.picker__verb')).toBe(true);
  });

  it('gives .trail__item a hover rule', () => {
    const text = readFileSync('src/app/reader/trail-rail.component.ts', 'utf8');
    expect(hasHoverRule(text, '.trail__item')).toBe(true);
  });

  it('gives .crumb__button a hover rule', () => {
    const text = readFileSync('src/app/reader/breadcrumb.component.ts', 'utf8');
    expect(hasSelector(text, '.crumb__button:hover:not(:disabled)')).toBe(true);
  });
});
