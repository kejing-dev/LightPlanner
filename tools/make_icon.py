"""Light Planner icon: the PICO 3D icon layers and the 512 export, drawn from the same shapes as the
adaptive icon (`res/drawable/ic_launcher_foreground.xml` on `@color/ic_launcher_background`).

    python tools/make_icon.py [export.png]

Layer 0: the plum tile. Layer 1: ceiling line + half-round downlight + the light pool (design appIcon).
sdf_N = the layer's alpha in white. Coordinates below are the vector's 108-unit viewport.
"""
import os
import sys
from PIL import Image, ImageDraw

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
RES = os.path.join(ROOT, "app", "src", "main", "res")
S = 1024
K = S / 108.0
PLUM = (0x4A, 0x2D, 0x5E, 255)
GLOW = (0xFF, 0xD5, 0x8A, 255)
GLOW70 = (0xFF, 0xD5, 0x8A, 179)


def tile():
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(im).rounded_rectangle((40, 40, S - 40, S - 40), radius=220, fill=PLUM)
    return im


def front():
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rectangle((30 * K, 30 * K, 78 * K, 33.2 * K), fill=GLOW)
    # half disc hanging under the ceiling line: centre (54, 33.2), radius 11, lower half
    d.pieslice((43 * K, 22.2 * K, 65 * K, 44.2 * K), 0, 180, fill=GLOW)
    pool = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(pool).ellipse((33 * K, 63.5 * K, 75 * K, 80.5 * K), fill=GLOW70)
    return Image.alpha_composite(im, pool)


def alpha_white(im):
    w = Image.new("RGBA", im.size, (255, 255, 255, 0))
    w.putalpha(im.split()[3])
    return w


def main():
    l0, l1 = tile(), front()
    draw = os.path.join(RES, "drawable")
    l0.save(os.path.join(draw, "icon_3d_layer_0.png"))
    l1.save(os.path.join(draw, "icon_3d_layer_1.png"))
    alpha_white(l0).save(os.path.join(draw, "icon_3d_sdf_0.png"))
    alpha_white(l1).save(os.path.join(draw, "icon_3d_sdf_1.png"))
    if len(sys.argv) > 1:
        full = Image.new("RGBA", (S, S), PLUM)
        Image.alpha_composite(full, l1).resize((512, 512), Image.LANCZOS).save(sys.argv[1])
    print("icon layers written")


if __name__ == "__main__":
    main()
