#!/usr/bin/env bash
#
# Mutations for Task 1.11 — error mapping, signed principals, catalogue routes, component graph.
# Run with: ./tools/mutation-test.sh tools/mutations/task-1.11.sh
#
# Each entry breaks one decision the task argues for. If a mutation SURVIVES, the argument is not
# backed by a test and either the test or the argument needs to change.

EM=backend/api/src/main/kotlin/com/mytetz/api/ErrorMapping.kt
CR=backend/api/src/main/kotlin/com/mytetz/api/CatalogRoutes.kt
CO=backend/api/src/main/kotlin/com/mytetz/api/Components.kt
AP=backend/api/src/main/kotlin/com/mytetz/api/Application.kt
PR=backend/api/src/main/kotlin/com/mytetz/api/Principal.kt
RL=backend/api/src/main/kotlin/com/mytetz/api/RateLimit.kt
HR=backend/api/src/main/kotlin/com/mytetz/api/HealthRoutes.kt
CS=backend/catalog/src/main/kotlin/com/mytetz/catalog/CatalogService.kt
TR=backend/catalog/src/main/kotlin/com/mytetz/catalog/TopicRequestRepository.kt
MO=backend/persistence/src/main/kotlin/com/mytetz/persistence/Mongo.kt
MC=backend/persistence/src/main/kotlin/com/mytetz/persistence/MongoConfig.kt
API=:backend:api:test
CAT=:backend:catalog:test
PERSIST=:backend:persistence:test

# --------------------------------------------------------------- error mapping

mutate M01-unknown-session-becomes-500 $EM \
  "s=s.replace('call.respond(HttpStatusCode.NotFound, ApiError(\"NOT_FOUND\", \"no such session\"))','call.respond(HttpStatusCode.InternalServerError, ApiError(\"INTERNAL\", \"unexpected error\"))',1)" $API

mutate M02-corruption-becomes-generic-INTERNAL $EM \
  "s=s.replace('ApiError(\"CORRUPT_SESSION\", \"this session\'s stored data is inconsistent and cannot be read\")','ApiError(\"INTERNAL\", \"unexpected error\")',1)" $API

mutate M03-IllegalArgument-to-404 $EM \
  "s=s.replace('log.info(\"rejected a malformed request: {}\", cause.message)\n            call.respond(HttpStatusCode.BadRequest, INVALID_REQUEST)','log.info(\"rejected a malformed request: {}\", cause.message)\n            call.respond(HttpStatusCode.NotFound, ApiError(\"NOT_FOUND\", cause.message.orEmpty()))',1)" $API

mutate M04-echo-IllegalArgument-message $EM \
  "s=s.replace('log.info(\"rejected a malformed request: {}\", cause.message)\n            call.respond(HttpStatusCode.BadRequest, INVALID_REQUEST)','log.info(\"rejected a malformed request: {}\", cause.message)\n            call.respond(HttpStatusCode.BadRequest, ApiError(\"INVALID_REQUEST\", cause.message.orEmpty()))',1)" $API

mutate M05-drop-BadRequestException-arm $EM \
  "s=s.replace('exception<BadRequestException> { call, cause ->','exception<java.io.CharConversionException> { call, cause ->',1)" $API

mutate M06-echo-GenerationFailed-message $EM \
  "s=s.replace('ApiError(\"GENERATION_FAILED\", \"the explanation could not be generated; try again\")','ApiError(\"GENERATION_FAILED\", cause.message.orEmpty())',1)" $API

mutate M07-drop-CORRUPT_SESSION-log-line $EM \
  "s=s.replace('log.error(\n                \"\$CORRUPT_SESSION_ALERT sessionId={}','log.debug(\n                \"quiet sessionId={}',1)" $API

mutate M08-drop-415-status-hook $EM \
  "s=s.replace('status(HttpStatusCode.UnsupportedMediaType) { call, status ->','status(HttpStatusCode.PaymentRequired) { call, status ->',1)" $API

mutate M09-framework-notfound-message-echoed $EM \
  "s=s.replace('call.respond(HttpStatusCode.NotFound, ApiError(\"NOT_FOUND\", \"not found\"))','call.respond(HttpStatusCode.NotFound, ApiError(\"NOT_FOUND\", cause.message.orEmpty()))',1)" $API

# --------------------------------------------------------------- catalogue routes

mutate M10-detail-route-stops-filtering-PUBLISHED $CR \
  "s=s.replace('catalog.findBySlug(slug)?.takeIf { it.status == TopicStatus.PUBLISHED }','catalog.findBySlug(slug)',1)" $API

mutate M11-route-ignores-rate-limiter $CR \
  "s=s.replace('if (!topicRequestLimiter.tryAcquire(caller)) {','if (false) {',1)" $API

mutate M12-route-ignores-body-size $CR \
  "s=s.replace('if (declaredLength == null || declaredLength > MAX_TOPIC_REQUEST_BODY_BYTES) {','if (false) {',1)" $API

mutate M13-limiter-keyed-on-principal-again $CR \
  "s=s.replace('val caller = ClientAddress.of(call, clientAddresses)','val caller = Principals.resolve(call, cookies).value',1)" $API

mutate M14-browse-routes-mint-a-cookie $CR \
  "s=s.replace('        call.respond(topics.map { it.toSummary() })','        Principals.resolve(call, cookies)\n        call.respond(topics.map { it.toSummary() })',1)" $API

# --------------------------------------------------------------- wiring and bootstrap

mutate M15-bootstrap-forgets-topicRequests-indexes $CO \
  "s=s.replace('        topicRequests.ensureIndexes()\n','',1)" $API

mutate M16-bootstrap-forgets-quota-indexes $CO \
  "s=s.replace('        quotaRepository.ensureIndexes()\n','',1)" $API

