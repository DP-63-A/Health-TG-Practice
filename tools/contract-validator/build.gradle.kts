plugins {
    java
    application
}

group = "local.healthtg"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.swagger.parser.v3:swagger-parser:2.1.24")
    implementation("com.networknt:json-schema-validator:1.5.6")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.2")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("local.healthtg.contracts.ContractValidator")
}

tasks.test {
    useJUnitPlatform()
    // Repo contracts/ is two levels up from this module when run from tools/contract-validator,
    // or via root include. Pass absolute path for stability.
    val contractsDir = rootProject.projectDir.resolve("contracts")
    inputs.dir(contractsDir).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("contracts.root", contractsDir.absolutePath)
    workingDir = rootProject.projectDir
}

tasks.named<JavaExec>("run") {
    val contractsDir = rootProject.projectDir.resolve("contracts")
    args(contractsDir.absolutePath)
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("validate") {
    group = "verification"
    description = "Run ContractValidator main against contracts/"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("local.healthtg.contracts.ContractValidator")
    args(rootProject.projectDir.resolve("contracts").absolutePath)
    workingDir = rootProject.projectDir
}
