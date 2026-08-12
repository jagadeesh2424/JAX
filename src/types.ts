export type Priority = 'high' | 'medium' | 'low';

export type TaskStatus = 'pending' | 'in_progress' | 'completed';

export interface Task {
  id: string;
  title: string;
  category: string;
  priority: Priority;
  status: TaskStatus;
  deadline: string | null; // ISO Date String
  description?: string;
  subtasks?: { id: string; title: string; completed: boolean }[];
  tags?: string[];
  createdAt: string;
  updatedAt: string;
}

export interface ChatMessage {
  id: string;
  sender: 'user' | 'ai' | 'system';
  text: string;
  taskCandidate?: Partial<Task>;
  isComplete?: boolean;
  pendingDraft?: Partial<Task>;
  missingFields?: string[];
  clarificationPrompt?: string;
  clarificationOptions?: {
    field: 'category' | 'priority' | 'deadline';
    label: string;
    value: string;
  }[];
  timestamp: string;
  isSaved?: boolean;
  jarvisSpeaking?: boolean;
}

export interface DailyBriefing {
  id: string;
  date: string;
  greeting: string;
  summary: string;
  totalPending: number;
  highPriorityCount: number;
  overdueCount: number;
  focusMessage: string;
  topActionableItemIds: string[];
  generatedAt: string;
}

export interface NoteChecklistItem {
  id: string;
  text: string;
  completed: boolean;
}

export interface NoteItem {
  id: string;
  title: string;
  content: string;
  category: string;
  tags: string[];
  isPinned: boolean;
  color: string; // hex or tailwind class
  checklist?: NoteChecklistItem[];
  createdAt: string;
  updatedAt: string;
}

export interface SqlQueryLog {
  id: string;
  query: string;
  table: string;
  action: 'INSERT' | 'UPDATE' | 'DELETE' | 'SELECT' | 'SCHEMA';
  timestamp: string;
  rowCount?: number;
}
