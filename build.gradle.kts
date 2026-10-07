plugins {
    // Root aggregator; applications live in :backend:api and :backend:bot.
}

subprojects {
    plugins.withId("java") {
        apply(plugin = "checkstyle")

        configure<CheckstyleExtension> {
            toolVersion = "10.21.2"
            configFile = rootProject.file("config/checkstyle/checkstyle.xml")
        }
    }
}

tasks.register("validateContracts") {
    group = "verification"
    description = "Validate OpenAPI and JSON contract examples (BE1-01)"
    dependsOn(":contract-validator:test")
}
