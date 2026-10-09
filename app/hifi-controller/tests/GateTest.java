package com.leoaudio.hifi.singlevolume;
import java.nio.file.*;
public class GateTest {
 static int n;
 static void check(boolean ok, String what) { n++; if (!ok) throw new AssertionError(n + " " + what); }
 public static void main(String[] a) throws Exception {
  String w4 = Files.readAllLines(Paths.get(a[0])).stream().filter(x -> x.startsWith("active\t")).findFirst().get().split("\t",2)[1];
  // HAL candidate C format = schema 5 + trailing hardvol field (same printf otherwise).
  String w5 = w4.replace("schema:4,", "schema:5,") + ",hardvol:0";
  String w5h = w4.replace("schema:4,", "schema:5,") + ",hardvol:1";
  LeoHifiState s4 = LeoHifiState.parse(w4, 1000), s5 = LeoHifiState.parse(w5, 1000), s5h = LeoHifiState.parse(w5h, 1000);
  check(s4.available && s4.active && !s4.schema5 && !s4.hardvol, "schema4 still parsed");
  check(s5.available && s5.active && s5.schema5 && !s5.hardvol, "schema5 parsed");
  check(s5h.hardvol, "hardvol flag");
  check(!LeoHifiState.parse(w4 + ",hardvol:0", 1000).available, "schema4 rejects extra key");
  check(!LeoHifiState.parse(w4.replace("schema:4,", "schema:5,"), 1000).available, "schema5 requires hardvol");
  check(!LeoHifiState.parse(w5.replace("hardvol:0", "hardvol:2"), 1000).available, "hardvol must be 0/1");
  check(!LeoHifiState.parse(w4.replace("schema:4,", "schema:6,"), 1000).available, "unknown schema rejected");
  // DAC window: schema5 accepts the quiet range, schema4 keeps 205..237.
  String q5 = w5.replace("vol_ctl_l:205,vol_ctl_r:205,vol_db:-25.0", "vol_ctl_l:155,vol_ctl_r:155,vol_db:-50.0");
  String q4 = w4.replace("vol_ctl_l:205,vol_ctl_r:205,vol_db:-25.0", "vol_ctl_l:155,vol_ctl_r:155,vol_db:-50.0");
  check(LeoHifiState.parse(q5, 1000).active, "schema5 -50 dB active");
  check(!LeoHifiState.parse(q4, 1000).active, "schema4 -50 dB not active");
  // Gate: DAC and guard requests only on schema 5, range 0..51.
  for (int k : new int[]{LeoHifiRequestGate.VOLUME, LeoHifiRequestGate.HARDVOL})
   check(!LeoHifiRequestGate.canStart(s4, 1001, true, false, k, 1), "no DAC/guard on schema4");
  check(LeoHifiRequestGate.canStart(s5, 1001, false, false, LeoHifiRequestGate.VOLUME, 0), "level 0 allowed, even locked");
  check(LeoHifiRequestGate.canStart(s5, 1001, false, false, LeoHifiRequestGate.VOLUME, 51), "level 51 allowed");
  check(!LeoHifiRequestGate.canStart(s5, 1001, true, false, LeoHifiRequestGate.VOLUME, 52), "level 52 refused");
  check(!LeoHifiRequestGate.canStart(s5, 1001, true, false, LeoHifiRequestGate.VOLUME, -1), "negative refused");
  check(!LeoHifiRequestGate.canStart(s5, 4001, true, false, LeoHifiRequestGate.VOLUME, 10), "stale refused");
  check(!LeoHifiRequestGate.canStart(s5, 1001, true, true, LeoHifiRequestGate.VOLUME, 10), "busy refused");
  check(!LeoHifiRequestGate.canStart(s5, 1001, false, false, LeoHifiRequestGate.MODE, 1), "mode still needs unlock");
  check(!LeoHifiRequestGate.canStart(s5, 1001, true, false, LeoHifiRequestGate.HARDVOL, 0), "disarm never via gate");
  LeoHifiRequestGate v = new LeoHifiRequestGate(LeoHifiRequestGate.VOLUME, 35, s5, 1001);
  check(v.parameter().startsWith("leo_hifi_volume=35;leo_hifi_session=") && v.parameter().contains(";leo_hifi_gen="), "volume parameter");
  check(v.accepts(0, s5.withReason("parsed"), 1002) == (s5.volumeUser == 35), "volume accept follows readback");
  LeoHifiRequestGate g = new LeoHifiRequestGate(LeoHifiRequestGate.HARDVOL, 1, s5, 1001);
  check(g.parameter().startsWith("leo_hifi_hardvol=1;leo_hifi_session=") && !g.parameter().contains("gen"), "arm parameter");
  check(!g.accepts(0, s5, 1002) && g.accepts(0, s5h, 1002), "arm accepted only when HAL reports it");
  check(LeoHifiRequestGate.disarmParameter().equals("leo_hifi_hardvol=0"), "disarm parameter");
  try { new LeoHifiRequestGate(LeoHifiRequestGate.VOLUME, 60, s5, 1001).parameter(); check(false, "60 must throw"); }
  catch (IllegalArgumentException e) { n++; }
  check(LeoHifiState.levelToCtl(0) == 135 && LeoHifiState.levelToCtl(35) == 205 && LeoHifiState.levelToCtl(51) == 237, "scale");
  System.out.println(n + " candidate-C gate checks passed");
 }
}
