import React, { useState, useEffect } from 'react';
import { Task } from './types';
import { RoomDB } from './db/roomDatabase';
import { AndroidShell } from './components/AndroidShell';
import { ChatCaptureScreen } from './components/ChatCaptureScreen';
import { TaskDashboard } from './components/TaskDashboard';
import { DailyBriefingModal } from './components/DailyBriefingModal';
import { RoomDbInspector } from './components/RoomDbInspector';
import { CalendarViewScreen } from './components/CalendarViewScreen';
import { NotesScreen } from './components/NotesScreen';
import { SettingsScreen } from './components/SettingsScreen';
import { NotificationBanner } from './components/NotificationBanner';
import { formatRelativeTime } from './utils/dateUtils';
import { Plus, X } from 'lucide-react';

export default function App() {
  const [activeTab, setActiveTab] = useState<'chat' | 'dashboard' | 'calendar' | 'memory' | 'briefing'>('chat');
  const [tasks, setTasks] = useState<Task[]>([]);
  const [isQuickAddOpen, setIsQuickAddOpen] = useState<boolean>(false);
  const [notification, setNotification] = useState<{ title: string; body: string } | null>(null);

  // Quick Add Form State
  const [quickTitle, setQuickTitle] = useState<string>('');
  const [quickCategory, setQuickCategory] = useState<string>('Work');
  const [quickPriority, setQuickPriority] = useState<'high' | 'medium' | 'low'>('medium');
  const [quickDeadline, setQuickDeadline] = useState<string>('');

  const loadTasks = async () => {
    const data = await RoomDB.getAllTasks();
    setTasks(data);
  };

  useEffect(() => {
    // Initialize Room DB & Subscribe to live changes
    loadTasks();
    const unsubscribe = RoomDB.subscribe(loadTasks);

    return () => unsubscribe();
  }, []);

  const handleQuickAddSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!quickTitle.trim()) return;

    const newTask: Task = {
      id: 'task-' + Date.now(),
      title: quickTitle.trim(),
      category: quickCategory,
      priority: quickPriority,
      status: 'pending',
      deadline: quickDeadline ? new Date(quickDeadline).toISOString() : null,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    };

    await RoomDB.insertTask(newTask);
    setQuickTitle('');
    setQuickDeadline('');
    setIsQuickAddOpen(false);
    loadTasks();
  };

  const pendingTasks = tasks.filter((t) => t.status === 'pending');

  return (
    <AndroidShell
      activeTab={activeTab}
      setActiveTab={setActiveTab}
      pendingCount={pendingTasks.length}
    >
      {/* Top Notification Toast Overlay */}
      <NotificationBanner
        notification={notification}
        onDismiss={() => setNotification(null)}
        onOpenBriefing={() => setActiveTab('briefing')}
      />

      {/* Screen Views */}
      {activeTab === 'chat' && (
        <ChatCaptureScreen onTaskSaved={loadTasks} />
      )}

      {activeTab === 'dashboard' && (
        <TaskDashboard
          tasks={tasks}
          onTasksChanged={loadTasks}
          onOpenQuickAdd={() => setIsQuickAddOpen(true)}
        />
      )}

      {activeTab === 'calendar' && (
        <CalendarViewScreen
          tasks={tasks}
          onTasksChanged={loadTasks}
          onOpenQuickAddWithDate={(dateIso) => {
            setQuickDeadline(dateIso.slice(0, 16));
            setIsQuickAddOpen(true);
          }}
        />
      )}

      {activeTab === 'memory' && (
        <NotesScreen onTasksChanged={loadTasks} />
      )}

      {activeTab === 'briefing' && (
        <DailyBriefingModal
          tasks={tasks}
          onTriggerNotification={(title, body) => setNotification({ title, body })}
        />
      )}

      {/* Quick Add Modal */}
      {isQuickAddOpen && (
        <div className="fixed inset-0 bg-slate-950/80 backdrop-blur-sm flex items-center justify-center p-4 z-50">
          <div className="bg-[#0D1117] border border-cyan-500/30 rounded-2xl p-5 max-w-md w-full space-y-4 shadow-2xl">
            <div className="flex items-center justify-between border-b border-slate-800 pb-2">
              <h3 className="text-sm font-bold text-slate-100 flex items-center space-x-2">
                <Plus className="w-4 h-4 text-cyan-400" />
                <span className="font-mono">Quick Add Task to Room DB</span>
              </h3>
              <button
                onClick={() => setIsQuickAddOpen(false)}
                className="p-1 text-slate-400 hover:text-slate-200"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <form onSubmit={handleQuickAddSubmit} className="space-y-3">
              <div>
                <label className="text-xs text-slate-400 block mb-1">Task Title</label>
                <input
                  type="text"
                  value={quickTitle}
                  onChange={(e) => setQuickTitle(e.target.value)}
                  placeholder="e.g., Submit project report"
                  required
                  className="w-full bg-slate-900 border border-slate-800 rounded-xl px-3 py-2 text-xs text-slate-100 outline-none focus:ring-1 focus:ring-cyan-500"
                />
              </div>

              <div className="grid grid-cols-2 gap-2">
                <div>
                  <label className="text-xs text-slate-400 block mb-1">Category</label>
                  <select
                    value={quickCategory}
                    onChange={(e) => setQuickCategory(e.target.value)}
                    className="w-full bg-slate-900 border border-slate-800 rounded-xl px-2.5 py-2 text-xs text-slate-100 outline-none"
                  >
                    <option value="Work">Work</option>
                    <option value="Personal">Personal</option>
                    <option value="Finance">Finance</option>
                    <option value="Health">Health</option>
                    <option value="Urgent">Urgent</option>
                  </select>
                </div>

                <div>
                  <label className="text-xs text-slate-400 block mb-1">Priority</label>
                  <select
                    value={quickPriority}
                    onChange={(e) => setQuickPriority(e.target.value as any)}
                    className="w-full bg-slate-900 border border-slate-800 rounded-xl px-2.5 py-2 text-xs text-slate-100 outline-none"
                  >
                    <option value="high">🔴 High</option>
                    <option value="medium">🟠 Medium</option>
                    <option value="low">🔵 Low</option>
                  </select>
                </div>
              </div>

              <div>
                <label className="text-xs text-slate-400 block mb-1">Deadline (Optional)</label>
                <input
                  type="datetime-local"
                  value={quickDeadline}
                  onChange={(e) => setQuickDeadline(e.target.value)}
                  className="w-full bg-slate-900 border border-slate-800 rounded-xl px-3 py-2 text-xs text-slate-100 outline-none"
                />
              </div>

              <div className="flex items-center justify-end space-x-2 pt-2 border-t border-slate-800">
                <button
                  type="button"
                  onClick={() => setIsQuickAddOpen(false)}
                  className="px-3.5 py-1.5 bg-slate-800 hover:bg-slate-700 text-xs font-medium text-slate-300 rounded-xl transition cursor-pointer"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  className="px-4 py-1.5 bg-cyan-600 hover:bg-cyan-500 text-xs font-bold text-slate-950 rounded-xl transition cursor-pointer shadow-md shadow-cyan-600/20"
                >
                  Save Task
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </AndroidShell>
  );
}
