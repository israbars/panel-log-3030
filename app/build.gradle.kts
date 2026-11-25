plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.panellog.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.panellog.app"
        minSdk = 23
        targetSdk = 34
        // Смещение +82 сохраняет непрерывность versionCode после переезда на новый репозиторий (предыдущая сборка дошла до 82)
        val run = 82 + ((System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()) ?: 1)
        versionCode = run
        versionName = "0.1.$run"
    }

    // Постоянный ключ подписи: без него каждая сборка на GitHub получала бы
    // новый ключ, и обновление поверх старой версии не устанавливалось бы.
    signingConfigs {
        getByName("debug") {
            storeFile = file("panellog.keystore")
            storePassword = "panellog"
            keyAlias = "panellog"
            keyPassword = "panellog"
        }
    }

    // Три программы из одного кода:
    //  simple — «G1 Hotelo»: снять ошибки, отправить/скачать (com.panellog.app)
    //  pro    — «G1 Hotelo Pro»: объекты, история, сравнение, карточки (com.panellog.app.pro)
    //  ai     — «G1 Hotelo AI»: отдельная сборка с ИИ-анализом ошибок и пожарных тревог (com.panellog.app.ai)
    flavorDimensions += "edition"
    productFlavors {
        create("simple") {
            dimension = "edition"
            resValue("string", "app_name", "G1 Hotelo")
        }
        create("pro") {
            dimension = "edition"
            applicationIdSuffix = ".pro"
            resValue("string", "app_name", "G1 Hotelo Pro")
        }
        create("ai") {
            dimension = "edition"
            applicationIdSuffix = ".ai"
            resValue("string", "app_name", "G1 Hotelo AI")
        }
    }

    sourceSets {
        getByName("ai") {
            java.srcDirs("src/pro/java")
            manifest.srcFile("src/pro/AndroidManifest.xml")
        }
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.github.mik3y:usb-serial-for-android:3.8.1")
}
