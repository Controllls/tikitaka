package com.example.hd_project.pleos

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.NaviMode
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import com.example.hd_project.RecordingPhase
import com.example.hd_project.RecordingStore
import com.example.hd_project.RecordingLocation
import com.example.hd_project.tiki.JourneyAccumulator
import com.example.hd_project.tiki.NavigationBackgroundPolicy
import com.example.hd_project.tiki.RoadObservation

/** Read-only navigation subscription scoped to one consented recording. Coordinates and addresses are never persisted. */
class RecordingNavigation(context: Context) : AutoCloseable {
    private val context=context.applicationContext
    private val main=Handler(Looper.getMainLooper())
    private var helper: NaviHelper?=null
    private var closed=false
    private var connected=false
    private var address: String?=null
    private var addressAt=0L
    private var turn: String?=null
    private var turnAt=0L
    private var hasFix=false
    private var navigationActive=true
    private var routeGuiding=false
    private val journey=JourneyAccumulator()
    private val startedAt=SystemClock.elapsedRealtime()
    @Volatile private var fixExpiresAt=0L
    @Volatile private var latest=RoadObservation()
    @Volatile private var expiresAt=0L
    @Volatile var status="connecting"
        private set
    private fun post(action: () -> Unit) { main.post { if(!closed)action() } }
    private fun publish() {
        val now=SystemClock.elapsedRealtime()
        if(!hasFix || !navigationActive || now-addressAt>NavigationBackgroundPolicy.FRESH_MS) { latest=RoadObservation();expiresAt=0;return }
        val freshAddress=address?.takeIf { now-addressAt<=NavigationBackgroundPolicy.FRESH_MS }
        val freshTurn=turn?.takeIf { routeGuiding && now-turnAt<=NavigationBackgroundPolicy.FRESH_MS }
        val next=NavigationBackgroundPolicy.infer(freshAddress,freshTurn)
        // Exit inference requires both a recent location fix and recent guidance.
        val observed=if(next.evidence=="upcoming-highway-exit")minOf(addressAt,turnAt) else addressAt
        latest=next;expiresAt=observed+NavigationBackgroundPolicy.FRESH_MS
    }
    private fun clear() { address=null;turn=null;hasFix=false;addressAt=0;turnAt=0;latest=RoadObservation();expiresAt=0;fixExpiresAt=0;journey.resetFix() }
    private val listener=object: NaviHelperEventListener {
        override fun onCurrentLocationInfo(info: CurrentLocationInfo) { post {
            // Invalid/no-fix coordinates must not make a cached address appear fresh.
            val valid=info.latitude.isFinite() && info.longitude.isFinite() && info.latitude in -90.0..90.0 &&
                info.longitude in -180.0..180.0 && (info.latitude!=0.0 || info.longitude!=0.0)
            hasFix=valid;address=info.address.takeIf { valid && it.isNotBlank() };addressAt=SystemClock.elapsedRealtime()
            fixExpiresAt=if(valid)addressAt+NavigationBackgroundPolicy.FRESH_MS else 0
            journey.observe(info.latitude,info.longitude,addressAt,valid && RecordingStore.state.phase==RecordingPhase.RECORDING)
            publish()
        } }
        override fun onTBTInfo(info: List<TBTInfo>) { post {
            val nearest=info.firstOrNull()
            turn=nearest?.takeIf { it.distance.isFinite() && it.distance>=0 }?.type?.name
            turnAt=SystemClock.elapsedRealtime();publish()
        } }
        override fun onNavigationStatus(value: NavigationStatus) { post {
            navigationActive=value==NavigationStatus.FREE_DRIVE || value==NavigationStatus.ROUTE_DRIVE
            routeGuiding=value==NavigationStatus.ROUTE_DRIVE
            if(value==NavigationStatus.INACTIVE || value==NavigationStatus.INITIALIZING)clear()
            else { if(value==NavigationStatus.FREE_DRIVE)turn=null;publish() }
        } }
        override fun onRouteStateInfo(info: RouteStateInfo) { post {
            navigationActive=info.naviMode!=NaviMode.NOT_WORKING
            routeGuiding=info.naviMode==NaviMode.ROUTE_GUIDING || info.naviMode==NaviMode.SIMULATION_GUIDING
            if(!navigationActive)clear() else publish()
        } }
        override fun onRouteCancelled() { post { routeGuiding=false;clear();request() } }
        override fun onRouteEnded() { post { routeGuiding=false;clear();request() } }
    }
    private val poll=object: Runnable {
        override fun run() {
            if(closed)return
            if(RecordingStore.state.phase==RecordingPhase.RECORDING)request()
            main.postDelayed(this,5_000)
        }
    }
    fun start() { post {
        try {
            val sdk=NaviHelper(context);helper=sdk
            sdk.initialize(onConnected={ post {
                connected=true;status="connected";sdk.addListener(listener);request()
            } },onFailed={ post { status="unavailable";clear() } })
            main.post(poll)
        } catch(_: Exception) { status="unavailable";clear() }
        catch(_: LinkageError) { status="unavailable";clear() }
    } }
    private fun request() {
        if(!connected)return
        try { helper?.getRouteStateInfo();helper?.getCurrentLocationInfo();helper?.getTBTInfo();status="connected" }
        catch(_: Exception) { status="unavailable";clear() }
    }
    fun pause() { journey.resetFix() }
    fun refreshAfterPause() { latest=RoadObservation();expiresAt=0;fixExpiresAt=0;journey.resetFix();post { clear();request() } }
    fun journey()=journey.snapshot()
    fun location(): RecordingLocation {
        val now=SystemClock.elapsedRealtime()
        return when {
            now<=fixExpiresAt -> RecordingLocation.AVAILABLE
            status=="unavailable" || now-startedAt>=NavigationBackgroundPolicy.FRESH_MS -> RecordingLocation.UNAVAILABLE
            else -> RecordingLocation.CONNECTING
        }
    }
    fun observation(): RoadObservation = if(SystemClock.elapsedRealtime()<=expiresAt)latest else RoadObservation()
    override fun close() {
        if(Looper.myLooper()!=Looper.getMainLooper()) { main.post { close() };return }
        if(closed)return
        closed=true;main.removeCallbacksAndMessages(null)
        runCatching { helper?.removeListener(listener) };runCatching { helper?.release() };helper=null
    }
}
