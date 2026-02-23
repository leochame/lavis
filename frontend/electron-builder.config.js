/**
 * Electron Builder 配置文件
 * 用于打包 Lavis 应用，包含 Java 后端和 JRE
 */

const path = require('path');

// 获取当前架构
const currentArch = process.arch === 'arm64' ? 'arm64' : 'x64';

module.exports = {
  appId: 'com.lavis.ai',
  productName: 'Lavis',
  copyright: 'Copyright © 2024 Lavis',

  directories: {
    output: 'dist-electron',
    buildResources: 'build',
  },

  files: [
    'dist/**/*',
    'electron/**/*.js',
    'package.json',
  ],

  // 关键：将 JRE 和 JAR 从 asar 包中解压出来
  // 这样 Java 可执行文件才能正常运行
  // 模型文件也需要解压，因为 vosk-browser 需要直接访问文件系统来解压 .tar.gz
  asarUnpack: [
    'node_modules/**/*.node',
    'dist/models/**/*.tar.gz', // Vosk 模型文件需要从 asar 中解压
  ],

  // 额外资源：JRE 和 JAR 文件
  // 这些文件会被复制到打包后的应用的 Resources 目录
  extraResources: [
    {
      from: '../target/lavis-0.0.1-SNAPSHOT.jar',
      to: 'backend/lavis.jar',
    },
    // JRE 会根据目标架构自动选择
    // 打包时会复制整个 jre 目录
    {
      from: 'jre',
      to: 'jre',
      filter: ['**/*'],
    },
  ],

  mac: {
    category: 'public.app-category.productivity',
    // 只打包当前架构，避免缺少对应 JRE 的问题
    target: [
      {
        target: 'dmg',
        arch: [currentArch],
      },
      {
        target: 'zip',
        arch: [currentArch],
      },
    ],
    icon: 'build/icon.icns',
    hardenedRuntime: true,
    gatekeeperAssess: false,
    entitlements: 'build/entitlements.mac.plist',
    entitlementsInherit: 'build/entitlements.mac.plist',
    // 签名配置 - 如果没有开发者证书，设置为 null 跳过签名
    // 注意：未签名的应用在 macOS 上可能需要手动允许运行
    identity: null,
    extendInfo: {
      NSHighResolutionCapable: true,
      NSRequiresAquaSystemAppearance: false,
      // 麦克风使用说明
      NSMicrophoneUsageDescription: 'Lavis 需要访问麦克风以支持语音唤醒和语音输入功能',
      // 屏幕录制使用说明
      NSScreenCaptureUsageDescription: 'Lavis 需要屏幕录制权限以支持截图分析功能',
      // 辅助功能使用说明
      NSAppleEventsUsageDescription: 'Lavis 需要自动化权限以支持系统级操作',
    },
  },

  dmg: {
    contents: [
      {
        x: 130,
        y: 220,
      },
      {
        x: 410,
        y: 220,
        type: 'link',
        path: '/Applications',
      },
    ],
    window: {
      width: 540,
      height: 500, // 增加高度以容纳新文件
    },
  },

  // 打包后执行的钩子 - 确保 JRE 有执行权限，并设置安装脚本权限
  afterPack: async (context) => {
    const fs = require('fs');
    const path = require('path');

    const resourcesPath = path.join(context.appOutDir, `${context.packager.appInfo.productFilename}.app`, 'Contents', 'Resources');
    const jrePath = path.join(resourcesPath, 'jre');

    // 递归设置执行权限
    const setExecutable = (dir) => {
      if (!fs.existsSync(dir)) return;

      const entries = fs.readdirSync(dir, { withFileTypes: true });
      for (const entry of entries) {
        const fullPath = path.join(dir, entry.name);
        if (entry.isDirectory()) {
          setExecutable(fullPath);
        } else if (entry.name === 'java' || entry.name.endsWith('.dylib') || entry.name.endsWith('.so')) {
          try {
            fs.chmodSync(fullPath, 0o755);
            console.log(`Set executable: ${fullPath}`);
          } catch (e) {
            console.warn(`Failed to set executable: ${fullPath}`, e.message);
          }
        }
      }
    };

    console.log('Setting JRE executable permissions...');
    setExecutable(jrePath);

    // 确保自动安装脚本有执行权限（如果存在）
    const installScriptPath = path.join(__dirname, 'build', '自动安装.command');
    if (fs.existsSync(installScriptPath)) {
      try {
        fs.chmodSync(installScriptPath, 0o755);
        console.log('✅ 自动安装脚本权限已设置');
      } catch (e) {
        console.warn(`⚠️ 设置安装脚本权限失败: ${e.message}`);
      }
    }
  },

  // 打包完成后执行的钩子 - 移除 quarantine 属性
  afterSign: async (context) => {
    if (process.platform !== 'darwin') {
      return;
    }

    const { execSync } = require('child_process');
    const path = require('path');
    const appPath = path.join(context.appOutDir, `${context.packager.appInfo.productFilename}.app`);
    
    try {
      console.log('🔓 移除 quarantine 属性...');
      // 只移除 .app 包本身的 quarantine 属性，不递归处理内部文件
      // 使用 -d 而不是 -dr，避免权限问题
      execSync(`xattr -d com.apple.quarantine "${appPath}" 2>/dev/null || true`, { stdio: 'inherit' });
      console.log('✅ Quarantine 属性已移除');
    } catch (error) {
      // 如果属性不存在，忽略错误
      console.log('ℹ️ Quarantine 属性不存在或已移除');
    }
  },

  // 所有构建产物完成后执行的钩子 - 在 DMG 中添加安装文件
  afterAllArtifactBuild: async (context) => {
    if (process.platform !== 'darwin') {
      return;
    }

    const fs = require('fs');
    const path = require('path');
    const { execSync } = require('child_process');

    // 查找 DMG 文件
    const outputDir = context.outDir || path.join(__dirname, 'dist-electron');
    const dmgFiles = fs.readdirSync(outputDir).filter(f => f.endsWith('.dmg'));

    if (dmgFiles.length === 0) {
      return;
    }

    const buildDir = path.join(__dirname, 'build');
    const installScript = path.join(buildDir, '自动安装.command');
    const installGuide = path.join(buildDir, '安装说明.rtf');

    // 检查安装文件是否存在
    if (!fs.existsSync(installScript) || !fs.existsSync(installGuide)) {
      console.warn('⚠️ 安装文件不存在，跳过 DMG 文件添加');
      return;
    }

    for (const dmgFile of dmgFiles) {
      const dmgPath = path.join(outputDir, dmgFile);
      console.log(`\n📦 处理 DMG: ${dmgFile}`);

      // 在整个循环体内共享挂载点变量，便于在 catch 中使用
      let mountPoint = '';

      try {
        // 挂载 DMG（不使用 -quiet，确保可以解析输出获取挂载点）
        const mountOutput = execSync(`hdiutil attach "${dmgPath}" -nobrowse`, { encoding: 'utf8' });

        // hdiutil attach 输出通常类似：
        // /dev/disk4  Apple_HFS  /Volumes/Lavis
        // 取最后一列作为挂载点，兼容空白字符分隔
        const lines = mountOutput.split('\n').map(l => l.trim()).filter(Boolean);
        if (lines.length > 0) {
          const lastLine = lines[lines.length - 1];
          const parts = lastLine.split(/\s+/);
          mountPoint = parts[parts.length - 1] || '';
        }

        if (!mountPoint) {
          console.warn('⚠️ 无法从 hdiutil 输出中解析挂载点，尝试跳过该 DMG，但会首先尝试卸载卷');

          // 尝试从输出中提取设备 ID（例如 /dev/disk4），以便主动卸载，避免挂载泄漏
          try {
            const firstLine = lines[0] || '';
            const firstParts = firstLine.split(/\s+/);
            const device = firstParts[0];

            if (device && device.startsWith('/dev/')) {
              try {
                execSync(`hdiutil detach "${device}" -force -quiet 2>/dev/null || true`, { stdio: 'ignore' });
                console.log(`ℹ️ 已尝试通过设备 ${device} 卸载 DMG，避免资源泄漏`);
              } catch (e) {
                // 卸载失败时仅记录日志，不中断后续处理
                console.warn(`⚠️ 通过设备卸载 DMG 失败: ${e.message}`);
              }
            } else {
              console.warn('⚠️ 无法从 hdiutil 输出中解析设备 ID，可能需要手动卸载挂载卷');
            }
          } catch (parseError) {
            console.warn(`⚠️ 解析 hdiutil 输出以卸载 DMG 时出错: ${parseError.message}`);
          }

          // 无法安全继续对 DMG 内容进行修改，跳过该 DMG
          continue;
        }

        console.log(`✅ DMG 已挂载到: ${mountPoint}`);

        // 复制安装文件到 DMG
        const targetScript = path.join(mountPoint, '自动安装.command');
        const targetGuide = path.join(mountPoint, '安装说明.rtf');

        fs.copyFileSync(installScript, targetScript);
        fs.copyFileSync(installGuide, targetGuide);

        // 确保脚本有执行权限
        fs.chmodSync(targetScript, 0o755);

        console.log('✅ 安装文件已添加到 DMG');

        // 卸载 DMG
        execSync(`hdiutil detach "${mountPoint}" -quiet`, { stdio: 'ignore' });
        console.log('✅ DMG 处理完成');

      } catch (error) {
        console.error(`❌ 处理 DMG 时出错: ${error.message}`);
        // 尝试卸载（如果已成功获取挂载点）
        if (mountPoint) {
          try {
            execSync(`hdiutil detach "${mountPoint}" -force -quiet 2>/dev/null || true`, { stdio: 'ignore' });
          } catch (e) {
            // 忽略卸载错误
          }
        }
      }
    }
  },

  // 压缩选项
  compression: 'normal', // 使用 normal 而不是 maximum，加快打包速度
};
