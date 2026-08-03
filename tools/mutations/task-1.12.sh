#!/usr/bin/env bash
#
# Mutations for Task 1.12 — session routes, the streaming explain endpoint, and the spend controls
# that hang off it. Run with: ./tools/mutation-test.sh tools/mutations/task-1.12.sh
#
# The three properties this task exists to establish are the first three sections. Each of them was
# broken in the implementation this task was handed, and each was broken in a way the briefed test
# suite stayed green through — so a survivor in those sections is not a missing test, it is the same
# defect coming back.

SR=backend/api/src/main/kotlin/com/mytetz/api/SessionRoutes.kt
EM=backend/api/src/main/kotlin/com/mytetz/api/ErrorMapping.kt
CO=backend/api/src/main/kotlin/com/mytetz/api/Components.kt
EG=backend/graph/src/main/kotlin/com/mytetz/graph/ExplanationGraph.kt
EMT=backend/api/src/test/kotlin/com/mytetz/api/ErrorMappingTest.kt
CT=backend/api/src/test/kotlin/com/mytetz/api/ComponentsTest.kt
SS=backend/session/src/main/kotlin/com/mytetz/session/SessionService.kt
API=:backend:api:test
GRAPH=:backend:graph:test

# ------------------------------------------------- property 1: a refusal must generate nothing

# The shape of the original defect: the gate returns a verdict and nothing acts on it.
mutate M01-quota-verdict-never-refuses $SR \
  "s=s.replace('    return if (becameFree()) null else refusal','    return null',1)" $API

mutate M02-explain-never-consults-the-quota $SR \
  "s=s.replace('        if (!plan.cached) {\n            val refusal = quota.refusalFor(principal) {','        if (false) {\n            val refusal = quota.refusalFor(principal) {',1)" $API

mutate M03-create-never-consults-the-quota $SR \
  "s=s.replace('        if (sessions.createWillGenerate(request.topicSlug)) {','        if (false) {',1)" $API

# M04 was `if (refusal != null) { respondRefusal(refusal) }` with the `return@post` removed — the
# straightforward way to write "refuse, then generate anyway", which is the defect this task fixed.
# It SURVIVED, and the reason is the point rather than a gap: with the gate ahead of the response,
# dropping the return makes the handler call `respond` twice, Ktor raises on the second, and the
# refusal already on the wire stands. The defect is no longer *representable* as a one-line edit,
# because expressing it needs the gate moved to after the stream opens. M01 and M02 cover the
# property directly — both assert the model was not called — so it is not left unpinned. Kept as a
# comment rather than deleted, so nobody re-adds it and reads the survival as a missing test.

mutate M05-spend-limit-refusal-is-a-200 $SR \
  "s=s.replace('        QuotaDecision.SpendLimitReached -> Refusal(\n            HttpStatusCode.ServiceUnavailable,','        QuotaDecision.SpendLimitReached -> Refusal(\n            HttpStatusCode.OK,',1)" $API

mutate M06-quota-refusal-drops-retry-after $SR \
  "s=s.replace('    refusal.error.retryAfter?.let { response.headers.append(HttpHeaders.RetryAfter, it.toString()) }','',1)" $API

mutate M07-principal-exceeded-carries-no-wait $SR \
  "s=s.replace('                retryAfter = decision.retryAfterSeconds,','                retryAfter = null,',1)" $API

# ------------------------------------------------- property 2: a cache hit survives the breaker

# M08 was `if (!plan.cached)` -> `if (true)`: consult the quota even for a plan that is already a
# hit. It SURVIVED, and it is an **equivalent mutant**. `refusalFor` re-evaluates `becameFree` before
# returning any refusal, and for a hit that predicate is true, so the verdict is discarded and the
# request proceeds — identical behaviour, one extra ledger read. Worth stating plainly, because it
# says which line is load bearing: the `!plan.cached` guard is an optimisation, and the thing that
# keeps hits alive during a spend incident is the re-check. `a tripped spend breaker still serves
# cached explanations` pins the behaviour; M09 pins the re-check that produces it.

# The stale-plan re-check. SessionService's KDoc is explicit that a miss frequently becomes a hit
# between prepare and execution, and that refusing it is a real failure during exactly the incident
# the breaker exists for.
mutate M09-stale-plan-is-never-re-read $SR \
  "s=s.replace('                    plan = prepare()\n                    plan.cached','                    plan.cached',1)" $API

mutate M10-create-gate-does-not-re-read $SR \
  "s=s.replace('quota.refusalFor(principal) { !sessions.createWillGenerate(request.topicSlug) }','quota.refusalFor(principal) { false }',1)" $API

# ------------------------------------------------- property 3: spend is recorded once, correctly

