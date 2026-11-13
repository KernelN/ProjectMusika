import { Playlist, Track } from '../core/models';
import {
  AccountDeviceSyncMode,
  computeHmacHex,
  ConnectedDeviceNode,
  ConnectionTransportMode,
  filterSnapshotForTargetDevice,
  parseQrPairInput,
  PlaybackSnapshot,
  PlaybackTransferResult,
  QrPairPayload,
  RemoteLibraryTrackSummary,
  shortAccountHash,
  SyncCommand,
  SyncWireEnvelope,
  trackToSummary,
  validateAndPreparePlaybackTransfer
} from '../core/syncContract';
import { DualQueueEngine } from '../player/DualQueueEngine';
import { SupabaseAuthSession, SupabaseSyncService } from './supabaseSync';

export interface OnlineControlState {
  accountId: string;
  username: string;
  pairSecretHmac: string;
  activePinCode: string;
  syncMode: AccountDeviceSyncMode;
  transportMode: ConnectionTransportMode;
  lanConnectedPeer?: string;
  supabaseOnlineConnected: boolean;
  localDeviceId: string;
  localDeviceName: string;
  localDeviceType: 'WEB' | 'DESKTOP';
  activeControllingDeviceId: string;
  browsingDeviceId: string;
  listenLocallyWhenSyncedTargetIsRemote: boolean;
  allowLanStreamingForMissingTracks: boolean;
  connectedDevices: ConnectedDeviceNode[];
  lastTransferResult?: PlaybackTransferResult;
  statusMessage?: string;
}

export interface DesktopBridge {
  isDesktopApp: boolean;
  scanDirectory: (rootPath: string) => Promise<{ rootPath: string; tracks: Track[] }>;
  pickFolder: () => Promise<string | null>;
  getLocalLanInfo: () => Promise<{ host?: string; port: number }>;
  getDiscoveredLanPeers: () => Promise<Array<{ deviceId: string; deviceName: string; host: string; port: number; accountHash: string; deviceType: 'ANDROID' | 'DESKTOP' }>>;
  onLanPeersUpdated: (cb: (peers: Array<{ deviceId: string; deviceName: string; host: string; port: number; accountHash: string; deviceType: 'ANDROID' | 'DESKTOP' }>) => void) => () => void;
  setLanAccountConfig: (config: { accountHash: string; pairSecretHmac: string; deviceId: string; deviceName: string; pinCode: string }) => Promise<void>;
  broadcastLanEnvelope: (envelope: SyncWireEnvelope) => Promise<void>;
  onIncomingLanEnvelope: (cb: (envelope: SyncWireEnvelope) => void) => () => void;
  updateLanLibrarySnapshot: (tracks: RemoteLibraryTrackSummary[], allowStreaming: boolean, rootPath: string) => Promise<void>;
  toLocalFileUrl: (filePath: string) => string;
}

declare global {
  interface Window {
    musikaDesktop?: DesktopBridge;
  }
}

export class DualTransportCoordinator {
  private state: OnlineControlState;
  private listeners = new Set<(state: OnlineControlState) => void>();
  private lanSocket: WebSocket | null = null;
  private lanProbeTimer: number | null = null;
  private lanAuthenticatedPeerId: string | null = null;
  private localLibrary: Track[] = [];
  private playlistsProvider: () => Playlist[] = () => [];
  private onPlaylistsMerged?: (remotePlaylists: Playlist[]) => void;

