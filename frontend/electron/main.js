"use strict";
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
const electron_1 = require("electron");
const path = __importStar(require("path"));
const http = __importStar(require("http"));
const backend_manager_1 = require("./backend-manager");
let mainWindow = null;
let tray = null;
// 使用 app.isPackaged 作为主要判断依据，这是最可靠的方式
// 环境变量作为开发模式的辅助判断
const isDev = !electron_1.app.isPackaged ||
    process.env.NODE_ENV === 'development' ||
    process.env.ELECTRON_DEV === '1' ||
    !!process.env.VITE_DEV_SERVER_URL;
const isMac = process.platform === 'darwin';
// ENV: set ELECTRON_OPAQUE=1 to force opaque framed window for debugging on devices/GPUs
const preferTransparent = isMac && process.env.ELECTRON_OPAQUE !== '1';
// ENV: ELECTRON_DEVTOOLS=1 to allow toggling DevTools (default off to avoid "像浏览器")
// 开发模式下默认允许 DevTools
const allowDevTools = isDev || process.env.ELECTRON_DEVTOOLS === '1';
// 统一窗口尺寸定义
// - Idle: 隐藏或极小（80x80，与 capsule 相同）
// - Listening: Mini 模式 (200x60px) - 语音唤醒/监听中
// - Expanded: Full 模式 (800x600px) - 交互展开
const WINDOW_BOUNDS = {
    idle: { width: 80, height: 80 },
    listening: { width: 200, height: 60 },
    expanded: { width: 800, height: 600 },
    // 兼容旧模式
    capsule: { width: 80, height: 80 },
    chat: { width: 960, height: 640 },
};
// 边缘吸附配置
const SNAP_THRESHOLD = 30; // 吸附阈值 (px)
const SNAP_MAGNETIC_RANGE = 80; // 磁性吸附范围 (px)
let currentMode = 'capsule';
let isSnappedToEdge = false;
let snapPosition = null;
let isHalfHidden = false; // 是否处于半隐藏状态
// 拖拽状态
let isDragging = false;
let dragStartPos = { x: 0, y: 0 };
let windowStartPos = { x: 0, y: 0 };
// 置顶定时器 - 定期确保窗口置顶
let alwaysOnTopInterval = null;
/**
 * 检查并请求麦克风权限 (macOS)
 */
