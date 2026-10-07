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

tasks.named<Test>("test") {
    exclude("**/Be1AcceptanceIntegrationTest.class")
}

tasks.register<Test>("be1Acceptance") {
    description = "Runs BE1 HTTP and MongoDB acceptance scenarios"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    include("**/Be1AcceptanceIntegrationTest.class")
    include("**/AuthHttpIntegrationTest.class")
    include("**/CoreStorageIntegrationTest.class")
    include("**/FilesHttpIntegrationTest.class")
    shouldRunAfter(tasks.named("test"))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}
springBoot { mainClass.set("org.healthtg.HealthTgApplication") }

tasks.register<JavaExec>("demoData") {
    group = "application"
    description = "Seeds or safely resets the local BE3-05 demo dataset"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.healthtg.seed.DemoDataCommand")
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })
    args = (findProperty("demoArgs") as String?)?.split(" ") ?: emptyList()
}