mutate M11-spend-is-billed-from-the-document $SR \
  "s=s.replace('            if (chunk is GraphChunk.Spent) spentMicros += chunk.costMicros','            if (chunk is GraphChunk.Done) spentMicros += chunk.explanation.costMicros',1)" $API

mutate M12-spend-is-never-recorded $SR \
  "s=s.replace('        withContext(NonCancellable) { quota.recordSpend(principal, spentMicros) }\n','',1)" $API

mutate M13-seed-spend-is-never-recorded $SR \
  "s=s.replace('            withContext(NonCancellable) { quota.recordSpend(principal, costMicros) }\n','',1)" $API

mutate M14-zero-cost-still-spends-an-allowance $SR \
  "s=s.replace('    if (spentMicros <= 0) return','    if (spentMicros < 0) return',1)" $API

mutate M15-generator-reports-the-winners-cost $EG \
  "s=s.replace('        emit(GraphChunk.Spent(costMicros))','        emit(GraphChunk.Spent(repository.findByKey(key)?.costMicros ?: costMicros))',1)" $GRAPH

mutate M16-a-cache-hit-claims-the-documents-cost $EG \
  "s=s.replace('            repository.incrementRequestCount(key)\n            emit(GraphChunk.Delta(stored.body))','            repository.incrementRequestCount(key)\n            emit(GraphChunk.Spent(stored.costMicros))\n            emit(GraphChunk.Delta(stored.body))',1)" $GRAPH

mutate M17-create-reports-no-spend $SS \
  "s=s.replace('                is GraphChunk.Spent -> onSpend(chunk.costMicros)','                is GraphChunk.Spent -> Unit',1)" $API

# ------------------------------------------------- ownership

mutate M18-explain-checks-no-ownership $SR \
  "s=s.replace('        sessions.requireOwnedBy(sessionId, principal)\n','',1)" $API

mutate M19-read-checks-no-ownership $SR \
  "s=s.replace('        sessions.requireOwnedBy(id, principal)\n','',1)" $API

mutate M20-ownership-checked-after-prepare $SR \
  "s=s.replace('        sessions.requireOwnedBy(sessionId, principal)\n\n        suspend fun prepare()','        suspend fun prepare()',1).replace('        var plan = prepare()','        var plan = prepare()\n        sessions.requireOwnedBy(sessionId, principal)',1)" $API

mutate M21-not-yours-is-distinguishable-from-absent $SR \
  "s=s.replace('    if (ownerOf(sessionId) != principal.value) throw SessionNotFoundException(sessionId)','    if (ownerOf(sessionId) != principal.value) throw ResourceNotFoundException(\"session \$sessionId is not yours\")',1)" $API

mutate M22-cookie-presence-is-treated-as-ownership $SR \
  "s=s.replace('    if (ownerOf(sessionId) != principal.value) throw SessionNotFoundException(sessionId)','    if (ownerOf(sessionId) == null) throw SessionNotFoundException(sessionId)',1)" $API

# ------------------------------------------------- cancellation, and the in-stream taxonomy

# The third appearance of this defect in this project, after Mongo.ping() and ExplanationGraph.
mutate M23-cancellation-swallowed-as-an-error $SR \
  "s=s.replace('    } catch (e: CancellationException) {\n        throw e\n    } catch (e: Exception) {\n        send(ServerSentEvent(event = \"error\"','    } catch (e: Exception) {\n        send(ServerSentEvent(event = \"error\"',1)" $API

mutate M24-stream-errors-lose-the-taxonomy $EM \
  "s=s.replace('internal fun sseErrorFor(cause: Throwable): ApiError = when (cause) {','internal fun sseErrorFor(cause: Throwable): ApiError = when (cause) {\n    is SessionNotFoundException -> ApiError(\"INTERNAL\", \"unexpected error\")\n    is CorruptSessionException -> ApiError(\"INTERNAL\", \"unexpected error\")',1)" $API

mutate M25-stream-errors-echo-the-upstream $EM \
  "s=s.replace('        ApiError(\"GENERATION_FAILED\", \"the explanation could not be generated; try again\")','        ApiError(\"GENERATION_FAILED\", cause.message.orEmpty())',1)" $API

mutate M26-corruption-mid-stream-raises-no-alert $EM \
  "s=s.replace('    is CorruptSessionException -> {\n        logCorruptSession(cause)','    is CorruptSessionException -> {',1)" $API

mutate M27-a-failed-generation-still-reports-done $SR \
  "s=s.replace('        send(ServerSentEvent(event = \"error\", data = json.encodeToString(sseErrorFor(e))))','        send(ServerSentEvent(event = \"done\", data = \"{}\"))',1)" $API

# ------------------------------------------------- the wire shape