  constructor(
    private readonly localEngine: DualQueueEngine,
    public readonly supabaseService: SupabaseSyncService,
    initialDeviceId: string,
    initialDeviceName: string,
    initialSession?: Partial<SupabaseAuthSession> & { pairSecretHmac?: string }
  ) {
    const isDesktop = Boolean(typeof window !== 'undefined' && window.musikaDesktop?.isDesktopApp);
    const randomPin = Math.floor(100000 + Math.random() * 900000).toString();
    const pairSecret =
      initialSession?.pairSecretHmac ||
      crypto.randomUUID().replace(/-/g, '') + crypto.randomUUID().replace(/-/g, '');

    this.state = {
      accountId: initialSession?.userId || 'local-guest',
      username: initialSession?.username || 'MusikaUser',
      pairSecretHmac: pairSecret,
      activePinCode: randomPin,
      syncMode: 'SYNCED_PLAYBACK',
      transportMode: 'OFFLINE_LOCAL',
      supabaseOnlineConnected: false,
      localDeviceId: initialDeviceId,
      localDeviceName: initialDeviceName,
      localDeviceType: isDesktop ? 'DESKTOP' : 'WEB',
      activeControllingDeviceId: initialDeviceId,
      browsingDeviceId: initialDeviceId,
      listenLocallyWhenSyncedTargetIsRemote: false,
      allowLanStreamingForMissingTracks: false,
      connectedDevices: [
        {
          deviceId: initialDeviceId,
          deviceName: initialDeviceName,
          deviceType: isDesktop ? 'DESKTOP' : 'WEB',
          accountId: initialSession?.userId || 'local-guest',
          isThisDevice: true,
          isActivePlayingDevice: true,
          monitorLocalAudio: true,
          loadedTrackKeys: [],
          loadedRelativePaths: []
        }
      ]
    };

    if (typeof window !== 'undefined' && window.musikaDesktop) {
      window.musikaDesktop.onIncomingLanEnvelope((env) => {
        void this.handleIncomingEnvelope(env, 'LAN_DIRECT');
      });
      window.musikaDesktop.onLanPeersUpdated((peers) => {
        if (!this.lanSocket && peers.length > 0) {
          void this.tryLanFirstThenOnlineFallback(peers[0].host, peers[0].port);
        }
      });
    }
  }

  getState(): OnlineControlState {
    return this.state;
  }

  subscribe(listener: (state: OnlineControlState) => void): () => void {
    this.listeners.add(listener);
    listener(this.state);
    return () => this.listeners.delete(listener);
  }

  private emit(partial: Partial<OnlineControlState>) {
    this.state = { ...this.state, ...partial };
    for (const l of this.listeners) l(this.state);
  }

  setPlaylistSyncCallbacks(provider: () => Playlist[], onMerged: (playlists: Playlist[]) => void) {
    this.playlistsProvider = provider;
    this.onPlaylistsMerged = onMerged;
  }

  async updateLocalLibrary(tracks: Track[], rootPath = '') {
    this.localLibrary = tracks;
    const summaries = tracks.map(trackToSummary);
    const updatedDevices = this.state.connectedDevices.map((d) =>
      d.deviceId === this.state.localDeviceId
        ? {
            ...d,
            loadedTrackKeys: summaries.map((s) => s.normalizedKey.toLowerCase()),
            loadedRelativePaths: summaries.map((s) => s.relativePath).filter(Boolean),
            catalogTracks: summaries
          }
        : d
    );
    this.emit({ connectedDevices: updatedDevices });

    if (typeof window !== 'undefined' && window.musikaDesktop) {
      await window.musikaDesktop.updateLanLibrarySnapshot(
        summaries,
        this.state.allowLanStreamingForMissingTracks,
        rootPath
      );
    }

    await this.pushCatalogAndSession(summaries);
  }

  setAllowLanStreamingForMissingTracks(enabled: boolean) {
    this.emit({ allowLanStreamingForMissingTracks: enabled });
  }

  setListenLocallyWhenSyncedTargetIsRemote(enabled: boolean) {
    this.emit({ listenLocallyWhenSyncedTargetIsRemote: enabled });
    if (this.state.syncMode === 'SYNCED_PLAYBACK' && this.state.activeControllingDeviceId !== this.state.localDeviceId) {
      this.localEngine.driver?.onPlayStateChanged(enabled && this.localEngine.getState().isPlaying);
    }
  }

  setBrowsingDeviceId(deviceId: string) {
    this.emit({ browsingDeviceId: deviceId });
  }

