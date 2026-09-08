plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.powerrun.dashboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.powerrun.dashboard"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
}
