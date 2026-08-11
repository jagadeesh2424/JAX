import React, { useState, useRef, useEffect } from 'react';
import { Send, Mic, CheckCircle2, Clock, Tag, AlertCircle, Volume2, RefreshCw, Bot, User } from 'lucide-react';
import { ChatMessage, Task, Priority } from '../types';
import { RoomDB } from '../db/roomDatabase';
import { formatFullDateTime } from '../utils/dateUtils';

// Helper to wrap raw 24kHz 16-bit 1-channel PCM audio bytes into a WAV blob for browser playback
function pcmToWavBlob(pcmBytes: Uint8Array, sampleRate = 24000, numChannels = 1, bitsPerSample = 16): Blob {
  const dataLength = pcmBytes.length;
  const header = new ArrayBuffer(44);
  const view = new DataView(header);

  /* RIFF identifier */
  view.setUint32(0, 0x52494646, false); // "RIFF"
  /* file length */
  view.setUint32(4, 36 + dataLength, true);
  /* RIFF type */
  view.setUint32(8, 0x57415645, false); // "WAVE"
  /* format chunk identifier */
  view.setUint32(12, 0x666d7420, false); // "fmt "
  /* format chunk length */
  view.setUint32(16, 16, true);
  /* sample format (raw PCM = 1) */
  view.setUint16(20, 1, true);
  /* channel count */
  view.setUint16(22, numChannels, true);
  /* sample rate */
  view.setUint32(24, sampleRate, true);
  /* byte rate */
  view.setUint32(28, sampleRate * numChannels * (bitsPerSample / 8), true);
  /* block align */
  view.setUint16(32, numChannels * (bitsPerSample / 8), true);
  /* bits per sample */
  view.setUint16(34, bitsPerSample, true);
  /* data chunk identifier */
  view.setUint32(36, 0x64617461, false); // "data"
  /* data chunk length */
  view.setUint32(40, dataLength, true);

  return new Blob([header, pcmBytes], { type: 'audio/wav' });
}

interface ChatCaptureScreenProps {
  onTaskSaved: () => void;
}

