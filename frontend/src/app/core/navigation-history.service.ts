import { Injectable, inject } from '@angular/core';
import { NavigationEnd, NavigationStart, Router } from '@angular/router';

/**
 * The in-app page a learner was on right before the current one — never a page from outside the
 * app.
 *
 * `SubscribePageComponent`'s "Stay on the free plan" control needs this: it goes back to where the
 * learner came from, and it must not read a URL a query parameter names, which would let a page
 * send a learner anywhere it likes (an open redirect). This service instead reads the real
 * `Router`'s own navigation events, the same events `RouteMetaDescriptionService` reads for the
 * page description. Only a navigation this app's own `Router` runs ever moves [previous], so an
 * external referrer — a search result, an email link — can never reach it.
 *
 * `app.config.ts` constructs this once, eagerly, through `provideAppInitializer`, the same way it
 * constructs `RouteMetaDescriptionService` — a service that only `providedIn: 'root'` would never
 * run at all, because nothing else injects it.
 */
@Injectable({ providedIn: 'root' })
export class NavigationHistoryService {
  private readonly router = inject(Router);

  /** The page the router held right before the navigation now in flight. Starts at the router's
   * own initial url, `/`, the same as [currentUrl] — see [previous]'s own comment. Set from
   * `NavigationStart`, and not from `NavigationEnd`: the value must move to the outgoing page
   * before the incoming one replaces it. */
  private previousUrl: string = this.router.url;

  /** The page the router settled on after its last completed navigation. Starts at the router's
   * own current url, so a `NavigationStart` that fires before any `NavigationEnd` still reads the
   * right outgoing page. */
  private currentUrl: string = this.router.url;

  constructor() {
    this.router.events.subscribe((event) => {
      if (event instanceof NavigationStart) {
        this.previousUrl = this.currentUrl;
      } else if (event instanceof NavigationEnd) {
        this.currentUrl = event.urlAfterRedirects;
      }
    });
  }

  /** The in-app page the learner was on before the current one. Before the very first navigation
   * of a visit, this reads the router's own initial url, `/` — which is also the exact fallback
   * `SubscribePageComponent` wants when no real previous page exists. */
  previous(): string {
    return this.previousUrl;
  }
}
