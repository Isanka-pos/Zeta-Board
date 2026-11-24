Zeta Board - changed and new files only

NEW
  app/src/main/java/com/zeta/board/Stats.kt        anonymous usage stats client (off until stats_url is set in Remote Config)
  .github/workflows/apply-zip.yml   auto-unpacks a zip uploaded to updates/ and releases the full update
  updates/README.txt                the folder you upload zips into

CHANGED
  app/src/main/java/com/zeta/board/RemoteCfg.kt   new_updates_on switch
  app/src/main/java/com/zeta/board/Updater.kt     holds back updates while new_updates_on = false
  app/src/main/java/com/zeta/board/Push.kt        ignores the instant update push while held
  app/src/main/java/com/zeta/board/ThemeStoreActivity.kt, WallpaperStore.kt, KeyboardHost.kt   usage events (theme, wallpaper, tool)
  REMOTE_CONFIG.md, CHANGELOG.txt
  .github/workflows/build.yml       ignores updates/ pushes; manual run has "full_only" and "silent" switches
  .github/scripts/make_patches.py   full_only support
  wallpapers/wallpapers.json        "Anime" category removed (Shinobi is now under "Art")

HOW TO UPDATE FROM NOW ON
  1. First time only: upload the 3 workflow/script files above (normal way).
  2. After that: GitHub -> updates/ folder -> Add file -> Upload files -> drop the new zip -> Commit.
  3. Wait ~5 min. A new release (full APK) is built and users get the update.
  Manual full release: Actions -> Build Zeta Board APK -> Run workflow -> tick "Full update only".

RELEASE WITH THE SWITCH (Firebase console -> Remote Config)
  1. Add key new_updates_on = false (Boolean) and Publish. Install this build once (normal update).
  2. From then on push as many times as you like: releases are built but phones are not told.
  3. When you are ready: set new_updates_on = true and Publish. Phones see the newest version within ~15 min of opening the app/keyboard.
