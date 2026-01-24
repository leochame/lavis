"use strict";
/**
 * Backend Manager - 管理内嵌的 Java 后端进程
 *
 * 功能：
 * 1. 自动检测并启动内嵌的 Java 后端
 * 2. 健康检查和自动重启
 * 3. 优雅关闭
 * 4. 日志收集
 */
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || (function () {
    var ownKeys = function(o) {
        ownKeys = Object.getOwnPropertyNames || function (o) {
            var ar = [];
            for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) ar[ar.length] = k;
            return ar;
        };
        return ownKeys(o);
    };
    return function (mod) {
        if (mod && mod.__esModule) return mod;
        var result = {};
        if (mod != null) for (var k = ownKeys(mod), i = 0; i < k.length; i++) if (k[i] !== "default") __createBinding(result, mod, k[i]);
        __setModuleDefault(result, mod);
        return result;
    };
})();
Object.defineProperty(exports, "__esModule", { value: true });
exports.setLogCallback = setLogCallback;
exports.startBackend = startBackend;
exports.stopBackend = stopBackend;
exports.getBackendStatus = getBackendStatus;
exports.resetRestartAttempts = resetRestartAttempts;
const child_process_1 = require("child_process");
const path = __importStar(require("path"));
const fs = __importStar(require("fs"));
const http = __importStar(require("http"));
const electron_1 = require("electron");
// 后端配置
const BACKEND_PORT = 8080;
const HEALTH_CHECK_INTERVAL = 5000; // 5秒检查一次
const STARTUP_TIMEOUT = 60000; // 60秒启动超时
const MAX_RESTART_ATTEMPTS = 3;
// 状态
let backendProcess = null;
let healthCheckTimer = null;
let restartAttempts = 0;
let isShuttingDown = false;
let logCallback = (level, message) => {
    const prefix = level === 'error' ? '❌' : level === 'warn' ? '⚠️' : '📦';
    console.log(`${prefix} [Backend] ${message}`);
};
/**
 * 设置日志回调
 */
function setLogCallback(callback) {
    logCallback = callback;
}
/**
 * 获取资源路径
 * 开发模式：项目根目录
 * 生产模式：app.asar.unpacked 或 Resources 目录
 */
function getResourcePath() {
    if (electron_1.app.isPackaged) {
        // 生产模式
        const resourcesPath = process.resourcesPath;
        return resourcesPath;
    }
    else {
        // 开发模式 - 返回项目根目录（frontend 的父目录）
        return path.join(__dirname, '..', '..');
    }
}
/**
 * 获取 JRE 路径
 */
function getJrePath() {
    const resourcePath = getResourcePath();
    const platform = process.platform;
    if (electron_1.app.isPackaged) {
        // 生产模式 - JRE 在 Resources/jre 目录
        const jrePath = path.join(resourcePath, 'jre');
        if (platform === 'darwin') {
            return path.join(jrePath, 'Contents', 'Home', 'bin', 'java');
        }
        else if (platform === 'win32') {
            return path.join(jrePath, 'bin', 'java.exe');
        }
        else {
            return path.join(jrePath, 'bin', 'java');
        }
    }
    else {
        // 开发模式 - 使用系统 Java
        return 'java';
    }
}
/**
 * 获取 JAR 路径
 */
