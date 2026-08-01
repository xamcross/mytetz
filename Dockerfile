# syntax=docker/dockerfile:1
#
# Three stages: Angular bundle -> Kotlin distribution -> JRE runtime.
# Only the third stage ships; the two builders are discarded.

# ---------------------------------------------------------------------------
# Stage 1 — Angular bundle
# ---------------------------------------------------------------------------
FROM node:24-slim AS frontend
WORKDIR /frontend

# Angular 21 declares engines.node = "^20.19.0 || ^22.12.0 || >=24.0.0". Assert it
# rather than trusting the tag: a silent drift below the floor produces a bundle
# that fails in subtle ways instead of failing the build.
RUN node --version && node -e "const [a,b]=process.versions.node.split('.').map(Number); if(!(a>=24||(a===22&&b>=12)||(a===20&&b>=19))){console.error('Node '+process.versions.node+' does not satisfy Angular 21');process.exit(1)}"

# Manifests first: `npm ci` is the slow step and only depends on these two files.
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci

COPY frontend/ ./
RUN npm run build && test -f dist/frontend/browser/index.html

# ---------------------------------------------------------------------------
# Stage 2 — Kotlin/Ktor distribution
# ---------------------------------------------------------------------------
# Deliberately NOT a `gradle:*` image: those pin their own Gradle, which would
# silently diverge from the wrapper. Building on a bare JDK and invoking
# ./gradlew means the version in gradle/wrapper/gradle-wrapper.properties (9.6.1)
# is the only Gradle that ever runs, here and on a developer machine alike.
FROM eclipse-temurin:21-jdk AS backend
WORKDIR /src

ENV GRADLE_USER_HOME=/gradle-home \
    GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx1500m"

# Build scripts only, copied before any source. Two levels of caching hang off
# this ordering:
#   1. Docker layer cache — editing Kotlin does not invalidate the resolve step
#      below, so it is skipped entirely.
#   2. The /gradle-home cache mount — when the resolve step does re-run, the
#      Gradle distribution and every already-downloaded artifact are still there.
# gradlew is committed with LF and the executable bit, but --chmod makes the
# build independent of how the host checked it out.
COPY --chmod=0755 gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts ./
COPY backend/persistence/build.gradle.kts ./backend/persistence/
COPY backend/llm/build.gradle.kts         ./backend/llm/
COPY backend/catalog/build.gradle.kts     ./backend/catalog/
COPY backend/graph/build.gradle.kts       ./backend/graph/
COPY backend/quota/build.gradle.kts       ./backend/quota/
COPY backend/session/build.gradle.kts     ./backend/session/
COPY backend/assess/build.gradle.kts      ./backend/assess/
COPY backend/api/build.gradle.kts         ./backend/api/
RUN --mount=type=cache,target=/gradle-home,sharing=locked \
    ./gradlew --no-daemon --console=plain :backend:api:dependencies \
        --configuration runtimeClasspath

COPY backend ./backend

# The Angular bundle is built in stage 1, so the npm-driven Gradle tasks are
# excluded here. Dropping the bundle at frontend/dist/frontend/browser is exactly
# where :backend:api:processResources expects it, so it lands in the jar under
# static/ with no source-tree mutation and no divergence from a local build.
COPY --from=frontend /frontend/dist/frontend/browser ./frontend/dist/frontend/browser

RUN --mount=type=cache,target=/gradle-home,sharing=locked \
    ./gradlew --no-daemon --console=plain :backend:api:installDist \
        -x installFrontend -x buildFrontend \
    && test -f backend/api/build/install/api/bin/api

# ---------------------------------------------------------------------------
# Stage 3 — runtime
# ---------------------------------------------------------------------------
# JRE, not JDK: no compiler, no build tooling, no source in the shipped image.
FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S app && adduser -S -G app -h /app app
WORKDIR /app

COPY --from=backend --chown=app:app /src/backend/api/build/install/api ./

USER app

# Ktor reads PORT; fly.toml's internal_port must match.
ENV PORT=8080 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseSerialGC"

EXPOSE 8080
CMD ["./bin/api"]