  async setAccountSession(session: SupabaseAuthSession) {
    this.emit({
      accountId: session.userId,
      username: session.username,
      pairSecretHmac: session.pairSecretHmac || this.state.pairSecretHmac
    });
    await this.pushDesktopLanAuthConfig();
    await this.startTransportLoop();
  }

  private async pushDesktopLanAuthConfig() {
    if (typeof window === 'undefined' || !window.musikaDesktop) return;
    const accountHash = await shortAccountHash(this.state.accountId);
    await window.musikaDesktop.setLanAccountConfig({
      accountHash,
      pairSecretHmac: this.state.pairSecretHmac,
      deviceId: this.state.localDeviceId,
      deviceName: this.state.localDeviceName,
      pinCode: this.state.activePinCode
    });
  }

  async startTransportLoop() {
    if (this.lanProbeTimer !== null) {
      window.clearInterval(this.lanProbeTimer);
    }

    // 1. Probe LAN endpoints first (from URL query/hash, saved devices, or Electron mDNS)
    const lanCandidate = await this.findBestLanCandidate();
    if (lanCandidate) {
      const ok = await this.tryLanFirstThenOnlineFallback(lanCandidate.host, lanCandidate.port);
      if (!ok) {
        await this.activateSupabaseOnlineFallback();
      }
    } else {
      await this.activateSupabaseOnlineFallback();
    }

    // 2. Gentle background re-probe every 15s to upgrade back to LAN when returning to same Wi-Fi
    this.lanProbeTimer = window.setInterval(async () => {
      if (this.state.transportMode !== 'LAN_DIRECT') {
        const candidate = await this.findBestLanCandidate();
        if (candidate) {
          await this.tryLanFirstThenOnlineFallback(candidate.host, candidate.port);
        }
      }
    }, 15_000);
  }

  private async findBestLanCandidate(): Promise<{ host: string; port: number } | null> {
    if (typeof window !== 'undefined' && window.musikaDesktop) {
      const peers = await window.musikaDesktop.getDiscoveredLanPeers();
      if (peers.length > 0) return { host: peers[0].host, port: peers[0].port };
    }
    const peerDevice = this.state.connectedDevices.find(
      (d) => !d.isThisDevice && d.lanHost && d.lanPort
    );
    if (peerDevice?.lanHost && peerDevice.lanPort) {
      return { host: peerDevice.lanHost, port: peerDevice.lanPort };
    }
    if (typeof window !== 'undefined' && window.location.protocol === 'http:' && window.location.hostname !== 'localhost') {
      return {
        host: window.location.hostname,
        port: Number(window.location.port || 8765)
      };
    }
    return null;
  }

