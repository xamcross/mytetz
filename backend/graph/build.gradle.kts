plugins { application }

// Issue #48's review path (`scripts/PublishTopExplanations.kt`) is the one thing in this module an
// owner runs by hand, so this is the module's one `run` entry point — see that file's own KDoc for
// the exact command, with and without `--publish`.
application { mainClass.set("com.mytetz.graph.scripts.PublishTopExplanationsKt") }

dependencies {
    implementation(project(":backend:persistence"))
    implementation(project(":backend:llm"))
    implementation(libs.mongodb.kotlin.coroutine)
    implementation(libs.mongodb.bson.kotlinx)
    testImplementation(testFixtures(project(":backend:llm")))
    testImplementation(libs.testcontainers.mongodb)
    testImplementation(libs.testcontainers.junit)
}
