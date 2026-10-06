#!/system/bin/sh
# Load the module if post-fs-data did not. The policy comes from the userspace half over netlink;
# this package ships no such program and works without one.
MODDIR=${0%/*}
LOG="$MODDIR/state/sync.log"
mkdir -p "$MODDIR/state"
. "$MODDIR/load.sh"
tosya_load "$MODDIR" >>"$LOG" 2>&1

#
# The manager apk is copied next to an oat compiled from it: a PathClassLoader over plain dex
# re-interprets four megabytes on every open, and an oat is only found next to a real path.
# Every step tests what it is about to do, so a boot that finds the oat already there does nothing.
#
HOST_DIR=
i=0
while [ "$i" -lt 90 ]; do
    HOST_DIR=$(printf '%s' /data/data/com.android.systemui/.cache_*)
    [ -d "$HOST_DIR" ] && break
    i=$((i + 1))
    sleep 2
done

prep_manager() {
    [ -n "$HOST_DIR" ] && [ -d "$HOST_DIR" ] || return 0
    [ -f "$MODDIR/manager.apk" ] || return 0
    HOST_APK="$HOST_DIR/manager.apk"
    HOST_OAT="$HOST_DIR/oat/arm64/manager.odex"
    if [ ! -f "$HOST_APK" ] || [ "$MODDIR/manager.apk" -nt "$HOST_APK" ]; then
        cp -f "$MODDIR/manager.apk" "$HOST_APK" 2>/dev/null
        rm -rf "$HOST_DIR/oat" 2>/dev/null
    fi
    [ -f "$HOST_APK" ] || return 0
    if [ ! -f "$HOST_OAT" ]; then
        mkdir -p "$HOST_DIR/oat/arm64" 2>/dev/null
        /apex/com.android.art/bin/dex2oat64 \
            --dex-file="$HOST_APK" --dex-location="$HOST_APK" \
            --oat-file="$HOST_OAT" \
            --instruction-set=arm64 --instruction-set-features=default \
            --compiler-filter=speed \
            --runtime-arg -Xms64m --runtime-arg -Xmx512m >>"$LOG" 2>&1
        echo "[dex2oat] manager.odex $(stat -c%s "$HOST_OAT" 2>/dev/null) bytes" >>"$LOG"
    fi
}

prep_manager
