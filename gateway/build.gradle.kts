plugins {
    id("org.springframework.boot")
}

description = "The only public entry point: serves the web UI, holds sessions and forwards API calls"

dependencies {
    implementation(project(":common"))

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
}
