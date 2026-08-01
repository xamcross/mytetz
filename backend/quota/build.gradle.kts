dependencies {
    implementation(project(":backend:persistence"))
    implementation(libs.mongodb.kotlin.coroutine)
    testImplementation(libs.testcontainers.mongodb)
    testImplementation(libs.testcontainers.junit)
}
