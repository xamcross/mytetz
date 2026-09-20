import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import {
  AccountView,
  AuthConfig,
  BillingPlansView,
  QuizAnswerPayload,
  QuizKind,
  QuizResultView,
  QuizTemplateView,
  SessionView,
  TopicSummary,
} from './models';

export interface Health {
  status: string;
  mongo: boolean;
}

/** The body of `POST /api/billing/checkout`. The server builds the URL and adds the learner's
 * email. No checkout secret ever reaches the browser this way. */
export interface CheckoutResponse {
  url: string;
}

/** The body of `POST /api/billing/portal`. The url points to the learner's own Freemius customer
 * portal page. The server reads the learner's email from the session. The browser sends no email
 * and no id on this call. */
export interface PortalResponse {
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
   * Marks a session complete, by the learner's own choice.
   *
   * The route answers `204` with no body. `SessionStore.complete` re-reads the session, or updates
   * it locally, rather than trusting a body this call never receives.
   */
  completeSession(id: string): Promise<void> {
    return firstValueFrom(this.http.post<void>(`/api/sessions/${id}/complete`, null));
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
   * The sign-in methods this deployment has configured, and the public Turnstile site key if one
   * is set. Open: it needs no account. `SignInPanelComponent` reads it before a learner has
   * signed in at all.
   */
  authConfig(): Promise<AuthConfig> {
    return firstValueFrom(this.http.get<AuthConfig>('/api/auth/config'));
  }

  /**
   * Asks the backend to email a sign-in link. Always resolves: the route answers `204` for a
   * known address and an unknown one alike, so this method carries no information about which one
   * `email` was.
   *
   * The body omits `turnstileToken` entirely when [turnstileToken] is `null`, and does not merely
   * set it to `undefined`. The backend field is optional. Sending an explicit `null` there is not
   * the same absence a deployment with no Turnstile secret expects. `AuthRoutes.kt`'s
   * `MagicLinkRequest` reads it as missing either way. This keeps the request body identical to
   * what it was before this field existed, whenever the panel never rendered a widget.
   */
  requestMagicLink(email: string, turnstileToken?: string | null): Promise<void> {
    return firstValueFrom(
      this.http.post<void>(
        '/api/auth/magic-link',
        turnstileToken ? { email, turnstileToken } : { email },
      ),
    );
  }

  signOut(): Promise<void> {
    return firstValueFrom(this.http.post<void>('/api/auth/sign-out', null));
  }

  /**
   * The price and the trial and subscriber numbers of the two plans. Open: it needs no account.
   * `SubscribePageComponent` reads it before a learner signs in, on the plan screen (issue #137).
   */
  billingPlans(): Promise<BillingPlansView> {
    return firstValueFrom(this.http.get<BillingPlansView>('/api/billing/plans'));
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

  /**
   * Asks the backend for a Freemius customer portal URL for the signed-in learner.
   *
   * The route reads the learner's email from the session. This method sends no email and no id.
   * The route answers `401` when the caller is not signed in, and `404 NO_SUBSCRIPTION` when the
   * learner has no subscription to manage.
   */
  portal(): Promise<PortalResponse> {
    return firstValueFrom(this.http.post<PortalResponse>('/api/billing/portal', null));
  }

  /**
   * Deletes the signed-in learner's account.
   *
   * The route needs a fresh sign-in. An old session gets `403 CONFIRMATION_REQUIRED`.
   * `AccountPageComponent` shows a specific message for that status.
   */
  deleteAccount(): Promise<void> {
    return firstValueFrom(this.http.post<void>('/api/account/delete', null));
  }

  /**
   * Starts a new quiz attempt for the session.
   *
   * `nodeId` is present in the body only when it is given. An exam covers the whole session and
   * takes no node. A Test Me quiz targets one node, so it sends the node's id.
   */
  startQuiz(sessionId: string, kind: QuizKind, nodeId?: string): Promise<QuizTemplateView> {
    return firstValueFrom(
      this.http.post<QuizTemplateView>(
        `/api/sessions/${sessionId}/quizzes`,
        nodeId === undefined ? { kind } : { kind, nodeId },
      ),
    );
  }

  /**
   * Submits every answer of one quiz attempt in a single request, and reads back the score.
   *
   * The route scores the whole list at once. It has no route for one answer at a time, so a
   * caller must collect every answer first and submit them together.
   */
  submitQuizAnswers(
    sessionId: string,
    attemptId: string,
    answers: QuizAnswerPayload[],
  ): Promise<QuizResultView> {
    return firstValueFrom(
      this.http.post<QuizResultView>(`/api/sessions/${sessionId}/quizzes/${attemptId}/answers`, {
        answers,
      }),
    );
  }
}
