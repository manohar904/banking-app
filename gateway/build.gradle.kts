plugins {
    id("org.springframework.boot")
}

description = "The only public entry point: serves the web UI, holds sessions in Redis and forwards API calls"

dependencies {
    implementation(project(":common"))
    // Sessions live in Redis, so restarting the gateway does not sign anyone out.
    implementation("org.springframework.boot:spring-boot-starter-session-data-redis")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
}
