#!/usr/bin/env node
/**
 * Lavis Icon Generator - 生成高级优雅的 L 图标
 * 设计理念：深色背景 + 金色 L，简约现代
 *
 * 使用方法：node scripts/generate-icon.js
 */

const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const BUILD_DIR = path.join(__dirname, '..', 'build');
const ICONSET_DIR = path.join(BUILD_DIR, 'icon.iconset');

// 所需尺寸
const SIZES = [
  [16, 1], [16, 2],
  [32, 1], [32, 2],
  [64, 1], [64, 2],
  [128, 1], [128, 2],
  [256, 1], [256, 2],
  [512, 1], [512, 2],
  [1024, 1],
];

/**
 * 生成 SVG 图标
 */
function generateSVG(size) {
  const cornerRadius = size * 0.22;

  // L 的尺寸和位置
  const padding = size * 0.22;
  const lWidth = size * 0.45;
  const lHeight = size * 0.56;
  const strokeWidth = size * 0.12;

  // L 的起点
  const lX = padding;
  const lY = padding;

  // L 的路径
  const lPath = `
    M ${lX + strokeWidth/2} ${lY}
    L ${lX + strokeWidth/2} ${lY + lHeight - strokeWidth/2}
    L ${lX + lWidth} ${lY + lHeight - strokeWidth/2}
  `.trim().replace(/\s+/g, ' ');

  return `<?xml version="1.0" encoding="UTF-8"?>
<svg width="${size}" height="${size}" viewBox="0 0 ${size} ${size}" xmlns="http://www.w3.org/2000/svg">
  <defs>
    <linearGradient id="bgGrad" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" style="stop-color:#242424"/>
      <stop offset="100%" style="stop-color:#1a1a1a"/>
    </linearGradient>
    <linearGradient id="lGrad" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" style="stop-color:#e8c068"/>
      <stop offset="50%" style="stop-color:#d4a853"/>
      <stop offset="100%" style="stop-color:#c49a48"/>
    </linearGradient>
  </defs>
  <rect x="0" y="0" width="${size}" height="${size}" rx="${cornerRadius}" ry="${cornerRadius}" fill="url(#bgGrad)"/>
  <path d="${lPath}" fill="none" stroke="url(#lGrad)" stroke-width="${strokeWidth}" stroke-linecap="round" stroke-linejoin="round"/>
</svg>`;
}

/**
 * 使用 sips 将 SVG 转换为 PNG（macOS 原生）
 */
function convertSVGtoPNG(svgPath, pngPath, size) {
  // macOS 没有原生 SVG 到 PNG 的转换，我们用 qlmanage
  try {
    // 先尝试 qlmanage
    execSync(`qlmanage -t -s ${size} -o "${path.dirname(pngPath)}" "${svgPath}" 2>/dev/null`, { stdio: 'pipe' });
    const qlOutput = svgPath + '.png';
    if (fs.existsSync(qlOutput)) {
      fs.renameSync(qlOutput, pngPath);
      return true;
    }
  } catch (e) {
    // qlmanage 失败
  }
  return false;
}

async function main() {
  console.log('🎨 Generating Lavis icon...');
  console.log('   Design: Dark background (#1a1a1a) + Gold L (#d4a853)');
  console.log();

  // 确保目录存在
  if (!fs.existsSync(ICONSET_DIR)) {
    fs.mkdirSync(ICONSET_DIR, { recursive: true });
  }

  // 检查是否有 rsvg-convert 或 convert
  let converter = null;
  try {
    execSync('which rsvg-convert', { stdio: 'pipe' });
    converter = 'rsvg';
  } catch {
    try {
      execSync('which convert', { stdio: 'pipe' });
      converter = 'imagemagick';
    } catch {
      // 没有转换工具
    }
  }

  if (!converter) {
    console.log('⚠️  No SVG converter found (rsvg-convert or ImageMagick)');
    console.log('   Installing librsvg via Homebrew...');
    try {
      execSync('brew install librsvg', { stdio: 'inherit' });
      converter = 'rsvg';
    } catch {
      console.log('❌ Failed to install librsvg. Please install manually:');
      console.log('   brew install librsvg');
      console.log();
      console.log('   Or use ImageMagick:');
      console.log('   brew install imagemagick');
      process.exit(1);
    }
  }

  // 生成各尺寸图标
  for (const [baseSize, scale] of SIZES) {
    const actualSize = baseSize * scale;
    const suffix = scale > 1 ? `@${scale}x` : '';
    const filename = `icon_${baseSize}x${baseSize}${suffix}.png`;
    const filepath = path.join(ICONSET_DIR, filename);

    // 生成 SVG
    const svgContent = generateSVG(actualSize);
    const svgPath = path.join(ICONSET_DIR, `temp_${actualSize}.svg`);
    fs.writeFileSync(svgPath, svgContent);

    // 转换为 PNG
    try {
      if (converter === 'rsvg') {
        execSync(`rsvg-convert -w ${actualSize} -h ${actualSize} -o "${filepath}" "${svgPath}"`, { stdio: 'pipe' });
      } else if (converter === 'imagemagick') {
        execSync(`convert -background none -density 300 "${svgPath}" -resize ${actualSize}x${actualSize} "${filepath}"`, { stdio: 'pipe' });
      }
      console.log(`   ✓ ${filename} (${actualSize}x${actualSize})`);
    } catch (e) {
      console.log(`   ❌ Failed: ${filename}`);
    }

    // 清理临时 SVG
    fs.unlinkSync(svgPath);
  }

  // 复制 1024 作为主图标
  const icon1024 = path.join(ICONSET_DIR, 'icon_1024x1024.png');
  if (fs.existsSync(icon1024)) {
    fs.copyFileSync(icon1024, path.join(BUILD_DIR, 'icon_1024.png'));
    console.log('   ✓ icon_1024.png');
  }

  // 生成 .icns 文件
  console.log();
  console.log('📦 Generating icon.icns...');
  try {
    execSync(`iconutil -c icns "${ICONSET_DIR}" -o "${path.join(BUILD_DIR, 'icon.icns')}"`, { stdio: 'pipe' });
    console.log('   ✓ icon.icns');
  } catch (e) {
    console.log('   ❌ Failed to generate icon.icns');
  }

  console.log();
  console.log('✅ Icon generation complete!');
  console.log(`   Output: ${BUILD_DIR}`);
}

main().catch(console.error);
