import {
  Album,
  AlbumSortOption,
  Artist,
  ArtistSortOption,
  compareAudioQuality,
  DeduplicatedTrackGroup,
  FolderNode,
  FolderSortOption,
  getNormalizedTrackKey,
  RandomMode,
  SongSortOption,
  Track
} from './models';

const DISC_FOLDER_REGEX = /^(?:cd|disc|disk|disco)\s*[-_.]?\s*0*([1-9][0-9]*)(?:\b|[-_\s].*)?$/i;

export function extractDiscNumberFromFolderName(folderName: string): number | undefined {
  const match = DISC_FOLDER_REGEX.exec(folderName.trim());
  if (!match) return undefined;
  const num = Number.parseInt(match[1], 10);
  return Number.isFinite(num) ? num : undefined;
}

export function isDiscFolder(folderName: string): boolean {
  return extractDiscNumberFromFolderName(folderName) !== undefined;
}

export function deduplicateTracks(rawTracks: Track[]): DeduplicatedTrackGroup[] {
  if (rawTracks.length === 0) return [];
  const grouped = new Map<string, Track[]>();
  for (const track of rawTracks) {
    const key = getNormalizedTrackKey(track);
    const list = grouped.get(key) ?? [];
    list.push(track);
    grouped.set(key, list);
  }

  const results: DeduplicatedTrackGroup[] = [];
  for (const [normalizedKey, variants] of grouped.entries()) {
    const sorted = [...variants].sort((a, b) => {
      const qualCmp = compareAudioQuality(b.audioQuality, a.audioQuality);
      return qualCmp !== 0 ? qualCmp : a.filePath.localeCompare(b.filePath);
    });
    const altCount = Math.max(0, sorted.length - 1);
    const annotated = sorted.map((v) => ({ ...v, alternateVariantsCount: altCount }));
    results.push({
      normalizedKey,
      primaryTrack: annotated[0],
      allVariants: annotated
    });
  }
  return results;
}

export function rawTracksForDirectoryView(rawTracks: Track[]): Track[] {
  return rawTracks.map((t) => ({ ...t, alternateVariantsCount: 0 }));
}

export function orderTracksForPlayAllDiscs(tracks: Track[], randomMode: RandomMode): Track[] {
  const sequential = [...tracks].sort(
    (a, b) =>
      a.discNumber - b.discNumber ||
      a.trackNumber - b.trackNumber ||
      a.title.toLowerCase().localeCompare(b.title.toLowerCase())
  );
  if (randomMode === 'OFF') return sequential;
  return shuffleArray(sequential);
}

export function shuffleArray<T>(items: T[]): T[] {
  const copy = [...items];
  for (let i = copy.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [copy[i], copy[j]] = [copy[j], copy[i]];
  }
  return copy;
}

export function hierarchicalPathCompare(pathA: string, pathB: string): number {
  const partsA = pathA.replace(/\\/g, '/').split('/').filter(Boolean);
  const partsB = pathB.replace(/\\/g, '/').split('/').filter(Boolean);
  const limit = Math.min(partsA.length, partsB.length);
  for (let i = 0; i < limit; i++) {
    const cmp = partsA[i].toLowerCase().localeCompare(partsB[i].toLowerCase());
    if (cmp !== 0) return cmp;
  }
  return partsA.length - partsB.length;
}

export function sortSongs(groups: DeduplicatedTrackGroup[], sortOption: SongSortOption): DeduplicatedTrackGroup[] {
  const copy = [...groups];
  switch (sortOption) {
    case 'ALPHABETICAL':
      return copy.sort((a, b) =>
        a.primaryTrack.title.toLowerCase().localeCompare(b.primaryTrack.title.toLowerCase())
      );
    case 'ALBUM':
      return copy.sort(
        (a, b) =>
          a.primaryTrack.album.toLowerCase().localeCompare(b.primaryTrack.album.toLowerCase()) ||
          a.primaryTrack.discNumber - b.primaryTrack.discNumber ||
          a.primaryTrack.trackNumber - b.primaryTrack.trackNumber
      );
    case 'ARTIST':
      return copy.sort(
        (a, b) =>
          a.primaryTrack.artist.toLowerCase().localeCompare(b.primaryTrack.artist.toLowerCase()) ||
          a.primaryTrack.album.toLowerCase().localeCompare(b.primaryTrack.album.toLowerCase()) ||
          a.primaryTrack.discNumber - b.primaryTrack.discNumber ||
          a.primaryTrack.trackNumber - b.primaryTrack.trackNumber
      );
    case 'DIRECTORY':
      return copy.sort((a, b) => {
        const pathCmp = hierarchicalPathCompare(
          a.primaryTrack.relativePath || a.primaryTrack.filePath,
          b.primaryTrack.relativePath || b.primaryTrack.filePath
        );
        return pathCmp !== 0
          ? pathCmp
          : a.primaryTrack.title.toLowerCase().localeCompare(b.primaryTrack.title.toLowerCase());
      });
  }
}

