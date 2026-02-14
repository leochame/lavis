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
      height: 400,
    },
  },

  // 打包后执行的钩子 - 确保 JRE 有执行权限
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

  // 压缩选项
  compression: 'normal', // 使用 normal 而不是 maximum，加快打包速度
};