  /**
   * Probes direct WebSocket over LAN (1500ms timeout). Falls back smoothly to Supabase Online if unreachable.
   * Respects browser mixed-content rules: HTTPS origins cannot dial insecure `ws://192.168.x.x` directly,
   * so HTTPS browser sessions immediately use Supabase Online (unless Electron desktop or http:// LAN portal).
   */
  async tryLanFirstThenOnlineFallback(host: string, port: number): Promise<boolean> {
    if (typeof window !== 'undefined' && window.location.protocol === 'https:' && !window.musikaDesktop?.isDesktopApp) {
      // Browser mixed-content guard: https:// page cannot connect to ws://LAN-IP directly
      await this.activateSupabaseOnlineFallback();
      return false;
    }

    return new Promise<boolean>((resolve) => {
      let settled = false;
      const wsUrl = `ws://${host}:${port}/ws`;
      let ws: WebSocket;
      try {
        ws = new WebSocket(wsUrl);
      } catch {
        void this.activateSupabaseOnlineFallback();
        resolve(false);
        return;
      }

      const timer = window.setTimeout(() => {
        if (!settled) {
          settled = true;
          try {
            ws.close();
          } catch {
            // ignore
          }
          void this.activateSupabaseOnlineFallback();
          resolve(false);
        }
      }, 1500);

      ws.onopen = async () => {
        const accountHash = await shortAccountHash(this.state.accountId);
        ws.send(
          JSON.stringify({
            type: 'hello',
            senderDeviceId: this.state.localDeviceId,
            senderDeviceName: this.state.localDeviceName,
            senderDeviceType: this.state.localDeviceType,
            accountHash,
            timestampMs: Date.now(),
            lanHost: window.musikaDesktop ? (await window.musikaDesktop.getLocalLanInfo()).host : undefined,
            lanPort: port
          } satisfies SyncWireEnvelope)
        );
      };

      ws.onmessage = async (ev) => {
        try {
          const env = JSON.parse(String(ev.data)) as SyncWireEnvelope;
          if (env.type === 'auth_challenge' && env.nonce) {
            const proof = await computeHmacHex(
              this.state.pairSecretHmac,
              `${env.nonce}|${this.state.localDeviceId}`
            );
            ws.send(
              JSON.stringify({
                type: 'auth_proof',
                senderDeviceId: this.state.localDeviceId,
                senderDeviceName: this.state.localDeviceName,
                senderDeviceType: this.state.localDeviceType,
                nonce: env.nonce,
                hmacProof: proof,
                timestampMs: Date.now()
              } satisfies SyncWireEnvelope)
            );
            if (!settled) {
              settled = true;
              window.clearTimeout(timer);
              this.lanSocket = ws;
              this.lanAuthenticatedPeerId = env.senderDeviceId;
              this.emit({
                transportMode: 'LAN_DIRECT',
                lanConnectedPeer: `${host}:${port}`,
                statusMessage: `Connected via Direct LAN (${host}:${port})`
              });
              await this.sendEnvelopeOverActiveTransport({
                type: 'catalog',
                senderDeviceId: this.state.localDeviceId,
                senderDeviceName: this.state.localDeviceName,
                senderDeviceType: this.state.localDeviceType,
                timestampMs: Date.now(),
                catalog: this.localLibrary.map(trackToSummary),
                snapshot: this.localEngine.toPlaybackSnapshot()
              });
              resolve(true);
            }
            return;
          }
          await this.handleIncomingEnvelope(env, 'LAN_DIRECT');
        } catch {
          // ignore malformed LAN frame
        }
      };

      ws.onerror = () => {
        if (!settled) {
          settled = true;
          window.clearTimeout(timer);
          void this.activateSupabaseOnlineFallback();
          resolve(false);
        }
      };

      ws.onclose = () => {
        if (this.lanSocket === ws) {
          this.lanSocket = null;
          this.lanAuthenticatedPeerId = null;
          void this.activateSupabaseOnlineFallback();
        }
      };
    });
  }

  private async activateSupabaseOnlineFallback() {
    if (!this.supabaseService.isConfigured() || this.state.accountId === 'local-guest') {
      this.emit({
        transportMode: 'OFFLINE_LOCAL',
        supabaseOnlineConnected: false
      });
      return;
    }

    await this.supabaseService.connectRealtime(
      this.state.accountId,
      (env) => void this.handleIncomingEnvelope(env, 'ONLINE_SUPABASE'),
      () => void this.refreshFromSupabase(),
      (connected) => {
        if (this.state.transportMode !== 'LAN_DIRECT') {
          this.emit({
            transportMode: connected ? 'ONLINE_SUPABASE' : 'OFFLINE_LOCAL',
            supabaseOnlineConnected: connected,
            statusMessage: connected
              ? 'LAN unavailable — switched automatically to Supabase Online Relay'
              : 'Connecting to Supabase Online Relay...'
          });
        } else {
          this.emit({ supabaseOnlineConnected: connected });
        }
      }
    );
    await this.pushCatalogAndSession(this.localLibrary.map(trackToSummary));
    await this.refreshFromSupabase();
  }

