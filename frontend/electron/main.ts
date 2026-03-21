import { app, BrowserWindow, globalShortcut, ipcMain, Tray, Menu, nativeImage, systemPreferences, desktopCapturer, shell, screen, dialog } from 'electron';
import * as path from 'path';
import * as http from 'http';
import { startBackend, stopBackend, getBackendStatus, setLogCallback } from './backend-manager';

let mainWindow: BrowserWindow | null = null;
let tray: Tray | null = null;
let isQuitting = false;

// 使用 app.isPackaged 作为主要判断依据，这是最可靠的方式
// 环境变量作为开发模式的辅助判断
const isDev = !app.isPackaged ||
  process.env.NODE_ENV === 'development' ||
  process.env.ELECTRON_DEV === '1' ||
  !!process.env.VITE_DEV_SERVER_URL;
const isMac = process.platform === 'darwin';
// ENV: set ELECTRON_OPAQUE=1 to force opaque framed window for debugging on devices/GPUs
const preferTransparent = isMac && process.env.ELECTRON_OPAQUE !== '1';
// ENV: ELECTRON_DEVTOOLS=1 to allow toggling DevTools (default off to avoid "像浏览器")
// 开发模式下默认允许 DevTools
const allowDevTools = isDev || process.env.ELECTRON_DEVTOOLS === '1';

function resolveAppIconPath(): string | null {
  const candidates = app.isPackaged
    ? [path.join(process.resourcesPath, 'icon.png')]
    : [path.join(__dirname, '..', '..', 'docs', 'images', 'icon.png')];

  for (const candidate of candidates) {
    const image = nativeImage.createFromPath(candidate);
    if (!image.isEmpty()) {
      return candidate;
    }
  }
  return null;
}

// 统一窗口尺寸定义
// - Idle: 隐藏或极小（80x80，与 capsule 相同）
// - Listening: Mini 模式 (200x60px) - 语音唤醒/监听中
// - Expanded/Chat: 默认放大，提升信息密度与可读性
const WINDOW_BOUNDS: Record<'idle' | 'listening' | 'expanded' | 'capsule' | 'chat', { width: number; height: number }> = {
  idle: { width: 80, height: 80 },
  listening: { width: 200, height: 60 },
  expanded: { width: 1080, height: 760 },
  // 兼容旧模式
  capsule: { width: 80, height: 80 },
  chat: { width: 1200, height: 820 },
};

// 主界面允许继续缩放，但不低于该阈值
const WINDOW_MIN_BOUNDS: Record<'expanded' | 'chat', { width: number; height: number }> = {
  expanded: { width: 860, height: 620 },
  chat: { width: 960, height: 680 },
};

// 边缘吸附配置
const SNAP_THRESHOLD = 30; // 吸附阈值 (px)
const SNAP_MAGNETIC_RANGE = 80; // 磁性吸附范围 (px)
let currentMode: 'capsule' | 'chat' | 'idle' | 'listening' | 'expanded' = 'capsule';
let isSnappedToEdge = false;
let snapPosition: 'left' | 'right' | 'top' | 'bottom' | null = null;

// 拖拽状态
let isDragging = false;
let dragStartPos = { x: 0, y: 0 };
let windowStartPos = { x: 0, y: 0 };

// 置顶定时器 - 定期确保窗口置顶
let alwaysOnTopInterval: NodeJS.Timeout | null = null;

/**
 * 检查并请求麦克风权限 (macOS)
 */