export function buildAlbums(rawTracks: Track[], sortOption: AlbumSortOption): Album[] {
  const byAlbum = new Map<string, Track[]>();
  for (const track of rawTracks) {
    const k = track.album.trim().toLowerCase();
    const list = byAlbum.get(k) ?? [];
    list.push(track);
    byAlbum.set(k, list);
  }

  const albumsWithPath: Array<{ album: Album; earliestRelPath: string }> = [];
  for (const albumTracks of byAlbum.values()) {
    const deduped = deduplicateTracks(albumTracks);
    const primary = deduped.map((g) => g.primaryTrack);
    const first = primary[0];
    const folderPaths = Array.from(
      new Set(albumTracks.map((t) => t.parentFolderPath).filter(Boolean))
    ).sort(hierarchicalPathCompare);
    const earliestRelPath =
      [...albumTracks]
        .map((t) => t.relativePath || t.filePath)
        .sort(hierarchicalPathCompare)[0] ?? '';
    const discFolders = folderPaths.filter((p) => {
      const name = p.split('/').pop() ?? '';
      return isDiscFolder(name);
    });
    const maxDiscTag = Math.max(1, ...albumTracks.map((t) => Math.max(t.discNumber, t.totalDiscs)));
    const distinctDiscs = new Set(albumTracks.map((t) => t.discNumber)).size;

    albumsWithPath.push({
      album: {
        title: first.album,
        artist: first.albumArtist || first.artist,
        trackCount: primary.length,
        totalDurationMs: primary.reduce((sum, t) => sum + t.durationMs, 0),
        discCount: Math.max(maxDiscTag, distinctDiscs, discFolders.length),
        discFolders,
        folderPaths,
        year: primary.find((t) => t.year)?.year,
        artworkUrl: primary.find((t) => t.artworkUrl)?.artworkUrl
      },
      earliestRelPath
    });
  }

  switch (sortOption) {
    case 'ALPHABETICAL':
      return albumsWithPath
        .sort((a, b) => a.album.title.toLowerCase().localeCompare(b.album.title.toLowerCase()))
        .map((x) => x.album);
    case 'ARTIST':
      return albumsWithPath
        .sort(
          (a, b) =>
            a.album.artist.toLowerCase().localeCompare(b.album.artist.toLowerCase()) ||
            a.album.title.toLowerCase().localeCompare(b.album.title.toLowerCase())
        )
        .map((x) => x.album);
    case 'DIRECTORY':
      return albumsWithPath
        .sort(
          (a, b) =>
            hierarchicalPathCompare(a.earliestRelPath, b.earliestRelPath) ||
            a.album.title.toLowerCase().localeCompare(b.album.title.toLowerCase())
        )
        .map((x) => x.album);
  }
}