async function checkAndRequestMicrophonePermission() {
    if (process.platform !== 'darwin') {
        return true; // 非 macOS 平台直接返回 true
    }
    const status = electron_1.systemPreferences.getMediaAccessStatus('microphone');
    console.log(`🎤 Microphone permission status: ${status}`);
    if (status === 'granted') {
        return true;
    }
    if (status === 'not-determined') {
        console.log('🎤 Requesting microphone permission...');
        const granted = await electron_1.systemPreferences.askForMediaAccess('microphone');
        console.log(`🎤 Microphone permission ${granted ? 'granted' : 'denied'}`);
        return granted;
    }
    // status === 'denied' 或 'restricted'
    console.warn('⚠️ Microphone permission denied. Please enable it in System Preferences > Privacy & Security > Microphone');
    return false;
}
function createWindow() {
    // 胶囊模式的初始尺寸
    const capsuleBounds = WINDOW_BOUNDS.capsule;
    // 获取主显示器，计算初始位置（右下角偏移）
    const primaryDisplay = electron_1.screen.getPrimaryDisplay();
    const { workArea } = primaryDisplay;
    const initialX = workArea.x + workArea.width - capsuleBounds.width - 100;
    const initialY = workArea.y + workArea.height - capsuleBounds.height - 100;
    const windowOptions = {
        width: capsuleBounds.width,
        height: capsuleBounds.height,
        x: initialX,
        y: initialY,
        // Transparent glass on macOS by default; fallback to opaque via ELECTRON_OPAQUE=1
        transparent: preferTransparent,
        frame: false, // 始终无边框，实现悬浮胶囊效果
        alwaysOnTop: true, // 胶囊模式始终置顶
        resizable: false,
        skipTaskbar: true, // 不在任务栏显示
        movable: true, // 确保窗口可移动
        // 透明窗口不需要背景色
        backgroundColor: '#00000000',
        // Hide the classic browser menu/toolbar to reduce "browser" feel
        autoHideMenuBar: true,
        // 圆形窗口需要这些设置
        hasShadow: false, // 透明窗口禁用系统阴影，由 CSS 控制
        // macOS: 设置窗口级别为悬浮面板
        type: isMac ? 'panel' : undefined,
        webPreferences: {
            preload: path.join(__dirname, 'preload.js'),
            nodeIntegration: false,
            contextIsolation: true,
            webSecurity: true,
            // 关键：禁用后台节流，确保唤醒词检测在窗口最小化/失焦时仍能正常工作
            backgroundThrottling: false,
            // 默认禁用 DevTools，只有在显式开启环境变量时才允许
            devTools: allowDevTools,
        },
    };
    const vibrancy = windowOptions.vibrancy ?? 'none';
    console.log(`🪟 Creating window | transparent=${windowOptions.transparent} frame=${windowOptions.frame} vibrancy=${vibrancy} size=${capsuleBounds.width}x${capsuleBounds.height}`);
    mainWindow = new electron_1.BrowserWindow(windowOptions);
    currentMode = 'capsule'; // 初始为胶囊模式
    // 强制设置置顶 - 使用最高级别
    enforceAlwaysOnTop();
    // 启动置顶保持定时器（每 500ms 检查一次）
    startAlwaysOnTopEnforcer();
    // 移除默认菜单，避免出现浏览器菜单栏
    electron_1.Menu.setApplicationMenu(null);
    // Renderer diagnostics: surface "why it's blank/transparent" to main process logs.
    mainWindow.webContents.on('did-fail-load', (_event, errorCode, errorDescription, validatedURL) => {
        console.error('❌ did-fail-load:', { errorCode, errorDescription, validatedURL });
    });
    mainWindow.webContents.on('render-process-gone', (_event, details) => {
        console.error('❌ render-process-gone:', details);
    });
    mainWindow.webContents.on('unresponsive', () => {
        console.error('❌ renderer unresponsive');
    });
    mainWindow.webContents.on('console-message', (_event, level, message, line, sourceId) => {
        // level: 0=log, 1=warn, 2=error
        const tag = level === 2 ? '🟥' : level === 1 ? '🟨' : '⬜️';
        console.log(`${tag} [renderer] ${message} (${sourceId}:${line})`);
    });
    // Load the app
    if (isDev) {
        mainWindow.loadURL('http://localhost:5173');
    }
    else {
        mainWindow.loadFile(path.join(__dirname, '../dist/index.html'));
    }
    // 如果设置了环境变量，自动打开开发者工具
    if (process.env.ELECTRON_DEVTOOLS === '1' || process.env.OPEN_DEVTOOLS === '1') {
        mainWindow.webContents.openDevTools();
        console.log('🔧 DevTools opened via environment variable');
    }
    // 提供快捷键手动打开/关闭 DevTools
    // 支持 Cmd+Alt+I (macOS) 或 Ctrl+Alt+I (Windows/Linux)
    mainWindow.webContents.on('before-input-event', (event, input) => {
        const isToggle = input.key?.toLowerCase() === 'i' &&
            input.control &&
            (input.meta || input.alt);
        if (isToggle && allowDevTools) {
            mainWindow?.webContents.toggleDevTools();
            event.preventDefault();
        }
    });
    mainWindow.on('closed', () => {
        mainWindow = null;
        stopAlwaysOnTopEnforcer();
        // 如果窗口关闭且没有其他窗口，退出应用
        if (electron_1.BrowserWindow.getAllWindows().length === 0) {
            electron_1.app.quit();
        }
    });
    // 监听窗口失去焦点时重新置顶
    mainWindow.on('blur', () => {
        if (currentMode === 'capsule' && mainWindow) {
            enforceAlwaysOnTop();
        }
    });
}
/**
 * 强制设置窗口置顶
 */
