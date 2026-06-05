import java.util.Properties
import java.io.FileInputStream
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.miner7222.gap"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.miner7222.gap"
        minSdk = 26
        targetSdk = 37
        versionCode = 1060
        versionName = "v1.0.6"
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

tasks.register("verifyDebugXposedMetadata") {
    group = "verification"
    description = "Verifies modern Xposed metadata is packaged into the debug APK."
    dependsOn("assembleDebug")

    val metadataNames = listOf("java_init.list", "module.prop", "scope.list")
    val metadataDir = layout.projectDirectory.dir("src/main/resources/META-INF/xposed")
    val apkFile = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk")

    inputs.files(metadataNames.map { metadataDir.file(it) })
    inputs.file(apkFile)

    doLast {
        val apk = apkFile.get().asFile
        if (!apk.isFile) {
            throw GradleException("Debug APK was not found: ${apk.absolutePath}")
        }

        ZipFile(apk).use { zip ->
            metadataNames.forEach { name ->
                val entryName = "META-INF/xposed/$name"
                val entry = zip.getEntry(entryName)
                    ?: throw GradleException("$entryName is missing from ${apk.name}")
                val expected = metadataDir.file(name).asFile.readText().normalizeMetadata()
                val actual = zip.getInputStream(entry).bufferedReader().use { it.readText() }.normalizeMetadata()
                if (actual != expected) {
                    throw GradleException("$entryName content differs from src/main/resources")
                }
            }
        }
    }
}

fun String.normalizeMetadata(): String = replace("\r\n", "\n").trim()

dependencies {
    implementation(libs.androidx.annotation)
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.2.0")
    implementation("com.google.android.material:material:1.13.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.libxposed.api)

    implementation(libs.libxposed.service)
    compileOnly(libs.libxposed.api)
}
