import { app, BrowserWindow, globalShortcut, Tray, Menu, nativeImage, ipcMain, screen } from 'electron';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { dirname } from 'path';
import { exec } from 'child_process';
import { promisify } from 'util';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const execAsync = promisify(exec);

let mainWindow: BrowserWindow | null = null;
let tray: Tray | null = null;

const isDev = process.env.NODE_ENV === 'development';
const BACKEND_PORT = 8080;
const BACKEND_URL = `http://localhost:${BACKEND_PORT}`;

function createWindow(): void {
  const primaryDisplay = screen.getPrimaryDisplay();
  const { width, height } = primaryDisplay.workAreaSize;

  // Initial size for capsule mode (small floating ball)
  const CAPSULE_SIZE = 80;
  const CHAT_WIDTH = 400;
  const CHAT_HEIGHT = 600;

  mainWindow = new BrowserWindow({
    width: CAPSULE_SIZE,
    height: CAPSULE_SIZE,
    x: width - CAPSULE_SIZE - 20,  // Position at top-right corner
    y: 20,
    frame: false,          // No window frame
    transparent: true,     // Transparent window
    alwaysOnTop: true,     // Always on top
    skipTaskbar: true,    // Don't show in taskbar
    resizable: false,
    hasShadow: false,      // No shadow for floating effect
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
    },
    ...(process.platform === 'darwin' && {
      vibrancy: 'ultra-dark',  // macOS blur effect
      visualEffectState: 'active',
    }),
  });

  // Load the app
  if (isDev) {
    mainWindow.loadURL('http://localhost:5173');
    mainWindow.webContents.openDevTools();
  } else {
    mainWindow.loadFile(path.join(__dirname, '../dist/index.html'));
  }

  // Ensure window is visible after load
  mainWindow.webContents.once('did-finish-load', () => {
    mainWindow?.show();
  });

  // Show window immediately
  mainWindow.show();

  // Hide on close (don't quit)
  mainWindow.on('close', (e) => {
    e.preventDefault();
    mainWindow?.hide();
  });

  mainWindow.on('closed', () => {
    mainWindow = null;
  });
}

function createTray(): void {
  // Create a simple icon from base64
  const iconData = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+P+/HgAFhAJ/wlseKgAAAABJRU5ErkJggg==';
  const icon = nativeImage.createFromDataURL(iconData);

  tray = new Tray(icon);

  const contextMenu = Menu.buildFromTemplate([
    { label: 'Show Lavis', click: () => mainWindow?.show() },
    { label: 'Hide Lavis', click: () => mainWindow?.hide() },
    { type: 'separator' },
    { label: 'Start Backend', click: startBackend },
    { label: 'Stop Backend', click: stopBackend },
    { type: 'separator' },
    { label: 'Quit', click: () => {
      tray?.destroy();
      app.quit();
    }},
  ]);

  tray.setToolTip('Lavis AI Agent');
  tray.setContextMenu(contextMenu);

  tray.on('double-click', () => {
    mainWindow?.show();
  });
}

function registerGlobalShortcuts(): void {
  // Alt+Space to toggle window
  globalShortcut.register('Alt+Space', () => {
    if (mainWindow) {
      if (mainWindow.isVisible()) {
        mainWindow.hide();
      } else {
        mainWindow.show();
      }
    }
  });

  // Cmd+K to quick chat
  globalShortcut.register('CommandOrControl+K', () => {
    mainWindow?.show();
    mainWindow?.webContents.send('quick-chat');
  });

  // Escape to hide
  globalShortcut.register('Escape', () => {
    mainWindow?.hide();
  });
}

async function startBackend(): Promise<void> {
  try {
    const backendPath = path.join(__dirname, '../../backend/target');
    await execAsync(`cd ${backendPath} && java -jar lavis-0.0.1-SNAPSHOT.jar`);
  } catch (error) {
    console.error('Failed to start backend:', error);
  }
}

async function stopBackend(): Promise<void> {
  try {
    // Find and kill Java process running Lavis
    const { stdout } = await execAsync('ps aux | grep lavis-0.0.1-SNAPSHOT.jar | grep -v grep');
    const pids = stdout.trim().split('\n').map(line => line.split(/\s+/)[1]).filter(Boolean);

    for (const pid of pids) {
      await execAsync(`kill ${pid}`);
    }
  } catch (error) {
    console.error('Failed to stop backend:', error);
  }
}

// IPC handlers for window controls
ipcMain.on('window-minimize', () => mainWindow?.minimize());
ipcMain.on('window-maximize', () => {
  if (mainWindow) {
    if (mainWindow.isMaximized()) {
      mainWindow.unmaximize();
    } else {
      mainWindow.maximize();
    }
  }
});
ipcMain.on('window-close', () => mainWindow?.hide());
ipcMain.on('window-hide', () => mainWindow?.hide());
ipcMain.on('window-show', () => mainWindow?.show());

// Window resize handlers for view mode changes
ipcMain.on('window-resize-capsule', () => {
  if (mainWindow) {
    const primaryDisplay = screen.getPrimaryDisplay();
    const { width } = primaryDisplay.workAreaSize;
    const CAPSULE_SIZE = 80;
    mainWindow.setSize(CAPSULE_SIZE, CAPSULE_SIZE);
    mainWindow.setPosition(width - CAPSULE_SIZE - 20, 20);
  }
});

ipcMain.on('window-resize-chat', () => {
  if (mainWindow) {
    const primaryDisplay = screen.getPrimaryDisplay();
    const { width } = primaryDisplay.workAreaSize;
    const CHAT_WIDTH = 400;
    const CHAT_HEIGHT = 600;
    mainWindow.setSize(CHAT_WIDTH, CHAT_HEIGHT);
    mainWindow.setPosition(width - CHAT_WIDTH - 20, 20);
  }
});

// App lifecycle
app.whenReady().then(() => {
  createWindow();
  createTray();
  registerGlobalShortcuts();

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow();
    }
  });
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit();
  }
});

app.on('will-quit', () => {
  globalShortcut.unregisterAll();
});
