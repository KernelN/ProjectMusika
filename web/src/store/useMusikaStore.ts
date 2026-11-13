import { create } from 'zustand';
import {
  buildAlbums,
  buildArtists,
  buildFolderTree,
  deduplicateTracks,
  isDiscFolder,
  orderTracksForPlayAllDiscs,
  sortFolders,
  sortSongs
} from '../core/deduplicator';
import {
  Album,
  AlbumSortOption,
  Artist,
  ArtistSortOption,
  DeduplicatedTrackGroup,
  FolderNode,
  FolderSortOption,
  getNormalizedTrackKey,
  LoopMode,
  NowPlayingArtworkStyle,
  Playlist,
  PlaylistTrackItem,
  RandomMode,
  SongSortOption,
  Track
} from '../core/models';
import {
  AccountDeviceSyncMode,
  parseQrPairInput,
  RemoteLibraryTrackSummary,
  SyncCommand
} from '../core/syncContract';
import { DualQueueEngine, EngineState } from '../player/DualQueueEngine';
import {
  getRegisteredBrowserFile,
  loadCachedDirectoryHandle,
  loadCachedTracks,
  scanBrowserDirectoryHandle,
  scanFallbackFileList
} from '../scanner/localScanner';
import { DualTransportCoordinator, OnlineControlState } from '../sync/dualTransportCoordinator';
import { SupabaseSyncService } from '../sync/supabaseSync';

export type MusikaTab = 'SONGS' | 'ALBUMS' | 'ARTISTS' | 'PLAYLISTS' | 'FOLDERS';

const STORAGE_KEY = 'musika_web_settings_v2';

interface PersistedSettings {
  localDeviceId: string;
  hudScale: number;
  artworkStyle: NowPlayingArtworkStyle;
  showListThumbnails: boolean;
  songSort: SongSortOption;
  albumSort: AlbumSortOption;
  artistSort: ArtistSortOption;
  folderSort: FolderSortOption;
  randomMode: RandomMode;
  loopMode: LoopMode;
  allowLanStreamingForMissingTracks: boolean;
  listenLocallyWhenSyncedTargetIsRemote: boolean;
  supabaseUrl: string;
  supabaseAnonKey: string;
  authUserId: string;
  authUsername: string;
  authRefreshToken: string;
  pairSecretHmac: string;
  rootPath: string;
  playlists: Playlist[];
}

function loadSettings(): PersistedSettings {
  const defaultSupabaseUrl = (import.meta.env.VITE_SUPABASE_URL as string | undefined) || '';
  const defaultSupabaseAnonKey = (import.meta.env.VITE_SUPABASE_ANON_KEY as string | undefined) || '';
  const defaults: PersistedSettings = {
    localDeviceId:
      (typeof window !== 'undefined' && window.musikaDesktop?.isDesktopApp ? 'desktop-' : 'web-') +
      Math.random().toString(36).slice(2, 10),
    hudScale: 1.0,
    artworkStyle: 'ROUNDED_SQUARE',
    showListThumbnails: true,
    songSort: 'ALPHABETICAL',
    albumSort: 'ALPHABETICAL',
    artistSort: 'ALPHABETICAL',
    folderSort: 'SYSTEM_DEFAULT',
    randomMode: 'OFF',
    loopMode: 'NO_LOOP',
    allowLanStreamingForMissingTracks: false,
    listenLocallyWhenSyncedTargetIsRemote: false,
    supabaseUrl: defaultSupabaseUrl,
    supabaseAnonKey: defaultSupabaseAnonKey,
    authUserId: '',
    authUsername: '',
    authRefreshToken: '',
    pairSecretHmac: '',
    rootPath: '/Music',
    playlists: []
  };
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return defaults;
    const parsed = JSON.parse(raw) as Partial<PersistedSettings>;
    return {
      ...defaults,
      ...parsed,
      supabaseUrl: parsed.supabaseUrl || defaults.supabaseUrl,
      supabaseAnonKey: parsed.supabaseAnonKey || defaults.supabaseAnonKey,
      playlists: Array.isArray(parsed.playlists) ? parsed.playlists : []
    };
  } catch {
    return defaults;
  }
}

function persistSettings(next: Partial<PersistedSettings>) {
  try {
    const cur = loadSettings();
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ ...cur, ...next }));
  } catch {
    // ignore storage errors
  }
}

const initialSettings = loadSettings();

export const supabaseService = new SupabaseSyncService();
supabaseService.configure(initialSettings.supabaseUrl, initialSettings.supabaseAnonKey);

export const dualQueueEngine = new DualQueueEngine(
  initialSettings.randomMode,
  initialSettings.loopMode,
  (randomMode, loopMode) => {
    persistSettings({ randomMode, loopMode });
  }
);

