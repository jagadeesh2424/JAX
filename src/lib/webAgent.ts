import { RoomDB } from '../db/roomDatabase';
import { Task, NoteItem } from '../types';

// Web mirror of the Android agent (ai/agent): a JSON tool-calling loop over the existing
// RoomDB (IndexedDB + Firestore sync). Keeps both products behaviourally consistent.

type ToolResult = { success: boolean; message: string; data?: unknown };

interface WebTool {
  name: string;
  description: string;
  params: string;
  run: (args: Record<string, any>) => Promise<ToolResult>;
}

function newId(prefix: string): string {
  return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`;
}

const tools: WebTool[] = [
  {
    name: 'create_task',
    description: 'Create a new task or reminder.',
    params: 'title (required), category, priority (high|medium|low), deadline (ISO or empty)',
    run: async (a) => {
      const title = String(a.title || '').trim();
      if (!title) return { success: false, message: 'title is required' };
      const now = new Date().toISOString();
      const task: Task = {
        id: newId('task'),
        title,
        category: a.category || 'Personal',
        priority: ['high', 'medium', 'low'].includes(a.priority) ? a.priority : 'medium',
        status: 'pending',
        deadline: a.deadline || null,
        description: a.description || '',
        createdAt: now,
        updatedAt: now,
      };
      await RoomDB.insertTask(task);
      return { success: true, message: `Created task "${task.title}" (id ${task.id}).` };
    },
  },
  {
    name: 'search_tasks',
    description: 'List tasks (open and completed), optionally filtered by a keyword. Each result has a completed flag. Use to get a task id before completing or deleting it.',
    params: 'query (optional)',
    run: async (a) => {
      const q = String(a.query || '').trim().toLowerCase();
      const all = await RoomDB.getAllTasks();
      const matched = q ? all.filter((t) => t.title.toLowerCase().includes(q)) : all;
      if (!matched.length) return { success: true, message: 'No matching tasks.' };
      const data = matched.slice(0, 20).map((t) => ({
        id: t.id,
        title: t.title,
        priority: t.priority,
        deadline: t.deadline || '',
        completed: t.status === 'completed',
      }));
      return { success: true, message: `Found ${matched.length} task(s).`, data: { tasks: data } };
    },
  },
  {
    name: 'complete_task',
    description: 'Complete or cancel a task by id (from search_tasks). Set remove=true to delete it, otherwise it is marked done.',
    params: 'id (required), remove (boolean)',
    run: async (a) => {
      const id = String(a.id || '').trim();
      if (!id) return { success: false, message: 'id is required (call search_tasks first)' };
      const target = (await RoomDB.getAllTasks()).find((t) => t.id === id);
      if (!target) return { success: false, message: `No task found with id ${id}` };
      if (a.remove === true) {
        await RoomDB.deleteTask(id);
        return { success: true, message: `Deleted task "${target.title}".` };
      }
      await RoomDB.updateTask({ ...target, status: 'completed', updatedAt: new Date().toISOString() });
      return { success: true, message: `Marked "${target.title}" as completed.` };
    },
  },
  {
    name: 'create_note',
    description: 'Save a note/memory (facts, preferences, information).',
    params: 'title (required), content (required), category',
    run: async (a) => {
      const title = String(a.title || '').trim();
      const content = String(a.content || '').trim();
      if (!title || !content) return { success: false, message: 'title and content are required' };
      const now = new Date().toISOString();
      const note: NoteItem = {
        id: newId('note'),
        title,
        content,
        category: a.category || 'Personal',
        tags: [],
        isPinned: false,
        color: 'indigo',
        createdAt: now,
        updatedAt: now,
      };
      await RoomDB.insertNote(note);
      return { success: true, message: `Saved note "${note.title}".` };
    },
  },
  {
    name: 'search_notes',
    description: 'Search saved notes/memories by keyword.',
    params: 'query (required)',
    run: async (a) => {
      const q = String(a.query || '').trim().toLowerCase();
      if (!q) return { success: false, message: 'query is required' };
      const matched = (await RoomDB.getAllNotes()).filter(
        (n) => n.title.toLowerCase().includes(q) || n.content.toLowerCase().includes(q),
      );
      if (!matched.length) return { success: true, message: `No notes match "${q}".` };
      const data = matched.slice(0, 20).map((n) => ({ title: n.title, category: n.category, content: n.content }));
      return { success: true, message: `Found ${matched.length} note(s).`, data: { notes: data } };
    },
  },
];

function catalogText(): string {
  return tools.map((t) => `- ${t.name}: ${t.description} | args: [${t.params}]`).join('\n');
}

async function buildContext(): Promise<string> {
  const open = (await RoomDB.getAllTasks()).filter((t) => t.status !== 'completed');
  const tasks = open.length
    ? open.map((t) => `- (${t.id}) [${t.priority}] ${t.title}${t.deadline ? ` due ${t.deadline}` : ''}`).join('\n')
    : 'None';
  return `USER: Jagadeesh\nTODAY: ${new Date().toISOString()}\nOPEN TASKS:\n${tasks}`;
}

function buildPrompt(userInput: string, context: string, transcript: string): string {
  return `You are J.A.X. (Jagadeesh Agent X), an executive AI agent. Use tools to fulfil the request.

CONTEXT:
${context}

AVAILABLE TOOLS:
${catalogText()}

RESPONSE RULES:
- Respond with exactly ONE JSON object and nothing else.
- To call a tool: {"action":"tool","tool":"<name>","args":{ ... }}
- When the request is fully handled, or it is just conversation: {"action":"final","reply":"<concise, courteous message>"}
- To cancel/complete/delete a task, first call search_tasks to obtain its id, then call complete_task. Never invent ids.

WORK SO FAR (tool results):
${transcript || '(none yet)'}

USER REQUEST: "${userInput}"`;
}

async function generateStep(prompt: string): Promise<string> {
  const res = await fetch('/api/agent-step', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ prompt }),
  });
  if (!res.ok) throw new Error('agent-step request failed');
  const data = await res.json();
  return data.text || '';
}

function parseJson(raw: string): any | null {
  try {
    const start = raw.indexOf('{');
    const end = raw.lastIndexOf('}');
    if (start !== -1 && end > start) return JSON.parse(raw.substring(start, end + 1));
  } catch {
    /* fall through */
  }
  return null;
}

// Runs the tool loop for one chat turn and returns J.A.X.'s final reply.
export async function runWebAgent(userInput: string, maxSteps = 6): Promise<string> {
  const context = await buildContext();
  let transcript = '';

  for (let step = 0; step < maxSteps; step++) {
    const prompt = buildPrompt(userInput, context, transcript);

    let raw: string;
    try {
      raw = await generateStep(prompt);
    } catch (e: any) {
      return `J.A.X. Notice: ${e.message || 'AI request failed.'}`;
    }

    const json = parseJson(raw);
    if (!json) return raw.trim() || 'Understood, Jagadeesh.';

    if (String(json.action || 'final').toLowerCase() === 'tool') {
      const tool = tools.find((t) => t.name === json.tool);
      const args = json.args || {};
      if (!tool) {
        transcript += `\nTOOL ${json.tool} -> FAILURE: unknown tool`;
        continue;
      }
      let result: ToolResult;
      try {
        result = await tool.run(args);
      } catch (e: any) {
        result = { success: false, message: e.message || 'tool threw an exception' };
      }
      const obs = result.success
        ? `SUCCESS: ${result.message}${result.data ? ` DATA: ${JSON.stringify(result.data)}` : ''}`
        : `FAILURE: ${result.message}`;
      transcript += `\nTOOL ${tool.name} -> ${obs}`;
    } else {
      return String(json.reply || 'Done, Jagadeesh.');
    }
  }
  return "I've handled what I can for now, Jagadeesh.";
}
