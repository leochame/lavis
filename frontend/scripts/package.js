#!/usr/bin/env node

/**
 * 一键打包脚本
 * 
 * 功能：
 * 1. 构建 Java 后端 JAR 文件
 * 2. 构建前端代码
 * 3. 编译 Electron 主进程代码
 * 4. 使用 electron-builder 打包应用
 * 
 * 使用方法：
 * npm run package
 */

const { spawn, spawnSync } = require('child_process');
const path = require('path');
const fs = require('fs');

const projectRoot = path.join(__dirname, '..', '..');
const frontendDir = path.join(__dirname, '..');
const targetDir = path.join(projectRoot, 'target');
const jarPath = path.join(targetDir, 'lavis-0.0.1-SNAPSHOT.jar');
const isWindows = process.platform === 'win32';
const mvnwName = isWindows ? 'mvnw.cmd' : 'mvnw';
const mvnwPath = path.join(projectRoot, mvnwName);
let mavenCommand = 'mvn';
let mavenCommandLabel = 'mvn';
let javaHome = '';

function isValidJavaHome(candidate) {
  if (!candidate) {
    return false;
  }
  const javaBin = path.join(candidate, 'bin', isWindows ? 'java.exe' : 'java');
  const javacBin = path.join(candidate, 'bin', isWindows ? 'javac.exe' : 'javac');
  return fs.existsSync(javaBin) && fs.existsSync(javacBin);
}

function ensureProcessJavaHome(resolvedJavaHome, envJavaHome, envJavaHomeValid) {
  if (!resolvedJavaHome) {
    return;
  }

  if (!envJavaHome || !envJavaHomeValid) {
    process.env.JAVA_HOME = resolvedJavaHome;
  }

  const javaBinDir = path.join(resolvedJavaHome, 'bin');
  const pathEntries = (process.env.PATH || '').split(path.delimiter).filter(Boolean);
  if (!pathEntries.includes(javaBinDir)) {
    process.env.PATH = [javaBinDir, ...pathEntries].join(path.delimiter);
  }
}

function runCommand(cmd, args, options = {}) {
  return new Promise((resolve, reject) => {
    console.log(`\n📦 执行: ${cmd} ${args.join(' ')}`);

    const { captureOutput = false, stdio, ...spawnOptions } = options;
    const usePipe = captureOutput || stdio === 'pipe';
    const child = spawn(cmd, args, {
      stdio: usePipe ? 'pipe' : stdio || 'inherit',
      shell: process.platform === 'win32',
      ...spawnOptions,
    });

    let stdout = '';
    let stderr = '';

    if (usePipe) {
      if (child.stdout) {
        child.stdout.on('data', (data) => {
          stdout += data.toString();
        });
      }
      if (child.stderr) {
        child.stderr.on('data', (data) => {
          stderr += data.toString();
        });
      }
    }

    child.on('exit', (code) => {
      if (code === 0) {
        resolve({ stdout, stderr });
      } else {
        const output = `${stdout}${stderr}`.trim();
        const details = output ? `\n${output}` : '';
        reject(new Error(`${cmd} ${args.join(' ')} 退出，代码: ${code}${details}`));
      }
    });

    child.on('error', (error) => {
      reject(new Error(`执行 ${cmd} 时出错: ${error.message}`));
    });
  });
}

function resolveJavaHome() {
  const envJavaHome = process.env.JAVA_HOME && process.env.JAVA_HOME.trim();
  if (envJavaHome && isValidJavaHome(envJavaHome)) {
    return envJavaHome;
  }

  if (process.platform === 'darwin') {
    const result = spawnSync('/usr/libexec/java_home', [], { encoding: 'utf8' });
    if (result.status === 0 && result.stdout) {
      const detected = result.stdout.trim();
      if (detected && isValidJavaHome(detected)) {
        return detected;
      }
    }
  }

  return '';
}