const deviceName =
  typeof window !== 'undefined' && window.musikaDesktop?.isDesktopApp
    ? 'Musika Desktop (Electron)'
    : `Musika Web (${typeof navigator !== 'undefined' ? navigator.platform || 'Browser' : 'Browser'})`;

export const transportCoordinator = new DualTransportCoordinator(
  dualQueueEngine,
  supabaseService,
  initialSettings.localDeviceId,
  deviceName,
  {
    userId: initialSettings.authUserId || undefined,
    username: initialSettings.authUsername || undefined,
    pairSecretHmac: initialSettings.pairSecretHmac || undefined
  }
);
transportCoordinator.setAllowLanStreamingForMissingTracks(
  initialSettings.allowLanStreamingForMissingTracks
);
transportCoordinator.setListenLocallyWhenSyncedTargetIsRemote(
  initialSettings.listenLocallyWhenSyncedTargetIsRemote
);

// Single shared HTML5 Audio player wired to DualQueueEngine
let audioEl: HTMLAudioElement | null = null;
let currentObjectUrl: string | null = null;
let progressTicker: number | null = null;

function ensureAudioElement(): HTMLAudioElement | null {
  if (typeof window === 'undefined' || typeof Audio === 'undefined') return null;
  if (!audioEl) {
    audioEl = new Audio();
    audioEl.addEventListener('ended', () => {
      dualQueueEngine.skipToNext(true);
    });
    audioEl.addEventListener('loadedmetadata', () => {
      if (audioEl && Number.isFinite(audioEl.duration)) {
        dualQueueEngine.updateProgress(audioEl.currentTime * 1000, audioEl.duration * 1000);
      }
    });
    if (progressTicker === null) {
      progressTicker = window.setInterval(() => {
        if (audioEl && !audioEl.paused) {
          const durMs = Number.isFinite(audioEl.duration) ? audioEl.duration * 1000 : 0;
          dualQueueEngine.updateProgress(audioEl.currentTime * 1000, durMs);
        }
      }, 250);
    }
  }
  return audioEl;
}

dualQueueEngine.driver = {
  onLoadAndPlayTrack(track, startPositionMs, playWhenReady) {
    const audio = ensureAudioElement();
    if (!audio) return;
    if (currentObjectUrl) {
      URL.revokeObjectURL(currentObjectUrl);
      currentObjectUrl = null;
    }

    let srcUrl = '';
    const file = getRegisteredBrowserFile(track.filePath);
    if (file) {
      currentObjectUrl = URL.createObjectURL(file);
      srcUrl = currentObjectUrl;
    } else if (track.lanStreamUri) {
      srcUrl = track.lanStreamUri;
    } else if (typeof window !== 'undefined' && window.musikaDesktop && !track.filePath.startsWith('http')) {
      srcUrl = window.musikaDesktop.toLocalFileUrl(track.filePath);
    } else if (track.filePath.startsWith('http://') || track.filePath.startsWith('https://')) {
      srcUrl = track.filePath;
    }

    if (!srcUrl) return;
    audio.src = srcUrl;
    audio.currentTime = Math.max(0, startPositionMs / 1000);
    if (playWhenReady) {
      void audio.play().catch(() => {
        // Autoplay policy block before interaction
      });
    }
  },
  onPlayStateChanged(isPlaying) {
    const audio = ensureAudioElement();
    if (!audio || !audio.src) return;
    if (isPlaying) {
      void audio.play().catch(() => {});
    } else {
      audio.pause();
    }
  },
  onSeekTo(positionMs) {
    const audio = ensureAudioElement();
    if (!audio) return;
    audio.currentTime = Math.max(0, positionMs / 1000);
  }
};

export interface MultiFolderSheetState {
  sourceTitle: string;
  folderPaths: string[];
  expanded: boolean;
}

interface MusikaStore {
  selectedTab: MusikaTab;
  rootPath: string;
  rawTracks: Track[];
  isScanning: boolean;
  scanCount: number;
  statusBanner: string | null;

  songSort: SongSortOption;
  albumSort: AlbumSortOption;
  artistSort: ArtistSortOption;
  folderSort: FolderSortOption;
  hudScale: number;
  artworkStyle: NowPlayingArtworkStyle;
  showListThumbnails: boolean;

  selectedAlbumTitle: string | null;
  selectedArtistName: string | null;
  selectedArtistAlbumFilter: string | null;
  selectedPlaylistId: string | null;
  currentFolderPath: string;

  playlists: Playlist[];
  scrollMemory: Record<string, number>;

  playbackState: EngineState;
  onlineState: OnlineControlState;

  showNowPlayingModal: boolean;
  showQueueModal: boolean;
  showSettingsModal: boolean;
  showConnectModal: boolean;
  variantPickerGroup: DeduplicatedTrackGroup | null;
  multiFolderSheet: MultiFolderSheetState | null;
  addToPlaylistCandidates: Track[] | null;

