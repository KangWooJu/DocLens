<p align="center">
  <img src="docs/assets/doclens-banner.png" alt="DocLens" width="760">
</p>

<p align="center">
  <em>Official docs, focused for AI agents.</em>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/status-early_development-A6E15E?style=flat-square&labelColor=0E1512" alt="Status: early development">
  <img src="https://img.shields.io/badge/java-21-F3E7D3?style=flat-square&labelColor=0E1512" alt="Java 21">
  <img src="https://img.shields.io/badge/spring_boot-4.1-F3E7D3?style=flat-square&labelColor=0E1512" alt="Spring Boot 4.1">
  <img src="https://img.shields.io/badge/python-3.13-F3E7D3?style=flat-square&labelColor=0E1512" alt="Python 3.13">
  <img src="https://img.shields.io/badge/license-MIT-F3E7D3?style=flat-square&labelColor=0E1512" alt="MIT license">
</p>

<p align="center">
  <a href="#why">Why</a> ·
  <a href="#how-it-works">How it works</a> ·
  <a href="#status">Status</a> ·
  <a href="#repository-layout">Layout</a> ·
  <a href="#build-from-source">Build</a> ·
  <a href="#license">License</a>
</p>

<p align="center">
  <sub>English · <a href="README.ko.md">한국어</a></sub>
</p>

---

Your agent asks the web about Spring Security 7. The web answers with a 2019 blog post about `WebSecurityConfigurerAdapter`. Your agent believes it.

DocLens hands your agent the official Spring docs for the exact version your project uses, already collected, cleaned and indexed into a local file. Every result carries its source: URL, heading path, version. The agent reads the right page of the right version, and says where it read it.

> [!NOTE]
> DocLens is in early development. The Pack format and its validating reader exist; the MCP server, the Agent Skill and the collection pipeline are being built. Anything marked *planned* does not work yet.

## Why

Ask a coding agent about a Spring API and it usually does one of three things: answers from memory trained on several major versions at once, runs web searches until something looks plausible, or pulls whole doc pages into its context. All three cost tokens and time, and none of them check that the version matches your `build.gradle`.

DocLens moves the repeatable parts out of the model:

| Code does | The agent still does |
|---|---|
| Collect, clean and index official docs ahead of time | Understand what the question means |
| Match the version your project uses to an installed Pack | Judge whether a source actually supports the answer |
| Check that every cited source exists | Connect several sources into an explanation |
| Return only the relevant sections, within a context budget | Say plainly what the docs do not confirm |

A cited source that exists is not the same as a source that supports the claim. DocLens checks the first; the agent is still responsible for the second.

## How it works

```
 build side (maintainer)                         your machine
┌────────────────────────────────────┐          ┌──────────────────────────────────────┐
│ scheduler   when to collect        │          │ coding agent (Claude Code, Codex ..) │
│ collector   fetch official docs    │          │   │  Agent Skill: how to research    │
│ pipeline    parse · chunk · index  │          │   ▼                                  │
│             export one Pack        │──Pack──▶ │ mcp-server   read-only, local, STDIO │
└────────────────────────────────────┘          │   ▼                                  │
                                                │ spring-security@7.0.2.pack (SQLite)  │
                                                └──────────────────────────────────────┘
```

- **Knowledge Pack.** One SQLite file per documentation version: documents, sections, chunks, code variants, identifiers, links and a full-text index. A logical content hash identifies the same content across builds. The reader validates a Pack completely before anything searches it.
- **Local MCP server** *(planned)*. Runs on your machine over STDIO and answers from installed Packs only. No remote search service: searches never leave your machine. Read-only: it never edits your project or runs shell commands.
- **Agent Skill** *(planned)*. A plain prompt file that tells your agent which version to check, how to search within a budget, and how to cite. No extra LLM API: your own agent does the writing.

Planned search result shape (illustrative):

```
spring-security@7.0.2 · Servlet Applications › Configuration › Java Configuration
https://docs.spring.io/spring-security/reference/...
  @Bean
  SecurityFilterChain filterChain(HttpSecurity http) throws Exception { ... }
```

## Status

| Step | What | State |
|---|---|---|
| A | Cross-language contracts, Pack v1 writer and validating reader | Done |
| B | `mcp-server`: search over installed Packs | Next |
| C | `pipeline`: parse and chunk official docs | Planned |
| D | `pipeline`: store and export Packs | Planned |
| E | `collector`: fetch official docs | Planned |
| F | `scheduler-server` and a local end-to-end run | Planned |
| G | Deployment on AWS | Planned |

