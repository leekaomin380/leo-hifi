#!/system/bin/sh
# Polls route-safety state every ~0.3 s for $1 seconds (default 12). Run ON the device.
# Unsafe = Qualcomm codec headphone DAC on (standard path) while media index is at max (25).
N=${1:-12}; end=$(( $(date +%s) + N ))
while [ $(date +%s) -lt $end ]; do
  q=$(tinymix 'QUAT_MI2S_RX Audio Mixer MultiMedia1' | awk '{print $NF}')
  h=$(tinymix 'HPHL DAC Switch' | awk '{print $NF}')
  v=$(settings get system volume_music_headphone)
  g=$(getprop persist.vendor.leo.audio.hifi.hardvol)
  p=$(head -1 /proc/asound/card0/pcm0p/sub0/status)
  flag=ok; [ "$h" = On ] && [ "$v" = 25 ] && flag=UNSAFE
  echo "$(cut -d' ' -f1 /proc/uptime) quat=$q hphDAC=$h mediaIdx=$v guard=$g pcm0p=$p $flag"
  sleep 0.3
done
