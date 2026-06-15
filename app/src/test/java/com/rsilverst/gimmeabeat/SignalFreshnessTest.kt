package com.rsilverst.gimmeabeat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure signal-freshness decision used by the auto-mode loop
 * to tell a live watch signal from a lost one (and when to give up). No Android
 * dependencies — this is the logic [AutoModeService.evaluateSignal] delegates to.
 */
class SignalFreshnessTest {

    private val fresh = 12_000L     // SIGNAL_FRESH_MS
    private val giveUp = 120_000L   // SIGNAL_GIVE_UP_MS

    @Test
    fun `fresh reading with a value is live`() {
        val status = SignalFreshness.classify(
            lastKnownBpm = 140,
            signalAgeMs = 3_000,
            lostMs = 0,
            freshThresholdMs = fresh,
        )
        assertEquals(SignalStatus.Live(140), status)
    }

    @Test
    fun `age exactly at the threshold is still live`() {
        val status = SignalFreshness.classify(
            lastKnownBpm = 120,
            signalAgeMs = fresh,
            lostMs = 0,
            freshThresholdMs = fresh,
        )
        assertEquals(SignalStatus.Live(120), status)
    }

    @Test
    fun `reading older than the threshold is lost`() {
        val status = SignalFreshness.classify(
            lastKnownBpm = 120,
            signalAgeMs = fresh + 1,
            lostMs = 8_000,
            freshThresholdMs = fresh,
        )
        assertEquals(SignalStatus.Lost(8_000), status)
    }

    @Test
    fun `fresh age but no value is lost`() {
        val status = SignalFreshness.classify(
            lastKnownBpm = null,
            signalAgeMs = 1_000,
            lostMs = 5_000,
            freshThresholdMs = fresh,
        )
        assertEquals(SignalStatus.Lost(5_000), status)
    }

    @Test
    fun `never having received a reading is lost`() {
        val status = SignalFreshness.classify(
            lastKnownBpm = null,
            signalAgeMs = null,
            lostMs = 30_000,
            freshThresholdMs = fresh,
        )
        assertEquals(SignalStatus.Lost(30_000), status)
    }

    @Test
    fun `lost result carries the lost duration`() {
        val status = SignalFreshness.classify(
            lastKnownBpm = 100,
            signalAgeMs = 99_999,
            lostMs = 42_000,
            freshThresholdMs = fresh,
        )
        assertEquals(SignalStatus.Lost(42_000), status)
    }

    @Test
    fun `does not give up before the window`() {
        assertFalse(SignalFreshness.shouldGiveUp(lostMs = giveUp - 1, giveUpAfterMs = giveUp))
    }

    @Test
    fun `gives up exactly at the window`() {
        assertTrue(SignalFreshness.shouldGiveUp(lostMs = giveUp, giveUpAfterMs = giveUp))
    }

    @Test
    fun `gives up past the window`() {
        assertTrue(SignalFreshness.shouldGiveUp(lostMs = giveUp + 5_000, giveUpAfterMs = giveUp))
    }

    private val absent = 30_000L   // SIGNAL_ABSENT_MS

    private fun health(ageMs: Long?) =
        SignalFreshness.health(ageMs, freshThresholdMs = fresh, absentThresholdMs = absent)

    @Test
    fun `recent reading is live`() {
        assertEquals(SignalHealth.LIVE, health(3_000))
    }

    @Test
    fun `age at the fresh boundary is live`() {
        assertEquals(SignalHealth.LIVE, health(fresh))
    }

    @Test
    fun `just past fresh is delayed`() {
        assertEquals(SignalHealth.DELAYED, health(fresh + 1))
    }

    @Test
    fun `age at the absent boundary is still only delayed`() {
        assertEquals(SignalHealth.DELAYED, health(absent))
    }

    @Test
    fun `past the absent threshold is absent`() {
        assertEquals(SignalHealth.ABSENT, health(absent + 1))
    }

    @Test
    fun `no reading is absent`() {
        assertEquals(SignalHealth.ABSENT, health(null))
    }
}
