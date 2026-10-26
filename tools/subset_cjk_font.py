#!/usr/bin/env python
"""Subset Noto Sans SC (variable, OFL 1.1) to the characters the app's Chinese copy uses.

Re-run whenever the zh strings change:

    python tools/subset_cjk_font.py [--source path/to/NotoSansSC-VF.ttf]

Output: app/src/main/res/font/noto_sans_sc_subset.ttf (the wght axis is kept) and
        third_party/noto-sans-sc/SUBSET.txt (what was included).
Latin letters and digits come from Noto Sans (res/font/noto_sans_*.ttf); this subset is its fallback.
"""
import argparse
import os
import re

from fontTools import subset
from fontTools.ttLib import TTFont

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
STRING_FILES = [
    os.path.join(ROOT, "app", "src", "main", "res", "values", "strings.xml"),
    os.path.join(ROOT, "app", "src", "main", "res", "values-zh", "strings.xml"),
]
EXTRA = (
    "，。、；：？！…—·（）《》“”‘’「」【】｜　～"
    "◆●○▲△■□✓←→↑↓°±×"
    "一二三四五六七八九十百千万零两个米厘毫盏灯"
    "暗偏合适亮不均桌子茶几沙发床地面台中间会墙太近边"
)
DEFAULT_SOURCE = r"C:\Users\Admin\AppData\Local\Temp\claude\C--Users-Admin-Downloads\052b92e7-9521-4995-bf0c-81b9e18249c3\scratchpad\font\NotoSansSC-VF.ttf"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--source", default=DEFAULT_SOURCE)
    args = ap.parse_args()
    chars = set(EXTRA)
    for f in STRING_FILES:
        with open(f, encoding="utf-8") as fh:
            text = re.sub(r"<[^>]+>", "", fh.read())
        chars.update(ch for ch in text if ord(ch) > 0x2000)
    chars = "".join(sorted(chars))
    out = os.path.join(ROOT, "app", "src", "main", "res", "font", "noto_sans_sc_subset.ttf")
    font = TTFont(args.source)
    opts = subset.Options()
    opts.flavor = None
    opts.layout_features = ["*"]
    opts.name_IDs = ["*"]
    opts.notdef_outline = True
    sub = subset.Subsetter(options=opts)
    sub.populate(text=chars)
    sub.subset(font)
    font.save(out)
    with open(os.path.join(ROOT, "third_party", "noto-sans-sc", "SUBSET.txt"), "w", encoding="utf-8") as fh:
        fh.write("Noto Sans SC variable font, subset to the characters of app/src/main/res/values*/strings.xml plus:\n" + EXTRA + "\n\nIncluded (%d):\n%s\n" % (len(chars), chars))
    print("subset written:", out, len(chars), "chars", os.path.getsize(out), "bytes")


if __name__ == "__main__":
    main()
