FROM gradle:9.0.0-jdk24 AS build
WORKDIR /workspace

COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
RUN gradle --no-daemon dependencies > /dev/null 2>&1 || true

COPY src ./src
RUN gradle --no-daemon bootJar -x test

FROM eclipse-temurin:24-jre AS runtime
RUN groupadd -g 10001 app && useradd -u 10001 -g app -m -d /app -s /usr/sbin/nologin app
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar /app/app.jar
RUN chown -R app:app /app
USER app:app

EXPOSE 8080 9090
ENV JAVA_OPTS=""
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD wget -qO- http://localhost:9090/actuator/health/liveness | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
