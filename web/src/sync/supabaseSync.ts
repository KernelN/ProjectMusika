import { createClient, RealtimeChannel, SupabaseClient } from '@supabase/supabase-js';
import { Playlist, PlaylistTrackItem, Track } from '../core/models';
import {
  AccountDeviceSyncMode,
  ConnectedDeviceNode,
  PlaybackSnapshot,
  RemoteLibraryTrackSummary,
  SyncWireEnvelope,
  trackToSummary
} from '../core/syncContract';

export interface SupabaseAuthSession {
  userId: string;
  username: string;
  accessToken: string;
  refreshToken: string;
  pairSecretHmac: string;
}

export class SupabaseSyncService {
  private client: SupabaseClient | null = null;
  private url = '';
  private anonKey = '';
  private channel: RealtimeChannel | null = null;

  configure(url: string, anonKey: string) {
    const cleanUrl = url.trim().replace(/\/+$/, '');
    const cleanKey = anonKey.trim();
    if (!cleanUrl || !cleanKey) {
      this.client = null;
      this.url = '';
      this.anonKey = '';
      return;
    }
    if (this.url === cleanUrl && this.anonKey === cleanKey && this.client) {
      return;
    }
    this.url = cleanUrl;
    this.anonKey = cleanKey;
    this.client = createClient(cleanUrl, cleanKey, {
      auth: { persistSession: true, autoRefreshToken: true }
    });
  }

  isConfigured(): boolean {
    return this.client !== null && this.url.length > 0 && this.anonKey.length > 0;
  }

  getConfig(): { url: string; anonKey: string } {
    return { url: this.url, anonKey: this.anonKey };
  }

  static toSyntheticEmail(username: string): string {
    const sanitized = username
      .trim()
      .toLowerCase()
      .replace(/[^a-z0-9._-]/g, '_');
    return `${sanitized || 'user'}@musika-player.org`;
  }

  async signUpWithUsername(username: string, password: string): Promise<SupabaseAuthSession> {
    if (!this.client) throw new Error('Supabase URL & Anon Key are not configured yet.');
    const cleanUser = username.trim();
    if (cleanUser.length < 2) throw new Error('Username must be at least 2 characters.');
    if (password.length < 6) throw new Error('Password must be at least 6 characters.');

    const email = SupabaseSyncService.toSyntheticEmail(cleanUser);
    const { data, error } = await this.client.auth.signUp({
      email,
      password,
      options: { data: { username: cleanUser } }
    });
    if (error) throw error;
    const session = data.session;
    const user = data.user;
    if (!user) throw new Error('Account created; please sign in.');

    const pairSecret = await this.fetchOrInitPairSecret(user.id);
    return {
      userId: user.id,
      username: cleanUser,
      accessToken: session?.access_token ?? '',
      refreshToken: session?.refresh_token ?? '',
      pairSecretHmac: pairSecret
    };
  }

  async signInWithUsername(username: string, password: string): Promise<SupabaseAuthSession> {
    if (!this.client) throw new Error('Supabase URL & Anon Key are not configured yet.');
    const cleanUser = username.trim();
    let email = SupabaseSyncService.toSyntheticEmail(cleanUser);

    try {
      const { data: rpcEmail } = await this.client.rpc('lookup_email_for_username', {
        p_username: cleanUser
      });
      if (typeof rpcEmail === 'string' && rpcEmail.includes('@')) {
        email = rpcEmail;
      }
    } catch {
      // Fallback to synthetic email if RPC is absent
    }

    const { data, error } = await this.client.auth.signInWithPassword({ email, password });
    if (error) throw error;
    if (!data.session || !data.user) throw new Error('Invalid credentials.');

    const pairSecret = await this.fetchOrInitPairSecret(data.user.id);
    return {
      userId: data.user.id,
      username: cleanUser,
      accessToken: data.session.access_token,
      refreshToken: data.session.refresh_token,
      pairSecretHmac: pairSecret
    };
  }

  async restoreSessionWithRefreshToken(refreshToken: string, username: string): Promise<SupabaseAuthSession | null> {
    if (!this.client || !refreshToken) return null;
    try {
      const { data, error } = await this.client.auth.refreshSession({ refresh_token: refreshToken });
      if (error || !data.session || !data.user) return null;
      const pairSecret = await this.fetchOrInitPairSecret(data.user.id);
      return {
        userId: data.user.id,
        username,
        accessToken: data.session.access_token,
        refreshToken: data.session.refresh_token,
        pairSecretHmac: pairSecret
      };
    } catch {
      return null;
    }
  }

  private async fetchOrInitPairSecret(userId: string): Promise<string> {
    if (!this.client) return '';
    try {
      const { data } = await this.client
        .from('account_settings')
        .select('pair_secret_hmac')
        .eq('user_id', userId)
        .maybeSingle();
      if (data?.pair_secret_hmac) return String(data.pair_secret_hmac);
    } catch {
      // Ignore if row hasn't populated yet
    }
    return '';
  }

