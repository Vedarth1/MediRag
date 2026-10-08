# MediRAG — Interview Script

> Ground truth: everything in this document was verified against the code in this repo.
> Where the old README is stale, this doc is correct and says so.

---

## 0. How to use this

You built this months ago and forgot the details. That is normal and it is fine — but
you cannot bluff an interviewer on a system *you* claim to have built. So this doc has
three jobs:

1. **A rehearsed narrative** (sections 1–2) so you can talk for 2 minutes without rambling.
2. **Hard numbers** (section 3) — interviews reward specificity. "About 5 chunks" is weak;
   "top-K of 5 with a cosine threshold of 0.3" is strong.
3. **Honest weak spots** (section 9) — the single highest-leverage part. Volunteering a
   limitation *before* they find it converts a red flag into a signal of maturity.

Read sections 1–3 out loud until they're natural. Skim the rest. Pair this with
[MediRAG-counter-questions.md](MediRAG-counter-questions.md).

---

## The opening move — your intro (say this almost verbatim)

When they say *"tell me about yourself"* or *"walk me through your project"*, you get
the first 30 seconds free and you control the agenda for the next 40 minutes. Do not
spend it on chronology ("I started with the auth service, then I..."). Open with the
**most impressive thing**, then hand them the follow-up you're best prepared for.

### The script

> "Thanks — I'll lead with the project that best shows how I work.
>
> It's called **MediRAG**: a healthcare platform built as six Spring Boot services behind
> a Spring Cloud Gateway — auth, appointments, health tracking, mental wellness and an AI
> diagnostic service — plus a Python embedding service for vector search.
>
> What I deliberately avoided was building a CRUD app with an AI call bolted on. Every
> layer carries a real decision: a token-bucket rate limiter at the gateway, Redis
> Cache-Aside with targeted invalidation, and a pessimistic row lock so two patients
> can't book the same slot at the same moment.
>
> But the part I'd want to be judged on is the diagnostic service, because that's where
> I built something non-trivial. You upload an X-ray **or** a PDF report, and both get
> analysed against a medical knowledge base in pgvector. The image path is two-stage: the
> first Vision call is deliberately cheap and does one job — extract radiological terms.
> The reason is that **you can't similarity-search a JPEG**; retrieval needs text, so the
> cheap call manufactures the query. Those terms pull the top five reference chunks, and
> a second, higher-quality call writes the structured report grounded in them.
>
> The decision I'm happiest with is that my failure policies are deliberate and
> *asymmetric*. If retrieval fails mid-analysis, it degrades to an ungrounded report —
> availability over quality, because a patient should never get a 500. But if ingesting a
> reference document fails, it aborts and rolls back, because a half-loaded knowledge base
> would silently poison every future diagnosis. Same infrastructure, opposite policy,
> chosen per path.
>
> Happy to go as deep as you like on the RAG pipeline, or the infrastructure."

### Delivery notes

- **Pause after "MediRAG"** and after "the part I'd want to be judged on". Silences read as
  confidence; they also give the interviewer a chance to steer.
- **Drop your pitch and slow down** on *"you can't similarity-search a JPEG"*. That single
  line does more work than the rest of the paragraph — it's the insight, and it's the
  moment they stop taking notes and start listening.
- **"I'd want to be judged on"** is a load-bearing phrase. It signals you know which part
  is hard, and it invites the deep-dive instead of forcing it.
- **The asymmetric-failure paragraph is the closer.** It's a judgment answer, not a
  feature answer, and senior reviewers weight judgment above scope. It also sets up the
  fail-open vs fail-closed question you have a great answer to.
- **End on an offer, not a full stop.** "Happy to go as deep as you like" converts a
  monologue into a conversation and lets them choose.
- **Target 75 seconds** (the text above runs about 300 words). If they look impatient, cut
  the four middle decisions to two and keep the RAG arc and the closer. Never cut the
  closer — that's the part that distinguishes you from someone who just listed a stack.

### The three flavours, and how to adapt