  initialize: () => Promise<void>;
  setSelectedTab: (tab: MusikaTab) => void;
  setSongSort: (opt: SongSortOption) => void;
  setAlbumSort: (opt: AlbumSortOption) => void;
  setArtistSort: (opt: ArtistSortOption) => void;
  setFolderSort: (opt: FolderSortOption) => void;
  setHudScale: (scale: number) => void;
  setArtworkStyle: (style: NowPlayingArtworkStyle) => void;
  setShowListThumbnails: (show: boolean) => void;
  setStatusBanner: (msg: string | null) => void;

  pickAndScanFolder: (fallbackFileList?: FileList) => Promise<void>;
  rescanCurrentRoot: () => Promise<void>;
  applyScannedLibrary: (rootPath: string, tracks: Track[]) => Promise<void>;

  saveScrollTop: (key: string, scrollTop: number) => void;
  jumpToAlbum: (albumTitle: string) => void;
  closeAlbumDetail: () => void;
  jumpToArtist: (artistName: string) => void;
  selectArtistAlbumFilter: (albumTitle: string | null) => void;
  closeArtistDetail: () => void;
  selectPlaylist: (playlistId: string | null) => void;
  openFolder: (folderPath: string) => void;
  navigateUpFolder: () => boolean;
  jumpToSectionRoot: () => void;
  jumpToFolders: (folderPaths: string[], sourceTitle: string) => void;
  toggleMultiFolderExpanded: () => void;
  dismissMultiFolderSheet: () => void;

  setShowNowPlayingModal: (open: boolean) => void;
  setShowQueueModal: (open: boolean) => void;
  setShowSettingsModal: (open: boolean) => void;
  setShowConnectModal: (open: boolean) => void;
  setVariantPickerGroup: (group: DeduplicatedTrackGroup | null) => void;
  setAddToPlaylistCandidates: (tracks: Track[] | null) => void;

  playTrackInContext: (track: Track, contextTracks: Track[], label: string) => void;
  playAllDiscsForAlbum: (albumTitle: string) => void;
  playAllDiscsInFolder: (folderPath: string, folderName: string) => void;
  selectFormatVariant: (group: DeduplicatedTrackGroup, chosen: Track) => void;

  createPlaylist: (name: string, tracks?: Track[]) => Promise<void>;
  renamePlaylist: (playlistId: string, newName: string) => Promise<void>;
  deletePlaylist: (playlistId: string) => Promise<void>;
  addTracksToPlaylist: (playlistId: string, tracks: Track[]) => Promise<void>;
  removeTrackFromPlaylist: (playlistId: string, index: number) => Promise<void>;
  moveTrackInPlaylist: (playlistId: string, fromIndex: number, toIndex: number) => Promise<void>;
  saveCurrentQueueAsPlaylist: (name: string) => Promise<void>;

  updateSupabaseConfig: (url: string, anonKey: string) => void;
  setAllowLanStreaming: (enabled: boolean) => void;
  setListenLocallyInSynced: (enabled: boolean) => void;
  signUpUsername: (username: string, password: string) => Promise<void>;
  signInUsername: (username: string, password: string) => Promise<void>;
  signOutAccount: () => Promise<void>;
  pairViaQrOrPin: (input: string, manualLanHost?: string) => Promise<string>;
  connectManualLanHost: (host: string, port: number) => Promise<boolean>;
  setAccountSyncMode: (mode: AccountDeviceSyncMode) => Promise<void>;
  setBrowsingDeviceId: (deviceId: string) => void;
  sendRemoteCommand: (cmd: SyncCommand, targetDeviceId?: string) => Promise<void>;
  transferPlaybackToDevice: (targetDeviceId: string) => Promise<void>;
}

function tracksFromRemoteSummaries(summaries: RemoteLibraryTrackSummary[], lanHost?: string): Track[] {
  return summaries.map((s) => ({
    filePath: s.relativePath || s.trackIdentity,
    relativePath: s.relativePath,
    fileName: s.relativePath.split('/').pop() || s.title,
    fileExtension: (s.audioQuality?.format || 'mp3').toLowerCase(),
    title: s.title,
    artist: s.artist,
    album: s.album,
    discNumber: s.discNumber,
    totalDiscs: 1,
    trackNumber: s.trackNumber,
    durationMs: s.durationMs,
    audioQuality: s.audioQuality ?? {
      format: 'MP3',
      bitrateKbps: 320,
      sampleRateHz: 44100,
      bitDepth: 16,
      isLossless: false
    },
    parentFolderPath: '/' + (s.relativePath.includes('/') ? s.relativePath.slice(0, s.relativePath.lastIndexOf('/')) : 'Remote'),
    lanStreamUri: lanHost
      ? `http://${lanHost}/stream?path=${encodeURIComponent(s.relativePath)}`
      : undefined
  }));
}

