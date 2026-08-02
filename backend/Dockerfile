FROM gradle:9.6.1-jdk25 AS build
WORKDIR /workspace

COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY src ./src

RUN --mount=type=cache,id=shared-ledger-gradle-home,target=/root/.gradle,sharing=locked \
    --mount=type=cache,id=shared-ledger-gradle-project,target=/workspace/.gradle,sharing=locked \
    --mount=type=cache,id=shared-ledger-gradle-build,target=/workspace/build,sharing=locked \
    rm -rf build/libs \
    && gradle --no-daemon bootJar -x test \
    && cp build/libs/*.jar /workspace/app.jar

FROM eclipse-temurin:25-jre AS runtime
# temurin:25-jre's base image no longer ships wget/curl; install curl for the health check.
RUN apt-get update \
  && apt-get install -y --no-install-recommends curl \
  && rm -rf /var/lib/apt/lists/*
RUN groupadd -g 10001 app && useradd -u 10001 -g app -m -d /app -s /usr/sbin/nologin app
WORKDIR /app
COPY --chown=app:app --from=build /workspace/app.jar /app/app.jar
USER app:app

EXPOSE 8080 9090
ENV JAVA_OPTS=""
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD curl -fsS http://localhost:9090/actuator/health/liveness | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