| They ask | Lead with | Then pivot to |
|---|---|---|
| "Tell me about yourself" | This script as written | Work history only if they ask |
| "Walk me through your most complex project" | The architectural scope, then the RAG arc | The two-stage *why* |
| "Tell me about something you're proud of" | The fail-open/fail-closed asymmetry **first** | Briefly, how the rest of it works |
| "What's a hard bug you hit?" | The MinIO presigned-URL signature mismatch | Broader debugging approach |

### What not to say

- **"It's basically a healthcare app with AI."** Undersells the entire project in six words.
- **"I used Spring Boot, Redis, Postgres, MinIO, pgvector and Groq."** A stack list is not
  an intro. Technologies only matter attached to a decision.
- **"...and then I also built a rate limiter."** Chain-of-features intros have no arc and
  invite no follow-up. Lead with the hardest thing instead.
- **Don't say "GPT-4o."** You're running Llama 4 Scout on Groq. See the cheat sheet.
- **Don't hedge with "kind of" / "basically" / "just".** Every "just" deletes credit
  you just earned.

---

## 1. The 30-second pitch

> "MediRAG is a healthcare platform I built to work across a lot of concerns at once
> instead of just CRUD. It's six Spring Boot services — a gateway plus five domain
> services: auth, appointments, health tracking, mental wellness and an AI diagnostic —
> and a small Python embedding service.
>
> The part I'm most proud of is the diagnostic service. I turned it into a real
> two-pipeline RAG system: you can upload an X-ray *or* a PDF/Word report, and both get
> grounded against a medical knowledge base stored in pgvector. The image path is a
> two-stage Vision flow — a cheap call extracts radiological terms, those terms drive
> vector retrieval, then a second Vision call generates the structured report using the
> retrieved knowledge as context."

**Why this works:** it's specific, it leads with the hardest technical thing, and it
names the pattern (two-stage RAG) rather than describing the UI.

---

## 2. The 2-minute walkthrough

Use this order — it follows a request through the system, which is how reviewers think.

### (a) Request path through the gateway

> "Every request enters at the gateway on 8080. It does three things: it validates the
> JWT in a global filter that runs at order `-1` — so before routing — then it injects
> the identity downstream as `X-User-Id`, `X-User-Email`, `X-User-Role` headers, and
> it applies a Redis-backed token-bucket rate limiter per route.
>
> The header injection is the important design choice. It means the five domain services
> never have to parse a JWT themselves for identity — the gateway is the only component
> that fully trusts the token, and downstream services read plain headers. I still run a
> local JWT filter in each service as a second layer, so a service isn't wide open if it
> is ever reached directly."

### (b) Auth and sessions

> "Auth service handles registration and login with BCrypt hashing. On login it issues
> an HS256 JWT — signed with jjwt 0.12.5, subject is the email, with `role` and `userId`
> as custom claims, 24-hour expiry.
>
> The thing worth calling out: I also write the token to Redis under `jwt:<email>` with a
> 24-hour TTL. The reason is that a plain JWT is stateless — once issued, you can't
> un-issue it. By keeping a server-side session record I gave myself a place to revoke
> from, which is what makes logout possible conceptually. I'll be honest that I only
> finished half of that: the key gets deleted on logout, but the enforcement point —
> checking Redis during authentication — is what I'd wire up next."

That last sentence is deliberate. See section 9, item 3.

### (c) Appointment service — caching and concurrency

> "Appointments is where I used the Cache-Aside pattern properly. The doctor list is
> cached in Redis under `doctors:all` or `doctors:<specialization>` with a 10-minute TTL.
> Reads check cache first, miss → Postgres → write back. And I don't just let the TTL
> expire — I do targeted invalidation: registering a doctor, booking, and cancelling all
> delete the specific keys, because availability has changed.
>
> The other thing in there is double-booking. Two patients hitting the same slot at the
> same moment is a classic race. I took a **pessimistic write lock** on the slot row —
> `SELECT ... FOR UPDATE` via `@Lock(PESSIMISTIC_WRITE)` — inside the transaction, so the
> second request blocks until the first commits and then sees `isBooked = true`."

### (d) The diagnostic service — the RAG system

