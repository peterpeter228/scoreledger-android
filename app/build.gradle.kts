plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("kapt")
    id("org.jetbrains.kotlin.plugin.compose")
}
// Fixed INTERNAL-BETA identity is supplied only by trusted CI, never stored in source.
val internalStorePath = System.getenv("SCORELEDGER_INTERNAL_KEYSTORE")
val requireInternalSigning = System.getenv("SCORELEDGER_REQUIRE_INTERNAL_SIGNING") == "true"
if (requireInternalSigning) require(!internalStorePath.isNullOrBlank()) { "Fixed internal signing is required" }
val useInternalSigning = !internalStorePath.isNullOrBlank()
fun signingEnv(name: String): String = requireNotNull(System.getenv(name)?.takeIf { it.isNotBlank() }) { "Missing internal signing configuration: $name" }
android {
    namespace = "com.example.scoreledger"
    compileSdk = 36
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "com.example.scoreledger"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    if (useInternalSigning) {
        signingConfigs.create("internalBeta") {
            storeFile = file(requireNotNull(internalStorePath))
            storePassword = signingEnv("SCORELEDGER_INTERNAL_STORE_PASSWORD")
            keyPassword = signingEnv("SCORELEDGER_INTERNAL_KEY_PASSWORD")
            keyAlias = signingEnv("SCORELEDGER_INTERNAL_KEY_ALIAS")
            storeType = "PKCS12"
        }
    }
    flavorDimensions += "channel"
    productFlavors {
        create("localBeta") {
            dimension = "channel"
            applicationIdSuffix = ".beta"
            if (useInternalSigning) signingConfig = signingConfigs.getByName("internalBeta")
            versionNameSuffix = "-beta"
            resValue("string", "app_display_name", "分数记录 · 内测")
            buildConfigField("boolean", "INTERNAL_BETA", "true")
        }
        create("play") {
            if (useInternalSigning) signingConfig = signingConfigs.getByName("debug")
            dimension = "channel"
            resValue("string", "app_display_name", "分数记录")
            buildConfigField("boolean", "INTERNAL_BETA", "false")
        }
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            // Let the flavor select the internal key; play remains on its debug key.
            if (useInternalSigning) signingConfig = null
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // No signing credentials are present in this handoff kit.
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
    sourceSets.getByName("test").resources.srcDir(rootProject.file("fixtures"))
    sourceSets.getByName("androidTest").assets.srcDir(rootProject.file("fixtures"))
}
// A build supplied with internal credentials cannot produce either commercial or beta release variants.
// Ordinary development without those credentials retains the existing commercial release guard.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { variant ->
        if (useInternalSigning) variant.enable = false
    }
}
kapt { arguments { arg("room.schemaLocation", "$projectDir/schemas") } }
val verifyCommercialRelease by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine(if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3", "tools/release_guard.py")
}
tasks.matching { it.name == "prePlayReleaseBuild" || it.name == "preLocalBetaReleaseBuild" }
    .configureEach { dependsOn(verifyCommercialRelease) }
dependencies {
    implementation(project(":core"))
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.compose.ui:ui:1.9.4")
    implementation("androidx.compose.foundation:foundation:1.9.4")
    implementation("androidx.compose.material3:material3:1.3.2")
    implementation("androidx.compose.ui:ui-tooling-preview:1.9.4")
    debugImplementation("androidx.compose.ui:ui-tooling:1.9.4")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    kapt("androidx.room:room-compiler:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.room:room-testing:2.8.4")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.9.4")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.9.4")
}
