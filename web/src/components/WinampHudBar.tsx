import React from 'react';
import {
  Disc3,
  Laptop,
  ListMusic,
  Pause,
  Play,
  RefreshCw,
  Settings,
  Shuffle,
  SkipBack,
  SkipForward,
  Wifi,
  WifiOff,
  Repeat,
  Globe
} from 'lucide-react';
import { formatDuration, formatTelemetrySummary, LoopMode, RandomMode } from '../core/models';
import { dualQueueEngine, MusikaTab, useMusikaStore } from '../store/useMusikaStore';

export function formatRandomBadge(mode: RandomMode): string {
  if (mode === 'OFF') return 'RND:OFF';
  if (mode === 'SHUFFLE_POOL') return 'RND:POOL';
  return 'RND:TRUE';
}

export function formatLoopBadge(mode: LoopMode): string {
  if (mode === 'NO_LOOP') return 'LOOP:OFF';
  if (mode === 'QUEUE_LOOP') return 'LOOP:ALL';
  return 'LOOP:1';
}

const TABS: Array<{ id: MusikaTab; label: string }> = [
  { id: 'SONGS', label: 'Songs' },
  { id: 'ALBUMS', label: 'Albums' },
  { id: 'ARTISTS', label: 'Artists' },
  { id: 'PLAYLISTS', label: 'Playlists' },
  { id: 'FOLDERS', label: 'Folders' }
];

