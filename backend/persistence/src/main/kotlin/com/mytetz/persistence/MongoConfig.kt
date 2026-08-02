package com.mytetz.persistence

data class MongoConfig(
    val uri: String,
    val databaseName: String,
    /**
     * How long the driver may spend looking for a reachable server before failing an operation.
     *
     * The driver's own default is 30 seconds. That is far too long here, for one specific reason:
     * `/api/health` pings Mongo on every request and `fly.toml`'s health check allows **5 seconds**,
     * so during an outage the endpoint whose entire purpose is to report "the database is
     * unreachable" could not answer inside the window it is checked in. Task 1.11 moved bootstrap off
     * the startup path so that route would exist during an outage; without this, the route exists
     * and still cannot reply in time.
     *
     * Short, but not so short that an ordinary Atlas replica-set election looks like an outage.
     */
    val serverSelectionTimeoutMillis: Long =
        resolveServerSelectionTimeoutMillis(System.getenv(SERVER_SELECTION_TIMEOUT_ENV)),
) {
    init {
        require(serverSelectionTimeoutMillis > 0) {
            "serverSelectionTimeoutMillis must be positive, was $serverSelectionTimeoutMillis"
        }
    }

    companion object {

        const val SERVER_SELECTION_TIMEOUT_ENV: String = "MYTETZ_MONGO_SERVER_SELECTION_TIMEOUT_MILLIS"

        /** Comfortably inside fly's 5s health-check timeout, and above a typical Atlas failover. */
        const val DEFAULT_SERVER_SELECTION_TIMEOUT_MILLIS: Long = 3_000

        fun fromEnv(): MongoConfig = MongoConfig(
            uri = System.getenv("MONGODB_URI")
                ?: error("MONGODB_URI is not set"),
            databaseName = System.getenv("MONGODB_DATABASE") ?: "mytetz",
        )

        /**
         * A missing, unparseable or non-positive override falls back to the default rather than
         * throwing — the same reasoning, and the same shape, as `GraphConfig.resolveMaxOutputTokens`
         * and `QuotaConfig.resolveDailyExplains`. Zero would mean "give up before trying".
         */
        internal fun resolveServerSelectionTimeoutMillis(raw: String?): Long =
            raw?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: DEFAULT_SERVER_SELECTION_TIMEOUT_MILLIS
    }
}
