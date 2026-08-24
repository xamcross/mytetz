dependencies {
    implementation(project(":backend:persistence"))
    implementation(libs.mongodb.kotlin.coroutine)
    // Declared directly, not only through a transitive dependency. User, MagicLinkToken and
    // AuthSession are @Serializable documents. EpochMillisAsBsonDateTime imports BsonEncoder and
    // BsonDecoder from org.bson.codecs.kotlinx.
    implementation(libs.mongodb.bson.kotlinx)
    // ResendMailSender's constructor names the type io.ktor.client.HttpClient, so this module
    // needs the client API on its own compile classpath and not only through a future consumer.
    // No engine is added here: the caller that builds the real HttpClient picks one, the same way
    // AnthropicLlmClient takes a pre-built AnthropicClient rather than choosing its own transport.
    implementation(libs.ktor.client.core)
    // MailSender.kt logs MAGIC_LINK_LOGGED and MAIL_SEND_FAILED. This module has never logged
    // before, so slf4j-api is declared here directly, pinned to the version :backend:api already
    // resolves through logback-classic.
    implementation(libs.slf4j.api)
    testImplementation(libs.testcontainers.mongodb)
    testImplementation(libs.testcontainers.junit)
    // MailSenderTest builds a real HttpClient against a loopback HttpServer, so it needs an
    // engine. CIO is pure Kotlin/coroutines and pulls in nothing beyond the client itself.
    testImplementation(libs.ktor.client.cio)
    // MailSenderTest attaches a ListAppender straight to the logger to read MAGIC_LINK_LOGGED and
    // MAIL_SEND_FAILED back, the same technique ErrorMappingTest uses in :backend:api.
    testImplementation(libs.logback.classic)
}
