-- pipeline schema, third migration. Minimal publication: a Build gets the next sequence of its pack
-- in the same UPDATE that marks it SUCCEEDED, and the latest Pack is the highest sequence of a pack.

ALTER TABLE pipeline.builds
    ADD COLUMN publication_sequence bigint CHECK (publication_sequence >= 1),
    ADD COLUMN published_at timestamptz,
    ADD CONSTRAINT builds_publication CHECK (
        (publication_sequence IS NULL) = (published_at IS NULL)
            AND (publication_sequence IS NULL OR status = 'SUCCEEDED')
    ),
    ADD CONSTRAINT builds_publication_unique UNIQUE (pack_id, publication_sequence);

-- Builds that succeeded before this migration are published in creation order. This is the only
-- UPDATE of SUCCEEDED rows the guard ever lets through. Flyway runs the migration in one transaction,
-- so the trigger is off only inside it and a failure rolls the DISABLE back as well.
ALTER TABLE pipeline.builds DISABLE TRIGGER builds_guard;

UPDATE pipeline.builds b
SET publication_sequence = ranked.sequence,
    published_at = clock_timestamp()
FROM (
    SELECT build_id,
           row_number() OVER (PARTITION BY pack_id ORDER BY created_at, build_id) AS sequence
    FROM pipeline.builds
    WHERE status = 'SUCCEEDED'
) ranked
WHERE b.build_id = ranked.build_id;

ALTER TABLE pipeline.builds ENABLE TRIGGER builds_guard;
