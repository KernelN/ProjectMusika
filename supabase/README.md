# Musika Stage 2 — Supabase Online Backend Setup

Musika uses **LAN-first direct WebSockets (`_musika._tcp` via mDNS/NSD)** whenever devices are on the same Wi-Fi, and automatically fails over to **Supabase Auth + Realtime + Postgres** when away from home or across different networks.

No custom backend VM is needed, and **zero audio files are uploaded** — only compact track metadata keys (`normalized_key`, `title`, `artist`, `album`, `duration_ms`), synced playlists, and live playback snapshots are stored under strict Row Level Security (RLS).

---

## 2-Minute Setup

1. Create a free project at [supabase.com](https://supabase.com).
2. In your Supabase Dashboard, go to **Authentication → Providers → Email**:
   - **Disable "Confirm email"** so users can sign up and sign in seamlessly using a simple **Username + Password** with zero email verification prompts.
3. Open **SQL Editor**, paste the entire contents of [`schema.sql`](./schema.sql), and click **Run**.
4. Copy your **Project URL** (`https://xxxxx.supabase.co`) and **Publishable / Anon Key** from **Project Settings → API**.

---

## Configuring Musika (Choose Any Method)

### Option A: Runtime Settings + QR Auto-Share (Easiest!)
1. Open the **Devices / Online Control** sheet or **Settings** in either Musika Android or Musika Web/Desktop.
2. Paste your **Supabase URL** and **Anon Key** once and sign in with your Username + Password.
3. Generate a **QR Pair Code** on that device and scan/paste it on your other devices — Supabase config + account pairing transfers automatically!

### Option B: Build-Time Injection for Release APKs & Web/Desktop Bundles
- **Android (`local.properties` or environment variables — gitignored):**
  ```properties
  SUPABASE_URL=https://your-project.supabase.co
  SUPABASE_ANON_KEY=your-anon-key
  ```
- **Web / Electron (`web/.env.local` — gitignored):**
  ```env
  VITE_SUPABASE_URL=https://your-project.supabase.co
  VITE_SUPABASE_ANON_KEY=your-anon-key
  ```

Official release builds compiled with these variables connect online out of the box with zero setup required for end users.
