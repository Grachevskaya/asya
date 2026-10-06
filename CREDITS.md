## Where this comes from

- [HMA-OSS](https://github.com/frknkrc44/HMA-OSS) by frknkrc44 -- the app, its Zygisk hooks and its
  configuration model. This repository is a clone of it, with everything that is not about hiding apps
  removed; its history is kept, so the work above and below this line is preserved.
- [Hide My Applist](https://github.com/Xposed-Modules-Repo/com.tsng.hidemyapplist) by nullptr -- the module
  HMA-OSS is the open source rewrite of, and where the hiding model starts.

The list below is HMA-OSS's own, untouched.

## Credits

- [DoNotTryAccessibility](https://github.com/Nitsuya/DoNotTryAccessibility) - settings and accessibility hiding code
- [SettingsFirewall](https://github.com/canyie/SettingsFirewall) - settings replacement idea
- [IAmNotADeveloper](https://github.com/xfqwdsj/IAmNotADeveloper) - hiding developer options idea
- [BetterKnownInstalled](https://github.com/Pixel-Props/BetterKnownInstalled) - package installer spoofing idea
- [0bbedCode](https://github.com/0bbedCode) - ID checker
- [vova7878](https://github.com/vova7878) - ZygoteLoader, AndroidVMTools and PanamaPort
- [RikkaW](https://github.com/RikkaApps) - stubs
- [ReZygisk](https://github.com/PerformanC/ReZygisk) - status update scripts
- All translators
- All root community
- You (if you are not a robot 🤖)

## Bundled components

These are used as they are published, and none of them is a fork carried in this tree.

- [lkmloader](https://github.com/Rouyashiki/lkmloader) by Rouyashiki -- MIT. Taken as the pinned
  submodule under `external/lkmloader`, and built for arm64 into every package, so a device whose
  kernel side has no KernelSU loader can still load the module.
- [ZygoteLoader](https://github.com/vova7878/ZygoteLoader) by vova7878 -- the zygisk loader this module
  is built and packaged against. It is not a fork: the published artifact is the one in use.
- [AndroidVMTools](https://github.com/aerath-stuff/AndroidVMTools) -- the pinned submodule under
  `external/AndroidVMTools`, which brings the runtime this module runs its hooks through.
- The kernel objects the package carries are the ones built from
  [tosya](https://github.com/Grachevskaya/tosya).

### !!! Note that this MD file isn't allowed to translate !!!
