const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('folioDesktop', Object.freeze({
  settings: () => ipcRenderer.invoke('folio:settings'),
  setRunAtLogin: enabled => ipcRenderer.invoke('folio:run-at-login', enabled === true),
  testNotification: () => ipcRenderer.invoke('folio:test-notification'),
  openDataFolder: () => ipcRenderer.invoke('folio:data-folder'),
  backup: () => ipcRenderer.invoke('folio:backup'),
  restore: () => ipcRenderer.invoke('folio:restore'),
  export: () => ipcRenderer.invoke('folio:export')
}));
