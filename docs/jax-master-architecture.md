# JAX: MASTER ARCHITECTURE & ROADMAP
### Practical Implementation Blueprint for a Next-Generation Personal AI OS
**Author:** Principal AI Architect  
**Target Platform:** Android-first (Kotlin) + Federated Edge/Cloud Nodes  
**Status:** BUILDABLE BLUEPRINT (Optimized for Intelligence, Privacy, Autonomy, Security, and Maintainability)

---

## 1. Executive Summary & Vision

JAX is a personal AI assistant evolving from a sandboxed mobile chatbot (Kotlin + Room + Firebase) into a persistent, autonomous, and private **cognitive partner** [221, 675]. The core objective is to move from a reactive text-based interface to a proactive agent that operates under a unified **Perceive → Understand → Remember → Reason → Plan → Act → Verify → Learn** feedback loop [221]. 

To enable this transition safely without introducing a fragmented network of fragile components, JAX leverages two unifying paradigms:
1.  **The Agentic File System (AFS) Abstraction for Context Engineering:** In JAX, "Everything is Context," and all context is represented through a standardized hierarchical filesystem [32, 139]. Memory databases, local file trees, remote API responses, and live Model Context Protocol (MCP) servers are mounted as virtual directory structures (e.g., `/context/memory/`, `/context/tools/github/`) [32, 156, 173]. This abstracts underlying storage, ensures strict transaction logging and data lineage, and solves the combinatorial complexity of model-tool integrations [32, 146, 149].
2.  **A True Local-First, Cloud-Edge Cooperative Topology:** Workloads are distributed dynamically based on a multi-objective optimization router [26, 624]. Low-latency tasks, continuous sensory perception, and private local data processing run directly on-device, accelerated by the Snapdragon Hexagon NPU via the MLCC framework [7, 473]. Heavy multi-hop planning, complex software engineering tasks, and long-context processing are routed to cloud-based frontier engines [7, 226]. Data synchronization across mobile, desktop, and backup nodes is handled asynchronously and without a central server using Conflict-free Replicated Data Types (CRDTs) and SQLite-sync [14, 37].

This master architecture prioritizes **structural security over prompt-based filtering**, utilizing a rigorous **Dual-LLM Shielding Pattern (Privileged vs. Quarantined model separation)** to block indirect prompt injections [82, 735]. It enforces **durable execution at the agent boundary** via two-tier rollback mechanisms (argument-level vs. API-level rollbacks) to maintain system consistency when external APIs crash [13, 224]. 

JAX is designed to run locally, operate continuously, protect your privacy in hardware-backed enclaves (Android Keystore and DPAPI), and learn incrementally from your daily life [6, 21, 221, 227].

---

## 2. Brain: Cognitive Architecture & Systems Flow

The JAX Brain operates as a continuous, non-blocking execution loop modeled after the Princeton Cognitive Architectures for Language Agents (CoALA) framework [16, 221]. It separates execution state from model reasoning, ensuring that model latency or failures do not corrupt the host application process [32, 292].

```
+-----------------------------------------------------------------------------------+
|                                     JAX BRAIN                                     |
+-----------------------------------------------------------------------------------+
|  [PERCEIVE]                                                                       |
|       |                                                                           |
|       v                                                                           |
|  Continuous Accessibility Trees [8] + Screen Screenshots [8] + Audio Streams [7]  |
|       |                                                                           |
|       v                                                                           |
|  [CONTEXT CONSTRUCTION]                                                           |
|       |                                                                           |
|       v                                                                           |
|  AFS Virtual Mount Engine [32] (Working + Episodic + Neocortical Profile [29])     |
|       |                                                                           |
|       v                                                                           |
|  [REASONING & ROUTING]                                                            |
|       |                                                                           |
|       +------------------------------> Local Qwen3 / Phi-4 (NPU) [7]              |
|       | (Complexity, sensitivity, cost)                                           |
|       +------------------------------> Cloud Gemini 1.5 Pro / Flash [35]          |
|       |                                                                           |
|       v                                                                           |
|  [PLANNING]                                                                       |
|       |                                                                           |
|       v                                                                           |
|  Directed Acyclic Graph (DAG) Plan Generation [221] (Step-by-Step API Intent)     |
|       |                                                                           |
|       v                                                                           |
|  [EXECUTION]                                                                      |
|       |                                                                           |
|       v                                                                           |
|  Model Context Protocol (MCP) Client Gateway [66] (Local Sandboxes / OAuth 2.1)   |
|       |                                                                           |
|       +-----------> Success ---------> [VERIFY] (Structured Validators) [13]      |
|       |                                   |                                       |
|       +-----------> Failure ---------> [ROLLBACK Engine] (Argument/API Level) [13]|
|                                           |                                       |
|                                           v                                       |
|                                        [LEARN] (Asynchronous Consolidation [18])  |
+-----------------------------------------------------------------------------------+
```

### Core Operations in the Brain Loop
1.  **Perception (Sensory Processing):** Ingests raw inputs from the device environment, including continuous ambient audio recordings, accessibility layout trees, filesystem events, and periodic display screenshots [30, 123, 221]. These raw sensory feeds are processed locally by unprivileged, tiny models (Gemma 3n / Google AI Edge) [7, 734] and structured into JSON event feeds.
2.  **Context Construction:** Leverages the Agentic File System (AFS) Virtual Mount Engine [32, 166]. It combines active context variables, recent dialogue history (W=10 messages) [29, 679], retrieved episodic memories [24, 98], and the user's permanent semantic profile [24, 96] to assemble the real-time operational context.
3.  **Reasoning & Routing:** Evaluates task complexity, prompt length, NPU thermal state, network availability, and data sensitivity [26, 723]. Simple classifications, short chats, and PII-sensitive operations are processed locally on the Snapdragon Hexagon NPU [7, 473]. Complex reasoning, large codebase analyses, and long-horizon document interactions are routed securely to cloud-native engines [7, 573].
4.  **Planning:** The chosen reasoning model decomposes complex instructions (e.g., "Reschedule my Tuesday morning meetings and notify the participants") into structured, declarative step-by-step action plans, represented as a Directed Acyclic Graph (DAG) of API intents [13, 221].
5.  **Execution:** The system’s orchestrator dispatches execution steps to local or remote servers via stateful Model Context Protocol (MCP) clients [66, 225].
6.  **Verification (Verification & Guardrails):** Every action is evaluated post-execution [13]. Structured schemas, regex validations, and a separate evaluator model check tool outputs against expected states (e.g., verifying if a file was successfully deleted or an email draft contains correct metadata) [13, 88].
7.  **Learning (Consolidation & Adaptation):** Operates on an asynchronous background schedule [24, 96]. Rather than relying on computationally expensive, slow, inline reflection loops that stall the user experience, JAX runs an offline consolidation engine during system idle periods (e.g., when the device is charging at night) [18, 84]. This process aggregates raw episodic logs, updates the semantic knowledge graph, derives preference weights, and prunes stale telemetry traces [24, 97, 223].

