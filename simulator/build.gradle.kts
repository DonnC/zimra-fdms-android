plugins {
    id("com.android.library")
}

group = "org.donnc"
version = rootProject.version

android {
    namespace = "org.donnc.fzimra.simulator"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = false
    }
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

dependencies {
    // The simulator speaks fzimra's transport and model types.
    api(project(":"))
    // Issues device certificates from certificate requests, as ZIMRA does.
    implementation("org.bouncycastle:bcpkix-jdk18on:1.86")
    // Reads and writes the gateway's JSON, keeping numbers exactly as sent.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    testImplementation("junit:junit:4.13.2")
}
