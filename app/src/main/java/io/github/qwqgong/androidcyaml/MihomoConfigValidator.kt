package io.github.qwqgong.androidcyaml

import java.io.File
import java.io.IOException

object MihomoConfigValidator {
    @JvmStatic
    @Throws(IOException::class)
    fun validate(paths: MihomoPaths, candidate: File) {
        MihomoNative.validate(paths, candidate)
    }
}