---

## 3. Memory: Core Architecture & Cognitive Lifecycle

A monolithic vector database is fundamentally insufficient for a JARVIS-like assistant [24, 98]. Vector search is optimized for semantic similarity but lacks temporal awareness (e.g., "What was my boss's email *last week*?") and cannot perform causal reasoning (e.g., "Why did I cancel that flight?") [24, 98, 102]. JAX implements a **five-tiered memory architecture** matching the CoALA specification, built entirely on standard in-process relational schemas with vector and graph extensions [24, 95, 222].

### Memory Subsystem Hierarchy

| Tier | Storage Backend | Data Representation | Read Latency | Forgetting/Compaction Mechanism |
| :--- | :--- | :--- | :--- | :--- |
| **Working Memory** | Active Process RAM / LLM Context Window [24, 98] | Live prompt buffers, transient variable registers, system state, session dialog history [24, 98, 118, 375] | $< 1	ext{ ms}$ | Volatile; cleared immediately upon task completion or session boundary detection [24, 98]. |
| **Episodic Memory** | SQLite + `sqlite-vec` virtual tables [14, 24] | Keyed JSON logs capturing full reasoning traces, tool inputs/outputs, and raw text messages, indexed by UUID [14, 28]. | $10	ext{--}50	ext{ ms}$ | Asynchronous background consolidation compresses raw logs into semantic summaries [24, 97]. |
| **Semantic Memory** | SQLite relational Graph schemas (Entity-Relation tables) [24, 222] | Strongly-typed nodes (Entities), edges (Relationships), and property attributes [69, 76, 835]. | $5	ext{--}20	ext{ ms}$ | Importance-weighted decay curves and Least-Recently-Used (LRU) cache eviction queues [25, 223]. |
| **Procedural Memory** | Local File System (AFS `/context/tools/`) [32, 168] | Declarative YAML schema files, workflow instructions, system prompts, and sandboxed skill scripts [24, 98]. | $1	ext{--}5	ext{ ms}$ | Explicit version control via Git; manual promotions and regressions [203]. |
| **Preference & Temporal** | Relational SQL tables (Encrypted) | User parameter weights, temporal calendar coordinates, decay parameters, and user models. | $1	ext{--}5	ext{ ms}$ | Real-time adjustment based on active user feedback loops and calendar parameters [222, 223]. |

### Core Memory Lifecycle Operations

```
   [Active User Interaction] 
               |
               v (Append Raw Dialog / Tool Events)
       +---------------+
       | Episodic Log  | <--- Stored in WAL-mode SQLite with origin UUIDs [14, 182]
       +---------------+
               |
               | Asynchronous Consolidation (Background Trigger when Idle) [24, 96]
               v
       +---------------+
       | Neocortical   | 
       |  Translation  | <--- Executed via Local model (Phi-4-Mini) [7, 679]
       +---------------+
               |
               +-------------------------------------------------+
               |                                                 |
               v (Extract Facts & Relations)                     v (Update Metadata)
       +---------------+                                 +------------------+
       | Semantic Graph| <--- Relational SQLite Schemas  | Project Briefs   | <--- prose summaries
       +---------------+      with temporal edges        +------------------+      of active tasks [14, 24]
               |              (e.g., React until 2025    
               |               Vue thereafter) [24, 97]
               v
       +---------------+
       | Decay / LRU   | <--- Automatically decay low-priority nodes [14, 24]
       |  Forgetting   |      Archive into compressed historical matrices [24, 97]
       +---------------+
```

1.  **Ingestion & Provenance Mapping:** Every write to the episodic memory store is recorded as an immutable entity keyed by a unique UUID minted on the origin device [14, 60]. Each memory record contains metadata parameters: `created_at` timestamp, `source_id` (application/sensor origin), `confidence` rating, and `arousal/importance` metrics [18, 14, 223].
2.  **Asynchronous Consolidation (Sleep Cycle):** When the system enters background idle mode, a scheduled worker extracts the latest un-consolidated episodic records [24, 96, 101]. It passes these episodes to a lightweight local model (Phi-4 Mini) with a specialized summarization prompt: *"Extract durable facts, entity relationships, and behavioral patterns. Identify contradictions"* [24, 101]. The resulting JSON is parsed, new vertices/edges are written to the semantic graph database, and raw episodic traces are archived to compressed historic logs [24, 101].
3.  **Conflict Resolution & Temporal Arbiter:** Real-world facts change over time (e.g., "User primary language is React until November 2025; has since transitioned primary stack to Vue") [24, 97]. To prevent semantic drift and contradictions, a **temporal arbiter** validates all graph writes [24, 97]. If a newly extracted fact conflicts with an existing node, the arbiter applies a temporal decay formula to the older relationship, updates the connection status, and writes a reconciliation summary preserving both historical context and current truth [24, 97, 223].
4.  **Forgetting and Compaction:** To prevent database bloat on-device, the semantic memory tier implements a **weighted decay curve** [24, 97, 211]. Each node's priority is calculated as:
    $$	ext{Priority} = 	ext{Importance} 	imes e^{-\lambda t} 	imes 	ext{ReadCount}$$
    When the database exceeds a strict device storage footprint (e.g., 2 GB), low-priority nodes are evicted from active indexes, compacted into global historical summary blocks, and stored in deep archival files [24, 97, 211].

---

## 4. Agent: Orchestration, State & Rollback Strategy

An agent performing real-world actions cannot rely on standard try-catch blocks or native database transactions because external third-party APIs cannot be rolled back with a database command [13, 19]. If JAX crashes mid-workflow, it must not leave half-written files, open connections, or duplicated transaction states in the external world [13, 20].

