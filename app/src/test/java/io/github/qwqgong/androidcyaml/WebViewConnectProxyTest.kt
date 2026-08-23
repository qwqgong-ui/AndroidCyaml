package io.github.qwqgong.androidcyaml

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class WebViewConnectProxyTest {
    @Test
    fun connectUsesRegisteredServerInsteadOfUrlDns() {
        ServerSocket().use { target ->
            target.bind(InetSocketAddress(ipv4Loopback(), 0))
            val targetDone = CountDownLatch(1)
            val targetFailure = AtomicReference<Throwable?>()
            val server = Thread {
                try {
                    target.accept().use { accepted ->
                        val request = accepted.getInputStream().readNBytes(4)
                        assertArrayEquals("ping".toByteArray(StandardCharsets.US_ASCII), request)
                        accepted.getOutputStream()
                            .write("pong".toByteArray(StandardCharsets.US_ASCII))
                        accepted.getOutputStream().flush()
                    }
                } catch (throwable: Throwable) {
                    targetFailure.set(throwable)
                } finally {
                    targetDone.countDown()
                }
            }
            server.isDaemon = true
            server.start()

            val protectedBeforeConnect = AtomicBoolean(false)
            val routeBoundBeforeProtect = AtomicBoolean(false)
            val resolvedHost = AtomicReference<String?>()
            val route = object : WebViewConnectProxy.NetworkRoute {
                override fun resolve(host: String): Array<InetAddress> {
                    resolvedHost.set(host)
                    return arrayOf(ipv4Loopback())
                }

                override fun bind(socket: Socket) {
                    socket.reuseAddress
                    routeBoundBeforeProtect.set(true)
                }
            }
            WebViewConnectProxy(
                { socket ->
                    protectedBeforeConnect.set(
                        routeBoundBeforeProtect.get() && !socket.isConnected,
                    )
                    true
                },
                { route },
            ).use { proxy ->
                Socket().use { client ->
                    val binding = proxy.register(
                        "https://unresolvable.invalid/xhttp",
                        "endpoint.invalid:" + target.localPort,
                    )
                    client.connect(InetSocketAddress(ipv4Loopback(), proxy.port()))
                    assertTrue(proxy.authority().startsWith("127.0.0.1:"))
                    val output = client.getOutputStream()
                    output.write(
                        (
                            "CONNECT unresolvable.invalid:443 HTTP/1.1\r\n" +
                                "Host: unresolvable.invalid:443\r\n" +
                                "Proxy-Authorization: " + binding.authorizationHeader() +
                                "\r\n\r\n"
                            ).toByteArray(StandardCharsets.US_ASCII),
                    )
                    output.flush()

                    val input = BufferedInputStream(client.getInputStream())
                    assertTrue(readHeaders(input).startsWith("HTTP/1.1 200"))

                    output.write("ping".toByteArray(StandardCharsets.US_ASCII))
                    output.flush()
                    assertArrayEquals(
                        "pong".toByteArray(StandardCharsets.US_ASCII),
                        input.readNBytes(4),
                    )
                    assertTrue(protectedBeforeConnect.get())
                    assertEquals("endpoint.invalid", resolvedHost.get())
                }
            }

            assertTrue(targetDone.await(5, TimeUnit.SECONDS))
            val failure = targetFailure.get()
            if (failure != null) {
                throw AssertionError(failure)
            }
        }
    }

    @Test
    fun unknownAuthorityIsRejected() {
        directProxy { true }.use { proxy ->
            Socket().use { client ->
                client.connect(InetSocketAddress(ipv4Loopback(), proxy.port()))
                client.getOutputStream().write(
                    (
                        "CONNECT unknown.invalid:443 HTTP/1.1\r\n" +
                            "Host: unknown.invalid:443\r\n\r\n"
                        ).toByteArray(StandardCharsets.US_ASCII),
                )
                client.getOutputStream().flush()
                assertTrue(readHeaders(client.getInputStream()).startsWith("HTTP/1.1 403"))
            }
        }
    }

    @Test
    fun knownAuthorityRequiresCredentials() {
        directProxy { true }.use { proxy ->
            Socket().use { client ->
                proxy.register("https://known.invalid/xhttp", "127.0.0.1:1443")
                client.connect(InetSocketAddress(ipv4Loopback(), proxy.port()))
                client.getOutputStream().write(
                    (
                        "CONNECT known.invalid:443 HTTP/1.1\r\n" +
                            "Host: known.invalid:443\r\n\r\n"
                        ).toByteArray(StandardCharsets.US_ASCII),
                )
                client.getOutputStream().flush()
                assertTrue(readHeaders(client.getInputStream()).startsWith("HTTP/1.1 407"))
            }
        }
    }

    @Test
    fun sameAuthorityGetsIsolatedTargetBindings() {
        directProxy { true }.use { proxy ->
            val first = proxy.register("https://same.invalid/xhttp", "127.0.0.1:1443")
            val duplicate = proxy.register("https://same.invalid/other", "127.0.0.1:1443")
            val second = proxy.register("https://same.invalid/xhttp", "127.0.0.1:2443")

            assertSame(first, duplicate)
            assertNotEquals(first.profileName(), second.profileName())
            assertNotEquals(first.authorizationHeader(), second.authorizationHeader())
        }
    }

    @Test
    fun sameAuthorityCredentialSelectsExactEndpoint() {
        val unavailablePort: Int
        ServerSocket().use { reservation ->
            reservation.bind(InetSocketAddress(ipv4Loopback(), 0))
            unavailablePort = reservation.localPort
        }

        ServerSocket().use { target ->
            target.bind(InetSocketAddress(ipv4Loopback(), 0))
            val targetDone = CountDownLatch(1)
            val server = Thread {
                try {
                    target.accept().use {
                        targetDone.countDown()
                    }
                } catch (ignored: IOException) {
                    // The assertion below reports a missing successful dial.
                }
            }
            server.isDaemon = true
            server.start()

            directProxy { true }.use { proxy ->
                Socket().use { client ->
                    proxy.register("https://pool.invalid/xhttp", "127.0.0.1:$unavailablePort")
                    val binding = proxy.register(
                        "https://pool.invalid/xhttp",
                        "127.0.0.1:" + target.localPort,
                    )
                    client.connect(InetSocketAddress(ipv4Loopback(), proxy.port()))
                    client.getOutputStream().write(
                        (
                            "CONNECT pool.invalid:443 HTTP/1.1\r\n" +
                                "Host: pool.invalid:443\r\n" +
                                "Proxy-Authorization: " + binding.authorizationHeader() +
                                "\r\n\r\n"
                            ).toByteArray(StandardCharsets.US_ASCII),
                    )
                    client.getOutputStream().flush()

                    assertTrue(
                        readHeaders(client.getInputStream()).startsWith("HTTP/1.1 200"),
                    )
                    assertTrue(targetDone.await(5, TimeUnit.SECONDS))
                }
            }
        }
    }

    @Test
    fun rejectedSocketProtectionFailsBeforeRemoteConnect() {
        ServerSocket().use { target ->
            target.bind(InetSocketAddress(ipv4Loopback(), 0))
            val protectionCalled = AtomicBoolean(false)
            directProxy { socket ->
                protectionCalled.set(true)
                assertFalse(socket.isConnected)
                false
            }.use { proxy ->
                Socket().use { client ->
                    val binding = proxy.register(
                        "https://protected.invalid/xhttp",
                        "127.0.0.1:" + target.localPort,
                    )
                    client.connect(InetSocketAddress(ipv4Loopback(), proxy.port()))
                    client.getOutputStream().write(
                        (
                            "CONNECT protected.invalid:443 HTTP/1.1\r\n" +
                                "Host: protected.invalid:443\r\n" +
                                "Proxy-Authorization: " + binding.authorizationHeader() +
                                "\r\n\r\n"
                            ).toByteArray(StandardCharsets.US_ASCII),
                    )
                    client.getOutputStream().flush()

                    assertTrue(
                        readHeaders(client.getInputStream())
                            .startsWith("HTTP/1.1 502 VPN Protect Failed"),
                    )
                    assertTrue(protectionCalled.get())
                }
            }
        }
    }

    private fun directProxy(
        protector: WebViewConnectProxy.SocketProtector,
    ): WebViewConnectProxy {
        val route = object : WebViewConnectProxy.NetworkRoute {
            override fun resolve(host: String): Array<InetAddress> =
                InetAddress.getAllByName(host)

            override fun bind(socket: Socket) {
                socket.reuseAddress
            }
        }
        return WebViewConnectProxy(protector) { route }
    }

    private fun ipv4Loopback(): InetAddress = InetAddress.getByAddress(IPV4_LOOPBACK)

    private fun readHeaders(input: InputStream): String {
        val buffer = ByteArrayOutputStream()
        var matched = 0
        while (buffer.size() < 16 * 1024) {
            val value = input.read()
            if (value < 0) {
                break
            }
            buffer.write(value)
            matched = when (matched) {
                0 -> if (value == '\r'.code) 1 else 0
                1 -> if (value == '\n'.code) 2 else 0
                2 -> if (value == '\r'.code) 3 else 0
                3 -> if (value == '\n'.code) 4 else 0
                else -> 4
            }
            if (matched == 4) {
                break
            }
        }
        return buffer.toString(StandardCharsets.US_ASCII)
    }

    private companion object {
        val IPV4_LOOPBACK = byteArrayOf(127, 0, 0, 1)
    }
}
