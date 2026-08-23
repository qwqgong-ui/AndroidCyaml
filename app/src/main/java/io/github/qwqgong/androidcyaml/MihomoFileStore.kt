package io.github.qwqgong.androidcyaml

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class MihomoFileStore(context: Context) {
    private val context: Context = context.applicationContext

    fun ensureReady(): MihomoPaths {
        val home = File(context.noBackupFilesDir, "mihomo")
        if (!home.isDirectory && !home.mkdirs()) {
            throw IOException("无法创建 mihomo 工作目录")
        }

        val config = File(home, "config.yaml")
        if (!config.isFile) {
            copyAssetFile("default-config.yaml", config)
        }
        makeConfigOwnerReadWrite(config)
        val ui = File(home, "ui")
        ensureDashboard(home, ui)
        return MihomoPaths(home, config, ui)
    }

    fun dashboardVersion(): String = displayVersion(requiredAssetVersion(ZASHBOARD_VERSION_ASSET))

    fun makeConfigOwnerReadWrite(config: File) {
        try {
            Os.chmod(config.absolutePath, OsConstants.S_IRUSR or OsConstants.S_IWUSR)
        } catch (exception: ErrnoException) {
            throw IOException("无法将 config.yaml 权限设为 0600", exception)
        }
    }

    fun moveReplacing(source: File, destination: File) {
        val parent = destination.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw IOException("无法创建目录：$parent")
        }
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (exception: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    fun deleteIfExists(file: File) {
        Files.deleteIfExists(file.toPath())
    }

    private fun ensureDashboard(home: File, ui: File) {
        val bundledVersion = requiredAssetVersion(ZASHBOARD_VERSION_ASSET)
        val marker = File(ui, ".androidcyaml-version")
        val installedVersion = if (marker.isFile) readText(marker).trim() else ""
        if (bundledVersion == installedVersion && File(ui, "index.html").isFile) {
            return
        }

        val staging = File(home, "ui.installing")
        val previous = File(home, "ui.previous")
        deleteRecursively(staging)
        deleteRecursively(previous)
        if (!staging.mkdirs()) {
            throw IOException("无法创建面板暂存目录")
        }
        copyAssetTree("zashboard", staging)
        writeText(File(staging, ".androidcyaml-version"), bundledVersion)
        if (ui.exists()) {
            moveReplacing(ui, previous)
        }
        try {
            moveReplacing(staging, ui)
        } catch (exception: IOException) {
            if (previous.exists() && !ui.exists()) {
                moveReplacing(previous, ui)
            }
            throw exception
        }
        deleteRecursively(previous)
    }

    private fun requiredAssetVersion(assetPath: String): String {
        val version = readAssetText(assetPath).trim()
        if (version.isEmpty()) {
            throw IOException("资源版本文件为空：$assetPath")
        }
        return version
    }

    private fun readAssetText(assetPath: String): String =
        context.assets.open(assetPath).use { input ->
            String(input.readBytes(), StandardCharsets.UTF_8)
        }

    private fun copyAssetTree(assetPath: String, destination: File) {
        val entries = context.assets.list(assetPath)
        if (entries == null || entries.isEmpty()) {
            copyAssetFile(assetPath, destination)
            return
        }
        if (!destination.isDirectory && !destination.mkdirs()) {
            throw IOException("无法创建目录：$destination")
        }
        for (entry in entries) {
            copyAssetTree("$assetPath/$entry", File(destination, entry))
        }
    }

    private fun copyAssetFile(assetPath: String, destination: File) {
        val parent = destination.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw IOException("无法创建目录：$parent")
        }
        context.assets.open(assetPath).use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output, 16 * 1024)
                output.fd.sync()
            }
        }
    }

    companion object {
        const val MAX_CONFIG_BYTES = 32 * 1024 * 1024

        private const val ZASHBOARD_VERSION_ASSET = "zashboard.version"

        private fun displayVersion(versionToken: String): String {
            val separator = versionToken.indexOf('@')
            return if (separator <= 0) versionToken else versionToken.substring(0, separator)
        }

        private fun readText(file: File): String =
            String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)

        private fun writeText(file: File, value: String) {
            FileOutputStream(file).use { output ->
                output.write(value.toByteArray(StandardCharsets.UTF_8))
                output.fd.sync()
            }
        }

        private fun deleteRecursively(file: File) {
            if (!file.exists()) {
                return
            }
            if (file.isDirectory) {
                file.listFiles()?.forEach { child -> deleteRecursively(child) }
            }
            Files.deleteIfExists(file.toPath())
        }
    }
}
