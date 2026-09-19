import { readFileSync } from 'node:fs';

/**
 * Animation N. `withViewTransitions` has no observable effect jsdom can assert — a real browser
 * runs `document.startViewTransition`, and jsdom has no such API. This test instead reads the
 * source, the same method `styles.spec.ts` and `palette.spec.ts` use for a rule a runtime
 * assertion cannot reach.
 *
 * `skipInitialTransition: true` matters here specifically: without it, the very first paint of
 * the application would itself run as a transition, which is not a change of route at all.
 */
describe('provideRouter (animation N, the change between routes)', () => {
  const source = readFileSync('src/app/app.config.ts', 'utf8');

  it('imports withViewTransitions from @angular/router', () => {
    expect(source).toMatch(
      /import\s*\{[^}]*withViewTransitions[^}]*\}\s*from\s*'@angular\/router'/,
    );
  });

  it('passes withViewTransitions to provideRouter, with skipInitialTransition set', () => {
    expect(source).toMatch(
      /provideRouter\(\s*routes,\s*withViewTransitions\(\{\s*skipInitialTransition:\s*true\s*\}\)\s*\)/,
    );
  });
});
