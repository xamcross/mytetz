import { test, expect, type Page } from '@playwright/test';
import type { SessionView } from '../src/app/core/models';
import {
  SEED,
  mockExplainStream,
  openQuantumPhysicsSession,
  selectPhrase,
  sseFrame,
  stubCatalogueAndSession,
} from './support';

/** A verb inside the picker, and only inside it. See `learn.spec.ts`'s own copy of this helper.
 * That file states why the scope is load-bearing. */
function verb(page: Page, name: string) {
  return page.locator('[role="dialog"]').getByRole('button', { name, exact: true });
}

const ROOT_NODE: SessionView['nodes'][number] = {
  nodeId: 'n0',
  parentNodeId: null,
  explanationKey: 'k0',
  span: '',
  verb: 'SEED',
  variant: 0,
  depth: 0,
};

/** What `GET /api/sessions/s1` returns once the model has drawn a diagram for "Quantum
 * mechanics": the same root plus one `VISUALIZE` node, whose media carries a diagram and no
 * image — the degradation case every layer below the client already guarantees. */
const AFTER_VISUALIZE: SessionView = {
  sessionId: 's1',
  topicSlug: 'quantum-physics',
  rootNodeId: 'n0',
  currentNodeId: 'n1',
  nodes: [
    ROOT_NODE,
    {
      nodeId: 'n1',
      parentNodeId: 'n0',
      explanationKey: 'k1',
      span: 'Quantum mechanics',
      verb: 'VISUALIZE',
      variant: 0,
      depth: 1,
    },
  ],
  status: 'ACTIVE',
  explanations: { k0: SEED, k1: 'A simple diagram of quantum mechanics.' },
  media: {
    k1: {
      diagram: {
        kind: 'SVG',
        source:
          '<svg viewBox="0 0 100 100"><title>A simple atom</title><circle cx="50" cy="50" r="30"/></svg>',
      },
      image: null,
    },
  },
};

/**
 * The one scenario Task 15 asks for: pick Visualize, and a diagram appears on the focus card.
 * Every layer below the client already guarantees the diagram-only shape shown here, so this is
 * the one case worth stubbing end to end — see `docs/superpowers/plans/2026-09-19-visualize.md`,
 * Task 15.
 */
test('choosing Show me a diagram shows a diagram on the focus card', async ({ page }) => {
  await stubCatalogueAndSession(page, AFTER_VISUALIZE);
  const stream = await mockExplainStream(page, 's1');

  await openQuantumPhysicsSession(page);
  await selectPhrase(page, 'focus-body', 'Quantum mechanics');
  await verb(page, 'Show me a diagram').click();

  // Decision 2's own consequence: a structured call answers once, so the client sees the whole
  // sentence as one Delta rather than word by word.
  await stream.send(sseFrame('meta', { contentKey: 'k1', cached: false }));
  await stream.send(sseFrame('delta', { t: 'A simple diagram of quantum mechanics.' }));
  await stream.send(sseFrame('done', { contentKey: 'k1', grounded: false }));
  await stream.close();

  const diagram = page.locator('app-media-renderer img.media__diagram');
  await expect(diagram).toBeVisible();
  await expect(diagram).toHaveAttribute('src', /^data:image\/svg\+xml/);
  await expect(diagram).toHaveAttribute('alt', 'A simple atom');

  // The requirements ask for a zoomable diagram. One button toggles the frame's own class; the
  // real width change is a unit-test concern, so this only proves the control reaches the DOM
  // and does its one job — see media-renderer.component.spec.ts for the width assertion itself.
  // Located by its own class, not its accessible name: the button's name changes with the state
  // this test is about to change ("View larger" becomes "Fit to card"), so a name-based locator
  // would stop matching the instant the click this test makes actually works.
  const zoomButton = page.locator('app-media-renderer button.media__zoom');
  const frame = page.locator('app-media-renderer .media__diagram-frame');
  await expect(frame).not.toHaveClass(/media__diagram-frame--zoomed/);
  await expect(zoomButton).toHaveAttribute('aria-pressed', 'false');

  await zoomButton.click();

  await expect(frame).toHaveClass(/media__diagram-frame--zoomed/);
  await expect(zoomButton).toHaveAttribute('aria-pressed', 'true');
  await expect(zoomButton).toHaveText('Fit to card');
});
