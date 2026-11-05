# Builds one service. Choose which with: docker build --build-arg SERVICE=gateway .
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY common/build.gradle.kts common/
COPY gateway/build.gradle.kts gateway/
COPY customer-service/build.gradle.kts customer-service/
COPY account-service/build.gradle.kts account-service/
COPY auth-service/build.gradle.kts auth-service/
COPY e2e-tests/build.gradle.kts e2e-tests/
# Dependencies get their own layer, so changing source code does not download them again.
# It comes before SERVICE is used, so all four images share this layer.
RUN sh ./gradlew :gateway:dependencies :customer-service:dependencies :account-service:dependencies \
    :auth-service:dependencies --no-daemon --console=plain > /dev/null
ARG SERVICE
COPY common/src common/src
COPY ${SERVICE}/src ${SERVICE}/src
# Tests run in CI, where Docker is available for the integration tests.
RUN sh ./gradlew :${SERVICE}:bootJar --no-daemon --console=plain && cp ${SERVICE}/build/libs/*.jar app.jar

# Runtime stage: the JDK and Gradle stay out of the final image.
FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S bank && adduser -S bank -G bank
USER bank
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
# Sizes the heap from the container's memory limit, leaving room for the rest of the JVM.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
