# MediRAG — Counter-Question Bank

> Companion to [MediRAG-interview-script.md](MediRAG-interview-script.md).
>
> Every answer here is grounded in the actual code. Each question has:
> **Answer** (what to say), and where useful **Follow-up** (the trap they set next)
> and **Concede** (the honest limitation to volunteer).
>
> Read the questions, not the answers. If you can say the answer in your own words,
> you're ready. Interviewers can tell the difference between recall and understanding,
> and they will push until they find the edge of yours.

---

## A. Architecture & microservices

### A1. Why microservices and not a monolith?

The domain genuinely splits into independently scaling concerns. Two examples carry the
argument: the diagnostic service is CPU/IO-heavy (file upload, PDF parsing, Vision calls,
vector search) and needs a very different resource profile from auth, which is
transactional and light. And the mental-health chat is a long-latency, low-throughput
path — I don't want it sharing a thread pool with login. Also, failure isolation: if the
embedding service dies, everything else keeps serving.

**Follow-up:** *"Isn't that overkill for a project this size?"*
Yes — for the scale it runs at today, a modular monolith would be simpler and I'd say so
openly. I built it distributed to exercise the concerns: gateway-level auth, independent
deployment, per-service caching. The honest cost is operational: six Docker images, six
configs, and debugging requires correlating logs across services, which is why tracing is
on my fix list.

### A2. How do services communicate?

They don't, synchronously — except for two intentional cases. No service calls another
service for domain data; each owns its schema. The two cross-service couplings are:

