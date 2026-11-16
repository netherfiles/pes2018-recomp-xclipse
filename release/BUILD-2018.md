# PES 2018 (Xbox 360, Europe) → Android ARM64 — build 0.2.0-patches

Built from `pes2018-recomp`, a fork of the `pes2017-recomp` pipeline.
Status: 0.1.6 is **boot-tested** on a Xiaomi 14T Pro — it reaches the top menu,
Exhibition and Match/MatchIdle, 8,745 frames at 60 FPS with 0 indirect misses.
0.2.0 adds the PATCHES importer (data pack, edited data, backups) and its
STFS/archive/transaction layer; it is built and JVM-tested, but the on-device
install/boot gate is still pending (`work/patch-harness/PHONE-TEST.md`). The FL
patch was verified loading on device in 0.1.6.

## APK

| | |
|---|---|
| File | `release/PES2018-recomp-0.2.0-patches.apk` |
| Size | 42,037,057 bytes (40.09 MiB) |
| SHA-256 | `f9c63517f3ad6eebf5a8df47255b08c3c0d335f9daeeac556d1f259d1ff557bb` |
| Package | `com.pes2018.recomp` |
| Version | `0.2.0-patches (pes2018)`, versionCode 8 |
| minSdk / targetSdk | 26 / 35 |
| ABI | `arm64-v8a` |
| Signing | debug key (`CN=Android Debug`); updates 0.1.6 in place |
| Previous | `release/PES2018-recomp-0.1.6-dlcroot.apk` (code 7) |

## PATCHES importer (0.2.0)

The launcher now has a **GAME DATA** hub and a **PATCHES** screen: PATCH (CPK
data pack), EDITED DATA (edit save), RESTORE PREVIOUS (backups), and an import
wizard for archives or extracted folders. It is the supported way to install
the FL/ONFIRE/SIMPLE patches; the old adb push is only a fallback.

Targets:

- data pack → `files/dlc/SYSTEM00000000` (inner CPKs + inner `DpFileList.bin`;
  never the 57,344-byte CON wrapper)
- edit data → `files/saves/EDIT00000000` (`data.bin`/`data.inf`/`data.png`)
- global save → `files/saves/SYSTEM00000000`, under ADVANCED, off by default,
  with a second confirmation; a backup is kept
- staging `files/patch-staging`; backups `files/patch-backups` (outside
  `saves/`, so the runtime's `RotatePackage` and `SaveBackup` cannot clobber or
  zip them)

Formats: ZIP/ZIP64 (tested with the three 2025/2026 samples), an extracted
Content tree, RAR5 single/multi-part and single 7z via libarchive 3.8.1
(compiled; device tests pending), and password prompts. A patch's outer files
are CON/STFS; the importer reads the file table, extracts the inner CPKs, and
writes the inner DpFileList bytes (2,222 / 2,900 / 2,108 in the samples).

STFS: CPK/DpFileList CONs are `0xAD0E`; EDIT/SYSTEM saves are `0x971A` with two
hash tables per 0xAA-block group. The save extractor verifies SHA-1 for every
block; the old `0xAD0E` formula produced corrupted EDIT `data.bin` and is not
used. The `0x971A` >`0x70E4` term is inferred and rejected until a fixture
exists.

Release build: 0.2.0-patches, code 8, 42,037,057 B, SHA-256
`f9c63517...`; 31/31 JVM tests; `zipalign -c -P 16 4` and signature pass. The
three samples were scanned on the desktop: FL 31 CPK/30 listed, ONFIRE 31/31
with duplicate EDIT deduped, SIMPLE 9/9. On-device install, EDIT load, SYSTEM
replacement, cancel/process-death, backup/restore and RAR/7z are still the
phone acceptance gate (`work/patch-harness/PHONE-TEST.md`).

## UI revamp (0.1.2-ui / 0.1.3-icon)

- **App icon**: every `mipmap-*` (48/72/96/144/192) and the adaptive icon are
  generated from the PES 2018 cover art, including the round icons. 0.1.3
  shrinks the cover to 74% on a navy `#0B1B33` canvas (not full-bleed) so the
  launcher mask does not cut the lettering; the foreground inset is 0% because
  the border is already in the artwork.
