#!/system/bin/sh
# The manager apk and an oat compiled from it, where the host process reads them.
#
# This is install work, not boot work. It runs from customize.d while the installer has the
# package in hand, and from the action button when someone needs it after the fact. No boot stage
# runs it: dex2oat over this apk is CPU the boot would be waiting on, and the lockscreen is what
# waits.
#
# The directory is the host's own: <host data>/.cache_*. The host side makes one for itself when
# it finds none, and it takes an existing one first -- so the one made here is the one both sides
# use from then on. It is left owned by the host's uid, because the host and the service write
# their state into it.

HOST_PACKAGE=com.android.systemui
HOST_DATA="/data/data/$HOST_PACKAGE"

prep_manager() {
  MODDIR=${1:-${MODPATH:-${0%/*}}}
  [ -f "$MODDIR/manager.apk" ] || return 0
  [ -d "$HOST_DATA" ] || return 1

  HOST_DIR=$(printf '%s' "$HOST_DATA"/.cache_*)
  if [ ! -d "$HOST_DIR" ]; then
    HOST_DIR="$HOST_DATA/.cache_$(head -c 6 /dev/urandom | od -An -tx1 | tr -d ' \n')"
    mkdir -p "$HOST_DIR" || return 1
  fi

  HOST_APK="$HOST_DIR/manager.apk"
  HOST_OAT="$HOST_DIR/oat/arm64/manager.odex"

  if [ ! -f "$HOST_APK" ] || [ "$MODDIR/manager.apk" -nt "$HOST_APK" ]; then
    cp -f "$MODDIR/manager.apk" "$HOST_APK" || return 1
    rm -rf "$HOST_DIR/oat"
  fi

  if [ ! -f "$HOST_OAT" ]; then
    mkdir -p "$HOST_DIR/oat/arm64" || return 1
    /apex/com.android.art/bin/dex2oat64 \
      --dex-file="$HOST_APK" --dex-location="$HOST_APK" \
      --oat-file="$HOST_OAT" \
      --instruction-set=arm64 --instruction-set-features=default \
      --compiler-filter=speed \
      --runtime-arg -Xms64m --runtime-arg -Xmx512m || return 1
  fi

  chown -R 1000:1000 "$HOST_DIR" 2>/dev/null
  chmod 0700 "$HOST_DIR" 2>/dev/null
  chmod 0644 "$HOST_APK" 2>/dev/null
  return 0
}
