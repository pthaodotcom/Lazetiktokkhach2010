package com.perkompus.laze

enum class TestState {
    INIT, CHECK_TIKTOK, OPEN_TEST_VIDEO, VERIFY_PLAYBACK, WAIT_PLAYBACK_END,
    RECORD_PLAYBACK_RESULT, NAVIGATION_TEST, VERIFY_NAVIGATION, SAVE_LOGS, STOP
}
enum class TestAction { NONE, OPEN_VIDEO, SWIPE }

data class TestConfig(
    val videoUrl: String,
    val videoMarker: String,
    val navigationCount: Int = 2,
    val awaySeconds: Int = 35,
    val playbackTimeoutSeconds: Int = 180
) {
    init {
        require(Regex("https://(www\\.)?tiktok\\.com/@[^/?#]+/video/[0-9]+/?").matches(videoUrl)) {
            "Dùng link đầy đủ https://www.tiktok.com/@tacgia/video/123456789."
        }
        require(videoMarker.trim().length in 8..200) { "Nhập đoạn mô tả riêng của video A (8–200 ký tự)." }
        require(navigationCount in 2..3) { "Chọn 2 hoặc 3 video để kiểm tra điều hướng." }
        require(awaySeconds in 30..40) { "Thời gian ở các video khác phải từ 30 đến 40 giây." }
        require(playbackTimeoutSeconds in 30..900) { "Giới hạn chờ phát phải từ 30 đến 900 giây." }
    }
}

data class ScreenSample(
    val isTikTok: Boolean,
    val identity: String? = null,
    val targetMatched: Boolean = false,
    val progress: Float? = null,
    val blocker: String? = null
)

/** Android-independent controller. Exactly one playback and a finite navigation test. */
class TestSession(val config: TestConfig, private val record: (TestState, String) -> Unit) {
    var state = TestState.INIT
        private set
    var startedPlaybacks = 0
        private set
    var completedPlaybacks = 0
        private set
    var verifiedNavigations = 0
        private set
    var outcome = "RUNNING"
        private set
    var reason = ""
        private set
    var completionEvidence: String? = null
        private set
    var returnedToTarget = false
        private set
    private var enteredAt = 0L
    private var openedAt = 0L
    private var sessionStartedAt = 0L
    private var lastMovementAt = 0L
    private var lastValidAt = 0L
    private var previousProgress: Float? = null
    private var lastPlaybackSampleAt = 0L
    private var movements = 0
    private var returning = false
    private var actionPending = false
    private var currentIdentity = "target"
    private var candidateIdentity: String? = null
    private var candidateSamples = 0
    private var candidateProgress: Float? = null
    private var awayStartedAt: Long? = null
    private var nextNavigationAt = 0L
    val active get() = state != TestState.INIT && state != TestState.STOP
    val expectedVideoIdentity get() = currentIdentity

    fun start(now: Long) {
        check(state == TestState.INIT)
        sessionStartedAt = now
        record(state, "SESSION_STARTED")
        move(TestState.CHECK_TIKTOK, now, "CHECK_INSTALLED_PACKAGE")
    }

    fun checked(installed: Boolean, now: Long): TestAction {
        if (state != TestState.CHECK_TIKTOK) return TestAction.NONE
        if (!installed) return fail("TIKTOK_NOT_INSTALLED", now)
        move(TestState.OPEN_TEST_VIDEO, now, "OPEN_EXPLICIT_TEST_URL")
        actionPending = true
        return TestAction.OPEN_VIDEO
    }

    fun opened(success: Boolean, now: Long) {
        if (!active || !actionPending || state != TestState.OPEN_TEST_VIDEO) return
        actionPending = false
        if (!success) { fail("CANNOT_OPEN_VIDEO", now); return }
        openedAt = now
        lastValidAt = now
        lastMovementAt = now
        previousProgress = null
        movements = 0
        candidateIdentity = null
        candidateSamples = 0
        move(if (returning) TestState.VERIFY_NAVIGATION else TestState.VERIFY_PLAYBACK,
            now, if (returning) "VERIFY_RETURN_TO_A" else "VERIFY_A_AND_PLAYBACK_FROM_START")
    }

