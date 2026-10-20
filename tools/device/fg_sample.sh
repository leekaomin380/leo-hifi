#!/system/bin/sh
# E0-a fuel gauge probe: 1 Hz for N seconds. Output CSV in /data/local/tmp/leo-power/
N=${1:-240}; O=/data/local/tmp/leo-power; mkdir -p $O; F=$O/fg-$(date +%H%M%S).csv
B=/sys/class/power_supply
echo "uptime,batt_cur,bms_cur,volt,status,usb_online,backlight,cpu0_freq,online,temp" > $F
i=0
while [ $i -lt $N ]; do
  echo "$(cut -d' ' -f1 /proc/uptime),$(cat $B/battery/current_now),$(cat $B/bms/current_now),$(cat $B/battery/voltage_now),$(cat $B/battery/status),$(cat $B/usb/online),$(cat /sys/class/leds/lcd-backlight/brightness),$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq),$(cat /sys/devices/system/cpu/online),$(cat $B/battery/temp)" >> $F
  i=$((i+1)); sleep 1
done
echo done >> $F