function enforceAlwaysOnTop() {
    if (!mainWindow)
        return;
    // 胶囊模式始终置顶
    if (currentMode === 'capsule' || currentMode === 'idle' || currentMode === 'listening') {
        // macOS 使用 'screen-saver' 级别，Windows 使用 'pop-up-menu'
        const level = isMac ? 'screen-saver' : 'pop-up-menu';
        mainWindow.setAlwaysOnTop(true, level);
        // macOS 额外设置：确保在所有桌面可见
        if (isMac) {
            mainWindow.setVisibleOnAllWorkspaces(true, { visibleOnFullScreen: true });
        }
    }
}
/**
 * 启动置顶保持定时器
 */
function startAlwaysOnTopEnforcer() {
    if (alwaysOnTopInterval)
        return;
    alwaysOnTopInterval = setInterval(() => {
        if (mainWindow && (currentMode === 'capsule' || currentMode === 'idle' || currentMode === 'listening')) {
            if (!mainWindow.isAlwaysOnTop()) {
                console.log('📌 Re-enforcing alwaysOnTop');
                enforceAlwaysOnTop();
            }
        }
    }, 500);
}
/**
 * 停止置顶保持定时器
 */
function stopAlwaysOnTopEnforcer() {
    if (alwaysOnTopInterval) {
        clearInterval(alwaysOnTopInterval);
        alwaysOnTopInterval = null;
    }
}
/**
 * 计算磁性吸附位置
 * 在拖拽过程中实时计算，提供丝滑的吸附体验
 */
function calculateSnapPosition(x, y, width, height) {
    const display = electron_1.screen.getDisplayNearestPoint({ x: x + width / 2, y: y + height / 2 });
    const { workArea } = display;
    let newX = x;
    let newY = y;
    let snapped = false;
    let edge = null;
    // 检查左边缘
    if (x < workArea.x + SNAP_MAGNETIC_RANGE) {
        if (x < workArea.x + SNAP_THRESHOLD) {
            newX = workArea.x;
            snapped = true;
            edge = 'left';
        }
    }
    // 检查右边缘
    else if (x + width > workArea.x + workArea.width - SNAP_MAGNETIC_RANGE) {
        if (x + width > workArea.x + workArea.width - SNAP_THRESHOLD) {
            newX = workArea.x + workArea.width - width;
            snapped = true;
            edge = 'right';
        }
    }
    // 检查上边缘
    if (y < workArea.y + SNAP_MAGNETIC_RANGE) {
        if (y < workArea.y + SNAP_THRESHOLD) {
            newY = workArea.y;
            snapped = true;
            edge = edge || 'top';
        }
    }
    // 检查下边缘
    else if (y + height > workArea.y + workArea.height - SNAP_MAGNETIC_RANGE) {
        if (y + height > workArea.y + workArea.height - SNAP_THRESHOLD) {
            newY = workArea.y + workArea.height - height;
            snapped = true;
            edge = edge || 'bottom';
        }
    }
    return { x: newX, y: newY, snapped, edge };
}
/**
 * 半隐藏窗口 - 收缩到边缘只露出一小部分
 */
