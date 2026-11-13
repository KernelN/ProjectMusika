import {
  orderTracksForPlayAllDiscs,
  shuffleArray
} from '../core/deduplicator';
import {
  cycleLoopMode,
  cycleRandomMode,
  LoopMode,
  RandomMode,
  Track
} from '../core/models';
import {
  doesDescriptorMatchLocal,
  PlaybackSnapshot,
  RemoteTrackDescriptor,
  SyncCommand,
  trackToDescriptor
} from '../core/syncContract';

export interface EngineState {
  currentTrack?: Track;
  isPlaying: boolean;
  positionMs: number;
  durationMs: number;
  randomMode: RandomMode;
  loopMode: LoopMode;
  priorityQueue: Track[];
  standardQueue: Track[];
  shufflePool: Track[];
  activeContextName?: string;
}

export interface PlaybackDriver {
  onLoadAndPlayTrack: (track: Track, startPositionMs: number, playWhenReady: boolean) => void;
  onPlayStateChanged: (isPlaying: boolean) => void;
  onSeekTo: (positionMs: number) => void;
}

export class DualQueueEngine {
  private state: EngineState;
  private playHistory: Track[] = [];
  private listeners = new Set<(state: EngineState) => void>();
  public driver: PlaybackDriver | null = null;
  private onModesChanged?: (randomMode: RandomMode, loopMode: LoopMode) => void;

  constructor(
    initialRandomMode: RandomMode = 'OFF',
    initialLoopMode: LoopMode = 'NO_LOOP',
    onModesChanged?: (randomMode: RandomMode, loopMode: LoopMode) => void
  ) {
    this.state = {
      isPlaying: false,
      positionMs: 0,
      durationMs: 0,
      randomMode: initialRandomMode,
      loopMode: initialLoopMode,
      priorityQueue: [],
      standardQueue: [],
      shufflePool: []
    };
    this.onModesChanged = onModesChanged;
  }

  getState(): EngineState {
    return this.state;
  }

  subscribe(listener: (state: EngineState) => void): () => void {
    this.listeners.add(listener);
    listener(this.state);
    return () => this.listeners.delete(listener);
  }

  private emit(next: EngineState) {
    this.state = next;
    for (const listener of this.listeners) listener(next);
  }

  playFromContext(selectedTrack: Track, contextTracks: Track[], contextName?: string) {
    const effectiveContext =
      contextTracks.length === 0
        ? [selectedTrack]
        : contextTracks.some((t) => t.filePath === selectedTrack.filePath)
          ? contextTracks
          : [selectedTrack, ...contextTracks];

    const pool = this.buildShufflePoolWithSelectedFirst(selectedTrack, effectiveContext);
    if (this.state.currentTrack && this.state.currentTrack.filePath !== selectedTrack.filePath) {
      this.playHistory.push(this.state.currentTrack);
    }

    this.emit({
      ...this.state,
      currentTrack: selectedTrack,
      isPlaying: true,
      positionMs: 0,
      durationMs: selectedTrack.durationMs,
      standardQueue: effectiveContext,
      shufflePool: pool,
      activeContextName: contextName
    });
    this.driver?.onLoadAndPlayTrack(selectedTrack, 0, true);
  }

  playAllDiscs(allDiscTracks: Track[], albumOrFolderName: string) {
    if (allDiscTracks.length === 0) return;
    const ordered = orderTracksForPlayAllDiscs(allDiscTracks, this.state.randomMode);
    const sequential = orderTracksForPlayAllDiscs(allDiscTracks, 'OFF');
    const first = ordered[0];
    this.emit({
      ...this.state,
      currentTrack: first,
      isPlaying: true,
      positionMs: 0,
      durationMs: first.durationMs,
      standardQueue: sequential,
      shufflePool: ordered,
      activeContextName: `${albumOrFolderName} (All Discs)`
    });
    this.driver?.onLoadAndPlayTrack(first, 0, true);
  }

  enqueueImmediate(track: Track) {
    if (!this.state.currentTrack) {
      this.emit({
        ...this.state,
        currentTrack: track,
        isPlaying: true,
        positionMs: 0,
        durationMs: track.durationMs,
        standardQueue: [track],
        shufflePool: [track]
      });
      this.driver?.onLoadAndPlayTrack(track, 0, true);
      return;
    }
    this.emit({
      ...this.state,
      priorityQueue: [...this.state.priorityQueue, track]
    });
  }

