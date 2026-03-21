/**
 * Backend Manager - 管理内嵌的 Java 后端进程
 *
 * 功能：
 * 1. 自动检测并启动内嵌的 Java 后端
 * 2. 健康检查和自动重启
 * 3. 优雅关闭
 * 4. 日志收集
 */

import { spawn, exec, ChildProcess } from 'child_process';
import * as path from 'path';
import * as fs from 'fs';
import * as http from 'http';
import { app } from 'electron';

// 后端配置
const BACKEND_PORT = 18765;
const HEALTH_CHECK_INTERVAL = 5000; // 5秒检查一次
const STARTUP_TIMEOUT = 60000; // 60秒启动超时
const MAX_RESTART_ATTEMPTS = 3;

// 状态
let backendProcess: ChildProcess | null = null;
let healthCheckTimer: NodeJS.Timeout | null = null;
let restartAttempts = 0;
let isShuttingDown = false;

// 日志回调
type LogCallback = (level: 'info' | 'warn' | 'error', message: string) => void;
let logCallback: LogCallback = (level, message) => {
  const prefix = level === 'error' ? '❌' : level === 'warn' ? '⚠️' : '📦';
  console.log(`${prefix} [Backend] ${message}`);
};

/**
 * 设置日志回调
 */
export function setLogCallback(callback: LogCallback) {
  logCallback = callback;
}

/**
 * 获取资源路径
 * 开发模式：项目根目录
 * 生产模式：app.asar.unpacked 或 Resources 目录
 */
function getResourcePath(): string {
  if (app.isPackaged) {
    // 生产模式
    const resourcesPath = process.resourcesPath;
    return resourcesPath;
  } else {
    // 开发模式 - 返回项目根目录（frontend 的父目录）
    return path.join(__dirname, '..', '..');
  }
}

/**
 * 获取 JRE 路径
 * 支持多种 JRE 目录结构，增强健壮性
 */
function getJrePath(): string {
  const resourcePath = getResourcePath();
  const platform = process.platform;
  const arch = process.arch === 'arm64' ? 'arm64' : 'x64';

  if (app.isPackaged) {
    // 生产模式 - JRE 在 Resources/jre/${os}-${arch} 目录
    let osDir = '';
    if (platform === 'darwin') {
      osDir = 'mac';
    } else if (platform === 'win32') {
      osDir = 'win';
    } else {
      osDir = 'linux';
    }

    const jreBasePath = path.join(resourcePath, 'jre', `${osDir}-${arch}`);
    logCallback('info', `Looking for JRE in: ${jreBasePath}`);

    // 定义所有可能的 Java 可执行文件路径
    const possiblePaths: string[] = [];

    if (platform === 'darwin') {
      // macOS 可能的路径结构
      possiblePaths.push(
        // 直接的 Contents/Home 结构
        path.join(jreBasePath, 'Contents', 'Home', 'bin', 'java'),
        // 直接的 bin 目录
        path.join(jreBasePath, 'bin', 'java'),
      );

      // 查找 jdk-*.jdk 或 jdk* 目录
      if (fs.existsSync(jreBasePath)) {
        try {
          const entries = fs.readdirSync(jreBasePath);
          for (const entry of entries) {
            if (entry.endsWith('.jdk') || entry.startsWith('jdk') || entry.startsWith('zulu')) {
              possiblePaths.push(
                path.join(jreBasePath, entry, 'Contents', 'Home', 'bin', 'java'),
                path.join(jreBasePath, entry, 'bin', 'java'),
              );
            }
          }
        } catch (e) {
          logCallback('warn', `Failed to scan JRE directory: ${e}`);
        }
      }
    } else if (platform === 'win32') {
      possiblePaths.push(
        path.join(jreBasePath, 'bin', 'java.exe'),
      );

      // 查找子目录
      if (fs.existsSync(jreBasePath)) {
        try {
          const entries = fs.readdirSync(jreBasePath);
          for (const entry of entries) {
            if (entry.startsWith('jdk') || entry.startsWith('jre') || entry.startsWith('zulu')) {
              possiblePaths.push(path.join(jreBasePath, entry, 'bin', 'java.exe'));
            }
          }
        } catch (e) {
          logCallback('warn', `Failed to scan JRE directory: ${e}`);
        }
      }
    } else {
      // Linux
      possiblePaths.push(
        path.join(jreBasePath, 'bin', 'java'),
      );

      if (fs.existsSync(jreBasePath)) {
        try {
          const entries = fs.readdirSync(jreBasePath);
          for (const entry of entries) {
            if (entry.startsWith('jdk') || entry.startsWith('jre') || entry.startsWith('zulu')) {
              possiblePaths.push(path.join(jreBasePath, entry, 'bin', 'java'));
            }
          }
        } catch (e) {
          logCallback('warn', `Failed to scan JRE directory: ${e}`);
        }
      }
    }

    // 尝试找到存在的路径
    for (const possiblePath of possiblePaths) {
      logCallback('info', `Checking Java path: ${possiblePath}`);
      if (fs.existsSync(possiblePath)) {
        logCallback('info', `Found Java at: ${possiblePath}`);
        return possiblePath;
      }
    }

    // 如果都不存在，返回最可能的默认路径（用于错误提示）
    const defaultPath = platform === 'darwin'
      ? path.join(jreBasePath, 'Contents', 'Home', 'bin', 'java')
      : platform === 'win32'
        ? path.join(jreBasePath, 'bin', 'java.exe')
        : path.join(jreBasePath, 'bin', 'java');

    logCallback('warn', `No Java found, using default path: ${defaultPath}`);
    return defaultPath;
  } else {
    // 开发模式 - 优先使用项目内的 JRE，否则使用系统 Java
    let osDir = '';
    if (platform === 'darwin') {
      osDir = 'mac';
    } else if (platform === 'win32') {
      osDir = 'win';
    } else {
      osDir = 'linux';
    }

    // 检查项目内是否有 JRE
    const projectJrePath = path.join(resourcePath, 'frontend', 'jre', `${osDir}-${arch}`);
    if (fs.existsSync(projectJrePath)) {
      const javaPath = platform === 'darwin'
        ? path.join(projectJrePath, 'Contents', 'Home', 'bin', 'java')
        : platform === 'win32'
          ? path.join(projectJrePath, 'bin', 'java.exe')
          : path.join(projectJrePath, 'bin', 'java');

      if (fs.existsSync(javaPath)) {
        logCallback('info', `Using project JRE: ${javaPath}`);
        return javaPath;
      }
    }

    // 使用系统 Java
    logCallback('info', 'Using system Java');
    return 'java';
  }
}

