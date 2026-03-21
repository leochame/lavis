"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const electron_1 = require("electron");
const listenerMap = new Map();
function getChannelListeners(channel) {
    let channelListeners = listenerMap.get(channel);
    if (!channelListeners) {
        channelListeners = new WeakMap();
        listenerMap.set(channel, channelListeners);
    }
    return channelListeners;
}
electron_1.contextBridge.exposeInMainWorld('electron', {
    ipcRenderer: {
        sendMessage: (channel, data) => {
            electron_1.ipcRenderer.send(channel, data);
        },
        on: (channel, callback) => {
            const wrapped = (_event, ...args) => callback(...args);
            getChannelListeners(channel).set(callback, wrapped);
            electron_1.ipcRenderer.on(channel, wrapped);
        },
        once: (channel, callback) => {
            electron_1.ipcRenderer.once(channel, (_event, ...args) => callback(...args));
        },
        removeListener: (channel, callback) => {
            const channelListeners = listenerMap.get(channel);
            const wrapped = channelListeners?.get(callback);
            if (!wrapped) {
                return;
            }
            electron_1.ipcRenderer.removeListener(channel, wrapped);
            channelListeners?.delete(callback);
        },
        removeAllListeners: (channel) => {
            electron_1.ipcRenderer.removeAllListeners(channel);
            listenerMap.delete(channel);
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
        // 拖拽相关 API
        dragStart: (mouseX, mouseY) => electron_1.ipcRenderer.invoke('platform:drag-start', { mouseX, mouseY }),
        dragMove: (mouseX, mouseY) => electron_1.ipcRenderer.invoke('platform:drag-move', { mouseX, mouseY }),
        dragEnd: () => electron_1.ipcRenderer.invoke('platform:drag-end'),
        getWindowPosition: () => electron_1.ipcRenderer.invoke('platform:get-window-position'),
        setWindowPosition: (x, y, animate) => electron_1.ipcRenderer.invoke('platform:set-window-position', { x, y, animate }),
    },
    backend: {
        request: (method, endpoint, data, port) => electron_1.ipcRenderer.invoke('backend:request', { method, endpoint, data, port }),
    },
});
