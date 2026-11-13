import React, { useState } from 'react';
import {
  ArrowDown,
  ArrowUp,
  Disc3,
  Folder,
  ListPlus,
  Lock,
  Pause,
  Play,
  Repeat,
  Save,
  Shuffle,
  SkipBack,
  SkipForward,
  Trash2,
  User,
  X
} from 'lucide-react';
import { formatDuration, formatTelemetrySummary } from '../core/models';
import { dualQueueEngine, useMusikaStore } from '../store/useMusikaStore';
import { formatLoopBadge, formatRandomBadge } from './WinampHudBar';

export const NowPlayingModal: React.FC = () => {
  const {
    showNowPlayingModal,
    setShowNowPlayingModal,
    setShowQueueModal,
    playbackState,
    artworkStyle,
    jumpToAlbum,
    jumpToArtist,
    jumpToFolders
  } = useMusikaStore();

  if (!showNowPlayingModal || !playbackState.currentTrack) return null;
  const track = playbackState.currentTrack;
  const duration = playbackState.durationMs || track.durationMs || 1;
  const isFullBg = artworkStyle === 'FULL_SCREEN_BACKGROUND';

  return (
    <div className="fixed inset-0 z-50 flex flex-col justify-between overflow-y-auto bg-musikaBg text-white">
      {isFullBg && track.artworkUrl && (
        <div
          className="pointer-events-none absolute inset-0 bg-cover bg-center opacity-25 blur-sm"
          style={{ backgroundImage: `url(${track.artworkUrl})` }}
        />
      )}
      <div className="relative z-10 mx-auto flex w-full max-w-2xl flex-1 flex-col justify-between p-5 sm:p-8">
        {/* Header */}
        <div className="flex items-center justify-between">
          <div>
            <p className="font-mono text-xs font-bold uppercase tracking-widest text-winampGreen">
              Now Playing
            </p>
            <p className="text-xs text-slate-400">
              {playbackState.activeContextName || 'Active Queue'}
            </p>
          </div>
          <button
            onClick={() => setShowNowPlayingModal(false)}
            className="rounded-full border border-slate-700 bg-winampBezel p-2 text-slate-200 hover:bg-slate-800"
          >
            <X className="h-5 w-5" />
          </button>
        </div>

        {/* Track Artwork Style Showcase */}
        <div className="my-6 flex flex-col items-center justify-center">
          {artworkStyle === 'SPINNING_DISC' ? (
            <div
              className={`relative flex h-64 w-64 items-center justify-center overflow-hidden rounded-full border-4 border-winampAmber/70 bg-winampLcd shadow-2xl ${
                playbackState.isPlaying ? 'musika-vinyl-spinning' : ''
              }`}
            >
              {track.artworkUrl ? (
                <img src={track.artworkUrl} alt={track.album} className="h-full w-full object-cover" />
              ) : (
                <Disc3 className="h-28 w-28 text-samsungBlue" />
              )}
              <div className="absolute h-10 w-10 rounded-full border-2 border-slate-700 bg-musikaBg" />
            </div>
          ) : (
            <div
              className={`flex h-64 w-64 items-center justify-center overflow-hidden bg-winampBezel shadow-2xl ${
                artworkStyle === 'WINAMP_RETRO_BEZEL'
                  ? 'rounded-sm border-4 border-winampGreen p-2'
                  : 'rounded-2xl border border-slate-700'
              }`}
            >
              {track.artworkUrl ? (
                <img src={track.artworkUrl} alt={track.album} className="h-full w-full object-cover" />
              ) : (
                <Disc3 className="h-24 w-24 text-winampAmber" />
              )}
            </div>
          )}

          {/* Marquee Track Info & Winamp Telemetry Readout */}
          <div className="mt-6 w-full text-center">
            <div className="musika-marquee-wrap">
              <h2
                className={`text-xl font-extrabold text-white sm:text-2xl ${
                  track.title.length > 28 ? 'musika-marquee-active' : 'truncate'
                }`}
              >
                {track.title}
              </h2>
            </div>
            <p className="mt-1 truncate text-sm font-medium text-slate-300">
              {track.artist} — {track.album}
            </p>
            <div className="mt-3 inline-block rounded border border-winampGreen/40 bg-winampLcd px-3 py-1 font-mono text-xs font-bold text-winampGreen">
              {formatTelemetrySummary(track.audioQuality)}
            </div>
          </div>

          {/* 1-2 Tap Cross-Navigation Bar */}
          <div className="mt-4 flex flex-wrap justify-center gap-2">
            <button
              onClick={() => {
                setShowNowPlayingModal(false);
                jumpToAlbum(track.album);
              }}
              className="inline-flex items-center gap-1 rounded-full border border-slate-700 bg-winampBezel px-3 py-1 text-xs font-semibold text-slate-200 hover:border-samsungBlue"
            >
              <Disc3 className="h-3.5 w-3.5 text-samsungBlue" />
              Go to Album
            </button>
            <button
              onClick={() => {
                setShowNowPlayingModal(false);
                jumpToArtist(track.artist);
              }}
              className="inline-flex items-center gap-1 rounded-full border border-slate-700 bg-winampBezel px-3 py-1 text-xs font-semibold text-slate-200 hover:border-samsungBlue"
            >
              <User className="h-3.5 w-3.5 text-winampAmber" />
              Go to Artist
            </button>
            <button
              onClick={() => {
                setShowNowPlayingModal(false);
                jumpToFolders([track.parentFolderPath], track.title);
              }}
              className="inline-flex items-center gap-1 rounded-full border border-slate-700 bg-winampBezel px-3 py-1 text-xs font-semibold text-slate-200 hover:border-samsungBlue"
            >
              <Folder className="h-3.5 w-3.5 text-winampGreen" />
              Go to Folder
            </button>
          </div>
        </div>

        {/* Scrubber & Transport Controls */}
        <div className="space-y-4">
          <div>
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
              className="h-2 w-full cursor-pointer accent-winampGreen"
            />
            <div className="mt-1 flex justify-between font-mono text-xs text-winampGreen">
              <span>{formatDuration(playbackState.positionMs)}</span>
              <span>{formatDuration(duration)}</span>
            </div>
          </div>

          <div className="flex items-center justify-between">
            <button
              onClick={() => dualQueueEngine.cycleRandomMode()}
              className="inline-flex items-center gap-1 rounded border border-winampAmber/50 bg-winampLcd px-2.5 py-1.5 font-mono text-xs font-bold text-winampAmber"
            >
              <Shuffle className="h-3.5 w-3.5" />
              {formatRandomBadge(playbackState.randomMode)}
            </button>

            <div className="flex items-center gap-4">
              <button
                onClick={() => dualQueueEngine.skipToPrevious()}
                className="rounded-full p-3 text-slate-200 hover:bg-slate-800"
              >
                <SkipBack className="h-6 w-6" />
              </button>
              <button
                onClick={() => dualQueueEngine.togglePlayPause()}
                className="flex h-14 w-14 items-center justify-center rounded-full bg-samsungBlue text-white shadow-lg hover:bg-blue-500"
              >
                {playbackState.isPlaying ? <Pause className="h-7 w-7" /> : <Play className="h-7 w-7" />}
              </button>
              <button
                onClick={() => dualQueueEngine.skipToNext(false)}
                className="rounded-full p-3 text-slate-200 hover:bg-slate-800"
              >
                <SkipForward className="h-6 w-6" />
              </button>
            </div>

            <button
              onClick={() => dualQueueEngine.cycleLoopMode()}
              className="inline-flex items-center gap-1 rounded border border-winampGreen/50 bg-winampLcd px-2.5 py-1.5 font-mono text-xs font-bold text-winampGreen"
            >
              <Repeat className="h-3.5 w-3.5" />
              {formatLoopBadge(playbackState.loopMode)}
            </button>
          </div>

          <button
            onClick={() => {
              setShowNowPlayingModal(false);
              setShowQueueModal(true);
            }}
            className="w-full rounded-xl border border-slate-700 bg-winampBezel py-2.5 text-sm font-semibold text-winampAmber hover:bg-slate-800"
          >
            View &amp; Edit Dual Queue / Save as Playlist
          </button>
        </div>
      </div>
    </div>
  );
};

