package io.github.qwqgong.androidcyaml

import android.content.Context
import android.net.Uri
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files

class ConfigInstaller(context: Context, private val fileStore: MihomoFileStore) {
    private val context: Context = context.applicationContext

    fun install(source: Uri?): Transaction {
        if (source == null) {
            throw IOException("未选择配置文件")
        }
        val paths = fileStore.ensureReady()
        val candidate = File(paths.home, "config.importing.yaml")
        val backup = File(paths.home, "config.previous.yaml")
        fileStore.deleteIfExists(candidate)
        fileStore.deleteIfExists(backup)
        try {
            copyWithLimit(source, candidate, MihomoFileStore.MAX_CONFIG_BYTES)
            MihomoConfigValidator.validate(paths, candidate)
            if (paths.config.isFile) {
                fileStore.moveReplacing(paths.config, backup)
            }
            fileStore.moveReplacing(candidate, paths.config)
            fileStore.makeConfigOwnerReadWrite(paths.config)
            return Transaction(fileStore, paths.config, backup, candidate)
        } catch (exception: IOException) {
            Files.deleteIfExists(candidate.toPath())
            if (backup.isFile) {
                Files.deleteIfExists(paths.config.toPath())
                fileStore.moveReplacing(backup, paths.config)
                fileStore.makeConfigOwnerReadWrite(paths.config)
            }
            throw exception
        }
    }

    private fun copyWithLimit(source: Uri, destination: File, byteLimit: Int) {
        val input = context.contentResolver.openInputStream(source)
            ?: throw IOException("无法读取所选文件")
        input.use {
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) {
                        break
                    }
                    total += read
                    if (total > byteLimit) {
                        throw IOException("config.yaml 超过 32 MiB 限制")
                    }
                    output.write(buffer, 0, read)
                }
                output.fd.sync()
            }
        }
    }

    class Transaction internal constructor(
        private val fileStore: MihomoFileStore,
        private val config: File,
        private val backup: File,
        private val candidate: File,
    ) : Closeable {
        private var finished = false

        fun commit() {
            if (finished) {
                return
            }
            fileStore.deleteIfExists(backup)
            finished = true
        }

        fun rollback() {
            if (finished) {
                return
            }
            fileStore.deleteIfExists(config)
            if (!backup.isFile) {
                throw IOException("上一份配置不存在，无法回滚")
            }
            fileStore.moveReplacing(backup, config)
            fileStore.makeConfigOwnerReadWrite(config)
            finished = true
        }

        override fun close() {
            fileStore.deleteIfExists(candidate)
        }
    }
}
