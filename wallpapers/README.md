# Online wallpapers

Zeta Board downloads its online wallpapers from this folder (no server needed).
Open the keyboard app -> Theme Store -> Wallpapers: they show there, and **Apply** downloads one and makes it the keyboard background.

## Add a wallpaper
1. Put the picture in `files/` (for example `files/ocean.jpg`, under 16 MB).
2. Put a small preview in `files/` too (for example `files/ocean_thumb.jpg`, about 360 px wide, under 2 MB).
3. Add one entry to `wallpapers.json`:

```json
{ "id": "ocean", "name": "Ocean", "category": "Nature",
  "thumb": "/files/ocean_thumb.jpg", "full": "/files/ocean.jpg",
  "width": 1080, "height": 1920, "size": 350000 }
```

4. Commit and push to the `main` branch. Phones pick it up the next time the Wallpapers tab opens (GitHub can take a few minutes to refresh).

## Add a live (video) wallpaper
Same steps, but `full` is an `.mp4` and the entry gets `"type": "video"` and `"category": "Live"`:

```json
{ "id": "live_ocean", "name": "Ocean", "category": "Live", "type": "video",
  "thumb": "/files/live_ocean_thumb.jpg", "full": "/files/live_ocean.mp4",
  "width": 606, "height": 1080, "size": 4500000 }
```

Use H.264 video, a short muted loop (10 to 30 seconds), under 60 MB. The thumbnail is a still picture from the video.
Store tiles of live wallpapers show a LIVE tag, and **Apply** makes the video the keyboard background.

Rules: `id` must be unique and never change, `thumb` and `full` must start with `/files/`.
The app only works with the repository being public.
