# The module tree is normalised for the module manager by 90-restore-module-permission.sh -- every file
# 0644 -- and a program is the one file that cannot live with that: the boot script has to be able to run
# the loader, or the kernel module is never loaded. This part runs after the normalisation, which is why it
# is numbered above it.
[ -f "$MODPATH/bin/lkmloader" ] && set_perm "$MODPATH/bin/lkmloader" 0 0 0755
