plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseSigningPropertyNames = listOf(
    "BINARIO_RELEASE_STORE_FILE",
    "BINARIO_RELEASE_STORE_PASSWORD",
    "BINARIO_RELEASE_KEY_ALIAS",
    "BINARIO_RELEASE_KEY_PASSWORD",
)
val releaseSigningProperties = releaseSigningPropertyNames.associateWith { propertyName ->
    providers.gradleProperty(propertyName)
        .orElse(providers.environmentVariable(propertyName))
}
val releaseSigningConfigured = releaseSigningProperties.values.all { it.isPresent }
if (releaseSigningProperties.values.any { it.isPresent } && !releaseSigningConfigured) {
    logger.warn("Release signing is only enabled when all BINARIO_RELEASE_* properties are set; building unsigned.")
}

val binarioVersionCode = providers.gradleProperty("BINARIO_VERSION_CODE")
    .orElse(providers.gradleProperty("BINARIO_WEAR_VERSION_CODE"))
    .get()
    .toInt()
val binarioVersionName = providers.gradleProperty("BINARIO_VERSION_NAME").get()

android {
    namespace = "it.binario.wear"
    compileSdk = 36
    defaultConfig {
        // Phone and watch must share package name and signing identity for the Wear Data Layer.
        applicationId = "it.binario.app"
        minSdk = 30
        targetSdk = 36
        versionCode = binarioVersionCode
        versionName = binarioVersionName
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = rootProject.file(releaseSigningProperties.getValue("BINARIO_RELEASE_STORE_FILE").get())
                storePassword = releaseSigningProperties.getValue("BINARIO_RELEASE_STORE_PASSWORD").get()
                keyAlias = releaseSigningProperties.getValue("BINARIO_RELEASE_KEY_ALIAS").get()
                keyPassword = releaseSigningProperties.getValue("BINARIO_RELEASE_KEY_PASSWORD").get()
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.wear.compose:compose-material3:1.6.2")
    implementation("androidx.wear.compose:compose-foundation:1.6.2")
    implementation("androidx.wear.compose:compose-ui-tooling:1.6.2")
    implementation("androidx.wear.tiles:tiles:1.6.2")
    implementation("androidx.wear.protolayout:protolayout:1.4.2")
    implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.2.1")
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    implementation("androidx.concurrent:concurrent-futures:1.2.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
