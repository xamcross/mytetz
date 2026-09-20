import { Routes } from '@angular/router';
import { CatalogPageComponent } from './catalog/catalog-page.component';

/**
 * `title` here reaches `document.title` through Angular's default `TitleStrategy`. `data.description`
 * reaches `<meta name="description">` through `RouteMetaDescriptionService`, which walks this same
 * tree the same way on every `NavigationEnd` — see that service's own comment.
 *
 * `learn/:sessionId` sets neither. Its title and description depend on the topic a session loads,
 * so `ReaderPageComponent` sets `Title` itself once that topic is known. See its `topicLabel`.
 */
export const routes: Routes = [
  {
    path: '',
    component: CatalogPageComponent,
    title: 'mytetz: understand hard topics one sentence at a time',
    data: {
      description:
        'Pick a topic like quantum physics or game theory. Highlight any phrase you do not understand, and get an explanation that fits where you are.',
    },
  },
  {
    path: 'auth',
    loadComponent: () =>
      import('./auth/auth-landing.component').then((m) => m.AuthLandingComponent),
    title: 'Sign-in | mytetz',
    data: {
      description:
        'Sign in to mytetz with a magic link or your Google account, and continue where you left off.',
    },
  },
  {
    path: 'account',
    loadComponent: () =>
      import('./account/account-page.component').then((m) => m.AccountPageComponent),
    title: 'Your account | mytetz',
    data: {
      description: 'Manage your mytetz subscription, your usage, and your sign-in.',
    },
  },
  {
    path: 'subscribe',
    loadComponent: () =>
      import('./account/subscribe-page.component').then((m) => m.SubscribePageComponent),
    title: 'Subscribe | mytetz',
    data: {
      description: 'See the Premium and the Free plan, and start a subscription to mytetz.',
    },
  },
  {
    path: 'learn/:sessionId',
    loadComponent: () =>
      import('./reader/reader-page.component').then((m) => m.ReaderPageComponent),
  },
  {
    path: 'privacy',
    loadComponent: () =>
      import('./legal/privacy-page.component').then((m) => m.PrivacyPageComponent),
    title: 'Privacy policy | mytetz',
    data: {
      description:
        'Read the mytetz privacy policy: the data we collect, the cookies we set, and the rights you have over your data.',
    },
  },
  {
    path: 'terms',
    loadComponent: () => import('./legal/terms-page.component').then((m) => m.TermsPageComponent),
    // The page's own heading is "Terms of service", not "Terms" — the title matches the heading.
    title: 'Terms of service | mytetz',
    data: {
      description: 'Read the mytetz terms of service: the trial, payment, and cancellation.',
    },
  },
  {
    path: 'imprint',
    loadComponent: () =>
      import('./legal/imprint-page.component').then((m) => m.ImprintPageComponent),
    title: 'Imprint | mytetz',
    data: {
      description: 'The legal imprint for mytetz: the provider, and the required contact details.',
    },
  },
  {
    path: '**',
    loadComponent: () =>
      import('./not-found/not-found-page.component').then((m) => m.NotFoundPageComponent),
  },
];
