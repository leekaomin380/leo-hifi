package com.leoaudio.hifi.singlevolume;
import java.nio.file.*;
public class RateTest {
 public static void main(String[] a) throws Exception {
  String wire = Files.readAllLines(Paths.get(a[0])).stream().filter(x -> x.startsWith("active\t")).findFirst().get().split("\t",2)[1];
  int n=0;
  LeoHifiState s48=LeoHifiState.parse(wire,1000); if(!(s48.active && s48.rateLabel().equals("48 kHz"))) throw new AssertionError("48"); n++;
  LeoHifiState s44=LeoHifiState.parse(wire.replace("backend:S24_LE/KHZ_48","backend:S24_LE/KHZ_44P1"),1000); if(!(s44.available && s44.active && s44.rateLabel().equals("44.1 kHz"))) throw new AssertionError("44"); n++;
  LeoHifiState s96=LeoHifiState.parse(wire.replace("backend:S24_LE/KHZ_48","backend:S24_LE/KHZ_96"),1000); if(s96.available) throw new AssertionError("96 accepted"); n++;
  LeoHifiState s16=LeoHifiState.parse(wire.replace("backend:S24_LE/KHZ_48","backend:S16_LE/KHZ_44P1"),1000); if(s16.available) throw new AssertionError("S16 accepted"); n++;
  LeoHifiState su=LeoHifiState.parse(wire.replace("backend:S24_LE/KHZ_48","backend:unconfirmed"),1000); if(!su.available || su.active) throw new AssertionError("unconfirmed"); n++;
  System.out.println(n+" rate checks passed");
 }
}