> "Diagnostic is the interesting one. Upload accepts images *and* documents, validates
> content type and a 20MB cap, stores the file in MinIO, returns `202 Accepted`
> immediately, and kicks off analysis asynchronously. The pipeline is chosen by MIME type.
>
> **Image pipeline** — two-stage Vision. Stage 1 is a deliberately cheap call with
> `detail: low` and `max_tokens: 150`; its only job is to return a comma-separated list of
> radiological terms, like 'cardiomegaly, bilateral pleural effusion'. I embed that string
> and run a cosine search against pgvector for the top 5 medical reference chunks above a
> 0.3 threshold. Stage 2 is the real call, `detail: high`, with the retrieved chunks
> injected into the system prompt as labelled reference material. It returns strict JSON —
> summary, overall confidence, and an array of findings with confidence, severity and a
> bounding box.
>
> **Report pipeline** — one stage. Extract text with PDFBox or Apache POI depending on
> format, chunk it sentence-aware at 500 characters with 50 characters of overlap, embed
> the chunks in a single batch call, retrieve per chunk, deduplicate by chunk ID, cap it,
> then do one LLM call with the full report text plus retrieved context. I reuse the exact
> same output JSON schema, so one parser handles both pipelines.
>
> The whole thing is **fail-open**. If the embedding service is down, retrieval returns
> empty context and the analysis still runs ungrounded. If parsing fails, the patient gets
> a 'consult a radiologist' fallback report rather than a 500. A missing knowledge base
> degrades quality, never availability."

### (e) The embedding service

> "The embeddings are local, not an API. A small FastAPI service runs
> `sentence-transformers/all-MiniLM-L6-v2` — 384 dimensions. The model is loaded once at
> startup in a FastAPI lifespan handler and reused, because loading per request would add
> seconds of latency each time. It exposes `/embed` for queries and `/embed/batch` for
> ingestion, capped at 64 texts per call, so ingesting a 50-page document is one HTTP
> round-trip instead of fifty. Embeddings come back L2-normalised, which is why I can
> compute cosine similarity as a plain dot product on the Java side."

That last clause is the kind of detail that makes an interviewer believe you wrote it.

---

## 3. Cheat sheet — the numbers

Memorise this block. It's where preparation shows.