export const useMusikaStore = create<MusikaStore>((set, get) => {
  dualQueueEngine.subscribe((playbackState) => {
    set({ playbackState });
  });

  transportCoordinator.subscribe((onlineState) => {
    set({ onlineState });
  });

  transportCoordinator.setPlaylistSyncCallbacks(
    () => get().playlists,
    (remotePlaylists) => {
      const existing = get().playlists;
      const byName = new Map(existing.map((p) => [p.name.toLowerCase(), p]));
      for (const remote of remotePlaylists) {
        const current = byName.get(remote.name.toLowerCase());
        if (!current || remote.updatedAt >= current.updatedAt) {
          byName.set(remote.name.toLowerCase(), remote);
        }
      }
      const merged = Array.from(byName.values()).sort((a, b) => b.createdAt - a.createdAt);
      persistSettings({ playlists: merged });
      set({ playlists: merged });
    }
  );

  return {
    selectedTab: 'SONGS',
    rootPath: initialSettings.rootPath,
    rawTracks: [],
    isScanning: false,
    scanCount: 0,
    statusBanner: null,

    songSort: initialSettings.songSort,
    albumSort: initialSettings.albumSort,
    artistSort: initialSettings.artistSort,
    folderSort: initialSettings.folderSort,
    hudScale: initialSettings.hudScale,
    artworkStyle: initialSettings.artworkStyle,
    showListThumbnails: initialSettings.showListThumbnails,

    selectedAlbumTitle: null,
    selectedArtistName: null,
    selectedArtistAlbumFilter: null,
    selectedPlaylistId: null,
    currentFolderPath: initialSettings.rootPath,

    playlists: initialSettings.playlists,
    scrollMemory: {},

    playbackState: dualQueueEngine.getState(),
    onlineState: transportCoordinator.getState(),

    showNowPlayingModal: false,
    showQueueModal: false,
    showSettingsModal: false,
    showConnectModal: false,
    variantPickerGroup: null,
    multiFolderSheet: null,
    addToPlaylistCandidates: null,

    async initialize() {
      document.documentElement.style.setProperty('--musika-hud-scale', String(get().hudScale));

      // Handle QR deep link in URL hash (#pair=...) automatically when opened from Phone LAN QR
      if (typeof window !== 'undefined' && window.location.hash.includes('pair=')) {
        const parsed = parseQrPairInput(window.location.href);
        if (parsed) {
          try {
            const msg = await transportCoordinator.pairViaQrOrPinInput(window.location.href);
            persistSettings({
              authUserId: parsed.accountId,
              authUsername: parsed.username,
              pairSecretHmac: parsed.pairSecretHmac,
              supabaseUrl: parsed.supabaseUrl || get().onlineState.accountId,
              supabaseAnonKey: parsed.supabaseAnonKey || supabaseService.getConfig().anonKey
            });
            set({ statusBanner: msg });
            window.history.replaceState(null, '', window.location.pathname);
          } catch {
            // ignore bad URL hash
          }
        }
      }

      // Restore cached IndexedDB catalog instantly (zero UI freeze)
      const cached = await loadCachedTracks();
      if (cached && cached.tracks.length > 0) {
        set({
          rootPath: cached.rootPath,
          currentFolderPath: cached.rootPath,
          rawTracks: cached.tracks
        });
        await transportCoordinator.updateLocalLibrary(cached.tracks, cached.rootPath);
      }

      // Restore Supabase auth if refresh token persisted
      if (initialSettings.authRefreshToken && supabaseService.isConfigured()) {
        const restored = await supabaseService.restoreSessionWithRefreshToken(
          initialSettings.authRefreshToken,
          initialSettings.authUsername
        );
        if (restored) {
          persistSettings({
            authUserId: restored.userId,
            authRefreshToken: restored.refreshToken,
            pairSecretHmac: restored.pairSecretHmac
          });
          await transportCoordinator.setAccountSession(restored);
        }
      } else {
        await transportCoordinator.startTransportLoop();
      }
    },

    setSelectedTab(tab) {
      set({ selectedTab: tab });
    },
    setSongSort(opt) {
      persistSettings({ songSort: opt });
      set({ songSort: opt });
    },
    setAlbumSort(opt) {
      persistSettings({ albumSort: opt });
      set({ albumSort: opt });
    },
    setArtistSort(opt) {
      persistSettings({ artistSort: opt });
      set({ artistSort: opt });
    },
    setFolderSort(opt) {
      persistSettings({ folderSort: opt });
      set({ folderSort: opt });
    },
    setHudScale(scale) {
      const clamped = Math.max(0.85, Math.min(1.4, scale));
      document.documentElement.style.setProperty('--musika-hud-scale', String(clamped));
      persistSettings({ hudScale: clamped });
      set({ hudScale: clamped });
    },
    setArtworkStyle(style) {
      persistSettings({ artworkStyle: style });
      set({ artworkStyle: style });
    },
    setShowListThumbnails(show) {
      persistSettings({ showListThumbnails: show });
      set({ showListThumbnails: show });
    },
    setStatusBanner(msg) {
      set({ statusBanner: msg });
    },

    async pickAndScanFolder(fallbackFileList) {
      set({ isScanning: true, scanCount: 0 });
      try {
        if (typeof window !== 'undefined' && window.musikaDesktop?.isDesktopApp && !fallbackFileList) {
          const picked = await window.musikaDesktop.pickFolder();
          if (picked) {
            const res = await window.musikaDesktop.scanDirectory(picked);
            await get().applyScannedLibrary(res.rootPath, res.tracks);
          }
          return;
        }

        if (fallbackFileList && fallbackFileList.length > 0) {
          const res = await scanFallbackFileList(fallbackFileList, (count) =>
            set({ scanCount: count })
          );
          await get().applyScannedLibrary(res.rootPath, res.tracks);
          return;
        }

        const picker = (window as unknown as { showDirectoryPicker?: () => Promise<FileSystemDirectoryHandle> }).showDirectoryPicker;
        if (picker) {
          const dirHandle = await picker();
          const res = await scanBrowserDirectoryHandle(dirHandle, get().rawTracks, (count) =>
            set({ scanCount: count })
          );
          await get().applyScannedLibrary(res.rootPath, res.tracks);
        } else {
          set({
            statusBanner: 'Browser directory picker unavailable here — use the Fallback File Folder button.'
          });
        }
      } catch (err) {
        if ((err as Error)?.name !== 'AbortError') {
          set({ statusBanner: `Folder scan cancelled or failed: ${(err as Error).message}` });
        }
      } finally {
        set({ isScanning: false });
      }
    },

    async rescanCurrentRoot() {
      set({ isScanning: true, scanCount: 0 });
      try {
        if (typeof window !== 'undefined' && window.musikaDesktop?.isDesktopApp) {
          const res = await window.musikaDesktop.scanDirectory(get().rootPath);
          await get().applyScannedLibrary(res.rootPath, res.tracks);
          return;
        }
        const cachedHandle = await loadCachedDirectoryHandle();
        if (cachedHandle) {
          const perm = await (cachedHandle as unknown as {
            requestPermission?: (opts: { mode: string }) => Promise<string>;
          }).requestPermission?.({ mode: 'read' });
          if (!perm || perm === 'granted') {
            const res = await scanBrowserDirectoryHandle(cachedHandle, get().rawTracks, (count) =>
              set({ scanCount: count })
            );
            await get().applyScannedLibrary(res.rootPath, res.tracks);
            return;
          }
        }
        await get().pickAndScanFolder();
      } finally {
        set({ isScanning: false });
      }
    },

    async applyScannedLibrary(rootPath, tracks) {
      persistSettings({ rootPath });
      set({
        rootPath,
        currentFolderPath: rootPath,
        rawTracks: tracks,
        statusBanner: `Scanned ${tracks.length} audio file(s) from ${rootPath}`
      });
      await transportCoordinator.updateLocalLibrary(tracks, rootPath);
    },

    saveScrollTop(key, scrollTop) {
      set((s) => ({
        scrollMemory: { ...s.scrollMemory, [key]: scrollTop }
      }));
    },

    jumpToAlbum(albumTitle) {
      set({
        selectedArtistName: null,
        selectedPlaylistId: null,
        selectedAlbumTitle: albumTitle,
        selectedTab: 'ALBUMS'
      });
    },
    closeAlbumDetail() {
      set({ selectedAlbumTitle: null });
    },
    jumpToArtist(artistName) {
      set({
        selectedAlbumTitle: null,
        selectedPlaylistId: null,
        selectedArtistAlbumFilter: null,
        selectedArtistName: artistName,
        selectedTab: 'ARTISTS'
      });
    },
    selectArtistAlbumFilter(albumTitle) {
      set({ selectedArtistAlbumFilter: albumTitle });
    },
    closeArtistDetail() {
      set({ selectedArtistName: null, selectedArtistAlbumFilter: null });
    },
    selectPlaylist(playlistId) {
      set({ selectedPlaylistId: playlistId, selectedTab: 'PLAYLISTS' });
    },
    openFolder(folderPath) {
      set({
        multiFolderSheet: null,
        selectedAlbumTitle: null,
        selectedArtistName: null,
        currentFolderPath: folderPath,
        selectedTab: 'FOLDERS'
      });
    },
    navigateUpFolder() {
      const cur = get().currentFolderPath;
      const root = get().rootPath;
      if (cur === root) return false;
      const idx = cur.lastIndexOf('/');
      const parent = idx > 0 ? cur.slice(0, idx) : root;
      const next = parent.length < root.length ? root : parent;
      set({ currentFolderPath: next });
      return true;
    },
    jumpToSectionRoot() {
      const tab = get().selectedTab;
      if (tab === 'ALBUMS') set({ selectedAlbumTitle: null });
      else if (tab === 'ARTISTS') set({ selectedArtistName: null, selectedArtistAlbumFilter: null });
      else if (tab === 'PLAYLISTS') set({ selectedPlaylistId: null });
      else if (tab === 'FOLDERS') set({ currentFolderPath: get().rootPath });
    },
    jumpToFolders(folderPaths, sourceTitle) {
      const clean = Array.from(new Set(folderPaths.filter(Boolean)));
      if (clean.length === 0) return;
      if (clean.length === 1) {
        get().openFolder(clean[0]);
      } else {
        set({
          multiFolderSheet: { sourceTitle, folderPaths: clean, expanded: false }
        });
      }
    },
    toggleMultiFolderExpanded() {
      const cur = get().multiFolderSheet;
      if (cur) set({ multiFolderSheet: { ...cur, expanded: !cur.expanded } });
    },
    dismissMultiFolderSheet() {
      set({ multiFolderSheet: null });
    },

    setShowNowPlayingModal: (open) => set({ showNowPlayingModal: open }),
    setShowQueueModal: (open) => set({ showQueueModal: open }),
    setShowSettingsModal: (open) => set({ showSettingsModal: open }),
    setShowConnectModal: (open) => set({ showConnectModal: open }),
    setVariantPickerGroup: (group) => set({ variantPickerGroup: group }),
    setAddToPlaylistCandidates: (tracks) => set({ addToPlaylistCandidates: tracks }),

    playTrackInContext(track, contextTracks, label) {
      const online = get().onlineState;
      const isBrowsingRemote = online.browsingDeviceId !== online.localDeviceId;
      if (isBrowsingRemote) {
        const peer = online.connectedDevices.find((d) => d.deviceId === online.browsingDeviceId);
        const lanHost = peer?.lanHost && peer.lanPort ? `${peer.lanHost}:${peer.lanPort}` : undefined;
        const primaryDesc = {
          relativePathFromRoot: track.relativePath,
          normalizedTrackKey: getNormalizedTrackKey(track),
          durationMs: track.durationMs,
          title: track.title,
          artist: track.artist,
          album: track.album,
          trackNumber: track.trackNumber,
          discNumber: track.discNumber,
          audioQuality: track.audioQuality,
          lanStreamUri: lanHost ? `http://${lanHost}/stream?path=${encodeURIComponent(track.relativePath)}` : undefined
        };
        const contextDescs = contextTracks.map((t) => ({
          relativePathFromRoot: t.relativePath,
          normalizedTrackKey: getNormalizedTrackKey(t),
          durationMs: t.durationMs,
          title: t.title,
          artist: t.artist,
          album: t.album,
          trackNumber: t.trackNumber,
          discNumber: t.discNumber,
          audioQuality: t.audioQuality
        }));
        void transportCoordinator.sendRemoteCommand(
          { type: 'PlayNow', track: primaryDesc, contextTracks: contextDescs },
          online.browsingDeviceId
        );
        set({ statusBanner: `Sent remote Play command to ${peer?.deviceName || online.browsingDeviceId}` });
        return;
      }

      dualQueueEngine.playFromContext(track, contextTracks, label);
      void transportCoordinator.sendRemoteCommand({
        type: 'StateUpdate',
        snapshot: dualQueueEngine.toPlaybackSnapshot()
      });
    },

    playAllDiscsForAlbum(albumTitle) {
      const tracks = getActiveTracksForStore(get()).filter(
        (t) => t.album.toLowerCase() === albumTitle.toLowerCase()
      );
      const deduped = deduplicateTracks(tracks).map((g) => g.primaryTrack);
      dualQueueEngine.playAllDiscs(deduped, albumTitle);
    },

    playAllDiscsInFolder(folderPath, folderName) {
      const tracks = getActiveTracksForStore(get());
      const tree = buildFolderTree(get().rootPath, tracks);
      const node = tree.get(folderPath);
      const parentPath = node?.parentPath || get().rootPath;
      const siblingDiscFolders = Array.from(tree.values()).filter(
        (n) => n.parentPath === parentPath && isDiscFolder(n.folderName)
      );
      const siblingSet = new Set(siblingDiscFolders.map((n) => n.absolutePath));
      const allTracks =
        siblingSet.size >= 2 && isDiscFolder(node?.folderName || folderName)
          ? tracks.filter((t) => siblingSet.has(t.parentFolderPath))
          : tracks.filter((t) => t.parentFolderPath === folderPath);
      dualQueueEngine.playAllDiscs(allTracks, folderName);
    },

    selectFormatVariant(group, chosen) {
      set({ variantPickerGroup: null });
      const ctx = dualQueueEngine.getState().standardQueue;
      const updated = (ctx.length > 0 ? ctx : [chosen]).map((t) =>
        getNormalizedTrackKey(t) === group.normalizedKey ? chosen : t
      );
      dualQueueEngine.playFromContext(
        chosen,
        updated,
        dualQueueEngine.getState().activeContextName || 'Format Override'
      );
    },

    async createPlaylist(name, tracks = []) {
      const clean = name.trim();
      if (!clean) return;
      const now = Date.now();
      const playlist: Playlist = {
        id: crypto.randomUUID(),
        name: clean,
        createdAt: now,
        updatedAt: now,
        items: tracks.map((t, idx) => ({
          positionIndex: idx,
          normalizedKey: getNormalizedTrackKey(t),
          relativePath: t.relativePath,
          title: t.title,
          artist: t.artist,
          album: t.album,
          durationMs: t.durationMs
        }))
      };
      const next = [playlist, ...get().playlists];
      persistSettings({ playlists: next });
      set({
        playlists: next,
        addToPlaylistCandidates: null,
        statusBanner: `Created playlist '${clean}' (${tracks.length} tracks)`
      });
      if (supabaseService.isConfigured() && get().onlineState.accountId !== 'local-guest') {
        await supabaseService.upsertPlaylist(get().onlineState.accountId, playlist);
      }
    },

    async renamePlaylist(playlistId, newName) {
      const clean = newName.trim();
      if (!clean) return;
      const old = get().playlists.find((p) => p.id === playlistId);
      const next = get().playlists.map((p) =>
        p.id === playlistId ? { ...p, name: clean, updatedAt: Date.now() } : p
      );
      persistSettings({ playlists: next });
      set({ playlists: next, statusBanner: `Renamed playlist to '${clean}'` });
      const updated = next.find((p) => p.id === playlistId);
      if (updated && supabaseService.isConfigured() && get().onlineState.accountId !== 'local-guest') {
        if (old && old.name !== clean) {
          await supabaseService.deletePlaylistRemote(get().onlineState.accountId, old.name);
        }
        await supabaseService.upsertPlaylist(get().onlineState.accountId, updated);
      }
    },

    async deletePlaylist(playlistId) {
      const target = get().playlists.find((p) => p.id === playlistId);
      const next = get().playlists.filter((p) => p.id !== playlistId);
      persistSettings({ playlists: next });
      set((s) => ({
        playlists: next,
        selectedPlaylistId: s.selectedPlaylistId === playlistId ? null : s.selectedPlaylistId,
        statusBanner: 'Deleted playlist'
      }));
      if (target && supabaseService.isConfigured() && get().onlineState.accountId !== 'local-guest') {
        await supabaseService.deletePlaylistRemote(get().onlineState.accountId, target.name);
      }
    },

    async addTracksToPlaylist(playlistId, tracks) {
      const next = get().playlists.map((p) => {
        if (p.id !== playlistId) return p;
        const startIdx = p.items.length;
        const appended: PlaylistTrackItem[] = tracks.map((t, offset) => ({
          positionIndex: startIdx + offset,
          normalizedKey: getNormalizedTrackKey(t),
          relativePath: t.relativePath,
          title: t.title,
          artist: t.artist,
          album: t.album,
          durationMs: t.durationMs
        }));
        return { ...p, updatedAt: Date.now(), items: [...p.items, ...appended] };
      });
      persistSettings({ playlists: next });
      set({
        playlists: next,
        addToPlaylistCandidates: null,
        statusBanner: `Added ${tracks.length} track(s) to playlist`
      });
      const updated = next.find((p) => p.id === playlistId);
      if (updated && supabaseService.isConfigured() && get().onlineState.accountId !== 'local-guest') {
        await supabaseService.upsertPlaylist(get().onlineState.accountId, updated);
      }
    },

    async removeTrackFromPlaylist(playlistId, index) {
      const next = get().playlists.map((p) => {
        if (p.id !== playlistId) return p;
        const items = p.items
          .filter((_, i) => i !== index)
          .map((item, idx) => ({ ...item, positionIndex: idx }));
        return { ...p, updatedAt: Date.now(), items };
      });
      persistSettings({ playlists: next });
      set({ playlists: next });
      const updated = next.find((p) => p.id === playlistId);
      if (updated && supabaseService.isConfigured() && get().onlineState.accountId !== 'local-guest') {
        await supabaseService.upsertPlaylist(get().onlineState.accountId, updated);
      }
    },

    async moveTrackInPlaylist(playlistId, fromIndex, toIndex) {
      const next = get().playlists.map((p) => {
        if (p.id !== playlistId) return p;
        if (fromIndex < 0 || toIndex < 0 || fromIndex >= p.items.length || toIndex >= p.items.length) return p;
        const copy = [...p.items];
        const [moved] = copy.splice(fromIndex, 1);
        copy.splice(toIndex, 0, moved);
        return {
          ...p,
          updatedAt: Date.now(),
          items: copy.map((item, idx) => ({ ...item, positionIndex: idx }))
        };
      });
      persistSettings({ playlists: next });
      set({ playlists: next });
      const updated = next.find((p) => p.id === playlistId);
      if (updated && supabaseService.isConfigured() && get().onlineState.accountId !== 'local-guest') {
        await supabaseService.upsertPlaylist(get().onlineState.accountId, updated);
      }
    },

    async saveCurrentQueueAsPlaylist(name) {
      const tracks = dualQueueEngine.exportQueueTracksForPlaylist();
      if (tracks.length === 0) {
        set({ statusBanner: 'Queue is empty — nothing to save.' });
        return;
      }
      await get().createPlaylist(name, tracks);
    },

    updateSupabaseConfig(url, anonKey) {
      supabaseService.configure(url, anonKey);
      persistSettings({ supabaseUrl: url, supabaseAnonKey: anonKey });
      set({ statusBanner: 'Saved Supabase Online endpoint configuration.' });
      void transportCoordinator.startTransportLoop();
    },

    setAllowLanStreaming(enabled) {
      persistSettings({ allowLanStreamingForMissingTracks: enabled });
      transportCoordinator.setAllowLanStreamingForMissingTracks(enabled);
      void transportCoordinator.updateLocalLibrary(get().rawTracks, get().rootPath);
    },

    setListenLocallyInSynced(enabled) {
      persistSettings({ listenLocallyWhenSyncedTargetIsRemote: enabled });
      transportCoordinator.setListenLocallyWhenSyncedTargetIsRemote(enabled);
    },

    async signUpUsername(username, password) {
      const session = await supabaseService.signUpWithUsername(username, password);
      persistSettings({
        authUserId: session.userId,
        authUsername: session.username,
        authRefreshToken: session.refreshToken,
        pairSecretHmac: session.pairSecretHmac
      });
      await transportCoordinator.setAccountSession(session);
      set({ statusBanner: `Signed up & logged in as ${session.username}` });
    },

    async signInUsername(username, password) {
      const session = await supabaseService.signInWithUsername(username, password);
      persistSettings({
        authUserId: session.userId,
        authUsername: session.username,
        authRefreshToken: session.refreshToken,
        pairSecretHmac: session.pairSecretHmac
      });
      await transportCoordinator.setAccountSession(session);
      set({ statusBanner: `Signed in as ${session.username}` });
    },

    async signOutAccount() {
      await supabaseService.disconnectRealtime();
      persistSettings({
        authUserId: '',
        authUsername: '',
        authRefreshToken: ''
      });
      set({ statusBanner: 'Signed out — running in local mode' });
    },

    async pairViaQrOrPin(input, manualLanHost) {
      const msg = await transportCoordinator.pairViaQrOrPinInput(input, manualLanHost);
      const st = transportCoordinator.getState();
      persistSettings({
        authUserId: st.accountId,
        authUsername: st.username,
        pairSecretHmac: st.pairSecretHmac,
        supabaseUrl: supabaseService.getConfig().url,
        supabaseAnonKey: supabaseService.getConfig().anonKey
      });
      set({ statusBanner: msg });
      return msg;
    },

    async connectManualLanHost(host, port) {
      const ok = await transportCoordinator.tryLanFirstThenOnlineFallback(host, port);
      set({
        statusBanner: ok
          ? `Connected directly to LAN peer ${host}:${port}`
          : `LAN unreachable at ${host}:${port} — failed over to Supabase Online`
      });
      return ok;
    },

    async setAccountSyncMode(mode) {
      await transportCoordinator.setAccountSyncMode(mode);
    },

    setBrowsingDeviceId(deviceId) {
      transportCoordinator.setBrowsingDeviceId(deviceId);
    },

    async sendRemoteCommand(cmd, targetDeviceId) {
      await transportCoordinator.sendRemoteCommand(cmd, targetDeviceId);
    },

    async transferPlaybackToDevice(targetDeviceId) {
      const res = await transportCoordinator.transferPlaybackToDevice(targetDeviceId);
      set({ statusBanner: res.reason });
    }
  };
});

export function getActiveTracksForStore(store: MusikaStore): Track[] {
  const { browsingDeviceId, localDeviceId, connectedDevices } = store.onlineState;
  if (browsingDeviceId === localDeviceId) {
    return store.rawTracks;
  }
  const peer = connectedDevices.find((d) => d.deviceId === browsingDeviceId);
  if (!peer?.catalogTracks) return [];
  const host = peer.lanHost && peer.lanPort ? `${peer.lanHost}:${peer.lanPort}` : undefined;
  return tracksFromRemoteSummaries(peer.catalogTracks, host);
}
