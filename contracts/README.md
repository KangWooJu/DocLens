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
| `url-canonical-vectors.json` | Link resolution per vector: `canonical` is the collector URL rule (null = not a target in the scope), `javaTarget` is the current pipeline link target; differing pairs are known differences |

## Rules

- Schemas use JSON Schema Draft 2020-12. Timestamps are UTC with a trailing `Z`.
- A new example must be added to `examples/index.json`; the check fails on unlisted files.
- Changing the meaning of a field needs a new contract version, not an edit in place.
- Every implementation of canonical JSON or ID hashing is tested against the golden vectors.
- `sourceType` `git-blob` is the git blob bytes of a raw URL pinned to a commit SHA, recorded with `contentType` `text/plain` and the commit in `sourceCommit`.

## ID formulas

`H(fields...)` is `sha256:` plus the lowercase hex SHA-256 of the fields, each NFC-normalized, UTF-8 encoded and prefixed with its 4-byte big-endian byte length. Numbers are decimal strings.

| ID | Fields |
|---|---|
| documentId (Python) | `document-v1`, project, exactVersion, sourceType, documentType, canonicalUrl |
| includeExcludePolicyHash (Python) | `include-exclude-v1`, sorted allowedPathPrefixes, `exclude`, sorted excludePathPrefixes |
| scopeHash (Python) | `scope-v1`, input count, then for each input sorted by (sourceId, project, exactVersion, sourceType, documentType): sourceId, project, exactVersion, sourceType, documentType, `true` or `false` for required, applicability, discoveryRuleId, canonicalRoot, includeExcludePolicyHash |
| inputScopeKey (Python) | the scopeHash fields over the inputs of one (project, exactVersion, applicability) projection |
| runInputSnapshotHash (Java) | `input-snapshot-v1`, discovery contentHash, envelope count, then per batch sorted by batchId: batchId, envelope contentHash; document count, then per outcome sorted by documentId: documentId, raw contentHash or `none`, versionEvidence canonical JSON hash |
| headingLocator | `heading-locator-v1`, `html-id`, NFC element id, occurrence; or `heading-locator-v1`, `ordinal`, path length, sibling ordinals |
| tabGroupLocator | `tabgroup-locator-v1`, `html-id`, NFC element id, occurrence; or `tabgroup-locator-v1`, `ordinal`, `1`, group ordinal; `none` without tabs |
| variantKey | the label itself for mapped `java`, `kotlin`, `groovy`, `maven`, `gradle`, `properties`, `yaml` at occurrence 0; otherwise the 64 hex digits of `variant-label-v1`, label, occurrence |
| sectionId | `section-v1`, documentId, headingLocator |
| blockId | `block-v1`, sectionId, block ordinal in the section |
| chunk tab identity, 2+ groups | `tabgroup-set-v1`, count, locators in DOM order; the 64 hex digits of `variant-set-v1`, count, default variant keys. Internal only, never a tool variant value |
| chunkId (Java) | `chunk-v1`, documentId, headingLocator, tabGroupLocator, variantKey, segmentIndex, content |
| conceptId (Java) | `concept-v1`, project, artifact, exactVersion, targetKind, qualifiedName, signature |

## processingConfigHash

`processingConfigHash` = `sha256:` + hex SHA-256 of the canonical JSON (same rules as `requestHash`) of a `processing-config-v1` preimage. The pipeline builds it from the values that change its output:

- parser: policy version, jsoup version, code size limit, every registered parser profile except `fixtureHashes` (approval evidence)
- chunker: policy version (includes the JDK major), target/max bytes, max blocks, overlap, header repeat limit, separator
- identifiers: code patterns, maximum length
- artifacts: structured document schema version
- pack: `sourceAttribution`, `createdBy`

Enums are written by name, sets as code-point-sorted arrays. Paths, timestamps and the hash itself are excluded. Behaviour coded in Java (selectors, renderers, ID formulas) is identified by the policy versions: `pipeline/src/test/resources/golden/processing-outputs.jsonl` pins the fixture outputs per hash, and CI rejects a pull request that changes or removes a line. A request whose hash differs from the applied configuration is rejected before any Build row is written.

## Check

```bash
uv run --no-project --with jsonschema==4.26.0 python tools/check_contracts.py
```

CI runs the same command in the `Contract checks` job.
