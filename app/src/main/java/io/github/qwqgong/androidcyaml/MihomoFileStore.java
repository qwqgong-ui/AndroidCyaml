package io.github.qwqgong.androidcyaml;

import android.content.Context;
import android.content.res.AssetManager;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

final class MihomoFileStore {
    static final int MAX_CONFIG_BYTES = 32 * 1024 * 1024;

    private static final String ZASHBOARD_VERSION_ASSET = "zashboard.version";
    private static final String GEODATA_VERSION_ASSET = "geodata.version";

    private final Context context;

    MihomoFileStore(Context context) {
        this.context = context.getApplicationContext();
    }

    MihomoPaths ensureReady() throws IOException {
        File home = new File(context.getNoBackupFilesDir(), "mihomo");
        if (!home.isDirectory() && !home.mkdirs()) {
            throw new IOException("无法创建 mihomo 工作目录");
        }

        File config = new File(home, "config.yaml");
        if (!config.isFile()) {
            copyAssetFile("default-config.yaml", config);
        }
        makeConfigOwnerReadWrite(config);
        ensureGeodata(home);

        File ui = new File(home, "ui");
        ensureDashboard(home, ui);
        return new MihomoPaths(home, config, ui);
    }

    String dashboardVersion() throws IOException {
        return displayVersion(requiredAssetVersion(ZASHBOARD_VERSION_ASSET));
    }

    void makeConfigOwnerReadWrite(File config) throws IOException {
        try {
            Os.chmod(
                    config.getAbsolutePath(),
                    OsConstants.S_IRUSR | OsConstants.S_IWUSR
            );
        } catch (ErrnoException exception) {
            throw new IOException("无法将 config.yaml 权限设为 0600", exception);
        }
    }

    void moveReplacing(File source, File destination) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("无法创建目录：" + parent);
        }
        try {
            Files.move(
                    source.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(
                    source.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    void deleteIfExists(File file) throws IOException {
        Files.deleteIfExists(file.toPath());
    }

    private void ensureGeodata(File home) throws IOException {
        String bundledVersion = requiredAssetVersion(GEODATA_VERSION_ASSET);
        File marker = new File(home, ".androidcyaml-geodata-version");
        File geoIp = new File(home, "GeoIP.dat");
        File geoSite = new File(home, "GeoSite.dat");
        String installedVersion = marker.isFile() ? readText(marker).trim() : "";
        if (bundledVersion.equals(installedVersion) && geoIp.isFile() && geoSite.isFile()) {
            return;
        }

        File geoIpStaging = new File(home, "GeoIP.dat.installing");
        File geoSiteStaging = new File(home, "GeoSite.dat.installing");
        deleteIfExists(geoIpStaging);
        deleteIfExists(geoSiteStaging);
        try {
            copyAssetFile("geodata/GeoIP.dat", geoIpStaging);
            copyAssetFile("geodata/GeoSite.dat", geoSiteStaging);
            moveReplacing(geoIpStaging, geoIp);
            moveReplacing(geoSiteStaging, geoSite);
            writeText(marker, bundledVersion);
        } finally {
            deleteIfExists(geoIpStaging);
            deleteIfExists(geoSiteStaging);
        }
    }

    private void ensureDashboard(File home, File ui) throws IOException {
        String bundledVersion = requiredAssetVersion(ZASHBOARD_VERSION_ASSET);
        File marker = new File(ui, ".androidcyaml-version");
        String installedVersion = marker.isFile() ? readText(marker).trim() : "";
        if (bundledVersion.equals(installedVersion) && new File(ui, "index.html").isFile()) {
            return;
        }

        File staging = new File(home, "ui.installing");
        File previous = new File(home, "ui.previous");
        deleteRecursively(staging);
        deleteRecursively(previous);
        if (!staging.mkdirs()) {
            throw new IOException("无法创建面板暂存目录");
        }
        copyAssetTree("zashboard", staging);
        writeText(new File(staging, ".androidcyaml-version"), bundledVersion);
        if (ui.exists()) {
            moveReplacing(ui, previous);
        }
        try {
            moveReplacing(staging, ui);
        } catch (IOException exception) {
            if (previous.exists() && !ui.exists()) {
                moveReplacing(previous, ui);
            }
            throw exception;
        }
        deleteRecursively(previous);
    }

    private String requiredAssetVersion(String assetPath) throws IOException {
        String version = readAssetText(assetPath).trim();
        if (version.isEmpty()) {
            throw new IOException("资源版本文件为空：" + assetPath);
        }
        return version;
    }

    private String readAssetText(String assetPath) throws IOException {
        try (InputStream input = context.getAssets().open(assetPath)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void copyAssetTree(String assetPath, File destination) throws IOException {
        AssetManager assets = context.getAssets();
        String[] entries = assets.list(assetPath);
        if (entries == null || entries.length == 0) {
            copyAssetFile(assetPath, destination);
            return;
        }
        if (!destination.isDirectory() && !destination.mkdirs()) {
            throw new IOException("无法创建目录：" + destination);
        }
        for (String entry : entries) {
            copyAssetTree(assetPath + "/" + entry, new File(destination, entry));
        }
    }

    private void copyAssetFile(String assetPath, File destination) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("无法创建目录：" + parent);
        }
        try (InputStream input = context.getAssets().open(assetPath);
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.getFD().sync();
        }
    }

    private static String displayVersion(String versionToken) {
        int separator = versionToken.indexOf('@');
        return separator <= 0 ? versionToken : versionToken.substring(0, separator);
    }

    private static String readText(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static void writeText(File file, String value) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
    }

    private static void deleteRecursively(File file) throws IOException {
        if (!file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        Files.deleteIfExists(file.toPath());
    }
}
