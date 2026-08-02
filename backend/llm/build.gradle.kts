plugins { `java-test-fixtures` }

dependencies {
    // `api`, not `implementation`: AnthropicLlmClient's primary constructor takes an
    // `AnthropicClient`, so the SDK is part of this module's public API. Under `implementation` a
    // consumer cannot even write `AnthropicLlmClient()` — resolving the defaulted parameter needs
    // the type — which is what `:backend:api`'s Components does. Same reasoning as
    // `:backend:persistence`'s `api(libs.mongodb.kotlin.coroutine)`.
    api(libs.anthropic.java)
    testFixturesImplementation(libs.kotlinx.coroutines.core)
}
