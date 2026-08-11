import React, { useState } from 'react';
import { Task } from '../types';
import { RoomDB } from '../db/roomDatabase';
import {
  Calendar as CalendarIcon,
  ChevronLeft,
  ChevronRight,
  Plus,
  Clock,
  CheckCircle2,
  AlertCircle,
  Tag,
  Filter,
  Layers,
} from 'lucide-react';
import { formatRelativeTime } from '../utils/dateUtils';

interface CalendarViewScreenProps {
  tasks: Task[];
  onTasksChanged: () => void;
  onOpenQuickAddWithDate?: (dateIso: string) => void;
}

export const CalendarViewScreen: React.FC<CalendarViewScreenProps> = ({
  tasks,
  onTasksChanged,
  onOpenQuickAddWithDate,
}) => {
  const [currentDate, setCurrentDate] = useState<Date>(new Date());
  const [selectedDate, setSelectedDate] = useState<Date>(new Date());
  const [statusFilter, setStatusFilter] = useState<'all' | 'pending' | 'high_priority'>('all');

  // Month navigation helpers
  const year = currentDate.getFullYear();
  const month = currentDate.getMonth();

  const monthNames = [
    'January', 'February', 'March', 'April', 'May', 'June',
    'July', 'August', 'September', 'October', 'November', 'December',
  ];

  const daysInMonth = new Date(year, month + 1, 0).getDate();
  const firstDayOfWeek = new Date(year, month, 1).getDay();

  const handlePrevMonth = () => {
    setCurrentDate(new Date(year, month - 1, 1));
  };

  const handleNextMonth = () => {
    setCurrentDate(new Date(year, month + 1, 1));
  };

  const handleToday = () => {
    const now = new Date();
    setCurrentDate(now);
    setSelectedDate(now);
  };

  // Helper to check if two dates are same day
  const isSameDay = (d1: Date, d2: Date) => {
    return (
      d1.getFullYear() === d2.getFullYear() &&
      d1.getMonth() === d2.getMonth() &&
      d1.getDate() === d2.getDate()
    );
  };

  // Filter tasks for selected date
  const selectedDayTasks = tasks.filter((t) => {
    if (!t.deadline) return false;
    const taskDate = new Date(t.deadline);
    if (isNaN(taskDate.getTime())) return false;
    if (!isSameDay(taskDate, selectedDate)) return false;

    if (statusFilter === 'pending') return t.status !== 'completed';
    if (statusFilter === 'high_priority') return t.priority === 'high' && t.status !== 'completed';
    return true;
  });

  // Get map of tasks count per day of current month
  const getDayTaskSummary = (dayNum: number) => {
    const dayDate = new Date(year, month, dayNum);
    const dayTasks = tasks.filter((t) => {
      if (!t.deadline) return false;
      const td = new Date(t.deadline);
      return !isNaN(td.getTime()) && isSameDay(td, dayDate);
    });

    const pendingCount = dayTasks.filter((t) => t.status !== 'completed').length;
    const hasHighPriority = dayTasks.some((t) => t.priority === 'high' && t.status !== 'completed');

    return { total: dayTasks.length, pendingCount, hasHighPriority };
  };

  const toggleTaskComplete = async (task: Task) => {
    const updatedStatus = task.status === 'completed' ? 'pending' : 'completed';
    await RoomDB.updateTask({ ...task, status: updatedStatus });
    onTasksChanged();
  };

  // Build grid items
  const calendarCells = [];
  // Empty slots before month start
  for (let i = 0; i < firstDayOfWeek; i++) {
    calendarCells.push(null);
  }
  // Days of month
  for (let day = 1; day <= daysInMonth; day++) {
    calendarCells.push(day);
  }

  const isTodaySelected = isSameDay(selectedDate, new Date());

  return (
    <div className="flex-1 flex flex-col h-full bg-slate-900 text-slate-100 overflow-hidden">
      {/* Top Header */}
      <div className="p-3 bg-slate-900/90 border-b border-slate-800 flex items-center justify-between">
        <div className="flex items-center space-x-2">
          <div className="w-8 h-8 rounded-xl bg-indigo-600/20 border border-indigo-500/30 flex items-center justify-center text-indigo-400">
            <CalendarIcon className="w-4 h-4" />
          </div>
          <div>
            <h2 className="text-sm font-bold text-slate-100">Task Calendar</h2>
            <p className="text-[11px] text-slate-400">
              {monthNames[month]} {year}
            </p>
          </div>
        </div>

        <div className="flex items-center space-x-1">
          <button
            onClick={handleToday}
            className="px-2.5 py-1 text-[11px] font-medium bg-slate-800 hover:bg-slate-700 text-indigo-300 rounded-lg border border-slate-700 transition cursor-pointer"
          >
            Today
          </button>
          <button
            onClick={handlePrevMonth}
            className="p-1 hover:bg-slate-800 text-slate-300 rounded-lg transition cursor-pointer"
          >
            <ChevronLeft className="w-4 h-4" />
          </button>
          <button
            onClick={handleNextMonth}
            className="p-1 hover:bg-slate-800 text-slate-300 rounded-lg transition cursor-pointer"
          >
            <ChevronRight className="w-4 h-4" />
          </button>
        </div>
      </div>

      {/* Main Content Area */}
      <div className="flex-1 overflow-y-auto p-3 space-y-4">
        {/* Month Grid Card */}
        <div className="bg-slate-800/80 border border-slate-700/80 rounded-2xl p-3 shadow-lg">
          {/* Day of week headers */}
          <div className="grid grid-cols-7 gap-1 text-center mb-1">
            {['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'].map((d) => (
              <div key={d} className="text-[10px] font-bold text-slate-400 uppercase py-1">
                {d}
              </div>
            ))}
          </div>

          {/* Grid Days */}
          <div className="grid grid-cols-7 gap-1">
            {calendarCells.map((dayNum, idx) => {
              if (dayNum === null) {
                return <div key={`empty-${idx}`} className="h-10 rounded-xl bg-slate-900/20" />;
              }

              const cellDate = new Date(year, month, dayNum);
              const isSelected = isSameDay(cellDate, selectedDate);
              const isToday = isSameDay(cellDate, new Date());
              const { total, pendingCount, hasHighPriority } = getDayTaskSummary(dayNum);

              return (
                <button
                  key={`day-${dayNum}`}
                  onClick={() => setSelectedDate(cellDate)}
                  className={`h-11 rounded-xl p-1 flex flex-col items-center justify-between transition cursor-pointer relative border ${
                    isSelected
                      ? 'bg-indigo-600 text-white border-indigo-400 shadow-md shadow-indigo-600/30'
                      : isToday
                      ? 'bg-slate-700/80 text-indigo-300 border-indigo-500/50'
                      : 'bg-slate-900/50 hover:bg-slate-800/80 text-slate-200 border-slate-800'
                  }`}
                >
                  <span
                    className={`text-[11px] font-bold ${
                      isSelected ? 'text-white' : isToday ? 'text-indigo-400' : 'text-slate-300'
                    }`}
                  >
                    {dayNum}
                  </span>

                  {/* Task Indicators */}
                  <div className="flex items-center space-x-0.5 mb-0.5">
                    {hasHighPriority && (
                      <span className="w-1.5 h-1.5 rounded-full bg-rose-500 animate-pulse" />
                    )}
                    {pendingCount > 0 && !hasHighPriority && (
                      <span className="w-1.5 h-1.5 rounded-full bg-amber-400" />
                    )}
                    {total > 0 && pendingCount === 0 && (
                      <span className="w-1.5 h-1.5 rounded-full bg-emerald-400" />
                    )}
                  </div>
                </button>
              );
            })}
          </div>
        </div>

        {/* Selected Day Header & Filter */}
        <div className="space-y-3">
          <div className="flex items-center justify-between border-b border-slate-800 pb-2">
            <div>
              <h3 className="text-xs font-bold text-slate-100 flex items-center space-x-1.5">
                <span>
                  {selectedDate.toLocaleDateString(undefined, {
                    weekday: 'short',
                    month: 'short',
                    day: 'numeric',
                  })}
                </span>
                {isTodaySelected && (
                  <span className="px-1.5 py-0.5 text-[9px] font-semibold bg-indigo-500/20 text-indigo-300 rounded-md border border-indigo-500/30">
                    Today
                  </span>
                )}
              </h3>
              <p className="text-[10px] text-slate-400">
                {selectedDayTasks.length} task{selectedDayTasks.length === 1 ? '' : 's'} scheduled
              </p>
            </div>

            {/* Status Filter Chips */}
            <div className="flex items-center space-x-1 bg-slate-800/80 p-0.5 rounded-xl border border-slate-700/80">
              <button
                onClick={() => setStatusFilter('all')}
                className={`px-2 py-1 text-[10px] font-medium rounded-lg transition ${
                  statusFilter === 'all'
                    ? 'bg-indigo-600 text-white shadow'
                    : 'text-slate-400 hover:text-slate-200'
                }`}
              >
                All
              </button>
              <button
                onClick={() => setStatusFilter('pending')}
                className={`px-2 py-1 text-[10px] font-medium rounded-lg transition ${
                  statusFilter === 'pending'
                    ? 'bg-indigo-600 text-white shadow'
                    : 'text-slate-400 hover:text-slate-200'
                }`}
              >
                Pending
              </button>
              <button
                onClick={() => setStatusFilter('high_priority')}
                className={`px-2 py-1 text-[10px] font-medium rounded-lg transition ${
                  statusFilter === 'high_priority'
                    ? 'bg-rose-600 text-white shadow'
                    : 'text-slate-400 hover:text-slate-200'
                }`}
              >
                High Priority
              </button>
            </div>
          </div>

          {/* Agenda List for Selected Day */}
          {selectedDayTasks.length === 0 ? (
            <div className="bg-slate-800/40 border border-slate-800 rounded-2xl p-6 text-center space-y-2">
              <CalendarIcon className="w-8 h-8 text-slate-600 mx-auto" />
              <div className="text-xs font-medium text-slate-300">No tasks scheduled for this day</div>
              <p className="text-[11px] text-slate-500 max-w-xs mx-auto">
                Use AI Chat or Quick Add to schedule a task in Room DB.
              </p>
            </div>
          ) : (
            <div className="space-y-2">
              {selectedDayTasks.map((task) => {
                const isCompleted = task.status === 'completed';
                const deadlineDate = task.deadline ? new Date(task.deadline) : null;
                const formattedTime = deadlineDate
                  ? deadlineDate.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
                  : 'All day';

                return (
                  <div
                    key={task.id}
                    className={`p-3 rounded-2xl border transition flex items-start space-x-3 ${
                      isCompleted
                        ? 'bg-slate-900/60 border-slate-800/80 opacity-70'
                        : task.priority === 'high'
                        ? 'bg-slate-800/90 border-rose-500/40'
                        : 'bg-slate-800/90 border-slate-700/80'
                    }`}
                  >
                    <button
                      onClick={() => toggleTaskComplete(task)}
                      className="mt-0.5 text-slate-400 hover:text-indigo-400 transition cursor-pointer"
                    >
                      {isCompleted ? (
                        <CheckCircle2 className="w-4 h-4 text-emerald-400" />
                      ) : (
                        <div className="w-4 h-4 rounded-full border border-slate-500 hover:border-indigo-400" />
                      )}
                    </button>

                    <div className="flex-1 min-w-0">
                      <div className="flex items-center justify-between">
                        <h4
                          className={`text-xs font-bold ${
                            isCompleted ? 'line-through text-slate-500' : 'text-slate-100'
                          }`}
                        >
                          {task.title}
                        </h4>
                        <span className="text-[10px] text-indigo-300 font-mono bg-indigo-950/60 border border-indigo-500/20 px-1.5 py-0.5 rounded-md flex items-center space-x-1">
                          <Clock className="w-3 h-3 text-indigo-400" />
                          <span>{formattedTime}</span>
                        </span>
                      </div>

                      {task.description && (
                        <p className="text-[11px] text-slate-400 mt-1 line-clamp-2">
                          {task.description}
                        </p>
                      )}

                      <div className="flex items-center space-x-2 mt-2 text-[10px]">
                        <span className="px-1.5 py-0.5 bg-slate-700/60 text-slate-300 rounded-md border border-slate-600/50 flex items-center space-x-1">
                          <Tag className="w-2.5 h-2.5 text-indigo-400" />
                          <span>{task.category}</span>
                        </span>

                        <span
                          className={`px-1.5 py-0.5 rounded-md font-semibold ${
                            task.priority === 'high'
                              ? 'bg-rose-500/20 text-rose-300 border border-rose-500/30'
                              : task.priority === 'medium'
                              ? 'bg-amber-500/20 text-amber-300 border border-amber-500/30'
                              : 'bg-blue-500/20 text-blue-300 border border-blue-500/30'
                          }`}
                        >
                          {task.priority.toUpperCase()}
                        </span>
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      </div>
    </div>
  );
};
