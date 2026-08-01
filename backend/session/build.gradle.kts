dependencies {
    implementation(project(":backend:persistence"))
    implementation(project(":backend:graph"))
    implementation(project(":backend:catalog"))
    implementation(libs.mongodb.kotlin.coroutine)
    testImplementation(libs.testcontainers.mongodb)
    testImplementation(libs.testcontainers.junit)
}
