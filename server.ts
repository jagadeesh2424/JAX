import express from 'express';
import path from 'path';
import { fileURLToPath } from 'url';
import { createServer as createViteServer } from 'vite';
import { GoogleGenAI, Type } from '@google/genai';
import dotenv from 'dotenv';

dotenv.config();

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const PORT = 3000;

// Initialize Gemini Client
const ai = new GoogleGenAI({
  apiKey: process.env.GEMINI_API_KEY || '',
  httpOptions: {
    headers: {
      'User-Agent': 'aistudio-build',
    },
  },
});

async function startServer() {
  const app = express();

  app.use(express.json());

  // Health check endpoint
  app.get('/api/health', (_req, res) => {
    res.json({ status: 'ok', time: new Date().toISOString() });
  });

  // AI Endpoint 1: Parse plain text dump into structured task with J.A.R.V.I.S. Conversational Verification Loop
  app.post('/api/parse-task-stream', async (req, res) => {
    try {
      const { text, userNowIso, previousDraft } = req.body;

      if (!text || typeof text !== 'string' || text.trim() === '') {
        return res.status(400).json({ error: 'Text prompt is required.' });
      }

      const nowStr = userNowIso || new Date().toISOString();

      const prompt = `
Current datetime reference: ${nowStr}.
${previousDraft ? `Previous Pending Draft: ${JSON.stringify(previousDraft)}` : 'No previous draft.'}
User incoming message: "${text.trim()}"

Role: You are J.A.X. (Jagadeesh Agent X), executive AI assistant for an Android app.
Task: Determine if the user message represents an actionable TASK (e.g., "Schedule doctor call", "Pay electricity bill tomorrow") OR a personal FACT/MEMORY (e.g., "My car insurance policy number is 98122", "Wife's favorite flower is Peony").

Structured Classification Rules:
1. If itemType is "MEMORY":
   - Extract title, category (e.g., Personal, Finance, Work, Tech, Health), and detailed content/value.
   - Set isComplete to true.
   - Generate jarvisReply as a courteous confirmation (e.g., "I have logged that to your Memory Vault, Jagadeesh.").
2. If itemType is "TASK":
   - Check if essential task details (Title, Category, Priority, Deadline) are present.
   - If missing deadline or essential info, set isComplete to false, fill task draft, and generate a polite clarification counter-question in jarvisReply.
   - If complete, set isComplete to true and confirm in jarvisReply (e.g., "Right away, Jagadeesh. Scheduled task in your Room database.").
`;

      res.setHeader('Content-Type', 'text/event-stream');
      res.setHeader('Cache-Control', 'no-cache');
      res.setHeader('Connection', 'keep-alive');

      const responseStream = await ai.models.generateContentStream({
        model: 'gemini-3.6-flash',
        contents: prompt,
        config: {
          systemInstruction: 'You are J.A.X. (Jagadeesh Agent X). You act as an articulate, highly efficient AI butler and personal assistant for Jagadeesh. Always reply in character with calm elegance, precision, and respectful courtesy.',
          responseMimeType: 'application/json',
          responseSchema: {
            type: Type.OBJECT,
            properties: {
              itemType: { type: Type.STRING, description: 'TASK or MEMORY' },
              isComplete: { type: Type.BOOLEAN, description: 'True if details are clear and ready to insert into Room DB' },
              jarvisReply: { type: Type.STRING, description: 'Polite spoken response by J.A.X.' },
              title: { type: Type.STRING, description: 'Task or Memory title' },
              category: { type: Type.STRING, description: 'Work, Personal, Finance, Health, Urgent, Learning, Tech' },
              priority: { type: Type.STRING, description: 'high, medium, or low for tasks' },
              deadline: { type: Type.STRING, description: 'ISO 8601 timestamp string or empty for tasks' },
              description: { type: Type.STRING, description: 'Details for memory or task description' },
              details: { type: Type.STRING, description: 'Detailed memory facts' },
              clarificationOptions: {
                type: Type.ARRAY,
                items: {
                  type: Type.OBJECT,
                  properties: {
                    field: { type: Type.STRING },
                    label: { type: Type.STRING },
                    value: { type: Type.STRING },
                  },
                  required: ['field', 'label', 'value'],
                },
              },
            },
            required: ['itemType', 'isComplete', 'jarvisReply', 'title', 'category'],
          },
        },
      });

      let accumulated = '';
      for await (const chunk of responseStream) {
        if (chunk.text) {
          accumulated += chunk.text;
          res.write(`data: ${JSON.stringify({ chunk: chunk.text, accumulated })}\n\n`);
        }
      }

      res.write(`data: ${JSON.stringify({ done: true, fullResponse: accumulated })}\n\n`);
      res.end();
    } catch (error: any) {
      console.error('Error in /api/parse-task-stream:', error);
      res.write(`data: ${JSON.stringify({ error: error.message })}\n\n`);
      res.end();
    }
  });

  app.post('/api/parse-task', async (req, res) => {
    try {
      const { text, userNowIso, previousDraft } = req.body;

      if (!text || typeof text !== 'string' || text.trim() === '') {
        return res.status(400).json({ error: 'Text prompt is required.' });
      }

      const nowStr = userNowIso || new Date().toISOString();

      const prompt = `
Current datetime reference: ${nowStr}.
${previousDraft ? `Previous Pending Task Draft: ${JSON.stringify(previousDraft)}` : 'No previous draft.'}
User incoming message: "${text.trim()}"

Role: You are J.A.X. (Jagadeesh Agent X), the executive AI assistant for an Android task app.
Task: Evaluate the task details for completeness.
Essential information required for a complete task record:
1. Title (actionable summary of what needs to be done)
2. Category (e.g., Work, Personal, Health, Urgent, Finance, Learning)
3. Priority ("high", "medium", or "low")
4. Deadline (explicit or relative date/time like "tomorrow 5pm", "Friday 10am", "by 3pm", "August 15").

Rules for Conversational Verification Loop:
- If a previous draft was provided, merge the user's new message details into the previous draft.
- If essential information like DEADLINE is missing, OR priority/details are vague, DO NOT set isComplete to true.
- When isComplete is false:
  - Generate jarvisReply as a concise, polite counter-question in J.A.X. character (e.g. "I can schedule that electrician call, Jagadeesh. What date and time works best for you?").
  - Fill pendingDraft with whatever information is extracted so far.
  - Provide clarificationOptions chips for quick user tap responses.
- When isComplete is true (all essential details present, or user just answered missing field):
  - Generate jarvisReply as a polished, respectful confirmation in J.A.X. character (e.g. "Right away, Jagadeesh. I have scheduled 'Call electrician' for tomorrow at 3:00 PM with High priority and logged it to your Room database.").
  - Populate taskCandidate with final complete details.
`;

      const response = await ai.models.generateContent({
        model: 'gemini-3.6-flash',
        contents: prompt,
        config: {
          systemInstruction: 'You are J.A.X. (Jagadeesh Agent X). You act as an articulate, highly efficient AI butler and personal assistant for Jagadeesh. Always reply in character with calm elegance, precision, and respectful courtesy.',
          responseMimeType: 'application/json',
          responseSchema: {
            type: Type.OBJECT,
            properties: {
              isComplete: { type: Type.BOOLEAN, description: 'True if task has clear title, category, priority, and deadline. False if crucial info is missing.' },
              jarvisReply: { type: Type.STRING, description: 'Concise spoken counter-question if incomplete, or confirmation if complete.' },
              title: { type: Type.STRING, description: 'Task title' },
              category: { type: Type.STRING, description: 'Category name' },
              priority: { type: Type.STRING, description: 'high, medium, or low' },
              deadline: { type: Type.STRING, description: 'ISO 8601 timestamp string or empty/null' },
              description: { type: Type.STRING, description: 'Optional extra task notes' },
              missingFields: {
                type: Type.ARRAY,
                items: { type: Type.STRING },
                description: 'Fields missing like deadline or category',
              },
              clarificationOptions: {
                type: Type.ARRAY,
                items: {
                  type: Type.OBJECT,
                  properties: {
                    field: { type: Type.STRING },
                    label: { type: Type.STRING },
                    value: { type: Type.STRING },
                  },
                  required: ['field', 'label', 'value'],
                },
              },
            },
            required: ['isComplete', 'jarvisReply', 'title', 'category', 'priority'],
          },
        },
      });

      const parsedText = response.text || '{}';
      const parsedData = JSON.parse(parsedText);

      if (!['high', 'medium', 'low'].includes(parsedData.priority)) {
        parsedData.priority = 'medium';
      }

      res.json(parsedData);
    } catch (error: any) {
      console.error('Error in /api/parse-task:', error);
      res.status(500).json({ error: error.message || 'Failed to parse task with Gemini API' });
    }
  });

  // AI Endpoint 2: Daily Briefing Generator
  app.post('/api/daily-briefing', async (req, res) => {
    try {
      const { tasks, userNowIso } = req.body;

      if (!Array.isArray(tasks)) {
        return res.status(400).json({ error: 'Tasks array is required' });
      }

      const nowStr = userNowIso || new Date().toISOString();

      const prompt = `
Current datetime: ${nowStr}
Tasks in user's Room DB:
${JSON.stringify(tasks, null, 2)}

Provide a daily briefing as structured JSON for an Android task notification banner / executive summary.
Highlight high priority pending items, imminent deadlines, and give an encouraging productivity focus message.
`;

      const response = await ai.models.generateContent({
        model: 'gemini-3.6-flash',
        contents: prompt,
        config: {
          systemInstruction: 'You are Saner AI Productivity Assistant. Create concise, actionable daily briefings with clear prioritisations.',
          responseMimeType: 'application/json',
          responseSchema: {
            type: Type.OBJECT,
            properties: {
              greeting: { type: Type.STRING },
              summary: { type: Type.STRING },
              focusMessage: { type: Type.STRING },
              topActionableItemIds: {
                type: Type.ARRAY,
                items: { type: Type.STRING },
              },
            },
            required: ['greeting', 'summary', 'focusMessage', 'topActionableItemIds'],
          },
        },
      });

      const briefingJson = JSON.parse(response.text || '{}');
      res.json(briefingJson);
    } catch (error: any) {
      console.error('Error in /api/daily-briefing:', error);
      res.status(500).json({ error: error.message || 'Failed to generate briefing with Gemini API' });
    }
  });

  // AI Endpoint 3: Polish note or extract action items
  app.post('/api/enhance-note', async (req, res) => {
    try {
      const { noteTitle, noteContent, action } = req.body;

      if (!noteContent && !noteTitle) {
        return res.status(400).json({ error: 'Note title or content required' });
      }

      const prompt = `
Note Title: "${noteTitle || ''}"
Note Content:
"${noteContent || ''}"

Requested Enhancement Action: "${action || 'summarize'}" (options: "summarize", "format_notion", "extract_tasks")

Process this note using AI:
- If action is "format_notion": Clean up typography, format key bullet points, add relevant emojis and structure nicely like a Notion note page.
- If action is "extract_tasks": Identify actionable todo items in the note content and create a structured checklist and task candidates.
- If action is "summarize": Provide a concise executive summary and 3 key takeaways.

Return structured JSON according to schema:
- title: polished title
- content: polished formatted note content
- tags: 3 relevant hashtag strings (e.g. ["Meeting", "Ideas", "Notion"])
- extractedChecklist: array of strings representing checklist todo items
`;

      const response = await ai.models.generateContent({
        model: 'gemini-3.6-flash',
        contents: prompt,
        config: {
          systemInstruction: 'You are an AI Notion-style note editor assistant. Enhance note organization, clarity, and extract actionable items.',
          responseMimeType: 'application/json',
          responseSchema: {
            type: Type.OBJECT,
            properties: {
              title: { type: Type.STRING },
              content: { type: Type.STRING },
              tags: {
                type: Type.ARRAY,
                items: { type: Type.STRING },
              },
              extractedChecklist: {
                type: Type.ARRAY,
                items: { type: Type.STRING },
              },
            },
            required: ['title', 'content', 'tags'],
          },
        },
      });

      const noteJson = JSON.parse(response.text || '{}');
      res.json(noteJson);
    } catch (error: any) {
      console.error('Error in /api/enhance-note:', error);
      res.status(500).json({ error: error.message || 'Failed to enhance note' });
    }
  });

  // AI Endpoint 4: Gemini Text-To-Speech (AUDIO response modality with prebuilt voice)
  app.post('/api/tts', async (req, res) => {
    try {
      const { text, voice = 'Puck' } = req.body;

      if (!text || typeof text !== 'string') {
        return res.status(400).json({ error: 'Text string is required' });
      }

      const cleanText = text.replace(/[*#_`]/g, '').trim();
      if (!cleanText) {
        return res.status(400).json({ error: 'Clean text is empty' });
      }

      const response = await ai.models.generateContent({
        model: 'gemini-3.1-flash-tts-preview',
        contents: [{ parts: [{ text: cleanText }] }],
        config: {
          responseModalities: ['AUDIO'],
          speechConfig: {
            voiceConfig: {
              prebuiltVoiceConfig: { voiceName: voice },
            },
          },
        },
      });

      const candidatePart = response.candidates?.[0]?.content?.parts?.[0];
      const audioData = candidatePart?.inlineData?.data;
      const mimeType = candidatePart?.inlineData?.mimeType || 'audio/wav';

      if (!audioData) {
        return res.status(500).json({ error: 'No audio returned from Gemini TTS' });
      }

      res.json({ audioData, mimeType });
    } catch (error: any) {
      console.error('Error in /api/tts:', error);
      res.status(500).json({ error: error.message || 'Gemini TTS generation failed' });
    }
  });

  // AI Endpoint: agent step — returns the model's next JSON action for the tool loop (web agent).
  app.post('/api/agent-step', async (req, res) => {
    try {
      const { prompt } = req.body;
      if (!prompt || typeof prompt !== 'string' || prompt.trim() === '') {
        return res.status(400).json({ error: 'prompt is required.' });
      }
      const response = await ai.models.generateContent({
        model: 'gemini-3.6-flash',
        contents: prompt,
        config: {
          systemInstruction: 'You are J.A.X. (Jagadeesh Agent X), an executive AI agent. Respond with exactly one JSON object as instructed, nothing else.',
          responseMimeType: 'application/json',
        },
      });
      res.json({ text: response.text || '{}' });
    } catch (error: any) {
      console.error('Error in /api/agent-step:', error);
      res.status(500).json({ error: error.message || 'agent-step failed' });
    }
  });

  // Vite Development or Static Production Serving
  if (process.env.NODE_ENV !== 'production') {
    const vite = await createViteServer({
      server: { middlewareMode: true },
      appType: 'spa',
    });
    app.use(vite.middlewares);
  } else {
    const distPath = path.join(process.cwd(), 'dist');
    app.use(express.static(distPath));
    app.get('*', (_req, res) => {
      res.sendFile(path.join(distPath, 'index.html'));
    });
  }

  app.listen(PORT, '0.0.0.0', () => {
    console.log(`[Server] Running on http://0.0.0.0:${PORT}`);
  });
}

startServer();
