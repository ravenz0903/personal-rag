const { contextBridge, ipcRenderer } = require('electron');
function noteDraft(operation,id,value,expected){const result=ipcRenderer.sendSync('folio:note-draft',operation,id,value,expected);if(result?.error)throw Error(result.error);return result?.value;}
contextBridge.exposeInMainWorld('folioDesktop', Object.freeze({
  noteDrafts:Object.freeze({get:id=>noteDraft('get',id),set:(id,value)=>noteDraft('set',id,value),remove:(id,expected)=>noteDraft('remove',id,undefined,expected)}),
  settings: () => ipcRenderer.invoke('folio:settings'),
  setRunAtLogin: enabled => ipcRenderer.invoke('folio:run-at-login', enabled === true),
  testNotification: () => ipcRenderer.invoke('folio:test-notification'),
  openDataFolder: () => ipcRenderer.invoke('folio:data-folder'),
  backup: () => ipcRenderer.invoke('folio:backup'),
  restore: () => ipcRenderer.invoke('folio:restore'),
  export: () => ipcRenderer.invoke('folio:export')
}));