  enqueueAtEnd(track: Track) {
    if (!this.state.currentTrack) {
      this.emit({
        ...this.state,
        currentTrack: track,
        isPlaying: true,
        positionMs: 0,
        durationMs: track.durationMs,
        standardQueue: [track],
        shufflePool: [track]
      });
      this.driver?.onLoadAndPlayTrack(track, 0, true);
      return;
    }
    this.emit({
      ...this.state,
      standardQueue: [...this.state.standardQueue, track],
      shufflePool: [...this.state.shufflePool, track]
    });
  }

  cycleRandomMode(): RandomMode {
    const next = cycleRandomMode(this.state.randomMode);
    this.setRandomMode(next);
    return next;
  }

  setRandomMode(mode: RandomMode) {
    const rebuiltPool =
      this.state.currentTrack && this.state.standardQueue.length > 0
        ? this.buildShufflePoolWithSelectedFirst(this.state.currentTrack, this.state.standardQueue)
        : shuffleArray(this.state.standardQueue);
    this.emit({
      ...this.state,
      randomMode: mode,
      shufflePool: rebuiltPool
    });
    this.onModesChanged?.(mode, this.state.loopMode);
  }

  cycleLoopMode(): LoopMode {
    const next = cycleLoopMode(this.state.loopMode);
    this.setLoopMode(next);
    return next;
  }

  setLoopMode(mode: LoopMode) {
    this.emit({ ...this.state, loopMode: mode });
    this.onModesChanged?.(this.state.randomMode, mode);
  }

  skipToNext(autoTriggered = false): Track | undefined {
    const active = this.state.currentTrack;
    if (!active) return undefined;

    if (this.state.loopMode === 'FILE_LOOP' && autoTriggered) {
      this.emit({ ...this.state, positionMs: 0, isPlaying: true });
      this.driver?.onSeekTo(0);
      this.driver?.onPlayStateChanged(true);
      return active;
    }

    if (this.state.priorityQueue.length > 0) {
      const [nextImmediate, ...remainingPriority] = this.state.priorityQueue;
      this.playHistory.push(active);
      this.emit({
        ...this.state,
        currentTrack: nextImmediate,
        priorityQueue: remainingPriority,
        positionMs: 0,
        durationMs: nextImmediate.durationMs,
        isPlaying: true
      });
      this.driver?.onLoadAndPlayTrack(nextImmediate, 0, true);
      return nextImmediate;
    }

    if (this.state.standardQueue.length === 0) {
      this.emit({ ...this.state, isPlaying: false, positionMs: 0 });
      this.driver?.onPlayStateChanged(false);
      return undefined;
    }

    if (this.state.randomMode === 'TRUE_RANDOM') {
      const idx = Math.floor(Math.random() * this.state.standardQueue.length);
      const nextRandom = this.state.standardQueue[idx];
      this.playHistory.push(active);
      this.emit({
        ...this.state,
        currentTrack: nextRandom,
        positionMs: 0,
        durationMs: nextRandom.durationMs,
        isPlaying: true
      });
      this.driver?.onLoadAndPlayTrack(nextRandom, 0, true);
      return nextRandom;
    }

    if (this.state.randomMode === 'SHUFFLE_POOL') {
      const pool =
        this.state.shufflePool.length > 0
          ? this.state.shufflePool
          : this.buildShufflePoolWithSelectedFirst(active, this.state.standardQueue);
      const curIdx = pool.findIndex((t) => t.filePath === active.filePath);
      const nextIdx = curIdx + 1;
      if (nextIdx >= 0 && nextIdx < pool.length) {
        const nextTrack = pool[nextIdx];
        this.playHistory.push(active);
        this.emit({
          ...this.state,
          currentTrack: nextTrack,
          shufflePool: pool,
          positionMs: 0,
          durationMs: nextTrack.durationMs,
          isPlaying: true
        });
        this.driver?.onLoadAndPlayTrack(nextTrack, 0, true);
        return nextTrack;
      }
      if (this.state.loopMode === 'QUEUE_LOOP' || this.state.loopMode === 'FILE_LOOP') {
        const reshuffled = shuffleArray(this.state.standardQueue);
        const nextTrack = reshuffled[0];
        this.playHistory.push(active);
        this.emit({
          ...this.state,
          currentTrack: nextTrack,
          shufflePool: reshuffled,
          positionMs: 0,
          durationMs: nextTrack.durationMs,
          isPlaying: true
        });
        this.driver?.onLoadAndPlayTrack(nextTrack, 0, true);
        return nextTrack;
      }
      this.emit({ ...this.state, isPlaying: false, positionMs: 0 });
      this.driver?.onPlayStateChanged(false);
      return undefined;
    }

    const curIdx = this.state.standardQueue.findIndex((t) => t.filePath === active.filePath);
    const nextIdx = curIdx + 1;
    if (nextIdx >= 0 && nextIdx < this.state.standardQueue.length) {
      const nextTrack = this.state.standardQueue[nextIdx];
      this.playHistory.push(active);
      this.emit({
        ...this.state,
        currentTrack: nextTrack,
        positionMs: 0,
        durationMs: nextTrack.durationMs,
        isPlaying: true
      });
      this.driver?.onLoadAndPlayTrack(nextTrack, 0, true);
      return nextTrack;
    }
    if (this.state.loopMode === 'QUEUE_LOOP') {
      const firstTrack = this.state.standardQueue[0];
      this.playHistory.push(active);
      this.emit({
        ...this.state,
        currentTrack: firstTrack,
        positionMs: 0,
        durationMs: firstTrack.durationMs,
        isPlaying: true
      });
      this.driver?.onLoadAndPlayTrack(firstTrack, 0, true);
      return firstTrack;
    }

    this.emit({ ...this.state, isPlaying: false, positionMs: 0 });
    this.driver?.onPlayStateChanged(false);
    return undefined;
  }