export function buildArtists(rawTracks: Track[], sortOption: ArtistSortOption): Artist[] {
  const byArtist = new Map<string, Track[]>();
  for (const track of rawTracks) {
    const k = track.artist.trim().toLowerCase();
    const list = byArtist.get(k) ?? [];
    list.push(track);
    byArtist.set(k, list);
  }

  const items: Array<{ artist: Artist; earliestRelPath: string }> = [];
  for (const artistTracks of byArtist.values()) {
    const deduped = deduplicateTracks(artistTracks);
    const first = deduped[0].primaryTrack;
    const albumCount = new Set(deduped.map((g) => g.primaryTrack.album.trim().toLowerCase())).size;
    const folderPaths = Array.from(
      new Set(artistTracks.map((t) => t.parentFolderPath).filter(Boolean))
    ).sort(hierarchicalPathCompare);
    const earliestRelPath =
      [...artistTracks]
        .map((t) => t.relativePath || t.filePath)
        .sort(hierarchicalPathCompare)[0] ?? '';

    items.push({
      artist: {
        name: first.artist,
        albumCount,
        trackCount: deduped.length,
        folderPaths,
        artworkUrl: deduped.find((g) => g.primaryTrack.artworkUrl)?.primaryTrack.artworkUrl
      },
      earliestRelPath
    });
  }

  switch (sortOption) {
    case 'ALPHABETICAL':
      return items
        .sort((a, b) => a.artist.name.toLowerCase().localeCompare(b.artist.name.toLowerCase()))
        .map((x) => x.artist);
    case 'DIRECTORY':
      return items
        .sort(
          (a, b) =>
            hierarchicalPathCompare(a.earliestRelPath, b.earliestRelPath) ||
            a.artist.name.toLowerCase().localeCompare(b.artist.name.toLowerCase())
        )
        .map((x) => x.artist);
  }
}

export function buildFolderTree(rootPath: string, tracks: Track[]): Map<string, FolderNode> {
  const normRoot = rootPath.replace(/\\/g, '/').replace(/\/+$/, '') || '/';
  const directCounts = new Map<string, number>();
  const recursiveCounts = new Map<string, number>();
  const allDirs = new Set<string>([normRoot]);

  for (const track of tracks) {
    const parent = (track.parentFolderPath || normRoot).replace(/\\/g, '/').replace(/\/+$/, '') || '/';
    directCounts.set(parent, (directCounts.get(parent) ?? 0) + 1);
    let cursor = parent;
    while (cursor.length >= normRoot.length) {
      allDirs.add(cursor);
      recursiveCounts.set(cursor, (recursiveCounts.get(cursor) ?? 0) + 1);
      if (cursor === normRoot) break;
      const lastSlash = cursor.lastIndexOf('/');
      if (lastSlash <= 0) break;
      cursor = cursor.slice(0, lastSlash);
      if (cursor.length < normRoot.length && cursor !== normRoot) break;
    }
  }

  const childrenByParent = new Map<string, string[]>();
  for (const dir of allDirs) {
    if (dir === normRoot) continue;
    const parent = dir.slice(0, dir.lastIndexOf('/')) || normRoot;
    const list = childrenByParent.get(parent) ?? [];
    list.push(dir);
    childrenByParent.set(parent, list);
  }

  const map = new Map<string, FolderNode>();
  let orderCounter = 0;
  for (const dir of allDirs) {
    const parentPath = dir === normRoot ? '' : dir.slice(0, dir.lastIndexOf('/')) || normRoot;
    const folderName = dir === normRoot ? normRoot.split('/').pop() || 'Root' : dir.split('/').pop() || dir;
    const relativePath =
      dir === normRoot ? '' : dir.startsWith(normRoot + '/') ? dir.slice(normRoot.length + 1) : folderName;
    const discNumber = extractDiscNumberFromFolderName(folderName);
    map.set(dir, {
      absolutePath: dir,
      relativePath,
      folderName,
      parentPath,
      directTrackCount: directCounts.get(dir) ?? 0,
      recursiveTrackCount: recursiveCounts.get(dir) ?? 0,
      childFolderCount: (childrenByParent.get(dir) ?? []).length,
      systemOrderIndex: orderCounter++,
      isDiscFolder: discNumber !== undefined,
      discNumber
    });
  }
  return map;
}

export function sortFolders(nodes: FolderNode[], sortOption: FolderSortOption): FolderNode[] {
  const copy = [...nodes];
  switch (sortOption) {
    case 'SYSTEM_DEFAULT':
      return copy.sort((a, b) => a.systemOrderIndex - b.systemOrderIndex);
    case 'ALPHABETICAL':
      return copy.sort((a, b) => a.folderName.toLowerCase().localeCompare(b.folderName.toLowerCase()));
    case 'MOST_MUSIC_FILES':
      return copy.sort(
        (a, b) =>
          b.recursiveTrackCount - a.recursiveTrackCount ||
          a.folderName.toLowerCase().localeCompare(b.folderName.toLowerCase())
      );
  }
}