  private async pushCatalogAndSession(summaries: RemoteLibraryTrackSummary[]) {
    if (!this.supabaseService.isConfigured() || this.state.accountId === 'local-guest') return;
    const lanInfo =
      typeof window !== 'undefined' && window.musikaDesktop
        ? await window.musikaDesktop.getLocalLanInfo()
        : undefined;
    await this.supabaseService.registerDeviceAndSyncMode(
      this.state.accountId,
      this.state.localDeviceId,
      this.state.localDeviceName,
      this.state.localDeviceType,
      lanInfo?.host,
      lanInfo?.port,
      this.state.activeControllingDeviceId === this.state.localDeviceId,
      this.state.listenLocallyWhenSyncedTargetIsRemote
    );
    await this.supabaseService.syncDeviceTracks(
      this.state.accountId,
      this.state.localDeviceId,
      this.localLibrary
    );
    await this.supabaseService.savePlaybackSession(
      this.state.accountId,
      this.state.localDeviceId,
      this.localEngine.toPlaybackSnapshot()
    );
    await this.sendEnvelopeOverActiveTransport({
      type: 'catalog',
      senderDeviceId: this.state.localDeviceId,
      senderDeviceName: this.state.localDeviceName,
      senderDeviceType: this.state.localDeviceType,
      timestampMs: Date.now(),
      catalog: summaries,
      snapshot: this.localEngine.toPlaybackSnapshot()
    });
  }

  async refreshFromSupabase() {
    if (!this.supabaseService.isConfigured() || this.state.accountId === 'local-guest') return;
    const cloud = await this.supabaseService.fetchFullAccountState(
      this.state.accountId,
      this.state.localDeviceId
    );
    const localNode = this.state.connectedDevices.find((d) => d.deviceId === this.state.localDeviceId);
    const mergedDevices = cloud.devices.map((d) =>
      d.deviceId === this.state.localDeviceId && localNode ? { ...d, catalogTracks: localNode.catalogTracks, loadedTrackKeys: localNode.loadedTrackKeys, loadedRelativePaths: localNode.loadedRelativePaths } : d
    );
    if (!mergedDevices.some((d) => d.deviceId === this.state.localDeviceId) && localNode) {
      mergedDevices.unshift(localNode);
    }
    this.emit({
      syncMode: cloud.syncMode ?? this.state.syncMode,
      activeControllingDeviceId: cloud.activeDeviceId ?? this.state.activeControllingDeviceId,
      connectedDevices: mergedDevices
    });
    if (cloud.playlists.length > 0 && this.onPlaylistsMerged) {
      this.onPlaylistsMerged(cloud.playlists);
    }
  }

  private async sendEnvelopeOverActiveTransport(envelope: SyncWireEnvelope) {
    let sentOverLan = false;
    if (this.lanSocket && this.lanSocket.readyState === WebSocket.OPEN) {
      this.lanSocket.send(JSON.stringify(envelope));
      sentOverLan = true;
    }
    if (typeof window !== 'undefined' && window.musikaDesktop) {
      await window.musikaDesktop.broadcastLanEnvelope(envelope);
      sentOverLan = true;
    }
    if (!sentOverLan && this.supabaseService.isConfigured()) {
      await this.supabaseService.broadcastEnvelope(envelope);
    } else if (this.state.supabaseOnlineConnected) {
      // Keep Supabase Realtime mirrors fresh even when on LAN if online relay is subscribed
      void this.supabaseService.broadcastEnvelope(envelope);
    }
  }

