import { Task, DailyBriefing, SqlQueryLog, Priority, TaskStatus, NoteItem } from '../types';
import { db as firestoreDb } from '../lib/firebase';
import { collection, doc, getDocs, setDoc, deleteDoc, onSnapshot } from 'firebase/firestore';

const DB_NAME = 'SanerRoomDatabase.db';
const DB_VERSION = 2;

class RoomDatabaseImpl {
  private db: IDBDatabase | null = null;
  private listeners: Set<() => void> = new Set();
  private sqlLogs: SqlQueryLog[] = [];
  private logListeners: Set<(logs: SqlQueryLog[]) => void> = new Set();
  private isFirestoreSynced = false;
  private syncStatus: 'connected' | 'connecting' | 'error' = 'connecting';

  async init(): Promise<void> {
    if (this.db) return;

    return new Promise((resolve, reject) => {
      const request = indexedDB.open(DB_NAME, DB_VERSION);

      request.onupgradeneeded = (event: IDBVersionChangeEvent) => {
        const db = (event.target as IDBOpenDBRequest).result;
        
        // Log schema creation
        this.addLog({
          id: 'log-' + Date.now() + '-schema-1',
          query: 'CREATE TABLE IF NOT EXISTS task_table / notes_table',
          table: 'schema',
          action: 'SCHEMA',
          timestamp: new Date().toISOString()
        });

        if (!db.objectStoreNames.contains('task_table')) {
          const taskStore = db.createObjectStore('task_table', { keyPath: 'id' });
          taskStore.createIndex('category', 'category', { unique: false });
          taskStore.createIndex('priority', 'priority', { unique: false });
          taskStore.createIndex('status', 'status', { unique: false });
          taskStore.createIndex('deadline', 'deadline', { unique: false });
        }

        if (!db.objectStoreNames.contains('briefing_table')) {
          db.createObjectStore('briefing_table', { keyPath: 'id' });
        }

        if (!db.objectStoreNames.contains('notes_table')) {
          const notesStore = db.createObjectStore('notes_table', { keyPath: 'id' });
          notesStore.createIndex('category', 'category', { unique: false });
          notesStore.createIndex('isPinned', 'isPinned', { unique: false });
        }

        if (!db.objectStoreNames.contains('categories_table')) {
          const catStore = db.createObjectStore('categories_table', { keyPath: 'name' });
          catStore.add({ name: 'Work', color: '#6366F1', icon: 'Briefcase' });
          catStore.add({ name: 'Personal', color: '#EC4899', icon: 'User' });
          catStore.add({ name: 'Health', color: '#10B981', icon: 'Heart' });
          catStore.add({ name: 'Urgent', color: '#EF4444', icon: 'AlertTriangle' });
          catStore.add({ name: 'Finance', color: '#F59E0B', icon: 'DollarSign' });
        }
      };

      request.onsuccess = (event) => {
        this.db = (event.target as IDBOpenDBRequest).result;
        this.seedInitialDataIfEmpty().then(() => {
          this.syncWithFirestore();
          resolve();
        });
      };

      request.onerror = (event) => {
        console.error('Room SQLite Database initialization error:', (event.target as IDBOpenDBRequest).error);
        reject((event.target as IDBOpenDBRequest).error);
      };
    });
  }

  private addLog(log: SqlQueryLog) {
    this.sqlLogs = [log, ...this.sqlLogs.slice(0, 99)];
    this.notifyLogListeners();
  }

  public getSqlLogs(): SqlQueryLog[] {
    return this.sqlLogs;
  }

  public subscribeSqlLogs(listener: (logs: SqlQueryLog[]) => void): () => void {
    this.logListeners.add(listener);
    listener(this.sqlLogs);
    return () => this.logListeners.delete(listener);
  }

  private notifyLogListeners() {
    this.logListeners.forEach(fn => fn(this.sqlLogs));
  }

  public subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  private notify() {
    this.listeners.forEach(fn => fn());
  }

