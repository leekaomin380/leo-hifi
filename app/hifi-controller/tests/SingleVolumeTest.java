package com.leoaudio.hifi.singlevolume;
import java.nio.file.*;
public class SingleVolumeTest {
 static int checks;
 static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("check " + checks); }
 public static void main(String[] args) throws Exception {
 String wire = Files.readAllLines(Paths.get(args[0])).stream().filter(x -> x.startsWith("active\t")).findFirst().get().split("\t",2)[1];
 LeoHifiState s=LeoHifiState.parse(wire,1000); check(s.available && s.active);
 for(int value: new int[]{0,1,30,60}) {
 check(!LeoHifiRequestGate.canStart(s,1001,true,false,2,value));
 try {new LeoHifiRequestGate(2,value,s,1001).parameter(); throw new AssertionError("DAC write permitted");}
 catch(IllegalArgumentException expected) { checks++; }
 check(!new LeoHifiRequestGate(2,value,s,1001).accepts(0,s,1002));
 }
 check(LeoHifiRequestGate.canStart(s,1001,true,false,1,1));
 check(LeoHifiRequestGate.canStart(s,1001,true,false,1,0));
 check(!LeoHifiRequestGate.canStart(s,4001,true,false,1,1));
 check(!LeoHifiRequestGate.canStart(s,1001,false,false,1,1));
 check(!LeoHifiRequestGate.canStart(s,1001,true,true,1,1));
 check(!LeoHifiRequestGate.canStart(s,1001,true,false,1,2));
 LeoHifiRequestGate mode=new LeoHifiRequestGate(1,1,s,1001);
 check(mode.parameter().startsWith("leo_hifi_mode=true;"));
 check(!mode.parameter().contains("leo_hifi_volume"));
 check(mode.accepts(0,s,1002));
 check(!mode.accepts(-1,s,1002));
 check(!mode.accepts(0,s,4002));
 System.out.println(checks+" single-volume checks passed");
 }
}
