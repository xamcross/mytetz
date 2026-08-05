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
              class="crumb__button"
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
        align-items: baseline;
        gap: 0.25rem;
        margin: 0 0 0.75rem;
        padding: 0;
        font-size: 0.85rem;
      }
      .crumb {
        display: flex;
        align-items: baseline;
        gap: 0.25rem;
        min-width: 0;
      }
      .crumb__button {
        background: none;
        border: 0;
        padding: 0.1rem 0.2rem;
        color: #1a56db;
        cursor: pointer;
        font: inherit;
        max-width: 22ch;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      .crumb__button:disabled {
        color: #333;
        font-weight: 600;
        cursor: default;
      }
      .crumb__separator {
        color: #999;
      }
    `,
  ],
})
export class BreadcrumbComponent {
  readonly nodes = input.required<NodeView[]>();
  readonly topicLabel = input.required<string>();
  readonly navigate = output<string>();
}
