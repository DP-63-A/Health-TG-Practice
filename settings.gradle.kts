rootProject.name = "health-tg-practice"

include("contract-validator", "backend:api", "backend:bot")
project(":contract-validator").projectDir = file("tools/contract-validator")