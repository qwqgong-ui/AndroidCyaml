package io.github.qwqgong.androidcyaml;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

final class ConfigInstaller {
    private final Context context;
    private final MihomoFileStore fileStore;

    ConfigInstaller(Context context, MihomoFileStore fileStore) {
        this.context = context.getApplicationContext();
        this.fileStore = fileStore;
    }

    Transaction install(Uri source) throws IOException, InterruptedException {
        if (source == null) {
            throw new IOException("未选择配置文件");
        }
        MihomoPaths paths = fileStore.ensureReady();
        File candidate = new File(paths.home(), "config.importing.yaml");
        File backup = new File(paths.home(), "config.previous.yaml");
        fileStore.deleteIfExists(candidate);
        fileStore.deleteIfExists(backup);
        try {
            copyWithLimit(source, candidate, MihomoFileStore.MAX_CONFIG_BYTES);
            MihomoConfigValidator.validate(paths, candidate);
            if (paths.config().isFile()) {
                fileStore.moveReplacing(paths.config(), backup);
            }
            fileStore.moveReplacing(candidate, paths.config());
            fileStore.makeConfigReadOnly(paths.config());
            return new Transaction(fileStore, paths.config(), backup, candidate);
        } catch (IOException | InterruptedException exception) {
            Files.deleteIfExists(candidate.toPath());
            if (backup.isFile()) {
                Files.deleteIfExists(paths.config().toPath());
                fileStore.moveReplacing(backup, paths.config());
                fileStore.makeConfigReadOnly(paths.config());
            }
            throw exception;
        }
    }

    private void copyWithLimit(Uri source, File destination, int byteLimit) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        try (InputStream input = resolver.openInputStream(source);
             FileOutputStream output = new FileOutputStream(destination)) {
            if (input == null) {
                throw new IOException("无法读取所选文件");
            }
            byte[] buffer = new byte[16 * 1024];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > byteLimit) {
                    throw new IOException("config.yaml 超过 32 MiB 限制");
                }
                output.write(buffer, 0, read);
            }
            output.getFD().sync();
        }
    }

    static final class Transaction implements Closeable {
        private final MihomoFileStore fileStore;
        private final File config;
        private final File backup;
        private final File candidate;
        private boolean finished;

        private Transaction(
                MihomoFileStore fileStore,
                File config,
                File backup,
                File candidate
        ) {
            this.fileStore = fileStore;
            this.config = config;
            this.backup = backup;
            this.candidate = candidate;
        }

        void commit() throws IOException {
            if (finished) {
                return;
            }
            fileStore.deleteIfExists(backup);
            finished = true;
        }

        void rollback() throws IOException {
            if (finished) {
                return;
            }
            fileStore.deleteIfExists(config);
            if (!backup.isFile()) {
                throw new IOException("上一份配置不存在，无法回滚");
            }
            fileStore.moveReplacing(backup, config);
            fileStore.makeConfigReadOnly(config);
            finished = true;
        }

        @Override
        public void close() throws IOException {
            fileStore.deleteIfExists(candidate);
        }
    }
}
