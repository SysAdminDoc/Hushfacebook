dependencies {
    implementation(project(":extensions:shared:library"))
}

extension {
    name = "extensions/shared.mpe"
}

android {
    // Unique per extension to avoid install-time package collisions.
    namespace = "app.morphe.extension.shared"

    defaultConfig {
        // The library it carries runs only inside Facebook 577 and 580, which declare API 30.
        minSdk = 30
    }
}
