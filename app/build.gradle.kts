import org.gradle.api.tasks.Exec

plugins {
    id("com.android.application")
}

val mihomoCommit = "166517a824621ac474b38ae07349d926889d9c69"
val mihomoPatchFile = rootProject.file("patches/mihomo/0001-androidcyaml-platform-hooks.patch")
val mihomoWrapperGoMod = rootProject.file("native/mihomo/go.mod")
val mihomoWrapperMain = rootProject.file("native/mihomo/main.go")
val androidNdkVersion = "29.0.14206865"

val releaseStoreFile = System.getenv("ANDROID_SIGNING_STORE_FILE")
val releaseStorePassword = System.getenv("ANDROID_SIGNING_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("ANDROID_SIGNING_KEY_ALIAS")
val releaseKeyPassword = System.getenv("ANDROID_SIGNING_KEY_PASSWORD")
val releaseSigningConfigured = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "io.github.qwqgong.androidcyaml"
    compileSdk = 37
    ndkVersion = androidNdkVersion

    defaultConfig {
        applicationId = "io.github.qwqgong.androidcyaml"
        minSdk = 36
        targetSdk = 37
        versionCode = 212
        versionName = "0.6.212"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_PLATFORM=android-35"
                cppFlags += listOf("-std=c++20")
            }
        }

        buildConfigField("String", "MIHOMO_COMMIT", "\"$mihomoCommit\"")
        buildConfigField("String", "MIHOMO_PATCH_SET", "\"${mihomoPatchFile.name}\"")
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
            signingConfigs.findByName("release")?.let { signingConfig = it }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("optimized") {
            initWith(getByName("release"))
            isDebuggable = false
            isJniDebuggable = false
            applicationIdSuffix = ".baselineprofile"
            versionNameSuffix = "-optimized"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*")
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

dependencies {
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    testImplementation("junit:junit:4.13.2")
}

val verifyReleaseSigning by tasks.registering {
    doLast {
        check(releaseSigningConfigured) {
            "Release signing requires ANDROID_SIGNING_STORE_FILE, " +
                "ANDROID_SIGNING_STORE_PASSWORD, ANDROID_SIGNING_KEY_ALIAS, and " +
                "ANDROID_SIGNING_KEY_PASSWORD"
        }
    }
}

val fetchGeodata by tasks.registering(Exec::class) {
    group = "build setup"
    description = "Fetch latest MetaCubeX geoip-lite.dat release asset"
    workingDir(rootProject.projectDir)
    commandLine("bash", "scripts/fetch_geodata.sh")
    inputs.file(rootProject.file("scripts/fetch_geodata.sh"))
    outputs.upToDateWhen { false }
}

val fetchZashboard by tasks.registering(Exec::class) {
    group = "build setup"
    description = "Fetch latest zashboard dist-no-fonts.zip release asset"
    workingDir(rootProject.projectDir)
    commandLine("bash", "scripts/fetch_zashboard.sh")
    inputs.file(rootProject.file("scripts/fetch_zashboard.sh"))
    outputs.upToDateWhen { false }
}

val mihomoLibrary = layout.projectDirectory.file(
    "src/main/jniLibs/arm64-v8a/libmihomo.so",
)
val mihomoHeader = layout.projectDirectory.file("src/main/cpp/generated/libmihomo.h")

val buildMihomo by tasks.registering(Exec::class) {
    group = "build setup"
    description = "Build clean mihomo plus the AndroidCyaml-owned JNI patch"
    workingDir(rootProject.projectDir)
    commandLine("bash", "scripts/build_mihomo.sh")
    inputs.file(rootProject.file("scripts/build_mihomo.sh"))
    inputs.file(mihomoPatchFile)
    inputs.file(mihomoWrapperGoMod)
    inputs.file(mihomoWrapperMain)
    inputs.property("mihomoCommit", mihomoCommit)
    inputs.property("androidNdkVersion", androidNdkVersion)
    outputs.files(mihomoLibrary, mihomoHeader)
}

tasks.configureEach {
    if (name == "packageRelease") {
        dependsOn(verifyReleaseSigning)
    }
    if (name.startsWith("configureCMake")
            || name.startsWith("buildCMake")
            || name.contains("ExternalNativeBuild")) {
        dependsOn(buildMihomo)
    }
}

tasks.named("preBuild") {
    dependsOn(fetchGeodata, fetchZashboard, buildMihomo)
}
