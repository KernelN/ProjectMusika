import { parseBlob } from 'music-metadata';
import { AudioQuality, Track } from '../core/models';

const AUDIO_EXTENSIONS = new Set([
  'mp3',
  'flac',
  'wav',
  'ogg',
  'opus',
  'aac',
  'm4a',
  'alac',
  'wma',
  'ape'
]);

const DB_NAME = 'musika_web_cache_v1';
const STORE_HANDLES = 'dir_handles';
const STORE_LIBRARY = 'library_index';

// In-memory File objects mapped by filePath for instant browser playback without re-copying bytes
const browserFileRegistry = new Map<string, File>();

export function registerBrowserFile(filePath: string, file: File) {
  browserFileRegistry.set(filePath, file);
}

export function getRegisteredBrowserFile(filePath: string): File | undefined {
  return browserFileRegistry.get(filePath);
}

function openCacheDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, 1);
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains(STORE_HANDLES)) db.createObjectStore(STORE_HANDLES);
      if (!db.objectStoreNames.contains(STORE_LIBRARY)) db.createObjectStore(STORE_LIBRARY);
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

export async function saveCachedDirectoryHandle(handle: unknown): Promise<void> {
  try {
    const db = await openCacheDb();
    const tx = db.transaction(STORE_HANDLES, 'readwrite');
    tx.objectStore(STORE_HANDLES).put(handle, 'root');
  } catch {
    // Safari/Firefox or storage quota fallback
  }
}

export async function loadCachedDirectoryHandle(): Promise<FileSystemDirectoryHandle | null> {
  try {
    const db = await openCacheDb();
    return await new Promise((resolve) => {
      const tx = db.transaction(STORE_HANDLES, 'readonly');
      const req = tx.objectStore(STORE_HANDLES).get('root');
      req.onsuccess = () => resolve((req.result as FileSystemDirectoryHandle) ?? null);
      req.onerror = () => resolve(null);
    });
  } catch {
    return null;
  }
}

export async function saveCachedTracks(rootPath: string, tracks: Track[]): Promise<void> {
  try {
    const db = await openCacheDb();
    const tx = db.transaction(STORE_LIBRARY, 'readwrite');
    // Omit transient blob URLs before saving
    const serializable = tracks.map((t) => ({ ...t, artworkUrl: undefined }));
    tx.objectStore(STORE_LIBRARY).put({ rootPath, tracks: serializable }, 'snapshot');
  } catch {
    // Ignore IndexedDB error
  }
}

export async function loadCachedTracks(): Promise<{ rootPath: string; tracks: Track[] } | null> {
  try {
    const db = await openCacheDb();
    return await new Promise((resolve) => {
      const tx = db.transaction(STORE_LIBRARY, 'readonly');
      const req = tx.objectStore(STORE_LIBRARY).get('snapshot');
      req.onsuccess = () =>
        resolve((req.result as { rootPath: string; tracks: Track[] }) ?? null);
      req.onerror = () => resolve(null);
    });
  } catch {
    return null;
  }
}

export async function parseAudioFileToTrack(
  file: File,
  rootName: string,
  relativePath: string,
  existingByPath?: Map<string, Track>
): Promise<Track | null> {
  const ext = file.name.split('.').pop()?.toLowerCase() ?? '';
  if (!AUDIO_EXTENSIONS.has(ext)) return null;

  const cleanRel = relativePath.replace(/\\/g, '/').replace(/^\/+/, '');
  const fullPath = `${rootName}/${cleanRel}`;
  registerBrowserFile(fullPath, file);

  const cached = existingByPath?.get(fullPath);
  if (
    cached &&
    cached.fileSizeBytes === file.size &&
    cached.lastModified === file.lastModified
  ) {
    // Re-attach artwork/file reference cleanly on unchanged incremental scan
    return cached;
  }

  const slashIdx = fullPath.lastIndexOf('/');
  const parentFolderPath = slashIdx > 0 ? fullPath.slice(0, slashIdx) : rootName;
  const segments = cleanRel.split('/').filter(Boolean);
  const inferredAlbum =
    segments.length >= 2 ? segments[segments.length - 2] : 'Unknown Album';
  const inferredArtist =
    segments.length >= 3 ? segments[segments.length - 3] : 'Unknown Artist';
  const defaultTitle = file.name.replace(/\.[^.]+$/, '');

  let title = defaultTitle;
  let artist = inferredArtist;
  let album = inferredAlbum;
  let albumArtist: string | undefined;
  let trackNumber = 1;
  let discNumber = 1;
  let totalDiscs = 1;
  let durationMs = 0;
  let year: number | undefined;
  let genre: string | undefined;
  let artworkUrl: string | undefined;
  const lossless = ext === 'flac' || ext === 'alac' || ext === 'wav' || ext === 'ape';
  let bitrateKbps = lossless ? 900 : 192;
  let sampleRateHz = 44100;
  let bitDepth = lossless ? 16 : 16;

  try {
    const metadata = await parseBlob(file, { duration: true, skipCovers: false });
    const c = metadata.common;
    const f = metadata.format;
    if (c.title?.trim()) title = c.title.trim();
    if (c.artist?.trim()) artist = c.artist.trim();
    if (c.album?.trim()) album = c.album.trim();
    if (c.albumartist?.trim()) albumArtist = c.albumartist.trim();
    if (c.track?.no) trackNumber = c.track.no;
    if (c.disk?.no) discNumber = c.disk.no;
    if (c.disk?.of) totalDiscs = Math.max(discNumber, c.disk.of);
    if (c.year) year = c.year;
    if (c.genre && c.genre.length > 0) genre = c.genre[0];
    if (c.picture && c.picture.length > 0) {
      const pic = c.picture[0];
      const copied = new Uint8Array(pic.data.byteLength);
      copied.set(pic.data);
      const blob = new Blob([copied.buffer], { type: pic.format || 'image/jpeg' });
      artworkUrl = URL.createObjectURL(blob);
    }
    if (f.duration) durationMs = Math.round(f.duration * 1000);
    if (f.bitrate) bitrateKbps = Math.round(f.bitrate / 1000);
    if (f.sampleRate) sampleRateHz = Math.round(f.sampleRate);
    if (f.bitsPerSample) bitDepth = f.bitsPerSample;
  } catch {
    // Keep clean filename/folder fallback tags when binary headers are malformed
  }

  const audioQuality: AudioQuality = {
    format: ext.toUpperCase(),
    bitrateKbps,
    sampleRateHz,
    bitDepth,
    isLossless: lossless
  };

  return {
    filePath: fullPath,
    relativePath: cleanRel,
    fileName: file.name,
    fileExtension: ext,
    title,
    artist,
    album,
    albumArtist,
    discNumber,
    totalDiscs,
    trackNumber,
    durationMs,
    audioQuality,
    year,
    genre,
    parentFolderPath,
    artworkUrl,
    fileSizeBytes: file.size,
    lastModified: file.lastModified
  };
}

