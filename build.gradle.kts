plugins {
    // AGP 9 compiles Kotlin itself (built-in Kotlin), so no separate Kotlin plugin.
    id("com.android.library") version "9.4.1"
}

group = "org.donnc"
version = "0.1.0-SNAPSHOT"

android {
    namespace = "org.donnc.fzimra"
    compileSdk = 36

    defaultConfig {
        // Android 8.0: hardware-backed Keystore for EC P-256. StrongBox (Android 9+)
        // is used when the device has it.
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = false
    }

    packaging {
        // Only the on-device test APK packages dependencies: BouncyCastle's jars
        // each carry the same licence and OSGi files. Keep one copy.
        resources.pickFirsts += setOf("META-INF/LICENSE.md", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
}

kotlin {
    jvmToolchain(17)
    explicitApi() // every public declaration states its visibility: the contract is deliberate
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Reads the JSON test vectors, keeping numbers exact; not shipped with the library.
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    // Parses and verifies the certificate requests fzimra writes; not shipped with the library.
    testImplementation("org.bouncycastle:bcpkix-jdk18on:1.86")

    // On-device tests of the Android Keystore integration (./gradlew connectedAndroidTest).
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("org.bouncycastle:bcpkix-jdk18on:1.86")
}
