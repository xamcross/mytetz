import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { ApiService } from '../core/api.service';
import { TopicSummary } from '../core/models';

/**
 * The product's entry point: `/`. Lists the curated catalogue. Each tile is a plain link to its
 * own topic page at `/topics/<slug>`, a page Ktor renders (`TopicPageHtml.kt`); the "Start with
 * this topic" control that creates a session and hands off to the reader now lives on that page,
 * not here — see `frontend/public/topic-start.js`. Issue #45 moved it there.
 *
 * Does not read `window`, `document` or `localStorage` on its render path (the `(input)` handler
 * below reads `Event.target`, which is unrelated to those globals and is available wherever the
 * event itself fires) — kept that way on purpose, per the design spec's note that the Angular
 * reader must stay SSR-safe for when spec C adds server rendering.
 *
 * Finding F9 of the design review, 2026-09-19, has no subject here any more. It reported that a
 * click on a tile disabled every tile and dropped each one to 55% opacity — a `tilesLocked()`
 * signal on a `<button>` tile. Issue #45 had already changed the tile into the plain link above,
 * before this issue started, so a click starts no session and locks no tile. This file has no
 * `tilesLocked` signal and no such opacity rule. Left out for that reason.
 */
@Component({
  selector: 'app-catalog-page',
  imports: [],
  template: `
    <main class="catalog">
      <div class="catalog__inner">
        <header class="catalog__header">
          <h1 class="catalog__title">What do you want to understand?</h1>
        </header>

        <!--
          Finding F8 of the design review. The introduction used to hold nine sentences here, and
          the search field sat below all of them. Two sentences stay; the rest moves below the
          tile grid, in .catalog__more, where a crawler still reads it and a learner does not have
          to. catalog-page.component.spec.ts asserts that .catalog__intro is the immediate sibling
          of .catalog__filter, which is an acceptance criterion of the introduction issue.
        -->
        <p class="catalog__intro">
          mytetz is a reading tool for a hard topic. Pick a topic below, from astronomy to
          psychology.
        </p>

        <div class="catalog__filter">
          <label class="mt-sr-only" for="topic-filter">Filter topics</label>
          <div class="catalog__row">
            <input
              id="topic-filter"
              class="catalog__search"
              type="search"
              placeholder="Search by title, category, or summary…"
              [value]="query()"
              (input)="onQueryInput($event)"
            />
            <div class="catalog__cats" role="group" aria-label="Filter by category">
              @for (c of categories(); track categoryId(c)) {
                <button
                  type="button"
                  class="mt-pill catalog__cat"
                  [class.mt-pill--teal]="category() === c"
                  [attr.data-category]="categoryId(c)"
                  [attr.aria-pressed]="category() === c"
                  (click)="category.set(c)"
                >
                  {{ categoryLabel(c) }}
                </button>
              }
            </div>
            @if (query() || category() !== null) {
              <!-- Finding F18 of the design review. Firefox draws no native clear control for
                   type="search", and the old reset showed only once the filter already matched
                   nothing. This one shows next to the pill row the moment either filter holds a
                   value, so a learner with one match still has a way back to the whole catalogue. -->
              <button
                type="button"
                class="mt-pill mt-pill--ghost catalog__clear"
                (click)="clearFilters()"
              >
                Clear the filters
              </button>
            }
          </div>
        </div>

        @if (topicsLoading()) {
          <p class="mt-sr-only" role="status">Loading topics…</p>
          <ul class="topics" aria-hidden="true">
            @for (i of skeletons; track i) {
              <li class="topic topic--skeleton mt-card">
                <span class="mt-skeleton bar bar--eyebrow"></span>
                <span class="mt-skeleton bar bar--title"></span>
                <span class="mt-skeleton bar bar--line"></span>
                <span class="mt-skeleton bar bar--line bar--short"></span>
              </li>
            }
          </ul>
        } @else if (topicsError(); as loadError) {
          <div class="mt-card mt-card--error banner banner--error" role="alert">
            <p class="banner__message">{{ loadError }}</p>
            <button
              type="button"
              class="mt-pill mt-pill--coral banner__retry-button"
              (click)="loadTopics()"
            >
              Retry
            </button>
          </div>
        } @else {
          <ul class="topics">
            @for (t of filteredTopics(); track t.slug; let i = $index) {
              <!--
                Animation G of the design review. The tile drops in and settles, with a delay that
                grows per tile and stops after six — a twelve-topic filter must not become a slow
                wave. Never on .catalog__cat: layout.spec.ts compares the top edge of every pill,
                and a vertical transform there would make that comparison flaky.
              -->
              <li class="topic" animate.enter="topic--in" [style.--i]="i">
                <a
                  class="mt-card topic__tile"
                  [attr.href]="'/topics/' + t.slug"
                  [attr.data-slug]="t.slug"
                >
                  <!-- Every tile's category is --mt-muted. The design gives the first tile a coral
                       eyebrow, and the code drops it: a coral retry pill is reachable in this same
                       view, and §3.3 allows one coral element. The eyebrow is decoration and the
                       pill is the action, so the pill keeps the accent. -->
                  <span class="mt-eyebrow topic__category">{{ t.category }}</span>
                  <h2 class="topic__title">{{ t.title }}</h2>
                  <p class="topic__summary">{{ t.summary }}</p>
                </a>
              </li>
            } @empty {
              <li class="topics__empty mt-card mt-card--dashed">
                @if (query() || category() !== null) {
                  <h2 class="topics__empty-title">Nothing under that name yet.</h2>
                  <p class="topics__empty-body">
                    The dashboard is {{ topics().length }} hand-written topics for now. Try a
                    shorter word, or clear the category.
                  </p>
                  <button type="button" class="mt-pill mt-pill--ghost" (click)="clearFilters()">
                    Clear the filters
                  </button>
                } @else {
                  <h2 class="topics__empty-title">No topics yet.</h2>
                  <p class="topics__empty-body">The dashboard is empty. Please come back later.</p>
                }
              </li>
            }
          </ul>
        }

        <!--
          Finding F8's own second half. The rest of the introduction lands here, below the tile
          grid, so the page still carries every sentence for a crawler that runs the script, and a
          learner meets the search field right after two sentences instead of nine.
        -->
        <p class="catalog__more">
          Each topic opens with one short passage. Read the passage. Highlight a word or phrase you
          do not understand. mytetz writes a short explanation for that phrase. The explanation
          opens next to the passage. You can highlight a word inside the explanation too. Each
          highlight opens a new explanation. You choose how many times you do this. The dashboard
          holds twelve subject areas: astronomy, biology, chemistry, computer science, earth
          science, economics, history, linguistics, mathematics, philosophy, physics, and
          psychology. Use the search box or a category filter to find a topic fast.
        </p>

        <!--
          A plain href, and not a routerLink: /guides is a static HTML file under
          frontend/public/guides, and app.routes.ts has no 'guides' path, so a routerLink would
          reach the wildcard route and open NotFoundPageComponent.
        -->
        <p class="catalog__guides">
          Do you want a method first? Read the <a href="/guides">study guides</a>.
        </p>
      </div>
    </main>
  `,
  styles: [
    `
      :host {
        display: block;
      }
      .catalog {
        padding: 36px 40px;
      }
      .catalog__inner {
        max-width: 1120px;
        margin: 0 auto;
        display: flex;
        flex-direction: column;
        gap: 16px;
      }
      .catalog__title {
        font-size: 34px;
        line-height: 1.15;
      }
      .catalog__intro,
      .catalog__more {
        margin: 0;
        max-width: 62ch;
        font-size: 16px;
        line-height: 1.6;
        font-weight: 500;
        color: var(--mt-muted);
        text-wrap: pretty;
      }
      .catalog__guides {
        margin: 0;
        font-size: 16px;
        line-height: 1.6;
        font-weight: 500;
        color: var(--mt-muted);
      }
      /* F18. The button sits right after the pill row, inside the same column, so it reads as
         part of the filter and not as a second, separate action. */
      .catalog__clear {
        align-self: flex-start;
      }
      /* The search takes its own line, and the categories take the next one.
         The design draws the two side by side, because its sample data has four categories. The
         real catalogue publishes twelve, so one line cannot hold both: the pills overflow the
         page, and the search — the only item that can shrink — collapses to nothing. */
      .catalog__row {
        display: flex;
        flex-direction: column;
        gap: 12px;
        align-items: stretch;
      }
      .catalog__search {
        width: 100%;
        /* A search field wider than this reads as a text area. */
        max-width: 520px;
        padding: 14px 18px;
        /* --mt-edge, not --mt-border: this is a control edge, and SC 1.4.11 needs 3:1. */
        border: var(--mt-border-w) solid var(--mt-edge);
        border-radius: var(--mt-r-row);
        background: var(--mt-surface);
        color: var(--mt-ink);
        font: inherit;
        font-size: 15px;
        font-weight: 600;
      }
      .catalog__search::placeholder {
        color: var(--mt-muted);
      }
      .catalog__search:focus-visible {
        border-color: var(--mt-teal);
      }
      /* Wrap, so every category stays reachable. A row that scrolls sideways on a wide screen
         hides a category behind a gesture nobody looks for. */
      .catalog__cats {
        display: flex;
        flex-wrap: wrap;
        gap: 8px;
      }
      .topics {
        list-style: none;
        padding: 0;
        margin: 6px 0 0;
        display: grid;
        grid-template-columns: 1fr;
        gap: 16px;
      }
      .topic {
        display: flex;
        min-width: 0;
      }
      /* Animation G of the design review. A tile drops in and settles on load and on every
         filter change, with a delay that grows per tile and stops after six — a twelve-topic
         filter must not turn into a slow wave. Never on .catalog__cat: two layout tests compare
         the top edge of every pill, and a vertical transform there would make both flaky. */
      .topic--in {
        animation: tile-in var(--mt-dur-state) var(--mt-ease-out) both;
        animation-delay: calc(min(var(--i), 5) * 24ms);
      }
      @keyframes tile-in {
        from {
          opacity: 0;
          transform: translateY(var(--mt-move-far));
        }
        to {
          opacity: 1;
          transform: none;
        }
      }
      /* A learner who asks for less motion still sees each tile arrive, with no stagger left to
         wait through. --mt-dur-state is already 1ms under reduced motion (styles.css), but the
         24ms-per-tile delay above is a literal value and not a token, so it needs its own
         override here — in this file, because #102 found a component's own rule always outranks
         a same-class rule in the global stylesheet. */
      @media (prefers-reduced-motion: reduce) {
        .topic--in {
          animation-delay: 0ms;
        }
      }
      .topic__tile {
        position: relative;
        width: 100%;
        text-align: left;
        padding: 20px;
        display: flex;
        flex-direction: column;
        gap: 6px;
        color: inherit;
        text-decoration: none;
        transition:
          transform var(--mt-dur-press) var(--mt-ease-press),
          box-shadow var(--mt-dur-press) var(--mt-ease-press);
      }
      /* (hover: hover) and not a plain :hover: see styles.css's own comment on .mt-pill:hover for
         why a touch screen needs this guard. */
      @media (hover: hover) {
        .topic__tile:hover {
          transform: translateY(-2px);
          box-shadow: var(--mt-lift-hover);
        }
      }
      .topic__tile:active {
        transform: translateY(2px);
        box-shadow: var(--mt-press);
      }
      .topic__title {
        font-size: 23px;
      }
      .topic__summary {
        margin: 0;
        font-size: 14px;
        line-height: 1.55;
        font-weight: 500;
        color: var(--mt-muted);
        text-wrap: pretty;
      }
      .topic--skeleton {
        flex-direction: column;
        gap: 10px;
        padding: 20px;
      }
      .bar {
        display: block;
        height: 14px;
      }
      .bar--eyebrow {
        width: 30%;
        height: 10px;
      }
      .bar--title {
        width: 60%;
        height: 22px;
      }
      .bar--line {
        width: 100%;
      }
      .bar--short {
        width: 72%;
      }
      .topics__empty {
        display: flex;
        flex-direction: column;
        align-items: flex-start;
        gap: 10px;
        padding: 24px;
      }
      .topics__empty-title {
        font-size: 22px;
      }
      .topics__empty-body {
        margin: 0;
        font-size: 15px;
        line-height: 1.55;
        font-weight: 500;
        color: var(--mt-muted);
        max-width: 52ch;
        text-wrap: pretty;
      }
      .banner {
        padding: 18px 20px;
        display: flex;
        flex-direction: column;
        align-items: flex-start;
        gap: 10px;
      }
      .banner__message {
        margin: 0;
        font-size: 15px;
        line-height: 1.55;
        font-weight: 500;
      }
      @media (min-width: 768px) {
        .topics {
          grid-template-columns: repeat(2, 1fr);
        }
      }
      @media (min-width: 1120px) {
        .topics {
          grid-template-columns: repeat(3, 1fr);
        }
      }
      @media (max-width: 767px) {
        .catalog {
          padding: 24px 20px;
        }
        .catalog__title {
          font-size: 26px;
        }
        .catalog__search {
          max-width: none;
        }
        /* Twelve wrapped pills push the first tile off a 390px screen. The row scrolls sideways
           here instead, which costs a gesture but keeps a topic in view. */
        .catalog__cats {
          flex-wrap: nowrap;
          overflow-x: auto;
          padding-bottom: 4px;
        }
        .catalog__cat {
          flex: none;
        }
      }
    `,
  ],
})
export class CatalogPageComponent implements OnInit {
  private readonly api = inject(ApiService);

