import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { pathToFileURL, fileURLToPath } from 'node:url';
import { Bonjour } from 'bonjour-service';
import { app, BrowserWindow, dialog, ipcMain } from 'electron';
import {
  createMusikaLanServer,
  getLanIpv4Address,
  scanMusicDirectory
} from './lanServerCore.mjs';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const webDistDir = path.resolve(__dirname, '..', 'web', 'dist');

let mainWindow = null;
let lanServer = null;
let lanPort = 8765;
const bonjour = new Bonjour();
let publishedService = null;
const discoveredPeers = new Map();

function broadcastPeersToRenderer() {
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send('musika:lan-peers-updated', Array.from(discoveredPeers.values()));
  }
}

function startMdnsDiscovery() {
  const cfg = lanServer?.getState();
  try {
    publishedService = bonjour.publish({
      name: `Musika-${cfg?.deviceId || os.hostname()}`,
      type: 'musika',
      port: lanPort,
      txt: {
        deviceId: cfg?.deviceId || 'desktop',
        deviceName: cfg?.deviceName || os.hostname(),
        deviceType: 'DESKTOP',
        accountHash: cfg?.accountHash || '',
        port: String(lanPort)
      }
    });
  } catch {
    // mDNS publish may fail on restricted interfaces; HTTP/WS server still runs
  }

  const browser = bonjour.find({ type: 'musika' });
  browser.on('up', (service) => {
    const ipv4 = service.addresses?.find((a) => !a.includes(':') && a !== '127.0.0.1') || service.host;
    const txt = service.txt || {};
    if (!ipv4 || txt.deviceId === lanServer?.getState().deviceId) return;
    discoveredPeers.set(txt.deviceId || ipv4, {
      deviceId: txt.deviceId || ipv4,
      deviceName: txt.deviceName || service.name,
      host: ipv4,
      port: Number(txt.port || service.port || 8765),
      accountHash: txt.accountHash || '',
      deviceType: txt.deviceType === 'DESKTOP' ? 'DESKTOP' : 'ANDROID'
    });
    broadcastPeersToRenderer();
  });
  browser.on('down', (service) => {
    const txt = service.txt || {};
    if (txt.deviceId && discoveredPeers.delete(txt.deviceId)) {
      broadcastPeersToRenderer();
    }
  });
}

async function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1180,
    height: 780,
    minWidth: 760,
    minHeight: 540,
    backgroundColor: '#0B0E14',
    title: 'Musika — Samsung/Winamp Desktop & Online Sync',
    webPreferences: {
      preload: path.join(__dirname, 'preload.cjs'),
      contextIsolation: true,
      nodeIntegration: false,
      webSecurity: false // Allow local file:// audio playback cleanly from Electron Desktop
    }
  });

  const devUrl = process.env.MUSIKA_DEV_URL;
  if (devUrl) {
    await mainWindow.loadURL(devUrl);
  } else if (fs.existsSync(path.join(webDistDir, 'index.html'))) {
    await mainWindow.loadFile(path.join(webDistDir, 'index.html'));
  } else {
    await mainWindow.loadURL(
      'data:text/html,<h2>Build web/ first with npm run build inside web/</h2>'
    );
  }
}

app.whenReady().then(async () => {
  lanServer = createMusikaLanServer({ webDistDir });
  try {
    const addr = await lanServer.listen(8765, '0.0.0.0');
    lanPort = typeof addr === 'object' && addr ? addr.port : 8765;
  } catch {
    const addr = await lanServer.listen(0, '0.0.0.0');
    lanPort = typeof addr === 'object' && addr ? addr.port : 8765;
  }

  lanServer.onIncomingEnvelope((env) => {
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send('musika:incoming-lan-envelope', env);
    }
  });

  startMdnsDiscovery();

  ipcMain.handle('musika:pick-folder', async () => {
    const result = await dialog.showOpenDialog(mainWindow, {
      properties: ['openDirectory'],
      title: 'Select Scoped Musical Root Folder'
    });
    if (result.canceled || result.filePaths.length === 0) return null;
    return result.filePaths[0];
  });

  ipcMain.handle('musika:scan-directory', async (_e, rootPath) => {
    const scanned = await scanMusicDirectory(rootPath);
    lanServer?.updateConfig({ rootPath: scanned.rootPath });
    return scanned;
  });

  ipcMain.handle('musika:get-local-lan-info', async () => ({
    host: getLanIpv4Address(),
    port: lanPort
  }));

  ipcMain.handle('musika:get-discovered-lan-peers', async () =>
    Array.from(discoveredPeers.values())
  );

  ipcMain.handle('musika:set-lan-account-config', async (_e, config) => {
    lanServer?.updateConfig(config);
    if (publishedService) {
      try {
        publishedService.stop?.();
      } catch {
        // ignore
      }
      startMdnsDiscovery();
    }
  });

  ipcMain.handle('musika:broadcast-lan-envelope', async (_e, envelope) => {
    lanServer?.broadcast(envelope);
  });

  ipcMain.handle('musika:update-lan-library-snapshot', async (_e, { tracks, allowStreaming, rootPath }) => {
    lanServer?.updateConfig({
      catalogTracks: tracks,
      allowStreaming: Boolean(allowStreaming),
      ...(rootPath ? { rootPath } : {})
    });
  });

  await createWindow();

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) void createWindow();
  });
});

app.on('window-all-closed', async () => {
  try {
    publishedService?.stop?.();
    bonjour.destroy();
  } catch {
    // ignore
  }
  await lanServer?.close();
  if (process.platform !== 'darwin') app.quit();
});

export { pathToFileURL };
