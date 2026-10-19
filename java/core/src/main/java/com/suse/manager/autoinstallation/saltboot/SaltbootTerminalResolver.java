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

import com.redhat.rhn.domain.formula.FormulaFactory;
import com.redhat.rhn.domain.server.MinionServer;
import com.redhat.rhn.domain.server.Server;
import com.redhat.rhn.domain.server.ServerFQDN;
import com.redhat.rhn.domain.server.ServerGroup;

import com.suse.manager.autoinstallation.saltboot.SaltbootConfigResolver.ConfigData;
import com.suse.manager.autoinstallation.saltboot.SaltbootConfigResolver.EffectiveConfig;
import com.suse.manager.autoinstallation.saltboot.SaltbootConfigResolver.GroupAssignment;
import com.suse.manager.autoinstallation.saltboot.SaltbootConfigResolver.Resolution;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Looks up the saltboot settings that apply to a terminal and detects whether the saltboot formulas still own them.
 */
public final class SaltbootTerminalResolver {

    /** Pillar categories of the saltboot formulas: partitioning (system or group) and branch settings (group). */
    private static final List<String> FORMULA_CATEGORIES = List.of(
            FormulaFactory.PREFIX + "saltboot", FormulaFactory.SALTBOOT_PILLAR);

    private SaltbootTerminalResolver() {
    }

    /**
     * What applies to one terminal.
     *
     * @param configResolution the outcome of the config resolution
     * @param effective the effective config, present only if a config was resolved
     * @param branches the branches of the groups of the terminal, more than one is a configuration error
     * @param problems the user facing descriptions of what prevents a complete configuration
     */
    public record TerminalSettings(Resolution configResolution, Optional<EffectiveConfig> effective,
                                   List<SaltbootBranch> branches, List<String> problems) {

        /**
         * @return the branch of the terminal, empty if there is none or it is ambiguous
         */
        public Optional<SaltbootBranch> branch() {
            return branches.size() == 1 ? Optional.of(branches.get(0)) : Optional.empty();
        }
    }

    /**
     * @param group a server group
     * @return true if a saltboot formula is assigned to the group
     */
    public static boolean hasSaltbootFormula(ServerGroup group) {
        return FORMULA_CATEGORIES.stream().anyMatch(category -> group.getPillarByCategory(category).isPresent());
    }

    /**
     * @param minion a system
     * @return true if a saltboot formula is assigned to the system itself
     */
    public static boolean hasSaltbootFormula(MinionServer minion) {
        return FORMULA_CATEGORIES.stream().anyMatch(category -> minion.getPillarByCategory(category).isPresent());
    }

    /**
     * @param minion a system
     * @return true if a saltboot formula is assigned to the system or any of its groups, which keeps the formula
     * pillar authoritative for Salt
     */
    public static boolean isFormulaManaged(MinionServer minion) {
        return hasSaltbootFormula(minion) || minion.getManagedGroups().stream()
                .anyMatch(SaltbootTerminalResolver::hasSaltbootFormula);
    }

    /**
     * Resolves the saltboot settings of a terminal from the domain.
     *
     * @param minion the terminal
     * @return the settings and the problems found
     */
    public static TerminalSettings resolve(MinionServer minion) {
        List<String> problems = new ArrayList<>();
        Map<Long, ConfigData> configs = SaltbootFactory.loadConfigGraph(minion.getOrg());

        Long systemConfigId = SaltbootFactory.lookupAssignment(minion)
                .map(a -> a.getConfig().getId()).orElse(null);
        List<GroupAssignment> groupAssignments = SaltbootFactory.listGroupAssignments(minion.getManagedGroups())
                .stream()
                .map(a -> new GroupAssignment(a.getConfig().getId(), a.getPriority()))
                .collect(Collectors.toList());

        Resolution resolution = SaltbootConfigResolver.resolve(systemConfigId, groupAssignments, configs);
        Optional<EffectiveConfig> effective = Optional.empty();
        if (resolution.isResolved()) {
            effective = Optional.of(SaltbootConfigResolver.effective(resolution.configId(), configs));
        }
        else if (resolution.isTie()) {
            problems.add("Terminal " + minion.getMinionId() + " is assigned the unrelated saltboot configs " +
                    resolution.tiedConfigIds().stream().map(id -> configs.get(id).name())
                            .collect(Collectors.joining(", ")) +
                    " with the same priority. Assign a config to the system or change the priorities.");
        }

        List<SaltbootBranch> branches = SaltbootFactory.listBranches(minion.getManagedGroups());
        if (branches.size() > 1) {
            problems.add("Terminal " + minion.getMinionId() + " is a member of several saltboot branch groups: " +
                    branches.stream().map(b -> b.getGroup().getName()).collect(Collectors.joining(", ")));
        }
        return new TerminalSettings(resolution, effective, branches, problems);
    }

    /**
     * @param branch a saltboot branch
     * @return the primary FQDN of the proxy of the branch, empty if no proxy is set
     */
    public static Optional<String> proxyFqdn(SaltbootBranch branch) {
        return Optional.ofNullable(branch.getProxy()).map(Server::findPrimaryFqdn).map(ServerFQDN::getName);
    }
}
