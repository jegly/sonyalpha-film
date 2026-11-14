# To do

## Spektrafilm looks are way too contrasty (next session)

The 22 looks in the Spektrafilm brand need changing before they are usable. Judged on full-size renders of a
real photo, they are far too contrasty: shadows crush to black and the subject turns into a silhouette.

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

Until this is fixed, Strength at 50% or lower is the workaround on the camera.

Also still open:

- v1.10.8 is committed but has not been installed or tested on the camera (the camera has v1.10.6). New in it:
  the grain texture, Auto grain, and the Spektrafilm brand.
- All Spektrafilm looks rate one star. The fit keeps the tone but loses colour richness.
- Setting up on a new computer: the Spektrafilm LUTs are not in this repo. Clone spektrafilm, install its
  Python packages, and run `tools/spektrafilm/bake.py` (see the top of that file and the README).
