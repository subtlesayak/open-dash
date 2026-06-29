import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // Firebase is OPTIONAL / bring-your-own-project: the Google Services plugin is only
    // applied when google-services.json contains a client for the requested package.
    // Without it the app builds and runs fully local (no sync).
    alias(libs.plugins.google.services) apply false
}

val localApplicationId = "com.opendash.app"
val playApplicationId = "com.subtlesayak.opendash"
val debugApplicationIdSuffix = ".mui3"
val googleServicesFile = project.file("google-services.json")
val firebaseClientPackages = if (googleServicesFile.exists()) {
    """"package_name"\s*:\s*"([^"]+)"""".toRegex()
        .findAll(googleServicesFile.readText())
        .map { it.groupValues[1] }
        .toSet()
} else {
    emptySet()
}
val requestedTasks = gradle.startParameter.taskNames
val firebaseFlavorNames = listOf("local", "play")
val requestedTaskMentionsFirebaseFlavor = requestedTasks.any { task ->
    firebaseFlavorNames.any { flavor -> task.contains(flavor, ignoreCase = true) }
}
val requestedFirebasePackageCandidates = mutableSetOf<String>().apply {
    fun addFlavor(flavorName: String, applicationId: String) {
        val mentionsFlavor = requestedTasks.isEmpty() ||
            !requestedTaskMentionsFirebaseFlavor ||
            requestedTasks.any { it.contains(flavorName, ignoreCase = true) }
        if (!mentionsFlavor) return
        val debugRequested = requestedTasks.isEmpty() ||
            requestedTasks.any { it.contains("debug", ignoreCase = true) }
        val releaseRequested = requestedTasks.isEmpty() ||
            requestedTasks.any { task ->
                task.contains("release", ignoreCase = true) ||
                    task.contains("bundle", ignoreCase = true)
            }
        if (debugRequested) add(applicationId + debugApplicationIdSuffix)
        if (releaseRequested) add(applicationId)
        add(applicationId)
    }
    addFlavor("local", localApplicationId)
    addFlavor("play", playApplicationId)
}
val hasFirebaseConfig = requestedFirebasePackageCandidates.any { it in firebaseClientPackages }
val buildingBundle = requestedTasks.any { it.contains("bundle", ignoreCase = true) }

if (hasFirebaseConfig) {
    apply(plugin = "com.google.gms.google-services")
}

val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
val googleWebClientId = providers.gradleProperty("GOOGLE_WEB_CLIENT_ID").orNull
    ?: localProperties.getProperty("GOOGLE_WEB_CLIENT_ID").orEmpty()

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("key.properties")

if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.example.opendash"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = localApplicationId
        minSdk = 24
        targetSdk = 36
        versionCode = 17
        versionName = "1.3.4"

        buildConfigField(
            "String",
            "GOOGLE_WEB_CLIENT_ID",
            "\"${googleWebClientId.replace("\\", "\\\\").replace("\"", "\\\"")}\"",
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("local") {
            dimension = "distribution"
            applicationId = localApplicationId
        }
        create("play") {
            dimension = "distribution"
            applicationId = playApplicationId
        }
    }

    val releaseStoreFilePath = providers.gradleProperty("OPENDASH_RELEASE_STORE_FILE").orNull
        ?: localProperties.getProperty("OPENDASH_RELEASE_STORE_FILE")
        ?: keystoreProperties.getProperty("storeFile")
    val releaseStorePassword = providers.gradleProperty("OPENDASH_RELEASE_STORE_PASSWORD").orNull
        ?: localProperties.getProperty("OPENDASH_RELEASE_STORE_PASSWORD")
        ?: keystoreProperties.getProperty("storePassword")
    val releaseKeyAlias = providers.gradleProperty("OPENDASH_RELEASE_KEY_ALIAS").orNull
        ?: localProperties.getProperty("OPENDASH_RELEASE_KEY_ALIAS")
        ?: keystoreProperties.getProperty("keyAlias")
    val releaseKeyPassword = providers.gradleProperty("OPENDASH_RELEASE_KEY_PASSWORD").orNull
        ?: localProperties.getProperty("OPENDASH_RELEASE_KEY_PASSWORD")
        ?: keystoreProperties.getProperty("keyPassword")
    val hasReleaseKeystore =
        !releaseStoreFilePath.isNullOrBlank() &&
            !releaseStorePassword.isNullOrBlank() &&
            !releaseKeyAlias.isNullOrBlank() &&
            !releaseKeyPassword.isNullOrBlank()

    signingConfigs {
        create("release") {
            if (hasReleaseKeystore) {
                storeFile = file(releaseStoreFilePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".mui3"
            resValue("string", "app_name", "OpenDash")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Release builds must never use the debug signing config. Local developers and CI
            // should provide their own release keystore through Gradle properties or CI secrets:
            // OPENDASH_RELEASE_STORE_FILE, OPENDASH_RELEASE_STORE_PASSWORD,
            // OPENDASH_RELEASE_KEY_ALIAS, and OPENDASH_RELEASE_KEY_PASSWORD.
            // When absent, Gradle produces an unsigned release artifact instead of falling
            // back to debug signing.
            if (hasReleaseKeystore) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
        compose = true
        resValues = true
    }

    // GitHub releases can publish the matching ABI APK (arm64 for most current phones)
    // instead of forcing every device to download all four MapLibre native libraries.
    // The universal artifact remains available for simple local/debug installation.
    splits {
        abi {
            isEnable = !buildingBundle
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }
}

androidComponents {
    onVariants { variant ->
        val variantApplicationId = variant.applicationId.get()
        if (hasFirebaseConfig && variantApplicationId !in firebaseClientPackages) {
            val taskVariantName = variant.name.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase() else it.toString()
            }
            tasks.matching { it.name == "process${taskVariantName}GoogleServices" }
                .configureEach { enabled = false }
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.ui.text.google.fonts)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.androidx.security.crypto)
    implementation(libs.google.identity.googleid)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.maplibre)
    implementation(libs.maplibre.annotation)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