/**
 * 获取 JAR 路径
 */
function getJarPath(): string {
  const resourcePath = getResourcePath();

  if (app.isPackaged) {
    // 生产模式 - JAR 在 Resources/backend 目录
    return path.join(resourcePath, 'backend', 'lavis.jar');
  } else {
    // 开发模式 - JAR 在项目 target 目录
    const targetDir = path.join(resourcePath, 'target');

    // 查找 JAR 文件
    if (fs.existsSync(targetDir)) {
      const files = fs.readdirSync(targetDir);
      const jarFile = files.find(f => f.endsWith('.jar') && !f.includes('sources') && !f.includes('javadoc'));
      if (jarFile) {
        return path.join(targetDir, jarFile);
      }
    }

    // 默认路径
    return path.join(resourcePath, 'target', 'lavis-0.0.1-SNAPSHOT.jar');
  }
}

/**
 * 开发模式下自动构建后端 JAR
 */
async function buildBackendJar(resourcePath: string): Promise<boolean> {
  return new Promise((resolve) => {
    const cmd = './mvnw -Dmaven.test.skip=true package';
    logCallback('info', `Building backend JAR: ${cmd}`);

    exec(cmd, { cwd: resourcePath }, (error, stdout, stderr) => {
      if (stdout?.trim()) {
        logCallback('info', stdout.trim());
      }
      if (stderr?.trim()) {
        logCallback('warn', stderr.trim());
      }
      if (error) {
        logCallback('error', `Backend JAR build failed: ${error.message}`);
        resolve(false);
        return;
      }
      resolve(true);
    });
  });
}

/**
 * 检查后端是否已经在运行
 */
async function isBackendRunning(): Promise<boolean> {
  return new Promise((resolve) => {
    const req = http.request({
      hostname: '127.0.0.1',
      port: BACKEND_PORT,
      path: '/api/agent/status',
      method: 'GET',
      timeout: 3000,
    }, (res) => {
      resolve(res.statusCode === 200);
    });

    req.on('error', () => resolve(false));
    req.on('timeout', () => {
      req.destroy();
      resolve(false);
    });

    req.end();
  });
}

/**
 * 等待后端启动
 */
