import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hingevala"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hingevala"
        minSdk = 30 // Wireless debugging arrived in Android 11
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sideloaded only, so the debug key is enough to install it.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // selectors.json, config.json and comments.txt come from the Python bot at the repo
    // root, so both share one copy.
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/botAssets"))
    // u2.jar: the uiautomator2 server, the same one the Python bot starts on the device.
    sourceSets["main"].assets.srcDir("src/main/assets-u2")

    packaging { resources.excludes += "META-INF/versions/**" }
}

dependencies {
    // Talks to the phone's own adb over Wireless debugging (as Shizuku does), which is
    // what lets the app start uiautomator without a computer.
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("com.github.MuntashirAkon:sun-security-android:1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

val copyBotAssets by tasks.registering(Copy::class) {
    from(rootProject.file("../selectors.json"), rootProject.file("../config.json"), rootProject.file("../comments.txt"))
    into(layout.buildDirectory.dir("generated/botAssets"))
}
tasks.named("preBuild") { dependsOn(copyBotAssets) }
