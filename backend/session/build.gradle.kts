dependencies {
    implementation(project(":backend:persistence"))
    // `api`, not `implementation`: Verb is a member of SessionNode, so it is part of this module's
    // public API. Under `implementation` a consumer of :backend:session cannot name the type it is
    // handed — verified by removing :backend:assess's own graph dependency and finding
    // :backend:graph absent from its compileClasspath.
    api(project(":backend:graph"))
    // Promoted to `api` by Task 1.10 for the same reason: CatalogService is a constructor parameter
    // of SessionService and Topic appears in its private helpers, so a consumer that cannot name
    // CatalogService cannot construct the service at all.
    api(project(":backend:catalog"))
    implementation(libs.mongodb.kotlin.coroutine)
    // Declared, not leaned on transitively: LearningSession and SessionNode are @Serializable
    // documents read and written through the driver's kotlinx codec. Same reasoning as commit
    // ae3dd81 for :backend:graph.
    implementation(libs.mongodb.bson.kotlinx)
    // SessionServiceTest drives the whole chain end to end, which means a real ExplanationGraph and
    // therefore a real LlmClient. FakeLlmClient is the one this project uses in tests; nothing here
    // may reach the Anthropic API.
    testImplementation(testFixtures(project(":backend:llm")))
    testImplementation(libs.testcontainers.mongodb)
    testImplementation(libs.testcontainers.junit)
}