  async handleIncomingEnvelope(env: SyncWireEnvelope, via: ConnectionTransportMode) {
    if (env.senderDeviceId === this.state.localDeviceId) return;

    // Register or update sender peer metadata & snapshot
    const existing = this.state.connectedDevices.find((d) => d.deviceId === env.senderDeviceId);
    const updatedPeer: ConnectedDeviceNode = existing ?? {
      deviceId: env.senderDeviceId,
      deviceName: env.senderDeviceName || env.senderDeviceId,
      deviceType: env.senderDeviceType || 'ANDROID',
      accountId: this.state.accountId,
      isThisDevice: false,
      isActivePlayingDevice: this.state.activeControllingDeviceId === env.senderDeviceId,
      loadedTrackKeys: [],
      loadedRelativePaths: []
    };

    if (env.lanHost && env.lanPort) {
      updatedPeer.lanHost = env.lanHost;
      updatedPeer.lanPort = env.lanPort;
    }
    if (env.catalog) {
      updatedPeer.catalogTracks = env.catalog;
      updatedPeer.loadedTrackKeys = env.catalog.map((c) => c.normalizedKey.toLowerCase());
      updatedPeer.loadedRelativePaths = env.catalog.map((c) => c.relativePath).filter(Boolean);
    }
    if (env.snapshot) {
      updatedPeer.lastSnapshot = env.snapshot;
    }

    const others = this.state.connectedDevices.filter((d) => d.deviceId !== env.senderDeviceId);
    this.emit({
      connectedDevices: [...others, updatedPeer],
      transportMode: via === 'LAN_DIRECT' ? 'LAN_DIRECT' : this.state.transportMode
    });

    if (env.type === 'sync_mode' && env.syncMode) {
      this.emit({ syncMode: env.syncMode });
    }

    if (env.type === 'playlists' && env.playlists && this.onPlaylistsMerged) {
      this.onPlaylistsMerged(
        env.playlists.map((p) => ({
          id: p.name,
          name: p.name,
          createdAt: p.clientUpdatedAt,
          updatedAt: p.clientUpdatedAt,
          items: p.items
        }))
      );
    }

    if (env.type === 'snapshot' && env.snapshot) {
      if (this.state.syncMode === 'SYNCED_PLAYBACK') {
        const shouldOutputAudio =
          this.state.activeControllingDeviceId === this.state.localDeviceId ||
          this.state.listenLocallyWhenSyncedTargetIsRemote;
        this.localEngine.applyRemoteSnapshot(
          env.snapshot,
          this.localLibrary,
          this.state.allowLanStreamingForMissingTracks && via === 'LAN_DIRECT',
          shouldOutputAudio
        );
      }
    }

    if (env.type === 'command' && env.command) {
      const cmd = env.command;
      const targetsThisDevice =
        !env.targetDeviceId ||
        env.targetDeviceId === this.state.localDeviceId ||
        this.state.syncMode === 'SYNCED_PLAYBACK';

      if (cmd.type === 'TransferPlayback') {
        const isTarget = cmd.targetDeviceId === this.state.localDeviceId;
        const isSource = cmd.sourceDeviceId === this.state.localDeviceId;
        const localNode = this.state.connectedDevices.find(
          (d) => d.deviceId === this.state.localDeviceId
        );
        if (isTarget && localNode) {
          const check = validateAndPreparePlaybackTransfer(
            cmd.sourceDeviceId,
            cmd.snapshot,
            localNode,
            this.state.allowLanStreamingForMissingTracks && via === 'LAN_DIRECT'
          );
          if (check.allowed) {
            const { filteredSnapshot } = filterSnapshotForTargetDevice(
              cmd.snapshot,
              localNode,
              this.state.allowLanStreamingForMissingTracks && via === 'LAN_DIRECT'
            );
            this.localEngine.applyRemoteSnapshot(
              filteredSnapshot,
              this.localLibrary,
              this.state.allowLanStreamingForMissingTracks && via === 'LAN_DIRECT',
              true
            );
            this.emit({
              activeControllingDeviceId: this.state.localDeviceId,
              lastTransferResult: check,
              statusMessage: check.reason
            });
          }
        } else if (isSource && cmd.pauseSourceDevice) {
          this.localEngine.setPlaying(false);
          this.emit({ activeControllingDeviceId: cmd.targetDeviceId });
        }
        return;
      }

      if (targetsThisDevice) {
        const shouldOutputAudio =
          env.targetDeviceId === this.state.localDeviceId ||
          this.state.activeControllingDeviceId === this.state.localDeviceId ||
          this.state.listenLocallyWhenSyncedTargetIsRemote;
        this.localEngine.applySyncCommand(
          cmd,
          this.localLibrary,
          this.state.allowLanStreamingForMissingTracks && via === 'LAN_DIRECT',
          shouldOutputAudio
        );
      }
    }
  }

