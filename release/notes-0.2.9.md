# PES 2018 Android 0.2.9

The first release of this maintained fork: **PES 2018** (Xbox 360, Europe) on Android, built on
[dzakyraf/pes2017-recomp](https://github.com/dzakyraf/pes2017-recomp). The app is a separate package
(`com.pes2018.recomp`), so it installs beside the PES 2017 build and does not touch its data.

You need your own PES 2018 disc files. Nothing from the game is in this repository or in the APK.

## What you need

- arm64 Android 8.0 or newer with Vulkan.
- About **7.3 GB** of game files and **10 GB** of free storage.
- Your own **PES 2018** (Xbox 360, Europe) `default.xex` and CPK archives, or a disc image to install from.
- A recent Snapdragon or Dimensity phone. This release was tested on a **Xiaomi 14T Pro** (Dimensity 9300+,
  Mali-G720, Android 16): the game boots, plays an Exhibition match, and ran for three hours without an issue.

## What's in it

- **PES 2018 support** on the recompilation pipeline: title addresses, installer, switch tables and the Android
  identity.
- **Patch manager (PATCHES):** installs FL/ONFIRE/SIMPLE-style data packs from a `.zip`/`.rar`/`.7z`, an extracted
  folder or a multi-part set. The source is scanned first, the install is staged with a journal and recovered
  after a kill, and one previous version is kept per slot (data pack, edit save, SYSTEM). STFS/CON wrappers are
  unpacked to the inner CPKs and `DpFileList.bin`.
- **GAME FILES:** the game folder, storage per part, and a read-only summary of the installed patch.
- **Save backup and restore:** Master League, Become a Legend, edit data and settings in one zip, plus an
  automatic copy each time the game starts (the last five are kept).
- **Diagnostics:** LOG → **Export report** writes one ZIP with `report.txt`, a file manifest, scrubbed logs, the
  per-process event timeline, patch state, Android exit reasons and a main-thread stall record. Share it or save
  it to `Download/PES18`. This is what a bug report needs.
- **Log viewer:** a short summary with counts, "the whole log", and the previous run kept as `game.log.1`.
- **Graphics and controls:** picture settings, FSR upscale, widescreen cameras, performance presets, FPS limit,
  touch controls, gamepads, and importing a Turnip driver on Adreno phones.

## Install

1. Uninstall is not needed if you already run this package; the APK updates in place.
2. Install `PES2018-recomp-0.2.9-patches.apk` over USB or from the phone.
3. Open the app, tap **KICK OFF**, and point it at your PES 2018 files (or an ISO) the first time.
4. Optional: install a data pack from **PATCHES**; back up your saves from **SETTINGS → GAME FILES**.

SHA-256: `2f47cd77886e9b38baa28095402b15c0bb7412bb96aa6ce4418d0459c7b66a8c`

## Known limits

- **PES 2018 only.** The PES 2017 build is the upstream project, not this branch.
- The input is the **disc XEX (version 16)**. A title update is not part of this release.
- Performance is heat-limited on a phone; the presets and the frame limit are there for that.
- Patch compatibility depends on the pack's title data; the app refuses a source without the PES 2018 title
  files rather than half-installing it.

## Licence and credits

GPL-3.0-only, the same licence as the upstream project. This fork keeps the upstream copyright, the
XenonRecomp and runtime credits, and the "bring your own copy" terms. PES 2018 is Konami's; this project
contains none of it.