| Thing | Value | Where |
|---|---|---|
| Services | 6 Spring Boot + 1 Python (FastAPI) | `docker-compose.yml` |
| Ports | gateway 8080, auth 8081, appointment 8082, health 8083, mental 8084, diagnostic 8085, embedding 8086 | `docker-compose.yml` |
| Gateway filter order | `-1` (before routing) | [JwtAuthFilter.java](../../gateway/src/main/java/gateway/medirag/gateway/filter/JwtAuthFilter.java) |
| Rate limit (normal routes) | replenish 10/s, burst 20 | [application.yaml](../../gateway/src/main/resources/application.yaml) |
| Rate limit (diagnostics) | replenish 5/s, burst 10 | same |
| Rate limit key | client IP (`@ipKeyResolver`) | [GatewayConfig.java](../../gateway/src/main/java/gateway/medirag/gateway/config/GatewayConfig.java) |
| JWT | HS256, jjwt 0.12.5, sub=email, claims `role` + `userId`, 24h | [JwtUtil.java](../../auth-service/src/main/java/com/medirag/auth/security/JwtUtil.java) |
| Session store | Redis `jwt:<email>`, TTL 24h | [AuthService.java](../../auth-service/src/main/java/com/medirag/auth/service/AuthService.java) |
| Password hashing | BCrypt | same |
| Doctor cache | `doctors:all` / `doctors:<spec>`, TTL 10 min | [AppointmentService.java](../../appointment-service/src/main/java/com/medirag/appointment/service/AppointmentService.java) |
| Slot locking | PESSIMISTIC_WRITE (`SELECT FOR UPDATE`) | [TimeSlotRepository.java](../../appointment-service/src/main/java/com/medirag/appointment/repository/TimeSlotRepository.java) |
| Upload cap | 20 MB file, 25 MB request | [application.yaml](../../diagnostic-service/src/main/resources/application.yaml) |
| Upload response | `202 Accepted` | [DiagnosticController.java](../../diagnostic-service/src/main/java/com/medirag/diagnostic_service/controller/DiagnosticController.java) |
| Embedding model | `all-MiniLM-L6-v2`, 384 dims, normalised | [main.py](../../embedding-service/main.py) |
| Batch embed cap | 64 texts/call, batch_size 32 | same |
| Vector column | `vector(384)`, **IVFFlat** index, `vector_cosine_ops`, `lists=100` | [init-db.sql](../../init-db.sql) |
| Distance operator | `<=>` (cosine distance), ORDER BY ASC | [KnowledgeChunkRepository.java](../../diagnostic-service/src/main/java/com/medirag/diagnostic_service/repository/KnowledgeChunkRepository.java) |
| Retrieval | top-K 5, similarity threshold 0.3 | [application.yaml](../../diagnostic-service/src/main/resources/application.yaml) |
| Chunking | 500 chars, 50 overlap, sentence-aware | [DocumentChunkingService.java](../../diagnostic-service/src/main/java/com/medirag/diagnostic_service/service/DocumentChunkingService.java) |
| Report retrieval cap | topK × 2 = 10 chunks | [RetrievalService.java](../../diagnostic-service/src/main/java/com/medirag/diagnostic_service/service/RetrievalService.java) |
| Stage 1 tokens | 150, `detail: low` | [AIAnalysisService.java](../../diagnostic-service/src/main/java/com/medirag/diagnostic_service/service/AIAnalysisService.java) |
| Stage 2 tokens | 1500, `detail: high` | same |
| Presigned URL TTL | 15 minutes | [MinioService.java](../../diagnostic-service/src/main/java/com/medirag/diagnostic_service/service/MinioService.java) |
| DB | Postgres 16 + pgvector, schema-per-service | [docker-compose.yml](../../docker-compose.yml) |
| Schemas | `auth`, `appointment`, `health`, `mental_health`, `diagnostic` | [init-db.sql](../../init-db.sql) |
| LLM | Groq, `meta-llama/llama-4-scout-17b-16e-instruct` (OpenAI-compatible API) | [diagnostic application.yaml](../../diagnostic-service/src/main/resources/application.yaml) |

> **Correct your README before the interview.** It still says "OpenAI GPT-4o (vision)" and
> "five services". You're on Llama 4 Scout via Groq's OpenAI-compatible endpoint, and
> there are six Spring services plus the embedding service. If they read your README and
> ask about GPT-4o, you'll look like you don't know your own stack.

---

## 4. Architecture diagram (draw this on a whiteboard)

```
                         ┌──────────────────────────────┐
        client ────────► │  Gateway :8080               │
                         │  • JWT global filter (ord -1)│
                         │  • token-bucket rate limiter │◄──► Redis (rate buckets)
                         │  • injects X-User-Id/Email/Role
                         │  • CORS                      │
                         └───────┬──────────────────────┘
                                 │
      ┌──────────┬───────────┬───┴───────┬────────────────┐
      ▼          ▼           ▼           ▼                ▼
   Auth:8081  Appt:8082  Health:8083  Mental:8084   Diagnostic:8085
      │          │           │           │                │
      └──────────┴───────────┴───────────┴────────────────┤
                                                          │
                                     Postgres 16 + pgvector (schema-per-service)
                                     Redis  (cache + sessions + rate limits)
                                     MinIO  (scans + generated PDF reports)
                                                          │
                                                          ▼
                                          Embedding svc :8086 (FastAPI)
                                          all-MiniLM-L6-v2 → 384-d vectors
                                                          ▲
                                                          │
                                          Groq / Llama-4-Scout (Vision + text)
```

---

## 5. Gateway deep dive — what to say

**Question shape: "Walk me through your gateway."**

- It's **Spring Cloud Gateway on Netty — reactive, not servlet**. Worth naming, because
  it means the filter returns `Mono<Void>` and you mutate the exchange rather than
  building a servlet filter chain.
- Routes are declarative YAML: path predicate → downstream URI. Five routes.
- The JWT filter is a `GlobalFilter` with `getOrder()` returning `-1`, chosen so it runs
  before routing filters — reject bad tokens before you spend anything routing them.
