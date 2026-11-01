# PrivSnap

A 48 KB Android utility to strip EXIF/GPS metadata and blur or redact sensitive text in screenshots before sharing.

No `INTERNET` permission, no storage permissions, and no third-party dependencies.

![Screenshots](assets/reddit-promo.png)

## Features

- **Zero permissions**: `AndroidManifest.xml` declares no `<uses-permission>` tags (`aapt2 dump permissions` is empty). Images are read via the system photo picker (`ACTION_GET_CONTENT`) or the Android share sheet (`ACTION_SEND`).
- **EXIF / GPS / XMP inspection and removal**: Parses binary JPEG (`APP1`..`APP15`, `COM`), PNG (`eXIf`, `tEXt`, `zTXt`, `iTXt`, `tIME`), and WebP chunks to display embedded GPS coordinates, camera model, serial numbers, and timestamps, then strips them on export.
- **Hash randomization and filename scrubbing**: Flips 1 LSB on 4 corner pixels during export so the output file gets a new SHA-256 hash, and renames the file to `PS_<8-hex>.jpg` instead of leaking timestamped camera/screenshot filenames.
- **Image redaction**: Supports 3-pass box blur (with subtle noise grain to prevent deconvolution), pixel mosaic, solid blackout bars, and tiled diagonal watermarks. Supports brush strokes, rectangle boxes, 2-finger pinch zoom, and undo/redo.

## Download

- **Latest APK (`v1.0.0`, 48 KB)**: [privsnap-1.0.0.apk](https://github.com/shrorse-ai/privsnap/releases/download/v1.0.0/privsnap-1.0.0.apk)
- **Obtainium**: Add `https://github.com/shrorse-ai/privsnap`
- **Minimum Android version**: Android 8.0 (API 26)

## Build & Test

The app is written in plain Java (5 source files in `app/src/main/java/io/privsnap/app/`) against the standard `android.jar` SDK:

```powershell
.\scripts\verify.ps1
```

## License

[MIT](LICENSE)
