plugins {
    `java-library`
}

description = "Code every service shares: internal tokens, security rules and error responses"

dependencies {
    api("org.springframework.boot:spring-boot-starter-webmvc")
    api("org.springframework.boot:spring-boot-starter-security")
    api("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    // For the exception types mapped in GlobalExceptionHandler.
    api("org.springframework:spring-tx")
    api("org.springframework.data:spring-data-commons")
    // For recording events in each service's own database.
    api("org.springframework:spring-jdbc")

    api("org.springframework.boot:spring-boot-starter-kafka")
    api("org.springframework.boot:spring-boot-starter-actuator")
    api("org.springframework.boot:spring-boot-starter-micrometer-metrics")
    api("io.micrometer:micrometer-registry-prometheus")
    // Traces requests across the services and sends them to Tempo over OTLP.
    api("org.springframework.boot:spring-boot-starter-opentelemetry")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
