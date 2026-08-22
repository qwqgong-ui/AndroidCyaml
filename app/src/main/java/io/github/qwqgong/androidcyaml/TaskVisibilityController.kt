package io.github.qwqgong.androidcyaml

import android.app.Activity
import android.app.ActivityManager

class TaskVisibilityController(private val activity: Activity) {
    fun setHiddenFromRecents(hidden: Boolean) {
        val manager = activity.getSystemService(ActivityManager::class.java) ?: return
        for (task in manager.appTasks) {
            if (task.taskInfo?.taskId == activity.taskId) {
                task.setExcludeFromRecents(hidden)
                return
            }
        }
    }
}
