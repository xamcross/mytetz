import { Routes } from '@angular/router';
import { CatalogPageComponent } from './catalog/catalog-page.component';

export const routes: Routes = [
  { path: '', component: CatalogPageComponent },
  {
    path: 'learn/:sessionId',
    loadComponent: () =>
      import('./reader/reader-page.component').then((m) => m.ReaderPageComponent),
  },
];