  async setAccountSyncMode(mode: AccountDeviceSyncMode) {
    this.emit({ syncMode: mode });
    await this.sendEnvelopeOverActiveTransport({
      type: 'sync_mode',
      senderDeviceId: this.state.localDeviceId,
      senderDeviceName: this.state.localDeviceName,
      senderDeviceType: this.state.localDeviceType,
      syncMode: mode,
      timestampMs: Date.now()
    });
    if (this.supabaseService.isConfigured() && this.state.accountId !== 'local-guest') {
      await this.supabaseService.updateAccountSettings(
        this.state.accountId,
        mode,
        this.state.activeControllingDeviceId
      );
    }
  }

  async sendRemoteCommand(command: SyncCommand, targetDeviceId = this.state.activeControllingDeviceId) {
    if (
      targetDeviceId === this.state.localDeviceId ||
      this.state.syncMode === 'SYNCED_PLAYBACK'
    ) {
      const playAudio =
        targetDeviceId === this.state.localDeviceId ||
        this.state.activeControllingDeviceId === this.state.localDeviceId ||
        this.state.listenLocallyWhenSyncedTargetIsRemote;
      this.localEngine.applySyncCommand(
        command,
        this.localLibrary,
        this.state.allowLanStreamingForMissingTracks && this.state.transportMode === 'LAN_DIRECT',
        playAudio
      );
    }

    await this.sendEnvelopeOverActiveTransport({
      type: 'command',
      senderDeviceId: this.state.localDeviceId,
      senderDeviceName: this.state.localDeviceName,
      senderDeviceType: this.state.localDeviceType,
      targetDeviceId,
      command,
      timestampMs: Date.now()
    });
  }

  async transferPlaybackToDevice(targetDeviceId: string): Promise<PlaybackTransferResult> {
    const sourceDeviceId = this.state.activeControllingDeviceId;
    const sourceNode = this.state.connectedDevices.find((d) => d.deviceId === sourceDeviceId);
    const targetNode = this.state.connectedDevices.find((d) => d.deviceId === targetDeviceId);
    if (!targetNode) {
      const res: PlaybackTransferResult = {
        allowed: false,
        sourceDeviceId,
        targetDeviceId,
        reason: `Target device '${targetDeviceId}' not found.`
      };
      this.emit({ lastTransferResult: res, statusMessage: res.reason });
      return res;
    }

    const allowStream =
      this.state.allowLanStreamingForMissingTracks && this.state.transportMode === 'LAN_DIRECT';
    const lanHostPort =
      allowStream && sourceNode?.lanHost && sourceNode.lanPort
        ? `${sourceNode.lanHost}:${sourceNode.lanPort}`
        : undefined;

    const sourceSnapshot =
      sourceDeviceId === this.state.localDeviceId
        ? this.localEngine.toPlaybackSnapshot(lanHostPort)
        : sourceNode?.lastSnapshot ?? this.localEngine.toPlaybackSnapshot(lanHostPort);

    const validation = validateAndPreparePlaybackTransfer(
      sourceDeviceId,
      sourceSnapshot,
      targetNode,
      allowStream
    );

    if (!validation.allowed) {
      this.emit({ lastTransferResult: validation, statusMessage: validation.reason });
      return validation;
    }

    const { filteredSnapshot } = filterSnapshotForTargetDevice(
      sourceSnapshot,
      targetNode,
      allowStream
    );

    const transferCmd: SyncCommand = {
      type: 'TransferPlayback',
      sourceDeviceId,
      targetDeviceId,
      snapshot: { ...filteredSnapshot, isPlaying: true },
      pauseSourceDevice: sourceDeviceId !== targetDeviceId
    };

    if (sourceDeviceId === this.state.localDeviceId && targetDeviceId !== this.state.localDeviceId) {
      this.localEngine.setPlaying(false);
    } else if (targetDeviceId === this.state.localDeviceId) {
      this.localEngine.applySyncCommand(transferCmd, this.localLibrary, allowStream, true);
    }

    const updatedDevices = this.state.connectedDevices.map((d) => ({
      ...d,
      isActivePlayingDevice: d.deviceId === targetDeviceId
    }));

    this.emit({
      activeControllingDeviceId: targetDeviceId,
      connectedDevices: updatedDevices,
      lastTransferResult: validation,
      statusMessage: validation.reason
    });

    await this.sendEnvelopeOverActiveTransport({
      type: 'command',
      senderDeviceId: this.state.localDeviceId,
      senderDeviceName: this.state.localDeviceName,
      senderDeviceType: this.state.localDeviceType,
      targetDeviceId,
      command: transferCmd,
      timestampMs: Date.now()
    });

    if (this.supabaseService.isConfigured() && this.state.accountId !== 'local-guest') {
      await this.supabaseService.updateAccountSettings(
        this.state.accountId,
        this.state.syncMode,
        targetDeviceId
      );
    }

    return validation;
  }

