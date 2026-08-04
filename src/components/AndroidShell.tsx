import React, { useState, useEffect } from 'react';
import {
  Wifi,
  Battery,
  Signal,
  Smartphone,
  Maximize2,
  Minimize2,
  Sparkles,
  LayoutList,
  Bell,
  Database,
  Settings,
  Calendar,
  FileText,
} from 'lucide-react';

interface AndroidShellProps {
  children: React.ReactNode;
  activeTab: 'chat' | 'dashboard' | 'calendar' | 'notes' | 'briefing' | 'settings';
  setActiveTab: (tab: 'chat' | 'dashboard' | 'calendar' | 'notes' | 'briefing' | 'settings') => void;
  pendingCount: number;
}

export const AndroidShell: React.FC<AndroidShellProps> = ({
  children,
  activeTab,
  setActiveTab,
  pendingCount,
}) => {
  const [currentTime, setCurrentTime] = useState<string>('');
  const [isPhoneFrame, setIsPhoneFrame] = useState<boolean>(true);

  useEffect(() => {
    const updateClock = () => {
      const now = new Date();
      setCurrentTime(now.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: false }));
    };
    updateClock();
    const interval = setInterval(updateClock, 10000);
    return () => clearInterval(interval);
  }, []);

  return (
    <div className="min-h-screen bg-[#0A0D12] text-slate-100 flex flex-col items-center justify-start p-0 md:p-4 font-sans selection:bg-cyan-500/30">
      {/* Top Header / Device View Switcher */}
      <header className="w-full max-w-5xl flex items-center justify-between px-4 py-3 bg-[#0D1117] border-b border-slate-800 rounded-none md:rounded-2xl mb-0 md:mb-4 shadow-xl z-30">
        <div className="flex items-center space-x-3">
          <div className="w-8 h-8 rounded-xl bg-gradient-to-tr from-cyan-500 via-indigo-600 to-amber-400 flex items-center justify-center shadow-md shadow-cyan-500/20">
            <Sparkles className="w-4 h-4 text-slate-950 font-bold" />
          </div>
          <div>
            <div className="flex items-center space-x-2">
              <h1 className="font-extrabold text-slate-100 text-sm md:text-base tracking-wide font-mono">J.A.X. AI</h1>
              <span className="w-2 h-2 rounded-full bg-emerald-400 animate-pulse" title="J.A.X. Online" />
              <span className="text-[10px] font-mono text-cyan-400 bg-cyan-500/10 border border-cyan-500/20 px-2 py-0.5 rounded-full">
                Native Room DB
              </span>
            </div>
            <p className="text-[11px] text-slate-400 hidden sm:block">Executive AI Task & Memory Assistant</p>
          </div>
        </div>

        {/* View Mode Switcher Toggle */}
        <div className="flex items-center space-x-2">
          <button
            onClick={() => setIsPhoneFrame(!isPhoneFrame)}
            className="flex items-center space-x-1.5 px-3 py-1.5 rounded-lg text-xs font-medium bg-slate-900 hover:bg-slate-800 text-slate-300 border border-slate-800 transition cursor-pointer"
            title={isPhoneFrame ? "Switch to Fullscreen Layout" : "Switch to Android Frame"}
          >
            <Smartphone className="w-3.5 h-3.5 text-cyan-400" />
            <span className="hidden sm:inline">{isPhoneFrame ? "Phone View" : "Expanded View"}</span>
            {isPhoneFrame ? <Maximize2 className="w-3 h-3 text-slate-400" /> : <Minimize2 className="w-3 h-3 text-slate-400" />}
          </button>
        </div>
      </header>

      {/* Main Device Shell */}
      <main className={`w-full ${isPhoneFrame ? 'max-w-[420px] h-[840px]' : 'max-w-5xl min-h-[800px]'} flex flex-col bg-[#0D1117] md:border md:border-slate-800/80 rounded-none md:rounded-3xl shadow-2xl overflow-hidden relative transition-all duration-300`}>
        {/* Android Device Status Bar */}
        <div className="w-full bg-[#080B0F] px-5 py-2.5 flex items-center justify-between text-xs text-slate-400 select-none border-b border-slate-800/60 z-20">
          <div className="font-semibold text-cyan-300 text-[13px] tracking-tight font-mono">{currentTime || '09:41'}</div>
          
          {/* Top Camera Punch Hole */}
          {isPhoneFrame && (
            <div className="w-20 h-4 bg-[#0D1117] rounded-full border border-slate-800/80 flex items-center justify-center space-x-1.5 px-2">
              <div className="w-2 h-2 rounded-full bg-slate-950 border border-slate-700" />
              <div className="w-1.5 h-1.5 rounded-full bg-cyan-900/80" />
            </div>
          )}

          <div className="flex items-center space-x-2">
            <Signal className="w-3.5 h-3.5 text-slate-300" />
            <Wifi className="w-3.5 h-3.5 text-slate-300" />
            <div className="flex items-center space-x-1">
              <span className="text-[10px] font-mono text-cyan-400">99%</span>
              <Battery className="w-4 h-4 text-slate-200 fill-cyan-400" />
            </div>
          </div>
        </div>

        {/* Content Body */}
        <div className="flex-1 overflow-y-auto bg-[#0D1117] relative flex flex-col">
          {children}
        </div>

        {/* Jetpack Compose Material 3 Dark Bottom Navigation Bar */}
        <nav className="w-full bg-[#080B0F] border-t border-slate-800/80 px-1 py-2 flex items-center justify-around z-20 overflow-x-auto no-scrollbar">
          <button
            onClick={() => setActiveTab('chat')}
            className={`flex-1 min-w-[48px] flex flex-col items-center justify-center py-1 px-1 rounded-xl transition cursor-pointer ${
              activeTab === 'chat'
                ? 'bg-cyan-500/15 text-cyan-300 font-bold border border-cyan-500/30 shadow-sm shadow-cyan-500/10'
                : 'text-slate-400 hover:text-slate-200 hover:bg-slate-900/60'
            }`}
          >
            <Sparkles className="w-4 h-4" />
            <span className="text-[10px] mt-1 tracking-tight font-medium">Chat</span>
          </button>

          <button
            onClick={() => setActiveTab('dashboard')}
            className={`flex-1 min-w-[48px] flex flex-col items-center justify-center py-1 px-1 rounded-xl transition cursor-pointer ${
              activeTab === 'dashboard'
                ? 'bg-cyan-500/15 text-cyan-300 font-bold border border-cyan-500/30 shadow-sm shadow-cyan-500/10'
                : 'text-slate-400 hover:text-slate-200 hover:bg-slate-900/60'
            }`}
          >
            <div className="relative">
              <LayoutList className="w-4 h-4" />
              {pendingCount > 0 && (
                <span className="absolute -top-1.5 -right-2 bg-amber-500 text-slate-950 text-[8px] font-bold px-1 py-0.2 rounded-full min-w-[14px] text-center shadow-sm">
                  {pendingCount}
                </span>
              )}
            </div>
            <span className="text-[10px] mt-1 tracking-tight font-medium">Tasks</span>
          </button>

          <button
            onClick={() => setActiveTab('calendar')}
            className={`flex-1 min-w-[48px] flex flex-col items-center justify-center py-1 px-1 rounded-xl transition cursor-pointer ${
              activeTab === 'calendar'
                ? 'bg-cyan-500/15 text-cyan-300 font-bold border border-cyan-500/30 shadow-sm shadow-cyan-500/10'
                : 'text-slate-400 hover:text-slate-200 hover:bg-slate-900/60'
            }`}
          >
            <Calendar className="w-4 h-4" />
            <span className="text-[10px] mt-1 tracking-tight font-medium">Calendar</span>
          </button>

          <button
            onClick={() => setActiveTab('notes')}
            className={`flex-1 min-w-[48px] flex flex-col items-center justify-center py-1 px-1 rounded-xl transition cursor-pointer ${
              activeTab === 'notes'
                ? 'bg-cyan-500/15 text-cyan-300 font-bold border border-cyan-500/30 shadow-sm shadow-cyan-500/10'
                : 'text-slate-400 hover:text-slate-200 hover:bg-slate-900/60'
            }`}
          >
            <FileText className="w-4 h-4" />
            <span className="text-[10px] mt-1 tracking-tight font-medium">Notes</span>
          </button>

          <button
            onClick={() => setActiveTab('briefing')}
            className={`flex-1 min-w-[48px] flex flex-col items-center justify-center py-1 px-1 rounded-xl transition cursor-pointer ${
              activeTab === 'briefing'
                ? 'bg-cyan-500/15 text-cyan-300 font-bold border border-cyan-500/30 shadow-sm shadow-cyan-500/10'
                : 'text-slate-400 hover:text-slate-200 hover:bg-slate-900/60'
            }`}
          >
            <Bell className="w-4 h-4" />
            <span className="text-[10px] mt-1 tracking-tight font-medium">Briefing</span>
          </button>

          <button
            onClick={() => setActiveTab('settings')}
            className={`flex-1 min-w-[48px] flex flex-col items-center justify-center py-1 px-1 rounded-xl transition cursor-pointer ${
              activeTab === 'settings'
                ? 'bg-cyan-500/15 text-cyan-300 font-bold border border-cyan-500/30 shadow-sm shadow-cyan-500/10'
                : 'text-slate-400 hover:text-slate-200 hover:bg-slate-900/60'
            }`}
          >
            <Settings className="w-4 h-4" />
            <span className="text-[10px] mt-1 tracking-tight font-medium">Settings</span>
          </button>
        </nav>

        {/* Android Gesture Navigation Line */}
        {isPhoneFrame && (
          <div className="w-full bg-[#080B0F] pb-1 flex justify-center items-center">
            <div className="w-32 h-1 bg-slate-700/80 rounded-full" />
          </div>
        )}
      </main>
    </div>
  );
};
