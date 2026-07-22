import org.gradle.api.tasks.Exec

plugins {
    id("com.android.application")
}

val mihomoCommit = "a563ca2194edbf560b3857801cb3cceab13d7ff9"
val hevSocks5TunnelCommit = "df11261f09ebafc37bac03f81029c9b75a4aa074"
val zashboardVersion = "v3.15.0"
val geodataCommit = "ab44fa37df7a2939806042c20af3a0bfd07152ea"

android {
    namespace = "io.github.qwqgong.androidcyaml"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "io.github.qwqgong.androidcyaml"
        minSdk = 36
        targetSdk = 36
        versionCode = 6
        versionName = "0.4.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        buildConfigField("String", "MIHOMO_COMMIT", "\"$mihomoCommit\"")
        buildConfigField(
            "String",
            "HEV_SOCKS5_TUNNEL_COMMIT",
            "\"$hevSocks5TunnelCommit\"",
        )
        buildConfigField("String", "ZASHBOARD_VERSION", "\"$zashboardVersion\"")
        buildConfigField("String", "GEODATA_COMMIT", "\"$geodataCommit\"")
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

val hevSocks5TunnelLibrary = layout.projectDirectory.file(
    "src/main/jniLibs/arm64-v8a/libhev-socks5-tunnel.so",
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

val buildHevSocks5Tunnel by tasks.registering(Exec::class) {
    group = "build setup"
    description = "Build the pinned HEV SOCKS5 tunnel for Android arm64"
    workingDir(rootProject.projectDir)
    commandLine("bash", "scripts/build_hev_socks5_tunnel.sh")
    inputs.file(rootProject.file("scripts/build_hev_socks5_tunnel.sh"))
    inputs.property("hevSocks5TunnelCommit", hevSocks5TunnelCommit)
    outputs.file(hevSocks5TunnelLibrary)
}

tasks.named("preBuild") {
    dependsOn(buildMihomo, buildHevSocks5Tunnel)
}
