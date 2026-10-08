import java.security.KeyStore
import java.security.MessageDigest

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
        versionCode = 70
        versionName = "1.12.0"
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

val verifyUpgradeSigning = tasks.register("verifyUpgradeSigning") {
    val signing = android.signingConfigs.getByName("debug")
    val storeFile = signing.storeFile
    val storePassword = signing.storePassword
    val keyAlias = signing.keyAlias
    doLast {
        val store = storeFile
            ?: throw GradleException("The historical Android signing keystore is required")
        check(store.isFile) { "Restore the historical signing keystore before building a release" }
        val keyStore = KeyStore.getInstance("JKS")
        store.inputStream().use { keyStore.load(it, storePassword?.toCharArray()) }
        val certificate = keyStore.getCertificate(keyAlias)
            ?: throw GradleException("The historical Android signing certificate is missing")
        val actual = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString("") { "%02x".format(it) }
        check(actual == "97f0958bcce5ab059cb31bed5826303168b6877f22e0385de3058c2a21dbecbf") {
            "Release signing identity changed; this APK would not update existing installations"
        }
    }
}

tasks.configureEach {
    if (name == "preReleaseBuild") dependsOn(verifyUpgradeSigning)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.documentfile)
    implementation(libs.capacitor.android)

    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
}
