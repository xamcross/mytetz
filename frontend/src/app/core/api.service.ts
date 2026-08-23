import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { AccountView, SessionView, TopicSummary } from './models';

export interface Health {
  status: string;
  mongo: boolean;
}

/** The body of `POST /api/billing/checkout`. The server builds the URL and adds the learner's
 * email. No checkout secret ever reaches the browser this way. */
export interface CheckoutResponse {
  url: string;
}

@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);

  health(): Promise<Health> {
    return firstValueFrom(this.http.get<Health>('/api/health'));
  }

  topics(q?: string): Promise<TopicSummary[]> {
    const params = q === undefined ? undefined : new HttpParams().set('q', q);
    return firstValueFrom(this.http.get<TopicSummary[]>('/api/catalog/topics', { params }));
  }

  /**
   * One published topic, by slug.
   *
   * The route answers 404 for a topic that does not exist and for one that is not published. It
   * uses one answer for both on purpose, so that a guessed slug tells a caller nothing.
   * A session on a topic that a curator later unpublished therefore gets a 404 here, while the
   * session itself still loads. A caller must handle that.
   */
  topic(slug: string): Promise<TopicSummary> {
    return firstValueFrom(this.http.get<TopicSummary>(`/api/catalog/topics/${slug}`));
  }

  createSession(topicSlug: string): Promise<SessionView> {
    return firstValueFrom(this.http.post<SessionView>('/api/sessions', { topicSlug }));
  }

  session(id: string): Promise<SessionView> {
    return firstValueFrom(this.http.get<SessionView>(`/api/sessions/${id}`));
  }

  /**
   * The signed-in learner's account. Answers `401 SIGN_IN_REQUIRED` when the browser holds no
   * session cookie, or an expired one — an ordinary outcome for a caller to expect, not only a
   * server fault. See `AccountStore.load`, which is where that distinction is made.
   */
  account(): Promise<AccountView> {
    return firstValueFrom(this.http.get<AccountView>('/api/account'));
  }

  /**
   * Asks the backend to email a sign-in link. Always resolves: the route answers `204` for a
   * known address and an unknown one alike, so this method carries no information about which one
   * `email` was.
   */
  requestMagicLink(email: string): Promise<void> {
    return firstValueFrom(this.http.post<void>('/api/auth/magic-link', { email }));
  }

  signOut(): Promise<void> {
    return firstValueFrom(this.http.post<void>('/api/auth/sign-out', null));
  }

  signOutAll(): Promise<void> {
    return firstValueFrom(this.http.post<void>('/api/auth/sign-out-all', null));
  }

  /**
   * Asks the backend for a Freemius checkout URL for the signed-in learner.
   *
   * The URL already carries the learner's email and `readonly_user=true`. The browser only
   * follows this URL and never builds one itself. The route answers `401` when the caller is not
   * signed in.
   */
  checkout(): Promise<CheckoutResponse> {
    return firstValueFrom(this.http.post<CheckoutResponse>('/api/billing/checkout', null));
  }
}