mutate M28-superseded-is-sent-as-a-delta $SR \
  "s=s.replace('    is GraphChunk.Superseded -> ServerSentEvent(\n        event = \"superseded\",','    is GraphChunk.Superseded -> ServerSentEvent(\n        event = \"delta\",',1)" $API

mutate M29-meta-is-not-announced $SR \
  "s=s.replace('    is GraphChunk.Meta -> ServerSentEvent(\n        event = \"meta\",','    is GraphChunk.Meta -> ServerSentEvent(\n        event = \"note\",',1)" $API

mutate M30-done-does-not-name-the-content-key $SR \
  "s=s.replace('        data = json.encodeToString(DoneEvent(chunk.explanation.key, chunk.explanation.grounded)),','        data = json.encodeToString(DoneEvent(\"\", chunk.explanation.grounded)),',1)" $API

mutate M31-meta-always-claims-a-miss $SR \
  "s=s.replace('MetaEvent(contentKey = chunk.contentKey, cached = chunk.cached)','MetaEvent(contentKey = chunk.contentKey, cached = false)',1)" $API

# ------------------------------------------------- caching, buffering and the bounds

mutate M32-stream-becomes-cacheable $SR \
  "s=s.replace('        call.response.headers.append(HttpHeaders.CacheControl, \"no-store, no-transform\")\n','',1)" $API

mutate M33-accel-buffering-header-dropped $SR \
  "s=s.replace('        call.response.headers.append(\"X-Accel-Buffering\", \"no\")\n','',1)" $API

mutate M34-session-creation-is-unbounded $SR \
  "s=s.replace('        if (!sessionLimiter.tryAcquire(caller)) {','        if (false) {',1)" $API

mutate M35-session-limit-keyed-on-the-principal $SR \
  "s=s.replace('        val caller = ClientAddress.of(call, clientAddresses)','        val caller = Principals.resolve(call, cookies).value',1)" $API

mutate M36-body-size-unbounded $SR \
  "s=s.replace('    if (declared != null && declared <= MAX_SESSION_BODY_BYTES) return true','    if (true) return true',1)" $API

# ------------------------------------------------- the session service seam

mutate M37-ownerOf-answers-for-the-wrong-field $SS \
  "s=s.replace('suspend fun ownerOf(sessionId: String): String? = sessions.findById(sessionId)?.principalId','suspend fun ownerOf(sessionId: String): String? = sessions.findById(sessionId)?.topicSlug',1)" $API

# ------------------------------------------------- review round 1: the two Criticals

AP=backend/api/src/main/kotlin/com/mytetz/api/Application.kt
LC=backend/llm/src/testFixtures/kotlin/com/mytetz/llm/FakeLlmClient.kt

# Critical 1. NOT a deletion — M12 already covers that, and the happy path kills it. This *moves* the
# recording back to where it was: after `collect`, so that anything throwing between the cost
# becoming known and the collection returning (the terminal `send`, and `appendNode`, which runs
# inside the flow after the last emit) skips it. The tokens are already bought at that point, and
# SPEND_UNRECORDED does not fire either, because the function that raises it never runs.
mutate M38-spend-recorded-after-collect-instead-of-finally $SR \
  "s=s.replace('    } finally {\n        withContext(NonCancellable) { quota.recordSpend(principal, spentMicros) }\n    }','    }',1).replace('            send(eventFor(chunk))\n        }\n    } catch (e: CancellationException) {','            send(eventFor(chunk))\n        }\n        quota.recordSpend(principal, spentMicros)\n    } catch (e: CancellationException) {',1)" $API

# Critical 2. Reading the lazy at routing time builds AnthropicLlmClient inside module(), so a
# missing ANTHROPIC_API_KEY stops the process booting and takes /api/health and topic browsing with
# it — reversing a decision Components' KDoc argues for at length.
mutate M39-session-service-resolved-at-routing-time $AP \
  "s=s.replace('            sessions = { components.sessions },','            sessions = components.sessions.let { built -> { built } },',1)" $API

# The re-check swallowing its own failure, so a Mongo blip inside it answers 500 on a request the
# cache could have served, instead of leaving the clean refusal in place.
mutate M40-quota-recheck-failure-becomes-the-answer $SR \
  "s=s.replace('                } catch (e: Exception) {\n                    log.info(\"the quota re-check could not be evaluated; keeping the refusal\", e)\n                    false\n                }','                } catch (e: Exception) {\n                    throw e\n                }',1)" $API

# RATE_LIMITED losing the header again.
mutate M41-rate-limit-refusal-drops-retry-after $SR \
  "s=s.replace('            call.respondRefusal(\n                Refusal(\n                    HttpStatusCode.TooManyRequests,\n                    ApiError(\n                        code = \"RATE_LIMITED\",\n                        message = \"too many sessions started; try again later\",\n                        retryAfter = SESSION_WINDOW_MILLIS / 1000,\n                    ),\n                )\n            )','            call.respond(\n                HttpStatusCode.TooManyRequests,\n                ApiError(\"RATE_LIMITED\", \"too many sessions started; try again later\", SESSION_WINDOW_MILLIS / 1000),\n            )',1)" $API

