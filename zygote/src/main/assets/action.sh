#!/system/bin/sh

# Magisk's action button, and the way in for anything else that knows the token: there is no
# asya app to launch, so this asks the host process for an activity, and the module answers that
# request with the manager's own -- out of the apk it carries.

MODDIR="${0%/*}"
PKG=io.github.grachevskaya.asya
ACTIVITY=io.github.grachevskaya.asya.ui.activity.MainActivity
APK_FILE="$MODDIR/manager.apk"


if [ ! -s "$TOKEN_FILE" ]; then
    echo "! No token in $TOKEN_FILE; reinstall the module so the installer writes one"
    exit 1
fi

TOKEN=$(cat "$TOKEN_FILE")

for user in $(pm list users | cut -f1 -d: | cut -f2 -d{ | tail -n +2); do
  [ "$user" = "999" ] && continue
  echo "- Asking $HOST to show the manager for user $user"
  # Asked for by broadcast first: a running host hears it and shows the manager itself, so its own
  # screen is never started on the way. Only when nothing is running to hear it does the shell start
  # the host -- the cold start, which has no other way in.
  #
  # That cold start asks for one of the host's own settings pages by action rather than naming the
  # top of it, and gives it a task of its own, which is what Sui does with its shortcut: to anyone
  # watching it is an ordinary settings page being opened, and the module replaces it a moment later.
  # The way the fork this is modelled on starts it: a secret code, broadcast to the host package.
  # The host's own code hears it and shows the manager -- the same thing the dialer sends when
  # someone types *#*#784784#*#*, so the entry a user has on the phone and the entry this script
  # uses are one and the same.
  CODE=2792
  API=$(getprop ro.build.version.sdk)
  if [ "$API" -ge 29 ]; then
    SECRET=android.telephony.action.SECRET_CODE
  else
    SECRET=android.provider.Telephony.SECRET_CODE
  fi

  # The broadcast names no package on purpose: a package-limited broadcast is never matched
  # against a receiver that was registered at runtime, so it goes out with zero receivers and
  # nothing at all happens -- which is exactly how every press of this button went unanswered.
  if [ -n "$(pidof $HOST 2>/dev/null)" ]; then
    am broadcast --user "$user" -a "$SECRET" \
      -d "android_secret_code://$CODE" --es "$TOKEN_EXTRA" "$TOKEN" >/dev/null 2>&1 \
      || echo "! Could not ask $HOST to show the manager on user $user"
  else
    # Nothing running to hear the code: bring the host up first. Its service, not one of its screens
    # -- that is the reason the host is SystemUI, and it means the cold start has no window either.
    am startservice --user "$user" -n "$HOST/com.android.systemui.SystemUIService" >/dev/null 2>&1 \
      || echo "! Could not reach $HOST on user $user"
    sleep 3
    am broadcast --user "$user" -a "$SECRET" \
      -d "android_secret_code://$CODE" --es "$TOKEN_EXTRA" "$TOKEN" >/dev/null 2>&1 \
      || echo "! Could not ask $HOST to show the manager on user $user"
  fi
done
echo "- If nothing came up: is $HOST in the module's scope, and has it been restarted"