- A `PUBLIC_PATHS` allowlist short-circuits the check: register, login, doctor list, slot
  list, mental-health resources, and every service's `/health`.
- On success it mutates the request to add `X-User-Id`, `X-User-Email`, `X-User-Role`.
  Downstream services read those headers — **identity is resolved once, at the edge**.
- Rate limiting is `RequestRateLimiter` with the Redis token-bucket implementation. Key
  is client IP. I gave diagnostics a lower budget (5/s, burst 10) because those requests
  carry file uploads and expensive model calls; everything else gets 10/s with burst 20.
- CORS is configured globally for `localhost:3000` and `localhost:5173`, with a
  `DedupeResponseHeader` filter so CORS headers don't get duplicated when both the gateway
  and a downstream service add them.

**Strong line to include:** *"Rate limiting at the edge rather than per-service means one
enforcement point and one config, and it protects the expensive AI routes from the whole
internet instead of just from logged-in users."*

---

## 6. Auth & security deep dive — what to say

- **Why JWT + Redis instead of pure JWT?** Because pure JWT can't be revoked. State lives
  in the token, so a stolen token is valid until expiry. Adding a Redis session record
  gives a revocation surface.
- **Why BCrypt?** Deliberately slow and salted per-hash, so a DB leak doesn't hand over
  passwords and rainbow tables are useless. Spring Security's `PasswordEncoder.matches()`
  handles the salt.
- **Why is identity injected as headers?** So only one component needs JWT-parsing logic
  and a secret. It also keeps the domain services decoupled from the token format.
- **Defence in depth:** each service still runs its own servlet JWT filter + a stateless
  `SecurityFilterChain` with `SessionCreationPolicy.STATELESS`, CSRF disabled because
  there are no cookies, Swagger and health permitted, everything else authenticated.

**Say this if pressed on trust boundaries:** *"The gateway is the trust boundary. In this
build I also publish service ports in docker-compose for local debugging, which means
those services are directly reachable and would trust forged `X-User-*` headers. That's a
deliberate local-dev convenience and the first thing I'd remove for any real deployment —
either don't publish the ports, or require an internal shared secret / mTLS on
service-to-service calls."* Owning this is far better than being caught by it.

---

## 7. Caching deep dive — what to say

Cache-Aside is used in **three** services, not one:

| Service | Key | TTL | Invalidated by |
|---|---|---|---|
| Appointment | `doctors:all`, `doctors:<spec>` | 10 min | register doctor, book, cancel |
| Health | `mealplans:<userId>` | config | profile update, new plan |
| Mental Health | resources list | config | resource changes |

The talking points:

- **Cache-Aside, not write-through**, because the read:write ratio on a doctor list is
  enormous — you'd otherwise hammer Postgres for data that changes a few times a day.
- **TTL is the backstop, not the strategy.** The real correctness comes from *targeted
  invalidation*: booking or cancelling changes availability, so the specific
  specialization key and the `all` key are deleted at the same moment inside the
  transaction. TTL alone would expose stale availability for up to 10 minutes.
- **JSON serialization boundary.** Values are stored as JSON strings via `ObjectMapper`
  and deserialized with a `CollectionType` token, so the cache doesn't leak a Hibernate
  entity graph — DTOs only. That also avoids lazy-loading surprises on deserialization.
- **The two hard problems** (if they ask): invalidation, and stampede. I handle
  invalidation explicitly. Stampede — many concurrent misses all querying the DB at once —
  is not solved here; I'd use a short lock or single-flight on the hot key.

---

## 8. The RAG deep dive — the money section

This is the part that differentiates the project. Get this fluent.

### 8.1 What "two pipelines" means

The system has **one upload endpoint** and **two analysis pipelines**, selected by MIME
type. Files split into `REPORT` (pdf, docx, txt) and `IMAGE` (jpeg, png, webp), and the
chosen pipeline is persisted on the scan row so you can debug which path ran.

### 8.2 Image pipeline, step by step

