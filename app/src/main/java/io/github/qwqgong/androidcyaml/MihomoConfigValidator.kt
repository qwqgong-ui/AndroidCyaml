package io.github.qwqgong.androidcyaml

import java.io.File
import java.io.IOException

object MihomoConfigValidator {
    fun validate(paths: MihomoPaths, candidate: File) {
        MihomoNative.validate(paths, candidate)
    }
}