```
                 [Goal: Send Consolidated Report]
                                |
                                v
               [Plan-Then-Execute Control Flow] [12]
                                |
             +------------------+------------------+
             |                                     |
             v                                     v
       [Step 1: Fetch Data]                  [Step 2: Write File]
             |                                     |
    (Tool: database_query)                 (Tool: write_to_disk)
             |                                     |
    [State: Completed]                     [State: Completed]
             |                                     |
             +------------------+------------------+
                                |
                                v
                   [Step 3: Dispatch Email]
                                |
                     (Tool: send_email_api)
                                |
                     === NETWORK TIMEOUT / CRASH ===
                                |
                                v
                  [Orchestrator Fault Detector]
                                |
             +------------------+------------------+
             |                                     |
             v                                     v
      [1. REVERT Internal Database]       [2. Adaptively ROLLBACK External Tools] [13]
             |                                     |
    Revert local SQL transaction           - Log tool failure
    to last verified Checkpoint [228]       - Trigger ARGUMENT-LEVEL Rollback (retry) [13, 45]
                                           - Escalates to API-LEVEL Rollback (alternative tool)
                                           - Run COMPENSATING ACTIONS [13, 22] (e.g., delete temp files)
```

### 1. Robust State Tracking & Execution State
JAX utilizes a **Plan-Then-Execute** pattern combined with **Durable Execution state machines** [13, 12, 131]:
*   Before any tool is executed, the agent generates a complete, structured JSON plan of action [13, 23]. This plan is written as a Directed Acyclic Graph (DAG) to a transactional SQLite `active_tasks` table [13, 224].
*   The system uses an **Event-Sourcing log** [13, 22]. Each execution step, its inputs, exact tool parameters, and response states are written to an immutable append-only transaction ledger [13, 22].
*   If the device loses power, shuts down, or experiences a network partition, the JAX background service reads the ledger on boot, identifies the exact failed state, and safely resumes from the last successfully written checkpoint [13, 22].

### 2. Two-Tier Rollback Recovery
When a step in the DAG fails (e.g., API rate limits, model parsing errors, or payload validation failures), the system invokes an **Adaptive Rollback Engine** [13, 44]:
*   **Argument-Level Rollback:** The orchestrator logs the tool failure, captures the error trace, and requests the local model to re-generate *only* the specific tool arguments while keeping the active tool and overall plan intact [13, 45, 50]. This is used for self-healing when encountering rate limits or invalid arguments [46, 50].
*   **API-Level Rollback:** If argument adjustment fails after a strict threshold (maximum 1 retry), the orchestrator escalates to API-level rollback [13, 50]. The system rewinds the state machine to the step immediately preceding the failure, invalidates the active tool, and directs the planning engine to select an alternative tool or path to accomplish the overall target goal (e.g., falling back from Cal.com API to generating a draft email with meeting coordinates) [13, 45, 50].

### 3. Compensating Actions (The "Undo" Chain)
For every write, modification, or state-changing action defined in a tool's schema, a corresponding **compensating transaction** must be declared [13, 22].
*   If the overall task fails mid-execution and cannot be recovered via API-level rollback, the orchestrator walks backward through the transaction ledger, executing the compensating undo action for each completed step (e.g., if JAX successfully created a cloud server and then failed to configure it, the compensating action is an API call to delete the newly created server instance) [13, 22].
*   For filesystem operations, JAX utilizes **isolated ephemeral workspaces (staging directories)** [13, 25]. Intermediate files are written to `/context/pad/taskID` [32, 157]. Only when the final verification step succeeds does the orchestrator commit the transaction, moving the verified files to their production folders [13, 22]. On failure, the staging workspace is deleted entirely [13, 25].

---

## 5. Autonomy: Event-Driven Proactive Decisions

A modern personal assistant cannot remain passive, waiting for a chat prompt [30, 48]. It must run continuously, monitoring background events, and deciding when to intervene [30, 126]. JAX utilizes a **fully decoupled Event-Driven Architecture (EDA)** built on local publish-subscribe buses to achieve energy efficiency and high autonomy [30, 123, 128].

```
+------------------+      +-------------------+      +-------------------+
|  Event Producer  | ---> |  Local Event Bus  | ---> |  Proactive Engine |
+------------------+      +-------------------+      +-------------------+
  - File Creation           In-process memory          - Heartbeat Loops [229]
  - Location Change         bus (Kotlin Flow) [130]    - Evaluates State vs. 
  - Calendar Events                                      Active Rule Graph [229]
  - Accessibility Tree                                            |
                                                                  v
                                                     [Decision Tree Engine]
                                                                  |
    +-------------------+-----------------+-----------------------+------------------+
    |                   |                 |                       |                  |
    v                   v                 v                       v                  v
[Do Nothing]        [Notify]         [Recommend]               [Ask]               [Act]
No attention        Ambient toast    "I noticed X,     "Confirm before I send     Execute plan;
needed.             (Non-intrusive)   suggest Y."       this draft email to..."   log and audit.
```

### The Autonomous Decision Tree
Upon processing a background event, the Proactive Engine evaluates the state against the active rule graph and classifies its proposed response into one of five execution tiers:
1.  **Do Nothing:** The event is logged in episodic history but requires no action (e.g., normal location coordinates or non-essential calendar updates).
2.  **Notify:** An ambient, non-intrusive notification is displayed (e.g., "JAX has processed your flight booking from your email and updated your calendar").
3.  **Recommend:** The system presents a structured suggestion (e.g., "I noticed your flight lands at 9 PM and there are currently no trains running due to maintenance. Would you like me to book a taxi?").
4.  **Ask (Explicit Human-in-the-Loop):** Sensitive or state-changing transactions require explicit, structured user confirmation [66, 372]. JAX drafts the execution plan, locks the state machine, and presents a BiometricPrompt modal to the user [6, 468]: *"Confirm before JAX emails John Doe and modifies file schedule.xlsx."*
5.  **Act:** JAX executes the action autonomously (e.g., archiving an processed invoice or updating local database notes) and writes a secure verification entry to the audit log.

---

## 6. Tools: Model Context Protocol (MCP) Integration

JAX integrates with external resources, APIs, and local systems using the **Model Context Protocol (MCP)**, resolving the "N x M" integration sprawl by standardizing all tool interfaces under a single JSON-RPC-based protocol [66, 277].

