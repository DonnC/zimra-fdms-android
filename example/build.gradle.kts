plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.donnc.fzimra.example"
    // Current Compose and Navigation need API 37 to compile against; fzimra itself
    // compiles against 36, and apps can use either.
    compileSdk = 37

    defaultConfig {
        applicationId = "org.donnc.fzimra.example"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // BouncyCastle's jars each carry the same licence and OSGi files.
        resources.pickFirsts += setOf("META-INF/LICENSE.md", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":"))
    implementation(project(":simulator"))

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("androidx.datastore:datastore-preferences:1.2.1")

    // Draws receipt QR codes. Rendering is the app's job, not fzimra's.
    implementation("com.google.zxing:core:3.5.4")
}
