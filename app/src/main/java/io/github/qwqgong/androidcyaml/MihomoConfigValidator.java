package io.github.qwqgong.androidcyaml;

import java.io.File;
import java.io.IOException;

final class MihomoConfigValidator {
    private MihomoConfigValidator() {}

    static void validate(MihomoPaths paths, File candidate) throws IOException {
        MihomoNative.validate(paths, candidate);
    }
}