function getMavenEnv() {
  if (!javaHome) {
    return process.env;
  }

  return {
    ...process.env,
    JAVA_HOME: javaHome,
  };
}

async function resolveMavenCommand() {
  const envMavenCommand = process.env.MAVEN_CMD && process.env.MAVEN_CMD.trim();
  if (envMavenCommand) {
    try {
      await runCommand(envMavenCommand, ['--version'], {
        captureOutput: true,
        cwd: projectRoot,
        env: getMavenEnv(),
      });
      return { cmd: envMavenCommand, label: envMavenCommand };
    } catch (error) {
      throw new Error(`MAVEN_CMD 无法使用: ${error.message}`);
    }
  }

  try {
    await runCommand('mvn', ['--version'], { captureOutput: true, env: getMavenEnv() });
    return { cmd: 'mvn', label: 'mvn' };
  } catch (error) {
    // ignore and try Maven Wrapper
  }

  if (fs.existsSync(mvnwPath)) {
    try {
      await runCommand(mvnwPath, ['--version'], {
        captureOutput: true,
        cwd: projectRoot,
        env: getMavenEnv(),
      });
      return { cmd: mvnwPath, label: isWindows ? 'mvnw.cmd' : './mvnw' };
    } catch (error) {
      if (!javaHome) {
        throw new Error(`Maven Wrapper 执行失败（未设置 JAVA_HOME）: ${error.message}`);
      }
      throw new Error(`Maven Wrapper 执行失败: ${error.message}`);
    }
  }

  throw new Error('未找到 Maven 或 Maven Wrapper，请先安装 Maven 或确保项目根目录包含 mvnw');
}

async function checkPrerequisites() {
  console.log('🔍 检查前置条件...');
  
  try {
    await runCommand('java', ['-version'], { captureOutput: true });
    console.log('✅ Java 已安装');
  } catch (error) {
    console.error('❌ 未找到 Java (JDK 21+)，请先安装 Java');
    process.exit(1);
  }

  const envJavaHome = process.env.JAVA_HOME && process.env.JAVA_HOME.trim();
  const envJavaHomeValid = envJavaHome ? isValidJavaHome(envJavaHome) : false;
  javaHome = resolveJavaHome();
  if (javaHome) {
    ensureProcessJavaHome(javaHome, envJavaHome, envJavaHomeValid);
    console.log(`✅ JAVA_HOME 已就绪 (${javaHome})`);
    if (envJavaHome && !envJavaHomeValid) {
      console.warn(`⚠️ 检测到无效的 JAVA_HOME 环境变量 (${envJavaHome})，已自动修正为 ${javaHome}`);
    } else if (!envJavaHome) {
      console.log(`ℹ️ 已自动设置 JAVA_HOME (${javaHome})`);
    }
  } else {
    if (envJavaHome && !envJavaHomeValid) {
      console.warn(`⚠️ JAVA_HOME 环境变量无效 (${envJavaHome})`);
    }
    console.warn('⚠️ 未检测到可用的 JAVA_HOME，Maven Wrapper 可能无法运行');
  }

  // 检查 Maven
  try {
    const resolved = await resolveMavenCommand();
    mavenCommand = resolved.cmd;
    mavenCommandLabel = resolved.label;
    console.log(`✅ Maven 已就绪 (${mavenCommandLabel})`);
  } catch (error) {
    console.error(`❌ ${error.message}`);
    process.exit(1);
  }

  // 检查 Node.js
  try {
    await runCommand('node', ['--version'], { captureOutput: true });
    console.log('✅ Node.js 已安装');
  } catch (error) {
    console.error('❌ 未找到 Node.js，请先安装 Node.js');
    process.exit(1);
  }
}