```
                     +---------------------------------------+
                     |              JAX CLIENT               |
                     +---------------------------------------+
                                         |
                                         v
                     +---------------------------------------+
                     |          MCP Client Manager           |
                     +---------------------------------------+
                                         |
                                         +-----------------------------------+
                                         | (TLS / Mutual Auth [264])         | (Local standard I/O)
                                         v                                   v
+----------------------------------------------------+   +-----------------------------------+
|               Remote HTTP/SSE Gateway              |   |       Local Sandboxed Servers     |
+----------------------------------------------------+   +-----------------------------------+
|  - Host OAuth 2.1 Authorization Endpoints [263]   |   |  - Standard I/O streams           |
|  - Token Auditing and Expiry Introspection [265]   |   |  - Root filesystem bounds [286]   |
|  - Token Audience & Resource Restriction [265]     |   |  - Non-dangerous configurations   |
+----------------------------------------------------+   +-----------------------------------+
```

### 1. Stateful Client Manager
A central client manager coordinates multiple isolated MCP client instances, supporting both local stdio transports (for fast, local device tools) and remote HTTP/SSE transports (for secure, remote API connectors) [66, 225, 279, 305].

### 2. Authorization Security & OAuth 2.1
*   **OAuth 2.1 with PKCE:** All remote MCP servers operate as OAuth 2.1 resource servers [63, 263]. The JAX host acts as an OAuth client on behalf of the user, initiating authorization-code-with-PKCE flows to obtain scoped, cryptographically signed access tokens [63, 263].
*   **CIMD (Client ID Metadata Documents):** To support dynamic trust in open MCP ecosystems without relying on centralized, heavy dynamic client registration databases, JAX implements CIMD (SEP-991) [63, 264]. JAX hosts its client metadata as a static JSON file on a verified HTTPS URL, which remote MCP servers fetch dynamically to verify JAX's identity [63, 264].
*   **Audience Restrictions:** To prevent token-replay and token-leakage exploits across multi-server configurations, all issued tokens use strict resource indicators (RFC 8707) [63, 265]. The token is explicitly bound to a single target MCP server's unique URI and cannot be forwarded or replayed on downstream services [63, 265, 301].
*   **Confused Deputy Prevention:** JAX's MCP Gateway blocks automatic token passthrough to downstream systems [66, 300]. If a tool requires accessing a third-party service, the gateway enforces a secure **Token Exchange** flow, requesting explicit consent from the user before minting a separate, scoped downstream token [63, 264].

### 3. Execution Sandboxing
All local, executable tool files (e.g., custom Python calculators or node scripts) are executed inside unprivileged, restricted runtime environments [32, 168]. 
*   **Filesystem Boundaries (Roots):** Stdio MCP servers are bound to strict, isolated directories using MCP's `roots` primitive [66, 286]. If an agent attempts to read or write a file outside its authorized workspace root (e.g., `/context/pad/taskID`), the host client rejects the request before it reaches the model [66, 286].
*   **Rootless Containerization:** Network-facing or heavy execution tools run in rootless, ephemeral sandboxes with restricted network routing [32, 225].

---

## 7. Multimodal: Sensory Perception & Computer Use

To achieve true JARVIS-like utility, JAX must perceive the world in high-fidelity, integrating voice, visual screen structures, screenshots, and device control [221, 229].

```
+-----------------------------------------------------------------------------------+
|                             JAX MULTIMODAL PERCEPTION                             |
+-----------------------------------------------------------------------------------+
|  [VOICE]                      [VISUAL ENGINE]                [DEVICE CONTROL]     |
|    |                               |                                |             |
|    v                               v                                v             |
|  Gemini Live Audio API [69]   AccessibilityService Tree [8]  dispatchGesture() [8] |
|  Fast, low-latency duplex     Direct structured node access  System-level swipes,  |
|  streaming over WebRTC.       (com.whatsapp:id/chat_name)    taps, and keys.       |
|                                    |                                              |
|                                    v                                              |
|                               takeScreenshot() [8]                                |
|                               Hardware buffer capture;                            |
|                               0% overhead, silent.                                |
+-----------------------------------------------------------------------------------+
```

### 1. Gemini Live duplex Audio
Duplex, low-latency conversational audio is achieved by streaming raw PCM audio blocks directly to the Gemini Live API over WebRTC [69, 251]. Duplex generation means the model can be interrupted in real time; JAX monitors microphone inputs continuously and cancels ongoing text-to-speech rendering the instant user voice activity is detected.

### 2. Screen Perception via AccessibilityService
Traditional multimodal computer-use agents rely entirely on continuous screenshots processed by large Vision-Language Models (VLMs) [8, 533]. This approach is incredibly slow, expensive in token cost, drains mobile batteries, and struggles with exact coordinate matching [8, 480, 534]. JAX implements a **hybrid screen perception engine**:
*   **First-Stage: Accessibility Layout Trees:** JAX runs an Android background service extending `AccessibilityService` with `FLAG_REPORT_VIEW_IDS` enabled [8, 519, 544]. This allows JAX to continuously parse the structured Android UI tree [8, 491]. It reads precise text contents, active window states, URL bar contents (e.g., `com.android.chrome:id/url_bar`), and resource IDs directly from the OS [8, 491, 506]. This provides instant, structured context without executing a single VLM call or screenshot [8, 491].
*   **Second-Stage: Silent Hardware Screenshots:** When structured UI layout trees are insufficient (e.g., inside canvas views, games, or obfuscated app interfaces), JAX invokes `takeScreenshot()` [8, 495]. Introduced in Android 11, this API captures frames directly from the hardware buffer [8, 495]. It runs entirely in the background, showing **no MediaProjection status bar recorders, no notifications, and no toasts**, ensuring a completely silent, zero-overhead visual capture [8, 495].

### 3. Gesture Injection & Computer Use
To automate device interaction, JAX utilizes system-level gesture injection:
*   **`dispatchGesture()` API:** Dispatches multi-touch gestures, taps, swipes, and scrolling actions [8, 492, 545]. Because these gestures are executed at the system level, they bypass standard overlay touch-blocking filters (`filterTouchesWhenObscured`), allowing JAX to interact safely and robustly with any application on the device [8, 493].
*   **Visual Tree Rendering:** In high-security contexts where `FLAG_SECURE` blocks screenshot capture, JAX reconstructs the visual display layout in memory by rendering the node boundaries from the accessibility tree [8, 508]. The agent can compute target tap coordinates and dispatch gestures accurately, bypassing standard visual blocking mechanisms [8, 508].

