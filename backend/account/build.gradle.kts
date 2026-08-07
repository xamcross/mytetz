dependencies {
    implementation(project(":backend:persistence"))
    implementation(libs.mongodb.kotlin.coroutine)
    // Declared directly, not only through a transitive dependency. User, MagicLinkToken and
    // AuthSession are @Serializable documents. EpochMillisAsBsonDateTime imports BsonEncoder and
    // BsonDecoder from org.bson.codecs.kotlinx.
    implementation(libs.mongodb.bson.kotlinx)
    testImplementation(libs.testcontainers.mongodb)
    testImplementation(libs.testcontainers.junit)
}
