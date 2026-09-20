import { Component, computed, effect, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { Title } from '@angular/platform-browser';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { map } from 'rxjs';
import { METERED_STATUSES } from '../account/allowance-meter.component';
import { WallCode, WallPanelComponent } from '../account/wall-panel.component';
import { QuizPanelComponent } from '../assess/quiz-panel.component';
import { SignInPanelComponent } from '../auth/sign-in-panel.component';
import { AccountStore } from '../core/account.store';
import { ApiService } from '../core/api.service';
import { QuizKind, SpanPayload, Verb } from '../core/models';
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
    WallPanelComponent,
    QuizPanelComponent,
  ],
  providers: [SessionStore],
  template: `
    <main class="reader">
      @if (store.loading()) {
        <p class="mt-sr-only" role="status">Loading your session…</p>
        <!--
          Finding F17 of the design review. The reading column sits before the rail here, in the
          DOM and not only on screen — see the comment on .reader__grid below for why. This
          placeholder must match that order, or the loaded content lands somewhere else and the
          page jumps the moment it replaces the skeleton.
        -->
        <div class="reader__grid">
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
          <div class="reader__rail rail-skeleton" aria-hidden="true">
            <span class="mt-pill mt-pill--ghost rail-skeleton__exam">Exam</span>
            <span class="mt-eyebrow rail-skeleton__head">Your trail</span>
            <span class="mt-pill mt-pill--ghost rail-skeleton__toggle">Show trail</span>
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
              <a class="mt-pill mt-pill--ghost banner__back" routerLink="/"
                >Back to the dashboard</a
              >
            </div>
          </div>
        </div>
      } @else if (store.session(); as session) {
        <!--
          Finding F17 of the design review. Below 768px the grid collapses to one column, and the
          rail used to sit first — a learner met the Exam pill, the trail toggle and the
          breadcrumb before the reading material. CSS order alone would not fix this: a browser
          tabs in DOM order regardless of order, so a keyboard learner would still land on the
          rail first while the eye met the card. The reading column is therefore first in the DOM
          here, at every width, and .reader__grid's own desktop rule places the rail back on the
          left with an explicit grid-column and grid-row — not order — which is what keeps the
          desktop layout without reopening the same mismatch there.
        -->
        <div class="reader__grid">
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

            <!--
              Finding F11 of the design review. app-focus-card's own <h1> shows at step 1 only —
              see its class comment. Past step 1 nothing else on this page is an <h1>, and a page
              with none is a defect for a screen reader and for a search engine. This one stands
              in exactly then, so the reader page keeps exactly one <h1> at every step. It is
              mt-sr-only rather than a second visible heading: the breadcrumb's root crumb already
              names the topic on screen, and a second visible copy would recreate the repetition
              F11 reports.
            -->
            @if (step() !== 1) {
              <h1 class="mt-sr-only">{{ topicLabel() }}</h1>
            }

            <app-breadcrumb
              [nodes]="store.breadcrumb()"
              [topicLabel]="topicLabel()"
              (navigate)="store.goTo($event)"
            />

            @if (signInRequired()) {
              <app-sign-in-panel animate.enter="wall--in" />
            } @else if (subscribeRequired(); as code) {
              <app-wall-panel [code]="code" animate.enter="wall--in" />
            } @else {
              <app-focus-card
                [body]="store.currentBody()"
                [media]="store.currentMedia()"
                [streamingText]="store.streamingText()"
                [isStreaming]="store.isStreaming()"
                [explainFailed]="store.error() !== null"
                [tokenResultText]="tokenResultAnnouncement()"
                [step]="step()"
                [verbLabel]="verbLabel()"
                [topicLabel]="topicLabel()"
                [readOnly]="store.isCompleted()"
                (explainRequested)="explain($event)"
              />
              <!--
                Finding F11's third change. Test me used to sit inside the card, and the session's
                own end control sat apart from it. The two now share one row below the card, and
                Exam stays in the rail — a different kind of control, a full exam rather than a
                check on the node in focus.
              -->
              <div class="focus__actions">
                <!--
                  Issue #139, review round 2. The price used to sit on a second line under the
                  label, which gave this pill and the plain one-line pill next to it two
                  different heights on the same row — a real screenshot found this. The price now
                  sits after the label, on the pill's own one line, the way .mt-pill already lays
                  out any two children: the pill's height is therefore the plain, one-line height
                  it always was, price or no price. aria-label keeps the accessible name "Test me"
                  fixed, so the price joins the description aria-describedby names and not the
                  name itself — the same split the verb picker keeps.
                -->
                <button
                  type="button"
                  class="mt-pill mt-pill--ghost"
                  data-testid="test-me"
                  aria-label="Test me"
                  [attr.aria-describedby]="showTokenPrice() ? 'test-me-price' : null"
                  (click)="testMe()"
                >
                  Test me
                  @if (showTokenPrice()) {
                    <!-- A non-breaking space, so "1" and "token" always wrap together. -->
                    <span class="reader__action-price" id="test-me-price">1&nbsp;token</span>
                  }
                </button>
                <!-- One control at a time: a completed session offers to start a new one, and an
                     active session offers to end itself. Never both — a learner who has just
                     completed a session has nothing left here to complete again. Neither carries a
                     price: completing a session spends nothing, and starting a new session on a
                     topic this learner already read spends nothing either — its seed is already
                     stored (see SessionService.create's content-addressed seed lookup). -->
                @if (store.isCompleted()) {
                  <button
                    type="button"
                    class="mt-pill mt-pill--coral"
                    data-testid="new-session"
                    (click)="startNewSession()"
                  >
                    Start a new session on this topic
                  </button>
                } @else {
                  <button
                    type="button"
                    class="mt-pill mt-pill--ghost"
                    data-testid="complete-session"
                    (click)="completeSession()"
                  >
                    Mark this session complete
                  </button>
                }
              </div>
              <!--
                Issue #139. Reserves its own line at every moment, whether or not it holds text, so
                its own arrival and departure move nothing below it — the focus card's own body
                sits above this row and is never affected either way.
              -->
              <p class="focus__token-result" role="presentation">{{ tokenResultAnnouncement() }}</p>
            }

            @if (quizKind(); as kind) {
              <app-quiz-panel
                [sessionId]="session.sessionId"
                [kind]="kind"
                [nodeId]="quizNodeId()"
                (close)="closeQuiz()"
              />
            }
          </div>

          <div class="reader__rail">
            <!-- TrailRailComponent draws its own "Your trail" heading; this control does not
                 belong to that component's file, so it sits here, directly above the rail. -->
            <button
              type="button"
              class="mt-pill mt-pill--ghost reader__exam"
              data-testid="exam"
              aria-label="Exam"
              [attr.aria-describedby]="showTokenPrice() ? 'exam-price' : null"
              (click)="exam()"
            >
              Exam
              @if (showTokenPrice()) {
                <span class="reader__action-price" id="exam-price">1&nbsp;token</span>
              }
            </button>
            <app-trail-rail
              [nodes]="store.tree()"
              [currentNodeId]="store.currentNodeId()"
              [topicLabel]="topicLabel()"
              (navigate)="store.goTo($event)"
            />
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
      /* Animation M. The wall panel stops a learner mid-task, and the sign-in panel does the
         same, so both settle in gently from below rather than snapping into the focus card's own
         slot. No overshoot: a wall is not an arrival to celebrate. */
      .wall--in {
        animation: wall-in var(--mt-dur-panel) var(--mt-ease-out) both;
      }
      @keyframes wall-in {
        from {
          opacity: 0;
          transform: translateY(var(--mt-move-far));
        }
        to {
          opacity: 1;
          transform: none;
        }
      }
      /* One column below 768px, in DOM order: the reading column, then the rail — see the
         template comment on this element for finding F17. Two columns above it: the trail rail on
         the left, the card on the right, as the design draws it. The design's third column at 4a
         is dropped — every card in it needs a route that does not exist yet. It returns as a
         third track here and nowhere else. */
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
      .reader__rail {
        display: flex;
        flex-direction: column;
        gap: 12px;
      }
      .reader__exam {
        align-self: flex-end;
      }
      /* Finding F11 of the design review. Test me and the session's own end control share this
         row, below the card. */
      /* The card's own lift shadow (--mt-lift-card in styles.css) reaches 5px below its border
         box. This row keeps at least 12px clear of that shadow, so it reads as a control below
         the card and not as a control the shadow already touches. 20px of margin leaves 15px
         clear of the shadow's own 5px, a margin above the 12px the criterion asks for. */
      .focus__actions {
        display: flex;
        /* Issue #139, review round 2. 8px, not the 10px this row always had: at 390px, Test me's
           new price left the row 2.4px over its own 350px width, one real run measured, which
           wrapped "Mark this session complete" onto a second line — the very row this round
           keeps to one line and one height. 8px recovers exactly the 2px this rule's own share
           of the fix needs; the rest comes from the smaller gap the priced pill itself now
           carries, below. */
        gap: 8px;
        flex-wrap: wrap;
        margin-top: 20px;
      }
      /*
       * Issue #139, review round 2. The price used to sit on a second line under the label,
       * which gave Test me one height and the plain, one-line "Mark this session complete" pill
       * next to it another — a real screenshot found the row with two heights and two centre
       * lines. The price now sits after the label, on .mt-pill's own one line, so Test me is a
       * plain one-line pill again, the same height as every pill beside it, price or no price.
       *
       * Inherits its colour from the pill it sits inside — --mt-teal on --mt-surface for a ghost
       * pill (5.47:1), already above the 4.5:1 an AA small text needs, so this rule adds no new
       * colour token.
       */
      .reader__action-price {
        font-size: 11px;
        font-weight: 800;
      }
      /*
       * Issue #139, review round 2. .mt-pill's own 6px gap, between Test me's label and its new
       * price, was measured pushing the row 2.4px past the 350px this row has at 390px — a real
       * run then wrapped "Mark this session complete" onto a second line, the very defect this
       * round fixes for the row's own height. :has() scopes a smaller gap to a pill that actually
       * carries a price, so .mt-pill's own rule stays the same for every other pill in the app.
       */
      .focus__actions .mt-pill:has(.reader__action-price) {
        gap: 4px;
      }
      /* Below 480px the two pills of this row need real spare room, and not 2px of it. A run on
         the CI machine, where the fonts are a little wider, put "Mark this session complete" on
         a second row at 390px. A side padding of 12px, and not the 16px of .mt-pill, gives the
         row 16px of spare room. The height of a pill does not change. */
      @media (max-width: 479px) {
        .focus__actions .mt-pill {
          padding-inline: 12px;
        }
      }
      /* Issue #139. One line, reserved whether or not it holds text, so the sentence that names
         the true result of an action moves nothing below it when it appears or clears. */
      .focus__token-result {
        margin: 8px 0 0;
        min-height: 20px;
        font-size: 14px;
        font-weight: 700;
        line-height: 20px;
        color: var(--mt-muted);
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
      /* The rail placeholder mirrors the loaded rail, state for state. It has an Exam ghost pill,
         an eyebrow at 768px and above, and a toggle ghost pill below it. Below 768px the rail
         stacks over the card. A placeholder of a different height moves the card down when the
         session lands. The Exam ghost pill carries no media query. The real Exam button shows at
         every width, so its placeholder must too. The height comes from .mt-pill, which is where
         the real buttons' height comes from too. A min-height here would drift the moment a pill
         changes. */
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
        /* Finding F17. The reading column is first in the DOM at every width, for a logical Tab
           order below 768px — see the template comment. Grid placement, and not the CSS order
           property, puts the rail back on the left here: a browser tabs in DOM order regardless
           of either one, so this choice makes no difference to the keyboard, and grid placement
           is the more direct tool for "this cell holds that item" than reordering a flow. */
        .reader__main {
          grid-column: 2;
          grid-row: 1;
        }
        .reader__rail {
          grid-column: 1;
          grid-row: 1;
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
  private readonly router = inject(Router);
  private readonly titleService = inject(Title);
  private readonly api = inject(ApiService);
  private readonly account = inject(AccountStore);
  readonly store = inject(SessionStore);

  /** True for a signed-in learner with a live count — see [METERED_STATUSES]. Issue #139: Test me
   * and Exam both spend a token, so their price shows only where a token could genuinely be
   * spent. A visitor with no account, or an account with no live count, sees no price. */
  protected readonly showTokenPrice = computed(() =>
    METERED_STATUSES.has(this.account.view()?.status ?? ''),
  );

  /**
   * The short, visible sentence for the control row: what the last finished explanation truly
   * spent, from `SessionStore.tokenResult()` — the true `remaining` before and after the account
   * read, never a guess. `''` shows nothing, and the row's own reserved height keeps that from
   * moving anything.
   *
   * The same string also reaches `FocusCardComponent`, which joins it into the one message a
   * screen reader hears alongside "The explanation is ready." — see that component's own
   * `tokenResultText` input.
   */
  protected readonly tokenResultAnnouncement = computed<string>(() => {
    const result = this.store.tokenResult();
    if (result === null) return '';
    if (!result.usedToken) return 'No token used. This text existed already.';
    const word = result.remaining === 1 ? 'token' : 'tokens';
    return `1 token used. ${result.remaining} ${word} left.`;
  });

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
  /** Codes that open a panel in place of the focus card: `SIGN_IN_REQUIRED` for `signInRequired`,
   * and the two wall codes for `subscribeRequired`. Each panel already says what happened, so a
   * banner on top of it would say the same thing twice. */
  private static readonly PANEL_CODES: ReadonlySet<string> = new Set([
    'SIGN_IN_REQUIRED',
    'TRIAL_EXHAUSTED',
    'SUBSCRIPTION_REQUIRED',
  ]);

  readonly bannerError = computed(() => {
    const failure = this.store.error();
    if (failure === null || this.store.session() === null) return null;
    if (ReaderPageComponent.PANEL_CODES.has(failure.code)) return null;
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
   * The wall code from the last refusal, or `null` when none applies.
   *
   * `TRIAL_EXHAUSTED` and `SUBSCRIPTION_REQUIRED` both open `WallPanelComponent`, and neither ever
   * reaches `bannerError`'s wait message. A trial pool does not roll over, so the wait message would
   * tell a spent trial to wait for a reset that never comes — the reason the backend answers these
   * two codes instead of `429 QUOTA_EXCEEDED`.
   */
  readonly subscribeRequired = computed<WallCode | null>(() => {
    const code = this.store.error()?.code;
    return code === 'TRIAL_EXHAUSTED' || code === 'SUBSCRIPTION_REQUIRED' ? code : null;
  });

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

    // `app.routes.ts` sets no static `title` for this route: the topic is not known until the
    // session, and then the catalogue, answer. [topicLabel] is `''` for the one tick before the
    // session resolves, and a bare "| mytetz" tab is worse than the generic title index.html sets,
    // so this effect waits for a real label before it writes one.
    effect(() => {
      const label = this.topicLabel();
      if (label.length > 0) this.titleService.setTitle(`${label} | mytetz`);
    });
  }

  explain(request: { span: SpanPayload; verb: Verb }): void {
    void this.store.explain(request.span, request.verb);
  }

  /** Ends the session on the learner's own word. `SessionStore.complete` updates the loaded
   * session in place, so the read-only card and this control's own replacement follow at once. */
  completeSession(): void {
    void this.store.complete();
  }

  /**
   * Opens a fresh session on the topic a completed one covered, and moves the reader onto it.
   *
   * A completed session stays exactly as it was — see [SessionStore.complete] — so continuing to
   * read the topic means a new session, not a reopened old one. `ApiService.createSession` is the
   * same call the catalogue page makes to start one from a topic page.
   */
  async startNewSession(): Promise<void> {
    const topicSlug = this.store.session()?.topicSlug;
    if (topicSlug === undefined) return;
    const created = await this.api.createSession(topicSlug);
    await this.router.navigate(['/learn', created.sessionId]);
  }

  /** Which quiz is open, or `null` when none is. Set by [testMe] and [exam], and cleared by
   * [closeQuiz]. */
  readonly quizKind = signal<QuizKind | null>(null);
  /** The node a Test Me quiz is scoped to. Always `null` for an Exam, which draws from the whole
   * session instead of one node. */
  readonly quizNodeId = signal<string | null>(null);

  /** Opens a quiz on the node the learner is reading right now. */
  testMe(): void {
    this.quizNodeId.set(this.store.currentNodeId());
    this.quizKind.set('TEST_ME');
  }

  /** Opens an exam over the whole session, with no one node in scope. */
  exam(): void {
    this.quizNodeId.set(null);
    this.quizKind.set('EXAM');
  }

  /** Closes the quiz panel and drops its scope, so the next quiz opened starts clean. */
  closeQuiz(): void {
    this.quizKind.set(null);
    this.quizNodeId.set(null);
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
