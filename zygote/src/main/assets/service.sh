#!/system/bin/sh
# Load the module if post-fs-data did not. The policy comes from the userspace half over netlink;
# this package ships no such program and works without one.
MODDIR=${0%/*}
LOG="$MODDIR/state/sync.log"
mkdir -p "$MODDIR/state"
. "$MODDIR/load.sh"
tosya_load "$MODDIR" >>"$LOG" 2>&1

# The state beside the module is served by a process of its own, started here as root through this
# module's own dex -- the way Sui starts its server. It shares nothing with the zygote, so whatever it
# reads is on no heap an application inherits.
if [ -f "$MODDIR/classes.dex" ]; then
  CLASSPATH="$MODDIR/classes.dex" app_process /system/bin \
    io.github.grachevskaya.asya.zygote.service.StateDaemon >>"$LOG" 2>&1 &
fi

# The manager apk and its oat are not this stage's business: the installer puts them together
# # The table the loader reads is written beside the module by the service, so nothing has to be
# carried over from anywhere else at boot.
