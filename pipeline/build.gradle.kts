plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":knowledge-core"))

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation(libs.jsoup)

    compileOnly(platform(libs.spring.boot.dependencies))
    compileOnly("org.projectlombok:lombok")
    annotationProcessor(platform(libs.spring.boot.dependencies))
    annotationProcessor("org.projectlombok:lombok")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation(libs.archunit.junit5)
}

tasks.named<Test>("test") {
    // Report selector differences between jsoup's legacy and web query modes.
    systemProperty("jsoup.queryMode", "check")
}
