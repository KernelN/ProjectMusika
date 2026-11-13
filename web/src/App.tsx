import React, { useEffect } from 'react';
import { ConnectModal, SettingsModal } from './components/ConnectAndSettingsModals';
import { LibraryViews } from './components/LibraryViews';
import {
  DualQueueModal,
  HelperModals,
  NowPlayingModal
} from './components/NowPlayingAndQueueModals';
import { TopNavigationBar, WinampMiniPlayerBar } from './components/WinampHudBar';
import { useMusikaStore } from './store/useMusikaStore';

export const App: React.FC = () => {
  const { initialize, hudScale } = useMusikaStore();

  useEffect(() => {
    void initialize();
  }, [initialize]);

  // Apply user-configured HUD scale across root font size without breaking Flexbox layout
  useEffect(() => {
    document.documentElement.style.fontSize = `${Math.round(hudScale * 16)}px`;
  }, [hudScale]);

  return (
    <div className="flex h-screen w-screen flex-col overflow-hidden bg-musikaBg text-slate-100 select-none">
      <TopNavigationBar />

      <main className="flex min-h-0 flex-1 flex-col overflow-hidden">
        <LibraryViews />
      </main>

      <WinampMiniPlayerBar />

      {/* Overlays & Modals */}
      <NowPlayingModal />
      <DualQueueModal />
      <HelperModals />
      <ConnectModal />
      <SettingsModal />
    </div>
  );
};

export default App;
