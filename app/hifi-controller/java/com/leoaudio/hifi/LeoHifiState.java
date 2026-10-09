package com.leoaudio.hifi.singlevolume;

import java.util.HashMap;
import java.util.Map;

public final class LeoHifiState {
    public static final long MAX_AGE_MS = 3000;
    /** Candidate C DAC scale: ctl = 135 + 2 * level, dB = -60 + level, level 0..51. */
    public static final int CTL_FLOOR_V5 = 135, LEVEL_MAX = 51;
    public static int levelToCtl(int level) { return CTL_FLOOR_V5 + 2 * level; }

    public final boolean available;
    public final boolean supported;
    public final boolean requested;
    public final boolean active;
    public final boolean pending;
    public final int volumeUser;
    public final int volumeLeft;
    public final int volumeRight;
    public final long session;
    public final long generation;
    public final long observedAtElapsedMs;
    public final String effective;
    public final String reason;
    public final String backend;
    public final boolean hardvol; /* schema 5: HAL guard armed */
    public final boolean schema5;

    private LeoHifiState(boolean available, boolean supported, boolean requested,
                         boolean active, boolean pending, int volumeUser,
                         int volumeLeft, int volumeRight, long session,
                         long generation, long observedAtElapsedMs,
                         String effective, String reason, String backend, boolean hardvol, boolean schema5) {
        this.available = available;
        this.supported = supported;
        this.requested = requested;
        this.active = active;
        this.pending = pending;
        this.volumeUser = volumeUser;
        this.volumeLeft = volumeLeft;
        this.volumeRight = volumeRight;
        this.session = session;
        this.generation = generation;
        this.observedAtElapsedMs = observedAtElapsedMs;
        this.effective = effective != null ? effective : "idle";
        this.reason = reason != null ? reason : "";
        this.backend = backend != null ? backend : "unconfirmed";
        this.hardvol = hardvol;
        this.schema5 = schema5;
    }

    public static LeoHifiState unavailable(String reason, long now) {
        return new LeoHifiState(false, false, false, false, false, 0, 0, 0, -1, -1, now, "idle", reason, "unconfirmed", false, false);
    }

