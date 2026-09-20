import {
  ApplicationConfig,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { Router, isActive, provideRouter, withViewTransitions } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';

import { routes } from './app.routes';
import { RouteMetaDescriptionService } from './core/route-meta-description.service';
import { NavigationHistoryService } from './core/navigation-history.service';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    // Animation N. `skipInitialTransition: true` keeps the application's first paint from
    // running as a transition of its own — that first paint is not a change of route. A browser
    // with no support for `document.startViewTransition` needs no fallback code of its own: the
    // router's own documentation states it "will not attempt to start a view transition and
    // continue processing the navigation as usual"
    // (https://angular.dev/api/router/withViewTransitions).
    //
    // `onViewTransitionCreated` corrects a second defect the design review's second round found:
    // a navigation that changes only the query string or the fragment — for example
    // `AccountPageComponent.removeQueryString`'s own `router.navigate([], { queryParams: {},
    // replaceUrl: true })` after the Freemius return — still started a real transition. That
    // faded the whole page for 320ms and took no input for a navigation that never changed the
    // route. `isActive`, `Router.currentNavigation` and `ViewTransition.skipTransition` are
    // named exactly as the official guide shows
    // (https://angular.dev/guide/routing/route-transition-animations, "Advanced transition
    // control with onViewTransitionCreated"); the `IsActiveMatchOptions` field names come from
    // https://angular.dev/api/router/ViewTransitionsFeatureOptions and the linked API pages.
    provideRouter(
      routes,
      withViewTransitions({
        skipInitialTransition: true,
        onViewTransitionCreated: ({ transition }) => {
          const router = inject(Router);
          const targetUrl = router.currentNavigation()!.finalUrl!;
          const isTargetRouteCurrent = isActive(targetUrl, router, {
            paths: 'exact',
            matrixParams: 'exact',
            fragment: 'ignored',
            queryParams: 'ignored',
          });
          if (isTargetRouteCurrent()) {
            transition.skipTransition();
          }
        },
      }),
    ),
    provideHttpClient(),
    // `RouteMetaDescriptionService` sets the description tag from its own constructor. Nothing
    // else injects it, so `provideAppInitializer` is what makes Angular construct it at all.
    provideAppInitializer(() => {
      inject(RouteMetaDescriptionService);
      // `NavigationHistoryService` reads every navigation from its own constructor, for
      // `SubscribePageComponent`'s "Stay on the free plan" control (issue #137). It needs the
      // same early construction, for the same reason.
      inject(NavigationHistoryService);
    }),
  ],
};