  async pairViaQrOrPinInput(rawInput: string, lanHostOptional?: string, lanPortOptional = 8765): Promise<string> {
    const trimmed = rawInput.trim();
    const qrPayload = parseQrPairInput(trimmed);
    if (qrPayload) {
      if (qrPayload.supabaseUrl && qrPayload.supabaseAnonKey) {
        this.supabaseService.configure(qrPayload.supabaseUrl, qrPayload.supabaseAnonKey);
      }
      if (qrPayload.refreshToken && this.supabaseService.isConfigured()) {
        await this.supabaseService.restoreSessionWithRefreshToken(
          qrPayload.refreshToken,
          qrPayload.username
        );
      }
      this.emit({
        accountId: qrPayload.accountId,
        username: qrPayload.username,
        pairSecretHmac: qrPayload.pairSecretHmac,
        statusMessage: `Paired via QR with account '${qrPayload.username}'`
      });
      if (qrPayload.lanHost) {
        await this.tryLanFirstThenOnlineFallback(qrPayload.lanHost, qrPayload.lanPort || 8765);
      } else {
        await this.startTransportLoop();
      }
      return `Paired with ${qrPayload.username}`;
    }

    // 6-digit PIN pairing against LAN peer
    if (/^\d{6}$/.test(trimmed)) {
      const candidate =
        lanHostOptional
          ? { host: lanHostOptional, port: lanPortOptional }
          : await this.findBestLanCandidate();
      if (!candidate) {
        throw new Error('6-digit PIN pairing requires a discovered LAN host or Peer IP.');
      }
      const resp = await fetch(`http://${candidate.host}:${candidate.port}/api/pair/pin`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          pinCode: trimmed,
          deviceId: this.state.localDeviceId,
          deviceName: this.state.localDeviceName
        })
      });
      if (!resp.ok) {
        throw new Error('Invalid or expired 6-digit pairing PIN.');
      }
      const data = (await resp.json()) as QrPairPayload;
      if (data.supabaseUrl && data.supabaseAnonKey) {
        this.supabaseService.configure(data.supabaseUrl, data.supabaseAnonKey);
      }
      this.emit({
        accountId: data.accountId,
        username: data.username,
        pairSecretHmac: data.pairSecretHmac,
        statusMessage: `PIN verified on LAN (${candidate.host}:${candidate.port})`
      });
      await this.tryLanFirstThenOnlineFallback(candidate.host, candidate.port);
      return `Paired with ${data.username} over LAN`;
    }

    throw new Error('Enter a valid Musika QR pairing link or 6-digit PIN.');
  }

  async generateQrPayload(): Promise<QrPairPayload> {
    const lanInfo =
      typeof window !== 'undefined' && window.musikaDesktop
        ? await window.musikaDesktop.getLocalLanInfo()
        : undefined;
    const cfg = this.supabaseService.getConfig();
    return {
      version: '2.2.0',
      accountId: this.state.accountId,
      username: this.state.username,
      pairSecretHmac: this.state.pairSecretHmac,
      pinCode: this.state.activePinCode,
      lanHost: lanInfo?.host,
      lanPort: lanInfo?.port ?? 8765,
      supabaseUrl: cfg.url,
      supabaseAnonKey: cfg.anonKey,
      issuedAtMs: Date.now()
    };
  }
}
