# Build stage: the JDK and Gradle stay out of the final image.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
# Dependencies get their own layer, so changing source code does not download them again.
RUN sh ./gradlew dependencies --no-daemon --console=plain > /dev/null
COPY src src
# Tests run in CI, where Docker is available for the integration tests.
RUN sh ./gradlew bootJar --no-daemon --console=plain

# Runtime stage.
FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S bank && adduser -S bank -G bank
USER bank
WORKDIR /app
COPY --from=build /workspace/build/libs/banking-app-*-SNAPSHOT.jar app.jar
EXPOSE 8080
# Sizes the heap from the container's memory limit, leaving room for the rest of the JVM.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
