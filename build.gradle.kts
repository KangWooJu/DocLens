plugins {
    alias(libs.plugins.spring.boot) apply false
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "jacoco")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    repositories {
        mavenCentral()
    }

    dependencies {
        "implementation"(platform(rootProject.libs.spring.boot.dependencies))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }

    // Shared language-neutral contracts and golden vectors for tests.
    val contractsDir = rootProject.layout.projectDirectory.dir("contracts/v1").asFile.absolutePath

    tasks.withType<Test>().configureEach {
        systemProperty("doclens.contractsDir", contractsDir)
    }

    tasks.named<Test>("test") {
        useJUnitPlatform {
            excludeTags("external")
        }
        finalizedBy("jacocoTestReport")
    }

    // Coverage report only; thresholds come once enough code exists.
    tasks.named<JacocoReport>("jacocoTestReport") {
        reports {
            xml.required = true
            html.required = true
        }
    }

    // Real external calls (managed PG, real S3, third-party APIs): run on demand only.
    tasks.register<Test>("externalTest") {
        description = "Runs tests tagged external."
        group = "verification"
        testClassesDirs = project.the<SourceSetContainer>()["test"].output.classesDirs
        classpath = project.the<SourceSetContainer>()["test"].runtimeClasspath
        useJUnitPlatform {
            includeTags("external")
        }
    }
}
