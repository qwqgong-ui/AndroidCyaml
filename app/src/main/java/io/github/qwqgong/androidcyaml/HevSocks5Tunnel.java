package io.github.qwqgong.androidcyaml;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class HevSocks5Tunnel {
    private static final String CONFIG_FILE_NAME = "hev-socks5-tunnel.yml";

    static {
        System.loadLibrary("hev-socks5-tunnel");
    }

    private HevSocks5Tunnel() {}

    private static native boolean TProxyStartService(String configPath, int fileDescriptor);

    private static native boolean TProxyStopService();

    private static native boolean TProxyIsRunning();

    @SuppressWarnings("unused")
    private static native long[] TProxyGetStats();

    static synchronized boolean start(
            File cacheDirectory,
            int fileDescriptor,
            int mtu,
            String tunnelIpv4Address,
            String tunnelIpv6Address,
            String socksAddress,
            int socksPort,
            String socksUsername,
            String socksPassword
    ) throws IOException, InterruptedException {
        stop();

        File config = new File(cacheDirectory, CONFIG_FILE_NAME);
        String content = String.format(
                Locale.ROOT,
                "tunnel:\n"
                        + "  mtu: %d\n"
                        + "  ipv4: '%s'\n"
                        + "  ipv6: '%s'\n"
                        + "  icmp: 'reply'\n"
                        + "socks5:\n"
                        + "  address: '%s'\n"
                        + "  port: %d\n"
                        + "  udp: 'udp'\n"
                        + "  udp-address: '%s'\n"
                        + "  pipeline: true\n"
                        + "  tcp-fastopen: true\n"
                        + "  username: '%s'\n"
                        + "  password: '%s'\n"
                        + "misc:\n"
                        + "  task-stack-size: 86016\n"
                        + "  tcp-buffer-size: 65536\n"
                        + "  udp-recv-buffer-size: 524288\n"
                        + "  log-file: stderr\n"
                        + "  log-level: warn\n",
                mtu,
                yamlSingleQuoted(tunnelIpv4Address),
                yamlSingleQuoted(tunnelIpv6Address),
                yamlSingleQuoted(socksAddress),
                socksPort,
                yamlSingleQuoted(socksAddress),
                yamlSingleQuoted(socksUsername),
                yamlSingleQuoted(socksPassword)
        );
        try (FileOutputStream output = new FileOutputStream(config, false)) {
            output.write(content.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }

        if (!TProxyStartService(config.getAbsolutePath(), fileDescriptor)) {
            return false;
        }

        // JNI reports worker creation, not full configuration success. Keep the
        // thread alive briefly so immediate parser/socket errors are surfaced.
        for (int attempt = 0; attempt < 10; attempt++) {
            Thread.sleep(25);
            if (!TProxyIsRunning()) {
                TProxyStopService();
                return false;
            }
        }
        return true;
    }

    static synchronized void stop() {
        try {
            // The native call also joins a worker that already exited after a
            // parser or socket error, avoiding a stale joinable thread.
            TProxyStopService();
        } catch (LinkageError ignored) {
            // Native loading failures are surfaced by start(); cleanup stays safe.
        }
    }

    static synchronized boolean isRunning() {
        try {
            return TProxyIsRunning();
        } catch (LinkageError ignored) {
            return false;
        }
    }

    private static String yamlSingleQuoted(String value) {
        return value.replace("'", "''");
    }
}