    public static LeoHifiState parse(String wire, long now) {
        if (wire == null || wire.length() == 0 || wire.length() > 2048) {
            return unavailable("empty_or_oversize", now);
        }

        // Exactly one Android AudioParameter key. The inner schema never uses
        // ';' or '='; reject legacy flattening instead of guessing provenance.
        if (!wire.startsWith("leo_hifi_status=") || wire.indexOf(';') >= 0
                || wire.indexOf('=', "leo_hifi_status=".length()) >= 0)
            return unavailable("invalid_envelope", now);
        String payload = wire.substring("leo_hifi_status=".length());

        if (payload.trim().isEmpty()) {
            return unavailable("empty_payload", now);
        }

        Map<String, String> map = new HashMap<>();
        String[] pairs = payload.split(",", -1);
        for (String pair : pairs) {
            if (pair.isEmpty()) {
                return unavailable("empty_pair", now);
            }
            int eq = pair.indexOf(':');
            if (eq <= 0 || eq == pair.length() - 1 || pair.indexOf(':', eq + 1) >= 0) {
                return unavailable("malformed_pair", now);
            }
            String k = pair.substring(0, eq);
            String v = pair.substring(eq + 1);
            if (!k.matches("[a-z][a-z0-9_]*") || !v.matches("[A-Za-z0-9_./-]+")) return unavailable("invalid_token", now);
            if (map.containsKey(k)) {
                return unavailable("duplicate_key", now);
            }
            map.put(k, v);
        }

        try {
            // Schema 4 = candidate A/B HAL; schema 5 adds the hard-volume guard.
            String schema = map.get("schema");
            if (!"4".equals(schema) && !"5".equals(schema)) return unavailable("invalid_schema", now);
            boolean v5 = "5".equals(schema);
            String[] required = v5 ? new String[]{"schema", "session", "gen", "supported", "requested",
                    "effective", "live", "flow", "vol_ctl_l", "vol_ctl_r", "vol_db",
                    "vol_user", "backend", "fail", "permanent_fail", "probes", "ev",
                    "bypass", "vol_applied", "restore_pending", "acdb", "hardvol"}
                    : new String[]{"schema", "session", "gen", "supported", "requested",
                    "effective", "live", "flow", "vol_ctl_l", "vol_ctl_r", "vol_db",
                    "vol_user", "backend", "fail", "permanent_fail", "probes", "ev",
                    "bypass", "vol_applied", "restore_pending", "acdb"};
            for (String key : required)
                if (!map.containsKey(key)) return unavailable("missing_" + key, now);
            // Fixed schema: an extension requires a deliberate protocol revision.
            if (map.size() != required.length) return unavailable("unknown_key", now);
            if (v5 && !"0".equals(map.get("hardvol")) && !"1".equals(map.get("hardvol")))
                return unavailable("invalid_hardvol", now);
            boolean hardvol = v5 && "1".equals(map.get("hardvol"));
            for (String key : new String[]{"permanent_fail", "vol_applied", "restore_pending"})
                if (!"0".equals(map.get(key)) && !"1".equals(map.get(key)))
                    return unavailable("invalid_" + key, now);
            if (!map.get("probes").matches("[0-3]")
                    || !map.get("ev").matches("0x[0-9a-f]{1,8}")
                    || !map.get("bypass").matches("0x[0-9a-f]{1,8}")
                    || !"absent_expected".equals(map.get("acdb")))
                return unavailable("invalid_diagnostics", now);

            String sessionStr = map.get("session");
            if (sessionStr == null) return unavailable("missing_session", now);
            if (!sessionStr.matches("[0-9]+")) return unavailable("invalid_session", now);
            long session = Long.parseLong(sessionStr);
            if (session <= 0) return unavailable("invalid_session", now);

            String genStr = map.get("gen");
            if (genStr == null) return unavailable("missing_gen", now);
            if (!genStr.matches("[0-9]+")) return unavailable("invalid_gen", now);
            long gen = Long.parseLong(genStr);
            if (gen < 0) return unavailable("invalid_gen", now);

            String suppStr = map.get("supported");
            if (!"0".equals(suppStr) && !"1".equals(suppStr)) return unavailable("invalid_supported", now);
            boolean supported = "1".equals(suppStr);

            String reqStr = map.get("requested");
            if (!"hifi".equals(reqStr) && !"standard".equals(reqStr)) return unavailable("invalid_requested", now);
            boolean requested = "hifi".equals(reqStr);

            String effStr = map.get("effective");
            if (!"idle".equals(effStr) && !"speaker".equals(effStr) &&
                !"wired_standard".equals(effStr) && !"arming".equals(effStr) &&
                !"hifi_active".equals(effStr) && !"hifi_degraded".equals(effStr) &&
                !"error_fallback".equals(effStr)) {
                return unavailable("invalid_effective", now);
            }

            String liveStr = map.get("live");
            if (!"0".equals(liveStr) && !"1".equals(liveStr)) return unavailable("invalid_live", now);
            boolean live = "1".equals(liveStr);

            String flowStr = map.get("flow");
            if (!"0".equals(flowStr) && !"1".equals(flowStr)) return unavailable("invalid_flow", now);
            boolean flow = "1".equals(flowStr);

            String vlStr = map.get("vol_ctl_l");
            String vrStr = map.get("vol_ctl_r");
            if (vlStr == null || vrStr == null) return unavailable("missing_vol_ctl", now);
            int volL = Integer.parseInt(vlStr);
            int volR = Integer.parseInt(vrStr);
            if (volL < -1 || volL > 255 || volR < -1 || volR > 255)
                return unavailable("invalid_vol_ctl", now);
            String db = volL < 0 ? "0.0" : String.format(java.util.Locale.ROOT,
                    "%.1f", -127.5 + 0.5 * volL);
            if (!db.equals(map.get("vol_db"))) return unavailable("invalid_vol_db", now);

            String vuStr = map.get("vol_user");
            if (vuStr == null) return unavailable("missing_vol_user", now);
            int volUser = Integer.parseInt(vuStr);
            if (volUser < 0 || volUser > 60) return unavailable("invalid_vol_user", now);

            String backendStr = map.get("backend");
            if (!verifiedBackend(backendStr) && !"unconfirmed".equals(backendStr)) {
                return unavailable("invalid_backend", now);
            }

            String failStr = map.get("fail");
            if (failStr == null) return unavailable("missing_fail", now);
            long fail = Long.parseLong(failStr);
            if (fail < 0) return unavailable("invalid_fail", now);

            boolean healthy = fail == 0 && "0".equals(map.get("restore_pending"))
                    && "0".equals(map.get("permanent_fail"));
            boolean active = supported && requested && healthy &&
                    "hifi_active".equals(effStr) &&
                    live &&
                    flow &&
                    (volL == volR) &&
                    (volL >= (v5 ? CTL_FLOOR_V5 : 205) && volL <= 237) &&
                    verifiedBackend(backendStr) &&
                    (fail == 0);

            return new LeoHifiState(true, supported, requested, active, false,
                    volUser, volL, volR, session, gen, now, effStr, healthy ? "parsed" : "hal_error", backendStr, hardvol, v5);
        } catch (NumberFormatException e) {
            return unavailable("number_format_error", now);
        }
    }

    public boolean isFresh(long now) {
        long age = now - observedAtElapsedMs;
        return age >= 0 && age <= MAX_AGE_MS;
    }

    public LeoHifiState withPending(boolean value, String reason) {
        return new LeoHifiState(available, supported, requested, active, value,
                volumeUser, volumeLeft, volumeRight, session, generation,
                observedAtElapsedMs, effective, reason, backend, hardvol, schema5);
    }

    public LeoHifiState withReason(String newReason) {
        return new LeoHifiState(available, supported, requested, active, pending,
                volumeUser, volumeLeft, volumeRight, session, generation,
                observedAtElapsedMs, effective, newReason, backend, hardvol, schema5);
    }

    /** 0.5: candidate-B HAL may verify a 44.1 kHz backend; nothing else is accepted. */
    static boolean verifiedBackend(String backend) {
        return "S24_LE/KHZ_48".equals(backend) || "S24_LE/KHZ_44P1".equals(backend);
    }

    public String rateLabel() {
        return "S24_LE/KHZ_44P1".equals(backend) ? "44.1 kHz" : "48 kHz";
    }
}
