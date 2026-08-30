package io.github.qwqgong.androidcyaml

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Accepts outbound sockets from the Go core over a unix socket and protects
 * them here, in Java.
 *
 * `VpnService.protect` has no NDK equivalent, so the request has to reach the
 * JVM. It does not have to arrive as a JNI upcall on a Go thread: those upcalls
 * blocked the calling OS thread inside cgo and attached it to ART, which is how
 * a reconnect storm used to leave hundreds of `Thread-N` threads behind. The Go
 * side now sends the descriptor with SCM_RIGHTS and waits on its own network
 * poller, and this fixed worker pool answers.
 *
 * The endpoint lives in the app's private no-backup directory rather than the
 * abstract namespace: every app on the device shares a network namespace, and
 * this socket protects and binds whatever descriptor it is handed.
 */
class SocketProtectService(
    endpoint: File,
    private val protector: (FileDescriptor) -> Boolean,
) : AutoCloseable {
    private val endpointFile: File = endpoint.absoluteFile
    private val binder = LocalSocket(LocalSocket.SOCKET_STREAM)
    private val server: LocalServerSocket
    private val workers: ThreadPoolExecutor

    @Volatile
    private var closed = false

    init {
        endpointFile.parentFile?.mkdirs()
        endpointFile.delete()
        binder.bind(LocalSocketAddress(endpointFile.path, LocalSocketAddress.Namespace.FILESYSTEM))
        server = try {
            LocalServerSocket(binder.fileDescriptor)
        } catch (failure: IOException) {
            closeQuietly(binder)
            endpointFile.delete()
            throw failure
        }
        val workerIds = AtomicInteger()
        workers = ThreadPoolExecutor(
            1,
            MAX_WORKERS,
            WORKER_IDLE_SECONDS,
            TimeUnit.SECONDS,
            SynchronousQueue(),
            { runnable -> Thread(runnable, "AndroidCyaml-protect-" + workerIds.incrementAndGet()) },
            // Under a burst wider than the pool the accept thread protects the
            // socket itself. That throttles accepting, which is the correct
            // back pressure: the Go callers are parked goroutines, not threads.
            ThreadPoolExecutor.CallerRunsPolicy(),
        )
        Thread(::acceptLoop, "AndroidCyaml-protect-accept").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "Socket protect endpoint listening at " + endpointFile.path)
    }

    /** Filesystem path the Go core connects to. */
    fun endpointPath(): String = endpointFile.path

    override fun close() {
        if (closed) {
            return
        }
        closed = true
        // binder owns the listening descriptor; LocalServerSocket only borrows
        // it, so closing binder alone both unblocks accept() and avoids closing
        // the same descriptor twice.
        closeQuietly(binder)
        workers.shutdownNow()
        endpointFile.delete()
    }

    private fun acceptLoop() {
        while (!closed) {
            val connection = try {
                server.accept()
            } catch (failure: IOException) {
                if (!closed) {
                    // Leaving the loop is deliberate: the Go side falls back to
                    // the JNI callback, which is slower but keeps dialing.
                    Log.w(TAG, "Protect endpoint stopped accepting; dials fall back to JNI", failure)
                }
                return
            }
            try {
                workers.execute { serve(connection) }
            } catch (rejected: RejectedExecutionException) {
                Log.d(TAG, "Protect endpoint is shutting down", rejected)
                closeQuietly(connection)
            }
        }
    }

    private fun serve(connection: LocalSocket) {
        try {
            connection.soTimeout = REQUEST_TIMEOUT_MILLIS
            if (!isOwnProcess(connection)) {
                Log.w(TAG, "Rejected a protect request from a foreign process")
                return
            }
            if (connection.inputStream.read() < 0) {
                return
            }
            val descriptors = connection.ancillaryFileDescriptors
            try {
                val granted = descriptors != null &&
                    descriptors.size == 1 &&
                    descriptors[0] != null &&
                    protector(descriptors[0])
                connection.outputStream.write(if (granted) GRANTED else REJECTED)
                connection.outputStream.flush()
            } finally {
                descriptors?.forEach { closeQuietly(it) }
            }
        } catch (failure: IOException) {
            Log.d(TAG, "Protect request failed", failure)
        } catch (failure: RuntimeException) {
            Log.w(TAG, "Protect request failed", failure)
        } finally {
            closeQuietly(connection)
        }
    }

    private fun isOwnProcess(connection: LocalSocket): Boolean = try {
        connection.peerCredentials.uid == Os.getuid()
    } catch (failure: IOException) {
        false
    }

    private fun closeQuietly(connection: LocalSocket) {
        try {
            connection.close()
        } catch (ignored: IOException) {
            Log.d(TAG, "Unable to close a protect connection", ignored)
        }
    }

    private fun closeQuietly(descriptor: FileDescriptor?) {
        if (descriptor == null) {
            return
        }
        try {
            Os.close(descriptor)
        } catch (ignored: ErrnoException) {
            Log.d(TAG, "Unable to close a received descriptor", ignored)
        }
    }

    private companion object {
        const val TAG = "AndroidCyaml/Protect"
        const val MAX_WORKERS = 8
        const val WORKER_IDLE_SECONDS = 30L
        const val REQUEST_TIMEOUT_MILLIS = 5_000
        const val GRANTED = 0x01
        const val REJECTED = 0x00
    }
}
