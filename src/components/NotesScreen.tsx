import React, { useState, useEffect } from 'react';
import { NoteItem, NoteChecklistItem, Task } from '../types';
import { RoomDB } from '../db/roomDatabase';
import {
  FileText,
  Plus,
  Pin,
  Trash2,
  Sparkles,
  Search,
  Tag,
  CheckSquare,
  Square,
  ArrowRight,
  Edit3,
  X,
  Copy,
  Check,
  Folder,
} from 'lucide-react';

interface NotesScreenProps {
  onTasksChanged: () => void;
}

export const NotesScreen: React.FC<NotesScreenProps> = ({ onTasksChanged }) => {
  const [notes, setNotes] = useState<NoteItem[]>([]);
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedTag, setSelectedTag] = useState<string>('all');
  const [isEditorOpen, setIsEditorOpen] = useState(false);
  const [editingNote, setEditingNote] = useState<NoteItem | null>(null);
  const [isEnhancing, setIsEnhancing] = useState(false);
  const [copiedId, setCopiedId] = useState<string | null>(null);

  // Note form state
  const [title, setTitle] = useState('');
  const [content, setContent] = useState('');
  const [category, setCategory] = useState('Work');
  const [tagsInput, setTagsInput] = useState('');
  const [color, setColor] = useState('indigo');
  const [isPinned, setIsPinned] = useState(false);
  const [checklist, setChecklist] = useState<NoteChecklistItem[]>([]);
  const [newChecklistText, setNewChecklistText] = useState('');

  const loadNotes = async () => {
    const data = await RoomDB.getAllNotes();
    setNotes(data);
  };

  useEffect(() => {
    loadNotes();
    const unsubscribe = RoomDB.subscribe(loadNotes);
    return () => unsubscribe();
  }, []);

  const openNewNoteModal = () => {
    setEditingNote(null);
    setTitle('');
    setContent('');
    setCategory('Work');
    setTagsInput('Ideas');
    setColor('indigo');
    setIsPinned(false);
    setChecklist([]);
    setIsEditorOpen(true);
  };

  const openEditNoteModal = (note: NoteItem) => {
    setEditingNote(note);
    setTitle(note.title);
    setContent(note.content);
    setCategory(note.category || 'Work');
    setTagsInput(note.tags ? note.tags.join(', ') : '');
    setColor(note.color || 'indigo');
    setIsPinned(note.isPinned || false);
    setChecklist(note.checklist || []);
    setIsEditorOpen(true);
  };

  const handleSaveNote = async () => {
    if (!title.trim() && !content.trim()) return;

    const parsedTags = tagsInput
      .split(',')
      .map((t) => t.trim())
      .filter((t) => t.length > 0);

    const noteToSave: NoteItem = {
      id: editingNote ? editingNote.id : 'note-' + Date.now(),
      title: title.trim() || 'Untitled Note',
      content,
      category,
      tags: parsedTags,
      isPinned,
      color,
      checklist,
      createdAt: editingNote ? editingNote.createdAt : new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    };

    if (editingNote) {
      await RoomDB.updateNote(noteToSave);
    } else {
      await RoomDB.insertNote(noteToSave);
    }

    setIsEditorOpen(false);
    loadNotes();
  };

  const handleDeleteNote = async (id: string, e?: React.MouseEvent) => {
    if (e) e.stopPropagation();
    if (confirm('Delete this quick note from Room DB?')) {
      await RoomDB.deleteNote(id);
      if (editingNote?.id === id) setIsEditorOpen(false);
      loadNotes();
    }
  };

  const togglePin = async (note: NoteItem, e?: React.MouseEvent) => {
    if (e) e.stopPropagation();
    await RoomDB.updateNote({ ...note, isPinned: !note.isPinned });
    loadNotes();
  };

  const handleAddChecklistItem = () => {
    if (!newChecklistText.trim()) return;
    const newItem: NoteChecklistItem = {
      id: 'nc-' + Date.now(),
      text: newChecklistText.trim(),
      completed: false,
    };
    setChecklist([...checklist, newItem]);
    setNewChecklistText('');
  };

  const toggleChecklistItem = (itemId: string) => {
    setChecklist(
      checklist.map((item) =>
        item.id === itemId ? { ...item, completed: !item.completed } : item
      )
    );
  };

  const removeChecklistItem = (itemId: string) => {
    setChecklist(checklist.filter((item) => item.id !== itemId));
  };

  // Convert checklist items or note into Room DB tasks!
  const convertNoteToTasks = async (note: NoteItem, e?: React.MouseEvent) => {
    if (e) e.stopPropagation();

    let createdCount = 0;
    if (note.checklist && note.checklist.length > 0) {
      for (const item of note.checklist) {
        if (!item.completed) {
          const newTask: Task = {
            id: 'task-' + Date.now() + '-' + Math.random().toString(36).substr(2, 4),
            title: item.text,
            category: note.category || 'Work',
            priority: 'medium',
            status: 'pending',
            deadline: null,
            description: `Imported from quick note: ${note.title}`,
            createdAt: new Date().toISOString(),
            updatedAt: new Date().toISOString(),
          };
          await RoomDB.insertTask(newTask);
          createdCount++;
        }
      }
    } else {
      const newTask: Task = {
        id: 'task-' + Date.now(),
        title: note.title,
        category: note.category || 'Work',
        priority: 'medium',
        status: 'pending',
        deadline: null,
        description: note.content,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      };
      await RoomDB.insertTask(newTask);
      createdCount = 1;
    }

    onTasksChanged();
    alert(`Successfully converted ${createdCount} task(s) into Room DB!`);
  };

  // Call Gemini AI enhancement endpoint
  const handleEnhanceWithAI = async (action: 'format_notion' | 'extract_tasks' | 'summarize') => {
    setIsEnhancing(true);
    try {
      const res = await fetch('/api/enhance-note', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ noteTitle: title, noteContent: content, action }),
      });
      const data = await res.json();

      if (data.title) setTitle(data.title);
      if (data.content) setContent(data.content);
      if (data.tags && Array.isArray(data.tags)) setTagsInput(data.tags.join(', '));
      if (data.extractedChecklist && Array.isArray(data.extractedChecklist)) {
        const newItems: NoteChecklistItem[] = data.extractedChecklist.map((text: string, i: number) => ({
          id: 'nc-ai-' + i + '-' + Date.now(),
          text,
          completed: false,
        }));
        setChecklist([...checklist, ...newItems]);
      }
    } catch (err) {
      console.error('AI Note enhancement failed:', err);
    } finally {
      setIsEnhancing(false);
    }
  };

  const copyNoteContent = (note: NoteItem, e: React.MouseEvent) => {
    e.stopPropagation();
    const fullText = `${note.title}\n\n${note.content}\n${
      note.checklist ? note.checklist.map((c) => `[${c.completed ? 'x' : ' '}] ${c.text}`).join('\n') : ''
    }`;
    navigator.clipboard.writeText(fullText);
    setCopiedId(note.id);
    setTimeout(() => setCopiedId(null), 2000);
  };

  // Filter notes
  const allTags = Array.from(new Set(notes.flatMap((n) => n.tags || [])));

  const filteredNotes = notes.filter((n) => {
    const matchesQuery =
      n.title.toLowerCase().includes(searchQuery.toLowerCase()) ||
      n.content.toLowerCase().includes(searchQuery.toLowerCase()) ||
      (n.tags && n.tags.some((t) => t.toLowerCase().includes(searchQuery.toLowerCase())));

    const matchesTag = selectedTag === 'all' || (n.tags && n.tags.includes(selectedTag));

    return matchesQuery && matchesTag;
  });

  const pinnedNotes = filteredNotes.filter((n) => n.isPinned);
  const otherNotes = filteredNotes.filter((n) => !n.isPinned);

  const getColorClasses = (c: string) => {
    switch (c) {
      case 'emerald':
        return 'bg-emerald-950/40 border-emerald-500/30 text-emerald-200';
      case 'purple':
        return 'bg-purple-950/40 border-purple-500/30 text-purple-200';
      case 'amber':
        return 'bg-amber-950/40 border-amber-500/30 text-amber-200';
      case 'rose':
        return 'bg-rose-950/40 border-rose-500/30 text-rose-200';
      default:
        return 'bg-indigo-950/40 border-indigo-500/30 text-indigo-200';
    }
  };

  return (
    <div className="flex-1 flex flex-col h-full bg-slate-900 text-slate-100 overflow-hidden">
      {/* Header Bar */}
      <div className="p-3 bg-slate-900/90 border-b border-slate-800 flex items-center justify-between">
        <div className="flex items-center space-x-2">
          <div className="w-8 h-8 rounded-xl bg-indigo-600/20 border border-indigo-500/30 flex items-center justify-center text-indigo-400">
            <FileText className="w-4 h-4" />
          </div>
          <div>
            <h2 className="text-sm font-bold text-slate-100">Notion Quick Notes</h2>
            <p className="text-[11px] text-slate-400">{notes.length} notes in Room DB</p>
          </div>
        </div>

        <button
          onClick={openNewNoteModal}
          className="px-3 py-1.5 bg-indigo-600 hover:bg-indigo-500 text-white font-medium text-xs rounded-xl transition cursor-pointer flex items-center space-x-1 shadow-md shadow-indigo-600/20"
        >
          <Plus className="w-3.5 h-3.5" />
          <span>New Note</span>
        </button>
      </div>

      {/* Search & Tag Filter Bar */}
      <div className="p-3 bg-slate-900/80 border-b border-slate-800 space-y-2">
        <div className="relative">
          <Search className="w-3.5 h-3.5 text-slate-400 absolute left-3 top-2.5" />
          <input
            type="text"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder="Search notes, tags, content..."
            className="w-full bg-slate-800/90 border border-slate-700/80 rounded-xl pl-8 pr-3 py-1.5 text-xs text-slate-100 placeholder-slate-400 outline-none focus:border-indigo-500"
          />
        </div>

        {/* Tag Filters */}
        <div className="flex items-center space-x-1.5 overflow-x-auto pb-0.5 no-scrollbar text-xs">
          <button
            onClick={() => setSelectedTag('all')}
            className={`px-2.5 py-1 rounded-lg text-[11px] font-medium transition whitespace-nowrap cursor-pointer ${
              selectedTag === 'all'
                ? 'bg-indigo-600 text-white'
                : 'bg-slate-800/80 text-slate-400 hover:text-slate-200'
            }`}
          >
            All Notes
          </button>
          {allTags.map((tag) => (
            <button
              key={tag}
              onClick={() => setSelectedTag(tag)}
              className={`px-2.5 py-1 rounded-lg text-[11px] font-medium transition whitespace-nowrap cursor-pointer flex items-center space-x-1 ${
                selectedTag === tag
                  ? 'bg-indigo-600 text-white'
                  : 'bg-slate-800/80 text-slate-400 hover:text-slate-200'
              }`}
            >
              <Tag className="w-3 h-3 text-indigo-400" />
              <span>#{tag}</span>
            </button>
          ))}
        </div>
      </div>

      {/* Main Content Area */}
      <div className="flex-1 overflow-y-auto p-3 space-y-4">
        {notes.length === 0 ? (
          <div className="bg-slate-800/40 border border-slate-800 rounded-2xl p-8 text-center space-y-3">
            <div className="w-12 h-12 rounded-2xl bg-indigo-600/10 border border-indigo-500/20 flex items-center justify-center mx-auto text-indigo-400">
              <FileText className="w-6 h-6" />
            </div>
            <div>
              <h3 className="text-xs font-bold text-slate-200">No notes created yet</h3>
              <p className="text-[11px] text-slate-400 max-w-xs mx-auto mt-1">
                Create Notion-style quick notes with interactive checklists, AI formatting, and Room DB sync.
              </p>
            </div>
            <button
              onClick={openNewNoteModal}
              className="px-4 py-2 bg-indigo-600 hover:bg-indigo-500 text-xs font-medium text-white rounded-xl transition cursor-pointer shadow-lg shadow-indigo-600/30"
            >
              Create First Quick Note
            </button>
          </div>
        ) : (
          <>
            {/* Pinned Section */}
            {pinnedNotes.length > 0 && (
              <div className="space-y-2">
                <div className="flex items-center space-x-1.5 text-xs font-bold text-amber-400">
                  <Pin className="w-3.5 h-3.5 fill-amber-400" />
                  <span className="uppercase tracking-wider text-[11px]">Pinned Quick Notes</span>
                </div>
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-2.5">
                  {pinnedNotes.map((note) => (
                    <NoteCard
                      key={note.id}
                      note={note}
                      onEdit={() => openEditNoteModal(note)}
                      onPin={(e) => togglePin(note, e)}
                      onDelete={(e) => handleDeleteNote(note.id, e)}
                      onConvertToTasks={(e) => convertNoteToTasks(note, e)}
                      onCopy={(e) => copyNoteContent(note, e)}
                      isCopied={copiedId === note.id}
                      getColorClasses={getColorClasses}
                    />
                  ))}
                </div>
              </div>
            )}

            {/* Other Notes */}
            <div className="space-y-2">
              {pinnedNotes.length > 0 && otherNotes.length > 0 && (
                <div className="text-[11px] font-bold text-slate-400 uppercase tracking-wider pt-2">
                  Other Quick Notes
                </div>
              )}
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-2.5">
                {otherNotes.map((note) => (
                  <NoteCard
                    key={note.id}
                    note={note}
                    onEdit={() => openEditNoteModal(note)}
                    onPin={(e) => togglePin(note, e)}
                    onDelete={(e) => handleDeleteNote(note.id, e)}
                    onConvertToTasks={(e) => convertNoteToTasks(note, e)}
                    onCopy={(e) => copyNoteContent(note, e)}
                    isCopied={copiedId === note.id}
                    getColorClasses={getColorClasses}
                  />
                ))}
              </div>
            </div>
          </>
        )}
      </div>

      {/* Editor Modal */}
      {isEditorOpen && (
        <div className="fixed inset-0 bg-slate-950/80 backdrop-blur-sm flex items-center justify-center p-3 z-50 overflow-y-auto">
          <div className="bg-slate-900 border border-slate-700/80 rounded-2xl p-4 max-w-lg w-full space-y-3 shadow-2xl my-auto">
            <div className="flex items-center justify-between border-b border-slate-800 pb-2">
              <div className="flex items-center space-x-2">
                <Edit3 className="w-4 h-4 text-indigo-400" />
                <h3 className="text-sm font-bold text-slate-100">
                  {editingNote ? 'Edit Notion Quick Note' : 'New Notion Quick Note'}
                </h3>
              </div>
              <button
                onClick={() => setIsEditorOpen(false)}
                className="p-1 text-slate-400 hover:text-slate-200 rounded-lg transition"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            {/* AI Action Toolbar */}
            <div className="p-2 bg-indigo-950/40 border border-indigo-500/20 rounded-xl flex items-center justify-between">
              <div className="flex items-center space-x-1.5 text-xs text-indigo-300 font-medium">
                <Sparkles className="w-3.5 h-3.5 text-indigo-400" />
                <span>AI Quick Polish with Gemini 3.6</span>
              </div>
              <div className="flex items-center space-x-1">
                <button
                  type="button"
                  disabled={isEnhancing}
                  onClick={() => handleEnhanceWithAI('format_notion')}
                  className="px-2 py-1 bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 text-[10px] text-white font-medium rounded-lg transition cursor-pointer"
                >
                  {isEnhancing ? 'Formatting...' : 'Notion Format'}
                </button>
                <button
                  type="button"
                  disabled={isEnhancing}
                  onClick={() => handleEnhanceWithAI('extract_tasks')}
                  className="px-2 py-1 bg-slate-800 hover:bg-slate-700 border border-indigo-500/30 text-[10px] text-indigo-200 font-medium rounded-lg transition cursor-pointer"
                >
                  Extract Tasks
                </button>
              </div>
            </div>

            {/* Title & Category */}
            <div className="space-y-2">
              <input
                type="text"
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                placeholder="Note Title (e.g., 💡 Q3 Sprint Ideas)..."
                className="w-full bg-slate-800/90 border border-slate-700/80 rounded-xl px-3 py-2 text-xs font-bold text-slate-100 placeholder-slate-400 outline-none focus:border-indigo-500"
              />

              <div className="grid grid-cols-2 gap-2">
                <div>
                  <label className="text-[10px] text-slate-400 block mb-1">Category</label>
                  <select
                    value={category}
                    onChange={(e) => setCategory(e.target.value)}
                    className="w-full bg-slate-800 border border-slate-700 rounded-xl px-2.5 py-1.5 text-xs text-slate-200 outline-none"
                  >
                    <option value="Work">Work</option>
                    <option value="Personal">Personal</option>
                    <option value="Ideas">Ideas</option>
                    <option value="Meeting">Meeting</option>
                    <option value="Health">Health</option>
                  </select>
                </div>

                <div>
                  <label className="text-[10px] text-slate-400 block mb-1">Color Theme</label>
                  <select
                    value={color}
                    onChange={(e) => setColor(e.target.value)}
                    className="w-full bg-slate-800 border border-slate-700 rounded-xl px-2.5 py-1.5 text-xs text-slate-200 outline-none"
                  >
                    <option value="indigo">Indigo Accent</option>
                    <option value="purple">Purple Accent</option>
                    <option value="emerald">Emerald Accent</option>
                    <option value="amber">Amber Accent</option>
                    <option value="rose">Rose Accent</option>
                  </select>
                </div>
              </div>
            </div>

            {/* Note Content Textarea */}
            <div>
              <label className="text-[10px] text-slate-400 block mb-1">Body Text / Markdown</label>
              <textarea
                value={content}
                onChange={(e) => setContent(e.target.value)}
                rows={5}
                placeholder="Write quick notes, meeting minutes, thoughts, or bullet points..."
                className="w-full bg-slate-800/90 border border-slate-700/80 rounded-xl p-3 text-xs text-slate-100 placeholder-slate-400 outline-none focus:border-indigo-500 font-mono leading-relaxed resize-none"
              />
            </div>

            {/* Notion Checklist Inside Note */}
            <div className="bg-slate-800/50 border border-slate-800 rounded-xl p-3 space-y-2">
              <div className="flex items-center justify-between">
                <span className="text-[11px] font-bold text-slate-300 flex items-center space-x-1">
                  <CheckSquare className="w-3.5 h-3.5 text-indigo-400" />
                  <span>Interactive Note Checklist</span>
                </span>
                <span className="text-[10px] text-slate-400">
                  {checklist.filter((c) => c.completed).length} / {checklist.length} done
                </span>
              </div>

              {checklist.map((item) => (
                <div key={item.id} className="flex items-center justify-between text-xs group">
                  <button
                    type="button"
                    onClick={() => toggleChecklistItem(item.id)}
                    className="flex items-center space-x-2 text-slate-200 text-left min-w-0 flex-1"
                  >
                    {item.completed ? (
                      <CheckSquare className="w-4 h-4 text-emerald-400 flex-shrink-0" />
                    ) : (
                      <Square className="w-4 h-4 text-slate-500 flex-shrink-0" />
                    )}
                    <span className={`truncate ${item.completed ? 'line-through text-slate-500' : ''}`}>
                      {item.text}
                    </span>
                  </button>
                  <button
                    type="button"
                    onClick={() => removeChecklistItem(item.id)}
                    className="text-slate-500 hover:text-rose-400 p-1 opacity-0 group-hover:opacity-100 transition"
                  >
                    <X className="w-3 h-3" />
                  </button>
                </div>
              ))}

              <div className="flex items-center space-x-1 pt-1">
                <input
                  type="text"
                  value={newChecklistText}
                  onChange={(e) => setNewChecklistText(e.target.value)}
                  onKeyDown={(e) => e.key === 'Enter' && (e.preventDefault(), handleAddChecklistItem())}
                  placeholder="Add a checklist item..."
                  className="flex-1 bg-slate-800 border border-slate-700/80 rounded-lg px-2.5 py-1 text-xs text-slate-100 outline-none"
                />
                <button
                  type="button"
                  onClick={handleAddChecklistItem}
                  className="px-2.5 py-1 bg-slate-700 hover:bg-slate-600 text-xs text-slate-200 rounded-lg transition"
                >
                  Add
                </button>
              </div>
            </div>

            {/* Tags & Pin */}
            <div className="flex items-center space-x-2">
              <div className="flex-1">
                <input
                  type="text"
                  value={tagsInput}
                  onChange={(e) => setTagsInput(e.target.value)}
                  placeholder="Tags comma separated (e.g. Ideas, Notion, Urgent)"
                  className="w-full bg-slate-800 border border-slate-700/80 rounded-xl px-3 py-1.5 text-xs text-slate-200 outline-none"
                />
              </div>

              <button
                type="button"
                onClick={() => setIsPinned(!isPinned)}
                className={`px-3 py-1.5 rounded-xl border text-xs font-medium flex items-center space-x-1 transition cursor-pointer ${
                  isPinned
                    ? 'bg-amber-500/20 text-amber-300 border-amber-500/40'
                    : 'bg-slate-800 text-slate-400 border-slate-700 hover:text-slate-200'
                }`}
              >
                <Pin className={`w-3.5 h-3.5 ${isPinned ? 'fill-amber-400' : ''}`} />
                <span>{isPinned ? 'Pinned' : 'Pin Note'}</span>
              </button>
            </div>

            {/* Footer Buttons */}
            <div className="flex items-center justify-end space-x-2 pt-2 border-t border-slate-800">
              <button
                type="button"
                onClick={() => setIsEditorOpen(false)}
                className="px-3.5 py-1.5 bg-slate-800 hover:bg-slate-700 text-xs font-medium text-slate-300 rounded-xl transition cursor-pointer"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={handleSaveNote}
                className="px-4 py-1.5 bg-indigo-600 hover:bg-indigo-500 text-xs font-medium text-white rounded-xl transition cursor-pointer shadow-md shadow-indigo-600/20"
              >
                Save Note
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

interface NoteCardProps {
  note: NoteItem;
  onEdit: () => void;
  onPin: (e: React.MouseEvent) => void;
  onDelete: (e: React.MouseEvent) => void;
  onConvertToTasks: (e: React.MouseEvent) => void;
  onCopy: (e: React.MouseEvent) => void;
  isCopied: boolean;
  getColorClasses: (c: string) => string;
}

const NoteCard: React.FC<NoteCardProps> = ({
  note,
  onEdit,
  onPin,
  onDelete,
  onConvertToTasks,
  onCopy,
  isCopied,
  getColorClasses,
}) => {
  return (
    <div
      onClick={onEdit}
      className={`p-3.5 rounded-2xl border transition cursor-pointer flex flex-col justify-between space-y-2.5 shadow-lg group hover:border-slate-500 ${getColorClasses(
        note.color || 'indigo'
      )}`}
    >
      <div>
        <div className="flex items-start justify-between">
          <h3 className="text-xs font-bold text-slate-100 line-clamp-1 pr-2">{note.title}</h3>
          <div className="flex items-center space-x-1 flex-shrink-0">
            <button
              onClick={onPin}
              title={note.isPinned ? 'Unpin' : 'Pin'}
              className="p-1 text-slate-400 hover:text-amber-400 rounded transition"
            >
              <Pin className={`w-3.5 h-3.5 ${note.isPinned ? 'fill-amber-400 text-amber-400' : ''}`} />
            </button>
            <button
              onClick={onCopy}
              title="Copy Note Text"
              className="p-1 text-slate-400 hover:text-indigo-300 rounded transition"
            >
              {isCopied ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
            </button>
            <button
              onClick={onDelete}
              title="Delete Note"
              className="p-1 text-slate-400 hover:text-rose-400 rounded transition"
            >
              <Trash2 className="w-3.5 h-3.5" />
            </button>
          </div>
        </div>

        {/* Content excerpt */}
        {note.content && (
          <p className="text-[11px] text-slate-300/90 mt-1 line-clamp-3 whitespace-pre-wrap font-sans">
            {note.content}
          </p>
        )}

        {/* Checklist Excerpt */}
        {note.checklist && note.checklist.length > 0 && (
          <div className="mt-2 space-y-1 bg-slate-900/40 p-2 rounded-xl border border-slate-800/80">
            {note.checklist.slice(0, 3).map((item) => (
              <div key={item.id} className="flex items-center space-x-1.5 text-[10px] text-slate-300">
                {item.completed ? (
                  <CheckSquare className="w-3 h-3 text-emerald-400 flex-shrink-0" />
                ) : (
                  <Square className="w-3 h-3 text-slate-500 flex-shrink-0" />
                )}
                <span className={`truncate ${item.completed ? 'line-through text-slate-500' : ''}`}>
                  {item.text}
                </span>
              </div>
            ))}
            {note.checklist.length > 3 && (
              <div className="text-[9px] text-slate-400 pt-0.5">
                + {note.checklist.length - 3} more checklist items
              </div>
            )}
          </div>
        )}
      </div>

      {/* Card Footer */}
      <div className="flex items-center justify-between border-t border-slate-800/60 pt-2 text-[10px]">
        <div className="flex items-center space-x-1">
          {note.tags &&
            note.tags.map((tag) => (
              <span
                key={tag}
                className="px-1.5 py-0.5 bg-slate-900/60 text-indigo-300 rounded-md border border-indigo-500/20"
              >
                #{tag}
              </span>
            ))}
        </div>

        <button
          onClick={onConvertToTasks}
          title="Convert checklist to Room DB Tasks"
          className="px-2 py-0.5 bg-indigo-600/30 hover:bg-indigo-600 text-indigo-200 hover:text-white rounded-lg transition border border-indigo-500/30 flex items-center space-x-1"
        >
          <span>To Task</span>
          <ArrowRight className="w-2.5 h-2.5" />
        </button>
      </div>
    </div>
  );
};