function halfHideWindow() {
    if (!mainWindow || !snapPosition)
        return;
    const [x, y] = mainWindow.getPosition();
    const [width, height] = mainWindow.getSize();
    const visiblePart = 16; // 露出 16px
    let newX = x;
    let newY = y;
    switch (snapPosition) {
        case 'left':
            newX = -(width - visiblePart);
            break;
        case 'right':
            const display = electron_1.screen.getDisplayNearestPoint({ x, y });
            newX = display.workArea.x + display.workArea.width - visiblePart;
            break;
        case 'top':
            newY = -(height - visiblePart);
            break;
        case 'bottom':
            const displayB = electron_1.screen.getDisplayNearestPoint({ x, y });
            newY = displayB.workArea.y + displayB.workArea.height - visiblePart;
            break;
    }
    mainWindow.setPosition(newX, newY, true);
    isHalfHidden = true;
    console.log(`👻 Window half-hidden at ${snapPosition} edge`);
}
/**
 * 从半隐藏状态恢复完整显示
 */
function showFullWindow() {
    if (!mainWindow || !snapPosition)
        return;
    const [x, y] = mainWindow.getPosition();
    const [width, height] = mainWindow.getSize();
    const display = electron_1.screen.getDisplayNearestPoint({ x: x + width / 2, y: y + height / 2 });
    const { workArea } = display;
    let newX = x;
    let newY = y;
    switch (snapPosition) {
        case 'left':
            newX = workArea.x;
            break;
        case 'right':
            newX = workArea.x + workArea.width - width;
            break;
        case 'top':
            newY = workArea.y;
            break;
        case 'bottom':
            newY = workArea.y + workArea.height - height;
            break;
    }
    mainWindow.setPosition(newX, newY, true);
    isHalfHidden = false;
    console.log(`👁️ Window restored from half-hidden`);
}
// 记录胶囊位置，用于展开动画
let lastCapsulePosition = null;
function resizeWindowByMode(mode) {
    if (!mainWindow)
        return;
    // 如果从胶囊切换到聊天/展开模式，记录当前位置
    if ((currentMode === 'capsule' || currentMode === 'idle' || currentMode === 'listening') &&
        (mode === 'chat' || mode === 'expanded')) {
        const [x, y] = mainWindow.getPosition();
        lastCapsulePosition = { x, y };
    }
    currentMode = mode; // 跟踪当前模式
    const bounds = WINDOW_BOUNDS[mode] || WINDOW_BOUNDS.capsule;
    if (mode === 'chat' || mode === 'expanded') {
        // 从胶囊位置展开到聊天/展开模式
        // 先设置大小，再移动到中心（带动画效果）
        const display = electron_1.screen.getPrimaryDisplay();
        const { workArea } = display;
        // 计算窗口的中心位置
        const centerX = Math.round(workArea.x + (workArea.width - bounds.width) / 2);
        const centerY = Math.round(workArea.y + (workArea.height - bounds.height) / 2);
        // 如果有记录的胶囊位置，从那里开始动画
        if (lastCapsulePosition) {
            // 先保持在胶囊位置
            mainWindow.setPosition(lastCapsulePosition.x, lastCapsulePosition.y);
        }
        // 设置新大小
        mainWindow.setSize(bounds.width, bounds.height);
        mainWindow.setResizable(mode === 'expanded' || mode === 'chat');
        // 移动到中心（带动画）
        mainWindow.setPosition(centerX, centerY, true);
        isSnappedToEdge = false;
        snapPosition = null;
        isHalfHidden = false;
    }
    else {
        // 切换到胶囊/监听模式
        mainWindow.setSize(bounds.width, bounds.height);
        mainWindow.setResizable(false);
        // 如果有记录的位置，恢复到那里
        if (lastCapsulePosition) {
            mainWindow.setPosition(lastCapsulePosition.x, lastCapsulePosition.y, true);
        }
    }
    // 胶囊/监听模式：始终置顶
    // 聊天/展开模式：取消置顶
    const shouldBeOnTop = mode === 'capsule' || mode === 'idle' || mode === 'listening';
    if (shouldBeOnTop) {
        enforceAlwaysOnTop();
    }
    else {
        mainWindow.setAlwaysOnTop(false);
        // macOS: 取消在所有桌面可见
        if (isMac) {
            mainWindow.setVisibleOnAllWorkspaces(false);
        }
    }
    console.log(`📌 Window alwaysOnTop: ${shouldBeOnTop} (mode: ${mode})`);
}
/**
 * 调整窗口到 Mini 模式（Listening 状态）
 */