    fun gestureFinished(success: Boolean, now: Long) {
        if (state != TestState.NAVIGATION_TEST || !actionPending) return
        actionPending = false
        if (!success) { fail("GESTURE_FAILED", now); return }
        candidateIdentity = null
        candidateSamples = 0
        move(TestState.VERIFY_NAVIGATION, now, "VERIFY_CHANGED_VIDEO")
    }

    fun observe(sample: ScreenSample, now: Long): TestAction {
        if (!active) return TestAction.NONE
        if (now - sessionStartedAt > (config.playbackTimeoutSeconds + config.awaySeconds + 90) * 1000L) {
            return fail("SESSION_TIMEOUT", now)
        }
        if (actionPending) return if (now - enteredAt > 10_000) fail("ACTION_TIMEOUT", now) else TestAction.NONE
        sample.blocker?.let { return fail(it, now) }
        if (!sample.isTikTok) {
            val opening = state == TestState.VERIFY_PLAYBACK || (returning && state == TestState.VERIFY_NAVIGATION)
            return if (!opening || now - enteredAt > 10_000) fail("TIKTOK_NOT_FOREGROUND", now) else TestAction.NONE
        }
        // State deadlines also apply when no usable nodes are exposed.
        if (state == TestState.VERIFY_NAVIGATION && now - enteredAt > 10_000) return fail("NAVIGATION_NOT_VERIFIED", now)
        if (state == TestState.VERIFY_PLAYBACK && now - openedAt > config.playbackTimeoutSeconds * 1000L) return fail("PLAYBACK_TIMEOUT", now)
        val progress = sample.progress?.takeIf { it.isFinite() && it in 0f..1f }
        if (progress == null || sample.identity == null) {
            candidateIdentity = null
            candidateSamples = 0
            candidateProgress = null
            return if (now - lastValidAt > 5_000) fail("SCREEN_OR_PROGRESS_UNVERIFIABLE", now) else TestAction.NONE
        }
        lastValidAt = now
        when (state) {
            TestState.VERIFY_PLAYBACK, TestState.WAIT_PLAYBACK_END -> {
                if (!sample.targetMatched) {
                    if (state == TestState.WAIT_PLAYBACK_END || now - enteredAt > 10_000) return fail("TARGET_VIDEO_NOT_VERIFIED", now)
                    return TestAction.NONE
                }
                if (now - openedAt > config.playbackTimeoutSeconds * 1000L) return fail("PLAYBACK_TIMEOUT", now)
                if (state == TestState.VERIFY_PLAYBACK && previousProgress == null) {
                    // Joining at 95% must never count as a full playback.
                    if (progress > 0.05f) return TestAction.NONE
                    previousProgress = progress
                    lastPlaybackSampleAt = now
                    lastMovementAt = now
                    return TestAction.NONE
                }
                val previous = previousProgress ?: progress
                if (now - lastPlaybackSampleAt > 2500) return fail("PLAYBACK_OBSERVATION_GAP", now)
                lastPlaybackSampleAt = now
                if (progress - previous > 0.25f) return fail("UNEXPECTED_PROGRESS_JUMP", now)
                val ended = state == TestState.WAIT_PLAYBACK_END && movements >= 2 &&
                    (progress >= 0.999f || (previous >= 0.98f && progress <= 0.05f))
                if (ended) {
                    completionEvidence = if (progress >= 0.999f) "RANGE_END_OBSERVED" else "NEAR_END_THEN_REPLAY_INFERRED"
                    completedPlaybacks = 1
                    move(TestState.RECORD_PLAYBACK_RESULT, now, completionEvidence!!)
                    nextNavigationAt = now
                    move(TestState.NAVIGATION_TEST, now, "BEGIN_BOUNDED_NAVIGATION")
                    return nextNavigation(now)
                }
                if (progress < previous - 0.02f) return fail("PLAYBACK_RESET_BEFORE_CONFIRMED_END", now)
                if (progress > previous + 0.0001f) {
                    movements++
                    lastMovementAt = now
                    if (state == TestState.VERIFY_PLAYBACK && movements >= 2) {
                        startedPlaybacks = 1
                        move(TestState.WAIT_PLAYBACK_END, now, "PROGRESS_ADVANCING")
                    }
                }
                previousProgress = progress
                if (now - lastMovementAt > 15_000) return fail("PLAYBACK_STALLED_OR_PAUSED", now)
            }
            TestState.VERIFY_NAVIGATION -> {
                if (returning) {
                    if (!sample.targetMatched) {
                        previousProgress = null
                        movements = 0
                        return TestAction.NONE
                    }
                    val previous = previousProgress
                    if (previous != null && progress > previous + 0.0001f) movements++
                    previousProgress = progress
                    if (movements >= 2) {
                        returnedToTarget = true
                        finish("PASS", "RETURN_TO_A_AND_PLAYBACK_VERIFIED", now)
                    }
                } else {
                    if (sample.targetMatched || sample.identity == currentIdentity) {
                        candidateIdentity = null
                        candidateSamples = 0
                        candidateProgress = null
                        return TestAction.NONE
                    }
                    val advancing = candidateProgress?.let { progress > it + 0.0001f } == true
                    if (candidateIdentity == sample.identity) candidateSamples++ else {
                        candidateIdentity = sample.identity
                        candidateSamples = 1
                    }
                    candidateProgress = progress
                    if (candidateSamples >= 2 && advancing) {
                        currentIdentity = sample.identity
                        verifiedNavigations++
                        if (awayStartedAt == null) awayStartedAt = now
                        nextNavigationAt = awayStartedAt!! + verifiedNavigations * config.awaySeconds * 1000L / config.navigationCount
                        previousProgress = progress
                        lastMovementAt = now
                        move(TestState.NAVIGATION_TEST, now, "VIDEO_CHANGED_$verifiedNavigations")
                    }
                }
            }
            TestState.NAVIGATION_TEST -> {
                if (sample.targetMatched || sample.identity != currentIdentity) return fail("UNEXPECTED_VIDEO_CHANGE", now)
                if (previousProgress?.let { kotlin.math.abs(progress - it) > 0.0001f } == true) lastMovementAt = now
                previousProgress = progress
                if (now - lastMovementAt > 15_000) return fail("OTHER_PLAYBACK_STALLED_OR_PAUSED", now)
                if (now >= nextNavigationAt) return nextNavigation(now)
            }
            else -> Unit
        }
        return TestAction.NONE
    }

    private fun nextNavigation(now: Long): TestAction {
        actionPending = true
        enteredAt = now
        return if (verifiedNavigations < config.navigationCount) TestAction.SWIPE else {
            returning = true
            move(TestState.OPEN_TEST_VIDEO, now, "RETURN_TO_A_AFTER_CONFIGURED_WAIT")
            TestAction.OPEN_VIDEO
        }
    }

    fun stop(now: Long, code: String = "USER_STOP") { if (active) finish("STOPPED", code, now) }
    fun fail(code: String, now: Long): TestAction {
        if (active) finish("FAILED", code, now)
        return TestAction.NONE
    }
    private fun finish(result: String, code: String, now: Long) {
        outcome = result
        reason = code
        actionPending = false
        move(TestState.SAVE_LOGS, now, code)
        move(TestState.STOP, now, code)
    }
    private fun move(next: TestState, now: Long, event: String) {
        state = next
        enteredAt = now
        record(next, event)
    }
}
