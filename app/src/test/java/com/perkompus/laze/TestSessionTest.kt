package com.perkompus.laze

import org.junit.Assert.*
import org.junit.Test

class TestSessionTest {
    private val config = TestConfig("https://www.tiktok.com/@tester/video/123456789", "unique test caption")
    private fun a(progress: Float) = ScreenSample(true, "target", true, progress)
    private fun b(id: String, progress: Float) = ScreenSample(true, id, false, progress)
    private fun opened(config: TestConfig = this.config): TestSession {
        return TestSession(config) { _, _ -> }.also {
            it.start(0)
            assertEquals(TestAction.OPEN_VIDEO, it.checked(true, 0))
            it.opened(true, 0)
        }
    }
    private fun completed(config: TestConfig = this.config): TestSession {
        return opened(config).also {
            it.observe(a(0f), 500)
            it.observe(a(0.1f), 1000)
            it.observe(a(0.2f), 1500)
            it.observe(a(.4f), 1750); it.observe(a(.6f), 2000); it.observe(a(.8f), 2250)
            assertEquals(TestAction.NONE, it.observe(a(0.95f), 2400))
            assertEquals(TestAction.SWIPE, it.observe(a(1f), 2500))
            assertEquals(1, it.completedPlaybacks)
        }
    }
    @Test fun noAutomaticStart() {
        val s = TestSession(config) { _, _ -> }
        assertFalse(s.active)
        assertEquals(TestAction.NONE, s.observe(a(1f), 0))
    }
    @Test fun missingPackageStopsBeforeOpen() {
        val s = TestSession(config) { _, _ -> }; s.start(0)
        assertEquals(TestAction.NONE, s.checked(false, 0))
        assertEquals("TIKTOK_NOT_INSTALLED", s.reason)
        assertEquals(TestState.STOP, s.state)
    }
    @Test fun failedOpenStops() {
        val s = TestSession(config) { _, _ -> }; s.start(0); s.checked(true, 0); s.opened(false, 0)
        assertEquals("CANNOT_OPEN_VIDEO", s.reason)
    }
    @Test fun initialNinetyFivePercentNeverCountsFullPlayback() {
        val s = opened()
        s.observe(a(.95f), 1000); s.observe(a(1f), 2000)
        assertEquals(0, s.startedPlaybacks)
        assertEquals(0, s.completedPlaybacks)
        assertEquals(TestState.VERIFY_PLAYBACK, s.state)
    }
    @Test fun progressMustActuallyMoveBeforeCountingPlayback() {
        val s = opened()
        s.observe(a(.01f), 0)
        for (time in 500L..15_500L step 500) s.observe(a(.01f), time)
        assertEquals("PLAYBACK_STALLED_OR_PAUSED", s.reason)
        assertEquals(0, s.startedPlaybacks)
    }
    @Test fun missingProgressStopsWithoutGestures() {
        val s = opened(); s.observe(ScreenSample(true, "target", true), 5001)
        assertEquals("SCREEN_OR_PROGRESS_UNVERIFIABLE", s.reason)
    }
    @Test fun popupAndNetworkErrorsStop() {
        for (code in listOf("POPUP_OR_INPUT_SCREEN", "NETWORK_LOGIN_OR_UNAVAILABLE_SCREEN")) {
            val s = opened(); s.observe(a(.01f).copy(blocker = code), 500)
            assertEquals(code, s.reason); assertEquals(0, s.completedPlaybacks)
        }
    }
    @Test fun leavingTikTokWhileWatchingStops() {
        val s = opened(); s.observe(a(0f), 0); s.observe(a(.1f), 500); s.observe(a(.2f), 1000)
        s.observe(ScreenSample(false), 1500)
        assertEquals("TIKTOK_NOT_FOREGROUND", s.reason)
    }
    @Test fun wrongTargetNeverCountsPlayback() {
        val s = opened(); s.observe(b("other", .5f), 10_001)
        assertEquals("TARGET_VIDEO_NOT_VERIFIED", s.reason)
    }
    @Test fun earlyReplayIsNotCompletion() {
        val s = opened(); s.observe(a(0f), 0); s.observe(a(.1f), 500); s.observe(a(.2f), 1000)
        s.observe(a(.4f), 1250); s.observe(a(.6f), 1500); s.observe(a(.7f), 1750); s.observe(a(.01f), 2000)
        assertEquals("PLAYBACK_RESET_BEFORE_CONFIRMED_END", s.reason)
        assertEquals(0, s.completedPlaybacks)
    }
    @Test fun nearEndReplayIsExplicitlyMarkedAsInference() {
        val s = opened(); s.observe(a(0f), 0); s.observe(a(.1f), 500); s.observe(a(.2f), 1000)
        s.observe(a(.4f), 1250); s.observe(a(.6f), 1500); s.observe(a(.8f), 1750); s.observe(a(.985f), 1900)
        assertEquals(TestAction.SWIPE, s.observe(a(.01f), 2000))
        assertEquals("NEAR_END_THEN_REPLAY_INFERRED", s.completionEvidence)
    }
    @Test fun gestureCallbackAloneDoesNotProveNavigation() {
        val s = completed(); s.gestureFinished(true, 3000)
        s.observe(a(.01f), 3500)
        assertEquals(0, s.verifiedNavigations)
        s.observe(a(.1f), 13_001)
        assertEquals("NAVIGATION_NOT_VERIFIED", s.reason)
    }
    @Test fun cancelledGestureStops() {
        val s = completed(); s.gestureFinished(false, 3000)
        assertEquals("GESTURE_FAILED", s.reason)
    }
    @Test fun frozenDifferentVideoDoesNotProveNavigation() {
        val s = completed(); s.gestureFinished(true, 3000)
        s.observe(b("B", .2f), 3500); s.observe(b("B", .2f), 4000)
        assertEquals(0, s.verifiedNavigations)
    }
    @Test fun boundedTwoAndThreeVideoRunsReturnAndStopWithoutCountingSecondPlayback() {
        for (count in 2..3) {
            val s = completed(config.copy(navigationCount = count, awaySeconds = 36))
            var time = 2500L
            var awayStart = 0L
            for (index in 1..count) {
                s.gestureFinished(true, time + 100)
                s.observe(b("other-$index", .1f), time + 500)
                s.observe(b("other-$index", .2f), time + 1000)
                if (index == 1) awayStart = time + 1000
                assertEquals(index, s.verifiedNavigations)
                val deadline = awayStart + index * 36_000L / count
                var tick = time + 1500
                while (tick < deadline) {
                    assertEquals(TestAction.NONE, s.observe(b("other-$index", (tick % 5000) / 5000f), tick))
                    tick += 500
                }
                assertEquals(if (index < count) TestAction.SWIPE else TestAction.OPEN_VIDEO,
                    s.observe(b("other-$index", .5f), deadline))
                time = deadline
            }
            assertEquals(36_000L, time - awayStart)
            s.opened(true, time)
            s.observe(a(.1f), time + 500); s.observe(a(.2f), time + 1000); s.observe(a(.3f), time + 1500)
            assertEquals("PASS", s.outcome)
            assertTrue(s.returnedToTarget)
            assertEquals(1, s.startedPlaybacks); assertEquals(1, s.completedPlaybacks)
            assertEquals(TestState.STOP, s.state)
            assertEquals(TestAction.NONE, s.observe(a(1f), time + 2000))
        }
    }
    @Test fun stopInvalidatesPendingGestureCallback() {
        val s = completed(); s.stop(3000); s.gestureFinished(true, 3500)
        assertEquals(TestState.STOP, s.state); assertEquals("USER_STOP", s.reason)
        assertEquals(TestAction.NONE, s.observe(b("B", .5f), 4000))
    }
    @Test fun everyTerminalPathSavesBeforeStop() {
        val states = mutableListOf<TestState>()
        val s = TestSession(config) { state, _ -> states.add(state) }
        s.start(0); s.stop(500)
        assertEquals(listOf(TestState.SAVE_LOGS, TestState.STOP), states.takeLast(2))
    }
    @Test fun seekingOrDiscontinuousProgressDoesNotCountAsFullPlayback() {
        val s = opened(); s.observe(a(0f), 0); s.observe(a(.1f), 500); s.observe(a(.2f), 1000)
        s.observe(a(.99f), 1500)
        assertEquals("UNEXPECTED_PROGRESS_JUMP", s.reason); assertEquals(0, s.completedPlaybacks)
    }
    @Test fun observationGapDoesNotCountAsFullPlayback() {
        val s = opened(); s.observe(a(0f), 0); s.observe(a(.1f), 500); s.observe(a(.2f), 1000)
        s.observe(a(.3f), 4000)
        assertEquals("PLAYBACK_OBSERVATION_GAP", s.reason)
    }
    @Test fun invalidConfigurationsAreRejected() {
        for (make in listOf<() -> TestConfig>(
            { config.copy(videoUrl = "https://tiktok.com.evil.example/@a/video/123") },
            { config.copy(videoMarker = "short") }, { config.copy(navigationCount = 4) },
            { config.copy(awaySeconds = 0) }, { config.copy(playbackTimeoutSeconds = 901) }
        )) {
            try { make(); fail("Must reject invalid config") } catch (_: IllegalArgumentException) { }
        }
    }
}
