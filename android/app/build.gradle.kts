import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.dokka)
}
android {
    namespace = "ru.arzimurotov.hotel"
    compileSdk = 36
    defaultConfig {
        applicationId = "ru.arzimurotov.hotel"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "0.9.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // Приватный ключ не попадает в Git. Debug Run работает на Mac/Windows без него.
    // Отдельный release package позволяет сохранить существующую debug-установку и её данные.
    val privateSigningFile = rootProject.file("../.local/signing.properties")
    val privateSigning = Properties().apply {
        if(privateSigningFile.exists())privateSigningFile.inputStream().use {load(it)}
    }
    if(privateSigningFile.exists())signingConfigs.create("distribution") {
        storeFile=rootProject.file("../.local/"+privateSigning.getProperty("storeFile"))
        storePassword=privateSigning.getProperty("storePassword")
        keyAlias=privateSigning.getProperty("keyAlias")
        keyPassword=privateSigning.getProperty("keyPassword")
    }
    buildTypes {
        debug {
            buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:8080/\"")
        }
        release {
            applicationIdSuffix = ".offline"
            if(privateSigningFile.exists())signingConfig=signingConfigs.getByName("distribution")
            buildConfigField("String", "API_BASE_URL", "\"https://hotel.invalid/\"")
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets.named("main") { kotlin.srcDir("../../shared/src/main/kotlin") }
    // Приложение русскоязычное; ресурсы календаря Material не выделяются по языкам.
    bundle { language { enableSplit = false } }
    testOptions { unitTests.isReturnDefaultValues = true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.mapsforge.android)
    implementation(libs.mapsforge.reader)
    implementation(libs.mapsforge.themes)
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.navigation.compose)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.runner)
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
