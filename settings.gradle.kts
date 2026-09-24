rootProject.name = "health-tg-practice"

include("contract-validator")
project(":contract-validator").projectDir = file("tools/contract-validator")

<<<<<<< HEAD
include("analytics")
project(":analytics").projectDir = file("core/analytics")
=======
include("backend")
>>>>>>> origin/develop
