# To do

## Spektrafilm looks are way too dark and contrasty (next session)

The 22 looks in the Spektrafilm brand need changing before they are usable. Judged on full-size renders of a
real photo, they are far too dark and contrasty: shadows crush to black and the subject turns into a silhouette.
The user confirmed this again on 2026-11-14 (new computer): "too dark and contrasty". Fix both together, and
check overall brightness as well as contrast (mid grey and the shadows should not sink).

Why: spektrafilm expects a flat, unprocessed picture, and the camera hands the app a finished one that already
has its own contrast curve. The film and print curves then go on top of that, so contrast is applied twice.

Things to try, in `tools/spektrafilm/bake.py`:

- Undo the camera's own tone curve before the film model, so the simulation sees something closer to scene
  light. For example treat the input as a flatter encoding than sRGB, or apply an inverse S-curve to the grid.
- Keep the simulation's colour but soften its tone: blend the baked LUT's tone curve part of the way back to
  a straight line while leaving the colour differences alone.
- Lift the black point. Mid grey is pinned at 0.50 and paper white is scaled to 0.97, but nothing protects
  the shadows (input 0.25 comes out at 0.16).
- A softer print stock where there is a choice (Portra Endura is the contrastiest of the papers tried).
- Check each change on the test photos at full size, not on contact sheets.

UPDATE 2026-11-14: bake.py now blends the grey curve 45% towards the film curve and lifts the black to 0.025
(BLEND and FLOOR at the top). The LUTs were rebaked into _shared/spektrafilm-luts (old ones in
_shared/spektrafilm-luts-old) and look much better on JES05348. STILL TO DO: run tools ConvertFilms on them,
rebuild, install, judge on the camera, and tune BLEND/FLOOR if needed.

Until this is fixed, Strength at 50% or lower is the workaround on the camera.

Also still open:

- v1.10.8 is committed but has not been installed or tested on the camera (the camera has v1.10.6). New in it:
  the grain texture, Auto grain, and the Spektrafilm brand.
- All Spektrafilm looks rate one star. The fit keeps the tone but loses colour richness.
- Setting up on a new computer: the Spektrafilm LUTs are not in this repo. Clone spektrafilm, install its
  Python packages, and run `tools/spektrafilm/bake.py` (see the top of that file and the README).

## Done 2026-11-14 (v1.10.9, built, installed only up to the playback fix)

- Spektrafilm looks softened (BLEND 0.45, FLOOR 0.025 in tools/spektrafilm/bake.py); the user likes them now.
- Playback in the app showed RAW shots lighter than the camera's own playback: Sony's viewer renders a RAW afresh
  without the look. Now plain RAW playback shows the preview stored in the ARW (flash of the light version first).
- The Kelvin row says "off" unless WB is Kelvin.
- Shots taken inside the app were compressed RAW (~24.8 MB) while the camera menu said uncompressed; one shot
  outside the app was 49 MB. New Fn row "RAW format" sets the mode through setRawRecMode (default Uncompressed).
  NOT yet tried on the camera: check the log for "raw rec modes" and "raw rec mode set", and ARW sizes.
- The identity look pass is skipped in plain RAW playback (it cost ~400 ms).
