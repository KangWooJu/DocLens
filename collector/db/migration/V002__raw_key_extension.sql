-- raw 원본 key 확장자를 content type 기준 .html 또는 .txt로
-- V001의 raw_key CHECK 두 개(형식, 위치 계산)는 이름 없는 CHECK라 정의로 찾아 지운다
DO $$
DECLARE
    item record;
BEGIN
    FOR item IN
        SELECT conname
        FROM pg_constraint
        WHERE conrelid = 'collector.collection_targets'::regclass
          AND contype = 'c'
          AND (pg_get_constraintdef(oid) LIKE '%raw_key ~%'
               OR pg_get_constraintdef(oid) LIKE '%substr(raw_key%')
    LOOP
        EXECUTE format('ALTER TABLE collector.collection_targets DROP CONSTRAINT %I', item.conname);
    END LOOP;
END $$;

-- 버전은 contracts/v1 exactVersion 패턴, 파일 이름은 raw_hash의 hex, raw_hash 없는 raw_key 거부
ALTER TABLE collector.collection_targets
    ADD CONSTRAINT collection_targets_raw_key_shape CHECK (
        raw_key IS NULL
        OR (raw_hash IS NOT NULL AND raw_key ~ ('^raw/[a-z][a-z0-9-]*/[0-9]+\.[0-9]+\.[0-9]+(\.RELEASE)?/' || substr(raw_hash, 8) || '\.(html|txt)$'))
    );

COMMENT ON COLUMN collector.sources.probe_version IS '규칙이 관측한 버전 (HTML meta version 또는 gradle.properties version=)';
COMMENT ON COLUMN collector.collection_targets.observed_version IS '규칙이 관측한 버전, 판정 아님 (HTML meta version 또는 gradle.properties version=)';