The first release, `v0.1.0`, is planned for when the MCP server can search a Pack.

## Repository layout

| Path | What |
|---|---|
| `knowledge-core/` | Pack format: canonical JSON, Pack writer and reader, shared by `pipeline` and `mcp-server` |
| `mcp-server/` | Local STDIO MCP server *(planned)* |
| `pipeline/` | Parse, chunk, store and export Packs *(planned)* |
| `scheduler-server/` | Schedules collection and processing *(planned)* |
| `collector/` | Python collector for official docs *(planned)* |
| `contracts/` | JSON Schemas, examples and golden vectors shared across languages |
| `tools/` | Contract checker |
| `maintenance-cli/` | Operator tool *(planned)* |

## Build from source

Requires JDK 21 and [uv](https://docs.astral.sh/uv/).

```bash
./gradlew build
```

```bash
cd collector && uv sync && uv run pytest
```

```bash
uv run --no-project --python 3.13 --with jsonschema==4.26.0 python tools/check_contracts.py
```

## Run the local stack

Requires Docker with Compose. Copy `.env.example` to `.env` and fill in the collector and S3 values; everything below runs from the repository root.

1. Tokens: run this once for `scheduler` and once for `operator`. Put the first line of the scheduler run in `.env` as `DOCLENS_SCHEDULER_TOKEN`, keep the operator one in your shell as `DOCLENS_OPERATOR_TOKEN`, and set `DOCLENS_CALLER_TOKENS=scheduler:<hash>,operator:<hash>` from the second lines.

   ```bash
   python3 -c "import secrets,hashlib;t=secrets.token_urlsafe(32);print(t);print(hashlib.sha256(t.encode()).hexdigest())"
   ```

2. Request template, after approving the sources with `registry import`, `probe` and `approve`:

   ```bash
   mkdir -p .local && (cd collector && uv run python -m collector request <sourceId>...) > .local/collection-request.json
   ```

3. Start, then copy the `sha256:…` from `docker compose logs pipeline | grep 'processing config'` into `.env` as `DOCLENS_SCHEDULER_PROCESSING_CONFIG_HASH` and restart the scheduler (`docker compose --profile app up -d scheduler`):

   ```bash
   ./gradlew :pipeline:bootJar :scheduler-server:bootJar
   docker compose --profile app --profile worker up -d --wait
   ```

4. Collect now (prints `{"result":"CREATED|DUPLICATE_SLOT|ACTIVE_RUN_EXISTS","runId":…}`):

   ```bash
   docker compose run --rm scheduler --spring.profiles.active=command collect-now <label>
   ```

The pipeline and scheduler containers run the jars in `pipeline/build/libs` and `scheduler-server/build/libs`. Do not rebuild them while the stack runs; stop it first with `docker compose --profile app --profile worker down`.

## Phase 1 measurement

One real run on 2026-11-27 (KST): Spring Boot `v4.1.2` reference docs at commit `634e6fd`, fetched from GitHub by the collector and built by the pipeline jar. The values come from that single run.

| Item | Value |
|---|---|
| Requests / collection time | 956 (951 documents, 4 discovery, 1 robots) / 45 min 47 s |
| Files fetched / bytes | 951 / 1,451,750 B |
| Ready run size | 350,325 B |
| `INVALID_BODY` | 0 |
| Documents (DONE / HELD) | 89 (89 / 0) |
| Sections | 714 |
| Chunks | 710 |
| Pack size | 8,216,576 B (about 7.84 MiB) |
| Build time | 13.3 s, JVM included (10.3 s from Build start to export) |
| `pack_meta` matches the Build row | Yes |
| Processing config hash | `sha256:c1acd88a783105b173d11f0ee4d0c9e384f7e32d82dbedf9bcdd9654b1dcb6e6` |
| Publication / latest `buildId` | 1 / `7f889261-d773-4d86-9df6-76cf42b79010` |

The Build stays well under the 120 s processing read timeout, so the timeout is unchanged.

## License

[MIT](LICENSE)

DocLens is not affiliated with or endorsed by the Spring project or Broadcom. Spring documentation remains under its own license.
