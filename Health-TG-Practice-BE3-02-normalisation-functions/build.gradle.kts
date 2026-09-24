plugins {
    // Root aggregator; real build lives in :contract-validator
}

tasks.register("validateContracts") {
    group = "verification"
    description = "Validate OpenAPI and JSON contract examples (BE1-01)"
    dependsOn(":contract-validator:test")
}
