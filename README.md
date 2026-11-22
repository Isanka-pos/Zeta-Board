# Zeta Board
1. Create a new GitHub repo, upload ALL files of this folder (keep the .github folder).
2. Open the Actions tab -> "Build Zeta Board APK" -> wait ~4 min.
3. Download the artifact "ZetaBoard-APK", install the APK.
4. Open the app -> Enable -> Select.

## Wallpapers (Theme Store)

The Theme Store has a **Wallpapers** tab. It lists pictures from your own wallpaper server (folder `zeta-wallpaper-server`, upload them from its `/admin` web page). Tap a wallpaper to preview it behind the keys, then **Apply**: it becomes the keyboard background, the same one **Choose photo** sets. **Reset** removes it. The list is saved on the phone, so it still opens offline.

One-time setup:
1. Deploy `zeta-wallpaper-server` (see its README) on an **https** address.
2. GitHub repo -> Settings -> Secrets and variables -> Actions -> **Variables** -> New repository variable `WALLPAPER_URL` = your address (for example `https://wallpapers.yourdomain.com`). Or edit `wallpaperUrl` in `app/build.gradle.kts`.
3. Push to `main` to build a new APK.

## Emoji Mix
Keyboard tool list -> **Emoji Mix** (hold and drag it onto the top bar to pin it). Pick two emoji; the keyboard calls your Emoji Mix API (`/api/emoji/mix?a=..&b=..`) and shows the chemistry score, verdict, fused name and ready-made mixes. Tap one to insert it.

One-time setup:
1. GitHub repo -> Settings -> Secrets and variables -> Actions -> **New repository secret**: name `EMOJIMIX_KEY`, value = your API key (the `x-api-key` value).
2. Push to `main` to build a new APK. Without the key the panel opens but shows "Emoji Mix is not set up yet".
3. The API address defaults to `https://isanka-api.vercel.app`. To change it, pass `-PemojimixUrl=...` or edit `emojimixUrl` in `app/build.gradle.kts`.
Note: the key is compiled into the APK, so anyone who unpacks the APK can read it. Keep rate limits on that key.

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
- Add `[silent]` to a commit message to publish without sending the instant push (the 15-minute check on phones can still notify).
- To stop the "Update available" notification completely, set the Remote Config key `update_alerts_on` to `false` (see REMOTE_CONFIG.md). It can be turned back on any time, no new APK needed.
- Phones that still have an older build get the push feature after they install this version through the normal 15-minute update check.
- Android does not deliver push to an app the user force-stopped; those phones fall back to the normal check.

## Announcements ("new feature coming")
GitHub -> Actions -> **Send announcement** -> Run workflow. Fill in Title, Message and (optional) Link. Every install gets it as a notification; tapping opens the link, or the app when no link is given. Uses the same `FIREBASE_SERVICE_ACCOUNT` secret. Users can mute these separately from update alerts (channel "News & announcements"). Only phones already on this version or newer receive them.

## Status Saver
Keyboard tool list -> **Status Saver** (hold and drag it onto the top bar to pin it). Add the WhatsApp (or WhatsApp Business) status folder once through Android's folder picker, then tap statuses to select them and **Save** (gallery, folder "Zeta Board"), **Send** (into the chat box, falls back to the share sheet when the app does not accept media) or **Share**.
Notes: only statuses already viewed in WhatsApp exist on the phone; saved files are never duplicated; the panel blocks screenshots like the clipboard panel.

## Singlish typing
Keyboard tool list -> **Singlish** (or the Singlish card in the app, menu -> Typing & Fonts). Letters typed on the English layer become Sinhala as you type; the word stays underlined until you type a space, punctuation, Enter or move the cursor. Backspace removes one typed letter and redraws the word.

| Type | Get | Type | Get |
|---|---|---|---|
| a aa ae aae | අ ආ ඇ ඈ | i ii u uu | ඉ ඊ උ ඌ |
| e ee ai | එ ඒ ඓ | o oo au | ඔ ඕ ඖ |
| k kh g gh | ක ඛ ග ඝ | ch / c, chh | ච ඡ |
| j jh | ජ ඣ | t T d D | ට ඨ ද ඩ |
| th Th dh Dh | ත ථ ධ ඪ | n N | න ණ |
| p ph b bh m | ප ඵ බ භ ම | y r l L w | ය ර ල ළ ව |
| s sh S | ස ශ ෂ | h f | හ ෆ |
| ng mb gn jn | ඟ ඹ ඥ ඤ | x H | ං ඃ |

A vowel after a consonant gives its sign (ka ක, kaa කා, ki කි, ke කෙ). A consonant followed by a consonant gets the hal sign (amma අම්මා). Consonant + r / y joins as rakaransaya / yansaya (kriyaa ක්‍රියා, vidyaa විද්‍යා). A word ending in `nawa` gets the long final vowel (yanawa යනවා).

Notes: no automatic capital letter while Singlish is on (capitals pick special letters); words that start with a dot (Quick Text shortcuts) are typed as plain letters; it is rule based, not a dictionary, so some words need doubled vowels.

## Play Protect warning when installing
Android shows "Google Play Protect - App scan recommended" for any APK that is not from the Play Store and that Google has not seen before. It is not a sign that something is wrong. Tap **Scan app** (or More details -> Install without scanning) and then **Install**. This text is added to every GitHub release automatically (`.github/INSTALL_HELP.md`) and is shown in the in-app update dialog. The app cannot show it before the very first install, so send new users to the release page or share the text above. Publishing on the Play Store is the only way to make the message go away for everyone.
