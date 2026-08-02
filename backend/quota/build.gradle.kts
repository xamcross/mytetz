dependencies {
    implementation(project(":backend:persistence"))
    implementation(libs.mongodb.kotlin.coroutine)
    // Declared, not leaned on transitively: PrincipalCounter is a @Serializable document, and
    // EpochMillisAsBsonDateTime imports BsonEncoder/BsonDecoder from org.bson.codecs.kotlinx.
    implementation(libs.mongodb.bson.kotlinx)
    testImplementation(libs.testcontainers.mongodb)
    testImplementation(libs.testcontainers.junit)
}
