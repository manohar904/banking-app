plugins {
    id("org.springframework.boot")
}

description = "Sign-in accounts and passwords"

dependencies {
    implementation(project(":common"))
    // Sends password reset links.
    implementation("org.springframework.boot:spring-boot-starter-mail")
}
