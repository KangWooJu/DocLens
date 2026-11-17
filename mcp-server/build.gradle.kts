plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":knowledge-core"))
    implementation("org.springframework.boot:spring-boot-starter")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
