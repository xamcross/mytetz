package com.mytetz.graph

/**
 * Looks up one licensed image for a span, or answers null.
 *
 * `:backend:graph` holds no HTTP client and calls Wikimedia for nothing on its own — the same rule
 * `Reconciliation.reconcile`'s own `fetchState` parameter states for Freemius
 * (`backend/billing/src/main/kotlin/com/mytetz/billing/Reconciliation.kt:37-38`). A lookup that
 * fails, and a lookup with nothing to find, both answer null. `ExplanationGraph` treats both the
 * same way: serve the diagram only.
 */
typealias CommonsLookup = suspend (span: String, ancestors: List<Ancestor>) -> ImageMedia?
