plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
    // AGP's KMP library plugin only registers lint tasks for the main compilation when the
    // standalone lint plugin is also applied; without it the module has no :lint task at all.
    // No version: com.android.lint ships inside AGP, which is already on the classpath.
    id("com.android.lint")
}

kotlin {
    android {
        namespace = "io.github.meko123456.ridetogether.shared"
        compileSdk = 37
        minSdk = 26
        withHostTestBuilder {}
    }

    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            // Instant/Duration in the domain: the alert engine reasons about time, and must do
            // so on a clock the tests control rather than the system clock.
            api(libs.kotlinx.datetime)
            implementation(libs.kotlinx.coroutines.core)
            // The Realtime Database is spoken to over its REST and streaming API rather than
            // through Firebase's platform SDKs, so one client serves both apps (#10).
            implementation(libs.ktor.client.core)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}

// RtdbEmulatorTest runs only when `firebase emulators:exec` has set FIREBASE_DATABASE_EMULATOR_HOST,
// and loads the repo's database.rules.json into the emulator. Neither is a task input by default, so
// Gradle would answer a run under the emulator, or a run after the rules changed, with the cached
// result of an earlier one: ten skips, or yesterday's rules, reported as success.
tasks.withType<Test>().configureEach {
    inputs.property(
        "firebaseDatabaseEmulatorHost",
        providers.environmentVariable("FIREBASE_DATABASE_EMULATOR_HOST").orElse(""),
    )
    inputs.property(
        "firebaseAuthEmulatorHost",
        providers.environmentVariable("FIREBASE_AUTH_EMULATOR_HOST").orElse(""),
    )
    inputs.file(rootProject.file("database.rules.json")).withPropertyName("databaseRules")
}
