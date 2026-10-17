plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.blico"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.blico"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1"
        // Só o S25 Ultra e celulares parecidos: corta do APK as bibliotecas nativas de 32 bits e de emulador.
        ndk { abiFilters += "arm64-v8a" }
    }

    // Assinatura fixa vinda dos segredos do CI, para o login do Google reconhecer o app e um APK
    // novo instalar por cima do antigo. Sem os segredos (build local), vale a chave de debug padrão.
    val keystore = System.getenv("BLICO_KEYSTORE_FILE")?.let(::file)?.takeIf { it.exists() }
    signingConfigs {
        if (keystore != null) {
            create("blico") {
                storeFile = keystore
                storePassword = System.getenv("BLICO_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("BLICO_KEY_ALIAS")
                keyPassword = System.getenv("BLICO_KEY_PASSWORD")?.takeIf { it.isNotEmpty() } ?: System.getenv("BLICO_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        if (keystore != null) {
            debug { signingConfig = signingConfigs.getByName("blico") }
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        // O LiteRT-LM traz a mesma biblioteca JNI por mais de um caminho.
        jniLibs { pickFirsts.add("**/liblitertlm_jni.so") }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":brain"))
    implementation(project(":voice"))
    implementation(project(":executor"))
    implementation(project(":skills"))
    implementation(project(":memory"))
    implementation(project(":confirmation"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.coroutines.android)
    implementation(libs.play.services.auth)
}
