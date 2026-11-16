# PES 2018 Android 0.3.0

Updates in place over 0.2.9; same requirements and the same PES 2018 Xbox 360 (Europe) game files.

You need your own copy of the game. Nothing from it is in this repository or in the APK.

## What's new

- **System music queries (upstream PR #66, by [@abdtirtayasa24](https://github.com/abdtirtayasa24)).**
  `XMPGetPlaybackController` and `XMPGetStatus` used to fall through the unhandled-message branch: they
  returned success without writing their output words, so the title read whatever was on the guest stack and
  could decide something else owned the music. Both now answer 0 (no override, idle). This is the path behind
  menu and stadium music going missing while sound effects and commentary still played.
- **Fast-core detection (upstream PR #51, by [@Doctor45dev](https://github.com/Doctor45dev)).**
  Cores are now classified by `/sys/devices/system/cpu/cpuN/cpu_capacity` with the clock rule as a fallback.
  The old clock rule called all eight cores fast on a Helio G85, so `PinToBigCores()` pinned nothing. RAM
  thresholds were also unreachable on 6/8/12 GB phones (they are now 5.5/7.5/11 GB), the Low preset no
  longer forces `lowMemory`, and the in-game quick panel now carries `loadScale`.
  On the Xiaomi 14T Pro this picks 4 of 8 fast cores by capacity and the menu still runs at 59 FPS.
- **Diagnostics.** The export now includes `logs/game.log.start`: the first 64 KB of the current run, with the
  native run header, driver, runtime options and the first perf lines, which the 2 MB tail would otherwise
  lose. The event log writes `startup.ready` instead of a misleading `run.clean-end` (it always ran right
  after start), and `report.txt` explains that `SIGNALED` on the `:game` process is the app's own kill after a
  quit, not a crash.

Everything from 0.2.9 is unchanged: the patch manager, save backup and restore, GAME FILES, the export ZIP,
picture settings, touch controls and driver import.

## Install

1. Install `PES2018-recomp-0.3.0-patches.apk` over USB or from the phone. It updates 0.2.9 in place.
2. Open the app, tap **KICK OFF**, and point it at your PES 2018 files (or an ISO) the first time.
3. Optional: install a data pack from **PATCHES**; back up your saves from **SETTINGS → GAME FILES**.

SHA-256: `302ebdef5ffa4387e40e7d577215ba791fc376eb12d6a49414df39b80168e0f2`

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
