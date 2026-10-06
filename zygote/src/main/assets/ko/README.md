# The kernel half

`tosya.ko` is built in the [tosya](../../../../../../tosya) repository, one object per KMI, and
copied in here when the module is packaged -- by `build/dev/pack-unified.sh`, or by CI, which
takes them from tosya's release. They are build artifacts, so they are ignored by git: this
directory is where they land, not where they live.

`post-fs-data.sh` picks the one that matches the running kernel and loads it. Nothing else in
this module needs the kernel half to be present; the hiding works whether it loaded or not, and
the only thing that changes is which lookups it can answer in the kernel instead of in the
service.
