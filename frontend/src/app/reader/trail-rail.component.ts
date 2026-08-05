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
 * ## The drawer under 640px
 *
 * The list starts collapsed and a toggle opens it; a media query at 640px hides the toggle and
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
      <button
        type="button"
        class="trail__toggle"
        [attr.aria-expanded]="!collapsed()"
        (click)="collapsed.set(!collapsed())"
      >
        {{ collapsed() ? 'Show' : 'Hide' }} trail ({{ nodes().length }})
      </button>
      <ol class="trail__list" [class.trail__list--collapsed]="collapsed()">
        @for (node of nodes(); track node.nodeId) {
          <li class="trail__row" [style.padding-left.rem]="node.depth * 0.75">
            <button
              type="button"
              class="trail__item"
              [class.trail__item--current]="node.nodeId === currentNodeId()"
              [attr.data-node-id]="node.nodeId"
              [attr.aria-current]="node.nodeId === currentNodeId() ? 'true' : null"
              (click)="navigate.emit(node.nodeId)"
            >
              <span class="trail__verb">{{ label(node.verb) }}</span>
              <span class="trail__span">{{ node.span || topicLabel() }}</span>
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
        border-right: 1px solid #eee;
        padding-right: 1rem;
      }
      .trail__toggle {
        width: 100%;
        text-align: left;
        padding: 0.5rem 0.75rem;
        border: 1px solid #ddd;
        border-radius: 6px;
        background: #fafafa;
        font: inherit;
        cursor: pointer;
      }
      .trail__list {
        list-style: none;
        margin: 0.5rem 0 0;
        padding: 0;
      }
      .trail__list--collapsed {
        display: none;
      }
      .trail__item {
        display: block;
        width: 100%;
        text-align: left;
        background: none;
        border: 0;
        border-left: 2px solid #eee;
        padding: 0.35rem 0.5rem;
        font: inherit;
        font-size: 0.85rem;
        cursor: pointer;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      .trail__item--current {
        border-left-color: #1a56db;
        background: #f0f4ff;
        font-weight: 600;
      }
      .trail__verb {
        display: block;
        font-size: 0.7rem;
        text-transform: uppercase;
        letter-spacing: 0.04em;
        color: #888;
      }
      @media (min-width: 640px) {
        .trail__toggle {
          display: none;
        }
        /* Wide screens never collapse: the rail is a permanent column there, so the initial
           collapsed state — chosen for the narrow case, without measuring anything — is overridden
           here rather than in TypeScript. */
        .trail__list--collapsed {
          display: block;
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
