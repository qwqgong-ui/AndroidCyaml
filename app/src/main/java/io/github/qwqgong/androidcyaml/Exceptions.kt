package io.github.qwqgong.androidcyaml

object Exceptions {
    @JvmStatic
    fun usefulMessage(throwable: Throwable): String {
        val message = throwable.message
        return if (message == null || message.isBlank()) {
            throwable.javaClass.simpleName
        } else {
            message
        }
    }
}
