/**
 * VisionPush Android SDK – Module-level Gradle build script (Kotlin DSL)
 *
 * Targets Android API 21+ with Kotlin-first source and full Java compatibility.
 * Dependencies: Firebase Cloud Messaging, WorkManager, OkHttp 4, Kotlin Coroutines.
 */

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

android {
    namespace = "de.visionlab.visionpush"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        targetSdk = 34

        // SDK version constants injected into BuildConfig at compile time
        buildConfigField("String", "VISIONPUSH_SDK_VERSION", "\"1.0.0\"")
        buildConfigField("String", "VISIONPUSH_DEFAULT_API_BASE_URL", "\"https://api.visionpush.de/v1\"")

        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
        // Expose suspension functions with a blocking Java-compatible variant via @JvmStatic
        freeCompilerArgs += listOf("-Xjvm-default=all")
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

dependencies {
    // ── Firebase Cloud Messaging ──────────────────────────────────────────────
    implementation(platform("com.google.firebase:firebase-bom:32.7.4"))
    implementation("com.google.firebase:firebase-messaging-ktx")

    // ── AndroidX WorkManager (background token sync & event delivery) ─────────
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // ── HTTP client ───────────────────────────────────────────────────────────
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // ── Kotlin Coroutines ─────────────────────────────────────────────────────
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // ── Core AndroidX ─────────────────────────────────────────────────────────
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")

    // ── JSON serialisation (lightweight, zero-dependency) ────────────────────
    implementation("org.json:json:20231013")

    // ── Unit tests ────────────────────────────────────────────────────────────
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

// ── Maven publication (for local/private Maven repository consumption) ────────
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "de.visionlab"
            artifactId = "visionpush-android"
            version = "1.0.0"

            afterEvaluate {
                from(components["release"])
            }

            pom {
                name.set("VisionPush Android SDK")
                description.set("Official Android SDK for the VisionPush push notification service.")
                url.set("https://visionpush.de")

                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }

                developers {
                    developer {
                        id.set("visionlab")
                        name.set("VisionLab GmbH")
                        email.set("sdk@visionpush.de")
                    }
                }
            }
        }
    }
}