export const TopNavigationBar: React.FC = () => {
  const {
    selectedTab,
    setSelectedTab,
    playbackState,
    onlineState,
    isScanning,
    scanCount,
    statusBanner,
    setStatusBanner,
    rescanCurrentRoot,
    setShowNowPlayingModal,
    setShowConnectModal,
    setShowSettingsModal,
    setBrowsingDeviceId
  } = useMusikaStore();

  const transportBadge =
    onlineState.transportMode === 'LAN_DIRECT'
      ? { label: 'LAN', Icon: Wifi, cls: 'text-winampGreen border-winampGreen/50 bg-winampGreen/10' }
      : onlineState.transportMode === 'ONLINE_SUPABASE'
        ? { label: 'ONLINE', Icon: Globe, cls: 'text-samsungBlue border-samsungBlue/50 bg-samsungBlue/10' }
        : { label: 'LOCAL', Icon: WifiOff, cls: 'text-winampAmber border-winampAmber/40 bg-winampAmber/10' };

  const browsingRemote = onlineState.browsingDeviceId !== onlineState.localDeviceId;
  const activeBrowsePeer = onlineState.connectedDevices.find(
    (d) => d.deviceId === onlineState.browsingDeviceId
  );

  return (
    <header className="sticky top-0 z-30 border-b border-slate-800 bg-winampBezel/95 backdrop-blur">
      <div className="mx-auto flex max-w-6xl items-center justify-between gap-2 px-3 py-2.5 sm:px-4">
        <div className="flex min-w-0 items-center gap-2.5">
          <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg border border-winampGreen/50 bg-winampLcd text-winampGreen shadow-inner">
            <Disc3 className={`h-5 w-5 ${playbackState.isPlaying ? 'musika-vinyl-spinning' : ''}`} />
          </div>
          <div className="min-w-0">
            <div className="flex items-center gap-2">
              <span className="font-mono text-base font-extrabold tracking-widest text-white">
                MUSIKA
              </span>
              <span
                className={`inline-flex items-center gap-1 rounded border px-1.5 py-0.5 font-mono text-[10px] font-bold ${transportBadge.cls}`}
              >
                <transportBadge.Icon className="h-3 w-3" />
                {transportBadge.label}
              </span>
            </div>
            <p className="truncate text-[10px] text-slate-400">
              Samsung Music + Winamp Hybrid
            </p>
          </div>
        </div>

        <div className="flex shrink-0 items-center gap-1.5 sm:gap-2">
          {playbackState.currentTrack && (
            <button
              onClick={() => setShowNowPlayingModal(true)}
              className="hidden items-center gap-1.5 rounded-full border border-samsungBlue bg-samsungBlue/20 px-2.5 py-1 text-xs font-semibold text-white hover:bg-samsungBlue/30 sm:inline-flex"
            >
              <span className="h-2 w-2 animate-pulse rounded-full bg-winampGreen" />
              Playing
            </button>
          )}

          <button
            onClick={() => dualQueueEngine.cycleRandomMode()}
            title="Cycle 3-state Random Mode"
            className="inline-flex items-center gap-1 rounded border border-winampAmber/50 bg-winampLcd px-2 py-1 font-mono text-[10px] font-bold text-winampAmber hover:bg-slate-800"
          >
            <Shuffle className="h-3 w-3" />
            <span>{formatRandomBadge(playbackState.randomMode)}</span>
          </button>

          <button
            onClick={() => dualQueueEngine.cycleLoopMode()}
            title="Cycle 3-state Loop Mode"
            className="inline-flex items-center gap-1 rounded border border-winampGreen/50 bg-winampLcd px-2 py-1 font-mono text-[10px] font-bold text-winampGreen hover:bg-slate-800"
          >
            <Repeat className="h-3 w-3" />
            <span>{formatLoopBadge(playbackState.loopMode)}</span>
          </button>

          <button
            onClick={() => setShowConnectModal(true)}
            title="Online & LAN Multi-Device Control / QR Pair"
            className="inline-flex items-center gap-1 rounded-lg border border-winampGreen/40 bg-winampLcd px-2.5 py-1.5 text-xs font-semibold text-winampGreen hover:bg-slate-800"
          >
            <Laptop className="h-4 w-4" />
            <span className="hidden md:inline">Devices</span>
          </button>

          <button
            onClick={() => void rescanCurrentRoot()}
            title="Rescan Musical Root Folder"
            className="rounded-lg p-1.5 text-slate-300 hover:bg-slate-800 hover:text-white"
          >
            <RefreshCw className={`h-4 w-4 ${isScanning ? 'animate-spin text-samsungBlue' : ''}`} />
          </button>

          <button
            onClick={() => setShowSettingsModal(true)}
            title="Settings"
            className="rounded-lg p-1.5 text-slate-300 hover:bg-slate-800 hover:text-white"
          >
            <Settings className="h-4 w-4" />
          </button>
        </div>
      </div>

      {isScanning && (
        <div className="border-t border-slate-800 bg-samsungBlue/15 px-4 py-1 text-xs text-samsungBlue">
          Scanning audio metadata &amp; folder tree... ({scanCount} parsed)
        </div>
      )}

      {browsingRemote && (
        <div className="flex items-center justify-between border-t border-amber-500/30 bg-amber-500/15 px-4 py-1.5 text-xs text-amber-200">
          <span>
            Remote Browsing: <strong>{activeBrowsePeer?.deviceName || onlineState.browsingDeviceId}</strong>
          </span>
          <button
            onClick={() => setBrowsingDeviceId(onlineState.localDeviceId)}
            className="rounded bg-slate-900/80 px-2 py-0.5 text-[11px] font-semibold text-white hover:bg-slate-800"
          >
            Switch Back to This Device
          </button>
        </div>
      )}

      {statusBanner && (
        <div className="flex items-center justify-between border-t border-slate-800 bg-samsungBlue/20 px-4 py-1 text-xs text-slate-100">
          <span className="truncate">{statusBanner}</span>
          <button
            onClick={() => setStatusBanner(null)}
            className="ml-3 shrink-0 font-semibold text-winampAmber hover:underline"
          >
            Dismiss
          </button>
        </div>
      )}

      {/* Horizontal scrollable top tabs (Samsung Music style) */}
      <nav className="mx-auto flex max-w-6xl overflow-x-auto px-2 sm:px-4">
        {TABS.map((tab) => {
          const active = selectedTab === tab.id;
          return (
            <button
              key={tab.id}
              onClick={() => setSelectedTab(tab.id)}
              className={`shrink-0 border-b-2 px-4 py-2.5 text-sm transition-colors ${
                active
                  ? 'border-samsungBlue font-bold text-white'
                  : 'border-transparent font-medium text-slate-400 hover:text-slate-200'
              }`}
            >
              {tab.label}
            </button>
          );
        })}
      </nav>
    </header>
  );
};

