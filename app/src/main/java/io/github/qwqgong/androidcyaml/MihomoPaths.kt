package io.github.qwqgong.androidcyaml

import java.io.File

data class MihomoPaths(
    @get:JvmName("home") val home: File,
    @get:JvmName("config") val config: File,
    @get:JvmName("ui") val ui: File,
)
