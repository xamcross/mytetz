import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { Router } from '@angular/router';
import { ApiService } from '../core/api.service';
import { SessionView, TopicSummary } from '../core/models';

/**
 * The product's entry point: `/`. Lists the curated catalogue and, on selection, creates a
 * session and hands off to the reader at `/learn/:sessionId`.
 *
 * Does not read `window`, `document` or `localStorage` on its render path (the `(input)` handler
 * below reads `Event.target`, which is unrelated to those globals and is available wherever the
 * event itself fires) — kept that way on purpose, per the design spec's note that the Angular
 * reader must stay SSR-safe for when spec C adds server rendering.
 */
@Component({
  selector: 'app-catalog-page',
  imports: [],
  template: `
    <main class="catalog">
      <header class="catalog__header">
        <h1>mytetz</h1>
        <p class="catalog__tagline">Pick a topic, highlight anything confusing, keep going.</p>
      </header>

      <div class="catalog__filter">
        <label for="topic-filter">Filter topics</label>
        <input
          id="topic-filter"
          type="search"
          placeholder="Search by title, category, or summary…"
          [value]="query()"
          (input)="onQueryInput($event)"
        />
      </div>

      @if (sessionError(); as err) {
        <p class="banner banner--error" role="alert">
          {{ err.message }}
          @if (err.retryLabel) {
            <span class="banner__retry">{{ err.retryLabel }}</span>
          }
          @if (err.reopenSessionId; as sessionId) {
            <button type="button" class="banner__retry-button" (click)="reopen(sessionId)">
              Try again
            </button>
          }
        </p>
      }

      @if (topicsLoading()) {
        <p class="status" role="status">Loading topics…</p>
      } @else if (topicsError(); as loadError) {
        <p class="banner banner--error" role="alert">
          {{ loadError }}
          <button type="button" class="banner__retry-button" (click)="loadTopics()">Retry</button>
        </p>
      } @else {
        <ul class="topics" [attr.aria-busy]="pendingSlug() !== null">
          @for (t of filteredTopics(); track t.slug) {
            <li class="topic">
              <button
                type="button"
                class="topic__button"
                [attr.data-slug]="t.slug"
                [disabled]="pendingSlug() !== null"
                (click)="open(t)"
              >
                <span class="topic__category">{{ t.category }}</span>
                <h2 class="topic__title">{{ t.title }}</h2>
                <p class="topic__summary">{{ t.summary }}</p>
                @if (pendingSlug() === t.slug) {
                  <span class="topic__pending" aria-live="polite">Starting…</span>
                }
              </button>
            </li>
          } @empty {
            <li class="topics__empty">
              @if (query()) {
                No topics match "{{ query() }}".
              } @else {
                No topics available yet.
              }
            </li>
          }
        </ul>
      }
    </main>
  `,
  styles: [
    `
      :host {
        display: block;
        font-family: system-ui, sans-serif;
        color: #1a1a1a;
        max-width: 720px;
        margin: 0 auto;
        padding: 2rem 1rem;
      }
      .catalog__tagline {
        color: #555;
      }
      .catalog__filter {
        margin: 1.5rem 0;
        display: flex;
        flex-direction: column;
        gap: 0.25rem;
      }
      .catalog__filter input {
        padding: 0.5rem 0.75rem;
        border: 1px solid #ccc;
        border-radius: 6px;
        font-size: 1rem;
      }
      .banner {
        padding: 0.75rem 1rem;
        border-radius: 6px;
        margin: 1rem 0;
      }
      .banner--error {
        background: #fdecea;
        color: #7a1f1f;
        border: 1px solid #f3c6c2;
      }
      .banner__retry {
        display: block;
        font-weight: 600;
        margin-top: 0.25rem;
      }
      .banner__retry-button {
        margin-left: 0.75rem;
      }
      .status {
        color: #555;
      }
      .topics {
        list-style: none;
        padding: 0;
        margin: 0;
        display: grid;
        gap: 0.75rem;
      }
      .topic__button {
        width: 100%;
        text-align: left;
        padding: 1rem;
        border: 1px solid #ddd;
        border-radius: 8px;
        background: #fff;
        cursor: pointer;
      }
      .topic__button:disabled {
        opacity: 0.6;
        cursor: not-allowed;
      }
      .topic__category {
        font-size: 0.75rem;
        text-transform: uppercase;
        letter-spacing: 0.04em;
        color: #888;
      }
      .topic__title {
        margin: 0.25rem 0;
        font-size: 1.1rem;
      }
      .topic__summary {
        margin: 0;
        color: #444;
      }
      .topic__pending {
        display: block;
        margin-top: 0.5rem;
        font-weight: 600;
        color: #1a56db;
      }
      .topics__empty {
        color: #555;
        padding: 1rem 0;
      }
    `,
  ],
})
export class CatalogPageComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);

  readonly topics = signal<TopicSummary[]>([]);
  readonly query = signal('');
  readonly topicsLoading = signal(true);
  readonly topicsError = signal<string | null>(null);

  /**
   * The slug of the topic currently mid-`createSession`, or `null` when nothing is in flight.
   *
   * Creating a session on a topic nobody has opened yet triggers a real model call to generate
   * the seed — seconds, not milliseconds, not the tens of milliseconds a click usually implies.
   * `open()` below checks this and returns immediately if it is already set, and every button is
   * disabled while it is set — not just the one clicked — so a learner cannot burn a second slot
   * out of the 30-per-hour session limit by double-clicking, or by clicking a different topic
   * while the first request is still in flight.
   */
  readonly pendingSlug = signal<string | null>(null);
  readonly sessionError = signal<SessionErrorView | null>(null);

  /**
   * Client-side, deliberately. `?q=` exists on the backend (Task 1.3), but Slice 1's whole
   * catalogue is ~20 hand-curated topics (design spec §14) — already fetched in full by
   * `loadTopics()` below — so filtering it locally is instant and issues zero additional
   * requests, which sidesteps "one request per keystroke against a rate-limited backend"
   * entirely rather than solving it with debouncing. Matches the backend's own fields (title,
   * category, summary — `aliases` is not part of the `TopicSummary` the API exposes) with a
   * case-insensitive substring test, mirroring `TopicRepository.listPublished`'s semantics as
   * closely as the client-visible fields allow. Revisit if Slice 5 grows the catalogue to the
   * "few hundred topics" the design spec anticipates — at that size client-side filtering may no
   * longer be the right trade-off.
   */
  readonly filteredTopics = computed(() => {
    const q = this.query().trim().toLowerCase();
    if (!q) return this.topics();
    return this.topics().filter(
      (t) =>
        t.title.toLowerCase().includes(q) ||
        t.category.toLowerCase().includes(q) ||
        t.summary.toLowerCase().includes(q),
    );
  });

  ngOnInit(): void {
    void this.loadTopics();
  }

  async loadTopics(): Promise<void> {
    this.topicsLoading.set(true);
    this.topicsError.set(null);
    try {
      this.topics.set(await this.api.topics());
    } catch {
      this.topicsError.set('Could not load the topic list. Check your connection and try again.');
    } finally {
      this.topicsLoading.set(false);
    }
  }

  onQueryInput(event: Event): void {
    this.query.set((event.target as HTMLInputElement).value);
  }

  async open(topic: TopicSummary): Promise<void> {
    // A second click while one request is already in flight does nothing. This guard also
    // covers the gap between the session existing and navigation completing — see
    // `goToSession`'s doc comment for why `pendingSlug` is deliberately *not* cleared once
    // `createSession` succeeds.
    if (this.pendingSlug() !== null) return;

    this.sessionError.set(null);
    this.pendingSlug.set(topic.slug);

    let session: SessionView;
    try {
      session = await this.api.createSession(topic.slug);
    } catch (err) {
      this.sessionError.set(describeSessionError(err));
      this.pendingSlug.set(null);
      return;
    }

    await this.goToSession(session.sessionId);
  }

  /** Retries navigating to an already-created session — never re-runs `createSession`, which
   * would spend a second session slot (and, on a first-ever topic, a second model call) on a
   * topic the learner already has an open session for. */
  reopen(sessionId: string): void {
    this.sessionError.set(null);
    void this.goToSession(sessionId);
  }

  /**
   * Navigates to an already-created session, awaited rather than fired-and-forgotten.
   *
   * Two defects from the first cut of this method, found in review, share one fix:
   *
   * 1. **`pendingSlug` must not be cleared on the success path.** The previous version reset it
   *    in a `finally` that ran immediately after firing (not awaiting) `navigate()`. That
   *    re-enabled every topic button while the route change — including, on a first visit, the
   *    lazy fetch of Task 1.16's reader chunk — was still in flight. A click landing in that
   *    window called `open()` again and created a *second*, paid session on a topic the learner
   *    already had one open for: exactly the failure Problem E's guard exists to prevent, just
   *    moved one step later. The fix is to never clear `pendingSlug` here on success at all: this
   *    component is about to be torn down by the navigation that just succeeded, so there is
   *    nothing left to re-enable for, and clearing it early is what reopens the window.
   * 2. **A navigation failure must not be silently swallowed.** The previous version only logged
   *    it (`.catch(() => console.error(...))`), which — from the learner's side — looks
   *    identical to the click never having registered, except that a session slot (and possibly
   *    a real model call, on a first-ever topic) has already been spent. `router.navigate()` can
   *    fail two ways, confirmed by reading `@angular/router`'s own `Recognizer`/`Navigation
   *    Transitions` source rather than assuming: a genuine `NavigationError` (e.g. a failed fetch
   *    of the lazy chunk) rejects the returned promise, while a cancellation (no matching route,
   *    a guard) resolves it to `false`. Both are handled and both are surfaced through
   *    `sessionError`, with a `reopen()` action rather than routing the learner back through
   *    `open()` — see that method's own comment for why.
   */
  private async goToSession(sessionId: string): Promise<void> {
    try {
      const navigated = await this.router.navigate(['/learn', sessionId]);
      if (!navigated) this.failNavigation(sessionId);
      // Otherwise: success, and `pendingSlug` is deliberately left set — see the doc comment
      // above.
    } catch {
      this.failNavigation(sessionId);
    }
  }

  private failNavigation(sessionId: string): void {
    this.sessionError.set({
      message: 'Your session was created, but the reader could not load.',
      retryLabel: null,
      reopenSessionId: sessionId,
    });
    this.pendingSlug.set(null);
  }
}

