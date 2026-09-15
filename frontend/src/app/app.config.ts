import {
  ApplicationConfig,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';

import { routes } from './app.routes';
import { RouteMetaDescriptionService } from './core/route-meta-description.service';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    provideHttpClient(),
    // `RouteMetaDescriptionService` sets the description tag from its own constructor. Nothing
    // else injects it, so `provideAppInitializer` is what makes Angular construct it at all.
    provideAppInitializer(() => {
      inject(RouteMetaDescriptionService);
    }),
  ],
};
