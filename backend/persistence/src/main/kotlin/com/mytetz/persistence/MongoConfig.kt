package com.mytetz.persistence

data class MongoConfig(
    val uri: String,
    val databaseName: String,
) {
    companion object {
        fun fromEnv(): MongoConfig = MongoConfig(
            uri = System.getenv("MONGODB_URI")
                ?: error("MONGODB_URI is not set"),
            databaseName = System.getenv("MONGODB_DATABASE") ?: "mytetz",
        )
    }
}
