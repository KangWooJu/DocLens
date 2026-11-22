plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":knowledge-core"))

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation(libs.jsoup)
    runtimeOnly("org.postgresql:postgresql")

    compileOnly(platform(libs.spring.boot.dependencies))
    compileOnly("org.projectlombok:lombok")
    annotationProcessor(platform(libs.spring.boot.dependencies))
    annotationProcessor("org.projectlombok:lombok")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation(libs.archunit.junit5)
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
}

tasks.named<Test>("test") {
    // Report selector differences between jsoup's legacy and web query modes.
    systemProperty("jsoup.queryMode", "check")

    // The F16 export test starts a child JVM on the test runtime classpath.
    val testClasspath = sourceSets["test"].runtimeClasspath
    inputs.files(testClasspath)
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Ddoclens.test.pipelineClasspath=" + testClasspath.asPath)
    })
}
