plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.rokkystudio.dropme"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.rokkystudio.dropme"
        minSdk = 26
        targetSdk = 37
        versionCode = 32
        versionName = "1.0.32"
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
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.material)
    implementation(libs.okhttp)
}

