package com.example.hd_project.tiki

import android.content.Context
import com.example.hd_project.momo.people.PeopleRepository
import com.example.hd_project.momo.people.PersonProfile

/** Cast size is a staging choice, never a claim about detected occupants or speakers. */
object ProductionSettings {
    const val RENDERER = "tikitaka-local-wide-v7"
    private fun prefs(context: Context) = context.getSharedPreferences("tikitaka-production", 0)
    fun count(context: Context) = prefs(context).getInt("castCount", 2).coerceIn(1, 4)
    fun seconds(context: Context) = prefs(context).getInt("clipSeconds", 40).coerceIn(10, 120)
    fun save(context: Context, count: Int, seconds: Int) {
        require(count in 1..4 && seconds in 10..120)
        check(prefs(context).edit().putInt("castCount", count).putInt("clipSeconds", seconds).commit())
    }
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