export const DualQueueModal: React.FC = () => {
  const { showQueueModal, setShowQueueModal, playbackState, saveCurrentQueueAsPlaylist } =
    useMusikaStore();
  const [playlistName, setPlaylistName] = useState('');
  const [showSaveForm, setShowSaveForm] = useState(false);

  if (!showQueueModal) return null;
  const modifiable = dualQueueEngine.isContextQueueModifiable();
  const contextQueue = dualQueueEngine.getVisibleContextQueue();

  return (
    <div className="fixed inset-0 z-50 flex items-end justify-center bg-black/75 p-0 sm:items-center sm:p-4">
      <div className="flex max-h-[90vh] w-full max-w-2xl flex-col rounded-t-2xl border border-slate-800 bg-winampBezel sm:rounded-2xl">
        <div className="flex items-center justify-between border-b border-slate-800 px-5 py-4">
          <div>
            <h3 className="text-lg font-bold text-white">Dual Queue Manager</h3>
            <p className="font-mono text-xs text-winampGreen">
              Random: {playbackState.randomMode} • Loop: {playbackState.loopMode}
            </p>
          </div>
          <div className="flex items-center gap-2">
            <button
              onClick={() => setShowSaveForm((s) => !s)}
              className="inline-flex items-center gap-1 rounded-lg bg-samsungBlue px-3 py-1.5 text-xs font-semibold text-white hover:bg-blue-500"
            >
              <Save className="h-3.5 w-3.5" />
              Save as Playlist
            </button>
            <button
              onClick={() => setShowQueueModal(false)}
              className="rounded-lg p-1.5 text-slate-400 hover:bg-slate-800 hover:text-white"
            >
              <X className="h-5 w-5" />
            </button>
          </div>
        </div>

        <div className="space-y-4 overflow-y-auto p-5">
          {showSaveForm && (
            <div className="flex gap-2 rounded-xl border border-samsungBlue/40 bg-musikaBg p-3">
              <input
                type="text"
                placeholder="Playlist Name (e.g. Night Drive FLAC)"
                value={playlistName}
                onChange={(e) => setPlaylistName(e.target.value)}
                className="flex-1 rounded-lg border border-slate-700 bg-winampLcd px-3 py-1.5 text-sm text-white outline-none focus:border-samsungBlue"
              />
              <button
                onClick={() => {
                  if (playlistName.trim()) {
                    void saveCurrentQueueAsPlaylist(playlistName.trim());
                    setPlaylistName('');
                    setShowSaveForm(false);
                  }
                }}
                className="rounded-lg bg-winampGreen px-4 py-1.5 text-xs font-bold text-black hover:opacity-90"
              >
                Save
              </button>
            </div>
          )}

          {/* Priority Queue */}
          <div>
            <div className="mb-2 flex items-center justify-between">
              <h4 className="text-xs font-bold uppercase tracking-wider text-winampAmber">
                Play Next — FIFO Priority Queue ({playbackState.priorityQueue.length})
              </h4>
              {playbackState.priorityQueue.length > 0 && (
                <button
                  onClick={() => dualQueueEngine.clearPriorityQueue()}
                  className="text-xs text-rose-400 hover:underline"
                >
                  Clear Priority
                </button>
              )}
            </div>
            {playbackState.priorityQueue.length === 0 ? (
              <p className="rounded-lg bg-musikaBg/60 p-3 text-xs text-slate-400">
                No &ldquo;Play Next&rdquo; tracks queued yet. Priority tracks always play first even in True Random.
              </p>
            ) : (
              <div className="space-y-1.5">
                {playbackState.priorityQueue.map((track, idx) => (
                  <div
                    key={`${track.filePath}-${idx}`}
                    className="flex items-center justify-between gap-2 rounded-lg border border-winampAmber/30 bg-musikaBg px-3 py-2"
                  >
                    <div className="min-w-0 flex-1">
                      <p className="truncate text-sm font-semibold text-white">
                        {idx + 1}. {track.title}
                      </p>
                      <p className="truncate text-xs text-slate-400">{track.artist}</p>
                    </div>
                    <div className="flex items-center gap-1">
                      <button
                        disabled={idx === 0}
                        onClick={() => dualQueueEngine.movePriorityQueueTrack(idx, idx - 1)}
                        className="rounded p-1 text-slate-300 hover:bg-slate-800 disabled:opacity-30"
                      >
                        <ArrowUp className="h-4 w-4" />
                      </button>
                      <button
                        disabled={idx === playbackState.priorityQueue.length - 1}
                        onClick={() => dualQueueEngine.movePriorityQueueTrack(idx, idx + 1)}
                        className="rounded p-1 text-slate-300 hover:bg-slate-800 disabled:opacity-30"
                      >
                        <ArrowDown className="h-4 w-4" />
                      </button>
                      <button
                        onClick={() => dualQueueEngine.removeFromPriorityQueue(idx)}
                        className="rounded p-1 text-rose-400 hover:bg-slate-800"
                      >
                        <Trash2 className="h-4 w-4" />
                      </button>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>

          {/* Standard Context Queue */}
          <div>
            <div className="mb-2 flex items-center justify-between">
              <h4 className="text-xs font-bold uppercase tracking-wider text-samsungBlue">
                Standard Context Queue ({modifiable ? contextQueue.length : 'Locked in True Random'})
              </h4>
              {modifiable && contextQueue.length > 0 && (
                <button
                  onClick={() => dualQueueEngine.clearContextQueue()}
                  className="text-xs text-rose-400 hover:underline"
                >
                  Clear Queue
                </button>
              )}
            </div>

            {!modifiable ? (
              <div className="flex items-center gap-2 rounded-xl border border-slate-700 bg-musikaBg p-4 text-xs text-slate-300">
                <Lock className="h-4 w-4 shrink-0 text-winampAmber" />
                <span>
                  Context queue inspection and reordering are disabled during <strong>TRUE RANDOM</strong> (memoryless mode). Switch to OFF or SHUFFLE POOL to inspect and edit.
                </span>
              </div>
            ) : contextQueue.length === 0 ? (
              <p className="rounded-lg bg-musikaBg/60 p-3 text-xs text-slate-400">
                Standard queue is empty.
              </p>
            ) : (
              <div className="space-y-1.5">
                {contextQueue.map((track, idx) => {
                  const isCurrent = playbackState.currentTrack?.filePath === track.filePath;
                  return (
                    <div
                      key={`${track.filePath}-${idx}`}
                      className={`flex items-center justify-between gap-2 rounded-lg border px-3 py-2 ${
                        isCurrent
                          ? 'border-winampGreen bg-samsungBlue/15'
                          : 'border-slate-800 bg-musikaBg'
                      }`}
                    >
                      <button
                        onClick={() =>
                          dualQueueEngine.playFromContext(
                            track,
                            playbackState.standardQueue,
                            playbackState.activeContextName
                          )
                        }
                        className="min-w-0 flex-1 text-left"
                      >
                        <p
                          className={`truncate text-sm font-semibold ${
                            isCurrent ? 'text-winampGreen' : 'text-white'
                          }`}
                        >
                          {idx + 1}. {track.title}
                        </p>
                        <p className="truncate text-xs text-slate-400">
                          {track.artist} • {track.audioQuality.format}
                        </p>
                      </button>
                      <div className="flex items-center gap-1">
                        <button
                          disabled={idx === 0}
                          onClick={() => dualQueueEngine.moveContextQueueTrack(idx, idx - 1)}
                          className="rounded p-1 text-slate-300 hover:bg-slate-800 disabled:opacity-30"
                        >
                          <ArrowUp className="h-4 w-4" />
                        </button>
                        <button
                          disabled={idx === contextQueue.length - 1}
                          onClick={() => dualQueueEngine.moveContextQueueTrack(idx, idx + 1)}
                          className="rounded p-1 text-slate-300 hover:bg-slate-800 disabled:opacity-30"
                        >
                          <ArrowDown className="h-4 w-4" />
                        </button>
                        <button
                          onClick={() => dualQueueEngine.removeFromContextQueue(idx)}
                          className="rounded p-1 text-rose-400 hover:bg-slate-800"
                        >
                          <Trash2 className="h-4 w-4" />
                        </button>
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
};

export const HelperModals: React.FC = () => {
  const {
    variantPickerGroup,
    setVariantPickerGroup,
    selectFormatVariant,
    multiFolderSheet,
    toggleMultiFolderExpanded,
    dismissMultiFolderSheet,
    openFolder,
    addToPlaylistCandidates,
    setAddToPlaylistCandidates,
    playlists,
    addTracksToPlaylist,
    createPlaylist
  } = useMusikaStore();

  const [newPlName, setNewPlName] = useState('');

  return (
    <>
      {variantPickerGroup && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/75 p-4">
          <div className="w-full max-w-md rounded-2xl border border-slate-700 bg-winampBezel p-5">
            <h3 className="text-base font-bold text-white">
              Select Format Variant — {variantPickerGroup.primaryTrack.title}
            </h3>
            <p className="mt-1 text-xs text-slate-400">
              Highest fidelity is auto-selected by default. Pick any local format variant below:
            </p>
            <div className="mt-4 space-y-2">
              {variantPickerGroup.allVariants.map((variant) => (
                <button
                  key={variant.filePath}
                  onClick={() => selectFormatVariant(variantPickerGroup, variant)}
                  className="flex w-full items-center justify-between rounded-xl border border-slate-700 bg-musikaBg p-3 text-left hover:border-samsungBlue"
                >
                  <div className="min-w-0 flex-1">
                    <p className="font-mono text-xs font-bold text-winampGreen">
                      {variant.audioQuality.format} • {variant.audioQuality.bitrateKbps} kbps
                    </p>
                    <p className="truncate text-[11px] text-slate-400">{variant.relativePath}</p>
                  </div>
                  <Play className="ml-2 h-4 w-4 shrink-0 text-samsungBlue" />
                </button>
              ))}
            </div>
            <button
              onClick={() => setVariantPickerGroup(null)}
              className="mt-4 w-full rounded-xl bg-slate-800 py-2 text-xs font-semibold text-white hover:bg-slate-700"
            >
              Close
            </button>
          </div>
        </div>
      )}

      {multiFolderSheet && (
        <div className="fixed inset-0 z-50 flex items-end justify-center bg-black/75 p-0 sm:items-center sm:p-4">
          <div className="w-full max-w-lg rounded-t-2xl border border-slate-700 bg-winampBezel p-5 sm:rounded-2xl">
            <div className="flex items-center justify-between">
              <h3 className="text-base font-bold text-white">
                Folders for {multiFolderSheet.sourceTitle}
              </h3>
              <button
                onClick={toggleMultiFolderExpanded}
                className="rounded-lg border border-slate-700 px-2.5 py-1 text-xs font-semibold text-samsungBlue hover:bg-slate-800"
              >
                {multiFolderSheet.expanded ? 'Compact View' : 'Expanded Paths'}
              </button>
            </div>
            <div className="mt-3 max-h-72 space-y-2 overflow-y-auto">
              {multiFolderSheet.folderPaths.map((path) => (
                <button
                  key={path}
                  onClick={() => openFolder(path)}
                  className="flex w-full items-center gap-2.5 rounded-xl border border-slate-800 bg-musikaBg px-3 py-2.5 text-left hover:border-samsungBlue"
                >
                  <Folder className="h-4 w-4 shrink-0 text-winampAmber" />
                  <span className="truncate font-mono text-xs text-slate-200">
                    {multiFolderSheet.expanded ? path : path.split('/').pop() || path}
                  </span>
                </button>
              ))}
            </div>
            <button
              onClick={dismissMultiFolderSheet}
              className="mt-4 w-full rounded-xl bg-slate-800 py-2 text-xs font-semibold text-white hover:bg-slate-700"
            >
              Close
            </button>
          </div>
        </div>
      )}

      {addToPlaylistCandidates && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/75 p-4">
          <div className="w-full max-w-md rounded-2xl border border-slate-700 bg-winampBezel p-5">
            <div className="flex items-center justify-between">
              <h3 className="flex items-center gap-2 text-base font-bold text-white">
                <ListPlus className="h-5 w-5 text-samsungBlue" />
                Add {addToPlaylistCandidates.length} Track(s) to Playlist
              </h3>
              <button
                onClick={() => setAddToPlaylistCandidates(null)}
                className="rounded p-1 text-slate-400 hover:bg-slate-800"
              >
                <X className="h-4 w-4" />
              </button>
            </div>
            <div className="mt-3 flex gap-2">
              <input
                type="text"
                placeholder="New playlist name..."
                value={newPlName}
                onChange={(e) => setNewPlName(e.target.value)}
                className="flex-1 rounded-lg border border-slate-700 bg-musikaBg px-3 py-1.5 text-sm text-white outline-none focus:border-samsungBlue"
              />
              <button
                onClick={() => {
                  if (newPlName.trim()) {
                    void createPlaylist(newPlName.trim(), addToPlaylistCandidates);
                    setNewPlName('');
                  }
                }}
                className="rounded-lg bg-samsungBlue px-3 py-1.5 text-xs font-semibold text-white"
              >
                Create
              </button>
            </div>
            <div className="mt-4 max-h-56 space-y-2 overflow-y-auto">
              {playlists.map((pl) => (
                <button
                  key={pl.id}
                  onClick={() => void addTracksToPlaylist(pl.id, addToPlaylistCandidates)}
                  className="flex w-full items-center justify-between rounded-xl border border-slate-800 bg-musikaBg px-3 py-2 text-left hover:border-samsungBlue"
                >
                  <span className="truncate text-sm font-semibold text-white">{pl.name}</span>
                  <span className="font-mono text-xs text-winampGreen">{pl.items.length} tracks</span>
                </button>
              ))}
            </div>
          </div>
        </div>
      )}
    </>
  );
};