---

## 8. Multi-Device Topology: Local vs. Cloud Edge Separation

To ensure absolute privacy, resilience, and cost efficiency, JAX adopts a hybrid cloud-edge topology that cleanly partitions responsibilities [26, 720].

```
+------------------------------------+          +------------------------------------+
|         ON-DEVICE MOBILE           |          |         CLOUD EDGE NODE            |
+------------------------------------+          +------------------------------------+
|  - Continuous Sensory Perception   |          |  - High-Overhead Planning          |
|  - Real-Time Duplex Voice Streams  |          |  - Multi-Hop Graph Traversals      |
|  - AES-256 SQLCipher Database      |          |  - Massive Document Workflows      |
|  - Qwen3-Mini & Phi-4-Mini         |          |  - Long-Context Code Reasoning     |
+------------------------------------+          +------------------------------------+
                   |                                               |
                   +-----------------------+-----------------------+
                                           |
                                           v
                        +------------------------------------+
                        |      SQLite-Sync CRDT Mesh [37]    |
                        +------------------------------------+
                        |  - Multi-master replication        |
                        |  - Block-Level LWW for Text        |
                        |  - Peer-to-Peer mDNS Discovery     |
                        +------------------------------------+
```

### Dynamic Workload Routing
Requests are routed dynamically using a multi-objective optimization policy (NSGA-II) that evaluates complexity, cost, queue state, and privacy constraints [26, 624, 723]:

1.  **Complexity Estimation:** JAX runs a local, lightweight classification router (Qwen3-Mini) [7, 734]. If a task is classified as simple chat, calendar retrieval, or basic extraction, it remains on-device [26, 722, 723]. Reasoning-heavy, programmatic, or multi-step tasks escalate [26, 723].
2.  **Confidence-Based Cascading:** The local model executes tool tasks first [26, 723]. If its output token-level entropy exceeds a strict threshold (indicating high uncertainty or likely hallucination), JAX halts execution and escalates the request, along with the active context window, to the cloud frontier engine [26, 723].
3.  **PII & Sensitivity Gates:** If the query retrieves sensitive files, passwords, or contacts, JAX forces on-device execution [26, 723, 728]. The task is restricted to local models (Phi-4 Mini) with zero cloud fallback, maintaining compliance boundaries [26, 723, 728].

### Peer-to-Peer CRDT Synchronization
Multiple JAX instances (Mobile, PC, Cloud) must synchronize memory state, user preferences, and task databases without routing every byte through a central, expensive server [14, 58].
*   **SQLite-Sync & cr-sqlite:** JAX’s local databases are turned into multi-master replicas using `cr-sqlite` and `sqlite-sync` extensions [14, 78, 184]. All tables are initialized with CRDT capabilities, utilizing Grow-Only Sets (G-Set) for logs, and Last-Write-Wins Element-Sets (LWW-Element-Set) for mutable states [14, 73].
*   **Block-Level Last-Write-Wins (LWW):** To keep text and markdown documents in sync across devices, JAX uses **Block-Level LWW** [37, 191]. The extension splits large text columns into individual block lines and merges them independently, ensuring that concurrent edits to different sections of a document are preserved without data loss or merge conflicts [37, 186, 191].
*   **Gossip Polling over mDNS:** Local area network (LAN) peers discover each other dynamically using mDNS (`_artel._tcp.local.`) [14, 62]. Replicas link with a single tap, publishing RSS-style JSON feeds containing delta-diffs since the last synchronized version [14, 61, 72]. Clocks are synchronized using Hybrid Logical Clocks (HLC) to preserve strict causal relationships without relying on physical network clock matching [15, 80].

---

## 9. Security Architecture & Threat Modeling

JAX operates in a hostile environment where tools can turn hostile and user inputs or external data can host malicious prompt injections [63, 323, 797]. The security model assumes a zero-trust posture toward all third-party tool metadata and data payloads [63, 323, 443].

### 1. Dual-LLM Shielding Pattern (Direct & Indirect Prompt Injection Defense)
To prevent prompt injections from hijacking JAX's tool execution capabilities, the architecture implements strict **Dual-LLM Shielding** combined with **CaMeL Framework variables** [82, 446, 767]:

```
+------------------+      +-------------------+      +-------------------+
|    User Input    | ---> |   Privileged LLM  | ---> |     Controller    |
+------------------+      +-------------------+      +-------------------+
  (Trusted source)          - Access to system         - Traditional Code
                            - Tool definitions         - Dereferences vars
                                   |                   - Executes tools
                                   | (Symbolic Path)
                                   v
+------------------+      +-------------------+      +-------------------+
|  Untrusted Data  | ---> |  Quarantined LLM  | ---> |   Symbolic Var    |
+------------------+      +-------------------+      +-------------------+
  (Web pages, emails,       - Zero tool access         - $VAR1 containing
   documents) [797]         - Fully isolated             tainted text [822]
```

*   **Privileged LLM (P-LLM):** Handles user commands, plans tool execution, and manages state [82, 735]. It operates *only* on trusted input (direct user queries) and has access to system tools [82, 735]. It is **never** exposed to raw, untrusted data retrieved from external sources (e.g., emails or web search results) [82, 735].
*   **Quarantined LLM (Q-LLM):** Ingests and processes all untrusted content (e.g., summarizing an incoming email or reading a webpage) [82, 735]. It has **zero tool access** and operates within a fully isolated runtime container [82, 735].
*   **Symbolic Variable Separation (The Controller):** When the P-LLM directs the system to summarize an email and email the summary to John, the orchestrator's non-LLM **Controller** executes the workflow [82, 821, 822]:
    1.  The Controller fetches the email and passes it directly to the Q-LLM [82, 822].
    2.  The Q-LLM generates the summary. The Controller traps this output in a secure symbolic variable register: `$VAR1 = "Email summary content..."` [82, 822].
    3.  The P-LLM is notified that the summary is available as `$VAR1`. The P-LLM generates the execution intent: `send_email(to="john@company.com", body=$VAR1)` [82, 822].
    4.  The Controller intercepts this intent, dereferences `$VAR1` into the body parameter, and invokes the system's email API [82, 822].
    **Result:** The P-LLM is never exposed to the raw untrusted email text, completely neutralizing indirect prompt injections trying to hijack the control flow [82, 738, 769].

