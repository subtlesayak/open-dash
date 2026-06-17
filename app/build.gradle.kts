import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // Firebase is OPTIONAL / bring-your-own-project: the Google Services plugin is only
    // applied when a google-services.json is present. Without it the app builds and runs
    // fully local (no sync) — a rider who doesn't want multi-device sync just omits the
    // file. To enable sync, drop your own Firebase project's google-services.json in app/.
    alias(libs.plugins.google.services) apply false
}

if (project.file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

fun String.asBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

fun loadProperties(vararg names: String): Properties =
    Properties().also { props ->
        names.map { rootProject.file(it) }
            .filter { it.exists() }
            .forEach { file -> file.inputStream().use(props::load) }
    }

val localSecrets = loadProperties("local.defaults.properties", "local.properties", "secrets.properties")
fun localSecret(name: String, fallback: String = ""): String =
    providers.gradleProperty(name)
        .orElse(providers.environmentVariable(name))
        .orElse(localSecrets.getProperty(name, fallback))
        .get()

android {
    namespace = "com.example.opendash"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.opendash.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 13
        versionName = "1.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "String",
            "DASH_DEFAULT_PASSWORD",
            localSecret("OPENDASH_DASH_DEFAULT_PASSWORD").asBuildConfigString(),
        )
        buildConfigField(
            "String",
            "GOOGLE_WEB_CLIENT_ID",
            localSecret("OPENDASH_GOOGLE_WEB_CLIENT_ID").asBuildConfigString(),
        )
        manifestPlaceholders["MAPS_API_KEY"] = localSecret("MAPS_API_KEY", "DEFAULT_API_KEY")
    }

    flavorDimensions += "navProvider"
    productFlavors {
        create("oss") {
            dimension = "navProvider"
            isDefault = true
            buildConfigField("boolean", "GOOGLE_NAV_FLAVOR", "false")
            buildConfigField("boolean", "MAPS_API_KEY_PRESENT", "false")
        }
        create("googleNav") {
            dimension = "navProvider"
            applicationIdSuffix = ".googlenav"
            versionNameSuffix = "-google-nav"
            val mapsApiKey = localSecret("MAPS_API_KEY", "DEFAULT_API_KEY")
            buildConfigField("boolean", "GOOGLE_NAV_FLAVOR", "true")
            buildConfigField("boolean", "MAPS_API_KEY_PRESENT", (mapsApiKey.isNotBlank() && mapsApiKey != "DEFAULT_API_KEY").toString())
        }
    }

    val releaseStoreFilePath = providers.gradleProperty("OPENDASH_RELEASE_STORE_FILE").orNull
    val releaseStorePassword = providers.gradleProperty("OPENDASH_RELEASE_STORE_PASSWORD").orNull
    val releaseKeyAlias = providers.gradleProperty("OPENDASH_RELEASE_KEY_ALIAS").orNull
    val releaseKeyPassword = providers.gradleProperty("OPENDASH_RELEASE_KEY_PASSWORD").orNull
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
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        buildConfig = true
        compose = true
        resValues = true
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
    "googleNavImplementation"(libs.google.navigation) {
        exclude(group = "org.chromium.net", module = "cronet-api")
        exclude(group = "org.chromium.net", module = "cronet-common")
        exclude(group = "org.chromium.net", module = "cronet-fallback")
    }
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
