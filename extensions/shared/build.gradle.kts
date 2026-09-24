dependencies {
    implementation(project(":extensions:shared:library"))
}

extension {
    name = "extensions/shared.mpe"
}

android {
    // Unique per extension to avoid install-time package collisions.
    namespace = "app.morphe.extension.shared"
}
