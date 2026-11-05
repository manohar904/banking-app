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

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
