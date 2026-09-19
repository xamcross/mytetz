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
    expect(source).toMatch(/provideRouter\(\s*routes,\s*withViewTransitions\(\{/);
    expect(source).toMatch(/skipInitialTransition:\s*true/);
  });

  /**
   * Defect 1 of the design review's second round. A navigation that changes only the query
   * string or the fragment — `AccountPageComponent.removeQueryString`'s own
   * `router.navigate([], { queryParams: {}, replaceUrl: true })`, for one — must not start a
   * real transition. The official guide names `isActive`, `Router.currentNavigation` and
   * `ViewTransition.skipTransition` for exactly this
   * (https://angular.dev/guide/routing/route-transition-animations).
   */
  describe('onViewTransitionCreated skips a query-string-only or fragment-only change', () => {
    it('imports isActive and Router from @angular/router', () => {
      expect(source).toMatch(/import\s*\{[^}]*\bisActive\b[^}]*\}\s*from\s*'@angular\/router'/);
      expect(source).toMatch(/import\s*\{[^}]*\bRouter\b[^}]*\}\s*from\s*'@angular\/router'/);
    });

    it('declares onViewTransitionCreated', () => {
      expect(source).toMatch(/onViewTransitionCreated:\s*\(\s*\{\s*transition\s*\}\s*\)\s*=>/);
    });

    it('reads the target URL from Router.currentNavigation().finalUrl', () => {
      expect(source).toMatch(/router\.currentNavigation\(\)!?\.finalUrl/);
    });

    it('calls isActive with paths and matrixParams exact, and fragment and queryParams ignored', () => {
      const block = source.match(/onViewTransitionCreated:[\s\S]*?\n\s{4}\}\),/)?.[0];
      if (!block) throw new Error('app.config.ts must declare the onViewTransitionCreated block');
      expect(block).toMatch(/isActive\(\s*targetUrl,\s*router,\s*\{/);
      expect(block).toMatch(/paths:\s*'exact'/);
      expect(block).toMatch(/matrixParams:\s*'exact'/);
      expect(block).toMatch(/fragment:\s*'ignored'/);
      expect(block).toMatch(/queryParams:\s*'ignored'/);
    });

    it('calls transition.skipTransition() when the target route is already current', () => {
      const block = source.match(/onViewTransitionCreated:[\s\S]*?\n\s{4}\}\),/)?.[0];
      if (!block) throw new Error('app.config.ts must declare the onViewTransitionCreated block');
      expect(block).toMatch(
        /if\s*\(\s*isTargetRouteCurrent\(\)\s*\)\s*\{\s*transition\.skipTransition\(\);/,
      );
    });
  });
});
