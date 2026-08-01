plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.serialization")

    repositories { mavenCentral() }

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(21)
    }

    dependencies {
        add("implementation", rootProject.libs.kotlinx.coroutines.core)
        add("implementation", rootProject.libs.kotlinx.serialization.json)
        add("testImplementation", kotlin("test"))
        add("testImplementation", rootProject.libs.kotlinx.coroutines.test)
    }

    tasks.withType<Test>().configureEach { useJUnitPlatform() }
}
