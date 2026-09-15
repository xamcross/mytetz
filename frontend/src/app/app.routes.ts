import { Routes } from '@angular/router';
import { CatalogPageComponent } from './catalog/catalog-page.component';

export const routes: Routes = [
  { path: '', component: CatalogPageComponent },
  {
    path: 'auth',
    loadComponent: () =>
      import('./auth/auth-landing.component').then((m) => m.AuthLandingComponent),
  },
  {
    path: 'account',
    loadComponent: () =>
      import('./account/account-page.component').then((m) => m.AccountPageComponent),
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
  },
  {
    path: 'terms',
    loadComponent: () => import('./legal/terms-page.component').then((m) => m.TermsPageComponent),
  },
  {
    path: 'imprint',
    loadComponent: () =>
      import('./legal/imprint-page.component').then((m) => m.ImprintPageComponent),
  },
];
