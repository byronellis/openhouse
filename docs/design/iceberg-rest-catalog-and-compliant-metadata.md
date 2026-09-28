# Iceberg REST Catalog and compliant metadata

**Status:** Design proposal
**Target:** Iceberg format v2, with fully qualified file URIs
**Scope:** A standards-compatible Iceberg metadata representation and an Iceberg REST
Catalog API alongside OpenHouse's existing catalog API

## Summary

OpenHouse can introduce a compliant metadata representation without moving data files or
breaking existing catalog clients. Keep one logical table, identified authoritatively by the
catalog, and maintain two independently addressable metadata graphs during transition:

- The **legacy graph** retains today's metadata-file placement and path conventions. The
  existing OpenHouse API continues to expose this graph to existing clients.
- The **compliant graph** uses qualified URIs in Iceberg metadata and a disjoint metadata-file
  namespace. A new Iceberg REST Catalog API exposes this graph to standard Iceberg clients.

Both APIs use one catalog commit implementation. A logical metadata commit writes the required
representations and atomically publishes the corresponding catalog pointers. A table upgrade
operation builds the compliant graph for existing snapshots without rewriting data files. Once
legacy clients have been retired, OpenHouse can stop writing and eventually remove the legacy
graph.

This proposal targets Iceberg v2. Relative paths are not part of the adopted Iceberg v1-v3
requirements; v4 path behavior is intentionally out of scope until that format version is
adopted and supported by the OpenHouse Iceberg dependencies.

## Context and current behavior

- The internal catalog stores a metadata-file pointer for each table. Iceberg table metadata
  records the table's base location and points to snapshots; snapshots point to Avro manifest
  lists, which point to Avro manifests.
- The table metadata JSON is currently written at the table root. Manifest lists and manifests
  are stored under `metadata/`.
- S3 locations are qualified, while Local and HDFS storage currently allocate scheme-less paths.
  These paths are persisted and may be copied into snapshot metadata.
- The Java client uses OpenHouse-specific APIs for table discovery and snapshot commits.
- OpenHouse's catalog namespace is one level deep: a namespace maps to a database and a table
  identifier adds the table name.
- The catalog-only rename and HTS property-removal work on the parent branch makes the catalog
  the natural authority for table identity. Rename should not require a rewrite of Iceberg
  metadata or table properties.

Iceberg permits catalogs to choose their atomic metadata-commit mechanism; storing the current
pointer in the catalog is compatible with that model. REST Catalog compatibility is a separate
concern from the validity of the table metadata itself.

## Goals

1. Write and serve Iceberg v2 metadata whose location fields are fully qualified URIs.
2. Avoid collisions or in-place mutation between legacy and compliant metadata files.
3. Preserve existing OpenHouse API behavior while adding an Iceberg REST Catalog interface.
4. Make both API paths use the same logical commit, catalog concurrency checks, and table
   identity.
5. Upgrade existing tables by rewriting metadata files, not data files.
6. Support large upgrades through the existing Spark job infrastructure.
7. Retire legacy writes and files only after clients and all retained snapshots no longer depend
   on them.

## Non-goals

- Implementing draft Iceberg v4 relative-path behavior.
- Rewriting, copying, or compacting data files as part of metadata compliance.
- Using a second database, namespace, table identifier, or table property to represent compliant
  mode.
- Replacing OpenHouse authorization, policy, or operational APIs with the REST Catalog protocol.
- Claiming full REST Catalog conformance before the required protocol endpoints and semantics are
  implemented and tested.

## Proposed metadata layout

The precise paths are an implementation decision; this example illustrates the isolation rule.
Existing legacy paths remain unchanged.

```text
<table-location>/
  00001-<uuid>.metadata.json                 # existing legacy table metadata
  metadata/
    snap-<legacy-name>.avro                  # existing legacy manifest lists/manifests
    <version>-rest-<uuid>.metadata.json       # compliant table metadata
    rest-snap-<snapshot-id>-<uuid>.avro       # compliant manifest lists
    rest-manifest-<uuid>.avro                # compliant manifests
```

