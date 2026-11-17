# Contracts

Language-neutral contracts shared by the Python collector and the Java modules. Each program validates against these files and converts to its own internal model; no ORM entity or internal DTO is shared across languages.

## Layout (`v1/`)

| File | Purpose |
|---|---|
| `collection.schema.json` | Messages between collector, scheduler, and pipeline (requests, status, outcomes, manifests, notices, errors) as `$defs` |
| `publication-manifest.schema.json` | Pack publication manifest read by the local MCP server |
| `examples/*.accepted.json`, `examples/*.rejected.json` | Messages that must pass or must be rejected |
| `examples/index.json` | Maps every example to its schema, definition, and expected result |
| `examples/root100/`, `examples/empty-optional/` | Multi-file fixtures for collection reconciliation tests |
| `canonical-golden-vectors.json` | Canonical JSON bytes and hashes every implementation must reproduce |
| `id-golden-vectors.json` | Length-prefixed ID encoding and hashes every implementation must reproduce |

## Rules

- Schemas use JSON Schema Draft 2020-12. Timestamps are UTC with a trailing `Z`.
- A new example must be added to `examples/index.json`; the check fails on unlisted files.
- Changing the meaning of a field needs a new contract version, not an edit in place.
- Every implementation of canonical JSON or ID hashing is tested against the golden vectors.

## Check

```bash
uv run --no-project --with jsonschema==4.26.0 python tools/check_contracts.py
```

CI runs the same command in the `Contract checks` job.
