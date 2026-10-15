#!/usr/bin/env python3
"""把图标 SVG（Figma 导出的 Frame 2.svg）转成 Android 自适应图标的前景与单色图层（VectorDrawable）。

SVG 里的 alpha 蒙版是“矩形只在弧线路径处可见”，等价于“弧线路径按矩形裁剪”，
VectorDrawable 不支持蒙版，用 <group> + <clip-path> 表达，结果一致。

用法：svg2adaptive.py <图标.svg> <res 目录>
"""
import re
import sys
import xml.etree.ElementTree as ET

NS = {"s": "http://www.w3.org/2000/svg"}
SVG, RES = sys.argv[1], sys.argv[2]
root = ET.parse(SVG).getroot()

# 把 512 × 512 原稿按几何中心放进 108dp 画布的 72dp 可见区，再缩小 5%、往右下各挪 1dp。
SCALE = 72 / 512 * 0.95
CX, CY = 256, 256
NUDGE_X, NUDGE_Y = 1.0, 1.0
TX, TY = 54 - CX * SCALE + NUDGE_X, 54 - CY * SCALE + NUDGE_Y

# 把单色图层的灰度换成不透明度，系统着色后仍能分出层次。
MONO_ALPHA = {"#727272": 1.0, "#C0C0C0": 0.55, "#D9D9D9": 0.4}


def rect_path(r):
    x, y = float(r.get("x", 0)), float(r.get("y", 0))
    w, h = float(r.get("width")), float(r.get("height"))
    return f"M{x},{y}h{w}v{h}h{-w}z"


masks = {m.get("id"): m for m in root.iter("{http://www.w3.org/2000/svg}mask")}
body = root.find("s:g", NS)  # clip-path="url(#clip0…)" 的外层组，即 0…512 画布本身


def elements(mono):
    out = []
    for el in body:
        tag = el.tag.split("}")[1]
        if tag == "path":
            d = el.get("d")
            if el.get("stroke"):
                color = el.get("stroke").upper()
                alpha = f' android:strokeAlpha="{MONO_ALPHA[color]}"' if mono else ""
                out.append(f'        <path android:pathData="{d}" android:strokeColor="{"#FF000000" if mono else color}"{alpha} '
                           f'android:strokeWidth="{el.get("stroke-width")}" />')
            else:
                color = el.get("fill").upper()
                alpha = f' android:fillAlpha="{MONO_ALPHA[color]}"' if mono else ""
                out.append(f'        <path android:pathData="{d}" android:fillColor="{"#FF000000" if mono else color}"{alpha} />')
        elif tag == "g" and el.get("mask"):
            mask = masks[re.search(r"#([^)]+)", el.get("mask")).group(1)]
            rect = el.find("s:rect", NS)
            color = rect.get("fill").upper()
            alpha = f' android:fillAlpha="{MONO_ALPHA[color]}"' if mono else ""
            out.append("        <group>")
            out.append(f'            <clip-path android:pathData="{rect_path(rect)}" />')
            for p in mask.findall("s:path", NS):
                out.append(f'            <path android:pathData="{p.get("d")}" android:fillColor="{"#FF000000" if mono else color}"{alpha} />')
            out.append("        </group>")
    return "\n".join(out)


def vector(mono):
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!-- 由 tools/icon/svg2adaptive.py 从图标 SVG 生成，不要手改 -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <group android:scaleX="{SCALE:.6f}" android:scaleY="{SCALE:.6f}" android:translateX="{TX:.3f}" android:translateY="{TY:.3f}">
{elements(mono)}
    </group>
</vector>
"""


open(f"{RES}/drawable/ic_launcher_foreground.xml", "w").write(vector(False))
open(f"{RES}/drawable/ic_launcher_monochrome.xml", "w").write(vector(True))
print("ok")
