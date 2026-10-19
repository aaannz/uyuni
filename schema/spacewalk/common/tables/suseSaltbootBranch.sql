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

CREATE TABLE suseSaltbootBranch
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

CREATE INDEX suse_sb_branch_proxy_idx ON suseSaltbootBranch (proxy_server_id);
