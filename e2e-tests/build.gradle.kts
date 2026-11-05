import org.springframework.boot.gradle.tasks.bundling.BootJar

description = "Runs all four packaged services together against real PostgreSQL. Requires Docker."

dependencies {
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("com.jayway.jsonpath:json-path")
    testRuntimeOnly("org.postgresql:postgresql")
}

evaluationDependsOn(":gateway")
evaluationDependsOn(":customer-service")
evaluationDependsOn(":account-service")
evaluationDependsOn(":auth-service")

tasks.withType<Test> {
    listOf("gateway", "customer-service", "account-service", "auth-service").forEach { service ->
        val bootJar = project(":$service").tasks.named<BootJar>("bootJar")
        dependsOn(bootJar)
        systemProperty("jar.$service", bootJar.get().archiveFile.get().asFile.absolutePath)
    }
}
