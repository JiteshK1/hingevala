#!/bin/bash
# Sends N real likes (with comments) from the app on the emulator, then stops. Needs the debug build installed (it accepts these test commands) and Hinge on
# Discover.  Usage:  bash android/tools/test-likes.sh [count]     (default 10)
N=${1:-10}
ADB="$HOME/Library/Android/sdk/platform-tools/adb"
DEV="${SERIAL:-emulator-5554}"
a() { "$ADB" -s "$DEV" "$@"; }
cmd() { a shell am start -n com.hingevala/.MainActivity --es cmd "$@" >/dev/null 2>&1; sleep 1; }

prefs=$(a shell run-as com.hingevala cat shared_prefs/hingevala.xml 2>/dev/null)
today=$(a shell date +%Y-%m-%d | tr -d '\r')
sent=0
if echo "$prefs" | grep -q "name=\"sent_date\">$today<"; then
  sent=$(echo "$prefs" | grep -o 'name="sent" value="[0-9]*"' | grep -o '[0-9]*' | tail -1)
fi
cap=$((sent + N))
echo "Sent today so far: $sent. Sending $N more (stops itself at $cap)."

cleanup() {
  cmd stop
  sleep 3
  cmd set --es key daily_cap --es value 0
  echo "Stopped; daily cap reset to 0 (no limit)."
}
trap cleanup EXIT

start_line=$(a shell run-as com.hingevala cat files/bot.log 2>/dev/null | wc -l | tr -d ' ')
cmd set --es key daily_cap --es value "$cap"
cmd dry_off
cmd start

deadline=$((SECONDS + 1800))
shown=0
while [ $SECONDS -lt $deadline ]; do
  sleep 5
  new=$(a shell run-as com.hingevala cat files/bot.log 2>/dev/null | tail -n +"$((start_line + 1))")
  lines=$(echo "$new" | grep -c . || true)
  if [ "$lines" -gt "$shown" ]; then
    echo "$new" | tail -n +"$((shown + 1))" | grep -v -E "^\s+at |^java\." | cut -c12-
    shown=$lines
  fi
  echo "$new" | grep -q -E "Daily cap reached|  Stopped\.$" && break
done
echo "Likes sent in this test: $(echo "$new" | grep -c 'Sent like')"
