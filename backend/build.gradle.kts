// Backend groups two independent applications; it has no Java sources or runtime.
plugins { base }

tasks.named("check") {
    dependsOn(":backend:api:check", ":backend:bot:check")
}
tasks.named("assemble") {
    dependsOn(":backend:api:assemble", ":backend:bot:assemble")
}
tasks.named("clean") {
    dependsOn(":backend:api:clean", ":backend:bot:clean")
}