async function buildBackend() {
  console.log('\n📦 构建 Java 后端...');
  
  // 检查 JAR 文件是否已存在且较新
  if (fs.existsSync(jarPath)) {
    const jarStats = fs.statSync(jarPath);
    const pomPath = path.join(projectRoot, 'pom.xml');
    
    if (fs.existsSync(pomPath)) {
      const pomStats = fs.statSync(pomPath);
      // 如果 JAR 比 pom.xml 新，跳过构建
      if (jarStats.mtime > pomStats.mtime) {
        console.log('✅ JAR 文件已存在且较新，跳过构建');
        return;
      }
    }
  }

  try {
    await runCommand(mavenCommand, ['clean', 'package', '-DskipTests'], {
      cwd: projectRoot,
      env: getMavenEnv(),
    });
    console.log('✅ Java 后端构建完成');
  } catch (error) {
    console.error('❌ Java 后端构建失败:', error.message);
    process.exit(1);
  }

  // 验证 JAR 文件是否存在
  if (!fs.existsSync(jarPath)) {
    console.error(`❌ JAR 文件未找到: ${jarPath}`);
    process.exit(1);
  }
  
  console.log(`✅ JAR 文件位置: ${jarPath}`);
}

async function buildFrontend() {
  console.log('\n📦 构建前端...');
  
  try {
    // 编译 Electron 主进程代码
    console.log('📝 编译 Electron 主进程代码...');
    await runCommand('npm', ['run', 'build:electron'], {
      cwd: frontendDir,
    });
    
    // 构建前端资源
    console.log('📝 构建前端资源...');
    await runCommand('npm', ['run', 'build'], {
      cwd: frontendDir,
    });
    
    console.log('✅ 前端构建完成');
  } catch (error) {
    console.error('❌ 前端构建失败:', error.message);
    process.exit(1);
  }
}

async function packageApp() {
  console.log('\n📦 打包 Electron 应用...');
  
  try {
    const builderConfig = path.join(frontendDir, 'electron-builder.config.js');
    await runCommand('npx', ['electron-builder', '--config', builderConfig, '--mac'], {
      cwd: frontendDir,
    });
    console.log('✅ 应用打包完成');
  } catch (error) {
    console.error('❌ 应用打包失败:', error.message);
    process.exit(1);
  }
}

function findPackagedAppDir(outputDir) {
  if (!fs.existsSync(outputDir)) {
    return '';
  }

  const entries = fs.readdirSync(outputDir, { withFileTypes: true });
  for (const entry of entries) {
    if (entry.isDirectory() && entry.name.endsWith('.app')) {
      return entry.name;
    }
  }
  return '';
}

function removeQuarantineAttribute(appPath) {
  if (process.platform !== 'darwin') {
    return;
  }

  try {
    const { execSync } = require('child_process');
    console.log('🔓 移除 quarantine 属性...');
    // 只移除 .app 包本身的 quarantine 属性，不递归处理内部文件
    // 使用 -d 而不是 -dr，避免权限问题
    execSync(`xattr -d com.apple.quarantine "${appPath}" 2>/dev/null || true`, { stdio: 'inherit' });
    console.log('✅ Quarantine 属性已移除');
  } catch (error) {
    console.warn('⚠️ 移除 quarantine 属性失败（可能已经不存在）:', error.message);
  }
}

function verifyPackagedResources() {
  if (process.platform !== 'darwin') {
    return;
  }

  const arch = process.arch === 'arm64' ? 'arm64' : 'x64';
  const outputDir = path.join(frontendDir, 'dist-electron', `mac-${arch}`);
  const appDirName = findPackagedAppDir(outputDir);

  if (!appDirName) {
    console.warn('⚠️ 未找到打包后的 .app 目录，跳过资源校验');
    return;
  }

  const appPath = path.join(outputDir, appDirName);
  const resourcesDir = path.join(appPath, 'Contents', 'Resources');
  const jarFile = path.join(resourcesDir, 'backend', 'lavis.jar');
  const javaBin = path.join(resourcesDir, 'jre', `mac-${arch}`, 'Contents', 'Home', 'bin', 'java');

  const missing = [];
  if (!fs.existsSync(jarFile)) {
    missing.push(`后端 JAR 未找到: ${jarFile}`);
  }
  if (!fs.existsSync(javaBin)) {
    missing.push(`内嵌 JRE 未找到: ${javaBin}`);
  }

  if (missing.length > 0) {
    console.error('❌ 打包资源校验失败:');
    for (const message of missing) {
      console.error(`   - ${message}`);
    }
    console.error('💡 请确认 electron-builder 使用了正确的配置文件 (electron-builder.config.js)');
    process.exit(1);
  }

  console.log('✅ 打包资源校验通过');
  
  // 移除 quarantine 属性，解决 macOS Gatekeeper 阻止问题
  removeQuarantineAttribute(appPath);
}