interface SessionErrorView {
  message: string;
  retryLabel: string | null;
  /** Set only for a navigation failure after a session already exists — see `reopen()`. Absent
   * for a `createSession` failure, where there is no session yet to reopen. */
  reopenSessionId?: string;
}

/**
 * The same `{code, message, retryAfter}` shape `ErrorMapping.kt` and `SessionRoutes.kt` respond
 * with on every refusal — 429 `RATE_LIMITED` (Task 1.11's 30-per-hour session limiter), 429
 * `QUOTA_EXCEEDED` and 503 `SPEND_LIMIT` (Task 1.12's per-principal quota and global spend
 * breaker) all use it, so one parser handles every case `POST /api/sessions` can return rather
 * than one branch per code. Not imported from a shared file: `sse.client.ts`'s `ErrorEventData`
 * is the same shape for the same reason and is kept private there too — this is a wire shape, not
 * a domain model, so it doesn't belong in `models.ts` alongside `TopicSummary`/`SessionView`.
 */
interface ApiErrorBody {
  code: string;
  message: string;
  retryAfter?: number | null;
}

function asApiErrorBody(value: unknown): ApiErrorBody | null {
  if (
    value !== null &&
    typeof value === 'object' &&
    typeof (value as Record<string, unknown>)['code'] === 'string' &&
    typeof (value as Record<string, unknown>)['message'] === 'string'
  ) {
    return value as ApiErrorBody;
  }
  return null;
}

