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

import com.redhat.rhn.common.hibernate.HibernateFactory;
import com.redhat.rhn.domain.org.Org;
import com.redhat.rhn.domain.server.Server;
import com.redhat.rhn.domain.server.ServerGroup;

import com.suse.manager.autoinstallation.saltboot.SaltbootConfigResolver.ConfigData;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Persistence of the saltboot deployment model.
 */
public class SaltbootFactory extends HibernateFactory {

    private static final Logger LOG = LogManager.getLogger(SaltbootFactory.class);
    private static final SaltbootFactory INSTANCE = new SaltbootFactory();

    private SaltbootFactory() {
        super();
    }

    @Override
    protected Logger getLogger() {
        return LOG;
    }

    /**
     * Stores branch settings.
     *
     * @param branch the branch
     * @return the managed branch
     */
    public static SaltbootBranch save(SaltbootBranch branch) {
        return INSTANCE.saveObject(branch);
    }

    /**
     * Removes branch settings.
     *
     * @param branch the branch
     */
    public static void remove(SaltbootBranch branch) {
        INSTANCE.removeObject(branch);
    }

    /**
     * @param group a server group
     * @return the saltboot branch settings of the group, if any
     */
    public static Optional<SaltbootBranch> lookupBranch(ServerGroup group) {
        return getSession().createQuery("FROM SaltbootBranch b WHERE b.group = :group", SaltbootBranch.class)
                .setParameter("group", group)
                .uniqueResultOptional();
    }

    /**
     * @param org an organization
     * @return the branches of the groups of the organization
     */
    public static List<SaltbootBranch> listBranches(Org org) {
        return getSession().createQuery("FROM SaltbootBranch b WHERE b.group.org = :org ORDER BY b.group.name",
                        SaltbootBranch.class)
                .setParameter("org", org)
                .list();
    }

    /**
     * @param groups the groups of a terminal
     * @return the branches among the given groups
     */
    public static List<SaltbootBranch> listBranches(Collection<? extends ServerGroup> groups) {
        if (groups.isEmpty()) {
            return List.of();
        }
        return getSession().createQuery("FROM SaltbootBranch b WHERE b.group IN (:groups)", SaltbootBranch.class)
                .setParameterList("groups", groups)
                .list();
    }

    /**
     * Stores a config.
     *
     * @param config the config
     * @return the managed config
     */
    public static SaltbootConfig save(SaltbootConfig config) {
        return INSTANCE.saveObject(config);
    }

    /**
     * Removes a config, which must not have children (database restriction).
     *
     * @param config the config
     */
    public static void remove(SaltbootConfig config) {
        INSTANCE.removeObject(config);
    }

    /**
     * @param org the organization
     * @param id the config id
     * @return the config if it exists in the organization
     */
    public static Optional<SaltbootConfig> lookupConfig(Org org, Long id) {
        return getSession().createQuery("FROM SaltbootConfig c WHERE c.org = :org AND c.id = :id", SaltbootConfig.class)
                .setParameter("org", org)
                .setParameter("id", id)
                .uniqueResultOptional();
    }

    /**
     * @param org the organization
     * @param name the config name
     * @return the config if it exists in the organization
     */
    public static Optional<SaltbootConfig> lookupConfig(Org org, String name) {
        return getSession().createQuery("FROM SaltbootConfig c WHERE c.org = :org AND c.name = :name",
                        SaltbootConfig.class)
                .setParameter("org", org)
                .setParameter("name", name)
                .uniqueResultOptional();
    }

    /**
     * @param org the organization
     * @return all configs of the organization
     */
    public static List<SaltbootConfig> listConfigs(Org org) {
        return getSession().createQuery("FROM SaltbootConfig c WHERE c.org = :org ORDER BY c.name",
                        SaltbootConfig.class)
                .setParameter("org", org)
                .list();
    }

