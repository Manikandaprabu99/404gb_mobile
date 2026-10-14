package com.fourzerofourgb.app.backup;

/** When a background People run may go ahead, how long it takes, and when the next one comes. Pure Java. */
final class FaceScanPolicy {

    static final int MIN_BATTERY_PCT = 30;
    static final long RUN_MS_CHARGING = 20 * 60_000L;
    static final long RUN_MS_CHARGING_AS_JOB = 9 * 60_000L;
    static final long RUN_MS_UNPLUGGED = 8 * 60_000L;
    /** From the app going to the background to the "soon" run. */
    static final long SOON_DELAY_MS = 90_000L;
    static final long FOLLOW_UP_CHARGING_MS = 60_000L;
    static final long FOLLOW_UP_UNPLUGGED_MS = 20 * 60_000L;
    /** A follow-up further out than this is left to the hourly run. */
    static final long MAX_FOLLOW_UP_MS = 60 * 60_000L;

    /** Why a run is skipped (FaceScanStatus.lastReason). */
    static final String SKIP_NOT_CHARGING = "not-charging";
    static final String SKIP_BATTERY_LOW = "battery-low";
    static final String SKIP_POWER_SAVE = "power-save";

    private FaceScanPolicy() {}

    /** Why a run must not go on now, or null; {@code batteryPct} < 0 = unknown (WorkManager's battery-not-low only). */
    static String skipReason(boolean charging, int batteryPct, boolean powerSave, boolean chargingOnly) {
        if (charging) {
            return null;
        }
        if (chargingOnly) {
            return SKIP_NOT_CHARGING;
        }
        if (powerSave) {
            return SKIP_POWER_SAVE;
        }
        if (batteryPct >= 0 && batteryPct < MIN_BATTERY_PCT) {
            return SKIP_BATTERY_LOW;
        }
        return null;
    }

    static boolean shouldRun(boolean charging, int batteryPct, boolean powerSave, boolean chargingOnly) {
        return skipReason(charging, batteryPct, powerSave, chargingOnly) == null;
    }

    /** How long a run may take, from its start. */
    static long runMillis(boolean charging, boolean foregroundService) {
        if (!charging) {
            return RUN_MS_UNPLUGGED;
        }
        return foregroundService ? RUN_MS_CHARGING : RUN_MS_CHARGING_AS_JOB;
    }

    /** Unplugging mid-run cuts the deadline to the unplugged length; plugging in never extends it. */
    static long deadlineAfterPowerChange(long startedAt, long deadlineAt, boolean chargingNow) {
        return chargingNow ? deadlineAt : Math.min(deadlineAt, startedAt + RUN_MS_UNPLUGGED);
    }

    /** A run that hit its deadline with photos left ({@code left} < 0 = not said) gets a follow-up. */
    static boolean shouldFollowUp(String reason, int left) {
        return FaceRunnerHost.REASON_DEADLINE.equals(reason) && left != 0;
    }

    /** Follow-up delay, at least until {@code resumeAt} (downloads allowed again); -1 = leave it to the hourly run. */
    static long followUpDelayMillis(boolean charging, long resumeAt, long now) {
        long delay = charging ? FOLLOW_UP_CHARGING_MS : FOLLOW_UP_UNPLUGGED_MS;
        if (resumeAt > now) {
            delay = Math.max(delay, resumeAt - now);
        }
        return delay > MAX_FOLLOW_UP_MS ? -1L : delay;
    }

    /** Battery level in percent from ACTION_BATTERY_CHANGED's level/scale, or -1. */
    static int batteryPercent(int level, int scale) {
        if (level < 0 || scale <= 0) {
            return -1;
        }
        return (int) Math.max(0L, Math.min(100L, Math.round(level * 100.0 / scale)));
    }
}