  skipToPrevious(): Track | undefined {
    const active = this.state.currentTrack;
    if (!active) return undefined;
    if (this.state.positionMs > 4000) {
      this.emit({ ...this.state, positionMs: 0, isPlaying: true });
      this.driver?.onSeekTo(0);
      this.driver?.onPlayStateChanged(true);
      return active;
    }

    const prevFromHistory = this.playHistory.pop();
    if (prevFromHistory) {
      this.emit({
        ...this.state,
        currentTrack: prevFromHistory,
        positionMs: 0,
        durationMs: prevFromHistory.durationMs,
        isPlaying: true
      });
      this.driver?.onLoadAndPlayTrack(prevFromHistory, 0, true);
      return prevFromHistory;
    }

    const idx = this.state.standardQueue.findIndex((t) => t.filePath === active.filePath);
    if (idx > 0) {
      const prev = this.state.standardQueue[idx - 1];
      this.emit({
        ...this.state,
        currentTrack: prev,
        positionMs: 0,
        durationMs: prev.durationMs,
        isPlaying: true
      });
      this.driver?.onLoadAndPlayTrack(prev, 0, true);
      return prev;
    }
    this.emit({ ...this.state, positionMs: 0 });
    this.driver?.onSeekTo(0);
    return active;
  }

  togglePlayPause() {
    if (!this.state.currentTrack) return;
    this.setPlaying(!this.state.isPlaying);
  }

  setPlaying(isPlaying: boolean) {
    if (!this.state.currentTrack) return;
    this.emit({ ...this.state, isPlaying });
    this.driver?.onPlayStateChanged(isPlaying);
  }

  seekTo(positionMs: number) {
    const clamped = Math.max(0, positionMs);
    this.emit({ ...this.state, positionMs: clamped });
    this.driver?.onSeekTo(clamped);
  }

  updateProgress(positionMs: number, durationMs: number) {
    this.emit({
      ...this.state,
      positionMs: Math.max(0, positionMs),
      durationMs: durationMs > 0 ? durationMs : this.state.durationMs
    });
  }

  isContextQueueModifiable(): boolean {
    return this.state.randomMode !== 'TRUE_RANDOM';
  }

  getVisibleContextQueue(): Track[] {
    if (this.state.randomMode === 'TRUE_RANDOM') return [];
    if (this.state.randomMode === 'SHUFFLE_POOL') {
      return this.state.shufflePool.length > 0 ? this.state.shufflePool : this.state.standardQueue;
    }
    return this.state.standardQueue;
  }

  movePriorityQueueTrack(fromIndex: number, toIndex: number) {
    if (fromIndex < 0 || toIndex < 0 || fromIndex >= this.state.priorityQueue.length || toIndex >= this.state.priorityQueue.length) {
      return;
    }
    const list = [...this.state.priorityQueue];
    const [moved] = list.splice(fromIndex, 1);
    list.splice(toIndex, 0, moved);
    this.emit({ ...this.state, priorityQueue: list });
  }

