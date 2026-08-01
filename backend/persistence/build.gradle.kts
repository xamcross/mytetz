dependencies {
    implementation(libs.mongodb.kotlin.coroutine)
    implementation(libs.mongodb.bson.kotlinx)
    testImplementation(libs.testcontainers.mongodb)
    testImplementation(libs.testcontainers.junit)
}

tasks.test {
    // This host's Docker Engine rejects the API version (1.32) that Testcontainers 1.20.4
    // falls back to when none is configured, with "client version 1.32 is too old.
    // Minimum supported API version is 1.40". Pin to the highest version this
    // Testcontainers release knows about so container startup (incl. the Ryuk reaper)
    // negotiates successfully.
    systemProperty("api.version", "1.44")
}
