# Paperleaf

An offline PDF book reader for Android, written in Kotlin with Jetpack Compose.
It is a native rewrite of the Flutter "Paperleaf Reader" and has no Flutter in it.

## Open and run

1. In Android Studio: **File → Open** and pick this folder (`paperleaf_native`).
2. Let Gradle sync (the first time downloads the libraries).
3. Connect the phone and press **Run**.

From a terminal instead:

```
gradlew assembleRelease      # build: app/build/outputs/apk/release/app-release.apk
gradlew testDebugUnitTest    # unit tests
```

The app id is `com.rasel.paperleaf`, so it installs next to the Flutter app
and shares nothing with it. To bring your books over, use **Back up library**
in the Flutter app and **Restore a backup** here: the backup format is the same.

## What is where

| Folder | What it holds |
|---|---|
| `core/` | Settings, page colors, importing PDFs, backup and restore, dictionary, small rules (titles, word review timing, stats) |
| `data/` | The SQLite database, its models, and every read and write (`Repository`) |
| `pdf/` | `PdfDoc`: pages drawn by Android's PdfRenderer; text, contents and PDF highlights read with PdfBox |
| `ui/library` | The shelf |
| `ui/reader` | The reader: page drawing and touch (`PageView`), page curl and slide (`TurnPainter`), logic (`ReaderState`), bars and sheets (`ReaderScreen`) |
| `ui/notes`, `ui/stats`, `ui/review`, `ui/settings` | The other screens |

## Libraries

- Jetpack Compose (Material 3), AndroidX
- [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android) (Apache 2.0) for text, contents and highlight export
- Fonts: Abril Fatface and Special Elite (licenses in `licenses/`)

## Before publishing

Release builds are signed with the debug key for now. Create a release key in
Android Studio (**Build → Generate Signed App Bundle / APK**) before uploading
anywhere, and keep it safe: updates must be signed with the same key.
