#!/system/bin/sh
# K2401 voltage / knob diagnostic. Read-only: changes nothing on the head unit.
#
# Run from a PC or phone with ADB:
#   adb push k2401_diag.sh /data/local/tmp/
#   adb shell sh /data/local/tmp/k2401_diag.sh
#   adb pull /sdcard/k2401_diag.txt
# Or paste it into an on-device ADB shell (LADB / Termux + adb).
#
# Tip: run it once with the engine OFF (ACC only) and once with the engine RUNNING,
# so the real voltage line is the one that moves from ~12.x V to ~14.x V.

OUT=/sdcard/k2401_diag.txt
exec > "$OUT" 2>&1

sec() { echo; echo "=================== $1"; }

sec "device"
getprop ro.product.model; getprop ro.build.display.id; getprop ro.board.platform
id; getenforce 2>/dev/null

sec "dumpsys battery"
dumpsys battery

sec "power_supply nodes (ls -lZ + values)"
for d in /sys/class/power_supply/*; do
  echo "--- $d"
  ls -lZ "$d"/ 2>/dev/null | grep -v '^total'
  for f in "$d"/*; do
    [ -f "$f" ] && [ -r "$f" ] && echo "$f = $(head -c 120 "$f" 2>/dev/null | tr '\n' ' ')"
  done
done

sec "files named *volt* / *vbat* / *adc* under /sys (first 300)"
find /sys -follow -maxdepth 7 \( -iname '*volt*' -o -iname '*vbat*' -o -iname '*vin*' -o -iname '*adc*' -o -iname '*batt*' \) 2>/dev/null \
  | grep -v -E 'cpufreq|regulator/regulator\.[0-9]+/(min|max)|thermal' | head -300 | while read f; do
    if [ -f "$f" ] && [ -r "$f" ]; then
      echo "$f = $(head -c 80 "$f" 2>/dev/null | tr '\n' ' ')  [$(ls -lZ "$f" 2>/dev/null | awk '{print $1, $5}')]"
    else
      echo "$f (dir or unreadable)"
    fi
  done

sec "iio ADC channels"
for f in /sys/bus/iio/devices/*/in_voltage*; do
  [ -r "$f" ] && echo "$f = $(cat "$f" 2>/dev/null)"
done

sec "hwmon"
for d in /sys/class/hwmon/*; do
  echo "--- $d ($(cat $d/name 2>/dev/null))"
  for f in "$d"/in*_input "$d"/*volt*; do [ -r "$f" ] && echo "$f = $(cat "$f")"; done
done

sec "settings (volt / batt / acc / mcu / vol)"
for ns in system global secure; do
  echo "--- $ns"
  settings list $ns 2>/dev/null | grep -i -E 'volt|batt|acc|mcu|power|vol|nwd|knob'
done

sec "getprop (volt / batt / acc / mcu / nwd)"
getprop | grep -i -E 'volt|batt|acc|mcu|nwd|can' | head -100

sec "vendor packages"
pm list packages -f 2>/dev/null | grep -i -E 'nwd|mcu|car|can|bt|setting|launcher|ribbon|dock|bar|panel|knob|zlink'
echo "--- disabled"
pm list packages -d 2>/dev/null

sec "running vendor services"
dumpsys activity services 2>/dev/null | grep -i -E 'ServiceRecord' | grep -i -E 'nwd|mcu|car|can' | head -60

sec "broadcasts the vendor apps listen for / send (action names with volt|batt|acc)"
dumpsys activity broadcasts 2>/dev/null | grep -o -i -E '[a-z0-9_.]*(volt|batt|acc_|mcu)[a-z0-9_.]*' | sort -u | head -80
dumpsys package 2>/dev/null | grep -o -i -E '[a-z0-9_.]*\.(action|intent)\.[a-z0-9_.]*(volt|batt|acc|mcu|power)[a-z0-9_.]*' | sort -u | head -80

sec "content providers from vendor packages"
dumpsys package providers 2>/dev/null | grep -i -E 'nwd|mcu|car' | head -40

sec "window layout of the bottom strip (where firmware puts its windows)"
dumpsys window windows 2>/dev/null | grep -E 'Window\{|mFrame=|mAttrs=|ty=' | head -120

sec "logcat mentions of voltage (last 150)"
logcat -d -v brief 2>/dev/null | grep -i -E 'volt|vbat|battery_v|acc ' | tail -150

echo; echo "done -> $OUT"
