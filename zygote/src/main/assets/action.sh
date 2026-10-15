#!/system/bin/sh
# The action button: one secret-code broadcast, addressed to the android package, where the receiver
# was registered.
API=$(getprop ro.build.version.sdk)
[ "$API" -ge 29 ] && SECRET=android.telephony.action.SECRET_CODE || SECRET=android.provider.Telephony.SECRET_CODE
am broadcast -a "$SECRET" -d android_secret_code://2792 android
