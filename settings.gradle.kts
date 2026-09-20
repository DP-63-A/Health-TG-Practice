rootProject.name = "health-tg-practice"

include("contract-validator")
include("backend")
project(":contract-validator").projectDir = file("tools/contract-validator")
