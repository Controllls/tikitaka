package com.example.hd_project.tiki

import org.junit.Assert.*
import org.junit.Test

class NavigationBackgroundPolicyTest {
    private val city=RoadObservation(RoadEnvironment.CITY,"current-general-road")
    private val highway=RoadObservation(RoadEnvironment.HIGHWAY,"current-road-name")
    @Test fun currentRoadAndNearestExitDetermineTheEnvironment() {
        assertEquals(RoadEnvironment.HIGHWAY,NavigationBackgroundPolicy.infer("경부고속도로 부산방향").environment)
        assertEquals(RoadEnvironment.HIGHWAY,NavigationBackgroundPolicy.infer("Olympic Expressway").environment)
        assertEquals(RoadEnvironment.HIGHWAY,NavigationBackgroundPolicy.infer("서울특별시 강남구", "TURN_EXIT_IC_R").environment)
        assertEquals(RoadEnvironment.CITY,NavigationBackgroundPolicy.infer("서울특별시 강남구 테헤란로 152").environment)
        assertEquals(RoadEnvironment.CITY,NavigationBackgroundPolicy.infer("경기도 성남시 분당내곡로 131").environment)
    }
    @Test fun administrativeAddressOrAnUpcomingEntranceIsNotProofOfHighwayDriving() {
        assertEquals(RoadEnvironment.UNKNOWN,NavigationBackgroundPolicy.infer("서울특별시 강남구").environment)
        assertEquals(RoadEnvironment.UNKNOWN,NavigationBackgroundPolicy.infer(null,"TURN_ENTER_IC_R").environment)
        assertEquals(RoadEnvironment.CITY,NavigationBackgroundPolicy.infer("서울특별시 사평대로 205","TURN_ENTER_IC_R").environment)
        assertEquals(RoadEnvironment.UNKNOWN,NavigationBackgroundPolicy.infer("").environment)
    }
    @Test fun eachHighlightUsesItsOwnRecordedInterval() {
        val trail=RoadTimeline(intervals=listOf(RoadInterval(0,30_000,highway),RoadInterval(30_000,90_000,city)))
        val first=NavigationBackgroundPolicy.select(trail,5_000,25_000)
        val second=NavigationBackgroundPolicy.select(trail,40_000,70_000)
        assertEquals("highway_rearview_loop_10s.mp4",first.asset)
        assertEquals("city_rearview_loop_10s.mp4",second.asset)
        assertNotEquals(first.fingerprint(),second.fingerprint())
        assertEquals(first,NavigationBackgroundPolicy.select(trail,5_000,25_000))
    }
    @Test fun missingOrSparseEvidenceKeepsTheDefaultInsteadOfInventingAnEnvironment() {
        assertEquals("city-road-loop.mp4",NavigationBackgroundPolicy.select(null,0,40_000).asset)
        val sparse=RoadTimeline(intervals=listOf(RoadInterval(0,19_999,highway)))
        assertEquals(RoadEnvironment.UNKNOWN,NavigationBackgroundPolicy.select(sparse,0,40_000).environment)
        assertEquals(RoadEnvironment.HIGHWAY,NavigationBackgroundPolicy.select(sparse,0,30_000).environment)
    }
    @Test fun dominantDurationWinsAndTiesPreferGeneralRoads() {
        val rows=listOf(RoadInterval(0,20_000,city),RoadInterval(20_000,40_000,highway))
        assertEquals(RoadEnvironment.CITY,NavigationBackgroundPolicy.select(RoadTimeline(intervals=rows),0,40_000).environment)
        assertEquals(RoadEnvironment.HIGHWAY,NavigationBackgroundPolicy.select(RoadTimeline(intervals=rows),15_000,40_000).environment)
    }
    @Test fun overlappingOrUnsupportedDataCannotDoubleCountCoverage() {
        val overlap=RoadTimeline(intervals=listOf(RoadInterval(0,30_000,city),RoadInterval(20_000,40_000,highway)))
        assertEquals(RoadEnvironment.UNKNOWN,NavigationBackgroundPolicy.select(overlap,0,40_000).environment)
        assertEquals(RoadEnvironment.UNKNOWN,NavigationBackgroundPolicy.select(overlap.copy(version=2),0,40_000).environment)
    }
    @Test fun pausedWallClockTimeIsNotAddedAndRepeatedObservationsAreCompacted() {
        val recorder=RecordingRoadTimeline()
        recorder.advanceTo(1000,city);recorder.advanceTo(2000,city)
        recorder.advanceTo(2000,highway) // Navigation can change while audio remains paused.
        recorder.advanceTo(3000,highway)
        assertEquals(listOf(RoadInterval(0,2000,city),RoadInterval(2000,3000,highway)),recorder.snapshot().intervals)
    }
    @Test fun expiredObservationsRemainUnknownInTheHighlightCoverage() {
        val recorder=RecordingRoadTimeline()
        recorder.advanceTo(10_000,highway);recorder.advanceTo(40_000,RoadObservation())
        val result=NavigationBackgroundPolicy.select(recorder.snapshot(),0,40_000)
        assertEquals(RoadEnvironment.UNKNOWN,result.environment);assertEquals(10_000L,result.knownMs)
    }
}
