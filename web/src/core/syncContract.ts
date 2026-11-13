import { AudioQuality, getNormalizedTrackKey, LoopMode, RandomMode, Track } from './models';

export const PROTOCOL_VERSION = '2.2.0';

export type ConnectionTransportMode = 'LAN_DIRECT' | 'ONLINE_SUPABASE' | 'OFFLINE_LOCAL';
export type AccountDeviceSyncMode = 'SYNCED_PLAYBACK' | 'INDEPENDENT_CONCURRENT';

export interface RemoteTrackDescriptor {
  relativePathFromRoot: string;
  normalizedTrackKey: string;
  durationMs: number;
  title: string;
  artist: string;
  album: string;
  trackNumber: number;
  discNumber: number;
  audioQuality?: AudioQuality;
  lanStreamUri?: string;
}

export interface PlaybackSnapshot {
  version: string;
  timestamp: number;
  isPlaying: boolean;
  positionMs: number;
  currentTrack?: RemoteTrackDescriptor;
  randomMode: RandomMode;
  loopMode: LoopMode;
  priorityQueue: RemoteTrackDescriptor[];
  standardQueue: RemoteTrackDescriptor[];
  activeContextName?: string;
}

export interface RemoteLibraryTrackSummary {
  trackIdentity: string;
  normalizedKey: string;
  relativePath: string;
  title: string;
  artist: string;
  album: string;
  discNumber: number;
  trackNumber: number;
  durationMs: number;
  audioQuality?: AudioQuality;
}

export interface ConnectedDeviceNode {
  deviceId: string;
  deviceName: string;
  deviceType: 'ANDROID' | 'WEB' | 'DESKTOP';
  accountId: string;
  isThisDevice: boolean;
  isActivePlayingDevice: boolean;
  monitorLocalAudio?: boolean;
  lanHost?: string;
  lanPort?: number;
  lastSnapshot?: PlaybackSnapshot;
  loadedTrackKeys: string[];
  loadedRelativePaths: string[];
  catalogTracks?: RemoteLibraryTrackSummary[];
}

export interface PlaybackTransferResult {
  allowed: boolean;
  sourceDeviceId: string;
  targetDeviceId: string;
  transferredTrack?: RemoteTrackDescriptor;
  reason: string;
}

export type SyncCommand =
  | { type: 'Play' }
  | { type: 'Pause' }
  | { type: 'TogglePlayPause' }
  | { type: 'SeekTo'; positionMs: number }
  | { type: 'SkipNext' }
  | { type: 'SkipPrevious' }
  | { type: 'SetRandom'; mode: RandomMode }
  | { type: 'SetLoop'; mode: LoopMode }
  | { type: 'Enqueue'; track: RemoteTrackDescriptor; immediate: boolean }
  | { type: 'PlayNow'; track: RemoteTrackDescriptor; contextTracks: RemoteTrackDescriptor[] }
  | { type: 'StateUpdate'; snapshot: PlaybackSnapshot }
  | { type: 'SetAccountSyncMode'; mode: AccountDeviceSyncMode }
  | {
      type: 'TransferPlayback';
      sourceDeviceId: string;
      targetDeviceId: string;
      snapshot: PlaybackSnapshot;
      pauseSourceDevice: boolean;
    };

export interface QrPairPayload {
  version: string;
  accountId: string;
  username: string;
  pairSecretHmac: string;
  pinCode: string;
  lanHost?: string;
  lanPort: number;
  supabaseUrl: string;
  supabaseAnonKey: string;
  refreshToken?: string;
  issuedAtMs: number;
}

export interface SyncWireEnvelope {
  type:
    | 'hello'
    | 'auth_challenge'
    | 'auth_proof'
    | 'command'
    | 'snapshot'
    | 'catalog'
    | 'playlists'
    | 'sync_mode'
    | 'ping'
    | 'pong';
  senderDeviceId: string;
  senderDeviceName?: string;
  senderDeviceType?: 'ANDROID' | 'WEB' | 'DESKTOP';
  accountHash?: string;
  targetDeviceId?: string;
  timestampMs: number;
  nonce?: string;
  hmacProof?: string;
  command?: SyncCommand;
  snapshot?: PlaybackSnapshot;
  catalog?: RemoteLibraryTrackSummary[];
  playlists?: Array<{
    name: string;
    clientUpdatedAt: number;
    items: Array<{
      positionIndex: number;
      normalizedKey: string;
      relativePath: string;
      title: string;
      artist: string;
      album: string;
      durationMs: number;
    }>;
  }>;
  syncMode?: AccountDeviceSyncMode;
  lanHost?: string;
  lanPort?: number;
}

