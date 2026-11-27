-- Baseline schema for Reservoir tenant storage.
-- Statements are idempotent so existing schemas created before schema_migrations adopt V001 cleanly.

CREATE TABLE IF NOT EXISTS ${schema}.global_records (
  id uuid NOT NULL PRIMARY KEY,
  local_id VARCHAR NOT NULL,
  source_id VARCHAR NOT NULL,
  source_version integer DEFAULT 1,
  payload JSONB NOT NULL
);

DROP INDEX IF EXISTS idx_local_id;

ALTER TABLE ${schema}.global_records ADD COLUMN IF NOT EXISTS source_version integer DEFAULT 1;

CREATE UNIQUE INDEX IF NOT EXISTS idx_local_source ON ${schema}.global_records (local_id, source_id, source_version);

CREATE INDEX IF NOT EXISTS idx_source ON ${schema}.global_records (source_id, source_version);

CREATE TABLE IF NOT EXISTS ${schema}.match_key_config (
  id VARCHAR NOT NULL PRIMARY KEY,
  matcher VARCHAR,
  method VARCHAR,
  update VARCHAR,
  params JSONB,
  args VARCHAR,
  cql JSONB
);

ALTER TABLE ${schema}.match_key_config ADD COLUMN IF NOT EXISTS matcher VARCHAR;

ALTER TABLE ${schema}.match_key_config ADD COLUMN IF NOT EXISTS args VARCHAR;

ALTER TABLE ${schema}.match_key_config ADD COLUMN IF NOT EXISTS cql JSONB;

CREATE TABLE IF NOT EXISTS ${schema}.cluster_meta (
  cluster_id uuid NOT NULL PRIMARY KEY,
  match_key_config_id VARCHAR NOT NULL,
  datestamp TIMESTAMP,
  FOREIGN KEY (match_key_config_id) REFERENCES ${schema}.match_key_config ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS cluster_meta_datestamp_idx ON ${schema}.cluster_meta (datestamp);

CREATE TABLE IF NOT EXISTS ${schema}.cluster_records (
  record_id uuid NOT NULL,
  match_key_config_id VARCHAR NOT NULL,
  cluster_id uuid NOT NULL,
  FOREIGN KEY (match_key_config_id) REFERENCES ${schema}.match_key_config ON DELETE CASCADE,
  FOREIGN KEY (record_id) REFERENCES ${schema}.global_records ON DELETE CASCADE
);

CREATE UNIQUE INDEX IF NOT EXISTS cluster_record_record_matchkey_idx ON ${schema}.cluster_records (record_id, match_key_config_id);

CREATE INDEX IF NOT EXISTS cluster_record_cluster_idx ON ${schema}.cluster_records (cluster_id);

CREATE TABLE IF NOT EXISTS ${schema}.cluster_values (
  cluster_id uuid NOT NULL,
  match_key_config_id VARCHAR NOT NULL,
  match_value VARCHAR NOT NULL,
  FOREIGN KEY (match_key_config_id) REFERENCES ${schema}.match_key_config ON DELETE CASCADE
);

CREATE UNIQUE INDEX IF NOT EXISTS cluster_value_value_idx ON ${schema}.cluster_values (match_key_config_id, match_value);

CREATE INDEX IF NOT EXISTS cluster_value_cluster_idx ON ${schema}.cluster_values (cluster_id);

CREATE TABLE IF NOT EXISTS ${schema}.module (
  id VARCHAR NOT NULL PRIMARY KEY,
  type VARCHAR,
  url VARCHAR,
  function VARCHAR,
  script VARCHAR
);

ALTER TABLE ${schema}.module ADD COLUMN IF NOT EXISTS type VARCHAR;

ALTER TABLE ${schema}.module ADD COLUMN IF NOT EXISTS script VARCHAR;

CREATE TABLE IF NOT EXISTS ${schema}.oai_config (
  id VARCHAR NOT NULL PRIMARY KEY,
  config JSONB NOT NULL
);

CREATE TABLE IF NOT EXISTS ${schema}.oai_pmh_clients (
  id VARCHAR NOT NULL PRIMARY KEY,
  config JSONB,
  job JSONB,
  stop BOOLEAN,
  owner UUID
);

ALTER TABLE ${schema}.oai_pmh_clients ADD COLUMN IF NOT EXISTS lease_until TIMESTAMP;

CREATE TABLE IF NOT EXISTS ${schema}.pool_initialization_jobs (
  id UUID NOT NULL PRIMARY KEY,
  pool_id VARCHAR NOT NULL,
  status VARCHAR NOT NULL CHECK (status IN ('running', 'idle')),
  claim_token UUID,
  lease_until TIMESTAMP,
  checkpoint UUID,
  total_records BIGINT NOT NULL DEFAULT 0,
  started_at TIMESTAMP NOT NULL,
  completed_at TIMESTAMP,
  error TEXT,
  FOREIGN KEY (pool_id) REFERENCES ${schema}.match_key_config ON DELETE CASCADE
);

CREATE UNIQUE INDEX IF NOT EXISTS pool_initialization_one_running_idx ON ${schema}.pool_initialization_jobs (status) WHERE status = 'running';
