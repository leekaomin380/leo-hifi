package com.leoaudio.hifi.singlevolume;

/** Pure Java transaction rules shared by the Android adapter and host tests.
 * 0.6 (candidate C): the DAC is again a user control, but only through the
 * schema-5 HAL, on its own 0..51 scale (1 dB per unit, capped at -9 dB). */
public final class LeoHifiRequestGate {
    public static final int MODE = 1, VOLUME = 2, HARDVOL = 3;
    public final int kind, value;
    public final long session, generation, startedAt;
    public LeoHifiRequestGate(int kind, int value, LeoHifiState before, long now) {
        this.kind = kind; this.value = value;
        session = before.session; generation = before.generation; startedAt = now;
    }
    private static boolean valid(int kind, int value) {
        switch (kind) {
            case MODE: return value == 0 || value == 1;
            case VOLUME: return value >= 0 && value <= LeoHifiState.LEVEL_MAX;
            case HARDVOL: return value == 1; // disarming bypasses the gate on purpose
            default: return false;
        }
    }
    public static boolean canStart(LeoHifiState s, long now, boolean permitted,
            boolean busy, int kind, int value) {
        if (!valid(kind, value) || busy || !s.available || !s.supported || s.pending || !s.isFresh(now))
            return false;
        // The DAC and guard requests exist only on the schema-5 HAL; volume keys
        // also work behind the keyguard, so they do not need the unlock check.
        if (kind != MODE) return s.requested && s.schema5;
        return permitted;
    }
    public String parameter() {
        if (!valid(kind, value)) throw new IllegalArgumentException("invalid request");
        String id = ";leo_hifi_session=" + session;
        switch (kind) {
            case MODE: return "leo_hifi_mode=" + (value == 1 ? "true" : "false") + id + ";leo_hifi_gen=" + generation;
            case VOLUME: return "leo_hifi_volume=" + value + id + ";leo_hifi_gen=" + generation;
            default: return "leo_hifi_hardvol=1" + id;
        }
    }
    /** Disarm needs no identity: it can only make the standard path audible again. */
    public static String disarmParameter() { return "leo_hifi_hardvol=0"; }
    public boolean accepts(int result, LeoHifiState after, long now) {
        if (!valid(kind, value) || result != 0 || now < startedAt || now - startedAt >= LeoHifiState.MAX_AGE_MS
                || !after.available || !after.supported || !after.isFresh(now)
                || after.session != session || after.generation < generation
                || "hal_error".equals(after.reason)) return false;
        switch (kind) {
            case VOLUME:
                return after.volumeUser == value && (!after.active
                        || (after.volumeLeft == LeoHifiState.levelToCtl(value)
                            && after.volumeRight == after.volumeLeft));
            case HARDVOL:
                return after.hardvol;
            default:
                return after.requested == (value == 1)
                    && (value == 1 || (!after.active && ("idle".equals(after.effective)
                        || "speaker".equals(after.effective) || "wired_standard".equals(after.effective))));
        }
    }
}
