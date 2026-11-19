# Zeta Board
1. Create a new GitHub repo, upload ALL files of this folder (keep the .github folder).
2. Open the Actions tab -> "Build Zeta Board APK" -> wait ~4 min.
3. Download the artifact "ZetaBoard-APK", install the APK.
4. Open the app -> Enable -> Select.

## Features
- Clipboard Manager (open it from the app home screen, or the clipboard icon on the keyboard)
- Update check every 15 minutes (background job + timer while the keyboard/app is running) and each time the keyboard opens (at most once every 4 minutes)
- Logo: replace `app/src/main/res/drawable-nodpi/app_logo.jpg` (square, 512x512) to change it
- Emoji panel: categories, recents, skin tones (hold), swipe pages. Key font + emoji font options are in the app home screen.

## App menu (three lines button)
- Tap the three-line button at the top right of the app home screen to open the category menu.
- Categories: Home (everything), Setup, Appearance, Typing & Fonts, Clipboard, Sticker Maker, Updates, About.
- **Sticker Maker** opens the in-app sticker screen (same engine as the keyboard sticker panel): Photo / Clipboard, history grid, tap a sticker to send it to WhatsApp, share it or delete it.

## Update notifications
- New version ready: heads-up notification that drops down from the top of the screen (sound + vibration) + warning banner at the top of the app + red dot on the menu button (checked every 15 minutes and whenever the keyboard opens).
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

## Zeta Translate

Type your message, select it, then tap the translate icon on the keyboard toolbar (or in the tool list). The selected text is replaced by its translation. With nothing selected, the whole message in the box is translated.

- Pick the language to translate into in the Zeta Board app -> Zeta Translate. The language you write in is detected automatically.
- Progress and problems show on the toolbar while it works. Tap that message to cancel.
- Text is sent to Google Translate's public web endpoint, or to MyMemory if Google cannot be reached, only when you tap the translate icon. Nothing is saved (no history, no keys). Password and private fields are never translated.
- Up to 4,000 characters per tap. It needs an internet connection.


## Voice typing

Tap the mic icon on the keyboard toolbar and speak. Each finished phrase is typed into the box, and it keeps listening until you tap the message on the toolbar to stop. The mic replaces the Theme icon on the default toolbar; Theme is now in the tool list (and you can pin any tool to the top bar yourself).

- Language is set in the Zeta Board app -> Voice Typing: Auto (Sinhala and English), Sinhala, or English. Auto lets the Google speech service switch between the two, so if it picks the wrong language, choose one language there.
- The first time, Android asks for the microphone permission (tap "Allow microphone" in the app, or tap the mic and allow it).
- Speech is handled by the Google speech service on the phone and needs an internet connection. Zeta Board saves nothing. Password and private fields never use voice typing.

## Instant update push (Firebase Cloud Messaging)

When a new version is published, every install gets a notification within seconds. The 15-minute check still runs as a fallback.

One-time setup:
1. Firebase Console -> project `zeta-b4e7c` -> Project settings -> Your apps -> **Add app -> Android**. Package name: `com.zeta.board`. Download `google-services.json` and put it in the `app/` folder (next to `app/build.gradle.kts`).
2. Firebase Console -> Project settings -> **Service accounts** -> *Generate new private key*. Open the downloaded JSON, copy ALL of it.
3. GitHub repo -> Settings -> Secrets and variables -> Actions -> **New repository secret**: name `FIREBASE_SERVICE_ACCOUNT`, value = the JSON. Never put this file in the repo.
4. Push to `main`. The workflow builds the APK, creates the release, then runs `.github/scripts/send_push.py`.

Notes:
- Add `[silent]` to a commit message to publish without notifying anyone.
- Phones that still have an older build get the push feature after they install this version through the normal 15-minute update check.
- Android does not deliver push to an app the user force-stopped; those phones fall back to the normal check.
