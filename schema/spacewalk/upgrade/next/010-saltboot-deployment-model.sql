-- Saltboot deployment model (autoinstallation overhaul step 3.2).
-- Must stay idempotent: scripts in upgrade/next are re-applied on every start.

CREATE TABLE IF NOT EXISTS suseSaltbootBranch
(
    id                          BIGINT
                                  CONSTRAINT suse_sb_branch_id_pk PRIMARY KEY
                                  GENERATED ALWAYS AS IDENTITY,
    group_id                    NUMERIC NOT NULL
                                  CONSTRAINT suse_sb_branch_gid_fk
                                    REFERENCES rhnServerGroup (id)
                                    ON DELETE CASCADE
                                  CONSTRAINT suse_sb_branch_gid_uq UNIQUE,
    proxy_server_id             NUMERIC
                                  CONSTRAINT suse_sb_branch_proxy_fk
                                    REFERENCES rhnServer (id)
                                    ON DELETE SET NULL,
    minion_id_naming            VARCHAR(16) DEFAULT 'Hostname' NOT NULL
                                  CONSTRAINT suse_sb_branch_naming_ck
                                    CHECK (minion_id_naming IN ('FQDN', 'Hostname', 'HWType', 'MAC')),
    disable_id_prefix           BOOLEAN DEFAULT FALSE NOT NULL,
    disable_unique_suffix       BOOLEAN DEFAULT FALSE NOT NULL,
    default_boot_image          VARCHAR,
    default_kernel_parameters   VARCHAR,
    created                     TIMESTAMPTZ
                                  DEFAULT (current_timestamp) NOT NULL,
    modified                    TIMESTAMPTZ
                                  DEFAULT (current_timestamp) NOT NULL
);

CREATE INDEX IF NOT EXISTS suse_sb_branch_proxy_idx ON suseSaltbootBranch (proxy_server_id);

CREATE TABLE IF NOT EXISTS suseSaltbootConfig
(
    id                          BIGINT
                                  CONSTRAINT suse_sb_config_id_pk PRIMARY KEY
                                  GENERATED ALWAYS AS IDENTITY,
    org_id                      NUMERIC NOT NULL
                                  CONSTRAINT suse_sb_config_oid_fk
                                    REFERENCES web_customer (id)
                                    ON DELETE CASCADE,
    name                        VARCHAR NOT NULL,
    parent_id                   BIGINT
                                  CONSTRAINT suse_sb_config_parent_fk
                                    REFERENCES suseSaltbootConfig (id)
                                    ON DELETE RESTRICT,
    partitioning                JSONB,
    kernel_action               VARCHAR(16)
                                  CONSTRAINT suse_sb_config_kaction_ck
                                    CHECK (kernel_action IN ('kexec', 'reboot', 'fail')),
    terminal_kernel_parameters  VARCHAR,
    freeze_image                BOOLEAN,
    created                     TIMESTAMPTZ
                                  DEFAULT (current_timestamp) NOT NULL,
    modified                    TIMESTAMPTZ
                                  DEFAULT (current_timestamp) NOT NULL,
    CONSTRAINT suse_sb_config_oid_name_uq UNIQUE (org_id, name)
);

CREATE INDEX IF NOT EXISTS suse_sb_config_parent_idx ON suseSaltbootConfig (parent_id);

CREATE TABLE IF NOT EXISTS suseSaltbootConfigAssignment
(
    id                          BIGINT
                                  CONSTRAINT suse_sb_assign_id_pk PRIMARY KEY
                                  GENERATED ALWAYS AS IDENTITY,
    config_id                   BIGINT NOT NULL
                                  CONSTRAINT suse_sb_assign_cid_fk
                                    REFERENCES suseSaltbootConfig (id)
                                    ON DELETE CASCADE,
    group_id                    NUMERIC
                                  CONSTRAINT suse_sb_assign_gid_fk
                                    REFERENCES rhnServerGroup (id)
                                    ON DELETE CASCADE
                                  CONSTRAINT suse_sb_assign_gid_uq UNIQUE,
    server_id                   NUMERIC
                                  CONSTRAINT suse_sb_assign_sid_fk
                                    REFERENCES rhnServer (id)
                                    ON DELETE CASCADE
                                  CONSTRAINT suse_sb_assign_sid_uq UNIQUE,
    priority                    INTEGER DEFAULT 0 NOT NULL,
    created                     TIMESTAMPTZ
                                  DEFAULT (current_timestamp) NOT NULL,
    modified                    TIMESTAMPTZ
                                  DEFAULT (current_timestamp) NOT NULL,
    CONSTRAINT suse_sb_assign_one_target_ck CHECK (
      (group_id IS NOT NULL AND server_id IS NULL) OR
      (group_id IS NULL AND server_id IS NOT NULL)
    )
);

CREATE INDEX IF NOT EXISTS suse_sb_assign_cid_idx ON suseSaltbootConfigAssignment (config_id);

CREATE TABLE IF NOT EXISTS suseSaltbootTerminal
(
    server_id                   NUMERIC
                                  CONSTRAINT suse_sb_terminal_sid_pk PRIMARY KEY
                                  CONSTRAINT suse_sb_terminal_sid_fk
                                    REFERENCES rhnServer (id)
                                    ON DELETE CASCADE,
    boot_image                  VARCHAR,
    root_device                 VARCHAR,
    salt_device                 VARCHAR,
    terminal_kernel_parameters  VARCHAR,
    force_redeploy              BOOLEAN DEFAULT FALSE NOT NULL,
    force_repartition           BOOLEAN DEFAULT FALSE NOT NULL,
    last_pxe_update             TIMESTAMPTZ,
    last_result                 VARCHAR,
    created                     TIMESTAMPTZ
                                  DEFAULT (current_timestamp) NOT NULL,
    modified                    TIMESTAMPTZ
                                  DEFAULT (current_timestamp) NOT NULL
);

-- Queue the formula data migration (UpgradeCommand#migrateSaltbootDomain).
-- The migration itself is idempotent; avoid duplicate pending tasks.
INSERT INTO rhnTaskQueue (id, org_id, task_name, task_data)
SELECT nextval('rhn_task_queue_id_seq'), wc.id,
       'upgrade_satellite_migrate_saltboot_domain', 0
FROM web_customer wc
WHERE wc.id = 1
  AND NOT EXISTS (
      SELECT 1
      FROM rhnTaskQueue tq
      WHERE tq.org_id = wc.id
        AND tq.task_name = 'upgrade_satellite_migrate_saltboot_domain'
        AND tq.task_data = 0
  );
