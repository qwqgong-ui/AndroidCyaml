package io.github.qwqgong.androidcyaml;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.util.Log;

import java.io.Closeable;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class AndroidPlatformServer implements Closeable {
    interface Handler {
        AndroidPlatformProtocol.Reply handle(AndroidPlatformProtocol.Request request) throws Exception;
    }

    private static final String TAG = "AndroidCyaml/Platform";
    private static final int SOCKET_TIMEOUT_MILLIS = 20_000;

    private final String socketName;
    private final LocalServerSocket serverSocket;
    private final Handler handler;
    private final ExecutorService acceptExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService requestExecutor = Executors.newCachedThreadPool();
    private volatile boolean closed;

    AndroidPlatformServer(Handler handler) throws IOException {
        this.handler = handler;
        socketName = "androidcyaml-platform-" + android.os.Process.myPid() + "-" + UUID.randomUUID();
        serverSocket = new LocalServerSocket(socketName);
        acceptExecutor.execute(this::acceptLoop);
    }

    String coreSocketAddress() {
        return "@" + socketName;
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                LocalSocket connection = serverSocket.accept();
                requestExecutor.execute(() -> handleConnection(connection));
            } catch (IOException exception) {
                if (!closed) {
                    Log.e(TAG, "Android platform accept loop failed", exception);
                }
                return;
            }
        }
    }

    private void handleConnection(LocalSocket connection) {
        AndroidPlatformProtocol.Reply reply;
        try {
            connection.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            AndroidPlatformProtocol.Request request = AndroidPlatformProtocol.readRequest(
                    connection.getInputStream()
            );
            reply = handler.handle(request);
        } catch (Exception exception) {
            Log.w(TAG, "Android platform request failed", exception);
            reply = AndroidPlatformProtocol.error(usefulMessage(exception));
        }

        try {
            AndroidPlatformProtocol.writeReply(connection, reply);
        } catch (IOException exception) {
            Log.w(TAG, "Unable to write Android platform response", exception);
        } finally {
            try {
                connection.close();
            } catch (IOException ignored) {
                // Request connections are one-shot.
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // Already closed.
        }
        acceptExecutor.shutdownNow();
        requestExecutor.shutdownNow();
    }

    private static String usefulMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName()
                : message;
    }
}
