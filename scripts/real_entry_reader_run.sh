#!/system/bin/sh
# One reader launch through the production entry, optionally captured with Perfetto.
#
# The CS-7 gate drives the scene host from a benchmark activity that bypasses the
# `isExperimentalPagedSceneReaderEnabled` gate, so it measures the candidate default path rather
# than what a release build does today. This script launches the real ReaderActivity instead
# (action READ_MANGA + the app's short content URL, i.e. the same contract AppRouter uses) and
# captures the first screen with the benchmark's own Perfetto config.
#
# Usage (as root on device):
#   sh real_entry_reader_run.sh /data/misc/perfetto-traces/real_entry.pb measure <manga_id>
#   sh real_entry_reader_run.sh - warmup <manga_id>
#
# Exit status mirrors the measured launch: non-zero means the activity did not start.

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
PRE_SLEEP="${4:-2}"
POST_SLEEP="${5:-4}"

launch() {
    am start -W -a "$PKG.action.READ_MANGA" -n "$ACT" -d "$URL"
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
sleep "$POST_SLEEP"
kill -INT "$PERFETTO_PID" 2>/dev/null
wait "$PERFETTO_PID" 2>/dev/null
chmod 666 "$OUT" 2>/dev/null
ls -l "$OUT"
exit $STATUS
