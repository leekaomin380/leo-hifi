package com.leoaudio.hifi.singlevolume;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

public final class ProtocolTest {
    static int checks;
    static void check(boolean value, String why) {
        checks++;
        if (!value) throw new AssertionError(why);
    }
    static void rejected(String wire, String why) {
        LeoHifiState s = LeoHifiState.parse(wire, 1000);
        check(!s.available && !s.active, why);
    }
    public static void main(String[] args) throws Exception {
        List<String> lines = Files.readAllLines(Paths.get(args[0]));
        String active = null;
        for (String line : lines) {
            String[] parts = line.split("\t", 2);
            LeoHifiState s = LeoHifiState.parse(parts[1], 1000);
            check(s.available, "actual C serialization: " + parts[0] + " " + s.reason);
            boolean working = parts[0].equals("active") || parts[0].equals("max_identity");
            check(s.active == working, "C state truth: " + parts[0]);
            if (parts[0].equals("restoring")) check(s.reason.equals("hal_error"), "recovery failure visible");
            if (parts[0].equals("active")) active = parts[1];
        }
        check(active != null, "active fixture supplied by C");
        rejected(active.substring("leo_hifi_status=".length()), "missing outer key");
        rejected("other=" + active, "nested outer key");
        rejected(active + ";other=1", "additional Android parameter");
        rejected(active + ";" + active, "duplicate outer key");
        rejected(active.replace("schema:4", "schema:3"), "legacy schema not guessed");
        rejected(active.replace("schema:4", "schema=3"), "legacy delimiter not flattened");
        rejected(active + ",extra:1", "extension requires revision");
        rejected(active + ",schema:4", "duplicate inner key");
        rejected(active.replace("session:", "session:9223372036854775807"), "identity overflow");
        rejected(active.replace("gen:", "gen:-"), "negative generation");
        rejected(active.replace("vol_ctl_l:205", "vol_ctl_l:256"), "out of hardware range");
        rejected(active.replace("vol_db:-25.0", "vol_db:-21.0"), "contradictory dB");
        rejected(active.replace("restore_pending:0", "restore_pending:2"), "invalid boolean");
        for (char c : new char[]{' ', '\n', '\r', '\t', '\0', ';', '=', ':', '中'})
            rejected(active + c, "invalid character " + (int)c);
        String payload = active.substring("leo_hifi_status=".length());
        for (String pair : payload.split(",")) {
            String shortened = ("," + payload + ",").replace("," + pair + ",", ",");
            rejected("leo_hifi_status=" + shortened.substring(1, shortened.length()-1),
                    "missing mandatory " + pair.split(":")[0]);
        }
        LeoHifiState s = LeoHifiState.parse(active, 1000);
        check(s.isFresh(4000) && !s.isFresh(4001) && !s.isFresh(999), "freshness boundaries");
        LeoHifiState off = LeoHifiState.parse(active.replace("requested:hifi", "requested:standard"),1000);
        check(!off.active, "active evidence cannot override OFF intent");
        LeoHifiState failed = LeoHifiState.parse(active.replace("restore_pending:0", "restore_pending:1"),1000);
        check(!failed.active, "recovery failure darkens icon");
        LeoHifiRequestGate request = new LeoHifiRequestGate(LeoHifiRequestGate.MODE, 0, s, 1000);
        check(!request.accepts(0, failed, 1001), "failed recovery not acknowledged");
        System.out.println("PROTOCOL: " + checks + " assertions passed (actual C output -> Java)");
    }
}
