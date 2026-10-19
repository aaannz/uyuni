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

CREATE TABLE suseSaltbootTerminal
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
