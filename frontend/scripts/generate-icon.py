#!/usr/bin/env python3
"""
Lavis Icon Generator - 生成高级优雅的 L 图标
设计理念：深色背景 + 金色 L，简约现代
"""

import subprocess
import os
from pathlib import Path

# 配置
BUILD_DIR = Path(__file__).parent.parent / "build"
ICONSET_DIR = BUILD_DIR / "icon.iconset"

# 设计参数
BACKGROUND_COLOR = "#1a1a1a"  # 深灰近黑
L_COLOR = "#d4a853"           # 优雅金色（琥珀金）
CORNER_RADIUS_RATIO = 0.22    # 圆角比例（macOS 风格）

# 所需尺寸
SIZES = [
    (16, 1), (16, 2),
    (32, 1), (32, 2),
    (64, 1), (64, 2),
    (128, 1), (128, 2),
    (256, 1), (256, 2),
    (512, 1), (512, 2),
    (1024, 1),
]

def generate_svg(size: int) -> str:
    """生成 SVG 图标"""
    corner_radius = size * CORNER_RADIUS_RATIO

    # L 的尺寸和位置（居中偏左上一点，视觉平衡）
    padding = size * 0.22
    l_width = size * 0.45
    l_height = size * 0.56
    stroke_width = size * 0.12

    # L 的起点（左上角）
    l_x = padding
    l_y = padding

    # L 的路径：从上往下，再往右
    # 使用圆角连接
    l_path = f"""
    M {l_x + stroke_width/2} {l_y}
    L {l_x + stroke_width/2} {l_y + l_height - stroke_width/2}
    L {l_x + l_width} {l_y + l_height - stroke_width/2}
    """

    svg = f'''<?xml version="1.0" encoding="UTF-8"?>
<svg width="{size}" height="{size}" viewBox="0 0 {size} {size}" xmlns="http://www.w3.org/2000/svg">
  <defs>
    <!-- 微妙的渐变，增加质感 -->
    <linearGradient id="bgGrad" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" style="stop-color:#242424"/>
      <stop offset="100%" style="stop-color:#1a1a1a"/>
    </linearGradient>

    <!-- L 的金色渐变 -->
    <linearGradient id="lGrad" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" style="stop-color:#e8c068"/>
      <stop offset="50%" style="stop-color:#d4a853"/>
      <stop offset="100%" style="stop-color:#c49a48"/>
    </linearGradient>

    <!-- 内阴影效果 -->
    <filter id="innerShadow" x="-50%" y="-50%" width="200%" height="200%">
      <feGaussianBlur in="SourceAlpha" stdDeviation="{size * 0.02}" result="blur"/>
      <feOffset dx="{size * 0.01}" dy="{size * 0.01}"/>
      <feComposite in2="SourceAlpha" operator="arithmetic" k2="-1" k3="1"/>
      <feColorMatrix type="matrix" values="0 0 0 0 0  0 0 0 0 0  0 0 0 0 0  0 0 0 0.3 0"/>
      <feBlend in2="SourceGraphic"/>
    </filter>
  </defs>

  <!-- 背景圆角矩形 -->
  <rect x="0" y="0" width="{size}" height="{size}" rx="{corner_radius}" ry="{corner_radius}" fill="url(#bgGrad)"/>

  <!-- L 字母 -->
  <path d="{l_path.strip()}"
        fill="none"
        stroke="url(#lGrad)"
        stroke-width="{stroke_width}"
        stroke-linecap="round"
        stroke-linejoin="round"
        filter="url(#innerShadow)"/>
</svg>'''
    return svg


def main():
    # 确保目录存在
    ICONSET_DIR.mkdir(parents=True, exist_ok=True)

    print("🎨 Generating Lavis icon...")
    print(f"   Design: Dark background + Gold L")
    print(f"   Background: {BACKGROUND_COLOR}")
    print(f"   L Color: {L_COLOR}")
    print()

    # 生成各尺寸图标
    for base_size, scale in SIZES:
        actual_size = base_size * scale
        suffix = f"@{scale}x" if scale > 1 else ""
        filename = f"icon_{base_size}x{base_size}{suffix}.png"
        filepath = ICONSET_DIR / filename

        # 生成 SVG
        svg_content = generate_svg(actual_size)
        svg_path = ICONSET_DIR / f"temp_{actual_size}.svg"

        with open(svg_path, 'w') as f:
            f.write(svg_content)

        # 使用 rsvg-convert 或 sips 转换为 PNG
        # 优先使用 rsvg-convert（更好的 SVG 支持）
        try:
            subprocess.run([
                'rsvg-convert', '-w', str(actual_size), '-h', str(actual_size),
                '-o', str(filepath), str(svg_path)
            ], check=True, capture_output=True)
        except (subprocess.CalledProcessError, FileNotFoundError):
            # 回退到 qlmanage（macOS 内置）
            try:
                subprocess.run([
                    'qlmanage', '-t', '-s', str(actual_size), '-o', str(ICONSET_DIR),
                    str(svg_path)
                ], check=True, capture_output=True)
                # qlmanage 输出文件名不同，需要重命名
                ql_output = ICONSET_DIR / f"temp_{actual_size}.svg.png"
                if ql_output.exists():
                    ql_output.rename(filepath)
            except:
                print(f"   ⚠️  Could not convert {filename}, trying alternative...")
                # 最后回退：使用 ImageMagick
                try:
                    subprocess.run([
                        'convert', '-background', 'none', '-density', '300',
                        str(svg_path), '-resize', f'{actual_size}x{actual_size}',
                        str(filepath)
                    ], check=True, capture_output=True)
                except:
                    print(f"   ❌ Failed to generate {filename}")
                    continue

        # 清理临时 SVG
        svg_path.unlink(missing_ok=True)

        print(f"   ✓ {filename} ({actual_size}x{actual_size})")

    # 复制 1024 作为主图标
    icon_1024 = ICONSET_DIR / "icon_1024x1024.png"
    if icon_1024.exists():
        import shutil
        shutil.copy(icon_1024, BUILD_DIR / "icon_1024.png")
        print(f"   ✓ icon_1024.png")

    # 生成 .icns 文件
    print()
    print("📦 Generating icon.icns...")
    try:
        subprocess.run([
            'iconutil', '-c', 'icns', str(ICONSET_DIR), '-o', str(BUILD_DIR / 'icon.icns')
        ], check=True)
        print("   ✓ icon.icns")
    except subprocess.CalledProcessError as e:
        print(f"   ❌ Failed to generate icon.icns: {e}")

    print()
    print("✅ Icon generation complete!")
    print(f"   Output: {BUILD_DIR}")


if __name__ == "__main__":
    main()