export async function scanBrowserDirectoryHandle(
  dirHandle: FileSystemDirectoryHandle,
  existingTracks: Track[] = [],
  onProgress?: (scanned: number) => void
): Promise<{ rootPath: string; tracks: Track[] }> {
  const rootPath = `/${dirHandle.name}`;
  const existingByPath = new Map(existingTracks.map((t) => [t.filePath, t]));
  const collectedFiles: Array<{ file: File; relPath: string }> = [];

  async function walk(handle: FileSystemDirectoryHandle, prefix: string) {
    const iterable = handle as unknown as {
      values: () => AsyncIterable<FileSystemHandle>;
    };
    for await (const entry of iterable.values()) {
      if (entry.kind === 'directory') {
        await walk(entry as FileSystemDirectoryHandle, prefix ? `${prefix}/${entry.name}` : entry.name);
      } else if (entry.kind === 'file') {
        const ext = entry.name.split('.').pop()?.toLowerCase() ?? '';
        if (AUDIO_EXTENSIONS.has(ext)) {
          const file = await (entry as FileSystemFileHandle).getFile();
          collectedFiles.push({ file, relPath: prefix ? `${prefix}/${entry.name}` : entry.name });
        }
      }
    }
  }

  await walk(dirHandle, '');

  const parsed: Track[] = [];
  const batchSize = 6;
  for (let i = 0; i < collectedFiles.length; i += batchSize) {
    const batch = collectedFiles.slice(i, i + batchSize);
    const items = await Promise.all(
      batch.map((item) => parseAudioFileToTrack(item.file, rootPath, item.relPath, existingByPath))
    );
    for (const track of items) {
      if (track) parsed.push(track);
    }
    onProgress?.(parsed.length);
  }

  await saveCachedDirectoryHandle(dirHandle);
  await saveCachedTracks(rootPath, parsed);
  return { rootPath, tracks: parsed };
}

export async function scanFallbackFileList(
  fileList: FileList,
  onProgress?: (scanned: number) => void
): Promise<{ rootPath: string; tracks: Track[] }> {
  const audioFiles = Array.from(fileList).filter((f) => {
    const ext = f.name.split('.').pop()?.toLowerCase() ?? '';
    return AUDIO_EXTENSIONS.has(ext);
  });
  const firstRel = (audioFiles[0] as File & { webkitRelativePath?: string })?.webkitRelativePath || '';
  const rootName = firstRel.includes('/') ? `/${firstRel.split('/')[0]}` : '/Music';
  const parsed: Track[] = [];
  const batchSize = 6;
  for (let i = 0; i < audioFiles.length; i += batchSize) {
    const batch = audioFiles.slice(i, i + batchSize);
    const items = await Promise.all(
      batch.map((f) => {
        const rawRel = (f as File & { webkitRelativePath?: string }).webkitRelativePath || f.name;
        const stripped = rawRel.includes('/') ? rawRel.slice(rawRel.indexOf('/') + 1) : rawRel;
        return parseAudioFileToTrack(f, rootName, stripped);
      })
    );
    for (const track of items) {
      if (track) parsed.push(track);
    }
    onProgress?.(parsed.length);
  }
  await saveCachedTracks(rootName, parsed);
  return { rootPath: rootName, tracks: parsed };
}
