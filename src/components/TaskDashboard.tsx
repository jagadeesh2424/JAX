import React, { useState } from 'react';
import { Task, Priority, TaskStatus } from '../types';
import { RoomDB } from '../db/roomDatabase';
import { formatRelativeTime, formatFullDateTime } from '../utils/dateUtils';
import {
  Search, Filter, Plus, Flame, Star, Clock, CheckCircle2, Circle, AlertCircle,
  Tag, Trash2, Edit3, ChevronDown, ChevronUp, Sparkles, Calendar, CheckSquare, Square
} from 'lucide-react';

interface TaskDashboardProps {
  tasks: Task[];
  onTasksChanged: () => void;
  onOpenQuickAdd: () => void;
}

export const TaskDashboard: React.FC<TaskDashboardProps> = ({
  tasks,
  onTasksChanged,
  onOpenQuickAdd,
}) => {
  const [selectedCategory, setSelectedCategory] = useState<string>('All');
  const [selectedPriority, setSelectedPriority] = useState<string>('All');
  const [selectedStatus, setSelectedStatus] = useState<TaskStatus | 'All'>('pending');
  const [searchQuery, setSearchQuery] = useState<string>('');
  const [sortBy, setSortBy] = useState<'deadline' | 'priority' | 'title'>('deadline');
  const [expandedTaskId, setExpandedTaskId] = useState<string | null>(null);
  const [editingTask, setEditingTask] = useState<Task | null>(null);

  // New subtask input state per task
  const [newSubtaskTitle, setNewSubtaskTitle] = useState<string>('');

  const categories = ['All', 'Work', 'Personal', 'Health', 'Urgent', 'Finance', 'Learning'];

  // Toggle completion
  const handleToggleTaskStatus = async (task: Task) => {
    const updatedStatus: TaskStatus = task.status === 'completed' ? 'pending' : 'completed';
    const updatedTask: Task = { ...task, status: updatedStatus };
    await RoomDB.updateTask(updatedTask);
    onTasksChanged();
  };

  // Delete task
  const handleDeleteTask = async (id: string) => {
    if (confirm('Delete this task from local Room database?')) {
      await RoomDB.deleteTask(id);
      onTasksChanged();
    }
  };

  // Toggle subtask completion
  const handleToggleSubtask = async (task: Task, subtaskId: string) => {
    if (!task.subtasks) return;
    const updatedSubtasks = task.subtasks.map((st) =>
      st.id === subtaskId ? { ...st, completed: !st.completed } : st
    );
    const updatedTask = { ...task, subtasks: updatedSubtasks };
    await RoomDB.updateTask(updatedTask);
    onTasksChanged();
  };

  // Add subtask
  const handleAddSubtask = async (task: Task) => {
    if (!newSubtaskTitle.trim()) return;
    const existing = task.subtasks || [];
    const newSt = {
      id: 'st-' + Date.now(),
      title: newSubtaskTitle.trim(),
      completed: false,
    };
    const updatedTask = { ...task, subtasks: [...existing, newSt] };
    await RoomDB.updateTask(updatedTask);
    setNewSubtaskTitle('');
    onTasksChanged();
  };

  // Save edited task
  const handleSaveEdit = async () => {
    if (!editingTask) return;
    await RoomDB.updateTask(editingTask);
    setEditingTask(null);
    onTasksChanged();
  };

  // Filter tasks
  const filteredTasks = tasks.filter((task) => {
    if (selectedCategory !== 'All' && task.category !== selectedCategory) return false;
    if (selectedPriority !== 'All' && task.priority !== selectedPriority) return false;
    if (selectedStatus !== 'All' && task.status !== selectedStatus) return false;
    if (
      searchQuery.trim() &&
      !task.title.toLowerCase().includes(searchQuery.toLowerCase()) &&
      !(task.description && task.description.toLowerCase().includes(searchQuery.toLowerCase()))
    ) {
      return false;
    }
    return true;
  });

  // Sort tasks
  filteredTasks.sort((a, b) => {
    if (sortBy === 'priority') {
      const pOrder: Record<Priority, number> = { high: 1, medium: 2, low: 3 };
      return pOrder[a.priority] - pOrder[b.priority];
    } else if (sortBy === 'title') {
      return a.title.localeCompare(b.title);
    } else {
      // deadline
      if (!a.deadline) return 1;
      if (!b.deadline) return -1;
      return new Date(a.deadline).getTime() - new Date(b.deadline).getTime();
    }
  });

  const getPriorityBadge = (priority: Priority) => {
    switch (priority) {
      case 'high':
        return (
          <span className="inline-flex items-center space-x-1 bg-rose-500/15 text-rose-400 border border-rose-500/30 px-2 py-0.5 rounded-full text-[10px] font-bold">
            <Flame className="w-3 h-3 fill-rose-500 text-rose-500" />
            <span>High</span>
          </span>
        );
      case 'medium':
        return (
          <span className="inline-flex items-center space-x-1 bg-amber-500/15 text-amber-400 border border-amber-500/30 px-2 py-0.5 rounded-full text-[10px] font-bold">
            <Star className="w-3 h-3 fill-amber-500 text-amber-500" />
            <span>Medium</span>
          </span>
        );
      case 'low':
        return (
          <span className="inline-flex items-center space-x-1 bg-blue-500/15 text-blue-400 border border-blue-500/30 px-2 py-0.5 rounded-full text-[10px] font-medium">
            <span>Low</span>
          </span>
        );
    }
  };

  return (
    <div className="flex-1 flex flex-col h-full bg-slate-900 text-slate-100 overflow-hidden relative">
      {/* Search & Header Controls */}
      <div className="px-4 py-3 bg-slate-950/80 border-b border-slate-800 space-y-2.5">
        <div className="flex items-center space-x-2">
          <div className="relative flex-1">
            <Search className="w-4 h-4 text-slate-400 absolute left-3 top-2.5" />
            <input
              type="text"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder="Search tasks by title or notes..."
              className="w-full bg-slate-900 border border-slate-700 rounded-xl pl-9 pr-3 py-1.5 text-xs text-slate-100 placeholder-slate-500 outline-none focus:ring-1 focus:ring-indigo-500"
            />
          </div>

          {/* Sort Selector */}
          <select
            value={sortBy}
            onChange={(e) => setSortBy(e.target.value as any)}
            className="bg-slate-900 border border-slate-700 text-slate-300 text-xs rounded-xl px-2 py-1.5 outline-none cursor-pointer"
          >
            <option value="deadline">⏰ Deadline</option>
            <option value="priority">🔥 Priority</option>
            <option value="title">🔤 Title</option>
          </select>
        </div>

        {/* Category Filters Pills */}
        <div className="flex items-center space-x-1.5 overflow-x-auto no-scrollbar pb-0.5">
          {categories.map((cat) => (
            <button
              key={cat}
              onClick={() => setSelectedCategory(cat)}
              className={`px-3 py-1 rounded-full text-xs font-medium transition cursor-pointer whitespace-nowrap ${
                selectedCategory === cat
                  ? 'bg-indigo-600 text-white shadow-sm shadow-indigo-600/30'
                  : 'bg-slate-800 hover:bg-slate-700 text-slate-300 border border-slate-700'
              }`}
            >
              {cat}
            </button>
          ))}
        </div>

        {/* Priority & Status Sub-filters */}
        <div className="flex items-center justify-between text-xs pt-1 border-t border-slate-800/80">
          <div className="flex items-center space-x-1">
            <span className="text-slate-400 text-[11px] mr-1">Status:</span>
            {(['pending', 'completed', 'All'] as const).map((st) => (
              <button
                key={st}
                onClick={() => setSelectedStatus(st)}
                className={`px-2 py-0.5 rounded-md text-[11px] capitalize transition cursor-pointer ${
                  selectedStatus === st
                    ? 'bg-slate-700 text-indigo-300 font-semibold'
                    : 'text-slate-400 hover:text-slate-200'
                }`}
              >
                {st}
              </button>
            ))}
          </div>

          <div className="flex items-center space-x-1">
            <span className="text-slate-400 text-[11px] mr-1">Priority:</span>
            {['All', 'high', 'medium', 'low'].map((pr) => (
              <button
                key={pr}
                onClick={() => setSelectedPriority(pr)}
                className={`px-2 py-0.5 rounded-md text-[11px] capitalize transition cursor-pointer ${
                  selectedPriority === pr
                    ? 'bg-slate-700 text-amber-300 font-semibold'
                    : 'text-slate-400 hover:text-slate-200'
                }`}
              >
                {pr}
              </button>
            ))}
          </div>
        </div>
      </div>

      {/* Task List Items Container */}
      <div className="flex-1 overflow-y-auto p-4 space-y-3">
        {filteredTasks.length === 0 ? (
          <div className="h-64 flex flex-col items-center justify-center text-center p-6 border-2 border-dashed border-slate-800 rounded-2xl">
            <CheckCircle2 className="w-10 h-10 text-slate-600 mb-2" />
            <h3 className="font-semibold text-slate-300 text-sm">No tasks found</h3>
            <p className="text-xs text-slate-500 mt-1 max-w-xs">
              Try adjusting your category or priority filter, or dump a new task in the Chat Capture tab!
            </p>
            <button
              onClick={onOpenQuickAdd}
              className="mt-4 px-4 py-2 bg-indigo-600 hover:bg-indigo-500 text-white rounded-xl text-xs font-medium transition cursor-pointer flex items-center space-x-1.5 shadow-md shadow-indigo-600/20"
            >
              <Plus className="w-4 h-4" />
              <span>Add Task</span>
            </button>
          </div>
        ) : (
          filteredTasks.map((task) => {
            const relTime = formatRelativeTime(task.deadline);
            const isExpanded = expandedTaskId === task.id;
            const completedSubtasks = task.subtasks?.filter((s) => s.completed).length || 0;
            const totalSubtasks = task.subtasks?.length || 0;

            return (
              <div
                key={task.id}
                className={`bg-slate-800/90 border rounded-2xl p-3.5 shadow-sm transition-all duration-200 ${
                  task.status === 'completed'
                    ? 'border-slate-800 opacity-60 bg-slate-900/60'
                    : relTime.isOverdue
                    ? 'border-rose-500/40 bg-slate-800'
                    : 'border-slate-700/80 hover:border-slate-600'
                }`}
              >
                {/* Main Row */}
                <div className="flex items-start justify-between gap-3">
                  {/* Checkbox */}
                  <button
                    onClick={() => handleToggleTaskStatus(task)}
                    className="mt-0.5 text-slate-400 hover:text-indigo-400 transition cursor-pointer flex-shrink-0"
                  >
                    {task.status === 'completed' ? (
                      <CheckCircle2 className="w-5 h-5 text-emerald-400 fill-emerald-500/20" />
                    ) : (
                      <Circle className="w-5 h-5 text-slate-500 hover:text-indigo-400" />
                    )}
                  </button>

                  {/* Task Content */}
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center space-x-2 flex-wrap gap-y-1">
                      <h4
                        className={`text-sm font-semibold tracking-tight break-words ${
                          task.status === 'completed'
                            ? 'line-through text-slate-500'
                            : 'text-slate-100'
                        }`}
                      >
                        {task.title}
                      </h4>
                    </div>

                    {/* Deadline & Subtask Status */}
                    <div className="flex items-center space-x-3 mt-1.5 text-[11px] text-slate-400 flex-wrap gap-y-1">
                      {task.deadline && (
                        <div
                          className={`flex items-center space-x-1 px-2 py-0.5 rounded-md font-medium ${
                            relTime.isOverdue
                              ? 'bg-rose-500/20 text-rose-300 border border-rose-500/30'
                              : relTime.isToday
                              ? 'bg-amber-500/15 text-amber-300'
                              : 'bg-slate-900/60 text-slate-300'
                          }`}
                        >
                          <Clock className="w-3 h-3" />
                          <span>{relTime.text}</span>
                        </div>
                      )}

                      {totalSubtasks > 0 && (
                        <div className="flex items-center space-x-1 text-slate-400 bg-slate-900/40 px-2 py-0.5 rounded">
                          <CheckSquare className="w-3 h-3 text-indigo-400" />
                          <span>
                            {completedSubtasks}/{totalSubtasks} subtasks
                          </span>
                        </div>
                      )}
                    </div>
                  </div>

                  {/* Right Actions */}
                  <div className="flex items-center space-x-1">
                    <button
                      onClick={() => setExpandedTaskId(isExpanded ? null : task.id)}
                      className="p-1.5 hover:bg-slate-700 text-slate-400 hover:text-slate-200 rounded-lg transition cursor-pointer"
                      title="Expand Details & Subtasks"
                    >
                      {isExpanded ? <ChevronUp className="w-4 h-4" /> : <ChevronDown className="w-4 h-4" />}
                    </button>
                    <button
                      onClick={() => setEditingTask(task)}
                      className="p-1.5 hover:bg-slate-700 text-slate-400 hover:text-indigo-300 rounded-lg transition cursor-pointer"
                      title="Edit Task"
                    >
                      <Edit3 className="w-4 h-4" />
                    </button>
                    <button
                      onClick={() => handleDeleteTask(task.id)}
                      className="p-1.5 hover:bg-rose-500/20 text-slate-400 hover:text-rose-400 rounded-lg transition cursor-pointer"
                      title="Delete Task"
                    >
                      <Trash2 className="w-4 h-4" />
                    </button>
                  </div>
                </div>

                {/* Expanded Subtasks & Description Area */}
                {isExpanded && (
                  <div className="mt-3 pt-3 border-t border-slate-700/80 bg-slate-900/70 p-3 rounded-xl space-y-2.5">
                    {/* Category & Priority Details */}
                    <div className="flex items-center space-x-3 pb-2 border-b border-slate-800/80 flex-wrap gap-y-1">
                      <div className="flex items-center space-x-1.5">
                        <span className="text-[10px] text-slate-400 font-medium">Category:</span>
                        <span className="text-[10px] bg-indigo-500/15 text-indigo-300 border border-indigo-500/30 px-2 py-0.5 rounded-full font-semibold">
                          {task.category}
                        </span>
                      </div>
                      <div className="flex items-center space-x-1.5">
                        <span className="text-[10px] text-slate-400 font-medium">Priority:</span>
                        {getPriorityBadge(task.priority)}
                      </div>
                    </div>
                    {task.description && (
                      <div>
                        <span className="text-[10px] font-bold text-slate-400 uppercase tracking-wider block mb-0.5">
                          Description
                        </span>
                        <p className="text-xs text-slate-300 leading-relaxed">{task.description}</p>
                      </div>
                    )}

                    {/* Subtask Checklist */}
                    <div>
                      <span className="text-[10px] font-bold text-slate-400 uppercase tracking-wider block mb-1.5">
                        Subtasks Checklist
                      </span>

                      <div className="space-y-1.5">
                        {task.subtasks?.map((st) => (
                          <div
                            key={st.id}
                            onClick={() => handleToggleSubtask(task, st.id)}
                            className="flex items-center space-x-2 text-xs text-slate-200 cursor-pointer hover:bg-slate-800/80 p-1.5 rounded-lg transition"
                          >
                            {st.completed ? (
                              <CheckSquare className="w-4 h-4 text-emerald-400" />
                            ) : (
                              <Square className="w-4 h-4 text-slate-500" />
                            )}
                            <span className={st.completed ? 'line-through text-slate-500' : ''}>
                              {st.title}
                            </span>
                          </div>
                        ))}
                      </div>

                      {/* Add Subtask Input */}
                      <div className="flex items-center space-x-2 mt-2">
                        <input
                          type="text"
                          value={newSubtaskTitle}
                          onChange={(e) => setNewSubtaskTitle(e.target.value)}
                          placeholder="Add subtask..."
                          className="flex-1 bg-slate-800 border border-slate-700 rounded-lg px-2.5 py-1 text-xs text-slate-100 outline-none focus:ring-1 focus:ring-indigo-500"
                          onKeyDown={(e) => {
                            if (e.key === 'Enter') handleAddSubtask(task);
                          }}
                        />
                        <button
                          onClick={() => handleAddSubtask(task)}
                          className="px-2.5 py-1 bg-slate-700 hover:bg-indigo-600 text-xs font-medium text-slate-200 rounded-lg transition cursor-pointer"
                        >
                          Add
                        </button>
                      </div>
                    </div>

                    <div className="text-[10px] text-slate-500 font-mono pt-1">
                      Created: {formatFullDateTime(task.createdAt)}
                    </div>
                  </div>
                )}
              </div>
            );
          })
        )}
      </div>

      {/* Floating Action Button (Material 3 Style) */}
      <button
        onClick={onOpenQuickAdd}
        className="absolute bottom-4 right-4 w-14 h-14 bg-gradient-to-tr from-indigo-600 to-purple-600 hover:from-indigo-500 hover:to-purple-500 text-white rounded-2xl shadow-xl shadow-indigo-600/30 flex items-center justify-center transition cursor-pointer active:scale-95 z-10"
        title="Add New Task"
      >
        <Plus className="w-7 h-7" />
      </button>

      {/* Task Edit Modal */}
      {editingTask && (
        <div className="fixed inset-0 bg-slate-950/80 backdrop-blur-sm flex items-center justify-center p-4 z-50">
          <div className="bg-slate-900 border border-slate-700 rounded-2xl p-5 max-w-md w-full space-y-4 shadow-2xl">
            <h3 className="text-sm font-bold text-slate-100 flex items-center space-x-2">
              <Edit3 className="w-4 h-4 text-indigo-400" />
              <span>Edit Task Details</span>
            </h3>

            <div className="space-y-3">
              <div>
                <label className="text-xs text-slate-400 block mb-1">Title</label>
                <input
                  type="text"
                  value={editingTask.title}
                  onChange={(e) => setEditingTask({ ...editingTask, title: e.target.value })}
                  className="w-full bg-slate-800 border border-slate-700 rounded-xl px-3 py-2 text-xs text-slate-100 outline-none focus:ring-1 focus:ring-indigo-500"
                />
              </div>

              <div className="grid grid-cols-2 gap-2">
                <div>
                  <label className="text-xs text-slate-400 block mb-1">Category</label>
                  <select
                    value={editingTask.category}
                    onChange={(e) => setEditingTask({ ...editingTask, category: e.target.value })}
                    className="w-full bg-slate-800 border border-slate-700 rounded-xl px-2.5 py-2 text-xs text-slate-100 outline-none"
                  >
                    <option value="Work">Work</option>
                    <option value="Personal">Personal</option>
                    <option value="Health">Health</option>
                    <option value="Urgent">Urgent</option>
                    <option value="Finance">Finance</option>
                    <option value="Learning">Learning</option>
                  </select>
                </div>

                <div>
                  <label className="text-xs text-slate-400 block mb-1">Priority</label>
                  <select
                    value={editingTask.priority}
                    onChange={(e) => setEditingTask({ ...editingTask, priority: e.target.value as Priority })}
                    className="w-full bg-slate-800 border border-slate-700 rounded-xl px-2.5 py-2 text-xs text-slate-100 outline-none"
                  >
                    <option value="high">🔥 High</option>
                    <option value="medium">🟠 Medium</option>
                    <option value="low">🔵 Low</option>
                  </select>
                </div>
              </div>

              <div>
                <label className="text-xs text-slate-400 block mb-1">Deadline</label>
                <input
                  type="datetime-local"
                  value={
                    editingTask.deadline
                      ? new Date(editingTask.deadline).toISOString().slice(0, 16)
                      : ''
                  }
                  onChange={(e) =>
                    setEditingTask({
                      ...editingTask,
                      deadline: e.target.value ? new Date(e.target.value).toISOString() : null,
                    })
                  }
                  className="w-full bg-slate-800 border border-slate-700 rounded-xl px-3 py-2 text-xs text-slate-100 outline-none"
                />
              </div>

              <div>
                <label className="text-xs text-slate-400 block mb-1">Description / Notes</label>
                <textarea
                  value={editingTask.description || ''}
                  onChange={(e) => setEditingTask({ ...editingTask, description: e.target.value })}
                  rows={3}
                  className="w-full bg-slate-800 border border-slate-700 rounded-xl p-2.5 text-xs text-slate-100 outline-none focus:ring-1 focus:ring-indigo-500"
                />
              </div>
            </div>

            <div className="flex items-center justify-end space-x-2 pt-2 border-t border-slate-800">
              <button
                onClick={() => setEditingTask(null)}
                className="px-3.5 py-1.5 bg-slate-800 hover:bg-slate-700 text-xs font-medium text-slate-300 rounded-xl transition cursor-pointer"
              >
                Cancel
              </button>
              <button
                onClick={handleSaveEdit}
                className="px-4 py-1.5 bg-indigo-600 hover:bg-indigo-500 text-xs font-medium text-white rounded-xl transition cursor-pointer shadow-md shadow-indigo-600/20"
              >
                Save Changes
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
