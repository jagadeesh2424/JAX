import React, { useState, useEffect } from 'react';
import { RoomDB } from '../db/roomDatabase';
import { SqlQueryLog, Task } from '../types';
import { Database, Terminal, Download, RefreshCw, Layers, Table, HardDrive, Trash2 } from 'lucide-react';

interface RoomDbInspectorProps {
  onDataReset: () => void;
}

export const RoomDbInspector: React.FC<RoomDbInspectorProps> = ({ onDataReset }) => {
  const [activeSubTab, setActiveSubTab] = useState<'tables' | 'queryLog'>('queryLog');
  const [sqlLogs, setSqlLogs] = useState<SqlQueryLog[]>([]);
  const [tasks, setTasks] = useState<Task[]>([]);
  const [exportedJson, setExportedJson] = useState<string | null>(null);

  useEffect(() => {
    const unsubscribe = RoomDB.subscribeSqlLogs((logs) => {
      setSqlLogs([...logs]);
    });

    loadTasks();

    return () => unsubscribe();
  }, []);

  const loadTasks = async () => {
    const data = await RoomDB.getAllTasks();
    setTasks(data);
  };

  const handleExportJson = async () => {
    const json = await RoomDB.exportDatabaseJson();
    setExportedJson(json);

    // Create download link
    const blob = new Blob([json], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `SanerRoomDatabase_Backup_${new Date().toISOString().slice(0, 10)}.json`;
    a.click();
    URL.revokeObjectURL(url);
  };

  const handleResetData = async () => {
    if (confirm('Are you sure you want to clear all tasks from local Room DB?')) {
      await RoomDB.clearAllTasks();
      loadTasks();
      onDataReset();
    }
  };

  return (
    <div className="flex-1 flex flex-col h-full bg-slate-900 text-slate-100 overflow-hidden">
      {/* Header */}
      <div className="px-4 py-3 bg-slate-950/80 border-b border-slate-800 flex items-center justify-between">
        <div className="flex items-center space-x-2">
          <Database className="w-4 h-4 text-indigo-400" />
          <h2 className="text-xs font-bold text-slate-100">Android Room / SQLite DB Inspector</h2>
        </div>

        <div className="flex items-center space-x-1.5">
          <button
            onClick={() => setActiveSubTab('queryLog')}
            className={`px-2.5 py-1 rounded-lg text-xs font-medium transition cursor-pointer ${
              activeSubTab === 'queryLog'
                ? 'bg-indigo-600 text-white'
                : 'bg-slate-800 text-slate-400 hover:text-slate-200'
            }`}
          >
            SQL Logs ({sqlLogs.length})
          </button>
          <button
            onClick={() => setActiveSubTab('tables')}
            className={`px-2.5 py-1 rounded-lg text-xs font-medium transition cursor-pointer ${
              activeSubTab === 'tables'
                ? 'bg-indigo-600 text-white'
                : 'bg-slate-800 text-slate-400 hover:text-slate-200'
            }`}
          >
            Table Records ({tasks.length})
          </button>
        </div>
      </div>

      {/* Main Body */}
      <div className="flex-1 overflow-y-auto p-4 space-y-4">
        {/* Database Overview Cards */}
        <div className="grid grid-cols-2 sm:grid-cols-3 gap-2.5">
          <div className="bg-slate-800/80 border border-slate-700/80 p-3 rounded-xl flex items-center space-x-3">
            <HardDrive className="w-5 h-5 text-indigo-400" />
            <div>
              <div className="text-[10px] text-slate-400">Database Name</div>
              <div className="text-xs font-mono font-bold text-slate-200">SanerRoomDatabase.db</div>
            </div>
          </div>

          <div className="bg-slate-800/80 border border-slate-700/80 p-3 rounded-xl flex items-center space-x-3">
            <Layers className="w-5 h-5 text-emerald-400" />
            <div>
              <div className="text-[10px] text-slate-400">Entities / Tables</div>
              <div className="text-xs font-bold text-slate-200">task_table, briefing_table</div>
            </div>
          </div>

          <div className="bg-slate-800/80 border border-slate-700/80 p-3 rounded-xl flex items-center space-x-3 col-span-2 sm:col-span-1">
            <Table className="w-5 h-5 text-amber-400" />
            <div>
              <div className="text-[10px] text-slate-400">Local Persistence Engine</div>
              <div className="text-xs font-bold text-slate-200">IndexedDB + Room DAO</div>
            </div>
          </div>
        </div>

        {/* Tab 1: Live SQL Query Console */}
        {activeSubTab === 'queryLog' && (
          <div className="bg-slate-950 border border-slate-800 rounded-2xl p-3.5 space-y-2 font-mono">
            <div className="flex items-center justify-between border-b border-slate-800 pb-2">
              <div className="flex items-center space-x-2 text-xs text-indigo-400">
                <Terminal className="w-4 h-4" />
                <span className="font-bold">Live Room SQLite Query Stream</span>
              </div>
              <span className="text-[10px] text-slate-500">Real-time DB transactions</span>
            </div>

            <div className="max-h-96 overflow-y-auto space-y-2 text-[11px] pr-1">
              {sqlLogs.length === 0 ? (
                <div className="text-slate-500 py-4 text-center">No queries logged yet.</div>
              ) : (
                sqlLogs.map((log) => (
                  <div
                    key={log.id}
                    className="p-2 bg-slate-900 rounded-lg border border-slate-800/80 flex flex-col space-y-1"
                  >
                    <div className="flex items-center justify-between text-[10px]">
                      <span
                        className={`font-bold px-1.5 py-0.5 rounded ${
                          log.action === 'INSERT'
                            ? 'bg-emerald-500/20 text-emerald-300'
                            : log.action === 'UPDATE'
                            ? 'bg-amber-500/20 text-amber-300'
                            : log.action === 'DELETE'
                            ? 'bg-rose-500/20 text-rose-300'
                            : 'bg-indigo-500/20 text-indigo-300'
                        }`}
                      >
                        @{log.action}
                      </span>
                      <span className="text-slate-500">{new Date(log.timestamp).toLocaleTimeString()}</span>
                    </div>

                    <div className="text-slate-300 break-all">{log.query}</div>
                  </div>
                ))
              )}
            </div>
          </div>
        )}

        {/* Tab 2: Raw Room Table Rows Browser */}
        {activeSubTab === 'tables' && (
          <div className="bg-slate-800/80 border border-slate-700/80 rounded-2xl p-3.5 space-y-3">
            <div className="flex items-center justify-between">
              <h3 className="text-xs font-bold text-slate-200 uppercase tracking-wider">
                `task_table` Content Rows ({tasks.length})
              </h3>
              <button
                onClick={loadTasks}
                className="text-xs text-indigo-400 hover:text-indigo-300 flex items-center space-x-1"
              >
                <RefreshCw className="w-3 h-3" />
                <span>Refresh</span>
              </button>
            </div>

            <div className="overflow-x-auto">
              <table className="w-full text-left text-[11px] text-slate-300">
                <thead className="bg-slate-900 text-slate-400 uppercase text-[10px]">
                  <tr>
                    <th className="p-2 border-b border-slate-700">ID</th>
                    <th className="p-2 border-b border-slate-700">Title</th>
                    <th className="p-2 border-b border-slate-700">Category</th>
                    <th className="p-2 border-b border-slate-700">Priority</th>
                    <th className="p-2 border-b border-slate-700">Status</th>
                    <th className="p-2 border-b border-slate-700">Deadline</th>
                  </tr>
                </thead>
                <tbody>
                  {tasks.map((t) => (
                    <tr key={t.id} className="border-b border-slate-700/50 hover:bg-slate-700/30 font-mono">
                      <td className="p-2 text-indigo-300">{t.id.slice(0, 8)}...</td>
                      <td className="p-2 font-sans font-medium text-slate-100">{t.title}</td>
                      <td className="p-2">{t.category}</td>
                      <td className="p-2 capitalize">{t.priority}</td>
                      <td className="p-2 capitalize">{t.status}</td>
                      <td className="p-2 text-slate-400">{t.deadline ? t.deadline.slice(0, 16) : 'null'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}

        {/* Export & Clear Actions */}
        <div className="bg-slate-800/80 border border-slate-700/80 rounded-2xl p-4 flex flex-col sm:flex-row items-center justify-between gap-3">
          <div>
            <h4 className="text-xs font-bold text-slate-200">Database Tools & Backups</h4>
            <p className="text-[11px] text-slate-400">Export SQLite Room table schema and records as JSON.</p>
          </div>

          <div className="flex items-center space-x-2">
            <button
              onClick={handleExportJson}
              className="px-3 py-1.5 bg-indigo-600 hover:bg-indigo-500 text-white rounded-xl text-xs font-medium transition cursor-pointer flex items-center space-x-1.5 shadow-md shadow-indigo-600/20"
            >
              <Download className="w-3.5 h-3.5" />
              <span>Export DB JSON</span>
            </button>

            <button
              onClick={handleResetData}
              className="px-3 py-1.5 bg-rose-600/20 hover:bg-rose-600/40 text-rose-300 border border-rose-500/30 rounded-xl text-xs font-medium transition cursor-pointer flex items-center space-x-1.5"
            >
              <Trash2 className="w-3.5 h-3.5" />
              <span>Clear Table</span>
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};
