/**
 * VisionPush Android SDK – Module-level Gradle build script (Kotlin DSL)
 *
 * Targets Android API 21+ with Kotlin-first source and full Java compatibility.
 * Dependencies: Firebase Cloud Messaging, WorkManager, OkHttp 4, Kotlin Coroutines.
 */

import java.net.URI

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
    id("org.jetbrains.dokka")
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

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
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

    // ── Encrypted storage for sensitive SDK state (FCM token, device ID) ─────
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // ── JSON serialisation (lightweight, zero-dependency) ────────────────────
    implementation("org.json:json:20231013")

    // ── Unit tests ────────────────────────────────────────────────────────────
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("org.robolectric:robolectric:4.11.1")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

// ── Dokka API documentation ───────────────────────────────────────────────────
tasks.withType<org.jetbrains.dokka.gradle.DokkaTask>().configureEach {
    dokkaSourceSets {
        named("main") {
            moduleName.set("VisionPush Android SDK")
            includes.from("Module.md")
            sourceLink {
                localDirectory.set(file("src/main/kotlin"))
                // URL(String) is deprecated since JDK 20; build via URI (imported
                // at the top) and convert. Avoids the deprecated URL constructor.
                remoteUrl.set(URI("https://github.com/visionlab-de/visionpush/tree/main/sdks/android/src/main/kotlin").toURL())
                remoteLineSuffix.set("#L")
            }
        }
    }
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

    repositories {
        // Local Maven cache — useful for host-app integration testing.
        mavenLocal()

        // GitHub Packages — published automatically by the sdk-release workflow.
        // Credentials are injected via GITHUB_ACTOR / GITHUB_TOKEN env vars in CI.
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/visionlab-de/visionpush")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: project.findProperty("gpr.user") as String?
                password = System.getenv("GITHUB_TOKEN") ?: project.findProperty("gpr.token") as String?
            }
        }
    }
}
