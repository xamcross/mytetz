dependencies {
    implementation(project(":backend:persistence"))
    implementation(libs.mongodb.kotlin.coroutine)
    implementation(libs.mongodb.bson.kotlinx)
    testImplementation(libs.testcontainers.mongodb)
    testImplementation(libs.testcontainers.junit)
}