export const WinampMiniPlayerBar: React.FC = () => {
  const { playbackState, onlineState, setShowNowPlayingModal, setShowQueueModal } = useMusikaStore();
  const track = playbackState.currentTrack;
  const duration = playbackState.durationMs || track?.durationMs || 1;
  const pct = Math.min(100, Math.max(0, (playbackState.positionMs / duration) * 100));

  return (
    <footer className="sticky bottom-0 z-30 border-t border-slate-800 bg-winampLcd/95 backdrop-blur">
      <div className="mx-auto max-w-6xl px-3 py-2 sm:px-4">
        {/* Progress bar */}
        <div className="mb-1.5 flex items-center gap-2">
          <span className="font-mono text-[10px] text-winampGreen">
            {formatDuration(playbackState.positionMs)}
          </span>
          <input
            type="range"
            min={0}
            max={duration}
            value={Math.min(playbackState.positionMs, duration)}
            onChange={(e) => {
              const ms = Number(e.target.value);
              dualQueueEngine.seekTo(ms);
              void useMusikaStore.getState().sendRemoteCommand({ type: 'SeekTo', positionMs: ms });
            }}
            className="h-1.5 flex-1 cursor-pointer accent-winampGreen"
            style={{
              background: `linear-gradient(to right, #00FF66 ${pct}%, #272E3A ${pct}%)`
            }}
          />
          <span className="font-mono text-[10px] text-slate-400">
            {formatDuration(duration)}
          </span>
        </div>

        <div className="flex items-center justify-between gap-3">
          <button
            onClick={() => track && setShowNowPlayingModal(true)}
            className="flex min-w-0 flex-1 items-center gap-3 text-left"
          >
            <div className="flex h-11 w-11 shrink-0 items-center justify-center overflow-hidden rounded-md border border-slate-700 bg-winampBezel">
              {track?.artworkUrl ? (
                <img src={track.artworkUrl} alt={track.title} className="h-full w-full object-cover" />
              ) : (
                <Disc3 className={`h-6 w-6 text-winampGreen ${playbackState.isPlaying ? 'musika-vinyl-spinning' : ''}`} />
              )}
            </div>
            <div className="min-w-0 flex-1">
              <div className="musika-marquee-wrap">
                <p
                  className={`text-sm font-bold text-white ${
                    (track?.title.length ?? 0) > 26 ? 'musika-marquee-active' : 'truncate'
                  }`}
                >
                  {track ? track.title : 'Tap any track to play — Dual Queue Ready'}
                </p>
              </div>
              <p className="truncate font-mono text-[11px] text-winampGreen">
                {track
                  ? `${track.artist} • ${formatTelemetrySummary(track.audioQuality)}`
                  : `${onlineState.transportMode} • Priority FIFO + Standard Queue`}
              </p>
            </div>
          </button>

          <div className="flex shrink-0 items-center gap-1">
            <button
              onClick={() => {
                dualQueueEngine.skipToPrevious();
                void useMusikaStore.getState().sendRemoteCommand({ type: 'SkipPrevious' });
              }}
              className="rounded-lg p-2 text-slate-200 hover:bg-slate-800"
              title="Previous"
            >
              <SkipBack className="h-4 w-4" />
            </button>
            <button
              onClick={() => {
                dualQueueEngine.togglePlayPause();
                void useMusikaStore.getState().sendRemoteCommand({ type: 'TogglePlayPause' });
              }}
              className="flex h-9 w-9 items-center justify-center rounded-full bg-samsungBlue text-white hover:bg-blue-500"
              title="Play / Pause"
            >
              {playbackState.isPlaying ? <Pause className="h-4 w-4" /> : <Play className="h-4 w-4" />}
            </button>
            <button
              onClick={() => {
                dualQueueEngine.skipToNext(false);
                void useMusikaStore.getState().sendRemoteCommand({ type: 'SkipNext' });
              }}
              className="rounded-lg p-2 text-slate-200 hover:bg-slate-800"
              title="Next"
            >
              <SkipForward className="h-4 w-4" />
            </button>
            <button
              onClick={() => setShowQueueModal(true)}
              className="relative ml-1 rounded-lg border border-slate-700 bg-winampBezel p-2 text-winampAmber hover:bg-slate-800"
              title="Open Queue Editor"
            >
              <ListMusic className="h-4 w-4" />
              {playbackState.priorityQueue.length > 0 && (
                <span className="absolute -right-1 -top-1 flex h-4 min-w-[1rem] items-center justify-center rounded-full bg-winampGreen px-1 text-[9px] font-bold text-black">
                  {playbackState.priorityQueue.length}
                </span>
              )}
            </button>
          </div>
        </div>
      </div>
    </footer>
  );
};
