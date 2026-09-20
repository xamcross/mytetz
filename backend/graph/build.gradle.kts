plugins { application }

// Issue #48's review path (`scripts/PublishTopExplanations.kt`) is the one thing in this module an
// owner runs by hand, so this is the module's one `run` entry point — see that file's own KDoc for
// the exact command, with and without `--publish`.
application { mainClass.set("com.mytetz.graph.scripts.PublishTopExplanationsKt") }

// Issue #47's review-date command (`scripts/SetTopicReviewDate.kt`) needs its own entry point.
// `run` above already maps to `PublishTopExplanationsKt`. See that file's own KDoc for the exact
// commands.
tasks.register<JavaExec>("runReviewDate") {
    group = "application"
    mainClass.set("com.mytetz.graph.scripts.SetTopicReviewDateKt")
    classpath = sourceSets["main"].runtimeClasspath
}

dependencies {
    implementation(project(":backend:persistence"))
    implementation(project(":backend:llm"))
    // Issue #47's review-date command reads and writes Topic.reviewedAt. That field lives in
    // :backend:catalog. :backend:catalog does not depend on this module, so this one line adds no
    // cycle. :backend:session and :backend:api already depend in the same direction.
    implementation(project(":backend:catalog"))
    implementation(libs.mongodb.kotlin.coroutine)
    implementation(libs.mongodb.bson.kotlinx)
    testImplementation(testFixtures(project(":backend:llm")))
    testImplementation(libs.testcontainers.mongodb)
    testImplementation(libs.testcontainers.junit)
}
