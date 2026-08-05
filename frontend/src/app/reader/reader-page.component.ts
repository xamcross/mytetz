import { Component, computed, effect, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { map } from 'rxjs';
import { SpanPayload, Verb } from '../core/models';
import { BreadcrumbComponent } from './breadcrumb.component';
import { FocusCardComponent } from './focus-card.component';
import { SessionStore } from './session.store';
import { TrailRailComponent } from './trail-rail.component';

/**
 * `/learn/:sessionId` — the reader.
 *
 * Layout C from the design spec: the session trail down the left, the breadcrumb over the focus card
 * on the right, the verb buttons under the card. Everything stateful lives in [SessionStore]; the
 * three components below are presentational and this component is the wiring between them.
 *
 * `SessionStore` is provided here rather than in the root injector, so its lifetime is this route's:
 * one session's nodes, focus and half-streamed text cannot leak into the next session opened in the
 * same tab.
 */
@Component({
  selector: 'app-reader-page',
  imports: [BreadcrumbComponent, FocusCardComponent, TrailRailComponent, RouterLink],
  providers: [SessionStore],
  template: `
    <main class="reader">
      @if (store.loading()) {
        <p class="status" role="status">Loading your session…</p>
      } @else if (loadError(); as failure) {
        <div class="banner banner--error" role="alert">
          <p class="banner__message">{{ failure.message }}</p>
          <div class="banner__actions">
            @if (failure.retryable) {
              <button type="button" class="banner__retry-button" (click)="store.retry()">
                Try again
              </button>
            }
            <a class="banner__back" routerLink="/">Back to topics</a>
          </div>
        </div>
      } @else if (store.session()) {
        <app-trail-rail
          class="reader__rail"
          [nodes]="store.tree()"
          [currentNodeId]="store.currentNodeId()"
          [topicLabel]="topicLabel()"
          (navigate)="store.goTo($event)"
        />

        <div class="reader__main">
          @if (bannerError(); as failure) {
            <div class="banner banner--error" role="alert">
              <p class="banner__message">
                {{ failure.message }}
                @if (failure.partiallyStreamed) {
                  <span class="banner__detail">
                    The partial answer on screen was discarded — it was never saved to your session.
                  </span>
                }
                @if (failure.retryAfter !== null) {
                  <span class="banner__detail">Try again in {{ wait(failure.retryAfter) }}.</span>
                }
              </p>
              <div class="banner__actions">
                @if (failure.retryable) {
                  <button type="button" class="banner__retry-button" (click)="store.retry()">
                    Try again
                  </button>
                }
                <button type="button" class="banner__dismiss" (click)="store.dismissError()">
                  Dismiss
                </button>
              </div>
            </div>
          }

          <app-breadcrumb
            [nodes]="store.breadcrumb()"
            [topicLabel]="topicLabel()"
            (navigate)="store.goTo($event)"
          />

          <app-focus-card
            [body]="store.currentBody()"
            [streamingText]="store.streamingText()"
            [isStreaming]="store.isStreaming()"
            (explainRequested)="explain($event)"
          />
        </div>
      }
    </main>
  `,
  styles: [
    `
      :host {
        display: block;
        font-family: system-ui, sans-serif;
        color: #1a1a1a;
      }
      .reader {
        max-width: 1040px;
        margin: 0 auto;
        padding: 1.5rem 1rem;
        display: grid;
        gap: 1.5rem;
      }
      .reader__main {
        min-width: 0;
      }
      .status {
        color: #555;
      }
      .banner {
        padding: 0.75rem 1rem;
        border-radius: 6px;
        margin: 0 0 1rem;
      }
      .banner--error {
        background: #fdecea;
        color: #7a1f1f;
        border: 1px solid #f3c6c2;
      }
      .banner__message {
        margin: 0 0 0.5rem;
      }
      .banner__detail {
        display: block;
        font-weight: 600;
        margin-top: 0.25rem;
      }
      .banner__actions {
        display: flex;
        gap: 0.75rem;
        align-items: center;
      }
      @media (min-width: 640px) {
        .reader {
          grid-template-columns: minmax(12rem, 16rem) 1fr;
          align-items: start;
        }
      }
    `,
  ],
})
export class ReaderPageComponent {
  private readonly route = inject(ActivatedRoute);
  readonly store = inject(SessionStore);

  /**
   * Read reactively rather than from `route.snapshot`. Angular reuses a component instance when only
   * a route parameter changes, so a snapshot read would leave a learner who navigated from one
   * session to another — history back/forward is enough — looking at the first session's tree under
   * the second session's URL.
   */
  private readonly sessionId = toSignal(
    this.route.paramMap.pipe(map((params) => params.get('sessionId') ?? '')),
    { initialValue: '' },
  );

  /**
   * A failure with nothing left to show behind it takes the whole page; anything else is a banner
   * over a working reader.
   *
   * The split is on "is there a session on screen", not on the failure's `kind`, and the difference
   * is a real one: a re-read that fails *after* a generation the learner has already paid for is a
   * load failure by kind, but the session it failed to refresh is still rendered and still correct.
   * Keying the full-page branch on `kind` alone would blank a working reader over a transient GET.
   */
  readonly loadError = computed(() => {
    const failure = this.store.error();
    return failure !== null && this.store.session() === null ? failure : null;
  });
  readonly bannerError = computed(() => {
    const failure = this.store.error();
    return failure !== null && this.store.session() !== null ? failure : null;
  });

  /**
   * The topic's name for the root crumb and the root rail row.
   *
   * Derived from the slug because `SessionView` carries only that — the real title lives on
   * `TopicSummary`, behind a second request this page would otherwise have to make purely for a
   * label. The catalogue's slugs are hand-curated kebab-case (`quantum-physics`), so the
   * reconstruction is exact for every topic in Slice 1; if that ever stops holding, adding `title`
   * to `SessionView` is the fix, not a cleverer transformation here.
   */
  readonly topicLabel = computed(() => {
    const slug = this.store.session()?.topicSlug ?? '';
    return slug
      .split('-')
      .filter((part) => part.length > 0)
      .map((part) => part[0].toUpperCase() + part.slice(1))
      .join(' ');
  });

  constructor() {
    effect(() => {
      const id = this.sessionId();
      if (id.length > 0) void this.store.load(id);
    });
  }

  explain(request: { span: SpanPayload; verb: Verb }): void {
    void this.store.explain(request.span, request.verb);
  }

  /**
   * A server-supplied wait, in words. A deliberate copy of `catalog-page.component.ts`'s formatter
   * rather than an import: that one is private to a component this task is not authorised to modify,
   * and importing from it would pull the catalogue page into the reader's lazy chunk. A shared
   * `core/` helper is the right move the moment a third caller appears.
   */
  wait(seconds: number): string {
    if (seconds < 60) {
      const s = Math.max(1, Math.ceil(seconds));
      return `${s} second${s === 1 ? '' : 's'}`;
    }
    const minutes = Math.ceil(seconds / 60);
    if (minutes < 60) return `${minutes} minute${minutes === 1 ? '' : 's'}`;
    const hours = Math.ceil(minutes / 60);
    return `${hours} hour${hours === 1 ? '' : 's'}`;
  }
}