- **Wordmark**: `launcher_logo.webp` was edited from "PES17 RECOMPILED FOR
  ANDROID" to **"PES18 RECOMPILED FOR ANDROID"** (the 18 is drawn in Arial
  Narrow Bold Italic in the original `#004F9D`, and the leftover pixels of
  "17" were cleaned).
- **No longer full screen**: `MainActivity`, `InstallActivity`,
  `SettingsActivity`, `FileBrowserActivity`, `DriverActivity`,
  `CapturesActivity` and `ControlsEditorActivity` now call
  `applySystemBars()` (`UiInsets.kt`): the status/navigation bars are visible,
  the content is padded by the system bars plus the display cutout, and the
  bar icons are dark on the white pages and light on the green editor.
  **GameActivity stays full screen** because that is the game.
- The `Theme.PES17` theme now sets `windowLightStatusBar/NavigationBar` and the
  bar colours to `@color/pes_page`; `Theme.PES17.Game` stays black.

Note: the native library is still named `libpes17.so`
(`System.loadLibrary("pes17")`) so the JNI symbols and packaging do not change;
the application id and label are already PES 2018.

## FL patch (DLC)

The `fl-patch` folder is not a raw game-data folder: it is a set of Xbox 360
**CON/STFS content packages** (`Content/0000000000000000/4A3007D3/00000001/`)
plus the official **title update** `tu0000000E_00000000` (`default.xexp`). The
32 content files were extracted to their raw CPKs (12.93 GB):

`bcl.cpk`, `FL 1..26.cpk`, `GRAFICOS.cpk`, `GRAMADOS.cpk`,
`LIBERTADORES.cpk`, `TREINADOR.cpk`, and `DpFileList.bin`.

The runtime now mounts the title's LIVE DLC drives (`mntLIVE*`/`LIVE*`) under
`files/dlc`, not `files/saves`; the app's automatic save backup therefore does
not try to zip 13 GB of DLC. Install with:

Install it through PATCHES (data pack) or, as a fallback, push the inner raw
CPKs and the 2,222-byte inner `DpFileList.bin` to
`files/dlc/SYSTEM00000000/` (not `LIVE00000000`, and not the 57,344-byte CON).

The FL patch is a **FreeDlc** package, not a LIVE DLC: it carries
`DpFileList.bin` + CPKs, while the `mntLIVE` path expects `data.bin`. On device
the game mounts `FreeDlc000` on `dlc/SYSTEM00000000`, opens the root as a
directory, enumerates it through a new `NtQueryDirectoryFile` (the title uses
the 8-argument variant, so class 0 defaults to `FileDirectoryInformation`),
reads `DpFileList.bin` (2,222 bytes) and then 36 CPK headers. The Exhibition
screen shows the FL ProCh patch with the 2018/19 Liverpool and Arsenal lineups,
0 errors. The TU was **not** applied: the disc image is XEX v16, newer than the
TU's base v14, and the DLC loader is already present in the disc build.

## Input

- `pes/default.xex` — the disc XEX, SHA-256
  `18d1e93ee219bd590b2558b6004b05785a36e3bcb01219ef0bccb4bab3552c5d`
  (XEX2, title `0x4A3007D3`, version 16, basic-encrypted + basic-compressed).
- The downloaded XEX is the same build: 322 bytes differ, all in the
  header/security area; the payload from `0x5000` is identical.
- `pes/default.pe` — output of `tools/xexdump.py`: 32,899,072 bytes, PowerPC
  BE, image base `0x82000000`, entry `0x824366D8`.

## Pipeline (full logs)

