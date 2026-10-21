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
        versionCode = 145
        versionName = "1.2.11"
    }

    buildFeatures {
        buildConfig = true
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
    implementation(project(":icons"))
    implementation(project(":neoui"))
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.material)
    implementation(libs.okhttp)
}

