import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.github.miner7222.gap"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.miner7222.gap"
        minSdk = 24
        targetSdk = 36
        versionCode = 1030
        versionName = "v1.0.3"
    }

    signingConfigs {
        create("release") {
            val keystorePropertiesFile = rootProject.file("local.properties")
            val keystoreProperties = Properties()
            if (keystorePropertiesFile.exists()) {
                keystoreProperties.load(FileInputStream(keystorePropertiesFile))
            }

            val storePath = keystoreProperties.getProperty("storeFile") 
                ?: System.getenv("SIGNING_KEY_STORE_PATH")
            
            val storePwd = keystoreProperties.getProperty("storePassword") 
                ?: System.getenv("SIGNING_STORE_PASSWORD")
            
            val keyAliasVal = keystoreProperties.getProperty("keyAlias") 
                ?: System.getenv("SIGNING_KEY_ALIAS")
            
            val keyPwd = keystoreProperties.getProperty("keyPassword") 
                ?: System.getenv("SIGNING_KEY_PASSWORD")

            if (!storePath.isNullOrEmpty() && !storePwd.isNullOrEmpty()) {
                storeFile = file(storePath)
                storePassword = storePwd
                keyAlias = keyAliasVal
                keyPassword = keyPwd
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
            freeCompilerArgs.addAll(
                "-Xno-param-assertions",
                "-Xno-call-assertions",
                "-Xno-receiver-assertions"
            )
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    packaging {
        resources.excludes += "DebugProbesKt.bin"
        resources.excludes += "**/kotlin/**"
        resources.excludes += "kotlin-tooling-metadata.json"
    }
}

tasks.register("printVersionName") {
    doLast {
        println(android.defaultConfig.versionName)
    }
}

dependencies {
    implementation(libs.androidx.annotation)
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("com.google.android.material:material:1.13.0")

    compileOnly(libs.xposed.api)
    compileOnly(libs.xposed.api.sources)
    implementation(libs.yukihook.api)
    ksp(libs.yukihook.ksp.xposed)
    implementation(libs.kavaref.core)
    implementation(libs.kavaref.extension)
}
