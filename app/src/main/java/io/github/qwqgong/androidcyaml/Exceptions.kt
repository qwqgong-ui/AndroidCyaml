package io.github.qwqgong.androidcyaml

object Exceptions {
    fun usefulMessage(throwable: Throwable): String {
        val message = throwable.message
        return if (message == null || message.isBlank()) {
            throwable.javaClass.simpleName
        } else {
            message
        }
    }
}
