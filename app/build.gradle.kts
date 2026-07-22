import org.gradle.api.tasks.Exec

plugins {
    id("com.android.application")
}

val mihomoCommit = "a563ca2194edbf560b3857801cb3cceab13d7ff9"
val zashboardVersion = "v3.15.0"

android {
    namespace = "io.github.qwqgong.androidcyaml"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.qwqgong.androidcyaml"
        minSdk = 36
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        buildConfigField("String", "MIHOMO_COMMIT", "\"$mihomoCommit\"")
        buildConfigField("String", "ZASHBOARD_VERSION", "\"$zashboardVersion\"")
    }

    buildFeatures {
        buildConfig = true
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
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    packaging {
        jniLibs {
            // The Go executable is intentionally packaged as libmihomo.so so Android
            // extracts it into the app's executable native-library directory.
            useLegacyPackaging = true
            keepDebugSymbols += "**/libmihomo.so"
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

val mihomoBinary = layout.projectDirectory.file(
    "src/main/jniLibs/arm64-v8a/libmihomo.so",
)

val buildMihomo by tasks.registering(Exec::class) {
    group = "build setup"
    description = "Build the pinned mihomo core for Android arm64"
    workingDir(rootProject.projectDir)
    commandLine("bash", "scripts/build_mihomo.sh")
    inputs.file(rootProject.file("scripts/build_mihomo.sh"))
    inputs.file(rootProject.file("patches/mihomo-android-vpn.patch"))
    inputs.property("mihomoCommit", mihomoCommit)
    outputs.file(mihomoBinary)
}

tasks.named("preBuild") {
    dependsOn(buildMihomo)
}
