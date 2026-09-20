plugins { application }

// Issue #48's review path (`scripts/PublishTopExplanations.kt`) is the one thing in this module an
// owner runs by hand, so this is the module's one `run` entry point — see that file's own KDoc for
// the exact command, with and without `--publish`.
application { mainClass.set("com.mytetz.graph.scripts.PublishTopExplanationsKt") }

// Issue #162's own owner command (`scripts/CorrectSeedText.kt`) needs a second entry point, and
// the `application` plugin gives a module only one `mainClass`. A plain `JavaExec` task, on the
// same runtime classpath as `run`, is the second one — see that file's own KDoc for the exact
// commands. `JavaExec` already understands `--args`, exactly as `run` does, with no extra wiring.
tasks.register<JavaExec>("correctSeedText") {
    group = "application"
    description = "Owner command (issue #162): replace, or take back, the text of one seed."
    mainClass.set("com.mytetz.graph.scripts.CorrectSeedTextKt")
    classpath = sourceSets.getByName("main").runtimeClasspath
    // The repository root, not this module's own directory (a plain JavaExec task's default) —
    // so `--file docs/content/seed-corrections-2026-09-20/<slug>.txt` in the runbook resolves the
    // same way from any checkout, without the owner cd-ing into backend/graph first.
    workingDir = rootProject.projectDir
}

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