export const ChatCaptureScreen: React.FC<ChatCaptureScreenProps> = ({ onTaskSaved }) => {
  const [messages, setMessages] = useState<ChatMessage[]>([
    {
      id: 'welcome-jax',
      sender: 'ai',
      text: "Good day, Jagadeesh. J.A.X. online. How can I assist you today?",
      timestamp: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
    },
  ]);

  const [input, setInput] = useState<string>('');
  const [isLoading, setIsLoading] = useState<boolean>(false);
  const [isRecording, setIsRecording] = useState<boolean>(false);
  const [isTtsEnabled] = useState<boolean>(true);
  const [pendingDraft, setPendingDraft] = useState<Partial<Task> | null>(null);
  
  const messagesEndRef = useRef<HTMLDivElement | null>(null);
  const recognitionRef = useRef<any>(null);
  const currentAudioRef = useRef<HTMLAudioElement | null>(null);

  // Auto-scroll on new messages
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, isLoading]);

  // Gemini TTS Audio Stream Playback Helper (response_modalities = ["AUDIO"], Puck/Charon voice)
  const speakJaxText = async (text: string) => {
    if (!isTtsEnabled || !text.trim()) return;

    try {
      if (currentAudioRef.current) {
        currentAudioRef.current.pause();
        currentAudioRef.current = null;
      }

      const res = await fetch('/api/tts', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text, voice: 'Puck' }),
      });

      if (!res.ok) {
        throw new Error('Gemini TTS service error');
      }

      const data = await res.json();
      if (!data.audioData) return;

      const binaryString = atob(data.audioData);
      const bytes = new Uint8Array(binaryString.length);
      for (let i = 0; i < binaryString.length; i++) {
        bytes[i] = binaryString.charCodeAt(i);
      }

      let audioBlob: Blob;
      if (data.mimeType && (data.mimeType.includes('wav') || data.mimeType.includes('mpeg') || data.mimeType.includes('mp3'))) {
        audioBlob = new Blob([bytes], { type: data.mimeType });
      } else {
        // Raw 24kHz 16-bit PCM -> wrap in WAV header
        audioBlob = pcmToWavBlob(bytes, 24000, 1, 16);
      }

      const audioUrl = URL.createObjectURL(audioBlob);
      const audio = new Audio(audioUrl);
      currentAudioRef.current = audio;
      await audio.play();
    } catch (e) {
      console.warn('Gemini TTS playback notice:', e);
    }
  };

  // Hybrid Speech Recognition (STT) setup
  const toggleSpeechRecognition = () => {
    if (isRecording) {
      if (recognitionRef.current) {
        recognitionRef.current.stop();
      }
      setIsRecording(false);
      return;
    }

    const SpeechRecognition = (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;

    if (!SpeechRecognition) {
      setIsRecording(true);
      setTimeout(() => {
        setInput("Call electrician tomorrow at 3pm high priority");
        setIsRecording(false);
      }, 2000);
      return;
    }

    try {
      const recognition = new SpeechRecognition();
      recognition.continuous = false;
      recognition.interimResults = true;
      recognition.lang = 'en-US';

      recognition.onstart = () => {
        setIsRecording(true);
      };

      recognition.onresult = (event: any) => {
        let transcript = '';
        for (let i = event.resultIndex; i < event.results.length; i++) {
          transcript += event.results[i][0].transcript;
        }
        setInput(transcript);
      };

      recognition.onerror = (event: any) => {
        console.warn('Speech recognition error:', event.error);
        setIsRecording(false);
      };

      recognition.onend = () => {
        setIsRecording(false);
      };

      recognitionRef.current = recognition;
      recognition.start();
    } catch (err) {
      console.warn('Speech API init error:', err);
      setIsRecording(false);
    }
  };

  // Send message and trigger Conversational Verification Loop with Optimistic UI & SSE Streaming
  const handleSendMessage = async (overrideText?: string) => {
    const textToSend = overrideText || input;
    if (!textToSend.trim() || isLoading) return;

    const userMessageText = textToSend.trim();

    // 1. OPTIMISTIC UI: Immediately append user message and clear text input box
    const userMsg: ChatMessage = {
      id: 'user-' + Date.now(),
      sender: 'user',
      text: userMessageText,
      timestamp: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
    };

    const streamingAiId = 'jax-stream-' + Date.now();
    const initialAiMsg: ChatMessage = {
      id: streamingAiId,
      sender: 'ai',
      text: '⚡ J.A.X. is processing...',
      timestamp: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
    };

    setMessages((prev) => [...prev, userMsg, initialAiMsg]);
    if (!overrideText) setInput('');
    setIsLoading(true);

    try {
      const response = await fetch('/api/parse-task-stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          text: userMessageText,
          userNowIso: new Date().toISOString(),
          previousDraft: pendingDraft,
        }),
      });

      if (!response.ok || !response.body) {
        throw new Error('Server returned an error streaming verification.');
      }

      const reader = response.body.getReader();
      const decoder = new TextDecoder('utf-8');
      let doneReading = false;
      let accumulatedJsonText = '';

      while (!doneReading) {
        const { value, done } = await reader.read();
        if (done) break;

        const chunkStr = decoder.decode(value, { stream: true });
        const lines = chunkStr.split('\n\n');

        for (const line of lines) {
          if (line.startsWith('data: ')) {
            try {
              const data = JSON.parse(line.replace('data: ', ''));

              if (data.accumulated) {
                accumulatedJsonText = data.accumulated;
                
                try {
                  const partialObj = JSON.parse(accumulatedJsonText);
                  if (partialObj.jarvisReply) {
                    setMessages((prev) =>
                      prev.map((m) =>
                        m.id === streamingAiId ? { ...m, text: partialObj.jarvisReply } : m
                      )
                    );
                  }
                } catch {
                  setMessages((prev) =>
                    prev.map((m) =>
                      m.id === streamingAiId ? { ...m, text: '⚡ J.A.X. verifying task details...' } : m
                    )
                  );
                }
              }

              if (data.done && data.fullResponse) {
                accumulatedJsonText = data.fullResponse;
                doneReading = true;
              }
            } catch {
              // Ignore partial JSON parse error during stream
            }
          }
        }
      }

      // Final JSON parse and verification outcome
      const finalData = JSON.parse(accumulatedJsonText);
      const isComplete = Boolean(finalData.isComplete);
      const jaxReply = finalData.jarvisReply || 'I have processed your request, Jagadeesh.';

      const extractedCandidate: Partial<Task> = {
        title: finalData.title || userMessageText,
        category: finalData.category || 'Personal',
        priority: (finalData.priority as Priority) || 'medium',
        deadline: finalData.deadline || null,
        description: finalData.description || '',
        status: 'pending',
      };

      if (!isComplete) {
        // Conversational Verification Loop: Hold pending task in memory state
        setPendingDraft(extractedCandidate);

        setMessages((prev) =>
          prev.map((m) =>
            m.id === streamingAiId
              ? {
                  ...m,
                  text: jaxReply,
                  isComplete: false,
                  pendingDraft: extractedCandidate,
                  clarificationOptions: finalData.clarificationOptions || [
                    { field: 'deadline', label: '⏰ Today 5 PM', value: 'Today at 5:00 PM' },
                    { field: 'deadline', label: '📅 Tomorrow 10 AM', value: 'Tomorrow at 10:00 AM' },
                    { field: 'priority', label: '🔥 High Priority', value: 'High priority' },
                    { field: 'category', label: '💼 Work Category', value: 'Work category' },
                  ],
                }
              : m
          )
        );
        speakJaxText(jaxReply);
      } else {
        // Verification Complete! Save task automatically to Room SQLite DB + Firestore Cloud
        const finalTask: Task = {
          id: 'task-' + Date.now() + '-' + Math.random().toString(36).substring(2, 6),
          title: extractedCandidate.title || 'Untitled Task',
          category: extractedCandidate.category || 'Personal',
          priority: extractedCandidate.priority || 'medium',
          status: 'pending',
          deadline: extractedCandidate.deadline || null,
          description: extractedCandidate.description || '',
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
        };

        await RoomDB.insertTask(finalTask);
        setPendingDraft(null);

        setMessages((prev) =>
          prev.map((m) =>
            m.id === streamingAiId
              ? {
                  ...m,
                  text: jaxReply,
                  isComplete: true,
                  taskCandidate: finalTask,
                  isSaved: true,
                }
              : m
          )
        );
        speakJaxText(jaxReply);
        onTaskSaved();
      }
    } catch (error: any) {
      console.error('J.A.X. task verification error:', error);
      setMessages((prev) =>
        prev.map((m) =>
          m.id === streamingAiId
            ? {
                ...m,
                text: `My apologies, Jagadeesh. An error occurred while processing: ${error.message || 'Unknown error'}. Please try again.`,
              }
            : m
        )
      );
      speakJaxText('My apologies, Jagadeesh. An error occurred. Please try again.');
    } finally {
      setIsLoading(false);
    }
  };

  const handleOptionClick = (optionValue: string) => {
    handleSendMessage(optionValue);
  };

  return (
    <div className="flex-1 flex flex-col h-full bg-slate-900 text-slate-100 overflow-hidden relative">
      {/* Clean Header Bar */}
      <div className="px-4 py-3.5 bg-slate-950 border-b border-slate-800 flex items-center justify-between shadow-md">
        <div className="flex items-center space-x-3">
          <div className="relative">
            <div className="w-9 h-9 rounded-full bg-gradient-to-tr from-cyan-500 to-indigo-600 flex items-center justify-center text-white font-black text-xs shadow-lg shadow-cyan-500/20">
              <Bot className="w-5 h-5" />
            </div>
            <span className="absolute -bottom-0.5 -right-0.5 w-2.5 h-2.5 bg-emerald-400 border-2 border-slate-950 rounded-full" />
          </div>
          <div>
            <h2 className="font-bold text-base tracking-wide text-slate-100 font-mono">J.A.X. AI</h2>
          </div>
        </div>
      </div>

      {/* Pending Memory Draft Banner if task incomplete */}
      {pendingDraft && (
        <div className="mx-4 mt-3 bg-amber-500/10 border border-amber-500/30 rounded-xl p-3 flex items-center justify-between text-xs text-amber-200">
          <div className="flex items-center space-x-2 overflow-hidden">
            <RefreshCw className="w-4 h-4 text-amber-400 animate-spin shrink-0" />
            <div className="truncate">
              <span className="font-bold text-amber-300">Pending Draft: </span>
              <span className="font-mono text-[11px] text-amber-100">
                "{pendingDraft.title || 'Draft'}" • {pendingDraft.category || 'Personal'} • {pendingDraft.priority || 'medium'} • {pendingDraft.deadline ? formatFullDateTime(pendingDraft.deadline) : '⚠️ Deadline Missing'}
              </span>
            </div>
          </div>
          <button
            onClick={() => setPendingDraft(null)}
            className="text-[10px] bg-slate-800 hover:bg-slate-700 text-slate-300 px-2 py-1 rounded ml-2 shrink-0 border border-slate-700 cursor-pointer"
          >
            Clear
          </button>
        </div>
      )}

      {/* Chat Messages Stream */}
      <div className="flex-1 overflow-y-auto p-4 space-y-4">
        {messages.map((msg) => (
          <div
            key={msg.id}
            className={`flex flex-col ${msg.sender === 'user' ? 'items-end' : 'items-start'}`}
          >
            {/* Sender Metadata */}
            <div className="flex items-center space-x-1.5 mb-1 text-[11px] text-slate-400">
              {msg.sender === 'ai' ? (
                <>
                  <Bot className="w-3.5 h-3.5 text-cyan-400" />
                  <span className="font-bold text-cyan-300 tracking-wider">J.A.X.</span>
                </>
              ) : (
                <>
                  <User className="w-3.5 h-3.5 text-indigo-400" />
                  <span className="font-medium text-slate-300">Jagadeesh</span>
                </>
              )}
              <span>• {msg.timestamp}</span>

              {msg.sender === 'ai' && (
                <button
                  onClick={() => speakJaxText(msg.text)}
                  className="ml-2 text-slate-400 hover:text-cyan-300 p-0.5 rounded transition cursor-pointer"
                  title="Speak message aloud"
                >
                  <Volume2 className="w-3 h-3" />
                </button>
              )}
            </div>

            {/* Chat Bubble */}
            <div
              className={`max-w-[92%] sm:max-w-[85%] rounded-2xl p-3.5 shadow-md ${
                msg.sender === 'user'
                  ? 'bg-gradient-to-r from-indigo-600 to-indigo-700 text-white rounded-br-none'
                  : 'bg-slate-800/90 border border-slate-700/80 text-slate-100 rounded-bl-none'
              }`}
            >
              <p className="text-xs sm:text-sm whitespace-pre-wrap leading-relaxed">{msg.text}</p>

              {/* Clarification Options */}
              {msg.sender === 'ai' && !msg.isComplete && msg.clarificationOptions && msg.clarificationOptions.length > 0 && (
                <div className="mt-3 pt-3 border-t border-slate-700/80">
                  <span className="text-[11px] text-cyan-300 font-medium block mb-2">Quick options:</span>
                  <div className="flex flex-wrap gap-1.5">
                    {msg.clarificationOptions.map((opt, i) => (
                      <button
                        key={i}
                        onClick={() => handleOptionClick(opt.value)}
                        className="text-xs bg-slate-900 hover:bg-cyan-600/30 text-cyan-200 border border-cyan-500/30 px-3 py-1.5 rounded-full transition cursor-pointer flex items-center space-x-1"
                      >
                        <span>{opt.label}</span>
                      </button>
                    ))}
                  </div>
                </div>
              )}

              {/* Verified Task Card */}
              {msg.taskCandidate && msg.isSaved && (
                <div className="mt-3 pt-3 border-t border-slate-700/80 bg-slate-900/90 rounded-xl p-3 space-y-2 border border-emerald-500/30">
                  <div className="flex items-center justify-between">
                    <span className="text-[11px] font-bold text-emerald-400 uppercase tracking-wider flex items-center space-x-1">
                      <CheckCircle2 className="w-3.5 h-3.5" />
                      <span>Verified & Saved</span>
                    </span>
                    <span className="text-[10px] text-emerald-300 bg-emerald-500/20 px-2 py-0.5 rounded border border-emerald-500/30 font-mono">
                      Room DB ID: {msg.taskCandidate.id?.substring(0, 8)}...
                    </span>
                  </div>

                  <div className="text-xs font-semibold text-slate-100">
                    {msg.taskCandidate.title}
                  </div>

                  <div className="grid grid-cols-3 gap-2 text-[11px] pt-1">
                    <div className="bg-slate-800 px-2 py-1 rounded flex items-center space-x-1 text-slate-300">
                      <Tag className="w-3 h-3 text-cyan-400" />
                      <span>{msg.taskCandidate.category}</span>
                    </div>
                    <div className="bg-slate-800 px-2 py-1 rounded flex items-center space-x-1 text-slate-300">
                      <AlertCircle className="w-3 h-3 text-amber-400" />
                      <span className="capitalize">{msg.taskCandidate.priority} priority</span>
                    </div>
                    <div className="bg-slate-800 px-2 py-1 rounded flex items-center space-x-1 text-slate-300 truncate">
                      <Clock className="w-3 h-3 text-indigo-400" />
                      <span className="truncate">{formatFullDateTime(msg.taskCandidate.deadline || null)}</span>
                    </div>
                  </div>
                </div>
              )}
            </div>
          </div>
        ))}

        {/* Loading Spinner */}
        {isLoading && (
          <div className="flex items-center space-x-2.5 text-xs text-cyan-400 bg-slate-800/80 p-3 rounded-2xl w-max border border-cyan-500/30 animate-pulse">
            <Bot className="w-4 h-4 text-cyan-400 animate-spin" />
            <span className="font-mono">J.A.X. is analyzing completeness...</span>
          </div>
        )}

        <div ref={messagesEndRef} />
      </div>

      {/* Speech Recognition Active Indicator */}
      {isRecording && (
        <div className="px-4 py-2 bg-cyan-950/90 border-t border-cyan-500/40 flex items-center justify-between text-xs text-cyan-200 animate-pulse">
          <div className="flex items-center space-x-2">
            <Mic className="w-4 h-4 text-rose-400 animate-bounce" />
            <span className="font-semibold">Speech Mode Active: Listening...</span>
          </div>
        </div>
      )}

      {/* Clean Bottom Input Bar */}
      <div className="p-3 bg-slate-950 border-t border-slate-800">
        <form
          onSubmit={(e) => {
            e.preventDefault();
            handleSendMessage();
          }}
          className="flex items-center space-x-2"
        >
          {/* Microphone Button */}
          <button
            type="button"
            onClick={toggleSpeechRecognition}
            className={`p-2.5 rounded-xl border transition cursor-pointer relative ${
              isRecording
                ? 'bg-rose-600 text-white border-rose-400 ring-2 ring-rose-500/50 animate-pulse'
                : 'bg-slate-800 hover:bg-slate-700 text-cyan-400 border-slate-700'
            }`}
            title={isRecording ? 'Stop recording' : 'Tap to speak'}
          >
            <Mic className="w-4 h-4" />
            {isRecording && (
              <span className="absolute -top-1 -right-1 w-2.5 h-2.5 bg-rose-400 rounded-full animate-ping" />
            )}
          </button>

          {/* Clean Text Input Box */}
          <input
            type="text"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            placeholder={pendingDraft ? "Answer J.A.X... e.g. 'tomorrow at 3pm high priority'" : "Type or speak to J.A.X..."}
            className="flex-1 bg-slate-900 border border-slate-700 rounded-xl px-3.5 py-2.5 text-xs text-slate-100 placeholder-slate-500 focus:outline-none focus:ring-1 focus:ring-cyan-500"
          />

          {/* Send Button */}
          <button
            type="submit"
            disabled={!input.trim() || isLoading}
            className="p-2.5 bg-gradient-to-r from-cyan-600 to-indigo-600 hover:from-cyan-500 hover:to-indigo-500 disabled:opacity-50 text-white rounded-xl font-medium transition cursor-pointer shadow-md shadow-cyan-600/20"
            title="Send to J.A.X."
          >
            <Send className="w-4 h-4" />
          </button>
        </form>
      </div>
    </div>
  );
};
