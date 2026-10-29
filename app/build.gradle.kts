import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val localBackend = providers.gradleProperty("localBackend").orNull == "true"
val backend = Properties().apply {
    rootProject.file(if(localBackend) "backend.local.properties" else "backend.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun backendString(key: String): String = "\"" + backend.getProperty(key, "")
    .replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "") + "\""

android {
    namespace = "ph.capstone.archeryclub"
    compileSdk { version = release(37) }
    defaultConfig {
        applicationId = "ph.capstone.archeryclub"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SUPABASE_URL", backendString("SUPABASE_URL"))
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", backendString("SUPABASE_PUBLISHABLE_KEY"))
        buildConfigField("boolean", "LOCAL_BACKEND", localBackend.toString())
        buildConfigField("boolean", "EMAIL_ENABLED", backend.getProperty("ENABLE_EMAIL", "false").toBoolean().toString())
    }
    buildTypes {
        debug {
            applicationIdSuffix = if(localBackend) ".local" else ".dev"
            versionNameSuffix = if(localBackend) "-local-test" else "-dev"
        }
        release {
            optimization { enable = true }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
}

val validateReleaseBackend = tasks.register("validateReleaseBackend") {
    doLast {
        check(!localBackend) { "Release builds cannot use an emulator-local backend." }
        val url = backend.getProperty("SUPABASE_URL", "")
        val key = backend.getProperty("SUPABASE_PUBLISHABLE_KEY", "")
        check(Regex("https://[a-z0-9]{20}\\.supabase\\.co/?").matches(url)) { "Configure the approved Supabase project URL before building a release." }
        check(key.startsWith("sb_publishable_") && !key.contains("REPLACE") && key.length > 30) { "Configure a publishable client key; privileged keys must never be bundled." }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(validateReleaseBackend) }

dependencies {
    implementation(platform("io.github.jan-tennert.supabase:bom:3.2.2"))
    implementation("io.github.jan-tennert.supabase:auth-kt")
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.github.jan-tennert.supabase:storage-kt")
    implementation("io.ktor:ktor-client-okhttp:3.2.2")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation(platform("androidx.compose:compose-bom:2026.02.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
