import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  ArrowLeft,
  ArrowUp,
  ChevronsUp,
  Disc3,
  Edit3,
  Folder,
  FolderOpen,
  FolderUp,
  Layers,
  ListPlus,
  Music2,
  Play,
  Plus,
  Search,
  Trash2,
  User
} from 'lucide-react';
import {
  buildAlbums,
  buildArtists,
  buildFolderTree,
  deduplicateTracks,
  isDiscFolder,
  rawTracksForDirectoryView,
  sortFolders,
  sortSongs
} from '../core/deduplicator';
import {
  AlbumSortOption,
  ArtistSortOption,
  DeduplicatedTrackGroup,
  FolderSortOption,
  formatDuration,
  formatTelemetrySummary,
  getNormalizedTrackKey,
  SongSortOption,
  Track
} from '../core/models';
import {
  dualQueueEngine,
  getActiveTracksForStore,
  useMusikaStore
} from '../store/useMusikaStore';

const ALPHABET = ['#', ...'ABCDEFGHIJKLMNOPQRSTUVWXYZ'.split('')];

function firstSortLetter(text: string): string {
  const trimmed = text.trim();
  if (!trimmed) return '#';
  const ch = trimmed[0].toUpperCase();
  return ch >= 'A' && ch <= 'Z' ? ch : '#';
}

interface FastAlphabetRailProps {
  keysForItems: string[];
  onJumpToIndex: (index: number) => void;
}

const FastAlphabetRail: React.FC<FastAlphabetRailProps> = ({ keysForItems, onJumpToIndex }) => {
  const indexByLetter = useMemo(() => {
    const map = new Map<string, number>();
    keysForItems.forEach((key, idx) => {
      const letter = firstSortLetter(key);
      if (!map.has(letter)) {
        map.set(letter, idx);
      }
    });
    return map;
  }, [keysForItems]);

  if (keysForItems.length < 6) return null;

  return (
    <div className="flex w-6 shrink-0 select-none flex-col items-center justify-center border-l border-slate-800/80 bg-slate-950/60 py-1 text-[9px] font-bold">
      {ALPHABET.map((letter) => {
        const target = indexByLetter.get(letter);
        const active = target !== undefined;
        return (
          <button
            key={letter}
            disabled={!active}
            onClick={() => active && onJumpToIndex(target)}
            className={`w-full py-[1px] text-center leading-tight transition ${
              active ? 'text-samsungBlue hover:text-winampGreen' : 'text-slate-700'
            }`}
          >
            {letter}
          </button>
        );
      })}
    </div>
  );
};

interface TrackRowProps {
  track: Track;
  group?: DeduplicatedTrackGroup;
  showThumbnails: boolean;
  onPlay: () => void;
}

const TrackRow: React.FC<TrackRowProps> = ({ track, group, showThumbnails, onPlay }) => {
  const {
    jumpToAlbum,
    jumpToArtist,
    jumpToFolders,
    setVariantPickerGroup,
    setAddToPlaylistCandidates,
    setStatusBanner,
    playbackState
  } = useMusikaStore();

  const isPlayingThis = playbackState.currentTrack?.filePath === track.filePath;
  const altCount = group ? group.allVariants.length - 1 : track.alternateVariantsCount ?? 0;

  return (
    <div
      className={`group flex items-center gap-2.5 rounded-xl border px-2.5 py-2 transition ${
        isPlayingThis
          ? 'border-samsungBlue bg-samsungBlue/15'
          : 'border-slate-800/80 bg-surfaceCard/70 hover:border-slate-700 hover:bg-surfaceCard'
      }`}
    >
      <button onClick={onPlay} className="flex min-w-0 flex-1 items-center gap-2.5 text-left">
        {showThumbnails && (
          <div className="flex h-10 w-10 shrink-0 items-center justify-center overflow-hidden rounded-lg border border-slate-700 bg-slate-900">
            {track.artworkUrl ? (
              <img src={track.artworkUrl} alt="" className="h-full w-full object-cover" />
            ) : (
              <Music2 className={`h-4 w-4 ${isPlayingThis ? 'text-winampGreen' : 'text-slate-500'}`} />
            )}
          </div>
        )}
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-1.5">
            <span
              className={`truncate text-sm font-semibold ${
                isPlayingThis ? 'text-winampGreen' : 'text-white'
              }`}
            >
              {track.title}
            </span>
            {track.discNumber > 1 && (
              <span className="shrink-0 rounded bg-slate-800 px-1.5 py-0.5 font-mono text-[9px] text-winampAmber">
                D{track.discNumber}
              </span>
            )}
          </div>
          <div className="truncate text-xs text-slate-400">
            {track.artist} • {track.album}
          </div>
          <div className="truncate font-mono text-[10px] text-winampGreen/90">
            {formatTelemetrySummary(track.audioQuality)} • {formatDuration(track.durationMs)}
          </div>
        </div>
      </button>

      <div className="flex shrink-0 items-center gap-1">
        {altCount > 0 && group && (
          <button
            onClick={() => setVariantPickerGroup(group)}
            className="rounded-md border border-winampAmber/40 bg-winampAmber/10 px-1.5 py-0.5 font-mono text-[10px] font-bold text-winampAmber hover:bg-winampAmber/20"
            title="Choose format version (FLAC / OPUS / MP3)"
          >
            +{altCount} fmt
          </button>
        )}
        <button
          onClick={() => {
            dualQueueEngine.enqueueImmediate(track);
            setStatusBanner(`Queued next: ${track.title}`);
          }}
          className="rounded-lg p-1.5 text-slate-400 hover:bg-slate-800 hover:text-winampGreen"
          title="Play Next (Priority Queue)"
        >
          <ListPlus className="h-4 w-4" />
        </button>
        <button
          onClick={() => setAddToPlaylistCandidates([track])}
          className="rounded-lg p-1.5 text-slate-400 hover:bg-slate-800 hover:text-white"
          title="Add to Playlist"
        >
          <Plus className="h-4 w-4" />
        </button>
        <div className="hidden items-center gap-0.5 sm:flex">
          <button
            onClick={() => jumpToAlbum(track.album)}
            className="rounded p-1.5 text-slate-400 hover:bg-slate-800 hover:text-samsungBlue"
            title="Go to Album"
          >
            <Disc3 className="h-3.5 w-3.5" />
          </button>
          <button
            onClick={() => jumpToArtist(track.artist)}
            className="rounded p-1.5 text-slate-400 hover:bg-slate-800 hover:text-samsungBlue"
            title="Go to Artist"
          >
            <User className="h-3.5 w-3.5" />
          </button>
          <button
            onClick={() => jumpToFolders([track.parentFolderPath], track.title)}
            className="rounded p-1.5 text-slate-400 hover:bg-slate-800 hover:text-winampAmber"
            title="Go to Folder"
          >
            <Folder className="h-3.5 w-3.5" />
          </button>
        </div>
      </div>
    </div>
  );
};

