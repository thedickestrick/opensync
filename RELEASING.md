# Releasing OpenSync

Releases are built and published automatically by GitHub Actions
([.github/workflows/release.yml](.github/workflows/release.yml)). You never build or upload an
APK by hand.

## Cut a release

Push a version tag:

```bash
git tag v1.3
git push origin v1.3
```

That triggers the pipeline, which:
1. builds a **signed release APK** (version taken from the tag),
2. creates a **GitHub Release** for that tag with the APK attached and auto-generated notes.

Within a minute or two the in-app updater (**Settings → App updates**) will offer it.

You can also run it manually: repo → **Actions → Release APK → Run workflow** → enter a version
like `1.3`.

## Versioning

- **versionName** comes from the tag (`v1.3` → `1.3`). No file to edit.
- **versionCode** is derived from the version so it always increases:
  `major*10000 + minor*100 + patch` (e.g. `1.3` → `10300`, `1.3.1` → `10301`).
- [version.properties](version.properties) is only used for **local dev builds**
  (`./gradlew assembleDebug`); the pipeline overrides it with `-PversionName`/`-PversionCode`.

## Signing

The workflow signs with a keystore supplied via repository **secrets**:

| Secret | Current value |
| --- | --- |
| `KEYSTORE_BASE64` | base64 of your `~/.android/debug.keystore` |
| `KEYSTORE_PASSWORD` | `android` |
| `KEY_ALIAS` | `androiddebugkey` |
| `KEY_PASSWORD` | `android` |

It's currently your **debug** key, chosen so CI releases install straight over the debug build
you already have — zero friction. It's fine for personal use.

### Switching to a dedicated release key (optional, recommended if others install it)

```bash
keytool -genkey -v -keystore opensync-release.jks -alias opensync -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 opensync-release.jks > opensync-release.jks.b64   # (PowerShell: use [Convert]::ToBase64String)
gh secret set KEYSTORE_BASE64 < opensync-release.jks.b64
gh secret set KEYSTORE_PASSWORD   # then type your store password
gh secret set KEY_ALIAS --body "opensync"
gh secret set KEY_PASSWORD        # then type your key password
```

After switching keys you must **uninstall and reinstall once** (the signature changes), then all
future updates flow normally again.
