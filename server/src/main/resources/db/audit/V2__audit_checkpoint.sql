-- Derby flush progress: one row per pod (derby:<node>).
CREATE TABLE audit_checkpoint (
  name        VARCHAR(255) NOT NULL,
  last_log_id BIGINT       NULL,
  updated_at  DATETIME(6)  NOT NULL,
  PRIMARY KEY (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;
