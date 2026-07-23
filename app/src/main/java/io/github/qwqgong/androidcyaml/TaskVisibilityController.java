package io.github.qwqgong.androidcyaml;

import android.app.Activity;
import android.app.ActivityManager;

final class TaskVisibilityController {
    private final Activity activity;

    TaskVisibilityController(Activity activity) {
        this.activity = activity;
    }

    void setHiddenFromRecents(boolean hidden) {
        ActivityManager manager = activity.getSystemService(ActivityManager.class);
        if (manager == null) {
            return;
        }
        for (ActivityManager.AppTask task : manager.getAppTasks()) {
            if (task.getTaskInfo().taskId == activity.getTaskId()) {
                task.setExcludeFromRecents(hidden);
                return;
            }
        }
    }
}
