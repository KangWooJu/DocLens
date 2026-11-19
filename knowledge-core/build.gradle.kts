plugins {
    `java-library`
    `java-test-fixtures`
}

dependencies {
    implementation(libs.sqlite.jdbc)
    implementation("tools.jackson.core:jackson-databind")

    testImplementation(libs.archunit.junit5)
}
