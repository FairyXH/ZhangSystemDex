plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.fairyxh.zhangsystemdex"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "io.github.fairyxh.zhangsystemdex"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // KernelSU/Magisk WebUI 交付通道：
    // 源码 app/src/main/assets/webroot/index.html 走标准 assets 目录，
    // 打包后为 assets/webroot/index.html，解压/释放到模块目录即为：
    //   /data/adb/modules/Zhang/webroot/index.html
    // （KernelSU 从模块目录读取 webroot/index.html 渲染模块 WebUI）

    lint {
        // Speed up daemon builds: no release lint vital checks needed.
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