export function trackToDescriptor(track: Track, lanHost?: string): RemoteTrackDescriptor {
  const normKey = getNormalizedTrackKey(track);
  const streamUri = lanHost
    ? `http://${lanHost}/stream?path=${encodeURIComponent(track.relativePath)}`
    : undefined;
  return {
    relativePathFromRoot: track.relativePath,
    normalizedTrackKey: normKey,
    durationMs: track.durationMs,
    title: track.title,
    artist: track.artist,
    album: track.album,
    trackNumber: track.trackNumber,
    discNumber: track.discNumber,
    audioQuality: track.audioQuality,
    lanStreamUri: streamUri
  };
}

export function trackToSummary(track: Track): RemoteLibraryTrackSummary {
  const normKey = getNormalizedTrackKey(track);
  return {
    trackIdentity: `${normKey}|${track.relativePath}`,
    normalizedKey: normKey,
    relativePath: track.relativePath,
    title: track.title,
    artist: track.artist,
    album: track.album,
    discNumber: track.discNumber,
    trackNumber: track.trackNumber,
    durationMs: track.durationMs,
    audioQuality: track.audioQuality
  };
}

export function doesDescriptorMatchLocal(
  descriptor: RemoteTrackDescriptor,
  localRelativePath: string,
  localNormalizedKey: string,
  localDurationMs: number
): boolean {
  if (
    descriptor.relativePathFromRoot &&
    localRelativePath &&
    descriptor.relativePathFromRoot.toLowerCase() === localRelativePath.toLowerCase()
  ) {
    return true;
  }
  if (
    descriptor.normalizedTrackKey &&
    localNormalizedKey &&
    descriptor.normalizedTrackKey.toLowerCase() === localNormalizedKey.toLowerCase()
  ) {
    if (descriptor.durationMs <= 0 || localDurationMs <= 0) return true;
    return Math.abs(descriptor.durationMs - localDurationMs) <= 2000;
  }
  return false;
}

export function deviceHasMatchingTrackMetadataLoaded(
  device: ConnectedDeviceNode,
  descriptor: RemoteTrackDescriptor
): boolean {
  const normKey = descriptor.normalizedTrackKey.trim().toLowerCase();
  if (normKey && device.loadedTrackKeys.some((k) => k.toLowerCase() === normKey)) {
    return true;
  }
  const relPath = descriptor.relativePathFromRoot.trim().toLowerCase();
  if (relPath && device.loadedRelativePaths.some((p) => p.toLowerCase() === relPath)) {
    return true;
  }
  if (
    device.catalogTracks?.some((t) =>
      doesDescriptorMatchLocal(descriptor, t.relativePath, t.normalizedKey, t.durationMs)
    )
  ) {
    return true;
  }
  const snap = device.lastSnapshot;
  if (!snap) return false;
  const loaded = [
    ...(snap.currentTrack ? [snap.currentTrack] : []),
    ...snap.priorityQueue,
    ...snap.standardQueue
  ];
  return loaded.some((c) =>
    doesDescriptorMatchLocal(descriptor, c.relativePathFromRoot, c.normalizedTrackKey, c.durationMs)
  );
}

