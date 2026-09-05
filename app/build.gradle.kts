plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.sillyclient"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.sillyclient"
        minSdk = 26
        targetSdk = 37
        versionCode = 16
        versionName = "1.9.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += listOf(
                "META-INF/LICENSE*",
                "META-INF/NOTICE*"
            )
        }
    }

    androidResources {
        noCompress += listOf("zip")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.capacitor.android)

    testImplementation(libs.junit)
}
