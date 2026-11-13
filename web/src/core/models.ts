export type SongSortOption = 'ALPHABETICAL' | 'ALBUM' | 'ARTIST' | 'DIRECTORY';
export type AlbumSortOption = 'ALPHABETICAL' | 'ARTIST' | 'DIRECTORY';
export type ArtistSortOption = 'ALPHABETICAL' | 'DIRECTORY';
export type FolderSortOption = 'SYSTEM_DEFAULT' | 'ALPHABETICAL' | 'MOST_MUSIC_FILES';

export type RandomMode = 'OFF' | 'SHUFFLE_POOL' | 'TRUE_RANDOM';
export type LoopMode = 'NO_LOOP' | 'QUEUE_LOOP' | 'FILE_LOOP';

export type NowPlayingArtworkStyle =
  | 'ROUNDED_SQUARE'
  | 'SPINNING_DISC'
  | 'FULL_SCREEN_BACKGROUND'
  | 'WINAMP_RETRO_BEZEL';

export const ARTWORK_STYLE_LABELS: Record<NowPlayingArtworkStyle, string> = {
  ROUNDED_SQUARE: 'Classic Rounded Square',
  SPINNING_DISC: 'Spinning Vinyl / CD Disc',
  FULL_SCREEN_BACKGROUND: 'Full-Screen Background (Spotify Style)',
  WINAMP_RETRO_BEZEL: 'Retro Winamp Bezel Frame'
};

export interface AudioQuality {
  format: string;
  bitrateKbps: number;
  sampleRateHz: number;
  bitDepth: number;
  isLossless: boolean;
}

export interface Track {
  filePath: string;
  relativePath: string;
  fileName: string;
  fileExtension: string;
  title: string;
  artist: string;
  album: string;
  albumArtist?: string;
  discNumber: number;
  totalDiscs: number;
  trackNumber: number;
  durationMs: number;
  audioQuality: AudioQuality;
  year?: number;
  genre?: string;
  parentFolderPath: string;
  artworkUrl?: string;
  alternateVariantsCount?: number;
  fileSizeBytes?: number;
  lastModified?: number;
  /** Non-persistent File object reference when loaded from Browser File System Access API */
  browserFileHandleKey?: string;
  /** Optional authenticated LAN stream URL when user enables LAN streaming for missing tracks */
  lanStreamUri?: string;
}

export interface DeduplicatedTrackGroup {
  normalizedKey: string;
  primaryTrack: Track;
  allVariants: Track[];
}

export interface Album {
  title: string;
  artist: string;
  trackCount: number;
  totalDurationMs: number;
  discCount: number;
  discFolders: string[];
  folderPaths: string[];
  year?: number;
  artworkUrl?: string;
}

export interface Artist {
  name: string;
  albumCount: number;
  trackCount: number;
  folderPaths: string[];
  artworkUrl?: string;
}

export interface FolderNode {
  absolutePath: string;
  relativePath: string;
  folderName: string;
  parentPath: string;
  directTrackCount: number;
  recursiveTrackCount: number;
  childFolderCount: number;
  systemOrderIndex: number;
  isDiscFolder: boolean;
  discNumber?: number;
}

export interface Playlist {
  id: string;
  name: string;
  createdAt: number;
  updatedAt: number;
  items: PlaylistTrackItem[];
}

export interface PlaylistTrackItem {
  positionIndex: number;
  normalizedKey: string;
  relativePath: string;
  title: string;
  artist: string;
  album: string;
  durationMs: number;
}

export function getNormalizedTrackKey(track: Pick<Track, 'artist' | 'album' | 'title'>): string {
  return `${track.artist.trim().toLowerCase()}|${track.album.trim().toLowerCase()}|${track.title.trim().toLowerCase()}`;
}

export function getFormatTier(format: string): number {
  switch (format.trim().toUpperCase()) {
    case 'FLAC':
    case 'ALAC':
    case 'WAV':
    case 'APE':
      return 3;
    case 'OPUS':
    case 'OGG':
    case 'AAC':
    case 'M4A':
      return 2;
    case 'MP3':
    case 'WMA':
      return 1;
    default:
      return 0;
  }
}

export function compareAudioQuality(a: AudioQuality, b: AudioQuality): number {
  const tierDiff = getFormatTier(a.format) - getFormatTier(b.format);
  if (tierDiff !== 0) return tierDiff;
  if (a.isLossless && b.isLossless) {
    const bitDiff = a.bitDepth - b.bitDepth;
    if (bitDiff !== 0) return bitDiff;
    return a.sampleRateHz - b.sampleRateHz;
  }
  return a.bitrateKbps - b.bitrateKbps;
}

export function formatTelemetrySummary(q: AudioQuality): string {
  const fmt = q.format.toUpperCase();
  const br = q.bitrateKbps > 0 ? `${q.bitrateKbps} kbps` : 'VBR';
  const sr = `${(q.sampleRateHz / 1000).toFixed(1)} kHz`;
  return `${fmt} | ${br} | ${sr} | ${q.bitDepth || 16}bit`;
}

export function formatDuration(ms: number): string {
  const totalSec = Math.max(0, Math.floor(ms / 1000));
  const min = Math.floor(totalSec / 60);
  const sec = totalSec % 60;
  return `${min}:${sec.toString().padStart(2, '0')}`;
}

export function cycleRandomMode(mode: RandomMode): RandomMode {
  if (mode === 'OFF') return 'SHUFFLE_POOL';
  if (mode === 'SHUFFLE_POOL') return 'TRUE_RANDOM';
  return 'OFF';
}

export function cycleLoopMode(mode: LoopMode): LoopMode {
  if (mode === 'NO_LOOP') return 'QUEUE_LOOP';
  if (mode === 'QUEUE_LOOP') return 'FILE_LOOP';
  return 'NO_LOOP';
}
