package com.example.hd_project.tiki

import android.content.Context
import com.example.hd_project.momo.people.*
import java.util.UUID

object TikiProfile {
    val characters = listOf("01-bear", "02-rabbit", "03-chick", "04-teal", "05-seal", "06-fox", "07-sheep", "08-frog")
    val labels = listOf("곰", "토끼", "병아리", "청록", "물범", "여우", "양", "개구리")
    fun repository(context: Context) = PeopleRepository(context, "tikitaka-profile")
    fun get(context: Context): PersonProfile = repository(context).list().singleOrNull()
        ?: PersonProfile(UUID.randomUUID().toString(), "나", "04-teal")
    fun save(context: Context, profile: PersonProfile) { repository(context).save(profile) }
}
