import React, { useEffect, useState } from 'react';
import QRCode from 'qrcode';
import {
  CheckCircle2,
  Copy,
  FolderOpen,
  Globe,
  KeyRound,
  Laptop,
  LogOut,
  MonitorSpeaker,
  Pause,
  Play,
  QrCode,
  Radio,
  RefreshCw,
  ShieldAlert,
  SkipBack,
  SkipForward,
  Sliders,
  Smartphone,
  Volume2,
  Wifi,
  WifiOff,
  X
} from 'lucide-react';
import { ARTWORK_STYLE_LABELS, NowPlayingArtworkStyle } from '../core/models';
import { encodeQrPairLink } from '../core/syncContract';
import { supabaseService, transportCoordinator, useMusikaStore } from '../store/useMusikaStore';

export const ConnectModal: React.FC = () => {
  const {
    showConnectModal,
    setShowConnectModal,
    onlineState,
    signUpUsername,
    signInUsername,
    signOutAccount,
    pairViaQrOrPin,
    connectManualLanHost,
    setAccountSyncMode,
    setListenLocallyInSynced,
    setBrowsingDeviceId,
    sendRemoteCommand,
    transferPlaybackToDevice,
    setSelectedTab
  } = useMusikaStore();

  const [authMode, setAuthMode] = useState<'signin' | 'signup'>('signin');
  const [username, setUsername] = useState(onlineState.username === 'MusikaUser' ? '' : onlineState.username);
  const [password, setPassword] = useState('');
  const [pairInput, setPairInput] = useState('');
  const [manualHost, setManualHost] = useState('');
  const [manualPort, setManualPort] = useState('8765');
  const [qrDataUrl, setQrDataUrl] = useState<string>('');
  const [qrLinkText, setQrLinkText] = useState<string>('');
  const [busyMsg, setBusyMsg] = useState<string | null>(null);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  useEffect(() => {
    if (!showConnectModal) return;
    let cancelled = false;
    void (async () => {
      try {
        const payload = await transportCoordinator.generateQrPayload();
        const link = encodeQrPairLink(payload);
        const url = await QRCode.toDataURL(link, {
          width: 220,
          margin: 1,
          color: { dark: '#e2e8f0', light: '#0b0e14' }
        });
        if (!cancelled) {
          setQrLinkText(link);
          setQrDataUrl(url);
        }
      } catch {
        // Ignore QR canvas errors
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [showConnectModal, onlineState.accountId, onlineState.username, onlineState.activePinCode]);

  if (!showConnectModal) return null;

  const isSignedIn = onlineState.accountId !== 'local-guest';
  const isSupabaseConfigured = supabaseService.isConfigured();

  const handleAuth = async (e: React.FormEvent) => {
    e.preventDefault();
    setErrorMsg(null);
    setBusyMsg(authMode === 'signup' ? 'Creating account...' : 'Signing in...');
    try {
      if (authMode === 'signup') {
        await signUpUsername(username, password);
      } else {
        await signInUsername(username, password);
      }
      setPassword('');
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : String(err));
    } finally {
      setBusyMsg(null);
    }
  };

  const handlePair = async (e: React.FormEvent) => {
    e.preventDefault();
    setErrorMsg(null);
    setBusyMsg('Pairing...');
    try {
      await pairViaQrOrPin(pairInput, manualHost || undefined);
      setPairInput('');
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : String(err));
    } finally {
      setBusyMsg(null);
    }
  };

  const handleLanProbe = async () => {
    if (!manualHost.trim()) {
      setErrorMsg('Enter a LAN IP address or hostname (e.g. 192.168.1.42).');
      return;
    }
    setErrorMsg(null);
    setBusyMsg(`Probing ws://${manualHost.trim()}:${manualPort}...`);
    try {
      await connectManualLanHost(manualHost.trim(), Number.parseInt(manualPort, 10) || 8765);
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : String(err));
    } finally {
      setBusyMsg(null);
    }
  };

  const DeviceIcon = ({ type }: { type: string }) => {
    if (type === 'ANDROID') return <Smartphone className="h-4 w-4 text-winampGreen" />;
    if (type === 'DESKTOP') return <Laptop className="h-4 w-4 text-samsungBlue" />;
    return <Globe className="h-4 w-4 text-winampAmber" />;
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/80 p-3 backdrop-blur-sm">
      <div className="flex max-h-[92vh] w-full max-w-2xl flex-col overflow-hidden rounded-2xl border border-slate-700 bg-surfaceCard shadow-2xl">
        <div className="flex items-center justify-between border-b border-slate-800 px-4 py-3">
          <div>
            <h2 className="flex items-center gap-2 text-base font-bold text-white">
              {onlineState.transportMode === 'LAN_DIRECT' ? (
                <Wifi className="h-4 w-4 text-winampGreen" />
              ) : onlineState.transportMode === 'ONLINE_SUPABASE' ? (
                <Globe className="h-4 w-4 text-samsungBlue" />
              ) : (
                <WifiOff className="h-4 w-4 text-slate-400" />
              )}
              Musika Connect — LAN-First + Online Sync
            </h2>
            <p className="font-mono text-[11px] text-slate-400">
              Transport: {onlineState.transportMode} | Device: {onlineState.localDeviceName}
            </p>
          </div>
          <button
            onClick={() => setShowConnectModal(false)}
            className="rounded-lg p-1.5 text-slate-400 hover:bg-slate-800 hover:text-white"
          >
            <X className="h-5 w-5" />
          </button>
        </div>

        <div className="flex-1 space-y-4 overflow-y-auto p-4 text-sm">
          {(errorMsg || busyMsg || onlineState.statusMessage) && (
            <div
              className={`rounded-xl border px-3 py-2 text-xs ${
                errorMsg
                  ? 'border-rose-500/60 bg-rose-950/50 text-rose-200'
                  : 'border-slate-700 bg-slate-900/90 text-slate-200'
              }`}
            >
              {errorMsg || busyMsg || onlineState.statusMessage}
            </div>
          )}

          {/* Account Sync Mode & Anti-Echo Monitor */}
          <div className="rounded-xl border border-slate-800 bg-slate-900/70 p-3 space-y-3">
            <div className="flex items-center justify-between">
              <span className="text-xs font-bold uppercase tracking-wider text-winampAmber">
                Multi-Device Playback Mode
              </span>
              <span className="font-mono text-[11px] text-slate-400">
                Account: {onlineState.username}
              </span>
            </div>
            <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
              <button
                onClick={() => void setAccountSyncMode('SYNCED_PLAYBACK')}
                className={`rounded-xl border p-2.5 text-left transition ${
                  onlineState.syncMode === 'SYNCED_PLAYBACK'
                    ? 'border-winampGreen bg-winampGreen/10 text-white'
                    : 'border-slate-700 bg-slate-950/60 text-slate-300 hover:border-slate-500'
                }`}
              >
                <div className="font-semibold text-xs">Synced Playback</div>
                <div className="mt-0.5 text-[11px] text-slate-400">
                  Mirror Now Playing, progress & queue; one speaker outputs by default.
                </div>
              </button>
              <button
                onClick={() => void setAccountSyncMode('INDEPENDENT_CONCURRENT')}
                className={`rounded-xl border p-2.5 text-left transition ${
                  onlineState.syncMode === 'INDEPENDENT_CONCURRENT'
                    ? 'border-samsungBlue bg-samsungBlue/10 text-white'
                    : 'border-slate-700 bg-slate-950/60 text-slate-300 hover:border-slate-500'
                }`}
              >
                <div className="font-semibold text-xs">Independent Concurrent</div>
                <div className="mt-0.5 text-[11px] text-slate-400">
                  Play different tracks simultaneously while still controlling peers.
                </div>
              </button>
            </div>

            {onlineState.syncMode === 'SYNCED_PLAYBACK' && (
              <label className="flex cursor-pointer items-center justify-between rounded-lg border border-slate-800 bg-black/30 px-3 py-2 text-xs">
                <div className="flex items-center gap-2 pr-2">
                  <Volume2 className="h-4 w-4 text-winampAmber shrink-0" />
                  <div>
                    <div className="font-medium text-slate-200">Play Audio Here Too (Monitor Mode)</div>
                    <div className="text-[11px] text-slate-400">
                      Off by default to avoid double-speaker echo when controlling a remote player.
                    </div>
                  </div>
                </div>
                <input
                  type="checkbox"
                  checked={onlineState.listenLocallyWhenSyncedTargetIsRemote}
                  onChange={(e) => setListenLocallyInSynced(e.target.checked)}
                  className="h-4 w-4 accent-winampGreen"
                />
              </label>
            )}
          </div>

          {/* Connected Devices & Transfer Playback */}
          <div className="rounded-xl border border-slate-800 bg-slate-900/70 p-3 space-y-2.5">
            <div className="flex items-center justify-between">
              <span className="text-xs font-bold uppercase tracking-wider text-winampGreen">
                Account Devices ({onlineState.connectedDevices.length})
              </span>
              <span className="text-[11px] text-slate-400">
                Browsing:{' '}
                <strong className="text-white">
                  {onlineState.connectedDevices.find((d) => d.deviceId === onlineState.browsingDeviceId)
                    ?.deviceName ?? 'Local'}
                </strong>
              </span>
            </div>

            <div className="space-y-2">
              {onlineState.connectedDevices.map((dev) => {
                const isSelf = dev.deviceId === onlineState.localDeviceId;
                const isBrowsingThis = dev.deviceId === onlineState.browsingDeviceId;
                const activeTrack = dev.lastSnapshot?.currentTrack;
                const localCatalogCount = dev.catalogTracks?.length ?? dev.loadedTrackKeys.length;
                return (
                  <div
                    key={dev.deviceId}
                    className={`rounded-xl border p-3 ${
                      dev.isActivePlayingDevice
                        ? 'border-winampGreen/60 bg-winampGreen/5'
                        : 'border-slate-800 bg-slate-950/60'
                    }`}
                  >
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <div className="flex items-center gap-2">
                        <DeviceIcon type={dev.deviceType} />
                        <div>
                          <div className="flex items-center gap-1.5 text-xs font-bold text-white">
                            {dev.deviceName}
                            {isSelf && (
                              <span className="rounded bg-slate-700 px-1.5 py-0.5 text-[10px] text-slate-200">
                                THIS DEVICE
                              </span>
                            )}
                            {dev.isActivePlayingDevice && (
                              <span className="rounded bg-winampGreen/20 px-1.5 py-0.5 text-[10px] text-winampGreen">
                                ACTIVE AUDIO
                              </span>
                            )}
                          </div>
                          <div className="font-mono text-[10px] text-slate-400">
                            {dev.isThisDevice || dev.lanHost || dev.lastSnapshot ? 'Online' : 'Standby'} | {localCatalogCount} indexed tracks
                            {dev.lanHost ? ` | LAN ${dev.lanHost}:${dev.lanPort ?? 8765}` : ''}
                          </div>
                        </div>
                      </div>

                      <div className="flex flex-wrap items-center gap-1.5">
                        <button
                          onClick={() => {
                            setBrowsingDeviceId(dev.deviceId);
                            setSelectedTab('SONGS');
                            setShowConnectModal(false);
                          }}
                          className={`rounded-lg border px-2 py-1 text-[11px] font-medium ${
                            isBrowsingThis
                              ? 'border-samsungBlue bg-samsungBlue/20 text-blue-200'
                              : 'border-slate-700 bg-slate-900 text-slate-300 hover:border-slate-500'
                          }`}
                        >
                          {isBrowsingThis ? 'Browsing Library' : 'Browse Library'}
                        </button>
                        {!isSelf && (
                          <button
                            onClick={() => void transferPlaybackToDevice(dev.deviceId)}
                            className="flex items-center gap-1 rounded-lg bg-samsungBlue px-2.5 py-1 text-[11px] font-semibold text-white hover:bg-blue-500"
                            title="Strict metadata-matched Move Music Here"
                          >
                            <MonitorSpeaker className="h-3.5 w-3.5" />
                            Move Here -&gt; Peer
                          </button>
                        )}
                        {isSelf && onlineState.activeControllingDeviceId !== onlineState.localDeviceId && (
                          <button
                            onClick={() => void transferPlaybackToDevice(dev.deviceId)}
                            className="flex items-center gap-1 rounded-lg bg-winampGreen px-2.5 py-1 text-[11px] font-bold text-black hover:bg-emerald-400"
                          >
                            <Radio className="h-3.5 w-3.5" />
                            Move Music Here
                          </button>
                        )}
                      </div>
                    </div>

                    {!isSelf && (
                      <div className="mt-2 flex items-center justify-between gap-2 border-t border-slate-800/80 pt-2">
                        <div className="min-w-0 truncate font-mono text-[11px] text-slate-300">
                          {activeTrack
                            ? `${dev.lastSnapshot?.isPlaying ? '▶' : '❚❚'} ${activeTrack.artist} — ${activeTrack.title}`
                            : 'Idle — tap Browse Library or send Play command'}
                        </div>
                        <div className="flex items-center gap-1 shrink-0">
                          <button
                            onClick={() => void sendRemoteCommand({ type: 'SkipPrevious' }, dev.deviceId)}
                            className="rounded bg-slate-800 p-1.5 text-slate-200 hover:bg-slate-700"
                          >
                            <SkipBack className="h-3.5 w-3.5" />
                          </button>
                          <button
                            onClick={() => void sendRemoteCommand({ type: 'TogglePlayPause' }, dev.deviceId)}
                            className="rounded bg-slate-800 p-1.5 text-winampGreen hover:bg-slate-700"
                          >
                            {dev.lastSnapshot?.isPlaying ? (
                              <Pause className="h-3.5 w-3.5" />
                            ) : (
                              <Play className="h-3.5 w-3.5" />
                            )}
                          </button>
                          <button
                            onClick={() => void sendRemoteCommand({ type: 'SkipNext' }, dev.deviceId)}
                            className="rounded bg-slate-800 p-1.5 text-slate-200 hover:bg-slate-700"
                          >
                            <SkipForward className="h-3.5 w-3.5" />
                          </button>
                        </div>
                      </div>
                    )}
                  </div>
                );
              })}
            </div>

            {onlineState.lastTransferResult && (
              <div
                className={`flex items-start gap-2 rounded-lg border px-2.5 py-2 text-xs ${
                  onlineState.lastTransferResult.allowed
                    ? 'border-winampGreen/40 bg-winampGreen/10 text-emerald-200'
                    : 'border-amber-500/40 bg-amber-950/40 text-amber-200'
                }`}
              >
                {onlineState.lastTransferResult.allowed ? (
                  <CheckCircle2 className="h-4 w-4 shrink-0 text-winampGreen" />
                ) : (
                  <ShieldAlert className="h-4 w-4 shrink-0 text-amber-400" />
                )}
                <span>{onlineState.lastTransferResult.reason}</span>
              </div>
            )}
          </div>

          {/* Direct LAN Probe + QR / PIN Pairing */}
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <div className="rounded-xl border border-slate-800 bg-slate-900/70 p-3 space-y-2.5">
              <div className="flex items-center gap-1.5 text-xs font-bold text-slate-200">
                <QrCode className="h-4 w-4 text-winampAmber" />
                Instant LAN / QR Pairing
              </div>
              <div className="flex items-center gap-3">
                {qrDataUrl ? (
                  <img
                    src={qrDataUrl}
                    alt="Musika Pair QR"
                    className="h-28 w-28 rounded-lg border border-slate-700 bg-black p-1"
                  />
                ) : (
                  <div className="flex h-28 w-28 items-center justify-center rounded-lg border border-slate-800 text-[11px] text-slate-500">
                    Generating QR...
                  </div>
                )}
                <div className="space-y-1.5 text-xs">
                  <div className="text-slate-400">6-Digit LAN PIN:</div>
                  <div className="font-mono text-lg font-bold tracking-widest text-winampGreen">
                    {onlineState.activePinCode}
                  </div>
                  <button
                    onClick={() => {
                      if (qrLinkText) void navigator.clipboard?.writeText(qrLinkText);
                      setBusyMsg('Copied musika://pair link to clipboard');
                    }}
                    className="flex items-center gap-1 rounded border border-slate-700 bg-slate-800 px-2 py-1 text-[11px] text-slate-200 hover:bg-slate-700"
                  >
                    <Copy className="h-3 w-3" />
                    Copy QR Link
                  </button>
                </div>
              </div>

              <form onSubmit={handlePair} className="space-y-1.5 pt-1">
                <input
                  value={pairInput}
                  onChange={(e) => setPairInput(e.target.value)}
                  placeholder="Paste musika://pair link or 6-digit PIN"
                  className="w-full rounded-lg border border-slate-700 bg-black/50 px-2.5 py-1.5 text-xs text-white focus:border-samsungBlue focus:outline-none"
                />
                <div className="flex gap-1.5">
                  <input
                    value={manualHost}
                    onChange={(e) => setManualHost(e.target.value)}
                    placeholder="Peer LAN IP (e.g. 192.168.1.42)"
                    className="min-w-0 flex-1 rounded-lg border border-slate-700 bg-black/50 px-2.5 py-1.5 text-xs text-white focus:border-samsungBlue focus:outline-none"
                  />
                  <input
                    value={manualPort}
                    onChange={(e) => setManualPort(e.target.value)}
                    className="w-16 rounded-lg border border-slate-700 bg-black/50 px-2 py-1.5 font-mono text-xs text-white"
                  />
                </div>
                <div className="flex gap-1.5">
                  <button
                    type="submit"
                    className="flex-1 rounded-lg bg-winampGreen px-2.5 py-1.5 text-xs font-bold text-black hover:bg-emerald-400"
                  >
                    Pair QR / PIN
                  </button>
                  <button
                    type="button"
                    onClick={() => void handleLanProbe()}
                    className="flex items-center gap-1 rounded-lg border border-slate-700 bg-slate-800 px-2.5 py-1.5 text-xs text-slate-200 hover:bg-slate-700"
                  >
                    <RefreshCw className="h-3 w-3" />
                    Probe LAN
                  </button>
                </div>
              </form>
            </div>

            {/* Supabase Account Sign In / Sign Up */}
            <div className="rounded-xl border border-slate-800 bg-slate-900/70 p-3 space-y-2.5">
              <div className="flex items-center justify-between">
                <span className="flex items-center gap-1.5 text-xs font-bold text-slate-200">
                  <KeyRound className="h-4 w-4 text-samsungBlue" />
                  Online Account (Username)
                </span>
                <span
                  className={`font-mono text-[10px] ${
                    isSupabaseConfigured ? 'text-winampGreen' : 'text-amber-400'
                  }`}
                >
                  {isSupabaseConfigured ? 'Supabase Ready' : 'No Supabase Key'}
                </span>
              </div>

              {isSignedIn ? (
                <div className="space-y-3 rounded-xl border border-slate-800 bg-black/30 p-3">
                  <div className="text-xs text-slate-300">
                    Signed in as <strong className="text-white">{onlineState.username}</strong>
                  </div>
                  <div className="font-mono text-[10px] text-slate-400">
                    ID: {onlineState.accountId}
                  </div>
                  <button
                    onClick={() => void signOutAccount()}
                    className="flex w-full items-center justify-center gap-1.5 rounded-lg border border-rose-500/40 bg-rose-950/40 px-3 py-1.5 text-xs font-semibold text-rose-200 hover:bg-rose-900/50"
                  >
                    <LogOut className="h-3.5 w-3.5" />
                    Sign Out
                  </button>
                </div>
              ) : (
                <form onSubmit={handleAuth} className="space-y-2">
                  <div className="flex rounded-lg border border-slate-800 bg-black/40 p-0.5 text-xs">
                    <button
                      type="button"
                      onClick={() => setAuthMode('signin')}
                      className={`flex-1 rounded-md py-1 font-medium ${
                        authMode === 'signin' ? 'bg-samsungBlue text-white' : 'text-slate-400'
                      }`}
                    >
                      Sign In
                    </button>
                    <button
                      type="button"
                      onClick={() => setAuthMode('signup')}
                      className={`flex-1 rounded-md py-1 font-medium ${
                        authMode === 'signup' ? 'bg-samsungBlue text-white' : 'text-slate-400'
                      }`}
                    >
                      Create Account
                    </button>
                  </div>
                  <input
                    value={username}
                    onChange={(e) => setUsername(e.target.value)}
                    placeholder="Username (letters/numbers)"
                    required
                    className="w-full rounded-lg border border-slate-700 bg-black/50 px-2.5 py-1.5 text-xs text-white focus:border-samsungBlue focus:outline-none"
                  />
                  <input
                    type="password"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    placeholder="Password (min 6 chars)"
                    required
                    minLength={6}
                    className="w-full rounded-lg border border-slate-700 bg-black/50 px-2.5 py-1.5 text-xs text-white focus:border-samsungBlue focus:outline-none"
                  />
                  <button
                    type="submit"
                    disabled={!isSupabaseConfigured}
                    className="w-full rounded-lg bg-samsungBlue px-3 py-1.5 text-xs font-bold text-white hover:bg-blue-500 disabled:cursor-not-allowed disabled:opacity-40"
                  >
                    {authMode === 'signin' ? 'Sign In Online' : 'Register & Sync'}
                  </button>
                  {!isSupabaseConfigured && (
                    <p className="text-[10px] text-amber-300">
                      Configure Supabase URL + Anon Key in Settings (or scan a QR code from a configured device). LAN-direct pairing still works offline!
                    </p>
                  )}
                </form>
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};

export const SettingsModal: React.FC = () => {
  const {
    showSettingsModal,
    setShowSettingsModal,
    rootPath,
    rawTracks,
    hudScale,
    setHudScale,
    artworkStyle,
    setArtworkStyle,
    showListThumbnails,
    setShowListThumbnails,
    onlineState,
    setAllowLanStreaming,
    updateSupabaseConfig,
    pickAndScanFolder,
    rescanCurrentRoot
  } = useMusikaStore();

  const initialCfg = supabaseService.getConfig();
  const [sbUrl, setSbUrl] = useState(initialCfg.url);
  const [sbKey, setSbKey] = useState(initialCfg.anonKey);
  const [savedToast, setSavedToast] = useState<string | null>(null);

  if (!showSettingsModal) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/80 p-3 backdrop-blur-sm">
      <div className="flex max-h-[90vh] w-full max-w-xl flex-col overflow-hidden rounded-2xl border border-slate-700 bg-surfaceCard shadow-2xl">
        <div className="flex items-center justify-between border-b border-slate-800 px-4 py-3">
          <h2 className="flex items-center gap-2 text-base font-bold text-white">
            <Sliders className="h-4 w-4 text-samsungBlue" />
            Musika Settings
          </h2>
          <button
            onClick={() => setShowSettingsModal(false)}
            className="rounded-lg p-1.5 text-slate-400 hover:bg-slate-800 hover:text-white"
          >
            <X className="h-5 w-5" />
          </button>
        </div>

        <div className="flex-1 space-y-4 overflow-y-auto p-4 text-sm">
          {/* Scoped Root Folder */}
          <div className="rounded-xl border border-slate-800 bg-slate-900/70 p-3 space-y-2.5">
            <div className="text-xs font-bold uppercase tracking-wider text-winampGreen">
              Scoped Musical Root Folder
            </div>
            <div className="truncate font-mono text-xs text-slate-300">
              {rootPath} ({rawTracks.length} audio files loaded)
            </div>
            <div className="flex flex-wrap gap-2">
              <button
                onClick={() => void pickAndScanFolder()}
                className="flex items-center gap-1.5 rounded-lg bg-samsungBlue px-3 py-1.5 text-xs font-semibold text-white hover:bg-blue-500"
              >
                <FolderOpen className="h-3.5 w-3.5" />
                Pick Folder
              </button>
              <label className="flex cursor-pointer items-center gap-1.5 rounded-lg border border-slate-700 bg-slate-800 px-3 py-1.5 text-xs font-medium text-slate-200 hover:bg-slate-700">
                <FolderOpen className="h-3.5 w-3.5" />
                Browser Directory Fallback
                <input
                  type="file"
                  // @ts-expect-error webkitdirectory is supported across desktop browsers
                  webkitdirectory="true"
                  multiple
                  className="hidden"
                  onChange={(e) => {
                    if (e.target.files && e.target.files.length > 0) {
                      void pickAndScanFolder(e.target.files);
                    }
                  }}
                />
              </label>
              <button
                onClick={() => void rescanCurrentRoot()}
                className="flex items-center gap-1.5 rounded-lg border border-slate-700 bg-slate-800 px-3 py-1.5 text-xs font-medium text-slate-200 hover:bg-slate-700"
              >
                <RefreshCw className="h-3.5 w-3.5" />
                Rescan
              </button>
            </div>
          </div>

          {/* HUD Scale & Appearance */}
          <div className="rounded-xl border border-slate-800 bg-slate-900/70 p-3 space-y-3">
            <div className="text-xs font-bold uppercase tracking-wider text-samsungBlue">
              HUD Scale & Artwork Style
            </div>
            <div>
              <div className="flex justify-between text-xs text-slate-300">
                <span>HUD Font & UI Scale</span>
                <span className="font-mono text-winampGreen">{hudScale.toFixed(2)}x</span>
              </div>
              <input
                type="range"
                min="0.85"
                max="1.4"
                step="0.05"
                value={hudScale}
                onChange={(e) => setHudScale(Number.parseFloat(e.target.value))}
                className="mt-1.5 w-full accent-samsungBlue"
              />
            </div>

            <div className="space-y-1.5">
              <div className="text-xs text-slate-300">Now Playing Artwork Style</div>
              <div className="grid grid-cols-1 gap-1.5 sm:grid-cols-2">
                {(Object.keys(ARTWORK_STYLE_LABELS) as NowPlayingArtworkStyle[]).map((style) => (
                  <button
                    key={style}
                    onClick={() => setArtworkStyle(style)}
                    className={`rounded-lg border px-2.5 py-2 text-left text-xs ${
                      artworkStyle === style
                        ? 'border-samsungBlue bg-samsungBlue/20 text-white'
                        : 'border-slate-700 bg-black/30 text-slate-300 hover:border-slate-500'
                    }`}
                  >
                    {ARTWORK_STYLE_LABELS[style]}
                  </button>
                ))}
              </div>
            </div>

            <label className="flex cursor-pointer items-center justify-between text-xs text-slate-200">
              <span>Show album/track artwork thumbnails in lists</span>
              <input
                type="checkbox"
                checked={showListThumbnails}
                onChange={(e) => setShowListThumbnails(e.target.checked)}
                className="h-4 w-4 accent-samsungBlue"
              />
            </label>
          </div>

          {/* Stage 2 Transfer & Supabase Config */}
          <div className="rounded-xl border border-slate-800 bg-slate-900/70 p-3 space-y-3">
            <div className="text-xs font-bold uppercase tracking-wider text-winampAmber">
              LAN Streaming Fallback & Supabase Project
            </div>
            <label className="flex cursor-pointer items-start justify-between gap-2 rounded-lg border border-slate-800 bg-black/30 p-2.5 text-xs">
              <div>
                <div className="font-medium text-slate-200">
                  Allow LAN Audio Streaming for Missing Tracks
                </div>
                <div className="mt-0.5 text-[11px] text-slate-400">
                  OFF by default. When OFF, Move Music Here strictly requires identical track metadata loaded locally on the destination device.
                </div>
              </div>
              <input
                type="checkbox"
                checked={onlineState.allowLanStreamingForMissingTracks}
                onChange={(e) => setAllowLanStreaming(e.target.checked)}
                className="mt-1 h-4 w-4 shrink-0 accent-winampGreen"
              />
            </label>

            <div className="space-y-1.5">
              <label className="block text-[11px] text-slate-400">Supabase Project URL</label>
              <input
                value={sbUrl}
                onChange={(e) => setSbUrl(e.target.value)}
                placeholder="https://your-project.supabase.co"
                className="w-full rounded-lg border border-slate-700 bg-black/50 px-2.5 py-1.5 font-mono text-xs text-white focus:border-samsungBlue focus:outline-none"
              />
              <label className="block text-[11px] text-slate-400">Supabase Anon Public Key</label>
              <input
                value={sbKey}
                onChange={(e) => setSbKey(e.target.value)}
                placeholder="eyJhbGciOi..."
                className="w-full rounded-lg border border-slate-700 bg-black/50 px-2.5 py-1.5 font-mono text-xs text-white focus:border-samsungBlue focus:outline-none"
              />
              <div className="flex items-center justify-between pt-1">
                <span className="text-[11px] text-winampGreen">{savedToast ?? ''}</span>
                <button
                  onClick={() => {
                    updateSupabaseConfig(sbUrl, sbKey);
                    setSavedToast('Supabase configuration saved');
                  }}
                  className="rounded-lg bg-slate-800 px-3 py-1.5 text-xs font-semibold text-white hover:bg-slate-700"
                >
                  Save Supabase Keys
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};