  private async seedInitialDataIfEmpty() {
    const tasks = await this.getAllTasks();
    if (tasks.length === 0) {
      const now = new Date();
      const tomorrow = new Date(now.getTime() + 24 * 60 * 60 * 1000);
      tomorrow.setHours(17, 0, 0, 0);

      const inTwoHours = new Date(now.getTime() + 2 * 60 * 60 * 1000);

      const defaultTasks: Task[] = [
        {
          id: 'task-1',
          title: 'Submit quarterly project report to management',
          category: 'Work',
          priority: 'high',
          status: 'pending',
          deadline: tomorrow.toISOString(),
          description: 'Include key metrics, financial summary, and Q3 roadmaps.',
          subtasks: [
            { id: 'st-1', title: 'Gather metrics from analytics dashboard', completed: true },
            { id: 'st-2', title: 'Write executive summary', completed: false },
            { id: 'st-3', title: 'Proofread slides', completed: false },
          ],
          tags: ['Report', 'Q3', 'Executive'],
          createdAt: now.toISOString(),
          updatedAt: now.toISOString()
        },
        {
          id: 'task-2',
          title: 'Doctor appointment & prescription renewal',
          category: 'Health',
          priority: 'medium',
          status: 'pending',
          deadline: inTwoHours.toISOString(),
          description: 'Bring blood test results and medication history.',
          tags: ['Doctor', 'Health'],
          createdAt: now.toISOString(),
          updatedAt: now.toISOString()
        },
        {
          id: 'task-3',
          title: 'Review pull request for Android task offline sync',
          category: 'Work',
          priority: 'high',
          status: 'pending',
          deadline: new Date(now.getTime() + 4 * 60 * 60 * 1000).toISOString(),
          description: 'Verify Room DAO transactions and IndexedDB migrations.',
          tags: ['Code', 'Room'],
          createdAt: now.toISOString(),
          updatedAt: now.toISOString()
        },
        {
          id: 'task-4',
          title: 'Pay monthly utility bills',
          category: 'Finance',
          priority: 'low',
          status: 'completed',
          deadline: new Date(now.getTime() - 12 * 60 * 60 * 1000).toISOString(),
          description: 'Electricity & Internet bills cleared via auto-pay.',
          createdAt: new Date(now.getTime() - 24 * 60 * 60 * 1000).toISOString(),
          updatedAt: now.toISOString()
        }
      ];

      for (const t of defaultTasks) {
        await this.insertTask(t, true);
      }
    }

    const notes = await this.getAllNotes();
    if (notes.length === 0) {
      const defaultNotes: NoteItem[] = [
        {
          id: 'note-1',
          title: '💡 Product Ideas & Saner.ai Features',
          content: 'Key ideas for next release:\n• AI Voice dump auto-summarizer\n• Notion-style rich quick notes with interactive checkboxes\n• Android Calendar deadline grid sync\n• Room SQLite live transaction log stream',
          category: 'Work',
          tags: ['Ideas', 'Notion', 'Features'],
          isPinned: true,
          color: 'indigo',
          checklist: [
            { id: 'nc-1', text: 'Design Notion-like note editor layout', completed: true },
            { id: 'nc-2', text: 'Add interactive checklist items inside notes', completed: true },
            { id: 'nc-3', text: 'Integrate Gemini AI note polishing endpoint', completed: false },
          ],
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
        },
        {
          id: 'note-2',
          title: '📝 Weekly Meeting Notes - Sprint Sync',
          content: 'Discussion points with team:\n- Finalize Q3 milestones and deliverables.\n- Offline database synchronization rules.\n- Daily notification scheduling logic via Android AlarmManager API.',
          category: 'Work',
          tags: ['Meeting', 'Sprint'],
          isPinned: false,
          color: 'purple',
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
        },
        {
          id: 'note-3',
          title: '🛒 Weekend Grocery & Errands Scratchpad',
          content: 'Quick list of items to pick up on Saturday morning:',
          category: 'Personal',
          tags: ['Shopping', 'Weekend'],
          isPinned: false,
          color: 'emerald',
          checklist: [
            { id: 'nc-10', text: 'Organic coffee beans', completed: true },
            { id: 'nc-11', text: 'Almond milk & Greek yogurt', completed: false },
            { id: 'nc-12', text: 'Pick up prescription from pharmacy', completed: false },
          ],
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
        }
      ];

      for (const n of defaultNotes) {
        await this.insertNote(n, true);
      }
    }
  }

  // --- Firestore Sync Infrastructure ---