/**
 * Turns a failed `createSession` into what the learner sees.
 *
 * The distinction the brief asks for falls out of the data rather than a switch on `code`: a
 * `retryAfter` means "try later" (429 `RATE_LIMITED`, 429 `QUOTA_EXCEEDED`) and is rendered as a
 * concrete wait; its absence means "the service itself is degraded" (503 `SPEND_LIMIT`, 503
 * `QUOTA_UNAVAILABLE`) and no retry countdown is shown, because there is no server-known wait to
 * report — inventing one would be a promise this component cannot keep. Either way the backend's
 * own `message` is shown verbatim: `ErrorMapping.kt`/`SessionRoutes.kt` already write these for a
 * human reader, and re-wording them here would just be a second copy to keep in sync.
 */
function describeSessionError(err: unknown): SessionErrorView {
  const body = err instanceof HttpErrorResponse ? asApiErrorBody(err.error) : null;
  if (body) {
    return {
      message: body.message,
      retryLabel: body.retryAfter ? `Try again in ${formatRetryAfter(body.retryAfter)}.` : null,
    };
  }
  return {
    message: 'Could not start that topic. Please try again.',
    retryLabel: null,
  };
}

function formatRetryAfter(seconds: number): string {
  if (seconds < 60) {
    const s = Math.max(1, Math.ceil(seconds));
    return `${s} second${s === 1 ? '' : 's'}`;
  }
  const minutes = Math.ceil(seconds / 60);
  if (minutes < 60) {
    return `${minutes} minute${minutes === 1 ? '' : 's'}`;
  }
  const hours = Math.ceil(minutes / 60);
  return `${hours} hour${hours === 1 ? '' : 's'}`;
}
