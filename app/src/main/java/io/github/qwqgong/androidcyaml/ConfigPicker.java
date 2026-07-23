package io.github.qwqgong.androidcyaml;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

final class ConfigPicker {
    private ConfigPicker() {}

    static void open(Activity activity, int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                        "application/x-yaml",
                        "application/yaml",
                        "text/yaml",
                        "text/x-yaml",
                        "text/plain",
                        "application/octet-stream"
                })
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        activity.startActivityForResult(
                Intent.createChooser(intent, activity.getString(R.string.choose_yaml)),
                requestCode
        );
    }

    static Uri persistReadPermission(Activity activity, Intent data) {
        Uri source = data == null ? null : data.getData();
        if (source == null) {
            return null;
        }
        try {
            activity.getContentResolver().takePersistableUriPermission(
                    source,
                    data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION
            );
        } catch (SecurityException ignored) {
            // Some providers grant only a one-shot read permission.
        }
        return source;
    }
}
