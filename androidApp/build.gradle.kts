import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

/**
 * The Firebase project rides go through (#10), if this build has one: each setting from the
 * environment, else from local.properties, which git ignores. Never from a committed file, since a
 * project is whoever deploys the app's to give. With no database URL the app keeps its in-memory
 * backend, on which a ride exists only on the phone that made it. See the README.
 */
val firebaseSettings: Map<String, String> = run {
    val local = Properties()
    providers.fileContents(rootProject.layout.projectDirectory.file("local.properties"))
        .asText.orNull?.let { local.load(it.reader()) }
    listOf("DATABASE_URL", "DATABASE_NAMESPACE", "API_KEY", "AUTH_EMULATOR_HOST").associateWith { name ->
        val key = "RIDETOGETHER_FIREBASE_$name"
        providers.environmentVariable(key).orNull ?: local.getProperty(key).orEmpty()
    }
}

android {
    namespace = "io.github.meko123456.ridetogether.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.meko123456.ridetogether"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0-dev"

        firebaseSettings.forEach { (name, value) ->
            val literal = value.replace("\\", "\\\\").replace("\"", "\\\"")
            buildConfigField("String", "FIREBASE_$name", "\"$literal\"")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // LogNotTimber started firing the moment MapLibre was added, because MapLibre pulls
        // Timber onto the classpath transitively and the check assumes anything with Timber
        // available should be using it. This app has no Timber dependency of its own and logs
        // through android.util.Log deliberately; adopting a logging library to satisfy a hint
        // about a transitive dependency would be the tail wagging the dog.
        disable += "LogNotTimber"
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.play.services.location)
    implementation(libs.maplibre)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.kotlinx.coroutines.test)
}