async function checkAndRequestMicrophonePermission(): Promise<boolean> {
  if (process.platform !== 'darwin') {
    return true; // 非 macOS 平台直接返回 true
  }

  const status = systemPreferences.getMediaAccessStatus('microphone');
  console.log(`🎤 Microphone permission status: ${status}`);

  if (status === 'granted') {
    return true;
  }

  if (status === 'not-determined') {
    console.log('🎤 Requesting microphone permission...');
    const granted = await systemPreferences.askForMediaAccess('microphone');
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
  const primaryDisplay = screen.getPrimaryDisplay();
  const { workArea } = primaryDisplay;
  const initialX = workArea.x + workArea.width - capsuleBounds.width - 100;
  const initialY = workArea.y + workArea.height - capsuleBounds.height - 100;

  const windowOptions: Electron.BrowserWindowConstructorOptions = {
    width: capsuleBounds.width,
    height: capsuleBounds.height,
    // 胶囊模式下的最小尺寸（不可再缩小）
    minWidth: capsuleBounds.width,
    minHeight: capsuleBounds.height,
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
    icon: resolveAppIconPath() ?? undefined,
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

  const vibrancy = (windowOptions as { vibrancy?: string }).vibrancy ?? 'none';
  console.log(`🪟 Creating window | transparent=${windowOptions.transparent} frame=${windowOptions.frame} vibrancy=${vibrancy} size=${capsuleBounds.width}x${capsuleBounds.height}`);

  mainWindow = new BrowserWindow(windowOptions);
  currentMode = 'capsule'; // 初始为胶囊模式

  // 强制设置置顶 - 使用最高级别
  enforceAlwaysOnTop();

  // 启动置顶保持定时器（每 500ms 检查一次）
  startAlwaysOnTopEnforcer();

  // 移除默认菜单，避免出现浏览器菜单栏
  Menu.setApplicationMenu(null);

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
  } else {
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
    const isToggle =
      input.key?.toLowerCase() === 'i' &&
      input.control &&
      (input.meta || input.alt);
    if (isToggle && allowDevTools) {
      mainWindow?.webContents.toggleDevTools();
      event.preventDefault();
    }
  });

  // 智能关闭处理：根据模式决定行为
  mainWindow.on('close', (event) => {
    // 如果正在退出过程中，允许关闭（由 will-quit 处理清理）
    if (isQuitting) {
      return;
    }

    // 控制板模式（chat/expanded）：阻止关闭，切换回胶囊模式
    if (currentMode === 'chat' || currentMode === 'expanded') {
      event.preventDefault();
      console.log('📋 Control panel closed, switching back to capsule mode');
      resizeWindowByMode('capsule');
      // 通知渲染进程切换回胶囊模式
      if (mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.webContents.send('switch-to-capsule');
      }
      return;
    }

    // 胶囊模式（capsule/idle/listening）：完全退出应用
    // 这会触发 before-quit -> will-quit 事件链，自动关闭后端
    console.log('💊 Capsule closed, quitting application');
    // 标记正在退出，避免重复触发
    isQuitting = true;
    app.quit();
  });

  mainWindow.on('closed', () => {
    mainWindow = null;
    stopAlwaysOnTopEnforcer();
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
  if (!mainWindow) return;

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
  if (alwaysOnTopInterval) return;

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
function calculateSnapPosition(x: number, y: number, width: number, height: number): { x: number; y: number; snapped: boolean; edge: typeof snapPosition } {
  const display = screen.getDisplayNearestPoint({ x: x + width / 2, y: y + height / 2 });
  const { workArea } = display;

  let newX = x;
  let newY = y;
  let snapped = false;
  let edge: typeof snapPosition = null;

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

// 记录胶囊位置，用于展开动画
let lastCapsulePosition: { x: number; y: number } | null = null;

function resizeWindowByMode(mode: 'capsule' | 'chat' | 'idle' | 'listening' | 'expanded') {
  if (!mainWindow) return;

  // 如果从胶囊切换到聊天/展开模式，记录当前位置
  if ((currentMode === 'capsule' || currentMode === 'idle' || currentMode === 'listening') && 
      (mode === 'chat' || mode === 'expanded')) {
    const [x, y] = mainWindow.getPosition();
    lastCapsulePosition = { x, y };
  }

  // 切换模式时，立即重置拖拽状态，避免窗口跟随鼠标
  if (isDragging) {
    isDragging = false;
    console.log('🛑 Drag cancelled due to mode change');
  }

  currentMode = mode; // 跟踪当前模式
  const bounds = WINDOW_BOUNDS[mode] || WINDOW_BOUNDS.capsule;

  // 根据模式设置对应的最小窗口尺寸
  // 主界面（聊天 / 设置）可以自由调整窗口大小，但不能低于可用下限
  const minBounds =
    mode === 'chat'
      ? WINDOW_MIN_BOUNDS.chat
      : mode === 'expanded'
        ? WINDOW_MIN_BOUNDS.expanded
        : WINDOW_BOUNDS.capsule;

  if (mode === 'chat' || mode === 'expanded') {
    // 从胶囊位置展开到聊天/展开模式
    // 先取消置顶，避免位置变化
    mainWindow.setAlwaysOnTop(false);
    if (isMac) {
      mainWindow.setVisibleOnAllWorkspaces(false);
    }

    const display = screen.getPrimaryDisplay();
    const { workArea } = display;

    // 计算窗口的中心位置
    const centerX = Math.round(workArea.x + (workArea.width - bounds.width) / 2);
    const centerY = Math.round(workArea.y + (workArea.height - bounds.height) / 2);

    // 设置新大小（先设置大小，再移动位置，避免闪烁）
    mainWindow.setSize(bounds.width, bounds.height);
    mainWindow.setResizable(true);
    // 设置主界面的最小尺寸：不能比当前模式的默认尺寸更小
    mainWindow.setMinimumSize(minBounds.width, minBounds.height);

    // 直接移动到中心（不使用动画，避免闪烁）
    mainWindow.setPosition(centerX, centerY, false);

    isSnappedToEdge = false;
    snapPosition = null;
  } else {
    // 切换到胶囊/监听模式
    // 先取消置顶，避免位置变化
    mainWindow.setAlwaysOnTop(false);
    if (isMac) {
      mainWindow.setVisibleOnAllWorkspaces(false);
    }
    
    // 设置新大小（先设置大小，避免位置计算错误）
    mainWindow.setSize(bounds.width, bounds.height);
    mainWindow.setResizable(false);
    // 胶囊/监听模式下，也同步使用对应模式的最小尺寸（主要是为了防止系统 Drag/Resize 出现意外）
    mainWindow.setMinimumSize(minBounds.width, minBounds.height);

    // 如果有记录的胶囊位置，恢复到那个位置（不使用动画，避免闪烁）
    if (lastCapsulePosition) {
      mainWindow.setPosition(lastCapsulePosition.x, lastCapsulePosition.y, false);
    } else {
      // 如果没有记录的位置，保持中心点不变
      const [currentX, currentY] = mainWindow.getPosition();
      const [currentWidth, currentHeight] = mainWindow.getSize();
      const centerX = currentX + currentWidth / 2;
      const centerY = currentY + currentHeight / 2;
      const newX = Math.round(centerX - bounds.width / 2);
      const newY = Math.round(centerY - bounds.height / 2);
      mainWindow.setPosition(newX, newY, false);
      lastCapsulePosition = { x: newX, y: newY };
    }

    // 确保窗口可见（切换到胶囊模式时必须显示窗口）
    if (!mainWindow.isVisible()) {
      mainWindow.show();
    }
  }

  // 胶囊/监听模式：始终置顶（在位置设置完成后）
  // 聊天/展开模式：已经在上面的 else 分支中取消了置顶
  const shouldBeOnTop = mode === 'capsule' || mode === 'idle' || mode === 'listening';
  if (shouldBeOnTop) {
    // 延迟一点设置置顶，确保位置已经稳定
    setTimeout(() => {
      if (mainWindow && (currentMode === 'capsule' || currentMode === 'idle' || currentMode === 'listening')) {
        enforceAlwaysOnTop();
      }
    }, 50);
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
ipcMain.on('resize-window', (_, { mode }: { mode: 'capsule' | 'chat' }) => {
  resizeWindowByMode(mode);
});

// Handle show/hide window
ipcMain.on('show-window', () => {
  if (mainWindow) {
    mainWindow.show();
    mainWindow.focus();
  }
});

ipcMain.on('hide-window', () => {
  if (mainWindow) {
    mainWindow.hide();
  }
});

// 胶囊右键菜单
ipcMain.on('show-context-menu', () => {
  if (!mainWindow) return;

  const contextMenu = Menu.buildFromTemplate([
    {
      label: 'Expand Panel',
      click: () => {
        resizeWindowByMode('chat');
        mainWindow?.webContents.send('switch-to-chat');
      },
    },
    {
      label: 'Pin Position',
      type: 'checkbox',
      checked: false,
      click: (menuItem) => {
        // TODO: 实现固定位置功能
        console.log('Pin position:', menuItem.checked);
      },
    },
    { type: 'separator' },
    {
      label: 'Settings',
      click: () => {
        resizeWindowByMode('expanded');
        mainWindow?.webContents.send('open-settings');
      },
    },
    { type: 'separator' },
    {
      label: 'Quit Lavis',
      click: () => {
        // 统一走 app.quit，触发 will-quit 做异步清理
        app.quit();
      },
    },
  ]);

  contextMenu.popup({ window: mainWindow });
});

// 新版平台抽象 IPC：使用 invoke/handle，便于 preload 暴露受控 API
ipcMain.handle('platform:resize-window', (_event, { mode }: { mode: 'capsule' | 'chat' | 'idle' | 'listening' | 'expanded' }) => {
  resizeWindowByMode(mode);
});

// 窗口状态 IPC：支持新的窗口状态管理
ipcMain.handle('resize-window-mini', () => {
  resizeWindowMini();
});

ipcMain.handle('resize-window-full', () => {
  resizeWindowFull();
});

ipcMain.handle('platform:minimize', () => {
  if (mainWindow) mainWindow.minimize();
});

ipcMain.handle('platform:hide', () => {
  if (mainWindow) mainWindow.hide();
});

ipcMain.handle('platform:set-always-on-top', (_event, { flag }: { flag: boolean }) => {
  if (mainWindow) mainWindow.setAlwaysOnTop(flag, 'screen-saver');
});

// 透明区域鼠标穿透控制
// forward: true 表示穿透透明区域，false 表示不穿透
ipcMain.handle('platform:set-ignore-mouse', (_event, { ignore, forward }: { ignore: boolean; forward?: boolean }) => {
  if (!mainWindow) return;
  if (ignore) {
    // 开启穿透，forward=true 时仅穿透透明区域
    mainWindow.setIgnoreMouseEvents(true, { forward: forward ?? true });
  } else {
    // 关闭穿透
    mainWindow.setIgnoreMouseEvents(false);
  }
});

// 获取当前吸附状态
ipcMain.handle('platform:get-snap-state', () => {
  return { isSnapped: isSnappedToEdge, position: snapPosition };
});

// 获取后端状态
ipcMain.handle('platform:get-backend-status', () => {
  return getBackendStatus();
});

// 重启后端
ipcMain.handle('platform:restart-backend', async () => {
  console.log('🔄 Restarting backend...');
  await stopBackend();
  return await startBackend();
});

// ============================================
// 拖拽相关 IPC - 实现丝滑拖拽和边缘吸附
// ============================================

// 开始拖拽
ipcMain.handle('platform:drag-start', (_event, { mouseX, mouseY }: { mouseX: number; mouseY: number }) => {
  if (!mainWindow) return;

  // 只在胶囊模式下允许拖拽
  if (currentMode !== 'capsule' && currentMode !== 'idle' && currentMode !== 'listening') {
    console.log('⚠️ Drag not allowed in current mode:', currentMode);
    return;
  }

  isDragging = true;
  dragStartPos = { x: mouseX, y: mouseY };
  const [winX, winY] = mainWindow.getPosition();
  windowStartPos = { x: winX, y: winY };

  console.log('🖱️ Drag started');
});

// 拖拽移动
ipcMain.handle('platform:drag-move', (_event, { mouseX, mouseY }: { mouseX: number; mouseY: number }) => {
  if (!mainWindow || !isDragging) return;

  // 只在胶囊模式下允许拖拽
  if (currentMode !== 'capsule' && currentMode !== 'idle' && currentMode !== 'listening') {
    // 如果不在胶囊模式，重置拖拽状态
    isDragging = false;
    return;
  }

  const deltaX = mouseX - dragStartPos.x;
  const deltaY = mouseY - dragStartPos.y;

  const newX = windowStartPos.x + deltaX;
  const newY = windowStartPos.y + deltaY;

  // 直接设置位置，不使用动画以保证流畅
  mainWindow.setPosition(Math.round(newX), Math.round(newY), false);
});

// 结束拖拽
ipcMain.handle('platform:drag-end', () => {
  if (!mainWindow || !isDragging) return;

  isDragging = false;

  // 只在胶囊模式下执行吸附
  if (currentMode !== 'capsule') return;

  const [x, y] = mainWindow.getPosition();
  const [width, height] = mainWindow.getSize();

  const snap = calculateSnapPosition(x, y, width, height);

  if (snap.snapped) {
    // 使用动画吸附到边缘
    mainWindow.setPosition(snap.x, snap.y, true);
    isSnappedToEdge = true;
    snapPosition = snap.edge;
    console.log(`🧲 Snapped to ${snap.edge} edge`);
  } else {
    isSnappedToEdge = false;
    snapPosition = null;
  }
});

// 获取窗口位置
ipcMain.handle('platform:get-window-position', () => {
  if (!mainWindow) return { x: 0, y: 0 };
  const [x, y] = mainWindow.getPosition();
  return { x, y };
});

// 设置窗口位置
ipcMain.handle('platform:set-window-position', (_event, { x, y, animate }: { x: number; y: number; animate?: boolean }) => {
  if (!mainWindow) return;
  mainWindow.setPosition(Math.round(x), Math.round(y), animate ?? false);
});

ipcMain.handle('platform:get-screenshot', async () => {
  try {
    const sources = await desktopCapturer.getSources({
      types: ['screen'],
      thumbnailSize: { width: 1920, height: 1080 },
    });
    if (!sources.length) return null;
    const primary = sources[0];
    return primary.thumbnail?.toDataURL() ?? null;
  } catch (error) {
    console.error('[platform:get-screenshot] failed:', error);
    return null;
  }
});

ipcMain.handle('platform:open-external', (_event, { url }: { url: string }) => {
  shell.openExternal(url).catch((err) => {
    console.error('[platform:open-external] failed:', err);
  });
});

ipcMain.handle('platform:check-mic', async () => {
  return checkAndRequestMicrophonePermission();
});

ipcMain.handle('platform:register-shortcut', (_event, { accelerator, action }: { accelerator: string; action: 'toggle-window' }) => {
  if (action !== 'toggle-window') return false;
  globalShortcut.unregister(accelerator);
  const success = globalShortcut.register(accelerator, () => {
    if (!mainWindow) return;
    if (mainWindow.isVisible()) {
      mainWindow.hide();
    } else {
      mainWindow.show();
      mainWindow.focus();
    }
  });
  return success;
});

// Backend API proxy - 允许渲染进程通过 IPC 发送请求到后端
// 这解决了在 Electron 环境中可能遇到的 CORS 或网络问题
ipcMain.handle('backend:request', async (_event, { method, endpoint, data, port = 18765 }: {
  method: 'GET' | 'POST' | 'PUT' | 'DELETE';
  endpoint: string;
  data?: unknown;
  port?: number;
}) => {
  return new Promise((resolve, reject) => {
    // 使用 127.0.0.1 而不是 localhost，避免 DNS 解析问题
    // 这在 Electron 环境中可以防止 DNS 相关的崩溃
    const options: http.RequestOptions = {
      method,
      hostname: '127.0.0.1', // 直接使用 IP 地址，避免 DNS 解析
      port: port,
      path: `/api/agent${endpoint}`,
      headers: {
        'Content-Type': 'application/json',
      },
      timeout: 0, // 无限超时，保持连接不断开
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
          } else {
            reject(new Error(`HTTP ${res.statusCode}: ${JSON.stringify(jsonData)}`));
          }
        } catch (error) {
          reject(new Error(`Failed to parse response: ${error}`));
        }
      });
    });

    // 超时处理：timeout 设置为 0 时不会触发此事件，保留用于兼容性
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
app.on('window-all-closed', () => {
  // 智能关闭方案：
  // - 如果用户关闭了胶囊模式窗口，isQuitting 已为 true，会触发 app.quit()
  // - 如果用户关闭了控制板模式窗口，窗口会切换回胶囊模式，不会触发此事件
  // - macOS 上，如果所有窗口都关闭且不在退出过程中，保持常驻（但我们的逻辑已经处理了）
  // 这里只处理非 macOS 平台或异常情况
  if (process.platform !== 'darwin' && !isQuitting) {
    app.quit();
  }
});

app.on('activate', () => {
  // 如果应用正在退出过程中，不要响应 dock 点击等激活事件，避免与 will-quit 清理逻辑竞争
  if (isQuitting) {
    console.log('⚠️ activate ignored because app is quitting');
    return;
  }

  // 只有在应用没有退出意图时才重新创建窗口
  // 如果用户已经关闭了所有窗口并退出，不应该重新创建
  if (BrowserWindow.getAllWindows().length === 0 && mainWindow === null) {
    createWindow();
  } else if (mainWindow) {
    // 如果窗口存在但被隐藏，显示它
    mainWindow.show();
    mainWindow.focus();
  }
});

// Global hotkey (Option+Space or Alt+Space)
app.whenReady().then(async () => {
  // 设置后端日志回调
  setLogCallback((level, message) => {
    const prefix = level === 'error' ? '❌' : level === 'warn' ? '⚠️' : '📦';
    console.log(`${prefix} [Backend] ${message}`);

    // 如果窗口已创建，发送日志到渲染进程
    if (mainWindow) {
      mainWindow.webContents.send('backend-log', { level, message });
    }
  });

  // 启动后端服务
  console.log('🚀 Starting backend service...');
  const backendResult = await startBackend();

  if (!backendResult.success) {
    console.error('❌ Failed to start backend service');
    if (backendResult.error) {
      console.error('Error details:', backendResult.error);
    }

    // 在开发模式下显示警告，但继续启动
    if (!app.isPackaged) {
      console.warn('⚠️ Development mode: Please ensure the backend JAR is built (mvn package)');
      console.warn('⚠️ Or start the backend manually: mvn spring-boot:run');
    } else {
      // 生产模式下显示错误对话框，包含详细错误信息
      const errorMessage = backendResult.error 
        ? `Failed to start the backend service.\n\n${backendResult.error}\n\nPlease check the console logs or reinstall the application.`
        : 'Failed to start the backend service. Please check the logs or reinstall the application.';
      
      dialog.showErrorBox('Backend Error', errorMessage);
    }
  } else {
    console.log('✅ Backend service started successfully');
  }

  // 首先检查并请求麦克风权限
  const micPermission = await checkAndRequestMicrophonePermission();
  if (!micPermission) {
    console.warn('⚠️ App started without microphone permission. Wake word detection may not work.');
  }

  // Register global hotkey
  globalShortcut.register('CommandOrControl+Space', () => {
    if (!mainWindow) return;

    if (mainWindow.isVisible()) {
      mainWindow.hide();
    } else {
      mainWindow.show();
      mainWindow.focus();
    }
  });

  // Register global hotkey for DevTools (Cmd+Shift+I or Ctrl+Shift+I)
  globalShortcut.register('CommandOrControl+Shift+I', () => {
    if (mainWindow) {
      mainWindow.webContents.toggleDevTools();
    }
  });

  createWindow();

  // Create system tray
  createTray();
});

// Clean up before quit
app.on('before-quit', () => {
  // 标记应用正在退出，防止其他操作干扰
  isQuitting = true;
  console.log('🛑 Application is quitting...');
});

// Clean up on quit
app.on('will-quit', async (event) => {
  // 阻止默认退出，等待后端关闭
  event.preventDefault();

  console.log('🛑 Application is quitting, cleaning up all resources...');

  // 取消注册所有全局快捷键
  try {
    globalShortcut.unregisterAll();
    console.log('✅ Global shortcuts unregistered');
  } catch (error) {
    console.error('⚠️ Error unregistering shortcuts:', error);
  }

  // 停止置顶定时器
  stopAlwaysOnTopEnforcer();

  // 销毁系统托盘
  if (tray) {
    try {
      tray.destroy();
      tray = null;
      console.log('✅ System tray destroyed');
    } catch (error) {
      console.error('⚠️ Error destroying tray:', error);
    }
  }

  // 确保所有窗口都已关闭
  const windows = BrowserWindow.getAllWindows();
  windows.forEach(window => {
    if (!window.isDestroyed()) {
      try {
        window.destroy();
      } catch (error) {
        console.error('⚠️ Error destroying window:', error);
      }
    }
  });
  console.log('✅ All windows closed');

  // 停止后端服务（这是最关键的步骤）
  console.log('🛑 Stopping backend service...');
  try {
    await stopBackend();
    console.log('✅ Backend service stopped');
  } catch (error) {
    console.error('❌ Error stopping backend:', error);
    // 即使出错也继续退出，避免应用卡住
  }

  // 额外等待一小段时间，确保所有进程都已关闭
  await new Promise(resolve => setTimeout(resolve, 500));

  // 现在可以退出了
  console.log('✅ All cleanup completed, exiting application');
  app.exit(0);
});

function createTray() {
  const iconPath = resolveAppIconPath();
  const icon = iconPath ? nativeImage.createFromPath(iconPath).resize({ width: 18, height: 18 }) : nativeImage.createEmpty();
  if (isMac && !icon.isEmpty()) {
    icon.setTemplateImage(true);
  }
  tray = new Tray(icon);

  const contextMenu = Menu.buildFromTemplate([
    {
      label: 'Show/Hide',
      click: () => {
        if (mainWindow) {
          if (mainWindow.isVisible()) {
            mainWindow.hide();
          } else {
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
          // 确保窗口处于 expanded 模式
          resizeWindowByMode('expanded');
          mainWindow.focus();
          mainWindow.webContents.send('open-settings');
        }
      },
    },
    { type: 'separator' },
    {
      label: 'Quit',
      click: () => {
        // 统一走 app.quit，触发 will-quit 做异步清理
        app.quit();
      },
    },
  ]);

  tray.setToolTip('Lavis AI');
  tray.setContextMenu(contextMenu);

  tray.on('click', () => {
    if (mainWindow) {
      if (mainWindow.isVisible()) {
        mainWindow.hide();
      } else {
        mainWindow.show();
        mainWindow.focus();
      }
    }
  });
}
