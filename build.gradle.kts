plugins {
    // Root aggregator; applications live in :backend:api and :backend:bot.
}

tasks.register("validateContracts") {
    group = "verification"
    description = "Validate OpenAPI and JSON contract examples (BE1-01)"
    dependsOn(":contract-validator:test")
}
