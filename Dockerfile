# syntax=docker/dockerfile:1

# --- Build stage -------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Cache dependencies first
COPY pom.xml .
RUN mvn -q -B dependency:go-offline

# Build the application (skip tests in the image build; CI runs them separately)
COPY src ./src
RUN mvn -q -B clean package -DskipTests

# --- Runtime stage -----------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app

# Run as a non-root user
RUN groupadd --system app && useradd --system --gid app --home /app app

COPY --from=build /workspace/target/*.jar /app/app.jar
RUN chown -R app:app /app
USER app

EXPOSE 8080

# Container-aware JVM defaults; override via JAVA_OPTS
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"

# Actuator health endpoint is used by k8s probes
HEALTHCHECK --interval=15s --timeout=3s --start-period=45s --retries=5 \
  CMD ["/bin/sh", "-c", "wget -q -O /dev/null http://127.0.0.1:8080/actuator/health || exit 1"]

ENTRYPOINT ["/bin/sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
