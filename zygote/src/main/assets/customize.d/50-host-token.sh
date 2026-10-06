#!/system/bin/sh

# The token that proves a launch is meant for the manager. It is made here, once, when the
# module is installed, and both sides read the same file: the action script passes it, and the
# wrapper in the host process checks it. Something that does not know it gets an ordinary launch
# and never learns there is a manager here.

MODPATH=${MODPATH:-${0%/*}/..}
STATE="$MODPATH/state"
mkdir -p "$STATE"

if [ ! -s "$STATE/host_token" ]; then
    head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n' > "$STATE/host_token"
    echo "" >> "$STATE/host_token"
fi

chmod 0600 "$STATE/host_token" 2>/dev/null
ui_print "- The manager's token is in place"
true
