-- Musika Stage 2: Supabase Auth, Cross-Device Metadata Index, Playlists & Realtime Schema
-- Run this script in your Supabase Project -> SQL Editor.
-- Also make sure: Authentication -> Providers -> Email -> "Confirm email" is turned OFF
-- so users can sign up & sign in seamlessly with Username + Password.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- 1. User Profiles (maps friendly Username -> synthetic/real Auth email safely)
CREATE TABLE IF NOT EXISTS public.profiles (
    id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    username TEXT UNIQUE NOT NULL,
    auth_email TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users can view own profile" ON public.profiles;
CREATE POLICY "Users can view own profile"
    ON public.profiles FOR SELECT
    USING (auth.uid() = id);

DROP POLICY IF EXISTS "Users can update own profile" ON public.profiles;
CREATE POLICY "Users can update own profile"
    ON public.profiles FOR UPDATE
    USING (auth.uid() = id);

DROP POLICY IF EXISTS "Users can insert own profile" ON public.profiles;
CREATE POLICY "Users can insert own profile"
    ON public.profiles FOR INSERT
    WITH CHECK (auth.uid() = id);

-- 2. Account Settings (SYNCED_PLAYBACK vs INDEPENDENT_CONCURRENT + offline LAN HMAC secret)
CREATE TABLE IF NOT EXISTS public.account_settings (
    user_id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    sync_mode TEXT NOT NULL DEFAULT 'SYNCED_PLAYBACK'
        CHECK (sync_mode IN ('SYNCED_PLAYBACK', 'INDEPENDENT_CONCURRENT')),
    active_device_id TEXT,
    pair_secret_hmac TEXT NOT NULL DEFAULT encode(gen_random_bytes(32), 'hex'),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE public.account_settings ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users manage own account_settings" ON public.account_settings;
CREATE POLICY "Users manage own account_settings"
    ON public.account_settings FOR ALL
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);

-- 3. Connected Account Devices (Android, Web PWA, Electron Desktop)
CREATE TABLE IF NOT EXISTS public.devices (
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    device_id TEXT NOT NULL,
    device_name TEXT NOT NULL,
    device_type TEXT NOT NULL CHECK (device_type IN ('ANDROID', 'WEB', 'DESKTOP')),
    lan_host TEXT,
    lan_port INT,
    is_active_playing BOOLEAN NOT NULL DEFAULT false,
    monitor_local_audio BOOLEAN NOT NULL DEFAULT false,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, device_id)
);

ALTER TABLE public.devices ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users manage own devices" ON public.devices;
CREATE POLICY "Users manage own devices"
    ON public.devices FOR ALL
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);

-- 4. Per-Device Track Metadata Index (ZERO audio files uploaded — metadata keys only!)
CREATE TABLE IF NOT EXISTS public.device_tracks (
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    device_id TEXT NOT NULL,
    track_identity TEXT NOT NULL,
    normalized_key TEXT NOT NULL,
    relative_path TEXT NOT NULL DEFAULT '',
    title TEXT NOT NULL,
    artist TEXT NOT NULL,
    album TEXT NOT NULL,
    disc_number INT NOT NULL DEFAULT 1,
    track_number INT NOT NULL DEFAULT 1,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    audio_quality_json JSONB,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, device_id, track_identity)
);

CREATE INDEX IF NOT EXISTS idx_device_tracks_norm_key
    ON public.device_tracks (user_id, device_id, normalized_key);

ALTER TABLE public.device_tracks ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users manage own device_tracks" ON public.device_tracks;
CREATE POLICY "Users manage own device_tracks"
    ON public.device_tracks FOR ALL
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);

-- 5. Cross-Device Playlists (resolved locally by normalized_key on each device)
CREATE TABLE IF NOT EXISTS public.playlists (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    client_updated_at BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, name)
);

ALTER TABLE public.playlists ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users manage own playlists" ON public.playlists;
CREATE POLICY "Users manage own playlists"
    ON public.playlists FOR ALL
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);

CREATE TABLE IF NOT EXISTS public.playlist_items (
    id BIGSERIAL PRIMARY KEY,
    playlist_id UUID NOT NULL REFERENCES public.playlists(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    position_index INT NOT NULL,
    normalized_key TEXT NOT NULL,
    relative_path TEXT NOT NULL DEFAULT '',
    title TEXT NOT NULL,
    artist TEXT NOT NULL,
    album TEXT NOT NULL,
    duration_ms BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_playlist_items_playlist
    ON public.playlist_items (playlist_id, position_index);

ALTER TABLE public.playlist_items ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users manage own playlist_items" ON public.playlist_items;
CREATE POLICY "Users manage own playlist_items"
    ON public.playlist_items FOR ALL
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);

-- 6. Latest Playback Session Snapshot per Device
CREATE TABLE IF NOT EXISTS public.playback_sessions (
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    device_id TEXT NOT NULL,
    snapshot_json JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, device_id)
);

ALTER TABLE public.playback_sessions ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users manage own playback_sessions" ON public.playback_sessions;
CREATE POLICY "Users manage own playback_sessions"
    ON public.playback_sessions FOR ALL
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);

-- 7. Trigger: Provision profile + account_settings (with offline LAN HMAC secret) on signup
CREATE OR REPLACE FUNCTION public.handle_new_musika_user()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_username TEXT;
BEGIN
    v_username := COALESCE(
        NULLIF(TRIM(NEW.raw_user_meta_data->>'username'), ''),
        SPLIT_PART(NEW.email, '@', 1)
    );

    INSERT INTO public.profiles (id, username, auth_email)
    VALUES (NEW.id, LOWER(v_username), NEW.email)
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO public.account_settings (user_id)
    VALUES (NEW.id)
    ON CONFLICT (user_id) DO NOTHING;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS on_auth_user_created_musika ON auth.users;
CREATE TRIGGER on_auth_user_created_musika
    AFTER INSERT ON auth.users
    FOR EACH ROW EXECUTE FUNCTION public.handle_new_musika_user();

-- 8. RPC: Resolve a Username to its login auth_email cleanly for Username + Password login
CREATE OR REPLACE FUNCTION public.lookup_email_for_username(p_username TEXT)
RETURNS TEXT
LANGUAGE sql
SECURITY DEFINER
STABLE
SET search_path = public
AS $$
    SELECT auth_email
    FROM public.profiles
    WHERE LOWER(username) = LOWER(TRIM(p_username))
    LIMIT 1;
$$;

GRANT EXECUTE ON FUNCTION public.lookup_email_for_username(TEXT) TO anon, authenticated;

-- 9. Enable Supabase Realtime CDC for account settings, devices, playlists, and playback sessions
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_publication_tables
        WHERE pubname = 'supabase_realtime' AND schemaname = 'public' AND tablename = 'account_settings'
    ) THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.account_settings;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_publication_tables
        WHERE pubname = 'supabase_realtime' AND schemaname = 'public' AND tablename = 'devices'
    ) THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.devices;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_publication_tables
        WHERE pubname = 'supabase_realtime' AND schemaname = 'public' AND tablename = 'playlists'
    ) THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.playlists;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_publication_tables
        WHERE pubname = 'supabase_realtime' AND schemaname = 'public' AND tablename = 'playback_sessions'
    ) THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.playback_sessions;
    END IF;
END $$;
