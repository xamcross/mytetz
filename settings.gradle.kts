plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "mytetz"

include(
    ":backend:persistence",
    ":backend:llm",
    ":backend:catalog",
    ":backend:graph",
    ":backend:quota",
    ":backend:session",
    ":backend:assess",
    ":backend:api",
)