  removeFromPriorityQueue(index: number) {
    if (index < 0 || index >= this.state.priorityQueue.length) return;
    this.emit({
      ...this.state,
      priorityQueue: this.state.priorityQueue.filter((_, i) => i !== index)
    });
  }

  clearPriorityQueue() {
    this.emit({ ...this.state, priorityQueue: [] });
  }

  moveContextQueueTrack(fromIndex: number, toIndex: number) {
    if (!this.isContextQueueModifiable()) return;
    const base = [...this.getVisibleContextQueue()];
    if (fromIndex < 0 || toIndex < 0 || fromIndex >= base.length || toIndex >= base.length) return;
    const [moved] = base.splice(fromIndex, 1);
    base.splice(toIndex, 0, moved);
    if (this.state.randomMode === 'SHUFFLE_POOL') {
      this.emit({ ...this.state, shufflePool: base });
    } else {
      this.emit({ ...this.state, standardQueue: base });
    }
  }

  removeFromContextQueue(index: number) {
    if (!this.isContextQueueModifiable()) return;
    const base = [...this.getVisibleContextQueue()];
    if (index < 0 || index >= base.length) return;
    const removed = base[index];
    base.splice(index, 1);
    if (this.state.randomMode === 'SHUFFLE_POOL') {
      this.emit({
        ...this.state,
        shufflePool: base,
        standardQueue: this.state.standardQueue.filter((t) => t.filePath !== removed.filePath)
      });
    } else {
      this.emit({
        ...this.state,
        standardQueue: base,
        shufflePool: this.state.shufflePool.filter((t) => t.filePath !== removed.filePath)
      });
    }
  }

  clearContextQueue() {
    if (!this.isContextQueueModifiable()) return;
    this.emit({ ...this.state, standardQueue: [], shufflePool: [] });
  }

  exportQueueTracksForPlaylist(): Track[] {
    const result: Track[] = [];
    const seen = new Set<string>();
    const pushUnique = (t: Track) => {
      if (!seen.has(t.filePath)) {
        seen.add(t.filePath);
        result.push(t);
      }
    };
    if (this.state.currentTrack) pushUnique(this.state.currentTrack);
    this.state.priorityQueue.forEach(pushUnique);
    if (this.state.randomMode !== 'TRUE_RANDOM') {
      this.getVisibleContextQueue().forEach(pushUnique);
    }
    return result;
  }

  toPlaybackSnapshot(lanHost?: string): PlaybackSnapshot {
    return {
      version: '2.2.0',
      timestamp: Date.now(),
      isPlaying: this.state.isPlaying,
      positionMs: this.state.positionMs,
      currentTrack: this.state.currentTrack
        ? trackToDescriptor(this.state.currentTrack, lanHost)
        : undefined,
      randomMode: this.state.randomMode,
      loopMode: this.state.loopMode,
      priorityQueue: this.state.priorityQueue.map((t) => trackToDescriptor(t, lanHost)),
      standardQueue: this.state.standardQueue.map((t) => trackToDescriptor(t, lanHost)),
      activeContextName: this.state.activeContextName
    };
  }

  applySyncCommand(
    command: SyncCommand,
    localLibrary: Track[],
    allowLanStreamFallback = false,
    playAudioOutput = true
  ) {
    switch (command.type) {
      case 'Play':
        this.emit({ ...this.state, isPlaying: true });
        if (playAudioOutput) this.driver?.onPlayStateChanged(true);
        break;
      case 'Pause':
        this.emit({ ...this.state, isPlaying: false });
        if (playAudioOutput) this.driver?.onPlayStateChanged(false);
        break;
      case 'TogglePlayPause':
        this.setPlaying(!this.state.isPlaying);
        break;
      case 'SeekTo':
        this.emit({ ...this.state, positionMs: command.positionMs });
        if (playAudioOutput) this.driver?.onSeekTo(command.positionMs);
        break;
      case 'SkipNext':
        this.skipToNext(false);
        break;
      case 'SkipPrevious':
        this.skipToPrevious();
        break;
      case 'SetRandom':
        this.setRandomMode(command.mode);
        break;
      case 'SetLoop':
        this.setLoopMode(command.mode);
        break;
      case 'Enqueue': {
        const resolved = this.resolveDescriptor(command.track, localLibrary, allowLanStreamFallback);
        if (resolved) {
          if (command.immediate) this.enqueueImmediate(resolved);
          else this.enqueueAtEnd(resolved);
        }
        break;
      }
      case 'PlayNow': {
        const primary = this.resolveDescriptor(command.track, localLibrary, allowLanStreamFallback);
        const context = command.contextTracks
          .map((d) => this.resolveDescriptor(d, localLibrary, allowLanStreamFallback))
          .filter((t): t is Track => Boolean(t));
        if (primary) {
          this.playFromContext(primary, context.length > 0 ? context : [primary], 'Remote Sync');
        }
        break;
      }
      case 'StateUpdate':
      case 'TransferPlayback':
        this.applyRemoteSnapshot(command.snapshot, localLibrary, allowLanStreamFallback, playAudioOutput);
        break;
      case 'SetAccountSyncMode':
        break;
    }
  }

