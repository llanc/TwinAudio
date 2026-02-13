#!/system/bin/sh
# TwinAudio Magisk Module - Service Script

MODDIR=${0%/*}
LOGFILE="/data/local/tmp/twinaudio_service.log"

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*" | tee -a "$LOGFILE"
}

log "=========================================="
log "TwinAudio Service Script Starting"
log "=========================================="

while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 1
done
log "✓ System boot completed"
sleep 5

AUDIO_PID=$(pidof audioserver)
if [ -z "$AUDIO_PID" ]; then
    sleep 10
    AUDIO_PID=$(pidof audioserver)
fi
if [ -z "$AUDIO_PID" ]; then exit 1; fi

ABI=$(getprop ro.product.cpu.abi)
TWINAUDIO_PATH="$MODDIR/lib/$ABI/libtwinaudio_native.so"

if [ ! -f "$TWINAUDIO_PATH" ]; then
    log "✗ Library missing! Expected $TWINAUDIO_PATH"
    exit 1
fi

chmod 644 "$TWINAUDIO_PATH"
chown root:root "$TWINAUDIO_PATH"
chcon u:object_r:system_lib_file:s0 "$TWINAUDIO_PATH" 2>/dev/null

log "→ Stopping original audioserver (preventing init respawn)..."
setprop ctl.stop audioserver
sleep 2

killall -9 audioserver 2>/dev/null
sleep 1

log "→ Starting custom audioserver with LD_PRELOAD..."
LD_PRELOAD="$TWINAUDIO_PATH" nohup /system/bin/audioserver > /dev/null 2>&1 &
sleep 3

FINAL_PID=$(pidof audioserver)
if [ -n "$FINAL_PID" ]; then
    if grep -q "libtwinaudio_native.so" "/proc/$FINAL_PID/maps" 2>/dev/null; then
        log "✅ TwinAudio injection SUCCESSFUL! PID: $FINAL_PID"
    else
        log "⚠️ WARNING: audioserver restarted but library verification failed."
    fi
else
    log "✗ FAILED: audioserver not running"
fi