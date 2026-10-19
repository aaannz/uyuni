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

CREATE TABLE suseSaltbootConfigAssignment
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

CREATE INDEX suse_sb_assign_cid_idx ON suseSaltbootConfigAssignment (config_id);