mutate M17-module-never-calls-bootstrap $AP \
  "s=s.replace('    val ready = bootstrap(components)\n','    val ready = java.util.concurrent.atomic.AtomicBoolean(true)\n',1)" $API

mutate M18-bootstrap-blocks-startup $AP \
  "s=s.replace('    launch {','    kotlinx.coroutines.runBlocking {',1)" $API

mutate M19-readiness-always-true $AP \
  "s=s.replace('ready = { ready.get() }','ready = { true }',1)" $API

mutate M20-api-catchall-removed $AP \
  "import re; s=re.sub(r'        route\(\"/api/\{\.\.\.\}\"\) \{.*?\n        \}\n', '', s, count=1, flags=re.S)" $API

mutate M21-health-ready-gets-a-default-again $HR \
  "s=s.replace('val ready: Boolean)','val ready: Boolean = true)',1)" $API

mutate M22-llm-built-eagerly $CO \
  "s=s.replace('private val llm: LlmClient by lazy(llmFactory)','private val llm: LlmClient = llmFactory()',1)" $API

# --------------------------------------------------------------- principals

mutate M23-signature-comparison-always-true $PR \
  "s=s.replace('MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))','true',1)" $API

mutate M24-cookie-read-with-default-encoding $PR \
  "s=s.replace('call.request.cookies[COOKIE_NAME, COOKIE_ENCODING]','call.request.cookies[COOKIE_NAME]',1)" $API

mutate M25-secure-from-Host-header $PR \
  "s=s.replace('secure = config.secure,','secure = call.request.local.serverHost != \"localhost\",',1)" $API

mutate M26-accept-any-signed-payload $PR \
  "s=s.replace('return PrincipalId.anonymous(uuid).takeIf { it.value == value }','return PrincipalId(value)',1)" $API

mutate M27-uuid-compared-case-insensitively $PR \
  "s=s.replace('if (parsed.toString() != uuid) return null','if (!parsed.toString().equals(uuid, ignoreCase = true)) return null',1)" $API

mutate M28-signing-key-falls-back-to-a-default $PR \
  "s=s.replace('check(key.isNotEmpty())','if (key.isEmpty()) return \"0\".repeat(32)\n            check(key.isNotEmpty())',1)" $API

# --------------------------------------------------------------- rate limiting

mutate M29-limiter-table-unbounded $RL \
  "s=s.replace('size > maxTrackedKeys','false',1)" $API

mutate M30-eviction-by-insertion-order-not-lru $RL \
  "s=s.replace('LinkedHashMap<String, Int>(INITIAL_CAPACITY, LOAD_FACTOR, true)','LinkedHashMap<String, Int>(INITIAL_CAPACITY, LOAD_FACTOR, false)',1)" $API

mutate M31-default-trusts-no-header-collapsing-all-callers $RL \
  "s=s.replace('value.isEmpty() -> ClientAddress.FLY_CLIENT_IP','value.isEmpty() -> null',1)" $API

mutate M32-client-address-not-truncated $RL \
  "s=s.replace('.take(MAX_ADDRESS_LENGTH)','',1)" $API

mutate M39-default-trusts-a-forgeable-header $RL \
  "s=s.replace('value.isEmpty() -> ClientAddress.FLY_CLIENT_IP','value.isEmpty() -> \"X-Forwarded-For\"',1)" $API

# --------------------------------------------------------------- catalogue storage

mutate M33-seeding-stops-preserving-publication $CS \
  "s=s.replace('repository.upsertPreservingStatus(it)','repository.upsert(it)',1)" $CAT

mutate M34-eviction-refuses-instead $TR \
  "s=s.replace('while (collection.countDocuments() >= maxDistinctRequests) {','while (false) {',1)" $CAT

mutate M35-eviction-picks-the-most-popular-row $TR \
  "s=s.replace('Indexes.ascending(\"count\"),\n                        Indexes.ascending(\"lastSeenAtEpochMillis\"),','Indexes.descending(\"count\"),\n                        Indexes.ascending(\"lastSeenAtEpochMillis\"),',1)" $CAT

mutate M36-eviction-index-not-created $TR \
  "import re; s=re.sub(r'        collection\.createIndex\(\n            Indexes\.compoundIndex\(Indexes\.ascending\(\"count\"\), Indexes\.ascending\(\"lastSeenAtEpochMillis\"\)\),\n            IndexOptions\(\)\.name\(\"weakest\"\),\n        \)\n', '', s, count=1)" $CAT

mutate M37-countFor-stops-normalising $TR \
  "s=s.replace('collection.find(Filters.eq(\"_id\", normalize(text))).firstOrNull()','collection.find(Filters.eq(\"_id\", text)).firstOrNull()',1)" $CAT

mutate M38-length-checked-before-normalisation $TR \
  "s=s.replace('if (normalized.isEmpty() || normalized.length > MAX_TEXT_LENGTH)','if (rawText.isEmpty() || rawText.length > MAX_TEXT_LENGTH)',1)" $CAT

# --------------------------------------------------------------- HEAD, and the health timeout

mutate M40-head-not-answered $AP \
  "s=s.replace('    install(AutoHeadResponse)\n','',1)" $API

mutate M41-server-selection-timeout-not-applied $MO \
  "s=s.replace('            .applyToClusterSettings {\n                it.serverSelectionTimeout(config.serverSelectionTimeoutMillis, TimeUnit.MILLISECONDS)\n            }\n','',1)" $PERSIST

mutate M42-server-selection-timeout-defaults-to-the-drivers $MC \
  "s=s.replace('const val DEFAULT_SERVER_SELECTION_TIMEOUT_MILLIS: Long = 3_000','const val DEFAULT_SERVER_SELECTION_TIMEOUT_MILLIS: Long = 30_000',1)" $PERSIST
