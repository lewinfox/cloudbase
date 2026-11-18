plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.cloudbase"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.cloudbase"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sideloaded onto one phone, so the debug key is fine.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

// Screenshot tests render the real screens with Robolectric; slow, so opt-in: ./gradlew :app:testDebugUnitTest -Pscreenshots
tasks.withType<Test>().configureEach {
    if (!project.hasProperty("screenshots")) exclude("**/screenshots/**")
    systemProperty("screenshotDir", rootProject.file("docs/screenshots").absolutePath)
}

dependencies {
    implementation(project(":core"))
    // Deliberately tiny: no AppCompat, Material, Fragments or map SDK. Everything is framework views.
    implementation("androidx.core:core:1.13.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
