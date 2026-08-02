dependencies {
    implementation(project(":backend:persistence"))
    // `api`, not `implementation`: Verb is a member of SessionNode, so it is part of this module's
    // public API. Under `implementation` a consumer of :backend:session cannot name the type it is
    // handed — verified by removing :backend:assess's own graph dependency and finding
    // :backend:graph absent from its compileClasspath.
    api(project(":backend:graph"))
    implementation(project(":backend:catalog"))
    implementation(libs.mongodb.kotlin.coroutine)
    // Declared, not leaned on transitively: LearningSession and SessionNode are @Serializable
    // documents read and written through the driver's kotlinx codec. Same reasoning as commit
    // ae3dd81 for :backend:graph.
    implementation(libs.mongodb.bson.kotlinx)
    testImplementation(libs.testcontainers.mongodb)
    testImplementation(libs.testcontainers.junit)
}
