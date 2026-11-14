package com.example.hd_project

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardingNotificationPolicyTest {
    private val waiting = BoardingState(enabled = true, serviceRunning = true,
        connection = SeatConnection.READY, occupied = true, awaitingResponse = true)

    @Test fun hiddenScreenRoutesPendingBoardingToNotification() {
        assertTrue(waiting.shouldNotify)
        assertFalse(waiting.copy(screenVisible = true).shouldNotify)
    }
    @Test fun visibleScreenCanPromptWithoutBackgroundService() {
        assertTrue(waiting.copy(screenVisible=true,serviceRunning=false).shouldPrompt)
        assertFalse(waiting.copy(screenVisible=true).shouldNotify)
    }
    @Test fun promptRequiresEnabledFreshUnansweredBoardingWithoutRecording() {
        assertFalse(waiting.copy(enabled=false).shouldPrompt)
        assertFalse(waiting.copy(recording=true).shouldPrompt)
        assertFalse(waiting.copy(occupied=false).shouldPrompt)
        assertFalse(waiting.copy(occupied=null).shouldPrompt)
        assertFalse(waiting.copy(connection=SeatConnection.UNAVAILABLE).shouldPrompt)
        assertFalse(waiting.copy(awaitingResponse=false).shouldPrompt)
    }

    @Test fun disabledOrStoppedStandbyCannotNotify() {
        assertFalse(waiting.copy(enabled = false).shouldNotify)
        assertFalse(waiting.copy(serviceRunning = false).shouldNotify)
    }

    @Test fun staleOrMissingSensorDataCannotNotify() {
        assertFalse(waiting.copy(connection = SeatConnection.UNAVAILABLE).shouldNotify)
        assertFalse(waiting.copy(connection = SeatConnection.CONNECTING).shouldNotify)
        assertFalse(waiting.copy(occupied = null).shouldNotify)
        assertFalse(waiting.copy(occupied = false).shouldNotify)
    }

    @Test fun handledInvitationCannotNotify() {
        assertFalse(waiting.copy(awaitingResponse = false).shouldNotify)
        assertFalse(waiting.copy(recording = true).shouldNotify)
    }
}
