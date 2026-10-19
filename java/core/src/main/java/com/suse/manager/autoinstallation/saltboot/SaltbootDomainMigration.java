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

import static com.redhat.rhn.domain.formula.FormulaFactory.PREFIX;
import static com.redhat.rhn.domain.formula.FormulaFactory.SALTBOOT;
import static com.redhat.rhn.domain.formula.FormulaFactory.SALTBOOT_GROUP;

import com.redhat.rhn.common.hibernate.HibernateFactory;
import com.redhat.rhn.domain.org.CustomDataKey;
import com.redhat.rhn.domain.server.CustomDataValue;
import com.redhat.rhn.domain.server.MinionServer;
import com.redhat.rhn.domain.server.Server;
import com.redhat.rhn.domain.server.ServerGroup;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * One-time migration of the saltboot formula data into the saltboot domain model. The formula assignments are not
 * touched: the formulas stay authoritative until the user unassigns them, the formula-save hooks keep the domain
 * equal meanwhile.
 * <p>
 * The migration is idempotent: groups and systems that already have domain rows are skipped, because the upgrade
 * task is queued again on every start until the release.
 */
public final class SaltbootDomainMigration {

    private static final Logger LOG = LogManager.getLogger(SaltbootDomainMigration.class);
    private static final String LEGACY_FLAGS_CATEGORY = "tuning-saltboot";
    private static final String FORCE_REDEPLOY = "force_redeploy";
    private static final String FORCE_REPARTITION = "force_repartition";
    private static final Set<String> TRUE_VALUES = Set.of("true", "1", "yes", "y");

    private SaltbootDomainMigration() {
    }

    /**
     * Migrates the data of the saltboot formulas.
     */
    public static void migrate() {
        migrateBranches();
        migrateGroupConfigs();
        migrateSystemConfigs();
        migrateForceFlags();
        LOG.info("Saltboot domain migration finished");
    }

    private static void migrateBranches() {
        for (Object[] row : listGroupFormulas(PREFIX + SALTBOOT_GROUP)) {
            ServerGroup group = (ServerGroup) row[0];
            if (SaltbootFactory.lookupBranch(group).isPresent()) {
                continue;
            }
            try {
                SaltbootFormulaMapper.applyBranchFormula(group, formulaData(row));
                LOG.info("Migrated saltboot branch settings of group {}", group.getName());
            }
            catch (RuntimeException e) {
                LOG.error("Unable to migrate saltboot branch settings of group {}", group.getName(), e);
            }
        }
    }

    private static void migrateGroupConfigs() {
        Map<Long, Set<String>> groupsByMinion = new HashMap<>();
        for (Object[] row : listGroupFormulas(PREFIX + SALTBOOT)) {
            ServerGroup group = (ServerGroup) row[0];
            group.getServers().forEach(server ->
                    groupsByMinion.computeIfAbsent(server.getId(), id -> new TreeSet<>()).add(group.getName()));
            if (SaltbootFactory.lookupAssignment(group).isPresent()) {
                continue;
            }
            try {
                SaltbootFormulaMapper.applyGroupConfigFormula(group, formulaData(row));
                LOG.info("Migrated saltboot config of group {}", group.getName());
            }
            catch (RuntimeException e) {
                LOG.error("Unable to migrate saltboot config of group {}", group.getName(), e);
            }
        }
        groupsByMinion.forEach((serverId, groups) -> {
            if (groups.size() > 1) {
                LOG.warn("Server {} is member of several groups with saltboot configs {}: they have the same " +
                        "priority and conflict once the saltboot formulas are unassigned", serverId, groups);
            }
        });
    }

    private static void migrateSystemConfigs() {
        List<Object[]> rows = HibernateFactory.getSession()
                .createQuery("SELECT m, p.pillar FROM Pillar p JOIN p.minion m WHERE p.category = :category",
                        Object[].class)
                .setParameter("category", PREFIX + SALTBOOT)
                .list();
        for (Object[] row : rows) {
            MinionServer minion = (MinionServer) row[0];
            if (SaltbootFactory.lookupAssignment(minion).isPresent()) {
                continue;
            }
            try {
                SaltbootFormulaMapper.applyServerConfigFormula(minion, formulaData(row));
                LOG.info("Migrated saltboot config of system {}", minion.getMinionId());
            }
            catch (RuntimeException e) {
                LOG.error("Unable to migrate saltboot config of system {}", minion.getMinionId(), e);
            }
        }
    }

    private static void migrateForceFlags() {
        List<Object[]> pillars = HibernateFactory.getSession()
                .createQuery("SELECT m, p.pillar FROM Pillar p JOIN p.minion m WHERE p.category = :category",
                        Object[].class)
                .setParameter("category", LEGACY_FLAGS_CATEGORY)
                .list();
        for (Object[] row : pillars) {
            Object saltboot = formulaData(row).get("saltboot");
            if (saltboot instanceof Map<?, ?> flags) {
                setFlags((Server) row[0], isTrue(flags.get(FORCE_REDEPLOY)), isTrue(flags.get(FORCE_REPARTITION)));
            }
        }

        List<CustomDataKey> keys = HibernateFactory.getSession()
                .createQuery("FROM CustomDataKey k WHERE k.label IN (:labels)", CustomDataKey.class)
                .setParameterList("labels", List.of("saltboot_" + FORCE_REDEPLOY, "saltboot_" + FORCE_REPARTITION))
                .list();
        if (keys.isEmpty()) {
            return;
        }
        List<CustomDataValue> values = HibernateFactory.getSession()
                .createQuery("FROM CustomDataValue AS c WHERE c.key IN (:keys)", CustomDataValue.class)
                .setParameterList("keys", keys)
                .list();
        for (CustomDataValue value : values) {
            boolean set = isTrue(value.getValue());
            String label = value.getKey().getLabel();
            setFlags(value.getServer(), set && label.endsWith(FORCE_REDEPLOY),
                    set && label.endsWith(FORCE_REPARTITION));
        }
    }

    /** Only raises flags: a flag is never cleared by the migration, so the migration can be repeated. */
    private static void setFlags(Server server, boolean redeploy, boolean repartition) {
        if (!redeploy && !repartition) {
            return;
        }
        SaltbootTerminal terminal = SaltbootFactory.lookupOrCreateTerminal(server.getId());
        terminal.setForceRedeploy(terminal.isForceRedeploy() || redeploy);
        terminal.setForceRepartition(terminal.isForceRepartition() || repartition);
        SaltbootFactory.save(terminal);
    }

    private static boolean isTrue(Object value) {
        return Boolean.TRUE.equals(value) || value instanceof String text && TRUE_VALUES.contains(text.toLowerCase());
    }

    private static List<Object[]> listGroupFormulas(String category) {
        return HibernateFactory.getSession()
                .createQuery("SELECT g, p.pillar FROM Pillar p JOIN p.group g WHERE p.category = :category",
                        Object[].class)
                .setParameter("category", category)
                .list();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> formulaData(Object[] row) {
        return (Map<String, Object>) row[1];
    }
}
