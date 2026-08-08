dependencies {
    implementation(project(":backend:persistence"))
    // Entitlement.resolve returns an Allowance. The quota module owns that type and its rules.
    implementation(project(":backend:quota"))
    implementation(libs.mongodb.kotlin.coroutine)
    // Declared directly, not only through a transitive dependency. Subscription and BillingEvent
    // are @Serializable documents. EpochMillisAsBsonDateTime imports BsonEncoder and BsonDecoder
    // from org.bson.codecs.kotlinx.
    implementation(libs.mongodb.bson.kotlinx)
    // BillingService logs BILLING_UNKNOWN_EVENT and Entitlement logs BILLING_NO_PERIOD_END. This
    // module has never logged before, so slf4j-api is declared here directly, the same as
    // :backend:account does for MailSender.
    implementation(libs.slf4j.api)
    testImplementation(libs.testcontainers.mongodb)
    testImplementation(libs.testcontainers.junit)
    // BillingServiceTest and EntitlementTest attach a ListAppender straight to the logger, to read
    // each operator alert token back. This is the technique ErrorMappingTest uses in :backend:api
    // and MailSenderTest uses in :backend:account.
    testImplementation(libs.logback.classic)
}
