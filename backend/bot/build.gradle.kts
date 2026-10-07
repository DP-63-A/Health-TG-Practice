plugins {
    java
    id("org.springframework.boot") version "3.5.6"
}

group = "org.healthtg"
version = "0.1.0"

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
repositories { mavenCentral() }

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.5.6"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.networknt:json-schema-validator:1.5.6")
    implementation(project(":backend:core"))
    implementation("org.telegram:telegrambots-client:9.2.0")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("com.networknt:json-schema-validator:1.5.6")
    testImplementation("org.testcontainers:mongodb")
    testImplementation("org.testcontainers:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}
tasks.test {
    useJUnitPlatform()
    systemProperty("contracts.root", rootProject.projectDir.resolve("contracts").absolutePath)
}
tasks.register<Test>("be1Acceptance") {
    description = "Runs BE1 bot-to-core acceptance scenarios"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    systemProperty("contracts.root", rootProject.projectDir.resolve("contracts").absolutePath)
    include("**/BotCoreStorageIntegrationTest.class")
    include("**/QuickCheckinAcceptanceTest.class")
    include("**/TextDialogStorageTest.class")
    shouldRunAfter(tasks.named("test"))
}
springBoot { mainClass.set("org.healthtg.bot.BotApplication") }

tasks.register<JavaExec>("recognizeFood") {
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.healthtg.bot.recognition.FoodRecognitionDemo")
    workingDir = rootProject.projectDir
}
