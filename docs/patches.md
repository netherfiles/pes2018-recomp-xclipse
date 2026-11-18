# Patches

A patch is a data pack: the CPK archives that carry competitions, kits, teams and faces, in the form the
game's console release used for downloadable content. Many packs also carry an edit save
(`EDIT00000000`) with edited player and team data, and a few carry a system save.

A patch is not the game and not the APK. The app writes the pack's files beside the game folder; the game
and the app stay where they are.

## Before you start

- The game must be installed. PATCHES reads the folder that holds `default.xex`.
- The game must be closed while a patch installs. The app refuses while it runs: "Quit the game first: its
  data cannot be replaced while it is running."
- One patch operation at a time. A second one is refused until the first finishes.
- Free space has to cover the peak, not the final size. The summary after the scan shows it: staging + the
  backup + the spool + a 64 MiB reserve. REPLACE with KEEP AS BACKUP also needs room for the pack installed
  now, because that pack becomes the backup.
- Use the pack as it was downloaded.

## What a patch looks like

A pack ships Xbox 360 content packages (CON, LIVE or PIRS files), not bare `.cpk` files. The app reads the
packages for the title `0x4A3007D3` (PES 2018, Europe). Inside them are the data pack's CPKs and one
`DpFileList.bin`, the list that orders the CPKs.

A real pack:

```
ONFIRE 2026/
├── Content/
│   └── 0000000000000000/
│       └── 4A3007D3/
│           └── 00000001/
│               ├── <CON packages, one CPK each>
│               └── tu0000000E_00000000    ← title update, skipped on purpose
└── readme.txt
```

`DpFileList.bin` is inside one of the CON packages, not a loose file next to them. The title update is for
the console; this port runs the disc build, so the pack's copy is skipped.

A wrong shape, which is what a tool that extracted the CONs leaves behind:

```
ONFIRE 2026/
├── bcl.cpk
├── FL 1.cpk
├── ...
└── DpFileList.bin
```

That is not a patch the app can use. Download the pack again and point PATCH ARCHIVE at the untouched file.

## Installing

1. Close the game.
2. Open PATCHES. The header must not say NO GAME FOLDER. If it does, choose the game folder there, or
   install the game first; nothing can be installed until the folder holds `default.xex`.
3. Tap INSTALL PATCH, or REPLACE when a pack is already installed. REPLACE asks what happens to the old one:
   KEEP AS BACKUP (it moves to RESTORE PREVIOUS), FREE OLD BACKUP FIRST (delete the backup there, keep the
   installed pack), or NO BACKUP (the installed pack is discarded and cannot be brought back).
4. Choose the source: PATCH ARCHIVE (.ZIP / .RAR / .7Z) for the download, EXTRACTED PATCH FOLDER for the
   folder it was unzipped to, or MULTI-PART FOLDER (PART 1) for a split RAR. For a multi-part set, choose the
   folder that holds every part.
5. An archive source opens Archive password. Type the password and tap USE PASSWORD, or tap NO PASSWORD when
   the pack has none. A folder source does not ask.
6. The source is scanned before anything is written. The summary lists PATCH (CPK count and size), EDITED
   DATA (players, teams), a GLOBAL SAVE switch under ADVANCED, and a SKIPPED count. SKIPPED counts files
   that belong to no patch: a readme, a preview image, the title update, a profile. That is normal.
7. Check PEAK SPACE NEEDED. INSTALL PATCH is disabled while there is not enough room.
8. Tap INSTALL PATCH on the summary. STOP cancels: the staged copy is removed, the pack already installed is
   left as it was, and the backup is not changed.

Point the app at one archive or one multi-part set. A folder with two ZIPs, two RARs or two 7z files in it
is refused, because the app cannot tell which one is the pack.

## After it is installed

PATCHES shows what is installed: the header reads PATCH INSTALLED or PATCH AND EDITED DATA INSTALLED, with
the pack's name, date, CPK count, size and entry count. EDITED DATA has its own card. INCOMPLETE means the
files the record expects are missing or no longer match; replace the pack, or remove it and use the game as
it is.

- SETTINGS → GAME FILES shows a read-only summary of the patch and the space each part uses.
- RESTORE PREVIOUS puts the one backup back. The pack installed now becomes the backup in its place.
- REMOVE PATCH removes the data pack; the dialog states whether the copy in RESTORE PREVIOUS stays. REMOVE
  EDITED DATA does the same for the edit save.
- Files: data pack `files/dlc/SYSTEM00000000`; edit save `files/saves/EDIT00000000`; global save
  `files/saves/SYSTEM00000000` only when ADVANCED was used; backups `files/patch-backups`. Here `files/` is
  the app's folder on the phone, one level above the game folder named on the PATCHES header.

## When it refuses

The import stops and shows one of these. The large line is the reason; the line under it names the package
or the mismatch. 0.3.2 added a separate message for a source with no packages at all; the other rows read
the same on both.

| What the source is | 0.3.0 and older | 0.3.2 and later | Why | What to do |
|---|---|---|---|---|
| No Xbox 360 packages: loose `.cpk` files, a `DpFileList.bin` on its own, a PC pack | NOT A PES 2018 PATCH | NOT AN XBOX PACK | Nothing in the source is a CON, LIVE or PIRS package | Download the pack again and use the archive or folder as it came |
| Packages for another title, such as PES 2017 | NOT A PES 2018 PATCH | NOT A PES 2018 PATCH | The packages name a title other than `0x4A3007D3` | Use a PES 2018 Xbox 360 pack |
| Title packages but no `DpFileList` | NO DPFILELIST | NO DPFILELIST | Nothing orders the CPKs, so the game would not see them | Use a pack that includes the list |
| One CPK and no list | NO DPFILELIST | NO DPFILELIST | A single file is not a data pack | Use the full pack |
| A package or part cannot be read | SOURCE CORRUPT | SOURCE CORRUPT | The download is truncated or damaged | Download it again, or copy it to the phone again |
| A part of a split RAR is missing | PART MISSING | PART MISSING | The parts are not all in one folder, or numbered apart | Put every part in one folder and choose part 1 |

## What it will not do

- Read a PES 2017, PC, PS3 or PS4 pack. Only PES 2018 Xbox 360 content packages are used.
- Open split 7z (`.7z.001`) or split ZIP volumes. A split RAR is accepted: put every part in one folder and
  choose MULTI-PART FOLDER (PART 1). Join or extract a split 7z or zip first.
- Read a save CON over 64 MiB inside an archive. Extract the pack to a folder and choose EXTRACTED PATCH
  FOLDER.
- Change your progress by itself. GLOBAL SAVE replaces the shared system save, which holds settings and
  progress for every mode; it sits under ADVANCED, off by default, behind a second confirmation.
- Host or link patch packs. The repository has none; get them from their authors.

## If it still fails

Open LOG and tap Export report. The ZIP holds the logs, the event timeline and the patch state, with paths
and names redacted or left out. Add the pack's name and version, and the exact wording on the screen,
including the line under the message: an unpacked pack and a pack for another game look the same at the
top, and that line is what tells them apart.
