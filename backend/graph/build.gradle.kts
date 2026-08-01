dependencies {
    implementation(project(":backend:persistence"))
    implementation(project(":backend:llm"))
    implementation(libs.mongodb.kotlin.coroutine)
    implementation(libs.mongodb.bson.kotlinx)
    testImplementation(testFixtures(project(":backend:llm")))
    testImplementation(libs.testcontainers.mongodb)
    testImplementation(libs.testcontainers.junit)
}
