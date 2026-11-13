package com.example.hd_project.tiki

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.hd_project.MainActivity
import java.io.File

object EpisodeNotifications {
    @android.annotation.SuppressLint("MissingPermission")
    fun completed(context: Context,movie: File) {
        if(!movie.isFile || Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return
        val prefs=context.getSharedPreferences("tikitaka-notifications",0)
        if(prefs.getBoolean(movie.name,false))return
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("tikitaka-episodes","에피소드 완성",NotificationManager.IMPORTANCE_DEFAULT))
        val intent=Intent(context,MainActivity::class.java).putExtra("episodeMovie",movie.name).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending=PendingIntent.getActivity(context,movie.name.hashCode(),intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(movie.name.hashCode(),NotificationCompat.Builder(context,"tikitaka-episodes")
            .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("새 에피소드가 완성됐어요")
            .setContentText("오늘 나눈 이야기를 만나보세요. 영상은 주차 중에 볼 수 있어요.").setContentIntent(pending).setAutoCancel(true).build())
        prefs.edit().putBoolean(movie.name,true).apply()
    }
}
