plugins {
    alias(libs.plugins.spring.boot)
}

springBoot {
    buildInfo()
}

dependencies {
    implementation(project(":knowledge-core"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation(libs.mcp.core)
    implementation(libs.mcp.json.jackson3)

    compileOnly(platform(libs.spring.boot.dependencies))
    compileOnly("org.projectlombok:lombok")
    annotationProcessor(platform(libs.spring.boot.dependencies))
    annotationProcessor("org.projectlombok:lombok")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(libs.archunit.junit5)
}

tasks.named<Test>("test") {
    // The STDIO process test starts the server as a child JVM with this classpath.
    val runtimeClasspath = sourceSets["main"].runtimeClasspath
    inputs.files(runtimeClasspath)
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Ddoclens.test.serverClasspath=" + runtimeClasspath.asPath)
    })
    systemProperty(
        "doclens.log.dir",
        layout.buildDirectory.dir("test-logs").get().asFile.absolutePath
    )
}