| Step | What happens | Why |
|---|---|---|
| 1 | Read image from MinIO, base64-encode into a data URL | Model API takes base64, not an object store reference |
| 2 | **Stage 1** Vision call: `detail: low`, `max_tokens: 150`, prompt asks for comma-separated terms only | Produces a *text query* cheaply. Low detail is far cheaper and you only need gross features to build the query |
| 3 | Embed the term string via `/embed` | Turns image evidence into something the vector index understands |
| 4 | pgvector cosine search, `LIMIT 5`, drop anything under 0.3 | Grounds the analysis in curated reference material |
| 5 | **Stage 2** Vision call: `detail: high`, `max_tokens: 1500`, retrieved chunks injected into the system prompt as a labelled reference block | The expensive, high-quality call now has both the image and relevant knowledge |
| 6 | Parse strict JSON → report + findings, generate PDF, mark completed | Same schema as the report pipeline |

**The key insight to articulate:** *"The reason for two stages rather than one is
economics and latency. Stage 1 costs almost nothing and exists purely to manufacture a
retrieval query out of an image. You can't similarity-search a JPEG — you can search the
text the model derives from it. Stage 2 then does the work that actually needs to be
careful, and it does it with grounded context."*

### 8.3 Report pipeline, step by step

| Step | What happens |
|---|---|
| 1 | Stream file from MinIO (no re-download, no temp file) |
| 2 | Extract text — PDFBox for PDF, Apache POI for DOCX, direct read for TXT. Reject password-protected PDFs and image-only PDFs with a clear message |
| 3 | Clean artefacts: form feeds, NUL bytes, repeated page-number lines |
| 4 | Chunk sentence-aware, 500 chars, 50 overlap |
| 5 | **Batch** embed all chunks in one call |
| 6 | Per chunk: cosine search top-5, accumulate, dedupe by chunk ID, cap at 10 |
| 7 | One LLM call: full report text + retrieved context in the system prompt |

**Two design details that earn credibility:**

- *Sentence-aware chunking.* "I don't cut blindly at 500 characters. I split on sentence
  boundaries with a lookbehind regex and only start a new chunk at a boundary, so every
  chunk is a complete thought. The 50-character overlap means a finding that straddles a
  boundary still appears intact in one of the two chunks."
- *Deduplication.* "Ten report chunks all searching the same knowledge base will return
  overlapping results, so I keep a `LinkedHashSet` of chunk IDs and drop repeats before
  capping. Without that, the context window fills with duplicates and wastes tokens."

### 8.4 Ingestion — how the knowledge base gets filled

Admin-only endpoint (`POST /api/diagnostics/admin/knowledge`), never on a patient path:

```
raw text → chunk → batch embed → saveAll() → pgvector
```

- `@Transactional`, so a partial ingest never lands.
- If the embedding service is unavailable it **throws and aborts** — deliberately the
  opposite of the fail-open behaviour on patient paths. For ingestion, silent partial
  success would mean a silently incomplete knowledge base. Different risk, different
  policy. *This contrast is a great thing to say out loud.*
- It asserts `embeddings.size() == chunks.size()` before saving, because a dimension/count
  mismatch would poison the index.
- Chunks carry `sourceTitle`, `sourceType` (DISEASE, SYMPTOM, RADIOLOGY_FINDING,
  DIFFERENTIAL_DIAGNOSIS, TREATMENT_GUIDELINE, REFERENCE_DOCUMENT), an optional
  `conditionTag`, and `chunkIndex`.

### 8.5 Retrieval internals worth naming

- `<=>` is pgvector's **cosine distance** (0 = identical), so it's `ORDER BY ... ASC`.
- The index is **IVFFlat** with `vector_cosine_ops` and `lists = 100`. Without an index,
  every query is a full table scan.
- The `float[]` maps to a real `vector(384)` column via Hibernate's native support
  (`@JdbcTypeCode(SqlTypes.VECTOR)` plus the `hibernate-vector` dependency). The table is
  created by `init-db.sql`, **not** Hibernate, because `ddl-auto` can't generate a
  `vector(384)` column type natively — that's a real gotcha I hit.