function getJarPath() {
    const resourcePath = getResourcePath();
    if (electron_1.app.isPackaged) {
        // 生产模式 - JAR 在 Resources/backend 目录
        return path.join(resourcePath, 'backend', 'lavis.jar');
    }
    else {
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
 * 检查后端是否已经在运行
 */
async function isBackendRunning() {
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
async function waitForBackend(timeoutMs = STARTUP_TIMEOUT) {
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
 */
async function startBackend() {
    if (isShuttingDown) {
        logCallback('warn', 'Cannot start backend during shutdown');
        return false;
    }
    // 检查是否已经在运行
    if (await isBackendRunning()) {
        logCallback('info', 'Backend is already running');
        startHealthCheck();
        return true;
    }
    const javaPath = getJrePath();
    const jarPath = getJarPath();
    logCallback('info', `Java path: ${javaPath}`);
    logCallback('info', `JAR path: ${jarPath}`);
    // 检查文件是否存在
    if (electron_1.app.isPackaged) {
        if (!fs.existsSync(javaPath)) {
            logCallback('error', `Java not found at: ${javaPath}`);
            return false;
        }
    }
    if (!fs.existsSync(jarPath)) {
        logCallback('error', `JAR not found at: ${jarPath}`);
        if (!electron_1.app.isPackaged) {
            logCallback('info', 'Development mode: Please run "mvn package" to build the JAR first');
        }
        return false;
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
    try {
        backendProcess = (0, child_process_1.spawn)(javaPath, javaArgs, {
            cwd: path.dirname(jarPath),
            stdio: ['ignore', 'pipe', 'pipe'],
            detached: false,
            env: {
                ...process.env,
                // 确保使用正确的 Java 环境
                JAVA_HOME: electron_1.app.isPackaged ? path.dirname(path.dirname(javaPath)) : process.env.JAVA_HOME,
            },
        });
        // 收集日志
        backendProcess.stdout?.on('data', (data) => {
            const lines = data.toString().split('\n').filter((l) => l.trim());
            lines.forEach((line) => {
                if (line.includes('ERROR') || line.includes('Exception')) {
                    logCallback('error', line);
                }
                else if (line.includes('WARN')) {
                    logCallback('warn', line);
                }
                else {
                    logCallback('info', line);
                }
            });
        });
        backendProcess.stderr?.on('data', (data) => {
            logCallback('error', data.toString());
        });
        backendProcess.on('error', (error) => {
            logCallback('error', `Failed to start backend: ${error.message}`);
            backendProcess = null;
        });
        backendProcess.on('exit', (code, signal) => {
            logCallback('info', `Backend exited with code ${code}, signal ${signal}`);
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
            return true;
        }
        else {
            logCallback('error', 'Backend failed to start within timeout');
            await stopBackend();
            return false;
        }
    }
    catch (error) {
        logCallback('error', `Failed to spawn backend process: ${error}`);
        return false;
    }
}
/**
 * 停止后端进程
 */
async function stopBackend() {
    isShuttingDown = true;
    stopHealthCheck();
    if (backendProcess) {
        logCallback('info', 'Stopping backend...');
        // 首先尝试优雅关闭
        try {
            // 发送 shutdown 请求
            await new Promise((resolve) => {
                const req = http.request({
                    hostname: '127.0.0.1',
                    port: BACKEND_PORT,
                    path: '/actuator/shutdown',
                    method: 'POST',
                    timeout: 5000,
                }, () => resolve());
                req.on('error', () => resolve());
                req.on('timeout', () => {
                    req.destroy();
                    resolve();
                });
                req.end();
            });
            // 等待进程退出
            await new Promise((resolve) => {
                const timeout = setTimeout(() => {
                    // 强制终止
                    if (backendProcess) {
                        logCallback('warn', 'Force killing backend process');
                        backendProcess.kill('SIGKILL');
                    }
                    resolve();
                }, 10000);
                if (backendProcess) {
                    backendProcess.once('exit', () => {
                        clearTimeout(timeout);
                        resolve();
                    });
                    // 发送 SIGTERM
                    backendProcess.kill('SIGTERM');
                }
                else {
                    clearTimeout(timeout);
                    resolve();
                }
            });
        }
        catch (error) {
            logCallback('error', `Error stopping backend: ${error}`);
            // 强制终止
            if (backendProcess) {
                backendProcess.kill('SIGKILL');
            }
        }
        backendProcess = null;
        logCallback('info', 'Backend stopped');
    }
    isShuttingDown = false;
}
/**
 * 启动健康检查
 */
function startHealthCheck() {
    stopHealthCheck();
    healthCheckTimer = setInterval(async () => {
        if (isShuttingDown)
            return;
        const running = await isBackendRunning();
        if (!running && !isShuttingDown) {
            logCallback('warn', 'Backend health check failed, attempting restart...');
            if (restartAttempts < MAX_RESTART_ATTEMPTS) {
                restartAttempts++;
                await startBackend();
            }
            else {
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
function getBackendStatus() {
    return {
        running: backendProcess !== null && !backendProcess.killed,
        pid: backendProcess?.pid ?? null,
        restartAttempts,
    };
}
/**
 * 重置重启计数
 */
function resetRestartAttempts() {
    restartAttempts = 0;
}