  private async pushTaskToFirestore(task: Task) {
    try {
      await Promise.allSettled([
        setDoc(doc(firestoreDb, 'tasks', task.id), task),
        setDoc(doc(firestoreDb, 'users', 'default_user', 'tasks', task.id), task)
      ]);
    } catch (e) {
      console.warn('Firestore task sync warning:', e);
    }
  }

  private async deleteTaskFromFirestore(id: string) {
    try {
      await Promise.allSettled([
        deleteDoc(doc(firestoreDb, 'tasks', id)),
        deleteDoc(doc(firestoreDb, 'users', 'default_user', 'tasks', id))
      ]);
    } catch (e) {
      console.warn('Firestore task delete warning:', e);
    }
  }

  private async pushNoteToFirestore(note: NoteItem) {
    try {
      await Promise.allSettled([
        setDoc(doc(firestoreDb, 'notes', note.id), note),
        setDoc(doc(firestoreDb, 'users', 'default_user', 'facts', note.id), note)
      ]);
    } catch (e) {
      console.warn('Firestore note/fact sync warning:', e);
    }
  }

  private async deleteNoteFromFirestore(id: string) {
    try {
      await Promise.allSettled([
        deleteDoc(doc(firestoreDb, 'notes', id)),
        deleteDoc(doc(firestoreDb, 'users', 'default_user', 'facts', id))
      ]);
    } catch (e) {
      console.warn('Firestore note/fact delete warning:', e);
    }
  }

  private async insertTaskLocally(task: Task): Promise<void> {
    if (!this.db) return;
    return new Promise((resolve) => {
      const tx = this.db!.transaction('task_table', 'readwrite');
      const store = tx.objectStore('task_table');
      store.put(task);
      tx.oncomplete = () => resolve();
      tx.onerror = () => resolve();
    });
  }

  private async insertNoteLocally(note: NoteItem): Promise<void> {
    if (!this.db) return;
    return new Promise((resolve) => {
      const tx = this.db!.transaction('notes_table', 'readwrite');
      const store = tx.objectStore('notes_table');
      store.put(note);
      tx.oncomplete = () => resolve();
      tx.onerror = () => resolve();
    });
  }

  private async syncWithFirestore() {
    if (this.isFirestoreSynced) return;
    try {
      // 1. Fetch tasks from Firestore (/users/default_user/tasks or root /tasks)
      let tasksSnap = await getDocs(collection(firestoreDb, 'users', 'default_user', 'tasks'));
      if (tasksSnap.empty) {
        tasksSnap = await getDocs(collection(firestoreDb, 'tasks'));
      }

      if (!tasksSnap.empty) {
        for (const docSnap of tasksSnap.docs) {
          const t = docSnap.data() as Task;
          await this.insertTaskLocally(t);
        }
      } else {
        // Push local seed tasks to Firestore
        const localTasks = await this.getAllTasks();
        for (const t of localTasks) {
          await this.pushTaskToFirestore(t);
        }
      }

      // 2. Fetch notes/facts from Firestore
      let notesSnap = await getDocs(collection(firestoreDb, 'users', 'default_user', 'facts'));
      if (notesSnap.empty) {
        notesSnap = await getDocs(collection(firestoreDb, 'notes'));
      }

      if (!notesSnap.empty) {
        for (const docSnap of notesSnap.docs) {
          const n = docSnap.data() as NoteItem;
          await this.insertNoteLocally(n);
        }
      } else {
        // Push local seed notes to Firestore
        const localNotes = await this.getAllNotes();
        for (const n of localNotes) {
          await this.pushNoteToFirestore(n);
        }
      }

      this.isFirestoreSynced = true;
      this.syncStatus = 'connected';
      this.notify();
    } catch (e) {
      console.warn('Firestore sync status:', e);
      this.syncStatus = 'error';
    }
  }

  public getSyncStatus() {
    return this.syncStatus;
  }

  // --- Notes DAO Implementation ---

  async getAllNotes(): Promise<NoteItem[]> {
    await this.init();
    return new Promise((resolve, reject) => {
      if (!this.db!.objectStoreNames.contains('notes_table')) {
        resolve([]);
        return;
      }
      const tx = this.db!.transaction('notes_table', 'readonly');
      const store = tx.objectStore('notes_table');
      const request = store.getAll();

      request.onsuccess = () => {
        const result = request.result as NoteItem[];
        this.addLog({
          id: 'log-' + Date.now() + '-sel-notes',
          query: 'SELECT * FROM notes_table ORDER BY isPinned DESC, updatedAt DESC',
          table: 'notes_table',
          action: 'SELECT',
          timestamp: new Date().toISOString(),
          rowCount: result.length
        });
        resolve(result);
      };

      request.onerror = () => reject(request.error);
    });
  }