  readonly topics = signal<TopicSummary[]>([]);
  readonly query = signal('');
  readonly topicsLoading = signal(true);
  readonly topicsError = signal<string | null>(null);

  /**
   * The chosen category, or `null` for every category.
   *
   * The value is `null` and not the string `'All'`. A catalogue can publish a category with the
   * name "All". That category must not collide with the control that clears the filter.
   */
  readonly category = signal<string | null>(null);

  /** `null` first, then each distinct category of the loaded topics, in alphabetical order. */
  readonly categories = computed<Array<string | null>>(() => {
    const distinct = [...new Set(this.topics().map((t) => t.category))];
    distinct.sort((a, b) => a.localeCompare(b));
    return [null, ...distinct];
  });

  /**
   * The filter runs on the client, deliberately. `?q=` exists on the backend (Task 1.3). But
   * Slice 1's whole catalogue is about 29 hand-curated topics, and `loadTopics()` already fetches
   * every one. A local match is then instant, and it issues no more requests.
   *
   * The category filter is free for the same reason: `TopicSummary.category` arrives with every
   * topic.
   *
   * The two filters combine with AND. Revisit if Slice 5 grows the catalogue to the few hundred
   * topics the design spec anticipates.
   */
  readonly filteredTopics = computed(() => {
    const q = this.query().trim().toLowerCase();
    const cat = this.category();
    return this.topics().filter((t) => {
      if (cat !== null && t.category !== cat) return false;
      if (q === '') return true;
      return (
        t.title.toLowerCase().includes(q) ||
        t.category.toLowerCase().includes(q) ||
        t.summary.toLowerCase().includes(q)
      );
    });
  });

  categoryId(category: string | null): string {
    return category ?? '__all__';
  }

  categoryLabel(category: string | null): string {
    return category ?? 'All';
  }

  /** Six placeholders, which is two full rows of the widest grid. */
  readonly skeletons = [0, 1, 2, 3, 4, 5];

  clearFilters(): void {
    this.query.set('');
    this.category.set(null);
  }

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
}