async function waitForBackend(timeoutMs: number = STARTUP_TIMEOUT): Promise<boolean> {
  const startTime = Date.now();

  while (Date.now() - startTime < timeoutMs) {
    if (await isBackendRunning()) {
      return true;
    }
    await new Promise(resolve => setTimeout(resolve, 1000));
  }

  return false;
}

/**
 * 启动后端进程
 * @returns Promise<{success: boolean, error?: string}>
 */
export async function startBackend(): Promise<{success: boolean, error?: string}> {
  if (isShuttingDown) {
    logCallback('warn', 'Cannot start backend during shutdown');
    return {success: false, error: 'Cannot start backend during shutdown'};
  }

  // 检查是否已经在运行
  if (await isBackendRunning()) {
    logCallback('info', 'Backend is already running');
    startHealthCheck();
    return {success: true};
  }

  const javaPath = getJrePath();
  let jarPath = getJarPath();
  const resourcePath = getResourcePath();

  logCallback('info', `Resource path: ${resourcePath}`);
  logCallback('info', `Java path: ${javaPath}`);
  logCallback('info', `JAR path: ${jarPath}`);

  // 检查文件是否存在
  if (app.isPackaged) {
    if (!fs.existsSync(javaPath)) {
      // 尝试列出目录内容以帮助调试
      const jreBaseDir = path.dirname(path.dirname(javaPath));
      let debugInfo = `Java executable not found at: ${javaPath}\n\nResource path: ${resourcePath}\nJRE base directory: ${jreBaseDir}\n`;
      
      if (fs.existsSync(jreBaseDir)) {
        try {
          const entries = fs.readdirSync(jreBaseDir, { recursive: true });
          debugInfo += `\nFound in JRE directory:\n${entries.slice(0, 20).join('\n')}`;
        } catch (e) {
          debugInfo += `\nCould not list JRE directory: ${e}`;
        }
      } else {
        debugInfo += `\nJRE base directory does not exist.`;
      }
      
      debugInfo += `\n\nPlease check if the JRE was properly packaged.`;
      logCallback('error', debugInfo);
      return {success: false, error: debugInfo};
    }
    
    // 检查 Java 可执行文件权限（macOS/Linux）
    if (process.platform !== 'win32') {
      try {
        const stats = fs.statSync(javaPath);
        if (!stats.isFile()) {
          const errorMsg = `Java path exists but is not a file: ${javaPath}`;
          logCallback('error', errorMsg);
          return {success: false, error: errorMsg};
        }
        // 尝试添加执行权限（如果需要）
        fs.chmodSync(javaPath, 0o755);
      } catch (e) {
        const errorMsg = `Cannot access Java executable: ${javaPath}\nError: ${e}`;
        logCallback('error', errorMsg);
        return {success: false, error: errorMsg};
      }
    }
  }

  if (!fs.existsSync(jarPath)) {
    if (!app.isPackaged) {
      logCallback('warn', `Backend JAR missing: ${jarPath}. Trying to build automatically...`);
      const built = await buildBackendJar(resourcePath);
      jarPath = getJarPath();
      if (built && fs.existsSync(jarPath)) {
        logCallback('info', `Backend JAR built successfully: ${jarPath}`);
      } else {
        const errorMsg = `JAR file not found at: ${jarPath}\n\nResource path: ${resourcePath}\n\nAuto-build failed. Please run "./mvnw -Dmaven.test.skip=true package" manually.`;
        logCallback('error', errorMsg);
        return {success: false, error: errorMsg};
      }
    } else {
      const errorMsg = `JAR file not found at: ${jarPath}\n\nResource path: ${resourcePath}\n\nPlease check if the backend JAR was properly packaged.`;
      logCallback('error', errorMsg);
      return {success: false, error: errorMsg};
    }
  }

  // 启动 Java 进程
  const javaArgs = [
    '-Xmx512m',
    '-Dserver.port=' + BACKEND_PORT,
    '-Dspring.profiles.active=production',
    '-jar',
    jarPath,
  ];

  logCallback('info', `Starting backend: ${javaPath} ${javaArgs.join(' ')}`);

  // 计算 JAVA_HOME
  let javaHome: string | undefined;
  if (app.isPackaged) {
    // 生产模式：从 java 可执行文件路径推导 JAVA_HOME
    const platform = process.platform;
    if (platform === 'darwin') {
      // macOS: java 在 Contents/Home/bin/java，所以 JAVA_HOME 是 Contents/Home
      const binDir = path.dirname(javaPath);
      const homeDir = path.dirname(binDir);
      javaHome = homeDir;
    } else {
      // Windows/Linux: java 在 bin/java，所以 JAVA_HOME 是 bin 的父目录
      javaHome = path.dirname(path.dirname(javaPath));
    }
  } else {
    // 开发模式：使用系统 JAVA_HOME
    javaHome = process.env.JAVA_HOME;
  }

  // 收集错误信息
  const errorMessages: string[] = [];

  try {
    const spawnEnv: NodeJS.ProcessEnv = {
      ...process.env,
    };

    if (javaHome) {
      spawnEnv.JAVA_HOME = javaHome;
      logCallback('info', `JAVA_HOME: ${javaHome}`);
    }

    backendProcess = spawn(javaPath, javaArgs, {
      cwd: path.dirname(jarPath),
      stdio: ['ignore', 'pipe', 'pipe'],
      detached: false,
      env: spawnEnv,
    });

    // 收集日志和错误
    backendProcess.stdout?.on('data', (data) => {
      const lines = data.toString().split('\n').filter((l: string) => l.trim());
      lines.forEach((line: string) => {
        if (line.includes('ERROR') || line.includes('Exception')) {
          logCallback('error', line);
          errorMessages.push(line);
        } else if (line.includes('WARN')) {
          logCallback('warn', line);
        } else {
          logCallback('info', line);
        }
      });
    });

    backendProcess.stderr?.on('data', (data) => {
      const errorText = data.toString();
      logCallback('error', errorText);
      errorMessages.push(errorText);
    });

    backendProcess.on('error', (error) => {
      const errorMsg = `Failed to start backend: ${error.message}`;
      logCallback('error', errorMsg);
      errorMessages.push(errorMsg);
      backendProcess = null;
    });

    backendProcess.on('exit', (code, signal) => {
      logCallback('info', `Backend exited with code ${code}, signal ${signal}`);
      if (code !== 0 && code !== null) {
        errorMessages.push(`Backend process exited with code ${code}`);
      }
      backendProcess = null;

      // 如果不是正常关闭，尝试重启
      if (!isShuttingDown && code !== 0 && restartAttempts < MAX_RESTART_ATTEMPTS) {
        restartAttempts++;
        logCallback('warn', `Attempting to restart backend (attempt ${restartAttempts}/${MAX_RESTART_ATTEMPTS})`);
        setTimeout(() => startBackend(), 3000);
      }
    });

    // 等待后端启动
    logCallback('info', 'Waiting for backend to start...');
    const started = await waitForBackend();

    if (started) {
      logCallback('info', '✅ Backend started successfully');
      restartAttempts = 0;
      startHealthCheck();
      return {success: true};
    } else {
      const recentErrors = errorMessages.slice(-5);
      const errorMsg = recentErrors.length > 0 
        ? `Backend failed to start within timeout.\n\nRecent errors:\n${recentErrors.join('\n')}`
        : 'Backend failed to start within timeout. The backend process may have crashed or failed to respond.';
      logCallback('error', errorMsg);
      await stopBackend();
      return {success: false, error: errorMsg};
    }
  } catch (error) {
    const errorMsg = `Failed to spawn backend process: ${error}`;
    logCallback('error', errorMsg);
    return {success: false, error: errorMsg};
  }
}