export const LibraryViews: React.FC = () => {
  const store = useMusikaStore();
  const {
    selectedTab,
    rootPath,
    songSort,
    setSongSort,
    albumSort,
    setAlbumSort,
    artistSort,
    setArtistSort,
    folderSort,
    setFolderSort,
    showListThumbnails,
    selectedAlbumTitle,
    closeAlbumDetail,
    jumpToAlbum,
    selectedArtistName,
    selectedArtistAlbumFilter,
    selectArtistAlbumFilter,
    closeArtistDetail,
    jumpToArtist,
    selectedPlaylistId,
    selectPlaylist,
    playlists,
    currentFolderPath,
    openFolder,
    navigateUpFolder,
    jumpToSectionRoot,
    jumpToFolders,
    scrollMemory,
    saveScrollTop,
    playTrackInContext,
    playAllDiscsForAlbum,
    playAllDiscsInFolder,
    pickAndScanFolder,
    setShowConnectModal,
    setAddToPlaylistCandidates,
    createPlaylist,
    renamePlaylist,
    deletePlaylist,
    removeTrackFromPlaylist,
    moveTrackInPlaylist
  } = store;

  const [searchQuery, setSearchQuery] = useState('');
  const [newPlaylistName, setNewPlaylistName] = useState('');
  const [renamingPlaylistId, setRenamingPlaylistId] = useState<string | null>(null);
  const [renameText, setRenameText] = useState('');
  const listContainerRef = useRef<HTMLDivElement | null>(null);
  const itemRefs = useRef<Array<HTMLDivElement | null>>([]);

  const activeTracks = useMemo(() => getActiveTracksForStore(store), [store]);
  const dedupedGroups = useMemo(() => deduplicateTracks(activeTracks), [activeTracks]);

  const groupByPrimaryPath = useMemo(() => {
    const map = new Map<string, DeduplicatedTrackGroup>();
    for (const g of dedupedGroups) {
      map.set(g.primaryTrack.filePath, g);
    }
    return map;
  }, [dedupedGroups]);

  const scrollKey = useMemo(() => {
    if (selectedAlbumTitle) return `ALBUM:${selectedAlbumTitle}`;
    if (selectedArtistName) return `ARTIST:${selectedArtistName}:${selectedArtistAlbumFilter ?? 'ALL'}`;
    if (selectedPlaylistId) return `PLAYLIST:${selectedPlaylistId}`;
    if (selectedTab === 'FOLDERS') return `FOLDER:${currentFolderPath || rootPath}`;
    return `TAB:${selectedTab}`;
  }, [
    selectedAlbumTitle,
    selectedArtistName,
    selectedArtistAlbumFilter,
    selectedPlaylistId,
    selectedTab,
    currentFolderPath,
    rootPath
  ]);

  useEffect(() => {
    const el = listContainerRef.current;
    if (!el) return;
    el.scrollTop = scrollMemory[scrollKey] ?? 0;
  }, [scrollKey]);

  const handleScroll = () => {
    if (listContainerRef.current) {
      saveScrollTop(scrollKey, listContainerRef.current.scrollTop);
    }
  };

  const scrollToItemIndex = (idx: number) => {
    const target = itemRefs.current[idx];
    if (target) {
      target.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
  };

  const query = searchQuery.trim().toLowerCase();

  // ---------------- ALBUM DETAIL VIEW ----------------
  if (selectedAlbumTitle) {
    const albumTracks = dedupedGroups
      .map((g) => g.primaryTrack)
      .filter((t) => t.album.toLowerCase() === selectedAlbumTitle.toLowerCase())
      .sort((a, b) => a.discNumber - b.discNumber || a.trackNumber - b.trackNumber);
    const mainArtist = albumTracks[0]?.artist ?? 'Unknown Artist';
    const folderSet = Array.from(new Set(albumTracks.map((t) => t.parentFolderPath)));
    const discNumbers = Array.from(new Set(albumTracks.map((t) => t.discNumber))).sort((a, b) => a - b);

    // Artist's other albums for the persistent horizontal index bar when artist has > 1 album
    const artistAlbums = buildAlbums(
      activeTracks.filter((t) => t.artist.toLowerCase() === mainArtist.toLowerCase()),
      'ALPHABETICAL'
    );

    return (
      <div className="flex min-h-0 flex-1 flex-col">
        <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-800 bg-surfaceCard/80 px-3 py-2.5">
          <div className="flex items-center gap-2 min-w-0">
            <button
              onClick={closeAlbumDetail}
              className="rounded-lg border border-slate-700 p-1.5 text-slate-200 hover:bg-slate-800"
            >
              <ArrowLeft className="h-4 w-4" />
            </button>
            <div className="min-w-0">
              <div className="truncate text-sm font-bold text-white">{selectedAlbumTitle}</div>
              <button
                onClick={() => jumpToArtist(mainArtist)}
                className="text-xs text-samsungBlue hover:underline"
              >
                {mainArtist} • {albumTracks.length} tracks
              </button>
            </div>
          </div>
          <div className="flex flex-wrap items-center gap-1.5">
            {discNumbers.length > 1 && (
              <button
                onClick={() => playAllDiscsForAlbum(selectedAlbumTitle)}
                className="flex items-center gap-1 rounded-lg bg-winampAmber/20 border border-winampAmber/50 px-2.5 py-1 text-xs font-semibold text-winampAmber hover:bg-winampAmber/30"
              >
                <Layers className="h-3.5 w-3.5" />
                Play All {discNumbers.length} Discs
              </button>
            )}
            <button
              onClick={() =>
                albumTracks[0] && playTrackInContext(albumTracks[0], albumTracks, selectedAlbumTitle)
              }
              className="flex items-center gap-1 rounded-lg bg-samsungBlue px-2.5 py-1 text-xs font-semibold text-white hover:bg-blue-500"
            >
              <Play className="h-3.5 w-3.5" />
              Play Album
            </button>
            <button
              onClick={() => jumpToFolders(folderSet, selectedAlbumTitle)}
              className="flex items-center gap-1 rounded-lg border border-slate-700 bg-slate-900 px-2.5 py-1 text-xs text-slate-200 hover:bg-slate-800"
            >
              <Folder className="h-3.5 w-3.5 text-winampAmber" />
              Go to Folder
            </button>
          </div>
        </div>

        {/* Horizontal Album Index Bar for multi-album artists */}
        {artistAlbums.length > 1 && (
          <div className="flex items-center gap-1.5 overflow-x-auto border-b border-slate-800 bg-slate-950/90 px-3 py-2">
            <span className="shrink-0 text-[10px] font-bold uppercase tracking-wider text-slate-400">
              {mainArtist} Albums:
            </span>
            {artistAlbums.map((alb) => (
              <button
                key={alb.title}
                onClick={() => jumpToAlbum(alb.title)}
                className={`shrink-0 rounded-full border px-2.5 py-1 text-xs font-medium ${
                  alb.title.toLowerCase() === selectedAlbumTitle.toLowerCase()
                    ? 'border-samsungBlue bg-samsungBlue text-white'
                    : 'border-slate-700 bg-slate-900 text-slate-300 hover:border-slate-500'
                }`}
              >
                {alb.title}
              </button>
            ))}
          </div>
        )}

        <div ref={listContainerRef} onScroll={handleScroll} className="flex-1 space-y-1.5 overflow-y-auto p-3">
          {albumTracks.map((track) => (
            <TrackRow
              key={track.filePath}
              track={track}
              group={groupByPrimaryPath.get(track.filePath)}
              showThumbnails={showListThumbnails}
              onPlay={() => playTrackInContext(track, albumTracks, selectedAlbumTitle)}
            />
          ))}
        </div>
      </div>
    );
  }

  // ---------------- ARTIST DETAIL VIEW ----------------
  if (selectedArtistName) {
    const artistAllTracks = dedupedGroups
      .map((g) => g.primaryTrack)
      .filter((t) => t.artist.toLowerCase() === selectedArtistName.toLowerCase())
      .sort(
        (a, b) =>
          a.album.toLowerCase().localeCompare(b.album.toLowerCase()) ||
          a.discNumber - b.discNumber ||
          a.trackNumber - b.trackNumber
      );
    const artistAlbums = buildAlbums(
      activeTracks.filter((t) => t.artist.toLowerCase() === selectedArtistName.toLowerCase()),
      'ALPHABETICAL'
    );
    const filteredTracks = selectedArtistAlbumFilter
      ? artistAllTracks.filter(
          (t) => t.album.toLowerCase() === selectedArtistAlbumFilter.toLowerCase()
        )
      : artistAllTracks;
    const artistFolders = Array.from(new Set(artistAllTracks.map((t) => t.parentFolderPath)));

    return (
      <div className="flex min-h-0 flex-1 flex-col">
        <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-800 bg-surfaceCard/80 px-3 py-2.5">
          <div className="flex items-center gap-2 min-w-0">
            <button
              onClick={closeArtistDetail}
              className="rounded-lg border border-slate-700 p-1.5 text-slate-200 hover:bg-slate-800"
            >
              <ArrowLeft className="h-4 w-4" />
            </button>
            <div className="min-w-0">
              <div className="truncate text-sm font-bold text-white">{selectedArtistName}</div>
              <div className="text-xs text-slate-400">
                {artistAlbums.length} album(s) • {artistAllTracks.length} tracks
              </div>
            </div>
          </div>
          <div className="flex items-center gap-1.5">
            <button
              onClick={() =>
                filteredTracks[0] &&
                playTrackInContext(filteredTracks[0], filteredTracks, selectedArtistName)
              }
              className="flex items-center gap-1 rounded-lg bg-samsungBlue px-2.5 py-1 text-xs font-semibold text-white hover:bg-blue-500"
            >
              <Play className="h-3.5 w-3.5" />
              Play All
            </button>
            <button
              onClick={() => jumpToFolders(artistFolders, selectedArtistName)}
              className="flex items-center gap-1 rounded-lg border border-slate-700 bg-slate-900 px-2.5 py-1 text-xs text-slate-200 hover:bg-slate-800"
            >
              <Folder className="h-3.5 w-3.5 text-winampAmber" />
              Go to Folder
            </button>
          </div>
        </div>

        {/* Horizontal Album Index Bar below Artist header (>1 album) */}
        {artistAlbums.length > 1 && (
          <div className="flex items-center gap-1.5 overflow-x-auto border-b border-slate-800 bg-slate-950/90 px-3 py-2">
            <button
              onClick={() => selectArtistAlbumFilter(null)}
              className={`shrink-0 rounded-full border px-2.5 py-1 text-xs font-medium ${
                selectedArtistAlbumFilter === null
                  ? 'border-samsungBlue bg-samsungBlue text-white'
                  : 'border-slate-700 bg-slate-900 text-slate-300'
              }`}
            >
              All Albums ({artistAllTracks.length})
            </button>
            {artistAlbums.map((alb) => (
              <button
                key={alb.title}
                onClick={() => selectArtistAlbumFilter(alb.title)}
                className={`shrink-0 rounded-full border px-2.5 py-1 text-xs font-medium ${
                  selectedArtistAlbumFilter === alb.title
                    ? 'border-samsungBlue bg-samsungBlue text-white'
                    : 'border-slate-700 bg-slate-900 text-slate-300 hover:border-slate-500'
                }`}
              >
                {alb.title} ({alb.trackCount})
              </button>
            ))}
          </div>
        )}

        <div ref={listContainerRef} onScroll={handleScroll} className="flex-1 space-y-1.5 overflow-y-auto p-3">
          {filteredTracks.map((track) => (
            <TrackRow
              key={track.filePath}
              track={track}
              group={groupByPrimaryPath.get(track.filePath)}
              showThumbnails={showListThumbnails}
              onPlay={() => playTrackInContext(track, filteredTracks, selectedArtistName)}
            />
          ))}
        </div>
      </div>
    );
  }

  // ---------------- PLAYLIST DETAIL VIEW ----------------
  if (selectedPlaylistId) {
    const pl = playlists.find((p) => p.id === selectedPlaylistId);
    if (pl) {
      const trackByKey = new Map(activeTracks.map((t) => [getNormalizedTrackKey(t), t]));
      const resolvedTracks = pl.items
        .map((item) => trackByKey.get(item.normalizedKey.toLowerCase()))
        .filter((t): t is Track => Boolean(t));

      return (
        <div className="flex min-h-0 flex-1 flex-col">
          <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-800 bg-surfaceCard/80 px-3 py-2.5">
            <div className="flex items-center gap-2 min-w-0">
              <button
                onClick={() => selectPlaylist(null)}
                className="rounded-lg border border-slate-700 p-1.5 text-slate-200 hover:bg-slate-800"
              >
                <ArrowLeft className="h-4 w-4" />
              </button>
              <div className="min-w-0">
                <div className="truncate text-sm font-bold text-white">{pl.name}</div>
                <div className="text-xs text-slate-400">
                  {pl.items.length} entries ({resolvedTracks.length} playable on active library)
                </div>
              </div>
            </div>
            <button
              onClick={() =>
                resolvedTracks[0] && playTrackInContext(resolvedTracks[0], resolvedTracks, pl.name)
              }
              disabled={resolvedTracks.length === 0}
              className="flex items-center gap-1 rounded-lg bg-samsungBlue px-2.5 py-1 text-xs font-semibold text-white hover:bg-blue-500 disabled:opacity-40"
            >
              <Play className="h-3.5 w-3.5" />
              Play Playlist
            </button>
          </div>

          <div ref={listContainerRef} onScroll={handleScroll} className="flex-1 space-y-1.5 overflow-y-auto p-3">
            {pl.items.map((item, idx) => {
              const matched = trackByKey.get(item.normalizedKey.toLowerCase());
              return (
                <div
                  key={`${item.normalizedKey}-${idx}`}
                  className="flex items-center justify-between gap-2 rounded-xl border border-slate-800 bg-surfaceCard/70 px-3 py-2"
                >
                  <button
                    disabled={!matched}
                    onClick={() => matched && playTrackInContext(matched, resolvedTracks, pl.name)}
                    className="min-w-0 flex-1 text-left disabled:opacity-50"
                  >
                    <div className="truncate text-sm font-semibold text-white">
                      {idx + 1}. {item.title}
                    </div>
                    <div className="truncate text-xs text-slate-400">
                      {item.artist} • {item.album} • {formatDuration(item.durationMs)}
                      {!matched && ' (Unavailable on current device)'}
                    </div>
                  </button>
                  <div className="flex items-center gap-1">
                    <button
                      disabled={idx === 0}
                      onClick={() => void moveTrackInPlaylist(pl.id, idx, idx - 1)}
                      className="rounded p-1.5 text-slate-400 hover:bg-slate-800 hover:text-white disabled:opacity-30"
                    >
                      <ArrowUp className="h-3.5 w-3.5" />
                    </button>
                    <button
                      disabled={idx === pl.items.length - 1}
                      onClick={() => void moveTrackInPlaylist(pl.id, idx, idx + 1)}
                      className="rounded p-1.5 text-slate-400 hover:bg-slate-800 hover:text-white disabled:opacity-30"
                    >
                      <ArrowUp className="h-3.5 w-3.5 rotate-180" />
                    </button>
                    <button
                      onClick={() => void removeTrackFromPlaylist(pl.id, idx)}
                      className="rounded p-1.5 text-rose-400 hover:bg-rose-950/40"
                    >
                      <Trash2 className="h-3.5 w-3.5" />
                    </button>
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      );
    }
  }

  // Empty Library Onboarding Card (when no local/remote tracks loaded)
  if (activeTracks.length === 0 && selectedTab !== 'PLAYLISTS') {
    return (
      <div className="flex flex-1 items-center justify-center p-6">
        <div className="max-w-md space-y-4 rounded-2xl border border-slate-800 bg-surfaceCard p-6 text-center shadow-xl">
          <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-2xl bg-samsungBlue/20 text-samsungBlue">
            <FolderOpen className="h-7 w-7" />
          </div>
          <h2 className="text-lg font-bold text-white">No Music Loaded on This Library</h2>
          <p className="text-xs leading-relaxed text-slate-400">
            Select your scoped Musical Root Folder on this PC/Browser, or open Musika Connect to browse & control your Android phone library over LAN / Supabase.
          </p>
          <div className="flex flex-wrap justify-center gap-2">
            <button
              onClick={() => void pickAndScanFolder()}
              className="flex items-center gap-1.5 rounded-xl bg-samsungBlue px-4 py-2 text-xs font-bold text-white hover:bg-blue-500"
            >
              <FolderOpen className="h-4 w-4" />
              Pick Music Root Folder
            </button>
            <button
              onClick={() => setShowConnectModal(true)}
              className="flex items-center gap-1.5 rounded-xl border border-slate-700 bg-slate-900 px-4 py-2 text-xs font-semibold text-winampGreen hover:border-winampGreen"
            >
              Open Musika Connect
            </button>
          </div>
        </div>
      </div>
    );
  }

  // ---------------- TAB: FOLDERS (STRICTLY HIERARCHICAL + RAW FILES) ----------------
  if (selectedTab === 'FOLDERS') {
    const rawTracks = rawTracksForDirectoryView(activeTracks);
    const normRoot = (activeTracks[0]?.filePath.startsWith(rootPath) ? rootPath : '') || rootPath;
    const normalizedRoot = normRoot.replace(/\\/g, '/').replace(/\/+$/, '') || '/';
    const tree = buildFolderTree(normalizedRoot, rawTracks);
    const activeDir = currentFolderPath && tree.has(currentFolderPath) ? currentFolderPath : normalizedRoot;

    const childFolders = sortFolders(
      Array.from(tree.values()).filter((n) => n.parentPath === activeDir && n.absolutePath !== activeDir),
      folderSort
    ).filter((n) => !query || n.folderName.toLowerCase().includes(query));

    const directFiles = rawTracks
      .filter(
        (t) =>
          (t.parentFolderPath || normalizedRoot).replace(/\\/g, '/').replace(/\/+$/, '') === activeDir
      )
      .filter(
        (t) =>
          !query ||
          t.title.toLowerCase().includes(query) ||
          t.fileName.toLowerCase().includes(query) ||
          t.artist.toLowerCase().includes(query)
      )
      .sort((a, b) => a.fileName.toLowerCase().localeCompare(b.fileName.toLowerCase()));

    const activeNode = tree.get(activeDir);
    const parentNode = activeNode?.parentPath ? tree.get(activeNode.parentPath) : undefined;
    const siblingDiscs = parentNode
      ? Array.from(tree.values())
          .filter((n) => n.parentPath === parentNode.absolutePath && n.isDiscFolder)
          .sort((a, b) => (a.discNumber ?? 99) - (b.discNumber ?? 99))
      : [];
    const childDiscs = childFolders.filter((f) => isDiscFolder(f.folderName));

    return (
      <div className="flex min-h-0 flex-1 flex-col">
        {/* Folder Toolbar: Navigation + Multi-Disc Quick Actions + Folder Sort */}
        <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-800 bg-surfaceCard/80 px-3 py-2">
          <div className="flex items-center gap-1.5 min-w-0">
            <button
              onClick={() => navigateUpFolder()}
              disabled={activeDir === normalizedRoot}
              className="rounded-lg border border-slate-700 p-1.5 text-slate-200 hover:bg-slate-800 disabled:opacity-30"
              title="Parent Folder"
            >
              <FolderUp className="h-4 w-4" />
            </button>
            <button
              onClick={jumpToSectionRoot}
              disabled={activeDir === normalizedRoot}
              className="rounded-lg border border-slate-700 p-1.5 text-winampAmber hover:bg-slate-800 disabled:opacity-30"
              title="Up to Musical Root Folder"
            >
              <ChevronsUp className="h-4 w-4" />
            </button>
            <span className="truncate font-mono text-xs text-slate-300">
              /{activeNode?.relativePath || ''} ({activeNode?.recursiveTrackCount ?? rawTracks.length} files in branch)
            </span>
          </div>

          <div className="flex flex-wrap items-center gap-1.5">
            {(childDiscs.length > 1 || siblingDiscs.length > 1) && (
              <button
                onClick={() =>
                  playAllDiscsInFolder(
                    childDiscs.length > 1 ? activeDir : parentNode?.absolutePath ?? activeDir,
                    childDiscs.length > 1
                      ? activeNode?.folderName ?? 'Multi-Disc'
                      : parentNode?.folderName ?? 'Multi-Disc'
                  )
                }
                className="flex items-center gap-1 rounded-lg border border-winampAmber/50 bg-winampAmber/15 px-2.5 py-1 text-xs font-semibold text-winampAmber hover:bg-winampAmber/25"
              >
                <Layers className="h-3.5 w-3.5" />
                Play All Discs
              </button>
            )}
            <select
              value={folderSort}
              onChange={(e) => setFolderSort(e.target.value as FolderSortOption)}
              className="rounded-lg border border-slate-700 bg-slate-900 px-2 py-1 text-xs text-slate-200"
            >
              <option value="SYSTEM_DEFAULT">Sort: System Default</option>
              <option value="ALPHABETICAL">Sort: Alphabetical</option>
              <option value="MOST_MUSIC_FILES">Sort: Most Music Files</option>
            </select>
          </div>
        </div>

        {/* Sibling Disc Switcher when inside Disc 1 / Disc 2 */}
        {siblingDiscs.length > 1 && (
          <div className="flex items-center gap-1.5 overflow-x-auto border-b border-slate-800 bg-slate-950/90 px-3 py-1.5">
            <span className="text-[10px] font-bold uppercase text-slate-400">Jump Disc:</span>
            {siblingDiscs.map((disc) => (
              <button
                key={disc.absolutePath}
                onClick={() => openFolder(disc.absolutePath)}
                className={`rounded-md border px-2 py-0.5 text-xs ${
                  disc.absolutePath === activeDir
                    ? 'border-winampGreen bg-winampGreen/20 text-winampGreen font-bold'
                    : 'border-slate-700 bg-slate-900 text-slate-300'
                }`}
              >
                {disc.folderName} ({disc.recursiveTrackCount})
              </button>
            ))}
          </div>
        )}

        <div ref={listContainerRef} onScroll={handleScroll} className="flex-1 space-y-1.5 overflow-y-auto p-3">
          {childFolders.map((folder) => (
            <button
              key={folder.absolutePath}
              onClick={() => openFolder(folder.absolutePath)}
              className="flex w-full items-center justify-between rounded-xl border border-slate-800 bg-surfaceCard/80 px-3 py-2.5 text-left hover:border-samsungBlue"
            >
              <div className="flex items-center gap-2.5 min-w-0">
                <Folder className="h-5 w-5 shrink-0 text-winampAmber" />
                <div className="min-w-0">
                  <div className="truncate text-sm font-semibold text-white">{folder.folderName}</div>
                  <div className="text-xs text-slate-400">
                    {folder.recursiveTrackCount} music files in folder & subfolders
                  </div>
                </div>
              </div>
              {folder.isDiscFolder && (
                <span className="rounded bg-winampAmber/20 px-2 py-0.5 font-mono text-[10px] font-bold text-winampAmber">
                  DISC {folder.discNumber}
                </span>
              )}
            </button>
          ))}

          {directFiles.map((track) => (
            <TrackRow
              key={track.filePath}
              track={track}
              showThumbnails={showListThumbnails}
              onPlay={() =>
                playTrackInContext(track, directFiles, `Folder: ${activeNode?.folderName ?? 'Root'}`)
              }
            />
          ))}
        </div>
      </div>
    );
  }

  // ---------------- TAB: PLAYLISTS ----------------
  if (selectedTab === 'PLAYLISTS') {
    return (
      <div className="flex min-h-0 flex-1 flex-col">
        <div className="flex flex-wrap items-center gap-2 border-b border-slate-800 bg-surfaceCard/80 px-3 py-2.5">
          <input
            value={newPlaylistName}
            onChange={(e) => setNewPlaylistName(e.target.value)}
            placeholder="New playlist name..."
            className="min-w-0 flex-1 rounded-lg border border-slate-700 bg-black/50 px-2.5 py-1.5 text-xs text-white focus:border-samsungBlue focus:outline-none"
          />
          <button
            onClick={() => {
              if (!newPlaylistName.trim()) return;
              void createPlaylist(newPlaylistName.trim());
              setNewPlaylistName('');
            }}
            className="flex items-center gap-1 rounded-lg bg-samsungBlue px-3 py-1.5 text-xs font-bold text-white hover:bg-blue-500"
          >
            <Plus className="h-3.5 w-3.5" />
            Create Playlist
          </button>
        </div>

        <div ref={listContainerRef} onScroll={handleScroll} className="flex-1 space-y-2 overflow-y-auto p-3">
          {playlists.length === 0 ? (
            <div className="rounded-xl border border-dashed border-slate-800 p-8 text-center text-xs text-slate-400">
              No playlists yet. Create one above, add songs from any track row, or convert your current Dual Queue into a playlist!
            </div>
          ) : (
            playlists.map((pl) => (
              <div
                key={pl.id}
                className="flex items-center justify-between gap-2 rounded-xl border border-slate-800 bg-surfaceCard/80 px-3 py-2.5"
              >
                {renamingPlaylistId === pl.id ? (
                  <div className="flex flex-1 items-center gap-2">
                    <input
                      value={renameText}
                      onChange={(e) => setRenameText(e.target.value)}
                      className="flex-1 rounded border border-slate-700 bg-black/60 px-2 py-1 text-xs text-white"
                    />
                    <button
                      onClick={() => {
                        void renamePlaylist(pl.id, renameText);
                        setRenamingPlaylistId(null);
                      }}
                      className="rounded bg-winampGreen px-2.5 py-1 text-xs font-bold text-black"
                    >
                      Save
                    </button>
                  </div>
                ) : (
                  <button onClick={() => selectPlaylist(pl.id)} className="min-w-0 flex-1 text-left">
                    <div className="truncate text-sm font-bold text-white">{pl.name}</div>
                    <div className="text-xs text-slate-400">{pl.items.length} tracks</div>
                  </button>
                )}
                <div className="flex items-center gap-1">
                  <button
                    onClick={() => {
                      setRenamingPlaylistId(pl.id);
                      setRenameText(pl.name);
                    }}
                    className="rounded p-1.5 text-slate-400 hover:bg-slate-800 hover:text-white"
                  >
                    <Edit3 className="h-4 w-4" />
                  </button>
                  <button
                    onClick={() => void deletePlaylist(pl.id)}
                    className="rounded p-1.5 text-rose-400 hover:bg-rose-950/40"
                  >
                    <Trash2 className="h-4 w-4" />
                  </button>
                </div>
              </div>
            ))
          )}
        </div>
      </div>
    );
  }

  // ---------------- TAB: ALBUMS ----------------
  if (selectedTab === 'ALBUMS') {
    const albums = buildAlbums(activeTracks, albumSort).filter(
      (a) =>
        !query ||
        a.title.toLowerCase().includes(query) ||
        a.artist.toLowerCase().includes(query)
    );
    const sortKeys = albums.map((a) => (albumSort === 'ARTIST' ? a.artist : a.title));

    return (
      <div className="flex min-h-0 flex-1 flex-col">
        <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-800 bg-surfaceCard/80 px-3 py-2">
          <div className="relative min-w-[180px] flex-1">
            <Search className="absolute left-2.5 top-2 h-3.5 w-3.5 text-slate-400" />
            <input
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder="Filter albums or artists..."
              className="w-full rounded-lg border border-slate-700 bg-black/50 py-1.5 pl-8 pr-2.5 text-xs text-white focus:border-samsungBlue focus:outline-none"
            />
          </div>
          <select
            value={albumSort}
            onChange={(e) => setAlbumSort(e.target.value as AlbumSortOption)}
            className="rounded-lg border border-slate-700 bg-slate-900 px-2 py-1.5 text-xs text-slate-200"
          >
            <option value="ALPHABETICAL">Sort: Alphabetical</option>
            <option value="ARTIST">Sort: Artist</option>
            <option value="DIRECTORY">Sort: Directory</option>
          </select>
        </div>

        <div className="flex min-h-0 flex-1">
          <div ref={listContainerRef} onScroll={handleScroll} className="flex-1 space-y-1.5 overflow-y-auto p-3">
            {albums.map((alb, idx) => (
              <div
                key={`${alb.title}-${alb.artist}-${idx}`}
                ref={(el) => {
                  itemRefs.current[idx] = el;
                }}
                className="flex items-center justify-between gap-2 rounded-xl border border-slate-800 bg-surfaceCard/70 px-3 py-2.5 hover:border-slate-600"
              >
                <button
                  onClick={() => jumpToAlbum(alb.title)}
                  className="flex min-w-0 flex-1 items-center gap-3 text-left"
                >
                  {showListThumbnails && (
                    <div className="flex h-11 w-11 shrink-0 items-center justify-center overflow-hidden rounded-lg border border-slate-700 bg-slate-900">
                      {alb.artworkUrl ? (
                        <img src={alb.artworkUrl} alt="" className="h-full w-full object-cover" />
                      ) : (
                        <Disc3 className="h-5 w-5 text-samsungBlue" />
                      )}
                    </div>
                  )}
                  <div className="min-w-0">
                    <div className="truncate text-sm font-bold text-white">{alb.title}</div>
                    <div className="truncate text-xs text-slate-400">
                      {alb.artist} • {alb.trackCount} tracks
                      {alb.discCount > 1 ? ` • ${alb.discCount} Discs` : ''}
                    </div>
                  </div>
                </button>
                <div className="flex shrink-0 items-center gap-1">
                  <button
                    onClick={() => jumpToArtist(alb.artist)}
                    className="rounded p-1.5 text-slate-400 hover:bg-slate-800 hover:text-samsungBlue"
                    title="Go to Artist"
                  >
                    <User className="h-4 w-4" />
                  </button>
                  <button
                    onClick={() => jumpToFolders(alb.folderPaths, alb.title)}
                    className="rounded p-1.5 text-slate-400 hover:bg-slate-800 hover:text-winampAmber"
                    title="Go to Folder"
                  >
                    <Folder className="h-4 w-4" />
                  </button>
                </div>
              </div>
            ))}
          </div>
          <FastAlphabetRail keysForItems={sortKeys} onJumpToIndex={scrollToItemIndex} />
        </div>
      </div>
    );
  }

  // ---------------- TAB: ARTISTS ----------------
  if (selectedTab === 'ARTISTS') {
    const artists = buildArtists(activeTracks, artistSort).filter(
      (a) => !query || a.name.toLowerCase().includes(query)
    );
    const sortKeys = artists.map((a) => a.name);

    return (
      <div className="flex min-h-0 flex-1 flex-col">
        <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-800 bg-surfaceCard/80 px-3 py-2">
          <div className="relative min-w-[180px] flex-1">
            <Search className="absolute left-2.5 top-2 h-3.5 w-3.5 text-slate-400" />
            <input
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder="Filter artists..."
              className="w-full rounded-lg border border-slate-700 bg-black/50 py-1.5 pl-8 pr-2.5 text-xs text-white focus:border-samsungBlue focus:outline-none"
            />
          </div>
          <select
            value={artistSort}
            onChange={(e) => setArtistSort(e.target.value as ArtistSortOption)}
            className="rounded-lg border border-slate-700 bg-slate-900 px-2 py-1.5 text-xs text-slate-200"
          >
            <option value="ALPHABETICAL">Sort: Alphabetical</option>
            <option value="DIRECTORY">Sort: Directory</option>
          </select>
        </div>

        <div className="flex min-h-0 flex-1">
          <div ref={listContainerRef} onScroll={handleScroll} className="flex-1 space-y-1.5 overflow-y-auto p-3">
            {artists.map((art, idx) => (
              <div
                key={art.name}
                ref={(el) => {
                  itemRefs.current[idx] = el;
                }}
                className="flex items-center justify-between gap-2 rounded-xl border border-slate-800 bg-surfaceCard/70 px-3 py-2.5 hover:border-slate-600"
              >
                <button
                  onClick={() => jumpToArtist(art.name)}
                  className="flex min-w-0 flex-1 items-center gap-3 text-left"
                >
                  <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full border border-slate-700 bg-slate-900">
                    <User className="h-5 w-5 text-samsungBlue" />
                  </div>
                  <div className="min-w-0">
                    <div className="truncate text-sm font-bold text-white">{art.name}</div>
                    <div className="text-xs text-slate-400">
                      {art.albumCount} album(s) • {art.trackCount} track(s)
                    </div>
                  </div>
                </button>
                <button
                  onClick={() => jumpToFolders(art.folderPaths, art.name)}
                  className="rounded p-1.5 text-slate-400 hover:bg-slate-800 hover:text-winampAmber"
                  title="Go to Folder"
                >
                  <Folder className="h-4 w-4" />
                </button>
              </div>
            ))}
          </div>
          <FastAlphabetRail keysForItems={sortKeys} onJumpToIndex={scrollToItemIndex} />
        </div>
      </div>
    );
  }

  // ---------------- TAB: SONGS (DEDUPLICATED BY DEFAULT) ----------------
  const sortedTracks = sortSongs(dedupedGroups, songSort)
    .map((g) => g.primaryTrack)
    .filter(
      (t) =>
        !query ||
        t.title.toLowerCase().includes(query) ||
        t.artist.toLowerCase().includes(query) ||
        t.album.toLowerCase().includes(query)
    );

  // Fast scrollbar uses the ACTIVE SORT KEY letter (Artist if sorted by Artist, Album if sorted by Album)
  const songSortKeys = sortedTracks.map((t) => {
    if (songSort === 'ARTIST') return t.artist;
    if (songSort === 'ALBUM') return t.album;
    if (songSort === 'DIRECTORY') return t.relativePath || t.filePath;
    return t.title;
  });

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-800 bg-surfaceCard/80 px-3 py-2">
        <div className="relative min-w-[180px] flex-1">
          <Search className="absolute left-2.5 top-2 h-3.5 w-3.5 text-slate-400" />
          <input
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder="Filter songs, artists, albums..."
            className="w-full rounded-lg border border-slate-700 bg-black/50 py-1.5 pl-8 pr-2.5 text-xs text-white focus:border-samsungBlue focus:outline-none"
          />
        </div>
        <select
          value={songSort}
          onChange={(e) => setSongSort(e.target.value as SongSortOption)}
          className="rounded-lg border border-slate-700 bg-slate-900 px-2 py-1.5 text-xs text-slate-200"
        >
          <option value="ALPHABETICAL">Sort: Alphabetical</option>
          <option value="ARTIST">Sort: Artist</option>
          <option value="ALBUM">Sort: Album</option>
          <option value="DIRECTORY">Sort: Directory</option>
        </select>
      </div>

      <div className="flex min-h-0 flex-1">
        <div ref={listContainerRef} onScroll={handleScroll} className="flex-1 space-y-1.5 overflow-y-auto p-3">
          {sortedTracks.map((track, idx) => (
            <div
              key={track.filePath}
              ref={(el) => {
                itemRefs.current[idx] = el;
              }}
            >
              <TrackRow
                track={track}
                group={groupByPrimaryPath.get(track.filePath)}
                showThumbnails={showListThumbnails}
                onPlay={() => playTrackInContext(track, sortedTracks, 'All Songs')}
              />
            </div>
          ))}
        </div>
        <FastAlphabetRail keysForItems={songSortKeys} onJumpToIndex={scrollToItemIndex} />
      </div>
    </div>
  );
};
