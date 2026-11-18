plugins {
    id("org.springframework.boot")
}

description = "Accounts, balances and the ledger"

dependencies {
    implementation(project(":common"))
    // Writes the statements customers download as PDF.
    implementation("com.github.librepdf:openpdf:2.0.3")
}