/**
 * 停止后端进程
 * 确保所有相关进程都被彻底关闭
 */
export async function stopBackend(): Promise<void> {
  isShuttingDown = true;
  stopHealthCheck();

  const backendPid = backendProcess?.pid;
  const platform = process.platform;

  if (backendProcess) {
    logCallback('info', `Stopping backend (PID: ${backendPid})...`);

    // 首先尝试优雅关闭
    try {
      // 发送 shutdown 请求
      await new Promise<void>((resolve) => {
        const req = http.request({
          hostname: '127.0.0.1',
          port: BACKEND_PORT,
          path: '/actuator/shutdown',
          method: 'POST',
          timeout: 3000,
        }, () => resolve());

        req.on('error', () => resolve());
        req.on('timeout', () => {
          req.destroy();
          resolve();
        });

        req.end();
      });

      // 等待进程退出（缩短超时时间，快速进入强制关闭）
      await new Promise<void>((resolve) => {
        const timeout = setTimeout(() => {
          // 超时后强制终止
          if (backendProcess && !backendProcess.killed) {
            logCallback('warn', 'Backend did not exit gracefully, force killing...');
            try {
              backendProcess.kill('SIGKILL');
            } catch (e) {
              logCallback('warn', `Error killing process: ${e}`);
            }
          }
          resolve();
        }, 5000); // 缩短到 5 秒

        if (backendProcess) {
          backendProcess.once('exit', () => {
            clearTimeout(timeout);
            resolve();
          });

          // 发送 SIGTERM
          try {
            backendProcess.kill('SIGTERM');
          } catch (e) {
            logCallback('warn', `Error sending SIGTERM: ${e}`);
            clearTimeout(timeout);
            resolve();
          }
        } else {
          clearTimeout(timeout);
          resolve();
        }
      });
    } catch (error) {
      logCallback('error', `Error stopping backend: ${error}`);
    }

    // 如果进程仍然存在，强制终止
    if (backendProcess && !backendProcess.killed) {
      logCallback('warn', 'Backend process still running, force killing...');
      try {
        backendProcess.kill('SIGKILL');
        // 等待一小段时间确保进程被终止
        await new Promise(resolve => setTimeout(resolve, 500));
      } catch (e) {
        logCallback('warn', `Error force killing: ${e}`);
      }
    }

    backendProcess = null;
  }

  // 额外的清理：使用系统命令确保所有相关进程都被关闭
  // 这对于处理僵尸进程或子进程特别有用
  if (backendPid) {
    try {
      await killProcessTree(backendPid, platform);
    } catch (error) {
      logCallback('warn', `Error killing process tree: ${error}`);
    }
  }

  // 在 macOS 上，额外检查并关闭可能残留的 Java 进程
  if (platform === 'darwin') {
    try {
      await killLavisJavaProcesses();
    } catch (error) {
      logCallback('warn', `Error killing Java processes: ${error}`);
    }
  }

  isShuttingDown = false;
  logCallback('info', 'Backend stopped completely');
}

