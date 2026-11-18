# PES 2018 Android (Xbox 360 → ARM64 recompilation)

An ahead-of-time recompilation of *Pro Evolution Soccer 2018* (Xbox 360) to native ARM64 code for Android.
The game's PowerPC code is translated to C++ with [XenonRecomp](https://github.com/hedge-dev/XenonRecomp)
and built with the Android NDK. A host runtime stands in for the console's kernel, GPU (Vulkan) and audio,
and a Kotlin app installs the game files, configures the run and starts the native library. The app also
installs [patch data packs](docs/patches.md) beside the game, backs up saves, and exports a
diagnostics report.

This is the maintained **PES 2018** fork of
[dzakyraf/pes2017-recomp](https://github.com/dzakyraf/pes2017-recomp). The PES 2017 build lives upstream;
this branch runs PES 2018 only, under its own package (`com.pes2018.recomp`).

Status: work in progress. The port boots to the title screen and plays an Exhibition match at 60 FPS on a
Xiaomi 14T Pro (Dimensity 9300+, Mali-G720, Android 16); a three-hour session ran without an issue, with
performance limited by the phone's heat before it is limited by the code. Other modes and save round-trips are
still unverified. [docs/PES2018.md](docs/PES2018.md) says exactly what works and what is left.

You need your own copy of the game. Nothing from it is in this repository.

## Quickstart

1. **Check your phone:** arm64 Android 8.0 or newer with Vulkan, and about 10 GB of free storage (the game files
   are about 7.3 GB). The tested phone is a Xiaomi 14T Pro.
2. **Get the game files:** see [Which copy of the game you need](#which-copy-of-the-game-you-need) below, and
   copy them to the phone.
3. **Install the app:** install the APK from
   [the latest release](https://github.com/sharifhidayatulloh/pes2018-recomp/releases/latest)
   (`PES2018-recomp-<version>-patches.apk`) over USB or by opening it on the phone, or build it from `android/`
   with `./gradlew :app:assembleRelease` (see [Build](#build)). Allow installs from your file manager if asked.
4. **Install the game:** open **SETTINGS → GAME FILES** and tap **Install the game files** (or tap **KICK OFF**
   when the app has already found a disc image). On the install screen choose **Browse the phone's storage** (or
   **Choose with the Files app**), pick the ISO or the extracted folder, and tap **Install**. If it stops, start
   again: it carries on from what was copied.
5. **Pick a preset:** open **SETTINGS**; the app suggests High, Medium or Low for your phone. Lower the render
   quality or turn off multisampling if the match stutters.
6. **Play:** tap **KICK OFF**. Use the on-screen buttons (pause and choose **Edit controls** to move them) or
   connect a gamepad.
7. **Install a patch (optional):** open **PATCHES** and tap **INSTALL PATCH**. Choose the pack as downloaded
   (ZIP, RAR or 7z) or the folder it was extracted to; the source is scanned before anything is written, then
   **INSTALL PATCH** copies the data pack (FL, ONFIRE, SIMPLE and similar) and the edit save beside the game.
   **RESTORE PREVIOUS** puts the slot that was there back. **SETTINGS → GAME FILES** shows the game folder, the
   space each part uses and a read-only patch summary. Folder shape, the import steps and each refusal are
   in [docs/patches.md](docs/patches.md).
8. **Back up your saves:** **SETTINGS → GAME FILES → Save data → Back up saves…** makes a zip you can keep in
   Downloads or Drive; **Restore saves…** puts one back and sets the current saves aside beside them.
9. **If something goes wrong:** open **LOG** for a short summary, share the whole log, or tap **Export report**
   for a diagnostics ZIP.

## Which copy of the game you need

The recompiled code is made from one exact executable, so the copy has to match it.

- **Game and version:** *Pro Evolution Soccer 2018* for **Xbox 360, Europe** (the `default.xex` of about 32 MB,
  XEX version 16, that this repository was built from). Other regions, other versions, the PC or PS4 releases,
  and other games will not work. The app checks `default.xex` by its SHA-256 and warns you when it is a
  different one. The port runs the disc build; a title update is not part of it.
- **Your own dump:** rip your own disc. Nothing from the game is distributed here.
- **Same copy for the build and the phone:** the APK is recompiled from a specific `default.xex`, so if you
  build it yourself, use the same disc dump you install on the phone.

Either form works:

| Form | What it must contain | Notes |
|---|---|---|
| **Disc image** (`.iso`) | an Xbox 360 disc image of the game, at least 500 MB | the installer reads it directly and copies only what the game needs |
| **Extracted folder** | `default.xex`, every `.cpk` archive (36 on the European disc) and the `nxeart` file, together in one folder | pick the folder that holds `default.xex` itself, not its parent |

Only `default.xex`, the `.cpk` files and `nxeart` are copied. Other disc content (such as `$SystemUpdate`) is
ignored, so it does not matter whether it is there. The full set is about 7.3 GB, so plan on about 10 GB free
while installing. Extract the whole disc, not a selection of the `.cpk` files.

## Build

```bash
GAME=../GAME ./build.sh            # recompile PPC -> C++ and build libpes17.so (arm64-v8a)
tools/deploy.sh                    # runtime-only rebuild, package, install and launch on the connected phone
```

`GAME` is the folder holding the disc's `default.xex`. The first full build recompiles all of `ppc/`; later
runtime-only rebuilds are much quicker. `RECOMPILE_ONLY=1` stops after the C++ is generated,
`DIAGNOSTICS=ON` builds the runtime with traces, dumps and reports, and
`PROFILE=safe|balanced|aggressive` trades generated code size against how much of the guest register file
stays in locals ([docs/RECOMP_RESEARCH.md](docs/RECOMP_RESEARCH.md)). The APK is built from `android/` with
Gradle. Game files go to `Android/data/com.pes2018.recomp/files/game/` (about 7.3 GB; plan on about 10 GB free).

## Features

- **Install from the app:** pick your disc folder or ISO; an interrupted copy carries on where it stopped.
- **Patch manager:** PATCHES installs a data pack and an edit save beside the game, scans the source before
  writing, keeps one previous version per slot, and can remove or restore it. A patch's outer CON/STFS files
  are unpacked to the inner CPKs and `DpFileList.bin`. How to install a patch:
  [docs/patches.md](docs/patches.md).
- **Game files:** SETTINGS → GAME FILES shows the game folder, the space each part uses and a read-only
  summary of the installed patch.
- **Picture settings:** display resolution, internal render multiplier (1x to 3x), match render quality, FSR
  sharpening upscale, multisampling for clean pitch lines, and a larger match ball that is easier to follow on a
  small screen.
- **Widescreen:** on a screen wider than 16:9 the game's cameras are widened, so the view fills the display
  instead of showing black bars.
- **Performance presets:** High, Medium and Low, chosen from what your phone looks like. Medium and Low lower
  the picture quality when the phone gets hot or the GPU falls behind, to keep the frame rate steadier.
- **FPS limit:** 30 to 60, where 60 is the console's own pacing and no limit.
- **Touch controls:** on-screen buttons you can move, resize and hide.
- **Gamepad:** physical controllers work, including an option that makes the controller player 2 while touch
  controls stay player 1.
- **Custom GPU driver:** import a Turnip driver (`.zip`) on Adreno phones for better speed or compatibility.
- **Save backup and restore:** Master League, Become a Legend, edit data and settings in one zip, with a copy
  taken automatically each time you kick off (the last five are kept).
- **Support:** a log viewer with a short error summary, whole-log sharing, a diagnostics ZIP from
  LOG → Export report, and clearing the shader cache or the logs.
- **Smoother and lighter:** built pipelines are kept on disk and reused next launch, and the texture cache is
  sized from the phone's memory.

## Reporting a bug

A report without evidence is a guess. In the app, open **LOG** and choose **Export report**; the ZIP holds the
logs, the event timeline, patch state, Android's exit reasons and any main-thread stall. It does not contain
game files, saves or account data — paths and names are redacted or left out.

Attach that ZIP to a new issue and say:

1. the app version from the footer (for example `0.2.9-patches · build 17`);
2. the phone, SoC and Android version;
3. the exact steps, and where it stopped;
4. whether a data pack is installed, and which one (see [docs/patches.md](docs/patches.md)).

If the app crashed before the export existed, the next launch offers the last crash; export from there.

## What you need to build it

| Requirement | Used for |
|---|---|
| macOS or Linux host, `cmake`, `ninja`, `clang`, `python3` 3.10+, `git` | recompiler build, generators, `build.sh` |
| Android NDK 28.x (`28.2.13676358`), Android SDK platform 35, JDK 17 | native library and Gradle app (`minSdk` 26, arm64-v8a only) |
| An Android phone with Vulkan 1.0+, `adb` | running and profiling; tested on a Xiaomi 14T Pro |
| Your own PES 2018 (Xbox 360, Europe) disc files, `default.xex` | the input to the recompiler |
| Network on first build | CMake fetches SDL3 and Dear ImGui |

## Documentation

| | |
|---|---|
| [docs/PES2018.md](docs/PES2018.md) | the 2018 port: input, status, patch importer, limits |
| [docs/patches.md](docs/patches.md) | installing a patch data pack: the folder shape, the steps, the errors |
| [release/BUILD-2018.md](release/BUILD-2018.md) | the 0.2.x build: APK, pipeline results, phone test steps |
| [docs/performance.md](docs/performance.md) | in-match measurements on the phone |
| [docs/RECOMP_RESEARCH.md](docs/RECOMP_RESEARCH.md) | recompiler findings and pipeline options |
| [docs/runtime.md](docs/runtime.md), [rendering](docs/rendering.md), [shaders](docs/shaders.md), [textures](docs/textures.md), [audio](docs/audio.md), [d3d9-seam](docs/d3d9-seam.md) | how the runtime stands in for the console |
| [docs/switches.md](docs/switches.md) | `PES17_*` runtime switches (the prefix is the runtime's, not the title's) |
| [docs/pillars/](docs/pillars/README.md) | the mobile performance roadmap |
| [.claude/docs/](.claude/docs/) | Claude session handovers and research notes: device setup, tools, open issues |

## Credits

This port stands on other people's work.

- **[XenonRecomp](https://github.com/hedge-dev/XenonRecomp)** by hedge-dev: the PowerPC → C++ recompiler and
  XenonAnalyse, vendored in `third_party/XenonRecomp` with local fixes (see `UPSTREAM.md` there). It bundles
  [fmt](https://github.com/fmtlib/fmt), [libmspack](https://github.com/kyz/libmspack),
  [simde](https://github.com/simd-everywhere/simde), [tiny-AES-c](https://github.com/kokke/tiny-AES-c),
  [toml++](https://github.com/marzer/tomlplusplus) and [xxHash](https://github.com/Cyan4973/xxHash).
- **[mw2-recompiled](https://github.com/PaulCombal/mw2-recompiled)** by PaulCombal and contributors (and
  GuruMachanica and contributors), GPL-3.0: the scaffold this port was built on. Its Xbox 360 runtime (kernel,
  XAM, GPU command processor, Xenos shader translator, Vulkan renderer, launcher) was adapted for this PES port.
- **[Xenia](https://xenia.jp)** and its contributors: the reference for Xbox 360 kernel behaviour, the Xenos
  GPU, texture formats and the XMA decoder; `third_party/ffmpeg-xenia` is Xenia's FFmpeg subset for XMA.
- **ReXGlue SDK**: sibling recompilation runtime we compared against
  ([.claude/docs/REXGLUE-EVALUATION.md](.claude/docs/REXGLUE-EVALUATION.md)); the FSR 1 SPIR-V in `runtime/gpu/vulkan/ffx/`
  comes from its SDK.
- **AMD FidelityFX Super Resolution 1** (EASU/RCAS, MIT): the optional upscaler.
- **[libadrenotools](https://github.com/bylaws/libadrenotools)** by bylaws: loading custom Adreno Vulkan drivers.
- **[SDL3](https://libsdl.org)**, **[Dear ImGui](https://github.com/ocornut/imgui)**,
  **[FFmpeg](https://ffmpeg.org)**, **[RenderDoc](https://renderdoc.org)** (`renderdoc_app.h`, GPU capture).
- **Barlow Condensed** (Jeremy Tribby, SIL OFL 1.1; `third_party/fonts`): launcher typeface.
- **Android NDK / SDK / Gradle** and the **Khronos Vulkan** headers and SPIR-V tooling.
- **Konami**: *Pro Evolution Soccer 2018* is their game and property. This project contains none of it and
  is not affiliated with or endorsed by Konami. The app's icon (drawn by `tools/make_artwork.py`), menu
  backdrop and wordmark are original artwork; the game's name is used only to say what it runs.

## Licence

GPL-3.0-only, see [LICENSE](LICENSE).