    /**
     * @param config a config
     * @return the configs that have the given config as parent
     */
    public static List<SaltbootConfig> listChildren(SaltbootConfig config) {
        return getSession().createQuery("FROM SaltbootConfig c WHERE c.parent = :parent ORDER BY c.name",
                        SaltbootConfig.class)
                .setParameter("parent", config)
                .list();
    }

    /**
     * Snapshots all configs of an organization for the {@link SaltbootConfigResolver}.
     *
     * @param org the organization
     * @return the configs by id
     */
    public static Map<Long, ConfigData> loadConfigGraph(Org org) {
        return listConfigs(org).stream().collect(Collectors.toMap(SaltbootConfig::getId, SaltbootFactory::toData));
    }

    /**
     * @param config a config entity
     * @return its snapshot for the {@link SaltbootConfigResolver}
     */
    public static ConfigData toData(SaltbootConfig config) {
        return new ConfigData(config.getId(), config.getName(),
                config.getParent() == null ? null : config.getParent().getId(), config.getPartitioning(),
                config.getKernelAction(), config.getTerminalKernelParameters(), config.getFreezeImage());
    }

    /**
     * Stores an assignment.
     *
     * @param assignment the assignment
     * @return the managed assignment
     */
    public static SaltbootConfigAssignment save(SaltbootConfigAssignment assignment) {
        return INSTANCE.saveObject(assignment);
    }

    /**
     * Removes an assignment.
     *
     * @param assignment the assignment
     */
    public static void remove(SaltbootConfigAssignment assignment) {
        INSTANCE.removeObject(assignment);
    }

    /**
     * @param group a server group
     * @return the config assignment of the group, if any
     */
    public static Optional<SaltbootConfigAssignment> lookupAssignment(ServerGroup group) {
        return getSession().createQuery("FROM SaltbootConfigAssignment a WHERE a.group = :group",
                        SaltbootConfigAssignment.class)
                .setParameter("group", group)
                .uniqueResultOptional();
    }

    /**
     * @param server a system
     * @return the config assignment of the system, if any
     */
    public static Optional<SaltbootConfigAssignment> lookupAssignment(Server server) {
        return getSession().createQuery("FROM SaltbootConfigAssignment a WHERE a.server = :server",
                        SaltbootConfigAssignment.class)
                .setParameter("server", server)
                .uniqueResultOptional();
    }

    /**
     * @param groups the groups of a terminal
     * @return the assignments among the given groups
     */
    public static List<SaltbootConfigAssignment> listGroupAssignments(Collection<? extends ServerGroup> groups) {
        if (groups.isEmpty()) {
            return List.of();
        }
        return getSession().createQuery("FROM SaltbootConfigAssignment a WHERE a.group IN (:groups)",
                        SaltbootConfigAssignment.class)
                .setParameterList("groups", groups)
                .list();
    }

    /**
     * @param config a config
     * @return the assignments of the config
     */
    public static List<SaltbootConfigAssignment> listAssignments(SaltbootConfig config) {
        return getSession().createQuery("FROM SaltbootConfigAssignment a WHERE a.config = :config",
                        SaltbootConfigAssignment.class)
                .setParameter("config", config)
                .list();
    }

    /**
     * Stores terminal state.
     *
     * @param terminal the terminal state
     * @return the managed terminal state
     */
    public static SaltbootTerminal save(SaltbootTerminal terminal) {
        return INSTANCE.saveObject(terminal);
    }

    /**
     * @param serverId the id of a terminal system
     * @return the state of the terminal, if any
     */
    public static Optional<SaltbootTerminal> lookupTerminal(Long serverId) {
        return Optional.ofNullable(getSession().find(SaltbootTerminal.class, serverId));
    }

    /**
     * @param serverId the id of a terminal system
     * @return the existing state of the terminal or a new stored one
     */
    public static SaltbootTerminal lookupOrCreateTerminal(Long serverId) {
        return lookupTerminal(serverId).orElseGet(() -> save(new SaltbootTerminal(serverId)));
    }
}
