import { contextBridge, ipcRenderer } from 'electron';
contextBridge.exposeInMainWorld('electronAPI', {
    // Window controls
    minimize: () => ipcRenderer.send('window-minimize'),
    maximize: () => ipcRenderer.send('window-maximize'),
    close: () => ipcRenderer.send('window-close'),
    hide: () => ipcRenderer.send('window-hide'),
    show: () => ipcRenderer.send('window-show'),
    resizeToCapsule: () => ipcRenderer.send('window-resize-capsule'),
    resizeToChat: () => ipcRenderer.send('window-resize-chat'),
    // Quick chat shortcut
    onQuickChat: (callback) => {
        ipcRenderer.on('quick-chat', callback);
    },
    // Platform info
    platform: process.platform,
});
