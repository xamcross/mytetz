import { Component, input, output, signal } from '@angular/core';
import { NodeView } from '../core/models';

const VERB_LABELS: Readonly<Record<string, string>> = {
  SEED: 'Topic',
  EXPLAIN: 'Explain',
  DIG_DEEPER: 'Deeper',
  BROADER_PICTURE: 'Broader',
  SIDE_VIEW: 'Side view',
  VISUALIZE: 'Visual',
};

/**
 * Every node of the session, indented by depth, with the node in focus highlighted.
 *
 * Expects [nodes] in the store's `tree()` order — depth-first from the root — because the
 * indentation is what communicates parentage here, and the session's own chronological order does
 * not match the nesting once a learner goes back and branches from an earlier node.
 *
 * ## The drawer under 768px
 *
 * The list starts collapsed and a toggle opens it; a media query at 768px hides the toggle and
 * forces the list visible, so the collapse only ever exists on narrow screens. Deliberately *not*
 * done by measuring the viewport: reading `window.matchMedia` on the render path is exactly what the
 * design spec rules out so the reader stays server-renderable for spec C. The initial state is
 * therefore chosen for the narrow case and overridden by CSS for the wide one, rather than being
 * decided in TypeScript at all.
 */
@Component({
  selector: 'app-trail-rail',
  imports: [],
  template: `
    <nav class="trail" aria-label="Session trail">
      <p class="mt-eyebrow trail__head">Your trail · {{ nodes().length }} steps</p>
      <button
        type="button"
        class="mt-pill mt-pill--ghost trail__toggle"
        [attr.aria-expanded]="!collapsed()"
        (click)="collapsed.set(!collapsed())"
      >
        {{ collapsed() ? 'Show' : 'Hide' }} trail ({{ nodes().length }})
      </button>
      <ol class="trail__list" [class.trail__list--collapsed]="collapsed()">
        @for (node of nodes(); track node.nodeId) {
          <li class="trail__row" [style.margin-left.px]="node.depth * 16">
            <button
              type="button"
              class="mt-card mt-card--flat trail__item"
              [class.trail__item--current]="node.nodeId === currentNodeId()"
              [attr.data-node-id]="node.nodeId"
              [attr.aria-current]="node.nodeId === currentNodeId() ? 'true' : null"
              (click)="navigate.emit(node.nodeId)"
            >
              <span class="trail__dot" aria-hidden="true"></span>
              <span class="trail__text">
                <span class="mt-eyebrow trail__verb">{{
                  label(node.verb) + (node.nodeId === currentNodeId() ? ' · here' : '')
                }}</span>
                <span class="trail__span">{{ node.span || topicLabel() }}</span>
              </span>
            </button>
          </li>
        }
      </ol>
    </nav>
  `,
  styles: [
    `
      :host {
        display: block;
      }
      .trail {
        display: flex;
        flex-direction: column;
        gap: 12px;
      }
      .trail__head {
        margin: 0;
      }
      .trail__list {
        list-style: none;
        margin: 0;
        padding: 0;
        display: flex;
        flex-direction: column;
        gap: 7px;
      }
      .trail__list--collapsed {
        display: none;
      }
      /* The surface, the border and the absent lift all come from .mt-card--flat. This rule adds
         the row's own layout, and the tighter radius the design gives a row. */
      .trail__item {
        width: 100%;
        text-align: left;
        display: flex;
        align-items: center;
        gap: 9px;
        padding: 11px 13px;
        border-radius: var(--mt-r-row);
        color: var(--mt-ink);
      }
      /* The four declarations below are also what .mt-pill--teal draws, and the duplication is
         deliberate. That class is a modifier of .mt-pill, and .mt-pill--teal:active changes only
         the shadow — .mt-pill itself supplies the 2px move that goes with it. On a row that is not
         a pill, the modifier would press half way. The row would also have to give up its own
         background and border shorthands to let the modifier through the cascade, which trades a
         visible duplication for a hidden dependency on specificity. */
      .trail__item--current {
        background: var(--mt-teal);
        border-color: var(--mt-teal);
        color: var(--mt-surface);
        box-shadow: var(--mt-lift-teal);
      }
      .trail__dot {
        width: 22px;
        height: 22px;
        flex: none;
        border-radius: 999px;
        background: var(--mt-amber);
      }
      .trail__text {
        display: flex;
        flex-direction: column;
        gap: 1px;
        min-width: 0;
      }
      .trail__verb {
        font-size: 10px;
      }
      .trail__item--current .trail__verb {
        color: var(--mt-chip);
      }
      .trail__span {
        font-family: var(--mt-display);
        font-size: 15px;
        font-weight: 600;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      /* Wide screens never collapse: the rail is a permanent column there, so the initial
         collapsed state — chosen for the narrow case, without measuring anything — is overridden
         here rather than in TypeScript. 768px, so the drawer and the verb picker change mode
         together. */
      @media (min-width: 768px) {
        .trail__toggle {
          display: none;
        }
        .trail__list--collapsed {
          display: flex;
        }
      }
      @media (max-width: 767px) {
        .trail__head {
          display: none;
        }
      }
    `,
  ],
})
export class TrailRailComponent {
  readonly nodes = input.required<NodeView[]>();
  readonly currentNodeId = input.required<string | null>();
  readonly topicLabel = input.required<string>();
  readonly navigate = output<string>();

  readonly collapsed = signal(true);

  label(verb: string): string {
    return VERB_LABELS[verb] ?? verb;
  }
}
