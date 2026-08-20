import org.gradle.api.tasks.Exec

plugins {
    id("com.android.application")
}

val mihomoCommit = "559085baa897f91863cc706dcbb3853665719b6e"
val mihomoPatchDir = rootProject.file("patches/mihomo")
val mihomoPatchFiles = fileTree(mihomoPatchDir) {
    include("*.patch")
}
val mihomoWrapperGoMod = rootProject.file("native/mihomo/go.mod")
val mihomoWrapperSources = fileTree(rootProject.file("native/mihomo")) {
    include("*.go")
}
val androidNdkVersion = "29.0.14206865"

android {
    namespace = "io.github.qwqgong.androidcyaml"
    compileSdk = 37
    ndkVersion = androidNdkVersion

    defaultConfig {
        applicationId = "io.github.qwqgong.androidcyaml"
        minSdk = 36
        targetSdk = 37
        versionCode = 290
        versionName = "0.7.177"

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
        buildConfigField("String", "MIHOMO_PATCH_SET", "\"patches/mihomo/*.patch\"")
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
    implementation("androidx.webkit:webkit:1.16.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
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
    description = "Build clean mihomo plus the AndroidCyaml-owned JNI patches"
    workingDir(rootProject.projectDir)
    commandLine("bash", "scripts/build_mihomo.sh")
    inputs.file(rootProject.file("scripts/build_mihomo.sh"))
    inputs.files(mihomoPatchFiles)
    inputs.file(mihomoWrapperGoMod)
    inputs.files(mihomoWrapperSources)
    inputs.property("mihomoCommit", mihomoCommit)
    inputs.property("androidNdkVersion", androidNdkVersion)
    outputs.files(mihomoLibrary, mihomoHeader)
}

tasks.configureEach {
    if (name.startsWith("configureCMake")
            || name.startsWith("buildCMake")
            || name.contains("ExternalNativeBuild")) {
        dependsOn(buildMihomo)
    }
}

tasks.named("preBuild") {
    dependsOn(fetchGeodata, buildMihomo)
}
