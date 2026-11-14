"""Bakes sRGB-to-sRGB film LUTs with spektrafilm for Film Sim's Spektrafilm brand.

spektrafilm by Andrea Volpato, https://github.com/andreavolpato/spektrafilm
Its profiles and the LUTs made from them are licensed CC BY-SA 4.0.

Run with spektrafilm's own Python environment, from a checkout of it:
    PYTHONPATH=src venv/bin/python /path/to/bake.py <output folder>

Three things differ from spektrafilm's own command line, and each LUT's header says so:
  - Exposure: camera white sits 2.47 stops above mid grey, so mid grey stays where the camera put it.
    (The tool's default for sRGB is 4 stops, which brightens everything by about a stop and a half.)
  - Slide films are scanned directly instead of being printed on paper.
  - The result is scaled so the paper or film base white reaches display white.
"""
import os, sys, shutil, tempfile, warnings
warnings.filterwarnings("ignore")

import spektrafilm.runtime.params_builder as pb
from spektrafilm_lut_creator import cli

STOPS = 2.47
WHITE = 0.97

# (film, print, output name). Stills negatives go on Kodak or Fuji paper, cine negatives on a cinema print stock.
JOBS = [
    ("kodak_portra_160", "kodak_portra_endura", "portra_160"),
    ("kodak_portra_400", "kodak_portra_endura", "portra_400"),
    ("kodak_portra_800", "kodak_portra_endura", "portra_800"),
    ("kodak_portra_800_push1", "kodak_portra_endura", "portra_800_push1"),
    ("kodak_portra_800_push2", "kodak_portra_endura", "portra_800_push2"),
    ("kodak_gold_200", "kodak_portra_endura", "gold_200"),
    ("kodak_ultramax_400", "kodak_portra_endura", "ultramax_400"),
    ("kodak_ektar_100", "kodak_portra_endura", "ektar_100"),
    ("fujifilm_c200", "fujifilm_crystal_archive_typeii", "fuji_c200"),
    ("fujifilm_pro_400h", "fujifilm_crystal_archive_typeii", "fuji_pro_400h"),
    ("fujifilm_xtra_400", "fujifilm_crystal_archive_typeii", "fuji_xtra_400"),
    ("fujifilm_provia_100f", "kodak_portra_endura", "provia_100f"),
    ("fujifilm_velvia_100", "kodak_portra_endura", "velvia_100"),
    ("kodak_ektachrome_100", "kodak_portra_endura", "ektachrome_100"),
    ("kodak_kodachrome_64", "kodak_portra_endura", "kodachrome_64"),
    ("kodak_vision3_50d", "kodak_2383", "vision3_50d_2383"),
    ("kodak_vision3_250d", "kodak_2383", "vision3_250d_2383"),
    ("kodak_vision3_200t", "kodak_2383", "vision3_200t_2383"),
    ("kodak_vision3_500t", "kodak_2383", "vision3_500t_2383"),
    ("kodak_vision3_250d", "kodak_2393", "vision3_250d_2393"),
    ("kodak_vision3_500t", "kodak_2393", "vision3_500t_2393"),
    ("kodak_verita_200d", "kodak_2383", "verita_200d_2383"),
]

# Slide film is a positive: look at the film itself rather than a print of it.
_init = pb.init_params
def init_params(*args, **kwargs):
    params = _init(*args, **kwargs)
    if params.film.is_positive:
        params.io.scan_film = True
    return params
pb.init_params = init_params


def rescale(src, dst, film, positive):
    head, rows = [], []
    for line in open(src):
        t = line.strip()
        if not t or t[0] == "#" or t[0].isalpha():
            head.append(line.rstrip("\n"))
        else:
            rows.append([float(x) for x in t.split()])
    white = max(rows[-1])  # last entry of a cube is input 1, 1, 1
    k = WHITE / white if white > 0 else 1.0
    note = [
        "# Modified for Film Sim (github.com/jegly/sonyalpha-film) by jegly:",
        "#   exposure set to %.2f stops above mid grey;%s" % (STOPS, " slide film scanned, not printed;" if positive else ""),
        "#   output scaled by %.4f so base white reaches %.2f." % (k, WHITE),
        "# Derived from spektrafilm by Andrea Volpato, https://github.com/andreavolpato/spektrafilm, CC BY-SA 4.0.",
    ]
    with open(dst, "w") as f:
        comments = [h for h in head if h.startswith("#")]
        others = [h for h in head if not h.startswith("#")]
        f.write("\n".join(comments + note + others) + "\n")
        for r in rows:
            f.write("%.6f %.6f %.6f\n" % tuple(min(1.0, v * k) for v in r))
    return white, k


def main():
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    for film, paper, name in JOBS:
        tmp = tempfile.mkdtemp()
        spec = os.path.join(tmp, "spec.toml")
        with open(spec, "w") as f:
            f.write('film_profile = "%s"\nprint_profiles = ["%s"]\ninput_color_space = "sRGB"\n'
                    'output_color_space = "sRGB"\nresolution = 33\nstops_above_midgray = %s\nname = "%s"\n'
                    % (film, paper, STOPS, name))
        code = cli.main(["build", "--from", spec, "--out", tmp])
        cube = [os.path.join(tmp, name, c) for c in os.listdir(os.path.join(tmp, name)) if c.endswith(".cube")][0]
        positive = _init(film_profile=film, print_profile=paper).film.is_positive
        white, k = rescale(cube, os.path.join(out, "spk_" + name + ".cube"), film, positive)
        lic = os.path.join(tmp, name, "SPEKTRAFILM_LICENSE.txt")
        if os.path.exists(lic):
            shutil.copy(lic, os.path.join(out, "SPEKTRAFILM_LICENSE.txt"))
        print("%-22s white %.3f scaled x%.3f%s" % (name, white, k, "  (slide, scanned)" if positive else ""))
        shutil.rmtree(tmp)


if __name__ == "__main__":
    main()
