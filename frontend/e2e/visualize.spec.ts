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
        // xmlns is not decoration: a data:image/svg+xml URL is parsed as a standalone XML
        // document, and a browser renders it in an <img> only when the root carries the SVG
        // namespace. The backend's own sanitiser adds this on every document it lets through, so
        // a stub without it is not the shape the real backend ever sends — see
        // MediaRendererComponent's own KDoc for the full explanation.
        source:
          '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><title>A simple atom</title><circle cx="50" cy="50" r="30"/></svg>',
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

  // `toBeVisible()` above is true for an `<img>` element that exists in the layout, whether or
  // not the browser actually decoded the image behind its `src`. A `data:image/svg+xml` URL is
  // parsed as a standalone XML document, and a browser renders it in an `<img>` only when its
  // root carries the SVG namespace. `naturalWidth` is the one property that answers "did this
  // actually decode a picture", so this is the assertion that proves a learner really sees a
  // diagram, not only that an element sits in the DOM.
  await expect
    .poll(async () => diagram.evaluate((img: HTMLImageElement) => img.naturalWidth))
    .toBeGreaterThan(0);

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

/** What `GET /api/sessions/s1` returns for the case this suite must never actually meet: the
 * server's own sanitiser has already refused a malformed document (`SvgSanitizerTest`'s own
 * `malformed XML is refused` case), so a stub carrying one here stands in for a defect that
 * reached the browser regardless — a bad deploy, a future bug in the sanitiser. This proves the
 * browser's own behaviour on that source, real and unmocked: it raises `error`, not `load`. */
const AFTER_VISUALIZE_BROKEN: SessionView = {
  ...AFTER_VISUALIZE,
  media: {
    k1: {
      diagram: {
        kind: 'SVG',
        source: '<svg xmlns="http://www.w3.org/2000/svg"><circle cx="1" cy="1" r="1"></svg>',
      },
      image: null,
    },
  },
};

test('shows a fallback message, not a broken image, when the diagram does not decode', async ({
  page,
}) => {
  await stubCatalogueAndSession(page, AFTER_VISUALIZE_BROKEN);
  const stream = await mockExplainStream(page, 's1');

  await openQuantumPhysicsSession(page);
  await selectPhrase(page, 'focus-body', 'Quantum mechanics');
  await verb(page, 'Show me a diagram').click();

  await stream.send(sseFrame('meta', { contentKey: 'k1', cached: false }));
  await stream.send(sseFrame('delta', { t: 'A simple diagram of quantum mechanics.' }));
  await stream.send(sseFrame('done', { contentKey: 'k1', grounded: false }));
  await stream.close();

  // Scoped to the component: `.focus__streaming` also carries `role="status"` while a stream
  // runs, and this assertion must name the renderer's own fallback and not that unrelated one.
  await expect(page.locator('app-media-renderer [role="status"]')).toHaveText(
    'The diagram could not be shown.',
  );
  await expect(page.locator('app-media-renderer img.media__diagram')).toHaveCount(0);
  await expect(page.locator('app-media-renderer button.media__zoom')).toHaveCount(0);
});
