# Remote Config (Firebase console -> Run / DevOps and engagement -> Remote Config)

Change these without releasing a new APK. After **Publish changes**, phones pick them up within ~15 minutes
(the next time the app or keyboard opens after that). Every key is optional: if it is missing the app uses its built-in value.

| Key | Type | Default | What it does |
|---|---|---|---|
| `min_version_code` | Number | `0` | Phones older than this version code see an update dialog with no "Later" button. 0 = off. Version code = the number after `1.0.` (e.g. v1.0.57 -> 57) |
| `tool_sticker_on` | Boolean | `true` | Stickers tool |
| `tool_clipboard_on` | Boolean | `true` | Clipboard tool |
| `tool_quicktext_on` | Boolean | `true` | Quick Text tool |
| `tool_translate_on` | Boolean | `true` | Translate tool |
| `tool_voice_on` | Boolean | `true` | Voice typing |
| `tool_status_on` | Boolean | `true` | Status Saver |
| `tool_singlish_on` | Boolean | `true` | Singlish toggle tool |
| `tool_emojimix_on` | Boolean | `true` | Emoji Mix tool |
| `tool_off_message` | String | `This tool is paused for now. Please try again later.` | Text shown on the toolbar when someone taps a switched-off tool (max 80 chars) |
| `emojimix_url` | String | empty | Emoji Mix API address (https only). Empty = the one built into the app |
| `wallpaper_url` | String | empty | Wallpaper server address (https only). Empty = the built-in one / GitHub folder |
| `perf_on` | Boolean | `true` | `false` switches Firebase Performance monitoring off for everyone |
| `banner_lines` | String | empty | Hacker-theme typing banner lines, comma separated, e.g. `ZETA_BOARD,wake_up_neo,root@zeta`. Letters, digits and `_ @ . -` only, 11 characters each, up to 12 lines. Empty = built-in lines |

Settings, Theme, Text style and Emoji can never be switched off.
Only the people who can sign in to your Firebase project can change these values.
