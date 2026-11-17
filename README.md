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

## App menu (three lines button)
- Tap the three-line button at the top right of the app home screen to open the category menu.
- Categories: Home (everything), Setup, Appearance, Typing & Fonts, Clipboard, Sticker Maker, Updates, About.
- **Sticker Maker** opens the in-app sticker screen (same engine as the keyboard sticker panel): Photo / Clipboard, history grid, tap a sticker to send it to WhatsApp, share it or delete it.

## Update notifications
- New version ready: push notification + warning banner at the top of the app + red dot on the menu button (checked every 45 minutes).
- After the update is installed: "Zeta Board updated" notification and a "What's new" screen on the next start.

## Sticker Maker
- Tap the sticker icon (left of the clipboard icon) on the keyboard toolbar.
- **Photo** opens the picker, **Clipboard** uses an image you copied. You can also use Share -> "Zeta Sticker" from the gallery.
- The photo is uploaded to catbox.moe, converted by the wolvarex img-to-sticker API, normalised to 512x512 WebP (<100 KB) and shown first in the grid. Tap to send, hold to delete.
- If the API is unreachable, a sticker is still made on the phone (offline mode).
- API key: defaults to the key in app/build.gradle.kts. To keep it out of the repo, add a GitHub secret named STICKER_KEY (the workflow passes it to the build).

## Privacy
- Android 14+: when a screenshot is taken, a warning banner slides in from the top and a heads-up notification is shown (the banner always works; the notification needs the notification permission). This cannot be switched off.
- Android 13 and older have no screenshot detection, so every screen and dialog uses FLAG_SECURE and screenshots / screen recording are blocked there.
- Android never reports a screenshot of a FLAG_SECURE window, so blocking and the warning cannot run together.
