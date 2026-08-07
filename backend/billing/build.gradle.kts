dependencies {
    implementation(project(":backend:persistence"))
    // Entitlement.resolve returns an Allowance. The quota module owns that type and its rules.
    implementation(project(":backend:quota"))
    implementation(libs.mongodb.kotlin.coroutine)
    // Declared directly, not only through a transitive dependency. Subscription and BillingEvent
    // are @Serializable documents. EpochMillisAsBsonDateTime imports BsonEncoder and BsonDecoder
    // from org.bson.codecs.kotlinx.
    implementation(libs.mongodb.bson.kotlinx)
    testImplementation(libs.testcontainers.mongodb)
    testImplementation(libs.testcontainers.junit)
}
