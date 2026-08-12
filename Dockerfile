# --- Build stage ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon dependencies || true

COPY src ./src
RUN ./gradlew --no-daemon installDist

# --- Runtime stage ---
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

COPY --from=build /app/build/install/LaborManagement ./

ENV PORT=8080
ENV JAVA_TOOL_OPTIONS="-Xmx384m"
EXPOSE 8080

ENTRYPOINT ["./bin/LaborManagement"]