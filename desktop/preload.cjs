const { contextBridge, ipcRenderer } = require('electron');
const { pathToFileURL } = require('node:url');

contextBridge.exposeInMainWorld('musikaDesktop', {
  isDesktopApp: true,
  pickFolder: () => ipcRenderer.invoke('musika:pick-folder'),
  scanDirectory: (rootPath) => ipcRenderer.invoke('musika:scan-directory', rootPath),
  getLocalLanInfo: () => ipcRenderer.invoke('musika:get-local-lan-info'),
  getDiscoveredLanPeers: () => ipcRenderer.invoke('musika:get-discovered-lan-peers'),
  onLanPeersUpdated: (cb) => {
    const handler = (_event, peers) => cb(peers);
    ipcRenderer.on('musika:lan-peers-updated', handler);
    return () => ipcRenderer.removeListener('musika:lan-peers-updated', handler);
  },
  setLanAccountConfig: (config) => ipcRenderer.invoke('musika:set-lan-account-config', config),
  broadcastLanEnvelope: (envelope) => ipcRenderer.invoke('musika:broadcast-lan-envelope', envelope),
  onIncomingLanEnvelope: (cb) => {
    const handler = (_event, env) => cb(env);
    ipcRenderer.on('musika:incoming-lan-envelope', handler);
    return () => ipcRenderer.removeListener('musika:incoming-lan-envelope', handler);
  },
  updateLanLibrarySnapshot: (tracks, allowStreaming, rootPath) =>
    ipcRenderer.invoke('musika:update-lan-library-snapshot', { tracks, allowStreaming, rootPath }),
  toLocalFileUrl: (filePath) => pathToFileURL(filePath).href
});