function resizeWindowMini() {
    resizeWindowByMode('listening');
}
/**
 * 调整窗口到 Full 模式（Expanded 状态）
 */
function resizeWindowFull() {
    resizeWindowByMode('expanded');
}
// 兼容旧 IPC 通道
electron_1.ipcMain.on('resize-window', (_, { mode }) => {
    resizeWindowByMode(mode);
});
// Handle show/hide window
electron_1.ipcMain.on('show-window', () => {
    if (mainWindow) {
        mainWindow.show();
        mainWindow.focus();
    }
});
electron_1.ipcMain.on('hide-window', () => {
    if (mainWindow) {
        mainWindow.hide();
    }
});
// 胶囊右键菜单
electron_1.ipcMain.on('show-context-menu', () => {
    if (!mainWindow)
        return;
    const contextMenu = electron_1.Menu.buildFromTemplate([
        {
            label: '展开面板',
            click: () => {
                resizeWindowByMode('chat');
                mainWindow?.webContents.send('switch-to-chat');
            },
        },
        {
            label: '固定位置',
            type: 'checkbox',
            checked: false,
            click: (menuItem) => {
                // TODO: 实现固定位置功能
                console.log('Pin position:', menuItem.checked);
            },
        },
        { type: 'separator' },
        {
            label: '开发者工具',
            click: () => {
                if (mainWindow) {
                    mainWindow.webContents.openDevTools();
                }
            },
        },
        { type: 'separator' },
        {
            label: '设置',
            click: () => {
                resizeWindowByMode('chat');
                mainWindow?.webContents.send('open-settings');
            },
        },
        { type: 'separator' },
        {
            label: '退出 Lavis',
            click: () => {
                // 关闭所有窗口并退出
                electron_1.BrowserWindow.getAllWindows().forEach(window => {
                    window.destroy();
                });
                electron_1.app.quit();
            },
        },
    ]);
    contextMenu.popup({ window: mainWindow });
});
// 新版平台抽象 IPC：使用 invoke/handle，便于 preload 暴露受控 API
electron_1.ipcMain.handle('platform:resize-window', (_event, { mode }) => {
    resizeWindowByMode(mode);
});
// 窗口状态 IPC：支持新的窗口状态管理
electron_1.ipcMain.handle('resize-window-mini', () => {
    resizeWindowMini();
});
electron_1.ipcMain.handle('resize-window-full', () => {
    resizeWindowFull();
});
electron_1.ipcMain.handle('platform:minimize', () => {
    if (mainWindow)
        mainWindow.minimize();
});
electron_1.ipcMain.handle('platform:hide', () => {
    if (mainWindow)
        mainWindow.hide();
});
electron_1.ipcMain.handle('platform:set-always-on-top', (_event, { flag }) => {
    if (mainWindow)
        mainWindow.setAlwaysOnTop(flag, 'screen-saver');
});
// 透明区域鼠标穿透控制
// forward: true 表示穿透透明区域，false 表示不穿透
electron_1.ipcMain.handle('platform:set-ignore-mouse', (_event, { ignore, forward }) => {
    if (!mainWindow)
        return;
    if (ignore) {
        // 开启穿透，forward=true 时仅穿透透明区域
        mainWindow.setIgnoreMouseEvents(true, { forward: forward ?? true });
    }
    else {
        // 关闭穿透
        mainWindow.setIgnoreMouseEvents(false);
    }
});
// 获取当前吸附状态
electron_1.ipcMain.handle('platform:get-snap-state', () => {
    return { isSnapped: isSnappedToEdge, position: snapPosition };
});
// 获取后端状态
electron_1.ipcMain.handle('platform:get-backend-status', () => {
    return (0, backend_manager_1.getBackendStatus)();
});
// 重启后端
electron_1.ipcMain.handle('platform:restart-backend', async () => {
    console.log('🔄 Restarting backend...');
    await (0, backend_manager_1.stopBackend)();
    return await (0, backend_manager_1.startBackend)();
});
// ============================================
// 拖拽相关 IPC - 实现丝滑拖拽和边缘吸附
// ============================================
// 开始拖拽
electron_1.ipcMain.handle('platform:drag-start', (_event, { mouseX, mouseY }) => {
    if (!mainWindow)
        return;
    isDragging = true;
    dragStartPos = { x: mouseX, y: mouseY };
    const [winX, winY] = mainWindow.getPosition();
    windowStartPos = { x: winX, y: winY };
    // 如果处于半隐藏状态，先恢复
    if (isHalfHidden) {
        showFullWindow();
    }
    console.log('🖱️ Drag started');
});
// 拖拽移动
electron_1.ipcMain.handle('platform:drag-move', (_event, { mouseX, mouseY }) => {
    if (!mainWindow || !isDragging)
        return;
    const deltaX = mouseX - dragStartPos.x;
    const deltaY = mouseY - dragStartPos.y;
    let newX = windowStartPos.x + deltaX;
    let newY = windowStartPos.y + deltaY;
    // 直接设置位置，不使用动画以保证流畅
    mainWindow.setPosition(Math.round(newX), Math.round(newY), false);
});
// 结束拖拽
electron_1.ipcMain.handle('platform:drag-end', () => {
    if (!mainWindow || !isDragging)
        return;
    isDragging = false;
    // 只在胶囊模式下执行吸附
    if (currentMode !== 'capsule')
        return;
    const [x, y] = mainWindow.getPosition();
    const [width, height] = mainWindow.getSize();
    const snap = calculateSnapPosition(x, y, width, height);
    if (snap.snapped) {
        // 使用动画吸附到边缘
        mainWindow.setPosition(snap.x, snap.y, true);
        isSnappedToEdge = true;
        snapPosition = snap.edge;
        console.log(`🧲 Snapped to ${snap.edge} edge`);
    }
    else {
        isSnappedToEdge = false;
        snapPosition = null;
    }
});
// 获取窗口位置
electron_1.ipcMain.handle('platform:get-window-position', () => {
    if (!mainWindow)
        return { x: 0, y: 0 };
    const [x, y] = mainWindow.getPosition();
    return { x, y };
});
// 设置窗口位置
electron_1.ipcMain.handle('platform:set-window-position', (_event, { x, y, animate }) => {
    if (!mainWindow)
        return;
    mainWindow.setPosition(Math.round(x), Math.round(y), animate ?? false);
});
electron_1.ipcMain.handle('platform:get-screenshot', async () => {
    try {
        const sources = await electron_1.desktopCapturer.getSources({
            types: ['screen'],
            thumbnailSize: { width: 1920, height: 1080 },
        });
        if (!sources.length)
            return null;
        const primary = sources[0];
        return primary.thumbnail?.toDataURL() ?? null;
    }
    catch (error) {
        console.error('[platform:get-screenshot] failed:', error);
        return null;
    }
});
electron_1.ipcMain.handle('platform:open-external', (_event, { url }) => {
    electron_1.shell.openExternal(url).catch((err) => {
        console.error('[platform:open-external] failed:', err);
    });
});
electron_1.ipcMain.handle('platform:check-mic', async () => {
    return checkAndRequestMicrophonePermission();
});
electron_1.ipcMain.handle('platform:register-shortcut', (_event, { accelerator, action }) => {
    if (action !== 'toggle-window')
        return false;
    electron_1.globalShortcut.unregister(accelerator);
    const success = electron_1.globalShortcut.register(accelerator, () => {
        if (!mainWindow)
            return;
        if (mainWindow.isVisible()) {
            mainWindow.hide();
        }
        else {
            mainWindow.show();
            mainWindow.focus();
        }
    });
    return success;
});
// Backend API proxy - 允许渲染进程通过 IPC 发送请求到后端
// 这解决了在 Electron 环境中可能遇到的 CORS 或网络问题
electron_1.ipcMain.handle('backend:request', async (_event, { method, endpoint, data, port = 8080 }) => {
    return new Promise((resolve, reject) => {
        // 使用 127.0.0.1 而不是 localhost，避免 DNS 解析问题
        // 这在 Electron 环境中可以防止 DNS 相关的崩溃
        const options = {
            method,
            hostname: '127.0.0.1', // 直接使用 IP 地址，避免 DNS 解析
            port: port,
            path: `/api/agent${endpoint}`,
            headers: {
                'Content-Type': 'application/json',
            },
            timeout: 30000, // 30秒超时，防止请求挂起
        };
        const req = http.request(options, (res) => {
            let responseData = '';
            res.on('data', (chunk) => {
                responseData += chunk;
            });
            res.on('end', () => {
                try {
                    const jsonData = responseData ? JSON.parse(responseData) : {};
                    if (res.statusCode && res.statusCode >= 200 && res.statusCode < 300) {
                        resolve({ status: res.statusCode, data: jsonData });
                    }
                    else {
                        reject(new Error(`HTTP ${res.statusCode}: ${JSON.stringify(jsonData)}`));
                    }
                }
                catch (error) {
                    reject(new Error(`Failed to parse response: ${error}`));
                }
            });
        });
        // 添加超时处理
        req.on('timeout', () => {
            req.destroy();
            reject(new Error('Request timeout'));
        });
        req.on('error', (error) => {
            console.error('[backend:request] Error:', error);
            // 检查是否是 DNS 相关错误
            if (error.message.includes('ENOTFOUND') || error.message.includes('EAI_AGAIN')) {
                console.error('[backend:request] DNS resolution error, using 127.0.0.1');
            }
            reject(error);
        });
        if (data && (method === 'POST' || method === 'PUT')) {
            req.write(JSON.stringify(data));
        }
        req.end();
    });
});
// Handle app quit
electron_1.app.on('window-all-closed', () => {
    // 在所有平台上都退出应用，而不是在 macOS 上保持运行
    // 如果用户想要退出，应该完全退出，而不是继续在后台运行
    electron_1.app.quit();
});
electron_1.app.on('activate', () => {
    // 只有在应用没有退出意图时才重新创建窗口
    // 如果用户已经关闭了所有窗口并退出，不应该重新创建
    if (electron_1.BrowserWindow.getAllWindows().length === 0 && mainWindow === null) {
        createWindow();
    }
    else if (mainWindow) {
        // 如果窗口存在但被隐藏，显示它
        mainWindow.show();
        mainWindow.focus();
    }
});
// Global hotkey (Option+Space or Alt+Space)
electron_1.app.whenReady().then(async () => {
    // 设置后端日志回调
    (0, backend_manager_1.setLogCallback)((level, message) => {
        const prefix = level === 'error' ? '❌' : level === 'warn' ? '⚠️' : '📦';
        console.log(`${prefix} [Backend] ${message}`);
        // 如果窗口已创建，发送日志到渲染进程
        if (mainWindow) {
            mainWindow.webContents.send('backend-log', { level, message });
        }
    });
    // 启动后端服务
    console.log('🚀 Starting backend service...');
    const backendResult = await (0, backend_manager_1.startBackend)();
    if (!backendResult.success) {
        console.error('❌ Failed to start backend service');
        if (backendResult.error) {
            console.error('Error details:', backendResult.error);
        }
        // 在开发模式下显示警告，但继续启动
        if (!electron_1.app.isPackaged) {
            console.warn('⚠️ Development mode: Please ensure the backend JAR is built (mvn package)');
            console.warn('⚠️ Or start the backend manually: mvn spring-boot:run');
        }
        else {
            // 生产模式下显示错误对话框，包含详细错误信息
            const errorMessage = backendResult.error
                ? `Failed to start the backend service.\n\n${backendResult.error}\n\nPlease check the console logs or reinstall the application.`
                : 'Failed to start the backend service. Please check the logs or reinstall the application.';
            electron_1.dialog.showErrorBox('Backend Error', errorMessage);
        }
    }
    else {
        console.log('✅ Backend service started successfully');
    }
    // 首先检查并请求麦克风权限
    const micPermission = await checkAndRequestMicrophonePermission();
    if (!micPermission) {
        console.warn('⚠️ App started without microphone permission. Wake word detection may not work.');
    }
    // Register global hotkey
    electron_1.globalShortcut.register('CommandOrControl+Space', () => {
        if (!mainWindow)
            return;
        if (mainWindow.isVisible()) {
            mainWindow.hide();
        }
        else {
            mainWindow.show();
            mainWindow.focus();
        }
    });
    // Register global hotkey for DevTools (Cmd+Shift+I or Ctrl+Shift+I)
    electron_1.globalShortcut.register('CommandOrControl+Shift+I', () => {
        if (mainWindow) {
            mainWindow.webContents.toggleDevTools();
        }
    });
    createWindow();
    // Create system tray
    createTray();
});
// Clean up before quit
electron_1.app.on('before-quit', (event) => {
    // 标记应用正在退出，防止其他操作干扰
    console.log('🛑 Application is quitting...');
});
// Clean up on quit
electron_1.app.on('will-quit', async (event) => {
    // 阻止默认退出，等待后端关闭
    event.preventDefault();
    // 取消注册所有全局快捷键
    electron_1.globalShortcut.unregisterAll();
    // 停止置顶定时器
    stopAlwaysOnTopEnforcer();
    // 销毁系统托盘
    if (tray) {
        tray.destroy();
        tray = null;
    }
    // 确保所有窗口都已关闭
    const windows = electron_1.BrowserWindow.getAllWindows();
    windows.forEach(window => {
        if (!window.isDestroyed()) {
            window.destroy();
        }
    });
    // 停止后端服务
    console.log('🛑 Stopping backend service...');
    try {
        await (0, backend_manager_1.stopBackend)();
        console.log('✅ Backend service stopped');
    }
    catch (error) {
        console.error('❌ Error stopping backend:', error);
    }
    // 现在可以退出了
    electron_1.app.exit(0);
});
function createTray() {
    // Create a simple tray icon (in production, replace with actual icon file)
    const icon = electron_1.nativeImage.createEmpty();
    tray = new electron_1.Tray(icon);
    const contextMenu = electron_1.Menu.buildFromTemplate([
        {
            label: 'Show/Hide',
            click: () => {
                if (mainWindow) {
                    if (mainWindow.isVisible()) {
                        mainWindow.hide();
                    }
                    else {
                        mainWindow.show();
                        mainWindow.focus();
                    }
                }
            },
        },
        {
            label: 'Settings',
            click: () => {
                if (mainWindow) {
                    if (!mainWindow.isVisible()) {
                        mainWindow.show();
                    }
                    mainWindow.focus();
                    mainWindow.webContents.send('open-settings');
                }
            },
        },
        { type: 'separator' },
        {
            label: 'Quit',
            click: () => {
                // 关闭所有窗口并退出
                electron_1.BrowserWindow.getAllWindows().forEach(window => {
                    window.destroy();
                });
                electron_1.app.quit();
            },
        },
    ]);
    tray.setToolTip('Lavis AI');
    tray.setContextMenu(contextMenu);
    tray.on('click', () => {
        if (mainWindow) {
            if (mainWindow.isVisible()) {
                mainWindow.hide();
            }
            else {
                mainWindow.show();
                mainWindow.focus();
            }
        }
    });
}
