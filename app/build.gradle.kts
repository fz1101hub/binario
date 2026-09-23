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
    .orElse(providers.gradleProperty("BINARIO_APP_VERSION_CODE"))
    .get()
    .toInt()
val binarioVersionName = providers.gradleProperty("BINARIO_VERSION_NAME").get()

android {
    namespace = "it.binario.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "it.binario.app"
        minSdk = 26
        targetSdk = 36
        versionCode = binarioVersionCode
        versionName = binarioVersionName

        // Public/operator best-effort pages are an explicit personal-build opt-in.
        // Enable only with -PBINARIO_PERSONAL_REALTIME_SOURCES=true.
        val personalRealtimeSources = providers.gradleProperty("BINARIO_PERSONAL_REALTIME_SOURCES")
            .orElse("false")
            .get()
        buildConfigField("boolean", "PERSONAL_REALTIME_SOURCES", personalRealtimeSources)
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

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

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        checkDependencies = true
        disable += "GradleDependency"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("org.mobilitydata:gtfs-realtime-bindings:0.0.8")
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    // Android supplies org.json at runtime; this artifact is only the JVM unit-test implementation.
    testImplementation("org.json:json:20240303")
}

// Reproduction profile for the large embedded-GTFS regression. The property
// limits the forked JUnit VM, not only the Gradle daemon.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    providers.gradleProperty("BINARIO_TEST_MAX_HEAP").orNull?.let { maxHeapSize = it }
}
