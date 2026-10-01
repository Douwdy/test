'use strict';

const { contextBridge, ipcRenderer } = require('electron');

const on = (channel) => (cb) => {
  const listener = (_e, data) => cb(data);
  ipcRenderer.on(channel, listener);
  return () => ipcRenderer.removeListener(channel, listener);
};

contextBridge.exposeInMainWorld('island', {
  onSnapshot: on('snapshot'),
  onActivity: on('activity'),
  onInflight: on('inflight'),
  onAlert: on('alert'),
  onToggleExpand: on('toggle-expand'),
  hover: (inside) => ipcRenderer.send('island:hover', inside),
  openSettings: () => ipcRenderer.send('island:open-settings'),
  snapshot: () => ipcRenderer.invoke('island:snapshot'),
});

contextBridge.exposeInMainWorld('settings', {
  get: () => ipcRenderer.invoke('settings:get'),
  save: (config) => ipcRenderer.invoke('settings:save', config),
  setSecret: (name, value) => ipcRenderer.invoke('settings:set-secret', name, value),
  testAdmin: (name) => ipcRenderer.invoke('settings:test-admin', name),
  exportCsv: () => ipcRenderer.invoke('settings:export-csv'),
  openData: () => ipcRenderer.invoke('settings:open-data'),
});
