/*
 * Copyright (c) 2026 SUSE LLC
 *
 * This software is licensed to you under the GNU General Public License,
 * version 2 (GPLv2). There is NO WARRANTY for this software, express or
 * implied, including the implied warranties of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. You should have received a copy of GPLv2
 * along with this software; if not, see
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.txt.
 *
 * Red Hat trademarks are not licensed under GPLv2. No permission is
 * granted to use or replicate Red Hat trademarks that are incorporated
 * in this software or its documentation.
 */

package com.suse.manager.autoinstallation.saltboot;

import com.redhat.rhn.domain.BaseDomainHelper;
import com.redhat.rhn.domain.server.Server;
import com.redhat.rhn.domain.server.ServerGroup;

import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Assigns a {@link SaltbootConfig} to either a server group or a single system. A group or system has at most one
 * assignment.
 */
@Entity
@Table(name = "suseSaltbootConfigAssignment")
public class SaltbootConfigAssignment extends BaseDomainHelper {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne
    @JoinColumn(name = "config_id", nullable = false)
    private SaltbootConfig config;

    @ManyToOne
    @JoinColumn(name = "group_id")
    private ServerGroup group;

    @ManyToOne
    @JoinColumn(name = "server_id")
    private Server server;

    @Column(name = "priority", nullable = false)
    private int priority;

    /**
     * Default constructor, for Hibernate.
     */
    protected SaltbootConfigAssignment() {
    }

    /**
     * Creates the assignment of a config to a group.
     *
     * @param configIn the config
     * @param groupIn the group
     * @param priorityIn the tie breaker between unrelated configs of one system, higher wins
     */
    public SaltbootConfigAssignment(SaltbootConfig configIn, ServerGroup groupIn, int priorityIn) {
        this.config = configIn;
        this.group = groupIn;
        this.priority = priorityIn;
    }

    /**
     * Creates the assignment of a config to a system.
     *
     * @param configIn the config
     * @param serverIn the system
     */
    public SaltbootConfigAssignment(SaltbootConfig configIn, Server serverIn) {
        this.config = configIn;
        this.server = serverIn;
    }

    public Long getId() {
        return id;
    }

    public SaltbootConfig getConfig() {
        return config;
    }

    public void setConfig(SaltbootConfig configIn) {
        this.config = configIn;
    }

    /**
     * @return the assigned group, null for a system assignment
     */
    public ServerGroup getGroup() {
        return group;
    }

    /**
     * @return the assigned system, null for a group assignment
     */
    public Server getServer() {
        return server;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priorityIn) {
        this.priority = priorityIn;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof SaltbootConfigAssignment other &&
                Objects.equals(group, other.group) && Objects.equals(server, other.server);
    }

    @Override
    public int hashCode() {
        return Objects.hash(group, server);
    }
}
