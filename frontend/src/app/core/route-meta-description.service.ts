import { Injectable, inject } from '@angular/core';
import { Meta } from '@angular/platform-browser';
import { ActivatedRouteSnapshot, NavigationEnd, Router } from '@angular/router';
import { filter } from 'rxjs';

/**
 * Sets `<meta name="description">` from the activated route's `data.description`, on every
 * `NavigationEnd`.
 *
 * Angular's default `TitleStrategy` does this same walk of the activated route tree for `title`,
 * with no configuration. There is no built-in equivalent for the description tag, so this service
 * repeats that walk itself and calls `Meta.updateTag`.
 *
 * `app.config.ts` instantiates this once, eagerly, through `provideAppInitializer`. A service
 * that only `providedIn: 'root'` would never run at all, because nothing injects it otherwise.
 */
@Injectable({ providedIn: 'root' })
export class RouteMetaDescriptionService {
  private readonly router = inject(Router);
  private readonly meta = inject(Meta);

  constructor() {
    this.router.events.pipe(filter((event) => event instanceof NavigationEnd)).subscribe(() => {
      const description = this.deepestDescription(this.router.routerState.snapshot.root);
      if (description !== null) {
        this.meta.updateTag({ name: 'description', content: description });
      }
    });
  }

  /** The `data.description` of the deepest activated route that sets one, or `null` when none does. */
  private deepestDescription(root: ActivatedRouteSnapshot): string | null {
    let found: string | null = null;
    let current: ActivatedRouteSnapshot | null = root;
    while (current !== null) {
      const description = current.data['description'];
      if (typeof description === 'string') found = description;
      current = current.firstChild;
    }
    return found;
  }
}
