plugins { `java-test-fixtures` }

dependencies {
    implementation(libs.anthropic.java)
    testFixturesImplementation(libs.kotlinx.coroutines.core)
}
