import { Component, input, output } from '@angular/core';
import { NodeView } from '../core/models';

/**
 * `Topic › span › span` — the path from the session's root to the node in focus.
 *
 * This is the same list the backend's `ContextChain.pathTo` assembles for the prompt, which is the
 * point: what the learner sees as their trail *is* the context the model was given, so the two
 * cannot drift.
 *
 * The root node has no span of its own (it is the topic's seed), so its crumb shows [topicLabel].
 */
@Component({
  selector: 'app-breadcrumb',
  imports: [],
  template: `
    <nav class="crumbs" aria-label="Explanation trail">
      <ol class="crumbs__list">
        @for (node of nodes(); track node.nodeId; let last = $last) {
          <li class="crumb">
            <button
              type="button"
              class="mt-chip crumb__button"
              [class.mt-chip--teal]="last"
              [attr.data-node-id]="node.nodeId"
              [attr.aria-current]="last ? 'page' : null"
              [disabled]="last"
              (click)="navigate.emit(node.nodeId)"
            >
              {{ node.parentNodeId === null ? topicLabel() : node.span }}
            </button>
            @if (!last) {
              <span class="crumb__separator" aria-hidden="true">›</span>
            }
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
      .crumbs__list {
        list-style: none;
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 6px;
        margin: 0 0 16px;
        padding: 0;
      }
      .crumb {
        display: flex;
        align-items: center;
        gap: 6px;
        min-width: 0;
      }
      .crumb__button {
        max-width: 22ch;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        display: block;
      }
      .crumb__button:disabled {
        cursor: default;
        opacity: 1;
      }
      .crumb__separator {
        color: var(--mt-faint);
        font-weight: 800;
      }
    `,
  ],
})
export class BreadcrumbComponent {
  readonly nodes = input.required<NodeView[]>();
  readonly topicLabel = input.required<string>();
  readonly navigate = output<string>();
}