- Query vectors are passed as pgvector's text literal `'[0.1,0.2,...]'` and `CAST(... AS
  vector)`, which is why there's no custom JDBC binding needed on the read path.
- Cosine similarity is computed in Java as a **plain dot product** — valid only because
  the embedding service sets `normalize_embeddings=True`, so all vectors are unit length.
  If that setting flipped, the scores would silently become wrong. *Naming this
  coupling shows you understand why it works, not just that it works.*

### 8.6 The knowledge/admin layer

There's an admin controller for stats and ingestion — deliberately not exposed on the
patient path, and it's the natural place to later add re-embedding, re-indexing, or
per-condition filtering (the repository already has a `findSimilarChunksByCondition`
variant written for exactly that).

---

## 9. Honest weak spots — say these before they find them

Volunteer **two or three**. Do not dump all of them. Pick the ones that fit the role.

1. **Secrets are committed in config files.** All six `application.yaml` files are tracked
   and carry hardcoded defaults: the JWT secret (same literal in all six), the Postgres
   password, MinIO credentials, and — the worst one — a Gmail app password as the default
   for `MAIL_PASSWORD` in the appointment service. **Credit where it's due:** `.env` is
   correctly gitignored and the LLM key is env-injected with a placeholder default
   (`${OPENAI_API_KEY:your-openai-key}`), so the API key itself is not exposed. But the
   other four secrets are in git history and must be treated as compromised and rotated.
   Fix: externalise every secret to environment injection or a vault, keep only `${VAR}`
   references in YAML with no fallback default (a default silently disables the
   protection), and commit a `.env.example` with placeholders instead — which the repo
   already does.
2. **The JWT secret should not be a shared symmetric literal.** With six services you end
   up distributing one HS256 secret everywhere. Cleaner: issue with RS256 and distribute
   the public key, so only the auth service can mint tokens.
3. **Logout isn't enforced end-to-end.** Redis holds the session and logout deletes it,
   but nothing consults Redis during request authentication — the gateway and each
   service validate signature and expiry only. So a token held after logout stays valid
   until its `exp`. Fix: check the Redis key in the gateway filter (accepting one Redis
   read per request), or move to short-lived access tokens plus a refresh-token store.
   *Frame as a known, scoped gap with a clear fix — not a surprise.*
4. **Rate limiting is per-IP.** Behind a NAT, a whole office shares one bucket; behind a
   proxy, every request comes from the proxy's IP unless you resolve `X-Forwarded-For`.
   Better key: authenticated user ID when present, IP as fallback.
5. **One Postgres instance, five schemas.** Schema-per-service is the pragmatic middle
   ground — logical isolation without five databases. Strictly it's still the "shared
   database" anti-pattern: the services aren't independently deployable at the data layer.
   I'd split the hot schemas (diagnostic, appointment) onto their own instances first.
6. **No circuit breaker.** If the embedding service or Groq is slow, calls just time out.
   Resilience4j with a timeout + circuit breaker around the embedding client is the
   obvious next step — especially because retrieval is already fail-open, so a breaker
   would be nearly free to add.
7. **No distributed tracing.** No Micrometer Tracing / OpenTelemetry, so correlating a
   slow request across five services means reading five log streams by eye. Would add
   trace IDs propagated through the gateway headers.
8. **Test coverage is thin.** There's essentially just a context-load test. The honest
   framing: the highest-value tests to add first are the pure functions — chunking
   boundaries, the retrieval dedup/cap logic, prompt assembly, and the JSON parser's
   tolerance for markdown fences.
9. **Report-pipeline similarity scores are approximate.** When aggregating across many
   report chunks, each retrieved chunk's score is computed against the *first* query
   embedding rather than its best-matching one. It's fine for ranking the displayed
   context, but it's an approximation and it's noted in a code comment.
10. **IVFFlat was tuned blind.** `lists = 100` is a guess for a knowledge base of
    unknown size, and IVFFlat wants to be built *after* data exists — built on an empty
    table it degrades recall. With today's KB size a sequential scan may even be faster.
    HNSW would be the safer default, or tune `lists ≈ sqrt(rows)` and rebuild after load.

---

## 10. Questions to ask them

Have three ready — it signals you're evaluating them too:

- "How do you handle secrets and rotation across services?"
- "Do you run your own vector store or is retrieval a managed service?"
- "What does the on-call story look like — how do you find which service broke?"
