plugins {
    id("com.android.library")
    `maven-publish`
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

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = false
    }
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "org.donnc"
            artifactId = "fzimra-simulator"
            version = project.version.toString()
            afterEvaluate { from(components["release"]) }
        pom {
            name.set("fzimra simulator")
            description.set("A ZIMRA Fiscal Device Gateway simulator for developing and testing with fzimra.")
            url.set("https://github.com/DonnC/zimra-fdms-android")
            licenses {
                license {
                    name.set("MIT License")
                    url.set("https://opensource.org/licenses/MIT")
                }
            }
            developers {
                developer {
                    id.set("DonnC")
                    name.set("Donald Chinhuru")
                }
            }
            scm {
                url.set("https://github.com/DonnC/zimra-fdms-android")
                connection.set("scm:git:https://github.com/DonnC/zimra-fdms-android.git")
            }
        }
        }
    }
    repositories {
        maven {
            name = "local"
            url = uri(rootProject.layout.buildDirectory.dir("repository"))
        }
    }
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
