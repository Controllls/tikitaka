package com.example.hd_project.tiki

import android.content.Context
import com.example.hd_project.momo.people.PeopleRepository
import com.example.hd_project.momo.people.PersonProfile

/** New productions use detected voices; legacy staging settings remain available for saved jobs. */
object ProductionSettings {
    const val RENDERER = "tikitaka-local-story-v11"
    const val AUTO_CAST = "detected-voices-v1"
    const val UNKNOWN_CAST = 2
    private fun prefs(context: Context) = context.getSharedPreferences("tikitaka-production", 0)
    fun count(context: Context) = prefs(context).getInt("castCount", 2).coerceIn(1, 4)
    fun seconds(context: Context) = prefs(context).getInt("clipSeconds", 40).coerceIn(10, 120)
    fun save(context: Context, count: Int, seconds: Int) {
        require(count in 1..4 && seconds in 10..120)
        check(prefs(context).edit().putInt("castCount", count).putInt("clipSeconds", seconds).commit())
    }
    fun saveDuration(context: Context, seconds: Int) {
        require(seconds in 10..120)
        check(prefs(context).edit().putInt("clipSeconds",seconds).commit())
    }
    fun renderCount(detected: Int, configured: Int, automatic: Boolean): Int =
        if(automatic) (if(detected>0)detected else UNKNOWN_CAST).coerceIn(1,4)
        else maxOf(configured,detected).coerceIn(1,4)
    fun profile(context: Context, source: String): PersonProfile {
        val repository = PeopleRepository(context, "tikitaka-job-" + EpisodeWork.id(source))
        return repository.list().firstOrNull() ?: TikiProfile.get(context).also(repository::save)
    }
    fun snapshot(context: Context, source: String) {
        profile(context, source)
        EpisodeWork.patch(context, source) {
            if (!it.has("castCount")) it.put("castCount", count(context))
            if (!it.has("clipSeconds")) it.put("clipSeconds", seconds(context))
        }
    }
}