  async connectRealtime(
    userId: string,
    onEnvelope: (env: SyncWireEnvelope) => void,
    onPostgresChange: () => void,
    onStatusChange: (connected: boolean) => void
  ) {
    if (!this.client) {
      onStatusChange(false);
      return;
    }
    if (this.channel) {
      await this.client.removeChannel(this.channel);
      this.channel = null;
    }

    const ch = this.client.channel(`musika:account:${userId}`, {
      config: { broadcast: { self: false } }
    });

    ch.on('broadcast', { event: 'musika_sync' }, ({ payload }) => {
      if (payload && typeof payload === 'object') {
        onEnvelope(payload as SyncWireEnvelope);
      }
    });

    ch.on(
      'postgres_changes',
      { event: '*', schema: 'public', table: 'account_settings', filter: `user_id=eq.${userId}` },
      () => onPostgresChange()
    );
    ch.on(
      'postgres_changes',
      { event: '*', schema: 'public', table: 'devices', filter: `user_id=eq.${userId}` },
      () => onPostgresChange()
    );
    ch.on(
      'postgres_changes',
      { event: '*', schema: 'public', table: 'playlists', filter: `user_id=eq.${userId}` },
      () => onPostgresChange()
    );
    ch.on(
      'postgres_changes',
      { event: '*', schema: 'public', table: 'playback_sessions', filter: `user_id=eq.${userId}` },
      () => onPostgresChange()
    );

    ch.subscribe((status) => {
      onStatusChange(status === 'SUBSCRIBED');
    });
    this.channel = ch;
  }

  async disconnectRealtime() {
    if (this.client && this.channel) {
      await this.client.removeChannel(this.channel);
      this.channel = null;
    }
  }

  async broadcastEnvelope(envelope: SyncWireEnvelope): Promise<boolean> {
    if (!this.channel) return false;
    const res = await this.channel.send({
      type: 'broadcast',
      event: 'musika_sync',
      payload: envelope
    });
    return res === 'ok';
  }

  async registerDeviceAndSyncMode(
    userId: string,
    deviceId: string,
    deviceName: string,
    deviceType: 'ANDROID' | 'WEB' | 'DESKTOP',
    lanHost: string | undefined,
    lanPort: number | undefined,
    isActivePlaying: boolean,
    monitorLocalAudio: boolean
  ) {
    if (!this.client) return;
    await this.client.from('devices').upsert({
      user_id: userId,
      device_id: deviceId,
      device_name: deviceName,
      device_type: deviceType,
      lan_host: lanHost ?? null,
      lan_port: lanPort ?? null,
      is_active_playing: isActivePlaying,
      monitor_local_audio: monitorLocalAudio,
      last_seen_at: new Date().toISOString()
    });
  }

  async updateAccountSettings(
    userId: string,
    syncMode: AccountDeviceSyncMode,
    activeDeviceId: string
  ) {
    if (!this.client) return;
    await this.client.from('account_settings').upsert({
      user_id: userId,
      sync_mode: syncMode,
      active_device_id: activeDeviceId,
      updated_at: new Date().toISOString()
    });
  }

  async syncDeviceTracks(userId: string, deviceId: string, tracks: Track[]) {
    if (!this.client) return;
    await this.client.from('device_tracks').delete().eq('user_id', userId).eq('device_id', deviceId);
    const summaries = tracks.map(trackToSummary);
    const chunkSize = 200;
    for (let i = 0; i < summaries.length; i += chunkSize) {
      const rows = summaries.slice(i, i + chunkSize).map((s) => ({
        user_id: userId,
        device_id: deviceId,
        track_identity: s.trackIdentity,
        normalized_key: s.normalizedKey,
        relative_path: s.relativePath,
        title: s.title,
        artist: s.artist,
        album: s.album,
        disc_number: s.discNumber,
        track_number: s.trackNumber,
        duration_ms: s.durationMs,
        audio_quality_json: s.audioQuality ?? null
      }));
      await this.client.from('device_tracks').upsert(rows);
    }
  }

  async savePlaybackSession(userId: string, deviceId: string, snapshot: PlaybackSnapshot) {
    if (!this.client) return;
    await this.client.from('playback_sessions').upsert({
      user_id: userId,
      device_id: deviceId,
      snapshot_json: snapshot,
      updated_at: new Date().toISOString()
    });
  }

