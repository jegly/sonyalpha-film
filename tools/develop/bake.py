"""Bakes sRGB-to-sRGB film LUTs with Develop's engine for Film Sim's Develop brand.

Develop (github.com/jegly/Develop) is a Linux fork of SpektraLab, whose engine is derived from spektrafilm by
Andrea Volpato. Its film catalogue has 123 films: spektrafilm's measured profiles plus 93 more built from the
makers' published data sheets. spektrafilm's profiles and the LUTs made from them are licensed CC BY-SA 4.0.

Run with a Python that has numpy, against a built Develop checkout:
    DEVELOP_ROOT=~/Documents/spektralab-linux python3 bake.py <output folder> [film_id ...]

Each film is developed as Develop develops it by default: a negative printed on its catalogue paper (still
negatives on their colour paper, cine negatives on Kodak 2383, black-and-white films on Ilford Multigrade),
a slide scanned directly. Everything that spreads light between pixels (grain, halation, glare, blurs) is off,
so each LUT entry is its own colour; Film Sim adds grain and halation itself after the shot.

The same three changes as tools/spektrafilm/bake.py, so the two brands sit together on the camera:
  - Exposure: the camera's picture is taken as scene light as it is (mid grey at sRGB 0.46 stays mid grey).
  - The result is scaled so the paper or film base white reaches display white.
  - The grey curve is blended back towards a straight line (BLEND) and the blacks are lifted (FLOOR). The camera
    hands the app a finished picture that already has contrast, so the film's own curve on top of it is too
    dark and contrasty. Colour differences are kept; only the tone is softened.
Each LUT's header says so.
"""
import json
import os
import sys
from pathlib import Path

import numpy as np

ROOT = Path(os.environ.get("DEVELOP_ROOT", Path.home() / "Documents" / "spektralab-linux")).expanduser()
sys.path.insert(0, str(ROOT / "engine" / "tests"))
import spk_ctypes as spk  # noqa: E402

N = 33
WHITE = 0.97
BLEND = 0.45   # 1 = the film's own grey curve, 0 = a straight line
FLOOR = 0.025  # lifted black point

# Nothing that spreads light. Each LUT entry is developed as its own PATCH x PATCH square and read at its
# centre, out of reach of the scanner's sharpening (about two pixels), which cannot be switched off; the
# film is 300 mm wide so a patch is far larger than the DIR couplers' diffusion.
PATCH = 7
QUIET = {
    "grain_active": False, "grain_sublayers_active": False, "halation_active": False, "glare_active": False,
    "lens_blur_um": 0.0, "scanner_lens_blur": 0.0, "film_format_mm": 300.0,
    "overscan_active": False, "date_imprint_active": False, "scene_latitude_active": False,
    "contrast_mask_active": False, "auto_exposure": False, "exposure_compensation_ev": 0.0,
}
IO = {"input_color_space": "sRGB", "input_cctf_decoding": True,
      "output_color_space": "sRGB", "output_cctf_encoding": True, "product_defaults": True}


def catalogue():
    stocks = json.load(open(ROOT / "app" / "SpektraLab" / "Resources" / "StockCatalog.json"))["stocks"]
    return [s for s in stocks if s["stage"] == "filming"]


def silver_pack():
    """Multigrade's grade 2, the enlarger pack Develop sends for a black-and-white print (gen_catalog.py
    `neutralPack`): the engine's own table has no row for a silver paper."""
    g = json.load(open(ROOT / "engine" / "resources_product" / "paper_grades.json"))["grades"]["2"]
    return {"c_filter_neutral": 0.0, "m_filter_neutral": g["m_filter_neutral"],
            "y_filter_neutral": g["y_filter_neutral"], "print_exposure": g["print_exposure"]}


def wire(s):
    """Develop's default settings for a film (desktop/src/shared/params.ts `heldToItsFilm` and `wire`)."""
    if s["type"] == "positive":
        return {"film_stock": s["id"], "scan_film": True}
    w = {"film_stock": s["id"], "print_stock": s["targetPrint"], "scan_film": False}
    if s["kind"].startswith("b&w"):
        w.update(silver_pack())
    return w


def grid():
    """The 33^3 cube's inputs in .cube order (red fastest), each a PATCH x PATCH square: N rows of N*N."""
    v = np.linspace(0.0, 1.0, N, dtype=np.float32)
    b, g, r = np.meshgrid(v, v, v, indexing="ij")
    cells = np.stack([r, g, b], axis=-1).reshape(N, N * N, 3)
    return np.repeat(np.repeat(cells, PATCH, axis=0), PATCH, axis=1)


def develop(engine, s, image):
    """The LUT's outputs, one per entry in .cube order: each patch's centre pixel."""
    with_s = engine.open(image, {**IO, **QUIET, **wire(s)})
    try:
        rgba, _ = with_s.render("full")
    finally:
        with_s.close()
    c = PATCH // 2
    return rgba[c::PATCH, c::PATCH, :3].astype(np.float64).reshape(-1, 3) / 65535.0


def soften(a):
    """Per-channel tone correction: the grey ramp ends up BLEND of the way from a straight line to the film's."""
    f = np.maximum.accumulate(a[[i * (1 + N + N * N) for i in range(N)]].mean(axis=1))
    x = np.linspace(0, 1, N)
    f = f + x * 1e-6  # strictly increasing so it can be inverted
    h = FLOOR + (BLEND * f + (1 - BLEND) * x) * (1 - FLOOR)
    return np.interp(a, f, h)


def write(path, s, rows, white, k):
    scanned = s["type"] == "positive"
    how = "scanned directly" if scanned else "printed on " + s["targetPrint"]
    head = [
        "# %s, developed by Develop's engine (github.com/jegly/Develop): %s." % (s["name"], how),
        "# Modified for Film Sim (github.com/jegly/sonyalpha-film) by jegly:",
        "#   no grain, halation, glare or blur (Film Sim adds its own after the shot);",
        "#   output scaled so base white reaches %.2f (from %.3f, x%.3f);" % (WHITE, white, k),
        "#   grey curve blended %.0f%% towards the film curve, black lifted to %.3f." % (BLEND * 100, FLOOR),
        "# Develop's engine is derived from spektrafilm by Andrea Volpato, https://github.com/andreavolpato/spektrafilm;",
        "# its profiles and LUTs made from them are CC BY-SA 4.0.",
        'TITLE "%s"' % s["name"],
        "LUT_3D_SIZE %d" % N,
    ]
    with open(path, "w") as f:
        f.write("\n".join(head) + "\n")
        for r in rows:
            f.write("%.6f %.6f %.6f\n" % tuple(r))


def main():
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    only = set(sys.argv[2:])
    engine = spk.Engine(library=ROOT / "build" / "linux" / "engine" / "spektralab_engine.so",
                        resources=ROOT / "build" / "host-linux-x64" / "engine")
    image = grid()
    for s in catalogue():
        if only and s["id"] not in only:
            continue
        rows = develop(engine, s, image)
        white = float(rows[-1].max())  # the last entry is input 1, 1, 1
        k = WHITE / white if white > 0 else 1.0
        rows = soften(np.minimum(1.0, rows * k))
        write(out / ("dev_%s.cube" % s["id"]), s, rows, white, k)
        print("%-36s white %.3f x%.3f  %s" % (s["id"], white, k, "scanned" if s["type"] == "positive" else s["targetPrint"]))


if __name__ == "__main__":
    main()
