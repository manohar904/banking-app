plugins {
    java
    id("org.springframework.boot") version "4.1.2"
    // Takes dependency versions from the Spring Boot BOM, as spring-boot-starter-parent did in Maven.
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.bank"
version = "0.0.1-SNAPSHOT"
description = "Banking REST API"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    // Flyway keeps PostgreSQL support in a separate module.
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // Needed only while compiling, so Lombok stays out of the packaged jar.
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    // Integration tests run against real PostgreSQL in Docker.
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    // Gradle does not add the launcher that runs JUnit tests by itself.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.bootRun {
    // Supplies the local database password; see application-local.yaml.
    systemProperty("spring.profiles.active", "local")
}
