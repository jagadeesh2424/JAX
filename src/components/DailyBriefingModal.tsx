import React, { useState, useEffect } from 'react';
import { DailyBriefing, Task } from '../types';
import { RoomDB } from '../db/roomDatabase';
import { formatRelativeTime } from '../utils/dateUtils';
import { Sparkles, Bell, Flame, CheckCircle2, Clock, RefreshCw, Volume2, ShieldAlert, Zap } from 'lucide-react';

interface DailyBriefingModalProps {
  tasks: Task[];
  onTriggerNotification: (title: string, body: string) => void;
}

export const DailyBriefingModal: React.FC<DailyBriefingModalProps> = ({ tasks, onTriggerNotification }) => {
  const [briefing, setBriefing] = useState<DailyBriefing | null>(null);
  const [isGenerating, setIsGenerating] = useState<boolean>(false);
  const [notificationPermission, setNotificationPermission] = useState<NotificationPermission>('default');
  const [dailyAlarmTime, setDailyAlarmTime] = useState<string>('08:00');
  const [isAlarmEnabled, setIsAlarmEnabled] = useState<boolean>(true);

  useEffect(() => {
    if ('Notification' in window) {
      setNotificationPermission(Notification.permission);
    }
    loadLatestBriefing();
  }, []);

  const loadLatestBriefing = async () => {
    const existing = await RoomDB.getLatestBriefing();
    if (existing) {
      setBriefing(existing);
    }
  };

  const handleGenerateBriefing = async () => {
    setIsGenerating(true);
    try {
      const response = await fetch('/api/daily-briefing', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          tasks: tasks.filter((t) => t.status !== 'completed'),
          userNowIso: new Date().toISOString(),
        }),
      });

      if (!response.ok) {
        throw new Error('Failed to generate daily briefing.');
      }

      const data = await response.json();

      const pendingTasks = tasks.filter((t) => t.status === 'pending');
      const highPriority = pendingTasks.filter((t) => t.priority === 'high');
      const overdue = pendingTasks.filter((t) => t.deadline && formatRelativeTime(t.deadline).isOverdue);

      const newBriefing: DailyBriefing = {
        id: 'briefing-' + Date.now(),
        date: new Date().toLocaleDateString([], { weekday: 'long', month: 'short', day: 'numeric' }),
        greeting: data.greeting || 'Good Morning!',
        summary: data.summary || 'Here is your daily task summary.',
        focusMessage: data.focusMessage || 'Focus on urgent high-priority tasks.',
        totalPending: pendingTasks.length,
        highPriorityCount: highPriority.length,
        overdueCount: overdue.length,
        topActionableItemIds: data.topActionableItemIds || highPriority.slice(0, 3).map((t) => t.id),
        generatedAt: new Date().toISOString(),
      };

      await RoomDB.saveDailyBriefing(newBriefing);
      setBriefing(newBriefing);

      // Trigger proactive reminder notification
      onTriggerNotification(
        `🌅 Daily Briefing Ready (${highPriority.length} High Priority Tasks)`,
        data.focusMessage || `You have ${pendingTasks.length} pending items today.`
      );
    } catch (error: any) {
      console.error('Error generating briefing:', error);
      alert('Could not generate AI briefing: ' + error.message);
    } finally {
      setIsGenerating(false);
    }
  };

  const requestNotificationPermission = async () => {
    if (!('Notification' in window)) {
      alert('Browser does not support notifications.');
      return;
    }

    const perm = await Notification.requestPermission();
    setNotificationPermission(perm);
    if (perm === 'granted') {
      onTriggerNotification(
        '🔔 Notifications Enabled!',
        'You will receive daily briefings and high-priority task alerts.'
      );
    }
  };

  const highPriorityTasks = tasks.filter((t) => t.status === 'pending' && t.priority === 'high');
  const overdueTasks = tasks.filter((t) => t.status === 'pending' && t.deadline && formatRelativeTime(t.deadline).isOverdue);

  return (
    <div className="flex-1 flex flex-col h-full bg-slate-900 text-slate-100 overflow-y-auto p-4 space-y-4">
      {/* Top Banner */}
      <div className="bg-gradient-to-r from-indigo-900/80 via-purple-900/70 to-slate-900 border border-indigo-500/30 rounded-2xl p-4 shadow-lg relative overflow-hidden">
        <div className="absolute top-0 right-0 p-6 opacity-10 pointer-events-none">
          <Sparkles className="w-32 h-32 text-indigo-300" />
        </div>

        <div className="flex items-center justify-between mb-2">
          <div className="flex items-center space-x-2">
            <div className="p-2 bg-indigo-600/30 rounded-xl border border-indigo-500/40">
              <Sparkles className="w-5 h-5 text-indigo-300" />
            </div>
            <div>
              <h2 className="text-sm font-bold text-slate-100">Proactive Daily Briefing</h2>
              <p className="text-[11px] text-indigo-300">J.A.X. Executive Priority Summary</p>
            </div>
          </div>

          <button
            onClick={handleGenerateBriefing}
            disabled={isGenerating}
            className="px-3 py-1.5 bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 text-white rounded-xl text-xs font-semibold flex items-center space-x-1.5 transition cursor-pointer shadow-md shadow-indigo-600/30"
          >
            <RefreshCw className={`w-3.5 h-3.5 ${isGenerating ? 'animate-spin' : ''}`} />
            <span>{isGenerating ? 'Generating...' : 'Run Briefing'}</span>
          </button>
        </div>

        {briefing ? (
          <div className="mt-3 bg-slate-950/60 p-3.5 rounded-xl border border-slate-800 space-y-2">
            <div className="flex items-center justify-between">
              <h3 className="text-xs font-bold text-indigo-300">{briefing.greeting}</h3>
              <span className="text-[10px] text-slate-400 font-mono">{briefing.date}</span>
            </div>

            <p className="text-xs text-slate-200 leading-relaxed font-normal">{briefing.summary}</p>

            <div className="bg-indigo-950/50 border border-indigo-500/30 p-2.5 rounded-lg flex items-start space-x-2">
              <Zap className="w-4 h-4 text-amber-400 flex-shrink-0 mt-0.5" />
              <p className="text-xs text-amber-200 font-medium">{briefing.focusMessage}</p>
            </div>
          </div>
        ) : (
          <p className="text-xs text-slate-300 mt-2">
            Click 'Run Briefing' above to synthesize your pending workload into an actionable daily focus report using Gemini AI.
          </p>
        )}
      </div>

      {/* Immediate ASAP Alerts Section */}
      <div className="bg-slate-800/80 border border-slate-700/80 rounded-2xl p-4 space-y-3">
        <div className="flex items-center justify-between border-b border-slate-700/80 pb-2">
          <div className="flex items-center space-x-2">
            <Flame className="w-4 h-4 text-rose-500 fill-rose-500/20" />
            <h3 className="text-xs font-bold text-slate-100 uppercase tracking-wider">
              Must Finish ASAP ({highPriorityTasks.length} High Priority)
            </h3>
          </div>
          <span className="text-[10px] text-rose-400 font-semibold bg-rose-500/10 px-2 py-0.5 rounded-full border border-rose-500/20">
            Alert Level High
          </span>
        </div>

        {highPriorityTasks.length === 0 ? (
          <div className="text-xs text-slate-400 flex items-center space-x-2 py-2">
            <CheckCircle2 className="w-4 h-4 text-emerald-400" />
            <span>No pending high priority items! You are all caught up.</span>
          </div>
        ) : (
          <div className="space-y-2">
            {highPriorityTasks.map((task) => (
              <div
                key={task.id}
                className="bg-slate-900/90 border border-rose-500/30 p-3 rounded-xl flex items-center justify-between"
              >
                <div>
                  <h4 className="text-xs font-bold text-slate-100">{task.title}</h4>
                  <div className="flex items-center space-x-2 mt-1 text-[10px] text-slate-400">
                    <span className="bg-slate-800 text-slate-300 px-1.5 py-0.5 rounded border border-slate-700">
                      {task.category}
                    </span>
                    {task.deadline && (
                      <span className="text-amber-300 flex items-center space-x-1">
                        <Clock className="w-3 h-3" />
                        <span>{formatRelativeTime(task.deadline).text}</span>
                      </span>
                    )}
                  </div>
                </div>

                <button
                  onClick={() =>
                    onTriggerNotification(
                      `🚨 High Priority Task Alert!`,
                      `Finish '${task.title}' today!`
                    )
                  }
                  className="p-2 bg-rose-600/20 hover:bg-rose-600/40 text-rose-300 border border-rose-500/30 rounded-xl text-xs font-medium transition cursor-pointer"
                  title="Send Test Reminder Alert"
                >
                  <Bell className="w-3.5 h-3.5" />
                </button>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Proactive Reminder Notification Settings */}
      <div className="bg-slate-800/80 border border-slate-700/80 rounded-2xl p-4 space-y-3">
        <div className="flex items-center space-x-2 border-b border-slate-700/80 pb-2">
          <Bell className="w-4 h-4 text-indigo-400" />
          <h3 className="text-xs font-bold text-slate-100 uppercase tracking-wider">
            Android Notification Manager & Alarm
          </h3>
        </div>

        <div className="space-y-2.5 text-xs">
          {/* Permission status */}
          <div className="flex items-center justify-between bg-slate-900/70 p-2.5 rounded-xl border border-slate-800">
            <div>
              <div className="font-semibold text-slate-200">Device Push Notification Permission</div>
              <div className="text-[11px] text-slate-400 capitalize">
                Status: {notificationPermission}
              </div>
            </div>

            {notificationPermission !== 'granted' ? (
              <button
                onClick={requestNotificationPermission}
                className="px-3 py-1 bg-indigo-600 hover:bg-indigo-500 text-white rounded-lg text-xs font-medium transition cursor-pointer"
              >
                Enable
              </button>
            ) : (
              <span className="text-emerald-400 text-xs font-semibold bg-emerald-500/10 px-2 py-1 rounded border border-emerald-500/20">
                Granted
              </span>
            )}
          </div>

          {/* Daily Schedule Alarm */}
          <div className="flex items-center justify-between bg-slate-900/70 p-2.5 rounded-xl border border-slate-800">
            <div>
              <div className="font-semibold text-slate-200">Daily Morning Briefing Alarm</div>
              <div className="text-[11px] text-slate-400">Scheduled via Android AlarmManager API</div>
            </div>

            <div className="flex items-center space-x-2">
              <input
                type="time"
                value={dailyAlarmTime}
                onChange={(e) => setDailyAlarmTime(e.target.value)}
                className="bg-slate-800 border border-slate-700 text-slate-100 rounded-lg px-2 py-1 text-xs outline-none"
              />
              <button
                onClick={() => setIsAlarmEnabled(!isAlarmEnabled)}
                className={`px-2.5 py-1 rounded-lg text-xs font-medium transition cursor-pointer ${
                  isAlarmEnabled
                    ? 'bg-emerald-600 text-white'
                    : 'bg-slate-800 text-slate-400 border border-slate-700'
                }`}
              >
                {isAlarmEnabled ? 'Active' : 'Off'}
              </button>
            </div>
          </div>

          {/* Test Trigger Button */}
          <button
            onClick={() =>
              onTriggerNotification(
                '📣 Proactive Reminder Simulation',
                `You have ${tasks.filter((t) => t.status === 'pending').length} tasks pending in your Room DB!`
              )
            }
            className="w-full bg-slate-800 hover:bg-slate-700 text-indigo-300 border border-slate-700 font-medium py-2 rounded-xl text-xs flex items-center justify-center space-x-1.5 transition cursor-pointer"
          >
            <Volume2 className="w-4 h-4" />
            <span>Test Proactive Banner Notification</span>
          </button>
        </div>
      </div>
    </div>
  );
};