### 2. Hardware-Bound Vault Storage
Plaintext access tokens and database encryption keys must never enter the application process space or be readable as cleartext on-disk [63, 227].
*   **Android Keystore:** JAX generates a 256-bit AES master key inside the Android Keystore system, explicitly configured with `setIsStrongBoxBacked(true)` to utilize physical secure enclaves (StrongBox KeyMint) on compatible Snapdragon devices [6, 458, 459]. Key operations are handled entirely within secure hardware, and key materials are non-exportable [6, 454, 456].
*   **SQLCipher Database Encryption:** All local SQLite databases (Episodic, Semantic, and Preference stores) are encrypted using 256-bit AES via SQLCipher [42, 227]. The database key is derived using PBKDF2 with SHA-512 and salted using hardware-bound keys retrieved from the Keystore, preventing offline database dumps [6, 227].
*   **Windows DPAPI / Keychain:** Desktop nodes use Apple Keychain or Windows DPAPI to secure their corresponding replication tokens and master keys in hardware enclaves [21, 810].

### 3. MCP Threat Mitigations (Netskope & Palo Alto Zero-Trust baselines)
*   **Tool Description Sanitization:** Tool definitions (descriptions, names, schemas) exposed by third-party MCP servers are considered untrusted [63, 323, 372]. JAX’s MCP Client runs an input regex parser that strips HTML comments (`<!-- -->`), hidden markdown hyperlinks, invisible Unicode control sequences, and base64 payloads from description fields before loading them, preventing **Tool Description Poisoning** and **Cross-Server Tool Shadowing** [63, 326, 331, 332].
*   **Origin-Tagged Sampling:** When an MCP server initiates a request for model sampling (`sampling/createMessage`), JAX enforces strict **Origin Tagging** [336, 359]. The prompt is explicitly tagged with the server's unique cryptographic ID, and the JAX client displays a mandatory, non-bypassable confirmation UI displaying the exact requesting server, its tool context, and the proposed prompt, preventing **Sampling-Based Injections** [336, 346, 347].

---

## 10. Technology Stack Recommendation

To allow a **solo developer** to build, maintain, and scale JAX successfully, the stack prioritizes deeply embedded, mature, and zero-dependency libraries over heavy, fragmented, cloud-native frameworks [24, 98, 233].

```
+-----------------------------------------------------------------------------------+
|                                 JAX TECHNOLOGY STACK                              |
+-----------------------------------------------------------------------------------+
|  [APPLICATION LAYER]                                                              |
|    - Kotlin / Jetpack Compose Multiplatform (Android + Desktop UI) [230]           |
|    - Kotlin Coroutines & Flow (Event-driven asynchronous orchestrator) [130]      |
|                                                                                   |
|  [LOCAL INFERENCE LAYER]                                                          |
|    - MLC Chat (Hexagon NPU via MLCC) / PocketPal AI (Vulkan GPU wrapper) [7, 483] |
|    - Models: Qwen3-Mini (1.5B, INT4) [7, 734] + Phi-4-Mini (3.8B, INT4) [7, 734]  |
|                                                                                   |
|  [STORAGE & DATA LAYER]                                                           |
|    - SQLCipher (256-bit AES database encryption) [42, 227]                       |
|    - SQLite with sqlite-vec extension (On-device vector search) [14, 63]          |
|    - cr-sqlite / sqlite-sync (CRDT multi-master replication) [15, 37, 78]        |
|                                                                                   |
|  [SECURITY & OS INTEGRATION]                                                      |
|    - Android Keystore & StrongBox KeyMint secure elements [6, 458]                |
|    - Android AccessibilityService (UI Tree Extraction & Screen parsing) [8, 486]   |
|                                                                                   |
|  [TELEMETRY & OBSERVABILITY]                                                      |
|    - Langfuse (Self-hosted local container for nested agent tracing) [228, 826]   |
|    - Arize Phoenix (Offline LLM-as-a-Judge evaluations) [228, 67]                 |
+-----------------------------------------------------------------------------------+
```

### Key Technology Decisions

#### 1. Database & Graph Memory Storage
*   **Recommendation:** **SQLite Relational Graph Schema + `sqlite-vec` extension** [14, 232].
*   **Why:** SQLite is highly stable, lightweight, and supports unified relational, vector (`sqlite-vec`), and relational graph schemas in a single in-process file, avoiding external database dependencies [24, 98, 222, 232]. 
*   **Conflict Resolved:** While dedicated property graph databases like Kuzu offer optimized Cypher queries, **Apple acquired and archived KuzuDB in October 2025**, halting its open-source development [232]. Other multi-model engines like ArcadeDB are written in Java, introducing prohibitive memory overhead for local mobile platforms [694]. SQLite Relational Graphs represent the most stable, performant, and durable choice for on-device memory [24, 98, 232].
*   **Alternative:** **LadybugDB** (the active community fork of Kuzu) [744, 754], or **GraphQLite** (SQLite extension adding Cypher queries) [38, 195].

#### 2. Local Model Inference Engine
*   **Recommendation:** **MLC Chat (for NPU execution) + llama.cpp/PocketPal AI (for GGUF compatibility)** [7, 483, 794].
*   **Why:** MLC Chat utilizes the MLCC compile framework to directly target the Snapdragon Hexagon NPU on Snapdragon 8 Elite chipsets, reaching **~40 tok/s on Qwen3 1.7B and ~22 tok/s on Phi-4 Mini** (3x faster than CPU-only alternatives) [7, 473]. For non-Snapdragon devices or custom models, JAX falls back to llama.cpp / PocketPal AI via Vulkan-GPU [7, 481, 794].
*   **Alternative:** **Ollama via Termux** (CPU-only, slow performance, hard to integrate into native Kotlin workflows) [7, 478].

#### 3. Data Synchronization & P2P Replication
*   **Recommendation:** **cr-sqlite / sqlite-sync (CRDT replication)** [15, 37, 78].
*   **Why:** Embeds CRDT semantics directly into standard SQLite tables, allowing conflict-free multi-writer replication across devices asynchronously without requiring complex application-layer sync protocols or leader elections [14, 58, 15, 78].
*   **Alternative:** **Automerge 2.0 / Yjs** (requires separate JSON databases and manual mapping to SQLite database schemas, adding significant code overhead) [15, 77, 83].

