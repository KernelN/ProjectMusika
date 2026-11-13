import { describe, expect, it } from 'vitest';
import {
  buildFolderTree,
  deduplicateTracks,
  orderTracksForPlayAllDiscs,
  sortFolders
} from './deduplicator';
import { Track } from './models';
import {
  ConnectedDeviceNode,
  encodeQrPairLink,
  filterSnapshotForTargetDevice,
  parseQrPairInput,
  validateAndPreparePlaybackTransfer
} from './syncContract';
import { DualQueueEngine } from '../player/DualQueueEngine';

function makeTrack(
  filePath: string,
  title: string,
  format: string,
  bitrateKbps: number,
  sampleRateHz = 44100,
  bitDepth = 16,
  durationMs = 240000,
  discNumber = 1,
  trackNumber = 1
): Track {
  const parts = filePath.split('/');
  const fileName = parts[parts.length - 1] || filePath;
  const parentFolderPath = parts.slice(0, -1).join('/') || '/';
  return {
    filePath,
    relativePath: filePath.replace(/^\/Music\//, ''),
    fileName,
    fileExtension: format.toLowerCase(),
    title,
    artist: 'Daft Punk',
    album: 'Discovery',
    discNumber,
    totalDiscs: 2,
    trackNumber,
    durationMs,
    audioQuality: {
      format,
      bitrateKbps,
      sampleRateHz,
      bitDepth,
      isLossless: format === 'FLAC'
    },
    parentFolderPath
  };
}

describe('Musika Web Core & Stage 2 Sync Contract', () => {
  it('deduplicates formats choosing highest quality FLAC over MP3/OPUS and preserves recursive folder counts', () => {
    const tFlac = makeTrack('/Music/Daft Punk/Discovery/CD1/01 - One More Time.flac', 'One More Time', 'FLAC', 1050, 96000, 24);
    const tOpus = makeTrack('/Music/Daft Punk/Discovery/CD1/01 - One More Time.opus', 'One More Time', 'OPUS', 160);
    const tMp3 = makeTrack('/Music/Daft Punk/Discovery/CD2/02 - Harder Better.mp3', 'Harder Better', 'MP3', 320);

    const groups = deduplicateTracks([tMp3, tOpus, tFlac]);
    expect(groups).toHaveLength(2);
    const oneMore = groups.find((g) => g.primaryTrack.title === 'One More Time')!;
    expect(oneMore.primaryTrack.audioQuality.format).toBe('FLAC');
    expect(oneMore.primaryTrack.alternateVariantsCount).toBe(1);

    const tree = buildFolderTree('/Music', [tFlac, tOpus, tMp3]);
    expect(tree.get('/Music/Daft Punk')?.recursiveTrackCount).toBe(3);
    expect(tree.get('/Music/Daft Punk/Discovery/CD1')?.isDiscFolder).toBe(true);
    const sortedRootKids = sortFolders([tree.get('/Music/Daft Punk/Discovery/CD2')!, tree.get('/Music/Daft Punk/Discovery/CD1')!], 'MOST_MUSIC_FILES');
    expect(sortedRootKids[0].folderName).toBe('CD1');
  });

  it('orders multi-disc tracks sequentially when Random is OFF', () => {
    const d2t1 = makeTrack('/Music/Album/CD2/01.flac', 'Track D2', 'FLAC', 900, 44100, 16, 180000, 2, 1);
    const d1t2 = makeTrack('/Music/Album/CD1/02.flac', 'Track D1-2', 'FLAC', 900, 44100, 16, 180000, 1, 2);
    const d1t1 = makeTrack('/Music/Album/CD1/01.flac', 'Track D1-1', 'FLAC', 900, 44100, 16, 180000, 1, 1);

    const ordered = orderTracksForPlayAllDiscs([d2t1, d1t2, d1t1], 'OFF');
    expect(ordered.map((t) => t.title)).toEqual(['Track D1-1', 'Track D1-2', 'Track D2']);
  });

  it('enforces Priority Queue FIFO even during True Random and 3-state Loop', () => {
    const t1 = makeTrack('/Music/01.flac', 'First', 'FLAC', 1000);
    const t2 = makeTrack('/Music/02.flac', 'Second', 'FLAC', 1000);
    const t3 = makeTrack('/Music/03.flac', 'Priority Hit', 'FLAC', 1000);

    const engine = new DualQueueEngine('TRUE_RANDOM', 'NO_LOOP');
    engine.playFromContext(t1, [t1, t2], 'All Songs');
    engine.enqueueImmediate(t3);

    engine.skipToNext(false);
    expect(engine.getState().currentTrack?.title).toBe('Priority Hit');
    expect(engine.getState().priorityQueue).toHaveLength(0);
  });

  it('enforces strict metadata verification on Move Music Here across formats and supports optional LAN streaming fallback', () => {
    const flacTrack = makeTrack('/Music/Daft Punk/Discovery/01 - One More Time.flac', 'One More Time', 'FLAC', 1050, 96000, 24, 320000);
    const missingTrack = makeTrack('/Music/Daft Punk/Discovery/02 - Aerodynamic.flac', 'Aerodynamic', 'FLAC', 980, 44100, 16, 212000);

    const engine = new DualQueueEngine();
    engine.playFromContext(flacTrack, [flacTrack, missingTrack], 'Discovery');

    const snapshot = engine.toPlaybackSnapshot('192.168.1.10:8765');

    const targetWithMp3Match: ConnectedDeviceNode = {
      deviceId: 'pc-1',
      deviceName: 'Windows PC',
      deviceType: 'DESKTOP',
      accountId: 'user-123',
      isThisDevice: false,
      isActivePlayingDevice: false,
      loadedTrackKeys: ['daft punk|discovery|one more time'],
      loadedRelativePaths: [],
      catalogTracks: [
        {
          trackIdentity: 'daft punk|discovery|one more time|MP3/One More Time.mp3',
          normalizedKey: 'daft punk|discovery|one more time',
          relativePath: 'MP3/One More Time.mp3',
          title: 'One More Time',
          artist: 'Daft Punk',
          album: 'Discovery',
          discNumber: 1,
          trackNumber: 1,
          durationMs: 320500, // Within 2s tolerance
          audioQuality: { format: 'MP3', bitrateKbps: 320, sampleRateHz: 44100, bitDepth: 16, isLossless: false }
        }
      ]
    };

    const allowedResult = validateAndPreparePlaybackTransfer('android-1', snapshot, targetWithMp3Match, false);
    expect(allowedResult.allowed).toBe(true);

    // Follow-up missing queue track is skipped when streaming is OFF, kept with lanStreamUri when ON
    const skippedRes = filterSnapshotForTargetDevice(snapshot, targetWithMp3Match, false);
    expect(skippedRes.skippedCount).toBe(1);
    expect(skippedRes.filteredSnapshot.standardQueue).toHaveLength(1);

    const streamRes = filterSnapshotForTargetDevice(snapshot, targetWithMp3Match, true);
    expect(streamRes.skippedCount).toBe(0);
    expect(streamRes.filteredSnapshot.standardQueue.some((d) => Boolean(d.lanStreamUri))).toBe(true);

    // Block transfer when active song is missing on target and LAN streaming is OFF
    engine.playFromContext(missingTrack, [missingTrack], 'Discovery');
    const blocked = validateAndPreparePlaybackTransfer(
      'android-1',
      engine.toPlaybackSnapshot(),
      targetWithMp3Match,
      false
    );
    expect(blocked.allowed).toBe(false);
  });

  it('round-trips QR pairing deep links accurately', () => {
    const encoded = encodeQrPairLink({
      version: '2.2.0',
      accountId: 'user-123',
      username: 'ale_music',
      pairSecretHmac: 'secret-hex',
      pinCode: '482910',
      lanHost: '192.168.1.50',
      lanPort: 8765,
      supabaseUrl: 'https://demo.supabase.co',
      supabaseAnonKey: 'anon-key-123',
      issuedAtMs: 1700000000000
    });
    const decoded = parseQrPairInput(encoded);
    expect(decoded?.username).toBe('ale_music');
    expect(decoded?.lanHost).toBe('192.168.1.50');
    expect(decoded?.supabaseUrl).toBe('https://demo.supabase.co');
  });
});
