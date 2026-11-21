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

Rules: `id` must be unique and never change, `thumb` and `full` must start with `/files/`.
The app only works with the repository being public.