The table's Iceberg `location` remains the physical table base location. Every URI stored in the
compliant metadata graph must resolve to the intended object and include a URI scheme; HDFS
locations must also have the authority required to identify the filesystem. Local storage needs a
qualified `file:` URI. The resolver that produces these paths should be centralized rather than
reconstructed independently in table operations, REST handlers, and migration code.

The `metadata/` directory already exists for legacy Avro files, so its existence is not a mode
marker. Keep both graphs there, using a reserved filename prefix for compliant files; the
catalog/API chooses the graph by its stored pointer. Preserve any Iceberg-required numeric
metadata-version prefix and verify the resulting metadata filenames with the supported Iceberg
runtime. The reserved prefix prevents compliant writes from overwriting legacy artifacts, but is
not itself a signal of compliance.

## Catalog state and API behavior

Keep one catalog entry and one logical identifier per table. The catalog should own:

- The physical table location and immutable table UUID.
- The active legacy metadata pointer.
- The compliant metadata pointer, nullable until the table is upgraded.
- Upgrade/dual-write state and a commit token or base version suitable for compare-and-swap.

These are catalog state, not duplicated Iceberg table properties. Both heads refer to the same
logical table, schemas, snapshots, and refs, but their metadata paths may differ.

The existing OpenHouse API keeps serving the legacy pointer during compatibility mode. The
Iceberg REST Catalog API serves the compliant pointer and implements the standard catalog
discovery/load/commit behavior needed by supported clients. Both paths enter a shared commit
service; neither API should independently implement metadata mutation or concurrency handling.

Map the REST Catalog's one-level namespace to an OpenHouse database. Do not invent a second
namespace for compliant tables: namespace and table identity remain the same regardless of the
metadata representation. REST Catalog operations should respect OpenHouse authentication,
authorization, and catalog-only rename semantics.

Rename changes catalog identity/mappings only; it does not update table properties, rewrite
metadata, or move either pointer. Data-affecting and Iceberg metadata commits are distinct
operations.

## Dual-write commit protocol

For a logical Iceberg metadata commit:

1. Read and validate the catalog's current commit token and whichever metadata heads are
   available for the table.
2. Apply the logical Iceberg change once and derive the legacy and compliant representations from
   that same resulting state.
3. Write immutable output files in each representation's isolated namespace. Compliant output
   must rewrite or create manifest/list files as needed so all nested file locations are
   qualified; it must not reuse a legacy manifest containing unqualified paths.
4. Verify output is durable/readable before making it visible.
5. Atomically compare-and-swap catalog state to publish both new pointers and advance the commit
   token. A failed CAS leaves the prior heads visible; unreferenced output is reclaimed later.

Data files may be referenced by both graphs when the qualified URI resolves to the same object.
Only metadata artifacts need separate paths. A writer that cannot complete required dual-write
must not report a successful commit while leaving the API heads inconsistent.

When a table has not yet been upgraded, the commit service must either produce a compliant
representation from the current legacy state as part of the first REST commit, or require an
explicit upgrade first. Prefer making the transition explicit in catalog state and returning a
clear error to REST clients until a compliant head exists; do not silently serve a legacy head
through the REST API.

## Upgrade existing tables

Expose an OpenHouse SQL operation, with syntax to be finalized, for example:

```sql
ALTER TABLE <database>.<table> UPGRADE ICEBERG METADATA;
```

This operation upgrades the metadata representation; it does not change the table's Iceberg
format version or move the table to a different catalog API. The exact SQL syntax is to be
finalized.

The command starts or resumes a catalog-managed upgrade:

1. Capture the current legacy pointer and commit token; mark the table as upgrading or fence
   conflicting commits.
