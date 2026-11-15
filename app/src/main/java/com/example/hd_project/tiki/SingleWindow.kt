package com.example.hd_project.tiki

import android.app.Activity
import android.app.ActivityManager
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import com.example.hd_project.MainActivity
import java.lang.ref.WeakReference

/**
 * Pleos split view shows each task of an app in its own window, so a driving-safe task replaces
 * the parked-only one instead of sitting next to it. MainActivity restores its place from the launch intent.
 */
internal fun Activity.closeParkedTask() {
    getSystemService(ActivityManager::class.java).appTasks.forEach { task ->
        runCatching { if (task.taskInfo?.baseIntent?.component?.className==MainActivity::class.java.name) task.finishAndRemoveTask() }
    }
}

/**
 * Builds the screen before the window draws its first frame. A window that first draws empty
 * replaces the previous task's window with a blank one until the content arrives.
 */
internal fun View.renderBeforeFirstDraw(render: () -> Unit) {
    viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
            if (width == 0) return true
            viewTreeObserver.removeOnPreDrawListener(this)
            render()
            return false
        }
    })
}

/**
 * How long to keep the window being replaced after the new one is on screen. The home screen swaps the
 * two task views with a short animation (about 230 ms); removing the old task during it shows both side
 * by side. In split view the home screen shows the old task in the neighbouring window from the end of
 * that animation until it is removed, so this is no longer than the animation needs.
 */
internal const val TASK_SWITCH_MS = 350L

/** Runs [block] once the next frame is on screen. */
internal fun View.afterNextFrame(block: () -> Unit) {
    if (Build.VERSION.SDK_INT >= 29) viewTreeObserver.registerFrameCommitCallback { post(block) } else post(block)
}

/** The driving-safe screen that has opened MainActivity and waits to be covered by it before finishing. */
internal object LeavingScreen {
    private var screen = WeakReference<Activity>(null)
    fun set(activity: Activity) { screen = WeakReference(activity) }
    fun clear(activity: Activity) { if (screen.get() === activity) screen.clear() }
    fun finish() { screen.get()?.let { if (!it.isFinishing) it.finish() };screen.clear() }
}
