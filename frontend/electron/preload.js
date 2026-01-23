"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const electron_1 = require("electron");
electron_1.contextBridge.exposeInMainWorld('electron', {
    ipcRenderer: {
        sendMessage: (channel, data) => {
            electron_1.ipcRenderer.send(channel, data);
        },
        on: (channel, callback) => {
            electron_1.ipcRenderer.on(channel, (_event, ...args) => callback(...args));
        },
        once: (channel, callback) => {
            electron_1.ipcRenderer.once(channel, (_event, ...args) => callback(...args));
        },
        removeAllListeners: (channel) => {
            electron_1.ipcRenderer.removeAllListeners(channel);
        },
    },
    platform: {
        resizeWindow: (mode) => electron_1.ipcRenderer.invoke('platform:resize-window', { mode }),
        resizeWindowMini: () => electron_1.ipcRenderer.invoke('resize-window-mini'),
        resizeWindowFull: () => electron_1.ipcRenderer.invoke('resize-window-full'),
        minimizeWindow: () => electron_1.ipcRenderer.invoke('platform:minimize'),
        hideWindow: () => electron_1.ipcRenderer.invoke('platform:hide'),
        setAlwaysOnTop: (flag) => electron_1.ipcRenderer.invoke('platform:set-always-on-top', { flag }),
        setIgnoreMouseEvents: (ignore, forward) => electron_1.ipcRenderer.invoke('platform:set-ignore-mouse', { ignore, forward }),
        getSnapState: () => electron_1.ipcRenderer.invoke('platform:get-snap-state'),
        getScreenshot: () => electron_1.ipcRenderer.invoke('platform:get-screenshot'),
        openExternalUrl: (url) => electron_1.ipcRenderer.invoke('platform:open-external', { url }),
        checkMicrophonePermission: () => electron_1.ipcRenderer.invoke('platform:check-mic'),
        registerGlobalShortcut: (accelerator, action) => electron_1.ipcRenderer.invoke('platform:register-shortcut', { accelerator, action }),
    },
    backend: {
        request: (method, endpoint, data, port) => electron_1.ipcRenderer.invoke('backend:request', { method, endpoint, data, port }),
    },
});