1. All services call the **embedding service** over HTTP (diagnostic only, actually —
   auth/appointment/health/mental don't).
2. Diagnostic calls **Groq** and **MinIO**.

I deliberately avoided service-to-service calls for domain data because that's what turns
microservices into a distributed monolith. The one place I'd reconsider is the patient ID:
it's a claim in the JWT rather than a lookup, which is exactly the right call — no
synchronous dependency on auth to resolve identity.

### A3. What happens if the diagnostic service is down?

The gateway returns a 5xx / connection error to the client for diagnostic routes; the
other four services are unaffected and keep serving normally. That's the point of
splitting them.

**Concede:** There's no circuit breaker, so callers just wait for a timeout rather than
failing fast. Resilience4j around the diagnostic route with a timeout and a fallback is
the fix. Since retrieval is already fail-open by design, adding a breaker would barely
change behaviour — which is why it's a cheap win.

### A4. How do you handle a request that spans multiple services?

Today, by correlating logs by hand. There's no Micrometer Tracing or OpenTelemetry, no
trace ID propagated through the gateway. This is the most obvious operational gap:
the gateway already injects headers, so propagating a `X-Trace-Id` (or adopting W3C
`traceparent`) plus adding the tracing dependency would light up distributed traces with
very little code. Actuator is already present per service for health checks.

### A5. Stateful vs stateless services?

All six are stateless in terms of application sessions — `SessionCreationPolicy.STATELESS`
everywhere, CSRF disabled because no cookies are used. All state is externalised: Postgres
for data, Redis for cache/sessions/rate-limit buckets, MinIO for files. That's what makes
them horizontally scalable — you could run three replicas of any service behind the
gateway with no sticky sessions.

---

## B. Security & JWT

### B1. JWT vs session tokens — why JWT?

JWT lets any service verify a request without a shared session lookup or a call back to
auth, which matters in a multi-service setup. The cost is revocation, which is the next
question.

### B2. JWTs can't be revoked. How do you handle logout?

This is the most likely question, and the honest answer matters.

**What I built:** Auth writes the issued token to Redis at `jwt:<email>` with a 24-hour
TTL. Logout deletes that key. The intent was to have a server-side session record so the
token could be invalidated server-side rather than waiting for `exp`.

**Concede (say this — don't hide it):** I only built half of it. Deletion is implemented,
but nothing consults Redis during request authentication. The gateway and each service's
`JwtAuthFilter` validate signature and expiry only. So a token captured before logout
remains valid until it expires. The fix is a single Redis existence check in the gateway
filter — one `GET` per request, with the cost being that the gateway becomes a Redis
dependency on the hot path.

**Stronger alternative to mention:** short-lived access tokens (5–15 min) plus a
refresh-token record in Redis. Then revocation latency is bounded by the access token TTL
without a per-request lookup, and you get sliding sessions for free.

### B3. Where is the JWT secret, and how do you manage it?

**Honest:** today the JWT secret is a literal repeated in the tracked
`application.yaml` of all six services, sitting next to the Postgres password and the
MinIO credentials. The appointment service also carries a Gmail app password as the
default for `MAIL_PASSWORD`. That's a known weakness, not a design choice. To be fair to
the design: `.env` is properly gitignored (untracked), the LLM key is env-injected with a
placeholder default rather than a real one, and a `.env.example` is committed — so the
pattern was understood, it just wasn't applied to the YAML.

**What I'd do:** never commit it; inject via environment or a secrets manager (Vault, AWS
Secrets Manager) referenced by the container platform; support key rotation with a
`kid`-based key set so you can roll keys without invalidating every live session. And
because it's already in git history, that secret should be treated as compromised and
rotated — same for the mail app password. (I've flagged this rather than reprinting the
values.)

### B4. Why HS256 and not RS256?

Pragmatically, HS256 is one shared secret and one line of config — fine for a single
trust domain. With five-plus services all holding the same secret, though, RS256 is
better: only the auth service holds the private key to sign; everyone else gets the public
key to verify. Compromising any *other* service then doesn't let an attacker mint tokens.
That's a real improvement I'd make.

### B5. How does a downstream service know who the user is?

The gateway validates the token and injects `X-User-Id`, `X-User-Email`, `X-User-Role`.
Services read those headers, so JWT parsing exists in exactly one place at the edge.
Services also run their own filter as defence in depth, so a directly-reached service
isn't wide open.

**Follow-up trap:** *"If services trust those headers, what stops me sending
`X-User-Id: 1` directly to port 8085?"*
Nothing — and that's a real hole in this build. Ports 8081–8085 are published in
`docker-compose.yml` for local convenience, and a published service would trust a forged
header. In production: don't publish the ports (only the gateway is exposed), and require
an internal shared secret or mTLS on service-to-service traffic so a header is only
trusted if it arrived through the gateway. **Volunteering this is much stronger than
being caught.**

### B6. Why BCrypt? What about the work factor?

BCrypt is deliberately slow and salts every hash, so a stolen DB doesn't hand over
passwords and precomputed rainbow tables are useless; `PasswordEncoder.matches()` handles
the salt transparently. Spring's default strength is 10, which is a reasonable
2020s-era tradeoff. It's a config knob: raise it and login gets slower but brute force
gets exponentially more expensive. I'd tune it based on an acceptable login latency
budget, and add a per-account login rate limit — the gateway's IP limit helps, but it
doesn't stop a distributed credential-stuffing attack against one account.

### B7. How do you stop someone enumerating users?

`register` returns "Email already registered" and login returns a generic
"Invalid email or password". So registration *does* leak whether an email exists — a
known tradeoff for UX. The stricter approach is to always accept registration and send a
verification email, so the response is identical either way.

### B8. Is CSRF a concern?

No, and I disabled it deliberately. CSRF attacks rely on the browser automatically
attaching credentials — cookies. This API authenticates with an `Authorization: Bearer`
header that the client must set explicitly, and the browser won't auto-attach it. No
cookies, no CSRF surface. If I ever moved to cookie-based auth, CSRF protection would have
to come back, along with `SameSite` and a token.

### B9. Where does rate limiting live, and how does it work?

At the gateway, using Spring Cloud Gateway's `RequestRateLimiter` with the **Redis
token-bucket** implementation. `replenishRate: 10` refills 10 tokens per second,
`burstCapacity: 20` allows a burst, `requestedTokens: 1` costs one token per request.
Diagnostics gets 5/10 because uploads are expensive. Key is client IP.

**Follow-up:** *"What's wrong with keying on IP?"*
Several things. Behind a NAT the whole office shares a bucket and one user starves the
rest. Behind a proxy every request looks like the proxy unless you resolve
`X-Forwarded-For` correctly — and that header is spoofable unless you trust only your own
edge. And an attacker with a botnet just rotates IPs. Better: key on the authenticated
user ID when present and fall back to IP for anonymous routes, and layer a per-account
limit on the login endpoint.

### B10. Why token bucket and not a fixed window?

Token bucket allows short bursts while enforcing a long-run average — that matches real
traffic, where a client fires three requests at once then goes quiet. A fixed window
punishes that: it either rejects legitimate bursts or allows a double-rate spike across a
window boundary. Token bucket with `burstCapacity` as a cap is smoother and fairer.

---

## C. Caching & Redis

### C1. Which caching pattern did you use, and why?

Cache-Aside, in three services: appointments (doctor lists), health (meal plans), and
mental health (resources). Cache-Aside because these are read-heavy, write-rare: reads hit
Redis first, a miss queries Postgres, and the result is written back with a TTL as a
backstop. Write-through would add write latency for data that's read far more often than
it changes.

### C2. How do you keep the cache correct?

Targeted invalidation, not just TTL. Booking or cancelling a slot changes a doctor's
availability, so I delete both `doctors:<specialization>` and `doctors:all` inside the
same transaction. Relying on the 10-minute TTL alone would serve stale availability for up
to ten minutes after a slot was taken — which for a booking system is a correctness bug,
not a performance tradeoff.

### C3. What are the two hard problems in caching, and which did you solve?

Invalidation, and stampede. I solved invalidation explicitly. **Stampede** I did not
solve: if a hot key expires, N concurrent requests all miss and all query Postgres
simultaneously. Fixes are single-flight (one request refreshes, the rest wait), a
short-lived lock on the cache key, or probabilistic early refresh so keys jitter rather
than expiring together. Worth naming even though it's unimplemented — showing you know the
failure mode is the point.

### C4. Cache penetration — what about a key that never exists?

If someone hammers a doctor specialisation that doesn't exist, every request misses and
hits Postgres. Standard fixes: cache the *negative* result with a short TTL, or use a
bloom filter to reject known-absent keys before you query. Not implemented here; would add
it if the endpoint were public.

### C5. What do you store in Redis, and does anything collide?

Three distinct purposes share the instance, with different key prefixes so they don't
collide:

| Purpose | Key pattern | Set by |
|---|---|---|
| Sessions | `jwt:<email>` | auth |
| Cache | `doctors:all`, `doctors:<spec>`, `mealplans:<userId>`, resources | appointment, health, mental |
| Rate limiting | managed by the gateway's Redis rate limiter | gateway |

**Concede:** sharing one Redis for sessions, cache and rate-limit buckets means an
eviction storm could theoretically evict sessions. In production I'd separate them —
different logical databases or instances — and apply an eviction policy that protects
session keys (or set no eviction on the session instance). Rate-limit counters are
naturally ephemeral; sessions are not.

### C6. Why JSON strings as cache values instead of Java serialization?

Two reasons. It's debuggable — you can read a Redis value with `redis-cli` and know what
it is. And it forces the cache to hold DTOs rather than Hibernate entities, which avoids
the classic trap of serialising a lazy proxy and hitting `LazyInitializationException` on
deserialization outside the persistence context.

### C7. What TTL did you choose and why?

10 minutes for the doctor list. The reasoning: doctors change rarely, availability changes
on bookings — which invalidation handles immediately — so the TTL only bounds how long a
missed invalidation can hurt. Short enough that a bug is survivable, long enough to absorb
the read traffic.

### C8. What if Redis goes down?

Cache reads throw and the code catches, falling through to Postgres — degraded latency,
not an outage. That's deliberate: Redis is a performance layer, not a source of truth.
The gateway's rate limiter, though, *does* hard-depend on Redis — Spring Cloud Gateway's
Redis rate limiter fails closed by default, so a Redis outage would block all traffic.
That's a real availability risk and the reason to either isolate the Redis used for rate
limiting or configure a fallback.

---

## D. Concurrency & data integrity

### D1. Two patients book the same slot at the same time. What happens?

The slot row is read with a **pessimistic write lock** — `@Lock(PESSIMISTIC_WRITE)` on
`findByIdForUpdate`, which emits `SELECT ... FOR UPDATE`. Inside the transaction, the
second request blocks until the first commits; it then reads `isBooked = true` and is
rejected with "This slot is already booked". The whole booking is `@Transactional`, so the
slot update and appointment insert commit or roll back together.

### D2. Why pessimistic and not optimistic locking?

Pessimistic is the right choice *here* because booking is a short transaction on a hot,
narrowly-contended row, and the cost of a rejected booking is bad UX. Optimistic
(version column + retry on conflict) suits low-contention, longer transactions where you
want to avoid holding DB locks — you'd have the client retry instead. Here the
contention is exactly on one row and the operation is short, so blocking briefly is
cheaper and simpler than building a retry path.

**Follow-up:** *"What's the cost of pessimistic locking?"*
Locks are held for the transaction duration, so a slow transaction holds others up, and
there's deadlock risk if lock ordering is inconsistent. Mitigations: keep the transaction
minimal — which is why the email send is `@Async` *outside* the critical path — and set a
lock timeout so a stuck request fails fast instead of piling up.

### D3. Why is the email send asynchronous?

`@Async` on `EmailService` moves the SMTP call to a separate thread so the HTTP response
doesn't wait on Gmail's SMTP server — which is slow and occasionally flaky. And the catch
block means a mail failure is logged, never surfaced: a failed confirmation email must
never roll back a successful booking.

**Follow-up:** *"What breaks with `@Async`?"*
It needs `@EnableAsync`, and it silently does nothing if the method is called from within
the same bean (self-invocation bypasses the proxy) — I call it from the service, so that's
fine. Also there's no retry: if the mail server is down the email is simply lost. Real fix
is a durable outbox pattern — write the intent to a table in the same transaction, then
have a worker send and mark it sent, giving at-least-once delivery with retries.

### D4. Is the upload idempotent?

No. Two identical uploads create two scan rows and two MinIO objects (the object name
includes a UUID). For medical scans that's arguably correct — re-uploading a scan is a new
analysis. If it weren't, I'd accept a client-supplied idempotency key and return the
existing result on repeat.

### D5. Why is document ingestion transactional but analysis fail-open?

Deliberate asymmetry, driven by risk. **Ingestion** aborts on failure: a partially
embedded reference document would silently poison the knowledge base, and a wrong
knowledge base degrades every subsequent diagnosis. Better to fail loudly and retry.
**Patient analysis** fails open: a missing embedding service should degrade *quality*
(ungrounded analysis) but never *availability* — the patient still gets a report. Same
infrastructure, opposite policy, chosen per path.

---

## E. RAG & AI

### E1. Explain RAG in one sentence.

Retrieval-Augmented Generation: instead of relying on what the model memorised during
training, you retrieve relevant documents at query time and inject them into the prompt,
so the output is grounded in a curated source you control — and you can cite it.

### E2. Why RAG at all instead of fine-tuning?

Fine-tuning is expensive, slow to iterate, and it teaches *style* more than *facts* —
models still hallucinate confidently after fine-tuning, and updating knowledge means
retraining. RAG keeps knowledge in a database I can edit, version, and cite. For medical
reference content, auditable sources matter more than fluent phrasing, and I can inspect
exactly which chunks grounded a report.

### E3. Walk me through the image pipeline. Why two stages?

Stage 1 is a cheap Vision call — `detail: low`, `max_tokens: 150` — whose only output is a
comma-separated list of radiological terms. Those terms get embedded and become the
retrieval query. Stage 2 is the real analysis — `detail: high`, 1500 tokens — with the
retrieved chunks injected into the system prompt.

The reason for two stages: **you cannot similarity-search an image.** Retrieval needs a
text query, and that text has to come from the model. Doing it in a dedicated cheap call
means the expensive call focuses entirely on producing the report, and the cheap call
costs a fraction of a cent. One combined call would either have to guess the query terms
without seeing the image, or do retrieval after the fact — which is impossible, since
retrieval has to happen before the prompt is built.

### E4. Why not just do retrieval on the report text directly?

That's exactly what the report pipeline does — one stage, because the text already exists.
The two-stage shape exists only where the input is an image and a query must be
manufactured from it. Different input modality, different pipeline, same output schema.

### E5. How do you know retrieval is actually helping?

Honest answer: I didn't build an evaluation harness — that's a real gap. What I have is
observability: the retrieval path logs which terms were extracted, how many chunks passed
the threshold, and every chunk's similarity score is available via a structured DTO and
the admin endpoints. So the *plumbing* is measurable.

What a proper evaluation would look like, and what I'd build next:

- **Retrieval quality:** curate a gold set of (image/finding → expected reference chunk)
  pairs and measure recall@k and MRR. Right now `top-k = 5` and `threshold = 0.3` are
  reasonable defaults, not tuned values.
- **Generation quality:** run both pipelines with and without retrieved context and compare
  findings against a radiologist-labelled set. That's the only way to answer "did RAG
  improve the output" with evidence rather than belief.

Naming the missing measurement and describing how you'd build it is a *stronger* answer
than pretending you measured it.

### E6. Why these two numbers — top-K 5 and threshold 0.3?

`top-k = 5` balances context quality against token cost and prompt dilution: more chunks
means more noise competing for the model's attention, and the strongest signal is usually
in the first few. `0.3` is a floor on cosine similarity to drop irrelevant chunks — set it
too high and you retrieve nothing on an unusual presentation; too low and you inject
noise. Both are externalised in `application.yaml` as `rag.top-k` and
`rag.similarity-threshold`, precisely because I expected to tune them against a labelled
set rather than hardcode a guess.

### E7. What's the chunk size and why? Why overlap?

500 characters with 50 characters of overlap, and the split is **sentence-aware** — I use a
lookbehind regex on `[.!?]` followed by whitespace so chunks never cut mid-sentence. A
chunk that starts mid-sentence embeds poorly, because the embedding model sees a
fragment without its subject. The 50-character overlap means a finding that straddles a
boundary appears intact in at least one chunk, so retrieval can still surface it whole.

**Follow-up:** *"Why 50 and not 100?"*
Overlap costs storage and index size — every character of overlap is embedded and stored
twice. 50 is roughly one clause, which is enough to preserve local context without
inflating the index by 10%+. The right value comes from evaluating recall at retrieval
time, which is the harness I'd build.

### E8. Why batch the embeddings?

Ingesting a document produces dozens of chunks. Embedding them one at a time means dozens
of HTTP round-trips, each with connection and serialisation overhead — the model itself is
the same work either way. One `/embed/batch` call capped at 64 texts (encoded with
`batch_size=32` internally) turns that into one round-trip. The cap exists because an
unbounded batch is a memory and latency risk on a synchronous endpoint.

### E9. Why a separate embedding service instead of embedding in Java?

Three reasons. The best sentence-transformer models are Python — DJL or ONNX in Java is
possible but a long detour. Isolating the model in its own process means the JVM's heap
and the model's memory don't fight, and I can scale or restart it independently. And it
keeps the Java services focused on domain logic. The cost is a network hop on every
retrieval, which is why the fail-open behaviour and (future) circuit breaker matter.

### E10. Why `all-MiniLM-L6-v2`?

It's 384-dimensional — small enough that the index and the storage stay cheap, and fast
enough to embed hundreds of chunks at ingestion without a GPU. It's a strong
general-purpose retrieval model for short passages.

**Concede:** it's a general-purpose model, not a biomedical one. A domain-tuned model like
PubMedBERT or a medical sentence-transformer would likely retrieve better on clinical
terminology — that's a real improvement, but it changes the embedding dimension, which
means re-embedding the entire knowledge base and rebuilding the index. Worth doing
deliberately, not casually. That's a concrete example of the operational cost of a model
change.

### E11. Model dimension is 384 — what if you changed the model?

It's a breaking change end-to-end. The `vector(384)` column definition has to change, every
stored chunk must be re-embedded, and the IVFFlat index rebuilt. The Java side already
warns if a returned vector isn't 384 dimensions, so a mismatch is loud rather than silent —
but it's still a migration, so I'd version the embedding model alongside the knowledge base
and re-ingest into a new column or table, then cut over.

### E12. How do you stop prompt injection from an uploaded document?

This is a genuinely important question for this system, and the honest answer is: partially.

**What protects you today:** the report text is placed between explicit delimiters
(`--- MEDICAL REPORT ---`) and the system prompt states the output must be strict JSON
matching a fixed schema. The parser then only reads known keys — `summary`,
`overallConfidence`, `findings[]` with `condition`/`confidence`/`severity`/`location`. So
an injected instruction can't change the response *shape* or make the service execute
anything; the worst case is a misleading report body.

**What I'd add:** treat document content as untrusted data explicitly in the system prompt,
sanitise obvious injection markers, and — most importantly — never let model output drive
an action as if it were a trusted command. The design already helps here: analysis is
advisory, the output is stored data for a human, and nothing downstream acts on it. There
is no tool calling and no write path, which is what makes injection here a
content-integrity problem rather than a remote-code-execution one.

That framing — "it's content integrity, not code execution, because nothing acts on the
output" — is the answer they're looking for.

### E13. The model returns JSON — what if it returns prose or wrapped markdown?

The parser is defensive. It strips ```` ```json ```` fences and bare fences with a regex
before parsing, because models wrap output despite instructions. If parsing still fails,
the exception is caught and it falls back to a "manual review required" report rather than
a 500. Severity strings are parsed leniently, defaulting to `NORMAL` on an unknown value,
and numeric fields are coerced across `Double`/`Number`. Every failure path returns a
valid report object.

**Follow-up:** *"Wouldn't a schema-constrained decoder be better?"*
Yes. Groq/OpenAI-compatible endpoints support JSON mode or structured outputs, and
constrained decoding would make malformed JSON structurally impossible rather than
recovered-from. My parser would still stay as the outer guard, but the primary defence
should be telling the decoder the schema. Worth adding — it would eliminate a whole class
of failure.

### E14. Why Llama 4 Scout on Groq rather than GPT-4o?

Groq's inference is dramatically faster and the API is OpenAI-compatible, so it's a
drop-in — same request shape, same response envelope, just a different `base_url` and
model name. For an interactive upload flow where the user waits on a two-stage Vision
pipeline, latency was the deciding factor. The config keys are still named `openai.*`
because the API is OpenAI-shaped, which is slightly confusing and something I'd rename to
`llm.*` for clarity.

**Concede:** the model choice is a vendor bet. Because the integration is
OpenAI-compatible and the model name is config, switching providers is a config change
plus a prompt re-tune — that's the portability argument for keeping the abstraction here.

### E15. Where does the AI output go — does it act on anything?

Nowhere automated. The report and its findings are persisted and rendered back to the
patient, plus a generated PDF is stored in MinIO. There is no tool calling, no function
execution, and no downstream service acts on the findings. Every fallback path produces a
report that says "consult a radiologist". That's the safety property: model output is
advisory data for a human, never a command.

### E16. Mental-health chat — what's the safety design?

The system prompt sets the persona explicitly and, critically, holds the boundaries: it
states the assistant is not a replacement for professional therapy, and that any
expression of self-harm or suicide must be met with an immediate referral to emergency
services (112, since this is India-focused) or a licensed therapist. Responses are capped
at 500 tokens with temperature 0.8 — higher than the other prompts, deliberately, because
empathy reads better slightly less deterministic. Full conversation history is replayed
on every call so the model has context.

**Concede:** a system prompt is not a hard safety guarantee. A production system needs a
crisis classifier running on the input *before* generation, not only an instruction inside
the prompt, plus escalation to a human. That's the honest gap between a strong prompt and
a safety-critical guarantee.

---

## F. Vector store & pgvector

### F1. Why pgvector instead of a dedicated vector DB?

Because I already had Postgres, and the knowledge base lives right next to the relational
data that references it — one transaction can write a chunk and its metadata, one backup
covers both, and there's no second system to operate. pgvector's cosine operator plus an
approximate index is entirely adequate at this scale. A dedicated store like Pinecone or
Weaviate starts to earn its keep at millions of vectors, or when you need sharding and
specialised filtering that Postgres can't do well.

**Follow-up:** *"When would you migrate?"*
When index build/query time or dataset size outgrows a single Postgres node, or when
vector search starts competing with transactional traffic for the same resources — at
which point the fix may be a read replica for search rather than a new database.

### F2. Which index, and why that one?

IVFFlat with `vector_cosine_ops` and `lists = 100`. IVFFlat partitions vectors into
clusters and searches only the nearest few, trading a little recall for a large speedup
over a full table scan.

**Concede — this is a good one to volunteer:** IVFFlat has to be *built after* data
exists, because the cluster centroids are derived from the data. Built on an empty table —
which is what `init-db.sql` does — the lists are meaningless and recall degrades. And
`lists = 100` was a guess. The rule of thumb is `lists ≈ sqrt(rows)` for IVFFlat, and you'd
rebuild the index after bulk ingestion. HNSW is the safer default for most workloads: it
builds incrementally, generally gives better recall, and doesn't need a rebuild after
load — at the cost of more memory and slower inserts. That's the change I'd make first.

### F3. Why cosine distance and not L2 or inner product?

Because the embeddings are L2-normalised, which makes cosine similarity and inner product
mathematically equivalent — and cosine is scale-invariant, so it measures semantic
direction rather than magnitude. That's the right notion of similarity for sentence
embeddings.

### F4. Cosine *distance* vs cosine *similarity* — are you consistent?

Yes, and this is a classic bug source. pgvector's `<=>` returns **cosine distance**
(0 = identical, 2 = opposite), so the query does `ORDER BY embedding <=> query ASC` to get
the most similar first. Meanwhile the threshold check in Java computes cosine
*similarity* as a dot product of the normalised vectors, where higher is better, and keeps
chunks `>= 0.3`. Two different scales, used in the two different places they belong — never
mixed. Worth saying out loud, because "is your threshold on distance or similarity" is
exactly the follow-up.

### F5. Why compute similarity in Java at all when the DB just did it?

Because the query only needs an *ordering* — it returns the top-K by distance. The score is
then computed in Java so it can be logged, surfaced in the API response, and filtered by
threshold, all from one place. It does cost an extra pass over the vectors, which is
trivial at K=5.

**Concede:** the approximation in the report pipeline. When aggregating across many report
chunks, each chunk's displayed score is computed against the first query embedding rather
than its own best-matching one, so the scores are for ranking the displayed context, not a
faithful per-query score. It's noted in a code comment. The clean fix is to have the SQL
return the distance alongside each row.

### F6. Duplicate results across queries — how do you handle it?

Retrieval over multiple report chunks will surface the same knowledge chunk repeatedly.
I keep a `LinkedHashSet` of chunk IDs while accumulating, which deduplicates and preserves
insertion order, then cap the total at `topK × 2`. Without that, the context window fills
with the same paragraph three times and wastes tokens while crowding out other signal.

### F7. Why is the table created in `init-db.sql` and not by Hibernate?

Because Hibernate's `ddl-auto` can't generate a `vector(384)` column type — it has no
mapping for it when creating schema. So the table and the IVFFlat index are created by the
SQL init script that runs on container start, while entities map onto it with
`@JdbcTypeCode(SqlTypes.VECTOR)` and the `hibernate-vector` dependency. That split is a
real gotcha: `ddl-auto: update` will manage the ordinary columns but the vector column and
its index must be managed by migration. In production this belongs in a real migration
tool — Flyway or Liquibase — rather than an init script, which only runs on a fresh
volume and would silently skip an existing database.

---

## G. Object storage & MinIO

### G1. Why MinIO instead of storing files on disk or in the DB?

Medical images don't belong in a relational DB — you'd bloat the DB, its backups, and its
replication, and you'd get no benefit since you never query the bytes. MinIO gives
S3-compatible object storage with presigned URLs, and because it's S3-compatible, moving
to actual S3 is a config change. Local disk would break horizontal scaling: two service
replicas with local disks don't share files.

### G2. How does a patient view their own scan securely?

The service never proxies image bytes for viewing. `GET /scan/{id}/view` returns a
**presigned URL** valid for 15 minutes. The browser fetches directly from MinIO. So the
service is out of the data path, and access is time-limited and cryptographically signed.
Ownership is enforced before the URL is minted: the scan is looked up by
`findByIdAndPatientId`, so you can't request a URL for someone else's scan.

### G3. Presigned URLs have a notorious problem in Docker. How did you solve it?

Signature mismatch, and it's worth explaining because it's the kind of thing that only
shows up when you actually run it. The signature covers the `Host` header. The service
talks to MinIO over the internal Docker network as `minio:9000`, so a URL signed with that
host is useless in a browser — the browser resolves `localhost:9000`, sends `Host:
localhost:9000`, and the signature doesn't match.

The fix is **two clients**: an internal client pointed at `http://minio:9000` used for
uploading and reading objects, and a separate presigned client pointed at
`http://localhost:9000` used to *generate* URLs. Signing with the browser-reachable host
makes the signature valid where it's actually used. I also pin the region to `us-east-1`
on the presigned client so the SDK doesn't make a network call to discover it.

**Follow-up:** *"What breaks when you deploy this for real?"*
`localhost:9000` is a dev assumption — in production the presigned client points at the
real public MinIO/S3 endpoint and everything else stays the same, which is exactly why the
endpoint is a config property rather than a hardcoded string.

### G4. How do you manage buckets?

Buckets are ensured at startup with retry and linear backoff (five attempts, 3s → 15s),
because MinIO may not be ready when the service starts even with a compose dependency —
`depends_on` waits for the container, not for the service inside it to be healthy. Two
buckets: scans and generated report PDFs, separated so you can apply different retention
policies.

### G5. Why does analysis read the image back as base64?

The Vision API takes image bytes inline — a data URL, `data:<mime>;base64,<bytes>` — so it
can't dereference a MinIO object. Base64 inflates by roughly a third, which is why the
upload cap is 20MB and `max-request-size` is 25MB. For very large images the better pattern
is a presigned GET URL passed to the API instead of inlining bytes, which avoids the
base64 inflation and the memory of holding the whole file.

### G6. What's the 20MB cap protecting?

Two things: the model's request size limit, and the service's own memory — a 20MB file
becomes ~27MB base64 in a string, plus the parsed PDF or decoded image alongside it. It's
also a simple denial-of-service guard: without a cap, a client could exhaust heap by
uploading something huge. Content type is validated against an explicit allowlist
(JPEG/PNG/WebP/PDF/DOCX/TXT), not inferred from the extension, so a renamed executable
isn't accepted.

---

## H. Resilience, failure & operations

### H1. A dependency is slow. What happens?

Today: the request thread waits for the HTTP client timeout and then fails. There's no
circuit breaker, so a slow embedding service slows every retrieval rather than being
short-circuited. Retrieval already tolerates it — the embedding client returns `null` on a
`RestClientException` and retrieval proceeds with empty context, so the patient still gets
an ungrounded report.

The improvement is Resilience4j: a timeout plus a circuit breaker around the embedding
client and the LLM call, so repeated failures open the circuit and fail fast instead of
consuming threads.

### H2. What about retries?

There are no retries on the LLM or embedding calls, deliberately for LLM calls — the
operation isn't free and a retry storm against a rate-limited provider makes things worse.
The right pattern is retry with exponential backoff and jitter, only on idempotent,
transient failures (timeouts, 429, 5xx), with a bounded attempt count, hopefully wrapped
inside a circuit breaker so a downed dependency isn't hammered. The one place retries
*are* implemented is the MinIO bucket check at startup, with linear backoff.

### H3. Is analysis synchronous or asynchronous, and why?

Asynchronous. The upload endpoint stores the file, persists a scan row as `UPLOADED`,
returns `202 Accepted` immediately, and triggers analysis on a separate thread. Analysis
is a two-stage Vision call plus retrieval plus PDF generation — seconds to tens of
seconds. Making the client block on that would risk gateway and proxy timeouts and give
terrible UX. The client instead polls the scan status, which moves
`UPLOADED → ANALYSING → COMPLETED`, or `FAILED` on error.

**Follow-up:** *"What are the failure modes of `@Async`?"*
It's in-process, so work is lost if the service restarts mid-analysis, and there's no
queue depth control — a burst of uploads means a burst of threads. The scan row survives as
`ANALYSING`, so it's recoverable in principle but not automatically. The production
version is a real queue (RabbitMQ, Kafka, SQS) with a dead-letter queue and a worker,
which gives durability, retry and backpressure. The state machine on the scan row is
already the right foundation for that — the status field is effectively a job state.

### H4. How do you know the system is healthy?

Each service exposes Actuator health, and the gateway publishes its own health and gateway
endpoints. Docker healthchecks cover Postgres (`pg_isready`) and the embedding service
(an HTTP check on `/health` that verifies the model actually loaded, returning
`MODEL_NOT_LOADED` if not).

**Concede:** there are no metrics, no alerting and no SLOs. Actuator *exposes* what you'd
scrape via Micrometer to Prometheus — latency percentiles on the retrieval path, LLM call
duration, retrieval-hit rate, queue depth for analysis — and that's the next step. Right
now health is binary and reactive.

### H5. How would you debug a report that came out wrong?

The pipeline is instrumented for exactly this. Logs record the extracted radiological
terms, how many chunks passed the threshold, the context length injected, and the raw model
response before parsing. The structured retrieval DTO carries each chunk's source title,
source type and similarity score, and the extracted report text is persisted on the report
row (`report_text`) for audit. So you can reconstruct: what terms were derived, what
knowledge was retrieved, what the model was actually given, and what it returned. That
`report_text` column exists specifically so a wrong output is diagnosable after the fact
rather than being a black box.

### H6. What's the biggest operational risk in this design?

The secrets in git, and the fact that the whole system depends on OpenAI-compatible
inference and Redis being available. Both are single points of failure with no fallback:
Redis failing closed on the rate limiter blocks all traffic, and the LLM being down means
analysis degrades to fallback reports (acceptable) but ingestion fails outright (also
acceptable, because it fails loudly). After that, the thin test coverage — the highest-risk
logic, chunking and retrieval aggregation, is exactly the logic with no tests.

---

## I. "Why didn't you use X?" — the honest pattern

Interviews often probe technology choices this way. The reliable structure is:
**what it would have bought you → why you didn't → when you would.**

| They ask | What it buys | Why not here | When you would |
|---|---|---|---|
| **Kafka / RabbitMQ** | Durable, retryable async work with backpressure | `@Async` was enough for a project with no background load; adding a broker means another container and ops surface for work that completes in seconds | When analysis volume grows, or when you need durability across restarts and a dead-letter queue |
| **Docker Swarm / Kubernetes** | Orchestration, self-healing, rolling deploys, secrets management | Compose is one file and one command; K8s would be a lot of YAML for a single-host system | When you need multiple replicas, zero-downtime deploys, or real secrets management |
| **Elasticsearch** | Full-text search, relevance ranking, faceting | The retrieval problem is semantic, not keyword — vector similarity is the right tool, and Postgres already holds the data | If you needed hybrid search (BM25 + vectors) or heavy faceted filtering |
| **OpenAI GPT-4o** | Strongest general vision reasoning | Latency and cost for an interactive flow; Groq is far faster and OpenAI-compatible | If accuracy on a labelled set proved materially better and justified the latency |
| **Spring Cloud Config / Eureka** | Centralised config, service discovery | Compose DNS already resolves service names on the bridge network; a discovery server for six fixed services is overhead | When instances are dynamic and scaling up and down, so addresses aren't stable |
| **gRPC between services** | Faster, typed contracts | There's almost no service-to-service traffic by design; HTTP/JSON is simpler to debug | When inter-service chatter becomes a latency contributor |
| **A dedicated vector DB** | Scale, specialised filtering, sharding | The KB is small; pgvector keeps one system and one transaction | Millions of vectors, or vector search starving transactional traffic |
| **Fine-tuning instead of RAG** | Style and format adherence | Knowledge changes and must be citable and auditable; fine-tuning doesn't fix hallucination | If you needed a very specific output style consistently and knowledge were static |

---

## J. Rapid fire — one-line answers

| Question | Answer |
|---|---|
| How many services? | Six Spring Boot plus a Python embedding service. |
| Gateway port? | 8080. Services 8081–8085, embedding 8086. |
| Gateway filter order? | `-1` — before routing. |
| Rate limit? | Token bucket, Redis-backed, 10/s burst 20; diagnostics 5/s burst 10; keyed by IP. |
| JWT algorithm? | HS256, jjwt 0.12.5, subject = email, 24h expiry. |
| How is identity passed downstream? | `X-User-Id`, `X-User-Email`, `X-User-Role` headers injected by the gateway. |
| Where are sessions stored? | Redis at `jwt:<email>`, 24h TTL. |
| Password hashing? | BCrypt. |
| Caching pattern? | Cache-Aside, in three services, with targeted invalidation. |
| How do you prevent double-booking? | Pessimistic write lock on the slot row (`SELECT ... FOR UPDATE`). |
| Embedding model? | `all-MiniLM-L6-v2`, 384 dimensions, L2-normalised. |
| Vector search? | pgvector cosine, IVFFlat index, `<=>` operator, top-K 5, threshold 0.3. |
| Chunking? | 500 chars, 50 overlap, sentence-aware. |
| Image pipeline? | Two-stage Vision: cheap term extraction → retrieval → grounded analysis. |
| Report pipeline? | PDFBox/POI extraction → chunk → batch embed → retrieve → one LLM call. |
| Which LLM? | Llama 4 Scout on Groq, via an OpenAI-compatible endpoint. |
| Why two MinIO clients? | Signature covers Host — sign with the browser-reachable host, upload via the internal one. |
| Presigned URL TTL? | 15 minutes. |
| What happens if the embedding service dies? | Retrieval returns empty context; analysis proceeds ungrounded. Fail-open. |
| What happens if ingestion fails? | It aborts and rolls back. Fail-closed — a partial KB would poison every future diagnosis. |
| Biggest known gap? | Logout revocation isn't enforced end-to-end, and secrets are committed in git. |
