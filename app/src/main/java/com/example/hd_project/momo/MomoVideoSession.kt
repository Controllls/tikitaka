package com.example.hd_project.momo

import android.content.Context
import java.security.MessageDigest

object MomoVideoSession {
    @Volatile var exportingSource: String?=null
        private set
    fun projectId(sourceName: String)="recording-"+MessageDigest.getInstance("SHA-256").digest(sourceName.toByteArray())
        .take(12).joinToString("") { "%02x".format(it) }
    fun pending(context: Context,id: String,value: Boolean) {
        context.getSharedPreferences("momo-pending",0).edit().putBoolean(id,value).apply()
    }
    fun isBusy(context: Context,sourceName: String)=exportingSource==sourceName ||
        context.getSharedPreferences("momo-pending",0).getBoolean(projectId(sourceName),false)
    @Synchronized fun beginExport(sourceName: String) {
        check(exportingSource==null) { "다른 영상을 저장하고 있어요." };exportingSource=sourceName
    }
    @Synchronized fun endExport(sourceName: String) { if(exportingSource==sourceName)exportingSource=null }
}
