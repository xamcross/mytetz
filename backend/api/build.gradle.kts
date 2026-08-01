plugins { application }

application { mainClass.set("com.mytetz.api.ApplicationKt") }

dependencies {
    implementation(project(":backend:session"))
    implementation(project(":backend:assess"))
    implementation(project(":backend:quota"))
    implementation(project(":backend:graph"))
    implementation(project(":backend:catalog"))
    implementation(project(":backend:llm"))
    implementation(project(":backend:persistence"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.logback.classic)
    testImplementation(libs.ktor.server.test.host)
}