  async upsertPlaylist(userId: string, playlist: Playlist) {
    if (!this.client) return;
    const { data: upserted } = await this.client
      .from('playlists')
      .upsert(
        {
          user_id: userId,
          name: playlist.name,
          client_updated_at: playlist.updatedAt,
          updated_at: new Date().toISOString()
        },
        { onConflict: 'user_id,name' }
      )
      .select('id')
      .single();

    if (!upserted?.id) return;
    await this.client.from('playlist_items').delete().eq('playlist_id', upserted.id);
    if (playlist.items.length > 0) {
      await this.client.from('playlist_items').insert(
        playlist.items.map((item, idx) => ({
          playlist_id: upserted.id,
          user_id: userId,
          position_index: idx,
          normalized_key: item.normalizedKey,
          relative_path: item.relativePath,
          title: item.title,
          artist: item.artist,
          album: item.album,
          duration_ms: item.durationMs
        }))
      );
    }
  }

  async deletePlaylistRemote(userId: string, playlistName: string) {
    if (!this.client) return;
    await this.client.from('playlists').delete().eq('user_id', userId).eq('name', playlistName);
  }

  async fetchFullAccountState(
    userId: string,
    localDeviceId: string
  ): Promise<{
    syncMode?: AccountDeviceSyncMode;
    activeDeviceId?: string;
    devices: ConnectedDeviceNode[];
    playlists: Playlist[];
  }> {
    if (!this.client) return { devices: [], playlists: [] };

    const [settingsRes, devicesRes, tracksRes, sessionsRes, playlistsRes, itemsRes] =
      await Promise.all([
        this.client.from('account_settings').select('*').eq('user_id', userId).maybeSingle(),
        this.client.from('devices').select('*').eq('user_id', userId),
        this.client.from('device_tracks').select('*').eq('user_id', userId),
        this.client.from('playback_sessions').select('*').eq('user_id', userId),
        this.client.from('playlists').select('*').eq('user_id', userId),
        this.client.from('playlist_items').select('*').eq('user_id', userId)
      ]);

    const tracksByDevice = new Map<string, RemoteLibraryTrackSummary[]>();
    for (const row of tracksRes.data ?? []) {
      const list = tracksByDevice.get(row.device_id) ?? [];
      list.push({
        trackIdentity: row.track_identity,
        normalizedKey: row.normalized_key,
        relativePath: row.relative_path ?? '',
        title: row.title,
        artist: row.artist,
        album: row.album,
        discNumber: row.disc_number ?? 1,
        trackNumber: row.track_number ?? 1,
        durationMs: row.duration_ms ?? 0,
        audioQuality: row.audio_quality_json ?? undefined
      });
      tracksByDevice.set(row.device_id, list);
    }

    const sessionByDevice = new Map<string, PlaybackSnapshot>();
    for (const sRow of sessionsRes.data ?? []) {
      if (sRow.snapshot_json) {
        sessionByDevice.set(sRow.device_id, sRow.snapshot_json as PlaybackSnapshot);
      }
    }

    const devices: ConnectedDeviceNode[] = (devicesRes.data ?? []).map((d) => {
      const cat = tracksByDevice.get(d.device_id) ?? [];
      return {
        deviceId: d.device_id,
        deviceName: d.device_name,
        deviceType: d.device_type,
        accountId: userId,
        isThisDevice: d.device_id === localDeviceId,
        isActivePlayingDevice: Boolean(d.is_active_playing),
        monitorLocalAudio: Boolean(d.monitor_local_audio),
        lanHost: d.lan_host ?? undefined,
        lanPort: d.lan_port ?? undefined,
        lastSnapshot: sessionByDevice.get(d.device_id),
        loadedTrackKeys: cat.map((t) => t.normalizedKey.toLowerCase()),
        loadedRelativePaths: cat.map((t) => t.relativePath).filter(Boolean),
        catalogTracks: cat
      };
    });

    const itemsByPlaylist = new Map<string, PlaylistTrackItem[]>();
    for (const item of itemsRes.data ?? []) {
      const list = itemsByPlaylist.get(item.playlist_id) ?? [];
      list.push({
        positionIndex: item.position_index,
        normalizedKey: item.normalized_key,
        relativePath: item.relative_path ?? '',
        title: item.title,
        artist: item.artist,
        album: item.album,
        durationMs: item.duration_ms ?? 0
      });
      itemsByPlaylist.set(item.playlist_id, list);
    }

    const playlists: Playlist[] = (playlistsRes.data ?? []).map((pl) => ({
      id: pl.id,
      name: pl.name,
      createdAt: new Date(pl.created_at).getTime(),
      updatedAt: Number(pl.client_updated_at || Date.now()),
      items: (itemsByPlaylist.get(pl.id) ?? []).sort((a, b) => a.positionIndex - b.positionIndex)
    }));

    return {
      syncMode: settingsRes.data?.sync_mode as AccountDeviceSyncMode | undefined,
      activeDeviceId: settingsRes.data?.active_device_id ?? undefined,
      devices,
      playlists
    };
  }
}