  applyRemoteSnapshot(
    snap: PlaybackSnapshot,
    localLibrary: Track[],
    allowLanStreamFallback = false,
    playAudioOutput = true
  ) {
    const matchedCurrent = snap.currentTrack
      ? this.resolveDescriptor(snap.currentTrack, localLibrary, allowLanStreamFallback)
      : undefined;
    const matchedPriority = snap.priorityQueue
      .map((d) => this.resolveDescriptor(d, localLibrary, allowLanStreamFallback))
      .filter((t): t is Track => Boolean(t));
    const matchedStandard = snap.standardQueue
      .map((d) => this.resolveDescriptor(d, localLibrary, allowLanStreamFallback))
      .filter((t): t is Track => Boolean(t));

    this.emit({
      ...this.state,
      currentTrack: matchedCurrent,
      isPlaying: snap.isPlaying,
      positionMs: snap.positionMs,
      durationMs: matchedCurrent?.durationMs ?? snap.currentTrack?.durationMs ?? 0,
      randomMode: snap.randomMode,
      loopMode: snap.loopMode,
      priorityQueue: matchedPriority,
      standardQueue: matchedStandard,
      shufflePool:
        matchedCurrent && matchedStandard.length > 0
          ? this.buildShufflePoolWithSelectedFirst(matchedCurrent, matchedStandard)
          : matchedStandard,
      activeContextName: snap.activeContextName
    });

    if (matchedCurrent && playAudioOutput) {
      this.driver?.onLoadAndPlayTrack(matchedCurrent, snap.positionMs, snap.isPlaying);
    } else if (!playAudioOutput) {
      this.driver?.onPlayStateChanged(false);
    }
  }

  private resolveDescriptor(
    descriptor: RemoteTrackDescriptor,
    localLibrary: Track[],
    allowLanStreamFallback: boolean
  ): Track | undefined {
    const localMatch = localLibrary.find((t) =>
      doesDescriptorMatchLocal(descriptor, t.relativePath, `${t.artist.toLowerCase()}|${t.album.toLowerCase()}|${t.title.toLowerCase()}`, t.durationMs)
    );
    if (localMatch) return localMatch;
    if (!allowLanStreamFallback || !descriptor.lanStreamUri) return undefined;

    return {
      filePath: descriptor.lanStreamUri,
      relativePath: descriptor.relativePathFromRoot,
      fileName: descriptor.title,
      fileExtension: descriptor.audioQuality?.format?.toLowerCase() ?? 'stream',
      title: descriptor.title,
      artist: descriptor.artist,
      album: descriptor.album,
      discNumber: descriptor.discNumber,
      totalDiscs: 1,
      trackNumber: descriptor.trackNumber,
      durationMs: descriptor.durationMs,
      audioQuality: descriptor.audioQuality ?? {
        format: 'STREAM',
        bitrateKbps: 320,
        sampleRateHz: 44100,
        bitDepth: 16,
        isLossless: false
      },
      parentFolderPath: '/lan-stream',
      lanStreamUri: descriptor.lanStreamUri
    };
  }

  private buildShufflePoolWithSelectedFirst(selectedTrack: Track, contextTracks: Track[]): Track[] {
    const others = shuffleArray(contextTracks.filter((t) => t.filePath !== selectedTrack.filePath));
    return [selectedTrack, ...others];
  }
}
