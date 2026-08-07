package io.github.qwqgong.androidcyaml;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class WebViewConnectProxyTest {
    private static final byte[] IPV4_LOOPBACK = {127, 0, 0, 1};

    @Test
    public void connectUsesRegisteredServerInsteadOfUrlDns() throws Exception {
        try (ServerSocket target = new ServerSocket()) {
            target.bind(new InetSocketAddress(ipv4Loopback(), 0));
            CountDownLatch targetDone = new CountDownLatch(1);
            AtomicReference<Throwable> targetFailure = new AtomicReference<>();
            Thread server = new Thread(() -> {
                try (Socket accepted = target.accept()) {
                    byte[] request = accepted.getInputStream().readNBytes(4);
                    assertArrayEquals("ping".getBytes(StandardCharsets.US_ASCII), request);
                    accepted.getOutputStream().write("pong".getBytes(StandardCharsets.US_ASCII));
                    accepted.getOutputStream().flush();
                } catch (Throwable throwable) {
                    targetFailure.set(throwable);
                } finally {
                    targetDone.countDown();
                }
            });
            server.setDaemon(true);
            server.start();

            AtomicBoolean protectedBeforeConnect = new AtomicBoolean(false);
            AtomicBoolean routeBoundBeforeProtect = new AtomicBoolean(false);
            AtomicReference<String> resolvedHost = new AtomicReference<>();
            WebViewConnectProxy.NetworkRoute route = new WebViewConnectProxy.NetworkRoute() {
                @Override
                public InetAddress[] resolve(String host) throws IOException {
                    resolvedHost.set(host);
                    return new InetAddress[]{ipv4Loopback()};
                }

                @Override
                public void bind(Socket socket) throws IOException {
                    socket.getReuseAddress();
                    routeBoundBeforeProtect.set(true);
                }
            };
            try (WebViewConnectProxy proxy = new WebViewConnectProxy(
                    socket -> {
                        protectedBeforeConnect.set(
                                routeBoundBeforeProtect.get() && !socket.isConnected()
                        );
                        return true;
                    },
                    () -> route
                 );
                 Socket client = new Socket()) {
                proxy.register(
                        "https://unresolvable.invalid/xhttp",
                        "endpoint.invalid:" + target.getLocalPort()
                );
                client.connect(new InetSocketAddress(
                        ipv4Loopback(),
                        proxy.port()
                ));
                assertTrue(proxy.authority().startsWith("127.0.0.1:"));
                OutputStream output = client.getOutputStream();
                output.write(("CONNECT unresolvable.invalid:443 HTTP/1.1\r\n"
                        + "Host: unresolvable.invalid:443\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                output.flush();

                BufferedInputStream input = new BufferedInputStream(client.getInputStream());
                String headers = readHeaders(input);
                assertTrue(headers.startsWith("HTTP/1.1 200"));

                output.write("ping".getBytes(StandardCharsets.US_ASCII));
                output.flush();
                assertArrayEquals(
                        "pong".getBytes(StandardCharsets.US_ASCII),
                        input.readNBytes(4)
                );
                assertTrue(protectedBeforeConnect.get());
                assertTrue("endpoint.invalid".equals(resolvedHost.get()));
            }

            assertTrue(targetDone.await(5, TimeUnit.SECONDS));
            if (targetFailure.get() != null) {
                throw new AssertionError(targetFailure.get());
            }
        }
    }

    @Test
    public void unknownAuthorityIsRejected() throws Exception {
        try (WebViewConnectProxy proxy = directProxy(socket -> true);
             Socket client = new Socket()) {
            client.connect(new InetSocketAddress(
                    ipv4Loopback(),
                    proxy.port()
            ));
            client.getOutputStream().write(("CONNECT unknown.invalid:443 HTTP/1.1\r\n"
                    + "Host: unknown.invalid:443\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().flush();
            String headers = readHeaders(client.getInputStream());
            assertTrue(headers.startsWith("HTTP/1.1 403"));
        }
    }

    @Test
    public void sameAuthorityAcceptsRegisteredEndpointPool() throws Exception {
        try (WebViewConnectProxy proxy = directProxy(socket -> true)) {
            proxy.register("https://same.invalid/xhttp", "127.0.0.1:1443");
            proxy.register("https://same.invalid/other", "127.0.0.1:1443");
            proxy.register("https://same.invalid/xhttp", "127.0.0.1:2443");
        }
    }

    @Test
    public void sameAuthorityFallsBackToAnotherRegisteredEndpoint() throws Exception {
        int unavailablePort;
        try (ServerSocket reservation = new ServerSocket()) {
            reservation.bind(new InetSocketAddress(ipv4Loopback(), 0));
            unavailablePort = reservation.getLocalPort();
        }

        try (ServerSocket target = new ServerSocket()) {
            target.bind(new InetSocketAddress(ipv4Loopback(), 0));
            CountDownLatch targetDone = new CountDownLatch(1);
            Thread server = new Thread(() -> {
                try (Socket ignored = target.accept()) {
                    targetDone.countDown();
                } catch (IOException ignored) {
                    // The assertion below reports a missing successful dial.
                }
            });
            server.setDaemon(true);
            server.start();

            try (WebViewConnectProxy proxy = directProxy(socket -> true);
                 Socket client = new Socket()) {
                proxy.register(
                        "https://pool.invalid/xhttp",
                        "127.0.0.1:" + unavailablePort
                );
                proxy.register(
                        "https://pool.invalid/xhttp",
                        "127.0.0.1:" + target.getLocalPort()
                );
                client.connect(new InetSocketAddress(ipv4Loopback(), proxy.port()));
                client.getOutputStream().write(("CONNECT pool.invalid:443 HTTP/1.1\r\n"
                        + "Host: pool.invalid:443\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                client.getOutputStream().flush();

                assertTrue(readHeaders(client.getInputStream()).startsWith("HTTP/1.1 200"));
                assertTrue(targetDone.await(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    public void rejectedSocketProtectionFailsBeforeRemoteConnect() throws Exception {
        try (ServerSocket target = new ServerSocket()) {
            target.bind(new InetSocketAddress(ipv4Loopback(), 0));
            AtomicBoolean protectionCalled = new AtomicBoolean(false);
            try (WebViewConnectProxy proxy = directProxy(socket -> {
                     protectionCalled.set(true);
                     assertFalse(socket.isConnected());
                     return false;
                 });
                 Socket client = new Socket()) {
                proxy.register(
                        "https://protected.invalid/xhttp",
                        "127.0.0.1:" + target.getLocalPort()
                );
                client.connect(new InetSocketAddress(
                        ipv4Loopback(),
                        proxy.port()
                ));
                client.getOutputStream().write(("CONNECT protected.invalid:443 HTTP/1.1\r\n"
                        + "Host: protected.invalid:443\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                client.getOutputStream().flush();

                String headers = readHeaders(client.getInputStream());
                assertTrue(headers.startsWith("HTTP/1.1 502 VPN Protect Failed"));
                assertTrue(protectionCalled.get());
            }
        }
    }

    private static WebViewConnectProxy directProxy(
            WebViewConnectProxy.SocketProtector protector
    ) throws IOException {
        WebViewConnectProxy.NetworkRoute route = new WebViewConnectProxy.NetworkRoute() {
            @Override
            public InetAddress[] resolve(String host) throws IOException {
                return InetAddress.getAllByName(host);
            }

            @Override
            public void bind(Socket socket) throws IOException {
                socket.getReuseAddress();
            }
        };
        return new WebViewConnectProxy(protector, () -> route);
    }

    private static InetAddress ipv4Loopback() throws IOException {
        return InetAddress.getByAddress(IPV4_LOOPBACK);
    }

    private static String readHeaders(InputStream input) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int matched = 0;
        while (buffer.size() < 16 * 1024) {
            int value = input.read();
            if (value < 0) {
                break;
            }
            buffer.write(value);
            matched = switch (matched) {
                case 0 -> value == '\r' ? 1 : 0;
                case 1 -> value == '\n' ? 2 : 0;
                case 2 -> value == '\r' ? 3 : 0;
                case 3 -> value == '\n' ? 4 : 0;
                default -> 4;
            };
            if (matched == 4) {
                break;
            }
        }
        return buffer.toString(StandardCharsets.US_ASCII);
    }
}
