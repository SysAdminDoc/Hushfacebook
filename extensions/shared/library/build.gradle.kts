plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.morphe.extension"
    compileSdk = 35

    defaultConfig {
        // Facebook 577 and 580 declare minSdk 30, and this library only ever runs inside them.
        minSdk = 30
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The payload is injected into Facebook, whose floor is API 30. javac compiles against a
    // modern JDK and Robolectric runs on one, so a library call or a type above that floor is
    // green all the way to a phone, where D8 has left it as a stub that throws. NewApi is the
    // only check here that reads the SDK_INT guards instead of flagging every guarded call.
    lint {
        checkOnly += "NewApi"
        error += "NewApi"
        // NewApi catches a call above the floor. ObsoleteSdkInt catches an SDK_INT guard at or
        // below it, which is dead code today and a wrong floor the next time minSdk moves.
        checkOnly += "ObsoleteSdkInt"
        error += "ObsoleteSdkInt"
        abortOnError = true
    }
}

dependencies {
    compileOnly(libs.annotation)
}
