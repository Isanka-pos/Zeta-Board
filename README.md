# Zeta Board
1. Create a new GitHub repo, upload ALL files of this folder (keep the .github folder).
2. Open the Actions tab -> "Build Zeta Board APK" -> wait ~4 min.
3. Download the artifact "ZetaBoard-APK", install the APK.
4. Open the app -> Enable -> Select.

## Features
- Clipboard Manager (open it from the app home screen, or the clipboard icon on the keyboard)
- Update check every 45 minutes (background job + timer while the keyboard/app is running)
- Logo: replace `app/src/main/res/drawable-nodpi/app_logo.jpg` (square, 512x512) to change it
- Emoji panel: categories, recents, skin tones (hold), swipe pages. Key font + emoji font options are in the app home screen.

## Sticker Maker
- Tap the sticker icon (left of the clipboard icon) on the keyboard toolbar.
- **Photo** opens the picker, **Clipboard** uses an image you copied. You can also use Share -> "Zeta Sticker" from the gallery.
- The photo is uploaded to catbox.moe, converted by the wolvarex img-to-sticker API, normalised to 512x512 WebP (<100 KB) and shown first in the grid. Tap to send, hold to delete.
- If the API is unreachable, a sticker is still made on the phone (offline mode).
- API key: defaults to the key in app/build.gradle.kts. To keep it out of the repo, add a GitHub secret named STICKER_KEY (the workflow passes it to the build).
