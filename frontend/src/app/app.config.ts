import {
  ApplicationConfig,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideRouter, withViewTransitions } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';

import { routes } from './app.routes';
import { RouteMetaDescriptionService } from './core/route-meta-description.service';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    // Animation N. `skipInitialTransition: true` keeps the application's first paint from
    // running as a transition of its own — that first paint is not a change of route. A browser
    // with no support for `document.startViewTransition` needs no fallback code of its own: the
    // router's own documentation states it "will not attempt to start a view transition and
    // continue processing the navigation as usual"
    // (https://angular.dev/api/router/withViewTransitions).
    provideRouter(routes, withViewTransitions({ skipInitialTransition: true })),
    provideHttpClient(),
    // `RouteMetaDescriptionService` sets the description tag from its own constructor. Nothing
    // else injects it, so `provideAppInitializer` is what makes Angular construct it at all.
    provideAppInitializer(() => {
      inject(RouteMetaDescriptionService);
    }),
  ],
};