function prepareBuildIconAssets() {
  const sourceIcon = path.join(projectRoot, 'docs', 'images', 'icon.png');
  const buildDir = path.join(frontendDir, 'build');
  const iconsetDir = path.join(buildDir, 'icon.iconset');
  const targetPng = path.join(buildDir, 'icon.png');
  const targetIcns = path.join(buildDir, 'icon.icns');

  if (!fs.existsSync(sourceIcon)) {
    throw new Error(`图标源文件不存在: ${sourceIcon}`);
  }

  fs.mkdirSync(buildDir, { recursive: true });
  fs.copyFileSync(sourceIcon, targetPng);
  console.log(`✅ 已复制图标: ${targetPng}`);

  if (process.platform !== 'darwin') {
    console.log('ℹ️ 非 macOS 环境，跳过 icon.icns 生成');
    return;
  }

  fs.rmSync(iconsetDir, { recursive: true, force: true });
  fs.mkdirSync(iconsetDir, { recursive: true });

  const iconsetFiles = [
    ['icon_16x16.png', 16],
    ['icon_16x16@2x.png', 32],
    ['icon_32x32.png', 32],
    ['icon_32x32@2x.png', 64],
    ['icon_128x128.png', 128],
    ['icon_128x128@2x.png', 256],
    ['icon_256x256.png', 256],
    ['icon_256x256@2x.png', 512],
    ['icon_512x512.png', 512],
    ['icon_512x512@2x.png', 1024],
  ];

  try {
    for (const [filename, size] of iconsetFiles) {
      const output = path.join(iconsetDir, filename);
      const result = spawnSync('sips', ['-z', String(size), String(size), sourceIcon, '--out', output], {
        stdio: 'pipe',
      });
      if (result.status !== 0) {
        throw new Error(`sips 生成失败: ${filename}`);
      }
    }
    const iconResult = spawnSync('iconutil', ['-c', 'icns', iconsetDir, '-o', targetIcns], {
      stdio: 'pipe',
    });
    if (iconResult.status !== 0) {
      throw new Error('iconutil 执行失败');
    }
    if (!fs.existsSync(targetIcns)) {
      throw new Error('icon.icns 未生成');
    }
    console.log(`✅ 已生成图标: ${targetIcns}`);
  } catch (error) {
    throw new Error(`生成 icon.icns 失败: ${error.message}`);
  }
}

async function main() {
  console.log('🚀 开始一键打包 Lavis 应用...\n');
  
  try {
    // 1. 检查前置条件
    await checkPrerequisites();

    // 1.5 准备图标资源（用于应用与打包图标）
    prepareBuildIconAssets();
    
    // 2. 构建 Java 后端
    await buildBackend();
    
    // 3. 构建前端
    await buildFrontend();
    
    // 4. 打包应用
    await packageApp();

    // 5. 校验打包资源
    verifyPackagedResources();
    
    console.log('\n✅ 打包完成！');
    console.log('📦 应用文件位于: frontend/dist-electron/');
    console.log('\n💡 提示: 可以将 .app 文件拖拽到 Applications 文件夹，或使用 .dmg 安装包安装');
  } catch (error) {
    console.error('\n❌ 打包过程中出错:', error.message);
    process.exit(1);
  }
}

main();