#### 4. Trace Observability & Evaluation
*   **Recommendation:** **Langfuse (Self-hosted local container) + Arize Phoenix (Offline Judge)** [56, 228].
*   **Why:** Langfuse provides lightweight, self-hostable nested agent tracing with out-of-the-box support for costs, sessions, and latency metrics [228, 826]. It can be deployed in a local Docker container in minutes [826]. Arize Phoenix is utilized for running offline evaluations ("LLM-as-a-judge") against captured trace datasets, ensuring prompt changes do not introduce regressions [228, 67].
*   **Alternative:** **LangSmith / Braintrust** (expensive, closed-source SaaS offerings with high data exfiltration risk) [83].

---

## 11. Top-10 Differentiators (Top-1% Personal AI Assistant)

These ten capabilities elevate JAX beyond basic chatbot applications, transforming it into a highly specialized, reliable, and deeply integrated personal OS assistant:

1.  **Symbolic Variable Separation (Dual-LLM Shielding):** Complete protection against direct and indirect prompt injections by isolating the planning model (P-LLM) from all raw, untrusted text inputs, utilizing symbolic variable placeholders (`$VAR1`) managed by a non-LLM Controller [82, 735].
2.  **Episodic-to-Semantic Auto-Consolidation (Sleep Cycle):** Runs background routines that process raw interaction logs during device idle times, converting transient episodic logs into permanent semantic graph nodes [24, 96, 223].
3.  **Local Hybrid Search (FTS + Vector + Graph):** A single-query retrieval engine inside SQLite that combines full-text indexing (BM25), vector similarity (`sqlite-vec`), and relational graph traversals to provide highly precise, context-aware memory recall [24, 98, 222].
4.  **Hardware-Bound Secure Keystore Orchestration:** Protecting system tokens and encryption keys inside secure elements (Android StrongBox KeyMint) [6, 458]. Plaintext keys never enter application memory space [6, 456].
5.  **Offline-First CRDT State Replication:** Synchronizing personal files, user models, and task databases across mobile and desktop devices peer-to-peer over local networks using CRDTs without central server coordination [14, 226].
6.  **Granular Context Resolution via Hybrid Decay:** Ensures stable performance of local model context windows by applying mathematical decay curves to historical graph connections, keeping active nodes visible while compacting older relationships [24, 97, 223].
7.  **Adaptive Rollback Recovery Engine:** A resilient orchestrator that evaluates external API failures, automatically invoking argument-level rollbacks (retry with parameters) or API-level rollbacks (re-routing via alternative tools) [13, 224].
8.  **Proactive Heartbeat Activation:** Evaluates user context, calendar states, and device triggers against an active rule graph in the background, allowing JAX to intervene autonomously without user prompting [30, 229].
9.  **Continuous Accessibility Layout Tree Extraction:** A secure background service that continuously parses active application layout trees via `AccessibilityService`, maintaining structural context without expensive screenshot visual processing [8, 491, 519].
10. **Dynamic Confidence-Based Model Cascading:** Evaluates task complexity locally first, utilizing token-level entropy and calibrated confidence metrics to determine when to escalate processing to cloud engines [26, 723].

---

## 12. Phased Development Roadmap

```
Phase 1: Foundation     Phase 2: Cognitive Memory   Phase 3: Tools & MCP      Phase 4: Agent & Proactivity
+-------------------+   +-----------------------+   +---------------------+   +--------------------------+
| - Kotlin Compose  |   | - SQLite + SQLCipher  |   | - Stdio MCP Gateway |   | - State Machine log [13] |
| - SQLCipher [42]  |-->| - sqlite-vec index[14]|-->| - Roots Sandbox [66]|-->| - Argument Rollback [13] |
| - StrongBox [6]   |   | - Phi-4-Mini local [7]|   | - CIMD Metadata [63]|   | - Proactive triggers [30]|
| - Gemini API [35] |   | - Langfuse traces [56]|   | - OAuth 2.1 [63]    |   | - Notify/Ask UI [30]     |
+-------------------+   +-----------------------+   +---------------------+   +--------------------------+
                                                                                           |
                                                                                           v
Phase 5: Voice/Screen   Phase 6: Sync & OS (Watch)                                 Phase 5 (Continued)
+-------------------+   +-----------------------+                                  +--------------------------+
| - Gemini Live [35]|   | - sqlite-sync CRDT    |                                  | - AccessibilityService   |
| - Duplex PCM      |-->| - Block-Level LWW [37]|--------------------------------->|   Tree parsing [8]       |
| - WebRTC streams  |   | - P2P mDNS Sync [14]  |                                  | - takeScreenshot() [8]   |
+-------------------+   +-----------------------+                                  +--------------------------+
```

### Phase 1: Foundation (Build Now)
*   **Objective:** Set up the basic application structure, secure local storage, and establish baseline cloud connectivity [230].
*   **Technologies:** Kotlin Compose Multiplatform, SQLCipher, Android Keystore, and Gemini API [230].
*   **Success Criteria:** JAX compiles locally, encrypts database files using hardware-bound keys, and runs basic conversational exchanges [27, 64].

### Phase 2: Cognitive Memory (Build Now)
*   **Objective:** Implement the multi-tiered memory architecture and run local model inference on-device [24, 98, 230].
*   **Technologies:** SQLite database, `sqlite-vec` extension, local Phi-4-Mini (GGUF), and self-hosted Langfuse tracing container [7, 14, 228].
*   **Success Criteria:** On-device memory searches resolve semantic queries within $30	ext{ ms}$ while tracking data provenance, and all agent calls generate nested telemetry spans in Langfuse [18, 28, 228].

### Phase 3: Tools & MCP Gateway (Build Now)
*   **Objective:** Connect JAX to external calendars, files, and systems using Model Context Protocol standard adapters [66, 230].
*   **Technologies:** Python/Node MCP SDK, JSON-RPC 2.0 stdio/HTTP transports, and AFS mounting resolvers [32, 66].
*   **Success Criteria:** The system discovers local tools, prompts for user confirmation, and executes verified functions securely [38, 40, 49].

### Phase 4: Agent & Proactivity (Build Later)
*   **Objective:** Enable long-running tasks, error recovery, and proactive background operations [13, 30].
*   **Technologies:** Event-driven publish-subscribe engine (Kotlin Flow), transaction log tables, argument-level and API-level rollback handlers [13, 130].
*   **Success Criteria:** Background events trigger proactive plans, and tool failures are recovered adaptively without manual code intervention [13, 30].