  async insertNote(note: NoteItem, silent = false): Promise<void> {
    await this.init();
    this.pushNoteToFirestore(note);
    return new Promise((resolve, reject) => {
      const tx = this.db!.transaction('notes_table', 'readwrite');
      const store = tx.objectStore('notes_table');
      const request = store.put(note);

      request.onsuccess = () => {
        if (!silent) {
          this.addLog({
            id: 'log-' + Date.now() + '-ins-note',
            query: `INSERT INTO notes_table (id, title, category, isPinned) VALUES ('${note.id}', '${note.title.replace(/'/g, "''")}', '${note.category}', ${note.isPinned})`,
            table: 'notes_table',
            action: 'INSERT',
            timestamp: new Date().toISOString(),
            rowCount: 1
          });
          this.notify();
        }
        resolve();
      };

      request.onerror = () => reject(request.error);
    });
  }

  async updateNote(note: NoteItem): Promise<void> {
    await this.init();
    note.updatedAt = new Date().toISOString();
    this.pushNoteToFirestore(note);
    return new Promise((resolve, reject) => {
      const tx = this.db!.transaction('notes_table', 'readwrite');
      const store = tx.objectStore('notes_table');
      const request = store.put(note);

      request.onsuccess = () => {
        this.addLog({
          id: 'log-' + Date.now() + '-upd-note',
          query: `UPDATE notes_table SET title = '${note.title.replace(/'/g, "''")}', isPinned = ${note.isPinned} WHERE id = '${note.id}'`,
          table: 'notes_table',
          action: 'UPDATE',
          timestamp: new Date().toISOString(),
          rowCount: 1
        });
        this.notify();
        resolve();
      };

      request.onerror = () => reject(request.error);
    });
  }

  async deleteNote(id: string): Promise<void> {
    await this.init();
    this.deleteNoteFromFirestore(id);
    return new Promise((resolve, reject) => {
      const tx = this.db!.transaction('notes_table', 'readwrite');
      const store = tx.objectStore('notes_table');
      const request = store.delete(id);

      request.onsuccess = () => {
        this.addLog({
          id: 'log-' + Date.now() + '-del-note',
          query: `DELETE FROM notes_table WHERE id = '${id}'`,
          table: 'notes_table',
          action: 'DELETE',
          timestamp: new Date().toISOString(),
          rowCount: 1
        });
        this.notify();
        resolve();
      };

      request.onerror = () => reject(request.error);
    });
  }

  // --- Task DAO Implementation ---

  async getAllTasks(): Promise<Task[]> {
    await this.init();
    return new Promise((resolve, reject) => {
      const tx = this.db!.transaction('task_table', 'readonly');
      const store = tx.objectStore('task_table');
      const request = store.getAll();

      request.onsuccess = () => {
        const result = request.result as Task[];
        this.addLog({
          id: 'log-' + Date.now() + '-sel-all',
          query: 'SELECT * FROM task_table ORDER BY deadline ASC',
          table: 'task_table',
          action: 'SELECT',
          timestamp: new Date().toISOString(),
          rowCount: result.length
        });
        resolve(result);
      };

      request.onerror = () => reject(request.error);
    });
  }

  async insertTask(task: Task, silent = false): Promise<void> {
    await this.init();
    this.pushTaskToFirestore(task);
    return new Promise((resolve, reject) => {
      const tx = this.db!.transaction('task_table', 'readwrite');
      const store = tx.objectStore('task_table');
      const request = store.put(task);

      request.onsuccess = () => {
        if (!silent) {
          this.addLog({
            id: 'log-' + Date.now() + '-ins',
            query: `INSERT INTO task_table (id, title, category, priority, status, deadline) VALUES ('${task.id}', '${task.title.replace(/'/g, "''")}', '${task.category}', '${task.priority}', '${task.status}', '${task.deadline}')`,
            table: 'task_table',
            action: 'INSERT',
            timestamp: new Date().toISOString(),
            rowCount: 1
          });
          this.notify();
        }
        resolve();
      };

      request.onerror = () => reject(request.error);
    });
  }

