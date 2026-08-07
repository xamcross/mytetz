import { Component, computed, effect, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { map } from 'rxjs';
import { SignInPanelComponent } from '../auth/sign-in-panel.component';
import { SpanPayload, Verb } from '../core/models';
import { BreadcrumbComponent } from './breadcrumb.component';
import { FocusCardComponent } from './focus-card.component';
import { SessionStore } from './session.store';
import { TrailRailComponent } from './trail-rail.component';

/** Words a title leaves lowercase unless they start it — see [ReaderPageComponent.topicLabel]. Only
 * `by` and `and` are load-bearing for today's catalogue; the rest are the conventional set, so the
 * next curated slug containing one does not need a code change to render correctly. */
const MINOR_WORDS: ReadonlySet<string> = new Set([
  'a',
  'an',
  'and',
  'as',
  'at',
  'but',
  'by',
  'for',
  'in',
  'nor',
  'of',
  'on',
  'or',
  'the',
  'to',
  'vs',
  'with',
]);

/**
 * `/learn/:sessionId` — the reader.
 *
 * Layout C from the design spec: the session trail down the left, the breadcrumb over the focus card
 * on the right. The focus card hosts the verb picker. Everything stateful lives in [SessionStore]; the
 * three components below are presentational and this component is the wiring between them.
 *
 * `SessionStore` is provided here rather than in the root injector, so its lifetime is this route's:
 * one session's nodes, focus and half-streamed text cannot leak into the next session opened in the
 * same tab.
 */
@Component({
  selector: 'app-reader-page',
  imports: [
    BreadcrumbComponent,
    FocusCardComponent,
    TrailRailComponent,
    RouterLink,
    SignInPanelComponent,
  ],
  providers: [SessionStore],
  template: `
    <main class="reader">
      @if (store.loading()) {
        <p class="visually-hidden" role="status">Loading your session…</p>
        <div class="reader__grid">
          <div class="reader__rail rail-skeleton" aria-hidden="true">
            <span class="mt-eyebrow rail-skeleton__head">Your trail</span>
            <span class="mt-pill mt-pill--ghost rail-skeleton__toggle">Show trail</span>
          </div>
          <div class="reader__main">
            <article class="focus-skeleton mt-card mt-card--raised">
              <span class="mt-eyebrow mt-eyebrow--coral">Writing your first explanation</span>
              <span class="mt-skeleton line"></span>
              <span class="mt-skeleton line"></span>
              <span class="mt-skeleton line line--92"></span>
              <span class="mt-skeleton line line--78"></span>
              <span class="mt-skeleton line line--46"></span>
              <p class="focus-skeleton__note">
                A few seconds — it is written fresh for you, then kept, so a return here is instant.
                The highlight unlocks when the text lands.
              </p>
            </article>
          </div>
        </div>
      } @else if (loadError(); as failure) {
        <div class="reader__centre">
          <div class="mt-card mt-card--error banner banner--error" role="alert">
            <p class="banner__message">{{ failure.message }}</p>
            <div class="banner__actions">
              @if (failure.retryable) {
                <button
                  type="button"
                  class="mt-pill mt-pill--coral banner__retry-button"
                  (click)="store.retry()"
                >
                  Try again
                </button>
              }
              <a class="mt-pill mt-pill--ghost banner__back" routerLink="/">Back to topics</a>
            </div>
          </div>
        </div>
      } @else if (store.session()) {
        <div class="reader__grid">
          <app-trail-rail
            class="reader__rail"
            [nodes]="store.tree()"
            [currentNodeId]="store.currentNodeId()"
            [topicLabel]="topicLabel()"
            (navigate)="store.goTo($event)"
          />

          <div class="reader__main">
            @if (bannerError(); as failure) {
              <div class="mt-card mt-card--error banner banner--error" role="alert">
                <p class="banner__message">
                  {{ failure.message }}
                  @if (failure.discardedText) {
                    <span class="banner__detail">
                      The partial answer on screen was discarded — it was never saved to your
                      session.
                    </span>
                  }
                  @if (failure.retryAfter !== null) {
                    <span class="banner__detail">Try again in {{ wait(failure.retryAfter) }}.</span>
                  }
                </p>
                <div class="banner__actions">
                  @if (failure.retryable) {
                    <!-- Teal, not coral. The reader is still on screen behind this banner, so the
                         verb picker and its coral "Explain it" stay reachable, and §3.3 allows one
                         coral control per view. The picker is a dialog that traps Tab, so while it
                         is open it is the whole view and its primary must be unmistakable. This
                         retry is then the secondary action, which is what teal names. -->
                    <button
                      type="button"
                      class="mt-pill mt-pill--teal banner__retry-button"
                      (click)="store.retry()"
                    >
                      Try again
                    </button>
                  }
                  <button
                    type="button"
                    class="mt-pill mt-pill--ghost banner__dismiss"
                    (click)="store.dismissError()"
                  >
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

            @if (signInRequired()) {
              <app-sign-in-panel />
            } @else {
              <app-focus-card
                [body]="store.currentBody()"
                [streamingText]="store.streamingText()"
                [isStreaming]="store.isStreaming()"
                [step]="step()"
                [verbLabel]="verbLabel()"
                [topicLabel]="topicLabel()"
                (explainRequested)="explain($event)"
              />
            }
          </div>
        </div>
      }
    </main>
  `,
  styles: [
    `
      :host {
        display: block;
      }
      .reader {
        padding: 28px 32px;
      }
      .visually-hidden {
        position: absolute;
        width: 1px;
        height: 1px;
        overflow: hidden;
        clip-path: inset(50%);
        white-space: nowrap;
      }
      /* One column below 768px. Two above it: the trail rail, then the card. The design's third
         column at 4a is dropped — every card in it needs a route that does not exist yet. It
         returns as a third track here and nowhere else. */
      .reader__grid {
        display: grid;
        grid-template-columns: 1fr;
        gap: 24px;
        max-width: 1004px;
        margin: 0 auto;
      }
      .reader__main {
        min-width: 0;
      }
      .reader__centre {
        max-width: 620px;
        margin: 48px auto 0;
      }
      .banner {
        padding: 20px 24px;
        margin: 0 0 16px;
        display: flex;
        flex-direction: column;
        gap: 10px;
      }
      .banner__message {
        margin: 0;
        font-size: 15px;
        line-height: 1.55;
        font-weight: 500;
      }
      .banner__detail {
        display: block;
        font-weight: 700;
        margin-top: 6px;
      }
      .banner__actions {
        display: flex;
        gap: 10px;
        align-items: center;
        flex-wrap: wrap;
      }
      .banner__back {
        text-decoration: none;
      }
      /* The rail placeholder mirrors the loaded rail, state for state: an eyebrow at 768px and
         above, a ghost pill below it. Below 768px the rail stacks over the card, so a placeholder
         of a different height moves the card down when the session lands. The height comes from
         .mt-pill, which is where the real toggle's height comes from too. A min-height here would
         drift the moment the pill changes. */
      .rail-skeleton {
        display: flex;
        flex-direction: column;
        gap: 12px;
        align-items: flex-start;
      }
      .focus-skeleton {
        padding: 32px 36px;
        display: flex;
        flex-direction: column;
        gap: 12px;
      }
      .line {
        display: block;
        height: 17px;
        width: 100%;
      }
      .line--92 {
        width: 92%;
      }
      .line--78 {
        width: 78%;
      }
      .line--46 {
        width: 46%;
      }
      .focus-skeleton__note {
        margin: 8px 0 0;
        font-size: 15px;
        line-height: 1.6;
        font-weight: 500;
        color: var(--mt-muted);
        max-width: 56ch;
        text-wrap: pretty;
      }
      @media (min-width: 768px) {
        .reader__grid {
          grid-template-columns: 260px minmax(0, 720px);
          align-items: start;
        }
        .rail-skeleton__toggle {
          display: none;
        }
      }
      @media (max-width: 767px) {
        .reader {
          padding: 20px;
        }
        .focus-skeleton {
          padding: 20px;
        }
        .rail-skeleton__head {
          display: none;
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
    if (failure === null || this.store.session() === null) return null;
    // SIGN_IN_REQUIRED gets its own branch below — see `signInRequired` — and not this banner:
    // the sign-in panel already says what happened, and a banner on top of it would say it twice.
    if (failure.code === 'SIGN_IN_REQUIRED') return null;
    return failure;
  });

  /**
   * True when the last explain attempt was refused because nobody is signed in.
   *
   * Drives the one substitution this task makes: the sign-in panel appears where the focus card
   * would, and the breadcrumb and the trail rail stay exactly as they were. Losing a learner's
   * trail at the moment they are asked to sign in would be the most expensive thing this could do.
   */
  readonly signInRequired = computed(() => this.store.error()?.code === 'SIGN_IN_REQUIRED');

  /**
   * The topic's name for the root crumb and the root rail row.
   *
   * The curated title comes first. `SessionStore` reads it from
   * `GET /api/catalog/topics/{slug}`, which is where the catalogue keeps it. The reader used to
   * rebuild the title from the slug and never asked that route for it.
   *
   * The rebuild stays as the fallback, because `topicTitle` is legitimately null twice: while the
   * catalogue request is still in flight, and when it answers 404 for a topic that a curator
   * unpublished under a session that still loads. A blank crumb in either case is worse than an
   * approximate one.
   *
   * The fallback capitalises every word except the short connectives, which is what the
   * catalogue's own titles do. A plain per-word capitalisation is *not* exact: run over all 29
   * published slugs in `topics.json`, it disagrees with the real title on two of them — "Evolution
   * **By** Natural Selection" against "Evolution by Natural Selection", and "Supply **And**
   * Demand" against "Supply and Demand". With [MINOR_WORDS] all 29 agree, which is the whole of
   * the current catalogue, checked rather than assumed.
   *
   * The fallback is still a guess, and it drifts on the first title that is not a mechanical
   * transform of its slug. It is now a guess of last resort and not the only answer.
   */
  readonly topicLabel = computed(() => this.store.topicTitle() ?? this.labelFromSlug());

  /** Words for the verb of the node in focus. The same map the trail rail uses, kept here rather
   * than shared: two short maps are cheaper than a `core/` module that one more caller would
   * justify. Add the third caller and move it. */
  private static readonly VERB_WORDS: Readonly<Record<string, string>> = {
    SEED: 'Topic',
    EXPLAIN: 'Explain',
    DIG_DEEPER: 'Dig deeper',
    BROADER_PICTURE: 'Broader picture',
    SIDE_VIEW: 'Side view',
    VISUALIZE: 'Diagram',
  };

  /** The node in focus, or `null` while the session loads. */
  private readonly currentNode = computed(() => {
    const id = this.store.currentNodeId();
    return this.store.tree().find((n) => n.nodeId === id) ?? null;
  });

  /** The position of the node in focus in the trail, counted from one. */
  readonly step = computed(() => {
    const node = this.currentNode();
    return node === null ? null : node.depth + 1;
  });

  readonly verbLabel = computed(() => {
    const node = this.currentNode();
    if (node === null) return '';
    return ReaderPageComponent.VERB_WORDS[node.verb] ?? node.verb;
  });

  private labelFromSlug(): string {
    const slug = this.store.session()?.topicSlug ?? '';
    return slug
      .split('-')
      .filter((part) => part.length > 0)
      .map((part, index) =>
        index > 0 && MINOR_WORDS.has(part) ? part : part[0].toUpperCase() + part.slice(1),
      )
      .join(' ');
  }

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
