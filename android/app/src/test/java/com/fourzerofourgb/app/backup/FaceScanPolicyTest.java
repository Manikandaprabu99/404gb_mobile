package com.fourzerofourgb.app.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Background People matching's run decisions (FaceScanPolicy): when a run may
 * go ahead, how long it may take and when the next one comes. Pure Java, like
 * FaceRunnerAssetsTest.
 */
public class FaceScanPolicyTest {

    @Test
    public void chargingAlwaysRuns() {
        assertTrue(FaceScanPolicy.shouldRun(true, 5, false, false));
        assertTrue(FaceScanPolicy.shouldRun(true, 5, true, true));
        assertTrue(FaceScanPolicy.shouldRun(true, -1, false, true));
        assertNull(FaceScanPolicy.skipReason(true, 100, true, false));
    }

    @Test
    public void unpluggedNeedsThirtyPercentAndNoBatterySaver() {
        assertTrue(FaceScanPolicy.shouldRun(false, 30, false, false));
        assertTrue(FaceScanPolicy.shouldRun(false, 100, false, false));
        assertFalse(FaceScanPolicy.shouldRun(false, 29, false, false));
        assertEquals(FaceScanPolicy.SKIP_BATTERY_LOW, FaceScanPolicy.skipReason(false, 29, false, false));
        assertEquals(FaceScanPolicy.SKIP_BATTERY_LOW, FaceScanPolicy.skipReason(false, 0, false, false));
        assertEquals(FaceScanPolicy.SKIP_POWER_SAVE, FaceScanPolicy.skipReason(false, 90, true, false));
        // Battery Saver is the clearer reason when both apply.
        assertEquals(FaceScanPolicy.SKIP_POWER_SAVE, FaceScanPolicy.skipReason(false, 10, true, false));
    }

    @Test
    public void unknownBatteryLevelLeavesItToWorkManager() {
        assertTrue(FaceScanPolicy.shouldRun(false, -1, false, false));
        assertFalse(FaceScanPolicy.shouldRun(false, -1, true, false));
    }

    @Test
    public void chargingOnlySkipsEveryUnpluggedRun() {
        assertEquals(FaceScanPolicy.SKIP_NOT_CHARGING, FaceScanPolicy.skipReason(false, 100, false, true));
        assertEquals(FaceScanPolicy.SKIP_NOT_CHARGING, FaceScanPolicy.skipReason(false, 10, true, true));
        assertFalse(FaceScanPolicy.shouldRun(false, 100, false, true));
    }

    @Test
    public void runLengths() {
        assertEquals(20 * 60_000L, FaceScanPolicy.runMillis(true, true));
        assertEquals(9 * 60_000L, FaceScanPolicy.runMillis(true, false));
        assertEquals(8 * 60_000L, FaceScanPolicy.runMillis(false, true));
        assertEquals(8 * 60_000L, FaceScanPolicy.runMillis(false, false));
        // A plain job is stopped by Android after 10 minutes: every length stays under that.
        assertTrue(FaceScanPolicy.runMillis(true, false) < 10 * 60_000L);
        assertTrue(FaceScanPolicy.runMillis(false, false) < 10 * 60_000L);
    }

    @Test
    public void unpluggingMidRunShortensTheRun() {
        long start = 1_000_000L;
        long deadline = start + FaceScanPolicy.RUN_MS_CHARGING;
        assertEquals(start + 8 * 60_000L, FaceScanPolicy.deadlineAfterPowerChange(start, deadline, false));
        assertEquals(deadline, FaceScanPolicy.deadlineAfterPowerChange(start, deadline, true));
        // Never longer than it was.
        long shortDeadline = start + 60_000L;
        assertEquals(shortDeadline, FaceScanPolicy.deadlineAfterPowerChange(start, shortDeadline, false));
        long unplugged = start + FaceScanPolicy.RUN_MS_UNPLUGGED;
        assertEquals(unplugged, FaceScanPolicy.deadlineAfterPowerChange(start, unplugged, true));
    }

    @Test
    public void followUpOnlyAfterADeadlineWithPhotosLeft() {
        assertTrue(FaceScanPolicy.shouldFollowUp("deadline", 120));
        // An older page that doesn't say how many are left.
        assertTrue(FaceScanPolicy.shouldFollowUp("deadline", -1));
        assertFalse(FaceScanPolicy.shouldFollowUp("deadline", 0));
        assertFalse(FaceScanPolicy.shouldFollowUp("done", 0));
        assertFalse(FaceScanPolicy.shouldFollowUp("stopped", 50));
        assertFalse(FaceScanPolicy.shouldFollowUp("Your GitHub sign-in ended. Open 404GB to sign in again.", 50));
        assertFalse(FaceScanPolicy.shouldFollowUp(null, 50));
    }

    @Test
    public void followUpDelays() {
        long now = 5_000_000_000L;
        assertEquals(60_000L, FaceScanPolicy.followUpDelayMillis(true, 0L, now));
        assertEquals(20 * 60_000L, FaceScanPolicy.followUpDelayMillis(false, 0L, now));
        // A resume time in the past changes nothing.
        assertEquals(60_000L, FaceScanPolicy.followUpDelayMillis(true, now - 1000L, now));
        // Waits for the download budget when that is later.
        assertEquals(25 * 60_000L, FaceScanPolicy.followUpDelayMillis(true, now + 25 * 60_000L, now));
        assertEquals(20 * 60_000L, FaceScanPolicy.followUpDelayMillis(false, now + 5 * 60_000L, now));
        // Too far off: the hourly run will do.
        assertEquals(-1L, FaceScanPolicy.followUpDelayMillis(true, now + 61 * 60_000L, now));
    }

    @Test
    public void batteryPercent() {
        assertEquals(30, FaceScanPolicy.batteryPercent(30, 100));
        assertEquals(50, FaceScanPolicy.batteryPercent(128, 255));
        assertEquals(100, FaceScanPolicy.batteryPercent(100, 100));
        assertEquals(100, FaceScanPolicy.batteryPercent(120, 100));
        assertEquals(-1, FaceScanPolicy.batteryPercent(-1, 100));
        assertEquals(-1, FaceScanPolicy.batteryPercent(40, 0));
    }
}