| Step | Result | Log |
|---|---|---|
| XEX → PE | 32,899,072 B, .text `0x82410000`+`0x18FD434`, 73,209 .pdata slots | `log/build-recompile.log` |
| XenonAnalyse | 1,432 switch tables; +2 missed → 1,434 | `log/build-recompile.log` |
| Helpers | 8/8 save/restore patterns found uniquely | `config/PES.toml` |
| Recompile pass 1 | 5,267 jump-table errors, 226 bounds | `log/build-recompile.log` |
| Recompile pass 2 | **0 jump-table errors**, 44 undecodable words (data at the tail of .text) | `log/build-recompile.log` |
| `mullhwu` | 1 line at `0x833E5928` — data inside .text; the final PES 2017 image has the same single line (`0x83318590`) | — |
| Kernel imports | 283 needed, 213 implemented, 70 stubbed | `log/build-recompile.log` |
| Generated | 617 C++ units, 435 MB | `ppc/` |
| Native build | 859/859 targets, 0 errors, 47 warnings (`-Wasm-operand-widths`, pre-existing) | `log/ninja-build.log` |
| `.so` | 372,809,776 B unstripped → 119,262,192 B `--strip-debug` → 104,312,592 B in the APK (AGP strip) | — |
| 16 KB pages | all LOAD segments are `0x4000` | `llvm-readelf` |
| Gradle | BUILD SUCCESSFUL, 41 MB APK | `log/gradle-apk.log` |

## PES 2018 runtime addresses (`runtime/title.h`, `runtime/pes_hooks.cpp`)

| 2017 | 2018 | Meaning |
|---|---|---|
| `0x82416708` | `0x824366D8` | entry point |
| `0x823F98E0` | `0x824198E0` | D3D Present |
| `0x823F70F8` | `0x824170F8` | BlockOnFence |
| `0x82042C08` | `0x821B15A4` | 16:9 aspect (rdata) |
| `0x8234808C` | `0x8236E784` | 16:9 aspect (second) |
| `0x82000720` | `0x82000728` | KeTimeStampBundle import record |
| `0x82000654` | `0x8200065C` | KeDebugMonitorData import record |
| `0x823F7C38` | `0x82417C38` | fence poll (yield) |
| `0x82F3CBD0` | `0x82FDDB20` | Intro/ProcInstall state |
| `0x82F00CE8` | `0x82F9CCE8` | procedure dispatcher |
| `0x823F64B8` | `0x824164B8` | ring kick → WaitAfterKick |
| `0x8240CD00` | `0x8242CCF8` | ring kick → WaitAfterKick |

Every hook was matched by masked instruction signature and has the same size as
its 2017 counterpart. Of the 73,078 PES 2017 .pdata functions, 62,945 (86.1%)
have an identical body in PES 2018.

## App

- `applicationId` = `com.pes2018.recomp`; the Kotlin namespace stays
  `com.pes2017.recomp` so the JNI symbols and the manifest do not change.
- Label "PES 2018"; the XEX hash in `BuildConfig` and CMake is the 2018 hash.
- The update check against the PES 2017 repository is disabled (it must never
  offer a different app's APK).
- The game-folder search also recognises PES 2018 folder names.
- Save backups are named `pes2018-saves-*.zip`.

## Still untested

1. A full match/mode matrix: boot, top menu, Exhibition and Match/MatchIdle
   have been run; the other modes, player data and save round-trips have not.
2. Installing from the PES 2018 ISO through the in-app installer: during the
   test the files were pushed directly to the app folder; `launcher/disc.cpp`
   already supports XDVDFS at `0x2080000`, so this should work but has not been
   exercised end to end.
3. Emulator/desktop: the runtime needs Vulkan and a 4 GiB address space, so
   neither was used.
4. A full match with the FL data: the Exhibition team select and lineups load;
   the match itself, the other modes and the save/player data are still
   unverified.

## Testing on a device

```bash
adb install -r release/PES2018-recomp-0.1.4-dlc.apk
# copy the ISO (or an extracted folder) to the phone, then in the app:
#   Install from disc image
adb logcat -c
adb shell am start -n com.pes2018.recomp/com.pes2017.recomp.MainActivity
# the runtime log:
adb pull /sdcard/Android/data/com.pes2018.recomp/files/game.log .
```

## Remaining risks

- PES 2018-specific behaviour that a build cannot show: the first-run flow,
  save/player-data layout, and the 70 imports that are still stubbed.
- `assets/shader_cache.bin` is still the 2017 Vulkan cache; it is validated by
  `pipelineCacheUUID` and size, so a mismatch is ignored, but it has not been
  tested with 2018 shaders.
- `PES17_HINT_*` is still tuned for 2017; the texture/perf numbers may need
  adjusting after the first device logs.
