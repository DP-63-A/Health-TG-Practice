plugins {
    java
    id("org.springframework.boot") version "3.5.6"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "org.healthtg"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":backend:core"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-mongodb")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:mongodb")
    testImplementation("org.testcontainers:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}
springBoot { mainClass.set("org.healthtg.HealthTgApplication") }

// BE3-05: local demo seed/reset commands. Parameters: -PseedRandom=<long> -PseedStartDate=<yyyy-MM-dd>.
fun registerSeedTask(taskName: String, command: String, taskDescription: String) =
    tasks.register<JavaExec>(taskName) {
        group = "demo"
        description = taskDescription
        mainClass.set("org.healthtg.seed.SeedCli")
        classpath = sourceSets["main"].runtimeClasspath
        javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
        args(command)
        providers.gradleProperty("seedRandom").orNull?.let { args("--health-tg.seed.random-seed=$it") }
        providers.gradleProperty("seedStartDate").orNull?.let { args("--health-tg.seed.start-date=$it") }
    }

registerSeedTask("seedDemo", "seed", "Create or re-apply the three synthetic 21-day demo profiles (idempotent)")
registerSeedTask("resetDemo", "reset", "Remove only seed entries of the configured demo accounts (demo environment only)")
