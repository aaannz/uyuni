--
-- Copyright (c) 2026 SUSE LLC
--
-- This software is licensed to you under the GNU General Public License,
-- version 2 (GPLv2). There is NO WARRANTY for this software, express or
-- implied, including the implied warranties of MERCHANTABILITY or FITNESS
-- FOR A PARTICULAR PURPOSE. You should have received a copy of GPLv2
-- along with this software; if not, see
-- http://www.gnu.org/licenses/old-licenses/gpl-2.0.txt.
--
-- Red Hat trademarks are not licensed under GPLv2. No permission is
-- granted to use or replicate Red Hat trademarks that are incorporated
-- in this software or its documentation.
--

CREATE TABLE suseSaltbootConfig
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

CREATE INDEX suse_sb_config_parent_idx ON suseSaltbootConfig (parent_id);