# The streaming mapping losing an arm the status mapping registers. M24 covered the session types;
# this covers the mechanism that is supposed to NOTICE such a loss.
mutate M42-stream-mapping-drops-a-registered-arm $EM \
  "s=s.replace('    is ResourceNotFoundException -> ApiError(\"NOT_FOUND\", cause.message.orEmpty())\n','',1)" $API

# The fixture hook itself, since two tests now depend on it firing exactly once mid-stream.
mutate M43-mid-stream-hook-never-fires $LC \
  "s=s.replace('            if (index == 0) afterFirstDelta?.invoke()','',1)" $API

# ------------------------------------------------- re-review: the same window, one layer down

# The Critical. NOT a deletion: this MOVES the announcement past the three things that can throw
# after the tokens are billed — the validator, insertIfAbsent, and the correction emit. Every one of
# those is a path on which the money is gone and the answer never arrives.
mutate M44-cost-announced-after-everything-that-can-throw $EG \
  "s=s.replace('        emit(GraphChunk.Spent(costMicros))\n\n        // The validator','\n        // The validator',1).replace('        return GraphChunk.Done(winner)','        emit(GraphChunk.Spent(costMicros))\n        return GraphChunk.Done(winner)',1)" $GRAPH

# The same shape on the seed path: reading the cost off the return value loses every generation that
# was billed and then rejected, because on that path there is no return value.
mutate M45-seed-spend-read-from-the-result-not-the-flow $SS \
  "s=s.replace('                is GraphChunk.Spent -> onSpend(chunk.costMicros)','                is GraphChunk.Spent -> spentOnSeed = chunk.costMicros',1).replace('        var seed: Explanation? = null','        var seed: Explanation? = null\n        var spentOnSeed = 0L',1).replace('        sessions.insert(session)','        sessions.insert(session)\n        onSpend(spentOnSeed)',1)" $API

# Spend put on the wire. It is this server's accounting, not the learner's.
mutate M46-spend-leaks-onto-the-wire $SR \
  "s=s.replace('    is GraphChunk.Spent -> null','    is GraphChunk.Spent -> ServerSentEvent(event = \"spent\", data = chunk.costMicros.toString())',1)" $API

# The same move as M44, run against the API module, because the property it breaks is a money
# property and the ledger assertions live there. M44 runs :backend:graph:test and so only ever
# exercises the graph's own contract; this one reaches the test that says the ledger must move even
# when the generation is rejected. Same edit, different evidence.
mutate M49-cost-announced-too-late-seen-from-the-ledger $EG \
  "s=s.replace('        emit(GraphChunk.Spent(costMicros))\n\n        // The validator','\n        // The validator',1).replace('        return GraphChunk.Done(winner)','        emit(GraphChunk.Spent(costMicros))\n        return GraphChunk.Done(winner)',1)" $API

# ------------------------------------------------- the two hardenings, mutated where they can bite
#
# The first attempt at these mutated the ASSERTIONS — weakening `assertEquals(REGISTERED_..., n)` to
# a tautology, and deleting `awaitReady`. Both SURVIVED, and they had to: the harness measures
# whether tests detect a change in PRODUCTION, and nothing detects a test that has been made weaker.
# A mutation of test code can only ever survive. Replaced with the production changes each hardening
# exists to notice.

# A registration added to installErrorMapping with no matching arm in sseErrorFor. This is the drift
# the one-file-two-functions argument rests on, and the checked-in count is what makes a PARTIAL
# scan fail loudly instead of quietly reporting coverage it never checked.
mutate M47-a-registration-drifts-away-from-the-stream-mapping $EM \
  "s=s.replace('        exception<SpanMismatchException> { call, cause ->','        exception<ArithmeticException> { call, _ -> call.respond(HttpStatusCode.BadRequest, INVALID_REQUEST) }\n        exception<SpanMismatchException> { call, cause ->',1)" $API

# A bootstrap that touches the session service. That forces the lazy model client, so on a keyless
# deployment bootstrap throws — and the throw is swallowed and logged as BOOTSTRAP_FAILED, leaving
# an instance serving with no indexes and no catalogue while every status code still reads 200.
# `awaitReady` in the boot test is the only thing that notices.
mutate M48-bootstrap-touches-the-session-service $CO \
  "s=s.replace('        catalog.seedFromResource()','        catalog.seedFromResource()\n        sessions.ownerOf(\"bootstrap-probe\")',1)" $API
