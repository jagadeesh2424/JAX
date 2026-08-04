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
  activeTab: 'chat' | 'dashboard' | 'calendar' | 'notes' | 'briefing' | 'room' | 'settings';
  setActiveTab: (tab: 'chat' | 'dashboard' | 'calendar' | 'notes' | 'briefing' | 'room' | 'settings') => void;
  pendingCount: number;
  highPriorityCount: number;
  unreadBriefing: boolean;
}

export const AndroidShell: React.FC<AndroidShellProps> = ({
  children,
  activeTab,
  setActiveTab,
  pendingCount,
  highPriorityCount,
  unreadBriefing,
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
    <div className="min-h-screen bg-slate-950 text-slate-100 flex flex-col items-center justify-start p-0 md:p-4 font-sans selection:bg-indigo-500/30">
      {/* Top Header / View Controller Bar */}
      <header className="w-full max-w-5xl flex items-center justify-between px-4 py-3 bg-slate-900/80 backdrop-blur border-b border-slate-800 rounded-none md:rounded-2xl mb-0 md:mb-4 shadow-lg z-30">
        <div className="flex items-center space-x-3">
          <div className="w-8 h-8 rounded-xl bg-gradient-to-tr from-indigo-600 via-purple-600 to-pink-500 flex items-center justify-center shadow-md shadow-indigo-500/20">
            <Sparkles className="w-4 h-4 text-white" />
          </div>
          <div>
            <div className="flex items-center space-x-2">
              <h1 className="font-bold text-slate-100 text-sm md:text-base tracking-tight">J.A.X. AI</h1>
              <span className="text-[10px] font-semibold bg-indigo-500/10 text-indigo-400 border border-indigo-500/20 px-2 py-0.5 rounded-full">
                Android Room DB
              </span>
            </div>
            <p className="text-xs text-slate-400 hidden sm:block">Plain-English AI Chat & Task Manager</p>
          </div>
        </div>

        {/* View Mode Switcher Toggle */}
        <div className="flex items-center space-x-2">
          <button
            onClick={() => setIsPhoneFrame(!isPhoneFrame)}
            className="flex items-center space-x-1.5 px-3 py-1.5 rounded-lg text-xs font-medium bg-slate-800 hover:bg-slate-700 text-slate-200 border border-slate-700 transition cursor-pointer"
            title={isPhoneFrame ? "Switch to Fullscreen Layout" : "Switch to Android Frame"}
          >
            <Smartphone className="w-3.5 h-3.5 text-indigo-400" />
            <span className="hidden sm:inline">{isPhoneFrame ? "Phone View" : "Expanded View"}</span>
            {isPhoneFrame ? <Maximize2 className="w-3 h-3 text-slate-400" /> : <Minimize2 className="w-3 h-3 text-slate-400" />}
          </button>
        </div>
      </header>

      {/* Main Container */}
      <main className={`w-full ${isPhoneFrame ? 'max-w-[420px] h-[840px]' : 'max-w-5xl min-h-[800px]'} flex flex-col bg-slate-900 md:border md:border-slate-800 rounded-none md:rounded-3xl shadow-2xl overflow-hidden relative transition-all duration-300`}>
        {/* Android Device Status Bar (Always Visible) */}
        <div className="w-full bg-slate-950 px-5 py-2.5 flex items-center justify-between text-xs text-slate-400 select-none border-b border-slate-800/50 z-20">
          <div className="font-semibold text-slate-200 text-[13px] tracking-tight">{currentTime || '09:41'}</div>
          
          {/* Top Camera Punch Hole / Pill */}
          {isPhoneFrame && (
            <div className="w-20 h-4 bg-slate-900 rounded-full border border-slate-800/80 flex items-center justify-center space-x-1.5 px-2">
              <div className="w-2 h-2 rounded-full bg-slate-950 border border-slate-700" />
              <div className="w-1.5 h-1.5 rounded-full bg-indigo-900/60" />
            </div>
          )}

          <div className="flex items-center space-x-2">
            <Signal className="w-3.5 h-3.5 text-slate-300" />
            <Wifi className="w-3.5 h-3.5 text-slate-300" />
            <div className="flex items-center space-x-1">
              <span className="text-[10px] font-mono text-slate-300">88%</span>
              <Battery className="w-4 h-4 text-slate-200 fill-slate-300" />
            </div>
          </div>
        </div>

        {/* Content Body */}
        <div className="flex-1 overflow-y-auto bg-slate-900 relative flex flex-col">
          {children}
        </div>

        {/* Android Material You Bottom Navigation Bar */}
        <nav className="w-full bg-slate-950/95 backdrop-blur-md border-t border-slate-800 px-2 py-2 flex items-center justify-around z-20">
          <button
            onClick={() => setActiveTab('chat')}
            className={`flex flex-col items-center justify-center py-1 px-3 rounded-2xl transition cursor-pointer ${
              activeTab === 'chat'
                ? 'bg-indigo-600/20 text-indigo-400 font-medium'
                : 'text-slate-400 hover:text-slate-200 hover:bg-slate-900'
            }`}
          >
            <div className="relative">
              <Sparkles className="w-5 h-5" />
            </div>
            <span className="text-[10px] mt-1">AI Chat</span>
          </button>

          <button
            onClick={() => setActiveTab('dashboard')}
            className={`flex flex-col items-center justify-center py-1 px-3 rounded-2xl transition cursor-pointer ${
              activeTab === 'dashboard'
                ? 'bg-indigo-600/20 text-indigo-400 font-medium'
                : 'text-slate-400 hover:text-slate-200 hover:bg-slate-900'
            }`}
          >
            <div className="relative">
              <LayoutList className="w-5 h-5" />
              {pendingCount > 0 && (
                <span className="absolute -top-1.5 -right-2 bg-indigo-500 text-white text-[9px] font-bold px-1.5 py-0.2 rounded-full min-w-[16px] text-center">
                  {pendingCount}
                </span>
              )}
            </div>
            <span className="text-[10px] mt-1">Tasks</span>
          </button>

          <button
            onClick={() => setActiveTab('calendar')}
            className={`flex flex-col items-center justify-center py-1 px-2.5 rounded-2xl transition cursor-pointer ${
              activeTab === 'calendar'
                ? 'bg-indigo-600/20 text-indigo-400 font-medium'
                : 'text-slate-400 hover:text-slate-200 hover:bg-slate-900'
            }`}
          >
            <Calendar className="w-5 h-5" />
            <span className="text-[10px] mt-1">Calendar</span>
          </button>

          <button
            onClick={() => setActiveTab('notes')}
            className={`flex flex-col items-center justify-center py-1 px-2.5 rounded-2xl transition cursor-pointer ${
              activeTab === 'notes'
                ? 'bg-indigo-600/20 text-indigo-400 font-medium'
                : 'text-slate-400 hover:text-slate-200 hover:bg-slate-900'
            }`}
          >
            <FileText className="w-5 h-5" />
            <span className="text-[10px] mt-1">Quick Notes</span>
          </button>

          <button
            onClick={() => setActiveTab('briefing')}
            className={`flex flex-col items-center justify-center py-1 px-2.5 rounded-2xl transition cursor-pointer ${
              activeTab === 'briefing'
                ? 'bg-indigo-600/20 text-indigo-400 font-medium'
                : 'text-slate-400 hover:text-slate-200 hover:bg-slate-900'
            }`}
          >
            <div className="relative">
              <Bell className="w-5 h-5" />
              {(highPriorityCount > 0 || unreadBriefing) && (
                <span className="absolute -top-1 -right-1.5 w-2.5 h-2.5 bg-rose-500 rounded-full animate-pulse border border-slate-950" />
              )}
            </div>
            <span className="text-[10px] mt-1">Briefing</span>
          </button>

          <button
            onClick={() => setActiveTab('settings')}
            className={`flex flex-col items-center justify-center py-1 px-2.5 rounded-2xl transition cursor-pointer ${
              activeTab === 'settings'
                ? 'bg-indigo-600/20 text-indigo-400 font-medium'
                : 'text-slate-400 hover:text-slate-200 hover:bg-slate-900'
            }`}
          >
            <Settings className="w-5 h-5" />
            <span className="text-[10px] mt-1">Settings</span>
          </button>
        </nav>

        {/* Android Gesture Bar */}
        {isPhoneFrame && (
          <div className="w-full bg-slate-950 pb-1 flex justify-center items-center">
            <div className="w-32 h-1 bg-slate-700 rounded-full" />
          </div>
        )}
      </main>
    </div>
  );
};
