#!/usr/bin/env python3
"""迭代求图标的缩放与中心：模糊后的视觉重心落在画布中心，且最远点不超出 66dp 安全区（留 0.5dp 余量）。
渲染用 macOS 的 qlmanage，测量用 optical_center.swift。结果写回 svg2adaptive.py 的 SCALE、CX、CY。
用法：fit_icon.py <图标.svg> <临时目录>"""
import re, subprocess, sys, os
SVG, TMP = sys.argv[1], sys.argv[2]
HERE = os.path.dirname(os.path.abspath(__file__))
CONV = os.path.join(HERE, 'svg2adaptive.py')
w = open(SVG).read(); inner = w[w.index('>') + 1:w.rindex('</svg>')]
src = open(CONV).read()
s = float(re.search(r'^SCALE = ([\d.]+)', src, re.M).group(1))
cx, cy = [float(v) for v in re.search(r'^CX, CY = ([\d.]+), ([\d.]+)', src, re.M).groups()]
TARGET_R = 32.5

def measure(s, cx, cy):
    path = f'{TMP}/fit.svg'
    open(path, 'w').write(f'<svg xmlns="http://www.w3.org/2000/svg" width="864" height="864" viewBox="0 0 108 108">'
                          f'<rect width="108" height="108" fill="#FFFFFF"/><g fill="none" transform="translate({54-cx*s} {54-cy*s}) scale({s})">{inner}</g></svg>')
    png = path + '.png'
    if os.path.exists(png): os.remove(png)
    subprocess.run(['qlmanage', '-t', '-s', '864', '-o', TMP, path], capture_output=True)
    out = subprocess.run(['swift', os.path.join(HERE, 'optical_center.swift'), png], capture_output=True, text=True).stdout
    bx, by = [float(v) for v in re.search(r'模糊后重心: \(([\d.]+), ([\d.]+)\)', out).groups()]
    r = float(re.search(r'最远点离中心 ([\d.]+) dp', out).group(1))
    return bx, by, r, out

for i in range(5):
    bx, by, r, out = measure(s, cx, cy)
    print(f'第 {i+1} 轮：scale={s:.4f} center=({cx:.1f},{cy:.1f}) 重心偏离 ({bx-54:+.2f},{by-54:+.2f})dp 最远 {r:.2f}dp')
    if abs(bx - 54) < 0.25 and abs(by - 54) < 0.25 and abs(r - TARGET_R) < 0.3:
        break
    # 把内容中心朝重心偏移的方向挪，再按最远距离调整缩放。
    cx += (bx - 54) / s
    cy += (by - 54) / s
    s *= TARGET_R / r
print(out)
src = re.sub(r'^SCALE = [\d.]+', f'SCALE = {s:.4f}', src, flags=re.M)
src = re.sub(r'^CX, CY = [\d.]+, [\d.]+', f'CX, CY = {cx:.1f}, {cy:.1f}', src, flags=re.M)
open(CONV, 'w').write(src)
