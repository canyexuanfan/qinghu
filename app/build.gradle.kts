import java.util.Properties

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "top.hnwen17.guard"
    compileSdk = 35
    defaultConfig {
        applicationId = "top.hnwen17.guard"
        minSdk = 30
        targetSdk = 35
        versionCode = 32
        versionName = "0.3.6"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    flavorDimensions += "data"
    productFlavors {
        create("standard") {
            dimension = "data"
            buildConfigField("boolean", "PREVIEW_DATA", "false")
            resValue("string", "app_name", "轻护")
        }
        create("preview") {
            dimension = "data"
            applicationIdSuffix = ".preview"
            buildConfigField("boolean", "PREVIEW_DATA", "true")
            resValue("string", "app_name", "轻护 · 界面预览")
        }
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // QH-P22：本地正式签名（凭据文件 .keystore-local/signing.properties 不入库）
            val spFile = rootProject.file(".keystore-local/signing.properties")
            if (spFile.exists()) {
                val props = Properties().apply { spFile.inputStream().use { load(it) } }
                signingConfig = signingConfigs.create("release") {
                    storeFile = spFile.parentFile.resolve(props.getProperty("storeFile"))
                    storePassword = props.getProperty("storePassword")
                    keyAlias = props.getProperty("keyAlias")
                    keyPassword = props.getProperty("keyPassword")
                }
            }
        }
    }
    buildFeatures { viewBinding = true; buildConfig = true; aidl = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = true }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.fragment:fragment-ktx:1.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.datastore:datastore-preferences:1.1.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // QH-P05-07（R01 准入，reference/legal/R01.md）：Shizuku 官方 Maven 制品，版本固定；普通模式不强制 Shizuku
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    testImplementation("junit:junit:4.13.2")
    // QH-P01-06 准入：仅单测classpath使用（不进APK），提供与Android同API的真实org.json实现
    testImplementation("org.json:json:20240303")
}