  async updateTask(task: Task): Promise<void> {
    await this.init();
    task.updatedAt = new Date().toISOString();
    this.pushTaskToFirestore(task);
    return new Promise((resolve, reject) => {
      const tx = this.db!.transaction('task_table', 'readwrite');
      const store = tx.objectStore('task_table');
      const request = store.put(task);

      request.onsuccess = () => {
        this.addLog({
          id: 'log-' + Date.now() + '-upd',
          query: `UPDATE task_table SET title = '${task.title.replace(/'/g, "''")}', priority = '${task.priority}', status = '${task.status}', category = '${task.category}' WHERE id = '${task.id}'`,
          table: 'task_table',
          action: 'UPDATE',
          timestamp: new Date().toISOString(),
          rowCount: 1
        });
        this.notify();
        resolve();
      };

      request.onerror = () => reject(request.error);
    });
  }

  async deleteTask(id: string): Promise<void> {
    await this.init();
    this.deleteTaskFromFirestore(id);
    return new Promise((resolve, reject) => {
      const tx = this.db!.transaction('task_table', 'readwrite');
      const store = tx.objectStore('task_table');
      const request = store.delete(id);

      request.onsuccess = () => {
        this.addLog({
          id: 'log-' + Date.now() + '-del',
          query: `DELETE FROM task_table WHERE id = '${id}'`,
          table: 'task_table',
          action: 'DELETE',
          timestamp: new Date().toISOString(),
          rowCount: 1
        });
        this.notify();
        resolve();
      };

      request.onerror = () => reject(request.error);
    });
  }


  async clearAllTasks(): Promise<void> {
    await this.init();
    return new Promise((resolve, reject) => {
      const tx = this.db!.transaction('task_table', 'readwrite');
      const store = tx.objectStore('task_table');
      const request = store.clear();

      request.onsuccess = () => {
        this.addLog({
          id: 'log-' + Date.now() + '-clear',
          query: 'DELETE FROM task_table',
          table: 'task_table',
          action: 'DELETE',
          timestamp: new Date().toISOString()
        });
        this.notify();
        resolve();
      };

      request.onerror = () => reject(request.error);
    });
  }

  // --- Daily Briefing DAO ---

  async saveDailyBriefing(briefing: DailyBriefing): Promise<void> {
    await this.init();
    return new Promise((resolve, reject) => {
      const tx = this.db!.transaction('briefing_table', 'readwrite');
      const store = tx.objectStore('briefing_table');
      const request = store.put(briefing);

      request.onsuccess = () => {
        this.addLog({
          id: 'log-' + Date.now() + '-ins-briefing',
          query: `INSERT INTO briefing_table (id, date, summary) VALUES ('${briefing.id}', '${briefing.date}', '${briefing.summary.substring(0, 30)}...')`,
          table: 'briefing_table',
          action: 'INSERT',
          timestamp: new Date().toISOString(),
          rowCount: 1
        });
        resolve();
      };

      request.onerror = () => reject(request.error);
    });
  }

  async getLatestBriefing(): Promise<DailyBriefing | null> {
    await this.init();
    return new Promise((resolve, reject) => {
      const tx = this.db!.transaction('briefing_table', 'readonly');
      const store = tx.objectStore('briefing_table');
      const request = store.getAll();

      request.onsuccess = () => {
        const briefings = request.result as DailyBriefing[];
        if (briefings.length === 0) {
          resolve(null);
        } else {
          briefings.sort((a, b) => new Date(b.generatedAt).getTime() - new Date(a.generatedAt).getTime());
          resolve(briefings[0]);
        }
      };

      request.onerror = () => reject(request.error);
    });
  }

  async exportDatabaseJson(): Promise<string> {
    const tasks = await this.getAllTasks();
    const briefing = await this.getLatestBriefing();
    return JSON.stringify({
      schemaVersion: DB_VERSION,
      database: DB_NAME,
      exportedAt: new Date().toISOString(),
      task_table: tasks,
      briefing_table: briefing ? [briefing] : []
    }, null, 2);
  }
}

export const RoomDB = new RoomDatabaseImpl();
