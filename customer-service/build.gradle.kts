plugins {
    id("org.springframework.boot")
}

description = "Customer records"

dependencies {
    implementation(project(":common"))
    // Tells customers by email when money moves.
    implementation("org.springframework.boot:spring-boot-starter-mail")
}
