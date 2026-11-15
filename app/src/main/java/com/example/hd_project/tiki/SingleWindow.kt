package com.example.hd_project.tiki

import android.app.Activity
import android.app.ActivityManager
import com.example.hd_project.MainActivity

/**
 * Pleos split view shows each task of an app in its own window, so a driving-safe task replaces
 * the parked-only one instead of sitting next to it. MainActivity restores its place from the launch intent.
 */
internal fun Activity.closeParkedTask() {
    getSystemService(ActivityManager::class.java).appTasks.forEach { task ->
        runCatching { if (task.taskInfo?.baseIntent?.component?.className==MainActivity::class.java.name) task.finishAndRemoveTask() }
    }
}
