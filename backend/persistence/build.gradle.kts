dependencies {
    // `api`, not `implementation`: `Mongo.database` is a public property of type MongoDatabase, so
    // the driver is part of this module's public API. Under `implementation` the driver is absent
    // from a consumer's compileClasspath and `:backend:api`'s Components — which hands
    // `mongo.database` to every repository constructor — cannot name the type it is passing.
    // Same reasoning as `:backend:session`'s `api(project(":backend:graph"))`.
    api(libs.mongodb.kotlin.coroutine)
    implementation(libs.mongodb.bson.kotlinx)
    testImplementation(libs.testcontainers.mongodb)
    testImplementation(libs.testcontainers.junit)
}
