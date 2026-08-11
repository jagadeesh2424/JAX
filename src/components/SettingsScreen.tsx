import React from 'react';
import { RoomDB } from '../db/roomDatabase';
import { Settings, RefreshCw, Smartphone, ShieldCheck, Sparkles, Database } from 'lucide-react';

interface SettingsScreenProps {
  onTasksChanged: () => void;
}

export const SettingsScreen: React.FC<SettingsScreenProps> = ({ onTasksChanged }) => {
  const handleReloadDemoTasks = async () => {
    if (confirm('Pre-populate demo tasks for J.A.X. into Room DB?')) {
      await RoomDB.clearAllTasks();
      // re-trigger initial seed
      await (RoomDB as any).seedInitialDataIfEmpty();
      onTasksChanged();
      alert('Demo tasks reloaded successfully!');
    }
  };

  return (
    <div className="flex-1 flex flex-col h-full bg-slate-900 text-slate-100 overflow-y-auto p-4 space-y-4">
      <div className="flex items-center space-x-2 border-b border-slate-800 pb-3">
        <Settings className="w-5 h-5 text-indigo-400" />
        <h2 className="text-sm font-bold text-slate-100">Application Settings</h2>
      </div>

      <div className="space-y-3 text-xs">
        {/* App Info Card */}
        <div className="bg-slate-800/80 border border-slate-700/80 p-4 rounded-2xl space-y-2">
          <div className="flex items-center space-x-2">
            <div className="w-7 h-7 rounded-lg bg-indigo-600 flex items-center justify-center">
              <Sparkles className="w-4 h-4 text-white" />
            </div>
            <div>
              <h3 className="font-bold text-slate-100">J.A.X. - Jagadeesh Assistant X</h3>
              <p className="text-[11px] text-indigo-300">Executive AI Task & Memory Assistant</p>
            </div>
          </div>
          <p className="text-slate-300 leading-relaxed pt-1">
            Combines natural plain-English AI task dump capture powered by Gemini 3.6 Flash on the server with local Android Room SQLite persistent storage.
          </p>
        </div>

        {/* Database Management */}
        <div className="bg-slate-800/80 border border-slate-700/80 p-4 rounded-2xl space-y-3">
          <div className="flex items-center space-x-2 border-b border-slate-700/80 pb-2">
            <Database className="w-4 h-4 text-indigo-400" />
            <h3 className="font-bold text-slate-100 uppercase tracking-wider text-[11px]">
              Hybrid Storage (Room DB + Firebase Cloud)
            </h3>
          </div>

          <div className="flex items-center justify-between">
            <div>
              <div className="font-medium text-slate-200">Reload Sample Demo Tasks</div>
              <div className="text-[11px] text-slate-400">Resets Room DB with sample J.A.X. task items</div>
            </div>
            <button
              onClick={handleReloadDemoTasks}
              className="px-3 py-1.5 bg-indigo-600 hover:bg-indigo-500 text-white font-medium rounded-xl transition cursor-pointer flex items-center space-x-1"
            >
              <RefreshCw className="w-3.5 h-3.5" />
              <span>Reload Demo</span>
            </button>
          </div>
        </div>

        {/* System & Architecture status */}
        <div className="bg-slate-800/80 border border-slate-700/80 p-4 rounded-2xl space-y-2">
          <div className="flex items-center space-x-2 border-b border-slate-700/80 pb-2">
            <ShieldCheck className="w-4 h-4 text-emerald-400" />
            <h3 className="font-bold text-slate-100 uppercase tracking-wider text-[11px]">
              Architecture & Persistence Status
            </h3>
          </div>

          <div className="space-y-1.5 text-[11px] text-slate-300 font-mono pt-1">
            <div className="flex justify-between">
              <span className="text-slate-400">AI Model:</span>
              <span className="text-indigo-300">gemini-3.6-flash</span>
            </div>
            <div className="flex justify-between">
              <span className="text-slate-400">Cloud Persistence:</span>
              <span className="text-emerald-400">Firebase Firestore (Active Cloud Sync)</span>
            </div>
            <div className="flex justify-between">
              <span className="text-slate-400">Local Database:</span>
              <span className="text-indigo-300">Android Room (IndexedDB Cache)</span>
            </div>
            <div className="flex justify-between">
              <span className="text-slate-400">Cloud Backup:</span>
              <span className="text-emerald-400">Automatic (Safe across APK reinstall)</span>
            </div>
            <div className="flex justify-between">
              <span className="text-slate-400">Security:</span>
              <span className="text-emerald-400">Server-Side API Key Encapsulation</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};
