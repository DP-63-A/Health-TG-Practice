rootProject.name = "health-tg-practice"

include(
    "contract-validator",
    "backend:core",
    "backend:api",
    "backend:bot",
    "analytics"
)

project(":contract-validator").projectDir = file("tools/contract-validator")
project(":analytics").projectDir = file("core/analytics")