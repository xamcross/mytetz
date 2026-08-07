plugins { application }

application { mainClass.set("com.mytetz.api.ApplicationKt") }

dependencies {
    implementation(project(":backend:account"))
    implementation(project(":backend:session"))
    implementation(project(":backend:assess"))
    implementation(project(":backend:quota"))
    implementation(project(":backend:graph"))
    implementation(project(":backend:catalog"))
    implementation(project(":backend:llm"))
    implementation(project(":backend:persistence"))
    // `:backend:account` declares `ktor-client-core` as `implementation`, so it does not reach this
    // module's own compile classpath. `Components.kt` builds a real `HttpClient(CIO)` for
    // `ResendMailSender` and `GoogleOAuth`, so both the client API and a real engine are needed here.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.server.status.pages)
    // HEAD is what uptime monitors send. Without this, `get { }` answers only GET and every HEAD
    // falls through to the /api/{...} catch-all as a 404.
    implementation(libs.ktor.server.auto.head.response)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.logback.classic)
    testImplementation(libs.ktor.server.test.host)
    // The API tests drive the real CatalogService and SessionService against a real Mongo, so this
    // module needs a container of its own. FakeLlmClient is the client every test in this project
    // uses: nothing in the test path may construct AnthropicLlmClient, which calls `fromEnv()` and
    // would both demand a key and put a real API call one mistake away.
    testImplementation(testFixtures(project(":backend:llm")))
    testImplementation(libs.testcontainers.mongodb)
    testImplementation(libs.testcontainers.junit)
    // AuthRoutesTest drives GoogleOAuth against a loopback com.sun.net.httpserver.HttpServer, the
    // same way GoogleOAuthTest and MailSenderTest do in :backend:account. The CIO engine this needs
    // already reaches the test classpath through the `implementation` dependency above.
}

val frontendDir = rootProject.file("frontend")
val isWindows = System.getProperty("os.name").startsWith("Windows")
val npm = if (isWindows) "npm.cmd" else "npm"

// node_modules holds tens of thousands of files; fingerprinting it as a task output costs far
// more than the install itself. Track the install with a stamp file instead, keyed on the
// manifests, and fall out of date whenever node_modules has been removed by hand.
val frontendInstallStamp = layout.buildDirectory.file("frontend/install.stamp")

val installFrontend = tasks.register<Exec>("installFrontend") {
    workingDir = frontendDir
    commandLine(npm, "ci")
    inputs.file(File(frontendDir, "package.json"))
    inputs.file(File(frontendDir, "package-lock.json"))
    outputs.file(frontendInstallStamp)
    outputs.upToDateWhen { File(frontendDir, "node_modules").isDirectory }
    doLast {
        frontendInstallStamp.get().asFile.apply {
            parentFile.mkdirs()
            writeText("ok\n")
        }
    }
}

val buildFrontend = tasks.register<Exec>("buildFrontend") {
    dependsOn(installFrontend)
    workingDir = frontendDir
    commandLine(npm, "run", "build")
    inputs.dir(File(frontendDir, "src"))
    inputs.dir(File(frontendDir, "public"))
    inputs.files(
        File(frontendDir, "angular.json"),
        File(frontendDir, "package.json"),
        File(frontendDir, "package-lock.json"),
        File(frontendDir, "tsconfig.json"),
        File(frontendDir, "tsconfig.app.json"),
    )
    outputs.dir(File(frontendDir, "dist"))
}

// angular.json sets no explicit outputPath, so the @angular/build:application builder emits
// browser assets to dist/<project>/browser, i.e. frontend/dist/frontend/browser.
tasks.named<ProcessResources>("processResources") {
    dependsOn(buildFrontend)
    from(File(frontendDir, "dist/frontend/browser")) { into("static") }
}
