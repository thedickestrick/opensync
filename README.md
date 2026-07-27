# OpenSync — file manager · gallery · folder sync (ad-free)

An original, ad-free Android app that combines three things:

- a **file manager** that browses your device *and* remote servers as if they were local,
- a **gallery** for your photos and videos (device + folder albums, including remote), and
- **folder synchronization / backup** (the original FolderSync-style feature).

It is clean-room — not a copy of any app's code or assets. Rename it freely (see *Renaming*).

## Features

### File manager
- Browse **Internal storage** and any **SMB (Windows share / domain) / FTP / FTPS / SFTP /
  WebDAV** account from one screen.
- Navigate remote folders exactly like local ones (breadcrumb, back, sort, hidden files).
- **Copy / cut / paste / delete / rename / new folder** — including moving files *between*
  locations (e.g. SFTP → local, local → WebDAV) via a recursive cross-provider engine.
- Multi-select (long-press), image thumbnails for local files, open any file with the
  system app chooser.

### Gallery
- **Device** photos/videos via Android's MediaStore, grouped into albums (fast, native feel).
- **Folder albums** for local folders and remote accounts — browse into any folder and see
  its media as a grid; remote image thumbnails download progressively (size-capped).
- Full-screen viewer with **swipe** between items and **pinch-to-zoom**; videos open in your
  player of choice.
- Switch data source (Device / Internal storage / each account) from the title bar.

### Folder sync / backup
- Sync directions: to remote / from remote / **two-way** (with a per-file state database that
  detects which side changed, plus deletions).
- Conflict rules, include/exclude filters, delete propagation.
- Scheduling via WorkManager (every N minutes, Wi-Fi-only / charging-only), progress
  notifications, and a sync log.

### Self-update from GitHub
- Point **Settings → App updates** at the GitHub repo where you publish releases.
- The app checks the latest release, compares versions, downloads the attached **.apk**, and
  launches the installer — **no sideloading of files by hand**.

### Everywhere
- Material 3 UI (light/dark/dynamic color), navigation drawer, encrypted credential storage
  (AES-GCM key in the Android Keystore). **No ads, no tracking.**

## Requirements

- **Android Studio** (Ladybug 2024.2+). It bundles the Android SDK and Gradle, which you
  don't currently have installed.
- A device/emulator on **Android 8.0 (API 26)** or newer.

## Build & run

1. Open the `FolderSync` folder in **Android Studio** and let **Gradle sync** finish.
2. Press **Run ▶** on a device/emulator.
3. In the app: open the **drawer** (☰). First run: **Settings → grant "All files access"**,
   enable **Notifications**, and (for the gallery) allow **photos & videos**.

Command-line build once an SDK exists — create `local.properties` with
`sdk.dir=C\:\\Users\\thedickestrick\\AppData\\Local\\Android\\Sdk`, then:

```bash
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Versioning & signing (required for updates to install)

Android only replaces an installed app when the new APK has a **higher `versionCode`** and is
signed with the **same key**. This project handles both:

- **Version** lives in [version.properties](version.properties). Bump it per release —
  `versionCode` must strictly increase (1 → 2 → 3 …); `versionName` is what the in-app updater
  compares to the GitHub release tag.
- **Signing**: if a git-ignored `keystore.properties` exists it signs releases with your key;
  otherwise releases use the **debug key** (stable on one machine, fine for personal use).
  Build all your releases the same way so the key never changes, or updates get rejected with
  *"signatures don't match."*

To sign with your own key (recommended if you build on more than one machine), create the
keystore once:

```bash
keytool -genkey -v -keystore opensync-release.jks -alias opensync -keyalg RSA -keysize 2048 -validity 10000
```

then create `keystore.properties` in the project root:

```
storeFile=opensync-release.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=opensync
keyPassword=YOUR_KEY_PASSWORD
```

## Releasing a new version (GitHub self-update)

1. Edit [version.properties](version.properties): raise `versionCode` (e.g. 1 → 2) and set
   `versionName` (e.g. `1.1`).
2. Build the APK: `./gradlew.bat assembleRelease` → `app/build/outputs/apk/release/app-release.apk`.
3. Create a **GitHub Release** tagged with the version (`v1.1` or `1.1`) and attach that APK.
4. In the app: **Settings → App updates** → enter your GitHub **owner** and **repo** →
   **Check for updates** → **Download & install** (Android asks you to allow installs from
   OpenSync the first time).

The updater only contacts the repo you configure, and Android's installer always confirms
before anything installs.

## Renaming the app

- Display name: `app/src/main/res/values/strings.xml` → `app_name`.
- Package / app id: `namespace` + `applicationId` in `app/build.gradle.kts`, and the
  `com.opensync.foldersync` source folders (Android Studio → *Refactor → Rename*).

## Project layout

```
app/src/main/java/com/opensync/foldersync/
├─ data/         Room entities + DAOs (accounts, folder pairs, sync log, sync state)
├─ crypto/       Keystore-backed AES-GCM for passwords
├─ provider/     StorageProvider abstraction + Local/SMB/FTP/SFTP/WebDAV
├─ files/        FileOps (copy/move/delete) + ExplorerRepository (connection mgmt)
├─ gallery/      GalleryRepository (MediaStore + folder media)
├─ sync/         SyncEngine, SyncFilter, SyncManager, SyncWorker
├─ update/       UpdateChecker (GitHub) + AppPrefs
├─ ui/           Compose screens: explorer, gallery, pairs, accounts, logs, settings
├─ Graph.kt      tiny DI container
└─ FolderSyncApp Application (WorkManager, notifications, Coil)
```

## Notes & limitations

- Not yet run through a device test matrix — build it and try it on data you can afford to
  experiment with first.
- Remote gallery thumbnails download the file to cache (servers don't provide thumbnails);
  large files (>15 MB) show an icon instead. Remote video thumbnails are skipped.
- Videos play via an external player intent (no in-app player yet).
- **SMB**: use SMB2/SMB3 (modern Windows/NAS). In the account, put the server IP/hostname,
  your Windows **domain** (blank for a workgroup or local account), username, password, and set
  the base path to `/<ShareName>` or `/<ShareName>/<subfolder>` (the first segment is the
  share). SMB1/CIFS-only servers are not supported. On Android the full BouncyCastle provider
  is installed at runtime because SMB 3.x signing needs AES-CMAC.
- SFTP uses `StrictHostKeyChecking=no`; WebDAV can't set remote mtimes (two-way sync
  compensates via its state database).
- Self-update requires you to sign releases with a **consistent key**, or Android will refuse
  to install the update over your existing install.
```