export function validateAndPreparePlaybackTransfer(
  sourceDeviceId: string,
  sourceSnapshot: PlaybackSnapshot,
  targetDevice: ConnectedDeviceNode,
  allowLanStreamingFallback = false
): PlaybackTransferResult {
  const activeTrack = sourceSnapshot.currentTrack;
  if (!activeTrack) {
    return {
      allowed: false,
      sourceDeviceId,
      targetDeviceId: targetDevice.deviceId,
      reason: 'No active track is currently loaded on source device.'
    };
  }

  if (!deviceHasMatchingTrackMetadataLoaded(targetDevice, activeTrack)) {
    return {
      allowed: false,
      sourceDeviceId,
      targetDeviceId: targetDevice.deviceId,
      transferredTrack: activeTrack,
      reason: `Cannot move playback: target device '${targetDevice.deviceName}' does not have '${activeTrack.title}' (${activeTrack.artist} — ${activeTrack.album}) with matching metadata loaded.`
    };
  }

  const skippedCount = allowLanStreamingFallback
    ? 0
    : [...sourceSnapshot.priorityQueue, ...sourceSnapshot.standardQueue].filter(
        (q) => !deviceHasMatchingTrackMetadataLoaded(targetDevice, q)
      ).length;
  const skippedNote =
    skippedCount > 0 ? ` (${skippedCount} queued item(s) missing locally skipped)` : '';

  return {
    allowed: true,
    sourceDeviceId,
    targetDeviceId: targetDevice.deviceId,
    transferredTrack: activeTrack,
    reason: `Metadata verified on '${targetDevice.deviceName}'. Transferred '${activeTrack.title}' at ${sourceSnapshot.positionMs}ms.${skippedNote}`
  };
}

export function filterSnapshotForTargetDevice(
  snapshot: PlaybackSnapshot,
  targetDevice: ConnectedDeviceNode,
  allowLanStreamingFallback = false
): { filteredSnapshot: PlaybackSnapshot; skippedCount: number } {
  if (allowLanStreamingFallback) {
    return { filteredSnapshot: snapshot, skippedCount: 0 };
  }
  const filteredPriority = snapshot.priorityQueue.filter((q) =>
    deviceHasMatchingTrackMetadataLoaded(targetDevice, q)
  );
  const filteredStandard = snapshot.standardQueue.filter((q) =>
    deviceHasMatchingTrackMetadataLoaded(targetDevice, q)
  );
  const skippedCount =
    snapshot.priorityQueue.length -
    filteredPriority.length +
    (snapshot.standardQueue.length - filteredStandard.length);
  return {
    filteredSnapshot: {
      ...snapshot,
      priorityQueue: filteredPriority,
      standardQueue: filteredStandard
    },
    skippedCount
  };
}

export async function sha256Hex(input: string): Promise<string> {
  const data = new TextEncoder().encode(input);
  const hashBuffer = await crypto.subtle.digest('SHA-256', data);
  return Array.from(new Uint8Array(hashBuffer))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');
}

export async function shortAccountHash(accountId: string): Promise<string> {
  const digest = await sha256Hex(accountId.trim().toLowerCase());
  return digest.slice(0, 12);
}

export async function computeHmacHex(secret: string, message: string): Promise<string> {
  const enc = new TextEncoder();
  const key = await crypto.subtle.importKey(
    'raw',
    enc.encode(secret),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign']
  );
  const sig = await crypto.subtle.sign('HMAC', key, enc.encode(message));
  return Array.from(new Uint8Array(sig))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');
}

export function encodeQrPairLink(payload: QrPairPayload): string {
  const jsonStr = JSON.stringify(payload);
  const bytes = new TextEncoder().encode(jsonStr);
  let bin = '';
  for (const b of bytes) bin += String.fromCharCode(b);
  const base64url = btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return `musika://pair?p=${base64url}`;
}

export function parseQrPairInput(rawInput: string): QrPairPayload | null {
  const trimmed = rawInput.trim();
  if (!trimmed) return null;
  try {
    if (trimmed.startsWith('{')) {
      return JSON.parse(trimmed) as QrPairPayload;
    }
    let token = '';
    if (trimmed.includes('p=')) {
      token = trimmed.split('p=')[1].split('&')[0].split('#')[0];
    } else if (trimmed.includes('#pair=')) {
      token = trimmed.split('#pair=')[1].split('&')[0];
    }
    if (!token) return null;
    const base64 = token.replace(/-/g, '+').replace(/_/g, '/');
    const pad = base64.length % 4 === 0 ? '' : '='.repeat(4 - (base64.length % 4));
    const bin = atob(base64 + pad);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    return JSON.parse(new TextDecoder().decode(bytes)) as QrPairPayload;
  } catch {
    return null;
  }
}
