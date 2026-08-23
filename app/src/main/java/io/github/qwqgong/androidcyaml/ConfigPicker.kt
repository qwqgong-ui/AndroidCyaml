package io.github.qwqgong.androidcyaml

import android.app.Activity
import android.content.Intent
import android.net.Uri

object ConfigPicker {
    fun open(activity: Activity, requestCode: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "application/x-yaml",
                    "application/yaml",
                    "text/yaml",
                    "text/x-yaml",
                    "text/plain",
                    "application/octet-stream",
                ),
            )
            .addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
        activity.startActivityForResult(
            Intent.createChooser(intent, activity.getString(R.string.choose_yaml)),
            requestCode,
        )
    }

    fun persistReadPermission(activity: Activity, data: Intent?): Uri? {
        val source = data?.data ?: return null
        if (data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) {
            return source
        }
        try {
            activity.contentResolver.takePersistableUriPermission(
                source,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (exception: SecurityException) {
            // Some providers grant only a one-shot read permission.
        }
        return source
    }
}