### Phase 5: Voice, Screen & Multimodal (Build Later)
*   **Objective:** Build high-fidelity screen perception and duplex conversational capabilities [69, 221].
*   **Technologies:** Gemini Live WebRTC integration, Android AccessibilityService tree parsers, and silent `takeScreenshot()` capture [8, 69].
*   **Success Criteria:** Duplex voice chat operates with $< 800	ext{ ms}$ response latency, and JAX extracts active application text fields and captures screens silently [8, 69].

### Phase 6: Sync & OS (Watch Only)
*   **Objective:** Replicate state peer-to-peer and establish JAX as a decentralized personal AI OS [14, 58].
*   **Technologies:** `sqlite-sync` / `cr-sqlite` CRDT engines, Block-Level LWW, and Windows DPAPI [14, 37, 227].
*   **Success Criteria:** Databases synchronize across mobile and desktop nodes, resolving state conflicts automatically without a central server [14, 37].

---

## 13. What NOT to Build

As a solo developer, you must protect your time by avoiding complex, high-overhead components that add minimal value to a personal assistant:

1.  **Do NOT build custom Vector Databases / Search Engines:** Standard SQLite with the `sqlite-vec` extension is fast, embedded, zero-config, and handles millions of vectors effortlessly [14, 63]. Avoid pgvector, Pinecone, or Weaviate containers on-device [24, 98].
2.  **Do NOT build custom Multi-Agent Orchestration Frameworks:** Avoid heavy frameworks like CrewAI or LangGraph [14, 67]. They introduce severe process overhead, token-bloat, and lack native durable execution properties [7, 13, 224]. Use a lightweight event machine with simple Kotlin Coroutines and transactional state tables [13, 130, 224].
3.  **Do NOT build proprietary Speech-to-Text / TTS Engines:** Do not attempt to run Whisper or local TTS models locally on mobile devices [7, 793]. They drain battery, cause thermal throttling, and cannot match the low-latency duplex quality of Gemini Live's streaming WebRTC audio API [7, 69, 793].
4.  **Do NOT build centralized Synchronization Servers:** Do not write custom sync protocols, WebSocket managers, or multi-tenant database backends [14, 58]. Use `sqlite-sync` and `cr-sqlite` to handle P2P replication natively [15, 78].
5.  **Do NOT build custom Prompt Registries:** Do not build a complex prompt deployment service [203]. Treat prompts and tool schemas as immutable, version-controlled flat files stored in your local directory (procedural memory), managing promotions through standard git workflows [24, 98, 203].

---

## 14. 20 Engineering Principles for JAX

1.  **Local-First Execution by Default:** Process all continuous user sensory feeds, context assembly, and sensitive transactions locally to preserve absolute privacy and maintain sub-second response times [7, 14, 720].
2.  **Structural Security over Prompt Filters:** Never rely on prompt instructions (e.g., "Do not leak data") to block injections [63, 735]. Enforce structural boundaries using the Dual-LLM Shielding Pattern [82, 735].
3.  **Zero Tool Access for Quarantined Models:** Any model instance that processes untrusted text (Q-LLM) must be run fully sandboxed, possess zero tool execution capabilities, and be treated as compromised [82, 735].
4.  **Hardware-Backed Cryptographic Roots:** All local data encryption keys and replication tokens must be stored in hardware secure elements (Android StrongBox or DPAPI) and never exist as plaintext in application memory [6, 21, 227].
5.  **Multi-Tiered Memory Segregation:** Segregate memory into explicit, managed layers (Working, Episodic, Semantic, Procedural, Preference) to prevent context-bloat and maximize retrieval accuracy [24, 95].
6.  **Asynchronous Memory Consolidation:** Offload heavy memory distillation, clustering, and graph building to an offline, asynchronous sleep cycle to keep runtime user-facing operations fast and cost-effective [24, 96, 120].
7.  **Standardized Interfaces (Model Context Protocol):** All tool definitions, prompt templates, and data schemas must strictly conform to the open Model Context Protocol [66, 277].
8.  **Explicit Consent for State-Changing Actions:** Destructive, financial, or communication actions (e.g., sending emails, deleting files, or spending money) must require explicit, biometric user verification [66, 372].
9.  **Isolated Tool Execution:** Run local tool code blocks inside isolated processes or sandboxes to prevent tool crashes from compromising the core host application [32, 225].
10. **Stateless Protocol Communication:** Keep client-server protocol messages stateless [66, 436]. Ensure requests contain all necessary capabilities and meta-context to enable scale-out without session affinity [66, 292, 436].
11. **Event-Driven Energy Efficiency:** Keep agents dormant and sleeping in-memory, waking them only when triggered by explicit system events, preventing expensive continuous polling loops [30, 123].
12. **Continuous Structured Screen Parsing:** Prioritize reading accessibility tree nodes (`AccessibilityService`) to understand screen context before falling back to expensive VLM visual screenshots [8, 491, 516].
13. **Local-First Conflict-Free Convergence:** Synchronize multiple personal devices peer-to-peer using CRDTs (sqlite-sync), resolving state conflicts deterministically without central leader databases [14, 37].
14. **Deterministic Evaluation Loops:** Run continuous evaluation checks against standard local benchmarks (using Arize Phoenix) to detect prompt-induced behavioral regressions [228, 67].
15. **Granular Telemetry Tracing:** Record all agent decisions, intermediate variables, and tool parameters in nested, structured trace logs (using self-hosted Langfuse) [228, 826].
16. **Immutable Prompt and Tool Versioning:** Treat system prompts and tool schemas as immutable, versioned assets promoted via Git tags to ensure reproducibility [203, 396].
17. **Programmatic Failover & State Rollbacks:** Always validate tool execution outcomes, implementing argument-level or API-level rollbacks to prevent cascading errors in long-running tasks [13, 224].
18. **Ambient Context Adaptation:** Monitor user attention, calendar states, and daily patterns continuously, feeding them dynamically into preference and user models [222, 223].
19. **Minimal External Dependencies:** Prioritize lightweight, embedded, Apache 2.0-licensed components to ensure long-term codebase maintainability and portability [662, 233].
20. **Continuous Token Optimization:** Compress historical data and evict redundant context continuously, preserving context window capacity for active reasoning [24, 97, 221].

---