2. Read table metadata and enumerate every retained snapshot, ref, and reachable manifest list
   and manifest.
3. Rewrite manifest entries with qualified data-file URIs. Write new manifest lists that point
   to the compliant manifests. Preserve snapshot IDs, sequence numbers, refs, schema/spec
   metadata, and history where supported.
4. Write compliant table metadata JSON under the compliant namespace. Update every
   location-bearing field, including metadata-log entries retained in the output.
5. Validate the rewritten graph and atomically publish the compliant pointer only if the legacy
   head/token still matches the captured base. On a concurrent commit, retry from the new base or
   leave the upgrade resumable without changing the visible head.
6. Mark the table upgraded. The legacy API continues to serve the legacy pointer while
   compatibility mode is enabled.

Rewrite each unique reachable manifest once per migration where possible, preserving sharing
between snapshots. Use stable migration identifiers and create-if-absent/verify semantics so a
retry is idempotent. Keep data files untouched if URI qualification preserves their actual
storage identity.

For small metadata graphs the SQL command may execute synchronously. For large graphs, submit a
Spark job through the existing jobs framework; choose the threshold based on metadata graph size
(for example, unique manifests, manifest-list count, or bytes), not table data size alone. The
job must be resumable, report progress/failure, and publish the compliant head only after the
entire graph has passed validation.

## Rollout and legacy retirement

1. Land path qualification and compliant metadata serialization behind a feature gate; validate
   it against independent Iceberg readers before exposing it through REST.
2. Implement the catalog pointer/state changes and shared dual-write commit service.
3. Add REST Catalog endpoints and client integration, initially opt-in.
4. Add the SQL upgrade command and Spark execution path; migrate representative tables of
   different sizes and storage backends.
5. Enable dual-write for upgraded tables. Measure legacy API reads/writes and dual-write
   failures.
6. Migrate clients to REST and disable new legacy writes once no legacy writer remains.
7. Retain legacy metadata for a configurable rollback period. Delete it only after confirming
   that no supported client, retained snapshot, or recovery flow can require it.

Snapshot expiration and orphan-file deletion must mark the union of files reachable from both
heads during dual-write and rollback retention. They must not delete shared data files just
because one metadata graph has been retired.

## Validation and acceptance criteria

- A compliant table's metadata JSON, manifest lists, manifests, and retained metadata log use
  fully qualified, resolvable URIs.
- An independent Iceberg v2 client can load, scan, and commit through the REST Catalog path.
- The legacy API continues to load and commit tables during dual-write.
- Both heads represent the same logical snapshots, refs, schema, and table properties after
  successful dual-write; catalog CAS prevents partial publication.
- A failed write, failed Spark job, process restart, or concurrent commit cannot publish an
  incomplete graph or overwrite a visible immutable file.
- Migration preserves data-file identity, snapshot IDs, refs, and readable history.
- Rename changes catalog identity without rewriting table metadata or either head.
- Expiration and orphan cleanup retain the union of required files until legacy retirement is
  complete.
- Tests cover Local, HDFS, and S3 URI qualification, as well as retries, CAS conflicts, partial
  writes, and migration idempotence.

## Open design decisions

- Exact catalog schema/API changes for storing a second metadata pointer and upgrade state.
- Final compliant path and filename layout, including the deterministic identity used for
  migration output.
- Whether new tables begin dual-write immediately or begin legacy-only until explicitly upgraded.
- Whether REST commits are rejected before upgrade or trigger an automatic synchronous upgrade.
- The supported subset/version of the Iceberg REST Catalog protocol and its auth/session
  requirements.
- Upgrade fencing strategy, retry policy, cancellation behavior, and Spark size threshold.
- Rollback retention duration and the operational signal that permits legacy-file deletion.
- Handling unsupported or corrupt historical metadata during conversion, including whether to
  fail the entire migration or allow an explicitly scoped snapshot-retention policy.
