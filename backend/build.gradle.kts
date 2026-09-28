// Backend groups two independent applications; it has no Java sources or runtime.
plugins { base }

tasks.named("check") {
    dependsOn(":backend:core:check", ":backend:api:check", ":backend:bot:check")
}
tasks.named("assemble") {
    dependsOn(":backend:core:assemble", ":backend:api:assemble", ":backend:bot:assemble")
}
tasks.named("clean") {
    dependsOn(":backend:core:clean", ":backend:api:clean", ":backend:bot:clean")
}
