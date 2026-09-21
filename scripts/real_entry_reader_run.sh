#!/system/bin/sh
# One reader launch through the production entry, optionally captured with Perfetto.
#
# The CS-7 gate drives the scene host from a benchmark activity that bypasses the
# `isExperimentalPagedSceneReaderEnabled` gate, so it measures the candidate default path rather
# than what a release build does today. This script launches the real ReaderActivity instead
# (action READ_MANGA + the app's short content URL, i.e. the same contract AppRouter uses) and
# captures it with the benchmark's own Perfetto config.
#
# Modes:
#   screen   cold launch, capture the first screen only
#   journey  cold launch, wait for the first screen to settle, then inject N full-page turns
#   warmup   discarded launch (no trace), used before a measured run
#
# Usage (as root on device):
#   sh real_entry_reader_run.sh /data/misc/perfetto-traces/real_entry.pb journey <manga_id>
#   sh real_entry_reader_run.sh - warmup <manga_id>
#
# Tunables (environment): PRE_SLEEP, POST_SLEEP, JOURNEY_SETTLE_S, JOURNEY_TURNS, TURN_STYLE
# (tap|swipe), TURN_MS, TURN_SPACING_S, SCREEN_W, SCREEN_H.

PKG=org.skepsun.kototoro
ACT="$PKG/$PKG.reader.ui.ReaderActivity"
# Perfetto only reads configs from its own directory: a config in /data/local/tmp is rejected
# with EACCES ("Permission denied") or, when the rejected read leaves a half-set-up session, with
# "EnableTracing IPC request rejected". /data/misc/perfetto-configs is the location it documents.
CFG=/data/misc/perfetto-configs/real_entry.cfg
MODE="$2"
MANGA_ID="${3:--722638835630210246}"
URL="https://kototoro.app/manga/$MANGA_ID"
OUT="$1"
PRE_SLEEP="${PRE_SLEEP:-2}"
POST_SLEEP="${POST_SLEEP:-4}"
JOURNEY_SETTLE_S="${JOURNEY_SETTLE_S:-5}"
JOURNEY_TURNS="${JOURNEY_TURNS:-4}"
TURN_STYLE="${TURN_STYLE:-swipe}"
TURN_MS="${TURN_MS:-200}"
TURN_SPACING_S="${TURN_SPACING_S:-1.2}"
SCREEN_W="${SCREEN_W:-1280}"
SCREEN_H="${SCREEN_H:-2772}"

launch() {
    am start -W -a "$PKG.action.READ_MANGA" -n "$ACT" -d "$URL"
}

turn_pages() {
    # Default is an injected drag (TURN_STYLE=swipe) because that is what the benchmark journey
    # does; TURN_STYLE=tap drives the same turn through the CENTER_RIGHT tap-grid zone instead.
    # Verified against the recorded reading position: 4 turns leave page=4 (percent 0.625 of 8).
    # Two traps live here, both of which produced a false "the turn did not commit":
    #   - a gesture that lands inside the initial settle window is absorbed (hence JOURNEY_SETTLE_S);
    #   - the position is persisted on close, so a run that is killed rather than closed looks like
    #     no turn happened even when the pages turned (hence the BACK press after the trace stops).
    Y=$((SCREEN_H / 2))
    X=$((SCREEN_W * 78 / 100))
    sleep "$JOURNEY_SETTLE_S"
    i=1
    while [ "$i" -le "$JOURNEY_TURNS" ]; do
        if [ "$TURN_STYLE" = "swipe" ]; then
            input swipe "$X" "$Y" "$((SCREEN_W * 22 / 100))" "$Y" "$TURN_MS"
        else
            input tap "$X" "$Y"
        fi
        sleep "$TURN_SPACING_S"
        i=$((i + 1))
    done
}

if [ "$MODE" = "warmup" ]; then
    # A discarded launch: ATSL warms up before measuring, and the first launch after an install
    # also pays profile installation, which is not part of what is being compared.
    launch
    sleep 4
    am force-stop "$PKG"
    exit 0
fi

rm -f "$OUT"
perfetto --txt -c "$CFG" -o "$OUT" &
PERFETTO_PID=$!
sleep "$PRE_SLEEP"
launch
STATUS=$?
if [ "$MODE" = "journey" ]; then
    turn_pages
fi
sleep "$POST_SLEEP"
kill -INT "$PERFETTO_PID" 2>/dev/null
wait "$PERFETTO_PID" 2>/dev/null
if [ "$MODE" = "journey" ]; then
    # Leave the reader the way a user does, *after* the trace stops: the reading position is
    # persisted on pause/close, so a run that is killed instead of closed looks like "no turn
    # committed" even when the pages did turn.
    input keyevent KEYCODE_BACK
    sleep 2
fi
chmod 666 "$OUT" 2>/dev/null
ls -l "$OUT"
exit $STATUS
