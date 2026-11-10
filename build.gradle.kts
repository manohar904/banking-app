import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension
import org.springframework.boot.gradle.plugin.SpringBootPlugin
import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
    id("org.springframework.boot") version "4.1.2" apply false
    // Takes dependency versions from the Spring Boot BOM, as spring-boot-starter-parent did in Maven.
    id("io.spring.dependency-management") version "1.1.7" apply false
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")

    group = "com.bank"
    version = "0.0.1-SNAPSHOT"

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    repositories {
        mavenCentral()
    }

    configure<DependencyManagementExtension> {
        imports {
            mavenBom(SpringBootPlugin.BOM_COORDINATES)
        }
    }

    dependencies {
        // Needed only while compiling, so Lombok stays out of the packaged jar.
        "compileOnly"("org.projectlombok:lombok")
        "annotationProcessor"("org.projectlombok:lombok")
        // Gradle does not add the launcher that runs JUnit tests by itself.
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    plugins.withId("org.springframework.boot") {
        tasks.withType<BootRun> {
            // Supplies local-only passwords and secrets; see each service's application-local.yaml.
            systemProperty("spring.profiles.active", "local")
        }
    }
}

// The services that own a database. The gateway has none: it only holds browser sessions.
configure(listOf(project(":customer-service"), project(":account-service"), project(":auth-service"))) {
    dependencies {
        "implementation"("org.springframework.boot:spring-boot-starter-data-jpa")
        "implementation"("org.springframework.boot:spring-boot-starter-validation")
        "implementation"("org.springframework.boot:spring-boot-starter-actuator")
        "implementation"("org.springframework.boot:spring-boot-starter-flyway")
        // Flyway keeps PostgreSQL support in a separate module.
        "implementation"("org.flywaydb:flyway-database-postgresql")
        "runtimeOnly"("org.postgresql:postgresql")

        "testImplementation"(testFixtures(project(":common")))
        "testImplementation"("org.springframework.boot:spring-boot-starter-webmvc-test")
        "testImplementation"("org.springframework.boot:spring-boot-starter-security-test")
        // Integration tests run against real PostgreSQL in Docker.
        "testImplementation"("org.springframework.boot:spring-boot-testcontainers")
        "testImplementation"("org.testcontainers:testcontainers-junit-jupiter")
        "testImplementation"("org.testcontainers:testcontainers-postgresql")
    }
}