/**
 * 杀死进程树（包括所有子进程）
 */
async function killProcessTree(pid: number, platform: string): Promise<void> {
  return new Promise((resolve) => {
    let command: string;
    if (platform === 'darwin' || platform === 'linux') {
      // macOS/Linux: 使用 pkill 杀死子进程
      command = `pkill -P ${pid} 2>/dev/null || true`;
    } else {
      // Windows: 使用 taskkill
      command = `taskkill /F /T /PID ${pid} 2>nul || exit 0`;
    }

    exec(command, () => {
      // 忽略错误，因为进程可能已经不存在
      resolve();
    });
  });
}

/**
 * 在 macOS 上杀死所有 Lavis 相关的 Java 进程
 * 通过检查进程命令行参数来识别
 */
async function killLavisJavaProcesses(): Promise<void> {
  return new Promise((resolve) => {
    // 查找所有包含 lavis.jar 的 Java 进程并强制杀死
    const command = `ps aux | grep -i "lavis.jar" | grep -v grep | awk '{print $2}' | xargs kill -9 2>/dev/null || true`;
    
    exec(command, () => {
      // 忽略错误，因为可能没有找到进程
      resolve();
    });
  });
}

/**
 * 启动健康检查
 */
function startHealthCheck() {
  stopHealthCheck();

  healthCheckTimer = setInterval(async () => {
    if (isShuttingDown) return;

    const running = await isBackendRunning();
    if (!running && !isShuttingDown) {
      logCallback('warn', 'Backend health check failed, attempting restart...');

      if (restartAttempts < MAX_RESTART_ATTEMPTS) {
        restartAttempts++;
        await startBackend();
      } else {
        logCallback('error', 'Max restart attempts reached, giving up');
        stopHealthCheck();
      }
    }
  }, HEALTH_CHECK_INTERVAL);
}

/**
 * 停止健康检查
 */
function stopHealthCheck() {
  if (healthCheckTimer) {
    clearInterval(healthCheckTimer);
    healthCheckTimer = null;
  }
}

/**
 * 获取后端状态
 */
export function getBackendStatus(): {
  running: boolean;
  pid: number | null;
  restartAttempts: number;
} {
  return {
    running: backendProcess !== null && !backendProcess.killed,
    pid: backendProcess?.pid ?? null,
    restartAttempts,
  };
}

/**
 * 重置重启计数
 */
export function resetRestartAttempts() {
  restartAttempts = 0;
}
