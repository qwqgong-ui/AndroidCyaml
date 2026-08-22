package io.github.qwqgong.androidcyaml;

final class Exceptions {
    private Exceptions() {}

    static String usefulMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName()
                : message;
    }
}
