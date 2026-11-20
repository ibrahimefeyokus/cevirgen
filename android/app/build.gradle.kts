plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.ibrahim.cevirgen"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "com.ibrahim.cevirgen"
        // HEIC okuma (ImageDecoder) ve Media3 için Android 8.0+
        minSdk = 26
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    packaging {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }

    buildTypes {
        release {
            // Şimdilik debug anahtarıyla imzalanır; mağaza için ayrı anahtar gerekir.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}

dependencies {
    // FileProvider (dosyaları paylaşmak / açmak için)
    implementation("androidx.core:core:1.13.1")
    // PDF birleştirme / bölme / sıkıştırma (Apache 2.0)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // Video ve ses dönüştürme (Apache 2.0)
    val media3 = "1.11.1"
    implementation("androidx.media3:media3-transformer:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-common:$media3")
    // MP3 kodlayıcı: LAME'in Java sürümü (LGPL)
    implementation("de.sciss:jump3r:1.0.5")
    // Taranmış PDF sayfaları için çevrimdışı yazı tanıma (model uygulamanın içinde)
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
