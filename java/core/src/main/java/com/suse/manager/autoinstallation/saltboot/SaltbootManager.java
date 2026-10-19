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

import com.redhat.rhn.common.hibernate.LookupException;
import com.redhat.rhn.common.localization.LocalizationService;
import com.redhat.rhn.common.security.PermissionException;
import com.redhat.rhn.domain.role.RoleFactory;
import com.redhat.rhn.domain.server.ManagedServerGroup;
import com.redhat.rhn.domain.server.MinionServer;
import com.redhat.rhn.domain.server.Server;
import com.redhat.rhn.domain.server.ServerFactory;
import com.redhat.rhn.domain.server.ServerGroup;
import com.redhat.rhn.domain.server.ServerGroupFactory;
import com.redhat.rhn.domain.user.User;
import com.redhat.rhn.manager.formula.FormulaUtil;

import com.suse.manager.autoinstallation.saltboot.SaltbootConfigResolver.EffectiveConfig;
import com.suse.manager.autoinstallation.saltboot.SaltbootTerminalResolver.TerminalSettings;
import com.suse.manager.saltboot.SaltbootException;
import com.suse.manager.saltboot.SaltbootUtils;
import com.suse.manager.webui.services.iface.SaltApi;
import com.suse.manager.webui.services.pillar.SaltbootPillarGenerator;
import com.suse.salt.netapi.datatypes.target.MinionList;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Operations on the saltboot deployment model: branches, configs, their assignments and terminal state.
 * Checks permissions and validity, refuses changes while a saltboot formula still owns the target and refreshes the
 * pillar of the affected terminals.
 */
public class SaltbootManager {

    private static final Logger LOG = LogManager.getLogger(SaltbootManager.class);

    /** Keys of the details of a branch. */
    public static final String BRANCH_NAMING = "minion_id_naming";
    public static final String BRANCH_DISABLE_ID_PREFIX = "disable_id_prefix";
    public static final String BRANCH_DISABLE_UNIQUE_SUFFIX = "disable_unique_suffix";
    public static final String BRANCH_BOOT_IMAGE = "default_boot_image";
    public static final String BRANCH_KERNEL_PARAMETERS = "default_kernel_parameters";
    public static final String BRANCH_PROXY_ID = "proxy_id";

    /** Keys of the details of a config. */
    public static final String CONFIG_NAME = "name";
    public static final String CONFIG_PARENT_ID = "parent_id";
    public static final String CONFIG_PARTITIONING = "partitioning";
    public static final String CONFIG_KERNEL_ACTION = "kernel_action";
    public static final String CONFIG_TERMINAL_PARAMETERS = "terminal_kernel_parameters";
    public static final String CONFIG_FREEZE_IMAGE = "freeze_image";

    private static final String FORMULA_HINT = " Unassign the saltboot formula from it first.";

    private final SaltApi saltApi;
    private final Consumer<ServerGroup> profileCreator;
    private final Consumer<ServerGroup> profileDeleter;

    /**
     * Creates a manager that maintains the cobbler group profiles through {@link SaltbootUtils}.
     *
     * @param saltApiIn the Salt API used to refresh the pillar of terminals
     */
    public SaltbootManager(SaltApi saltApiIn) {
        this(saltApiIn, SaltbootUtils::createSaltbootProfile,
                group -> SaltbootUtils.deleteSaltbootProfile(group.getName(), group.getOrg()));
    }

    /**
     * Creates a manager with explicit cobbler group profile handling.
     *
     * @param saltApiIn the Salt API used to refresh the pillar of terminals
     * @param profileCreatorIn creates or updates the cobbler profile of a branch group
     * @param profileDeleterIn deletes the cobbler profile of a branch group
     */
    public SaltbootManager(SaltApi saltApiIn, Consumer<ServerGroup> profileCreatorIn,
                           Consumer<ServerGroup> profileDeleterIn) {
        this.saltApi = saltApiIn;
        this.profileCreator = profileCreatorIn;
        this.profileDeleter = profileDeleterIn;
    }

    // ---- branches ----

    /**
     * Creates the saltboot settings of a branch group.
     *
     * @param user the acting user
     * @param groupId the id of the branch group
     * @param proxyId the id of the proxy serving the branch terminals
     * @param details the settings, see the BRANCH_ keys, absent keys keep the defaults
     * @return the new branch
     */
    public SaltbootBranch createBranch(User user, Long groupId, Long proxyId, Map<String, Object> details) {
        ManagedServerGroup group = lookupGroup(user, groupId);
        ensureNoFormula(SaltbootTerminalResolver.hasSaltbootFormula(group), "Group " + group.getName());
        if (SaltbootFactory.lookupBranch(group).isPresent()) {
            throw new SaltbootValidationException("Group " + group.getName() + " already is a saltboot branch");
        }
        SaltbootBranch branch = new SaltbootBranch(group);
        branch.setProxy(lookupProxy(user, proxyId));
        applyBranchDetails(branch, details);
        SaltbootFactory.save(branch);
        syncBranchProfile(group);
        refreshPillars(groupMinions(group));
        return branch;
    }

    /**
     * Updates the saltboot settings of a branch group; only the given keys change.
     *
     * @param user the acting user
     * @param groupId the id of the branch group
     * @param details the changed settings, see the BRANCH_ keys, proxy id 0 removes the proxy
     * @return the updated branch
     */
    public SaltbootBranch updateBranch(User user, Long groupId, Map<String, Object> details) {
        SaltbootBranch branch = lookupBranch(user, groupId);
        ensureNoFormula(SaltbootTerminalResolver.hasSaltbootFormula(branch.getGroup()),
                "Group " + branch.getGroup().getName());
        if (details.containsKey(BRANCH_PROXY_ID)) {
            branch.setProxy(lookupProxy(user, toLong(details.get(BRANCH_PROXY_ID))));
        }
        applyBranchDetails(branch, details);
        SaltbootFactory.save(branch);
        syncBranchProfile(branch.getGroup());
        refreshPillars(groupMinions(branch.getGroup()));
        return branch;
    }

    /**
     * Deletes the saltboot settings of a branch group together with its cobbler group profile.
     *
     * @param user the acting user
     * @param groupId the id of the branch group
     */
    public void deleteBranch(User user, Long groupId) {
        SaltbootBranch branch = lookupBranch(user, groupId);
        ServerGroup group = branch.getGroup();
        ensureNoFormula(SaltbootTerminalResolver.hasSaltbootFormula(group), "Group " + group.getName());
        try {
            profileDeleter.accept(group);
        }
        catch (SaltbootException e) {
            throw new SaltbootValidationException(e.getMessage());
        }
        SaltbootFactory.remove(branch);
        refreshPillars(groupMinions(group));
    }

    /**
     * @param user the acting user
     * @param groupId the id of a branch group
     * @return the saltboot settings of the group
     */
    public SaltbootBranch getBranch(User user, Long groupId) {
        return lookupBranch(user, groupId);
    }

    /**
     * @param user the acting user
     * @return the branches of the organization of the user the user has access to
     */
    public List<SaltbootBranch> listBranches(User user) {
        return SaltbootFactory.listBranches(user.getOrg()).stream()
                .filter(branch -> canAccessGroup(user, branch.getGroup()))
                .toList();
    }

    // ---- configs ----

    /**
     * Creates a config.
     *
     * @param user the acting user, an organization administrator
     * @param name the unique name
     * @param parentId the id of the parent config or null
     * @param details the settings, see the CONFIG_ keys
     * @return the new config
     */
    public SaltbootConfig createConfig(User user, String name, Long parentId, Map<String, Object> details) {
        ensureOrgAdmin(user);
        if (StringUtils.isBlank(name)) {
            throw new SaltbootValidationException("A saltboot config needs a name");
        }
        if (SaltbootFactory.lookupConfig(user.getOrg(), name).isPresent()) {
            throw new SaltbootValidationException("A saltboot config named " + name + " already exists");
        }
        SaltbootConfig config = new SaltbootConfig(user.getOrg(), name);
        if (parentId != null) {
            config.setParent(lookupConfig(user, parentId));
        }
        applyConfigDetails(config, details);
        SaltbootFactory.save(config);
        return config;
    }

    /**
     * Updates a config; only the given keys change. The terminals that use the config or a descendant get their
     * pillar regenerated.
     *
     * @param user the acting user, an organization administrator
     * @param configId the config id
     * @param details the changed settings, see the CONFIG_ keys, parent id null or 0 removes the parent
     * @return the updated config
     */
    public SaltbootConfig updateConfig(User user, Long configId, Map<String, Object> details) {
        ensureOrgAdmin(user);
        SaltbootConfig config = lookupConfig(user, configId);
        ensureNoFormulaOnAssignments(config);

        if (details.containsKey(CONFIG_NAME)) {
            String name = String.valueOf(details.get(CONFIG_NAME));
            if (StringUtils.isBlank(name)) {
                throw new SaltbootValidationException("A saltboot config needs a name");
            }
            Optional<SaltbootConfig> sameName = SaltbootFactory.lookupConfig(user.getOrg(), name);
            if (sameName.isPresent() && !sameName.get().getId().equals(config.getId())) {
                throw new SaltbootValidationException("A saltboot config named " + name + " already exists");
            }
            config.setName(name);
        }
        if (details.containsKey(CONFIG_PARENT_ID)) {
            config.setParent(lookupParent(user, config, toLong(details.get(CONFIG_PARENT_ID))));
        }
        applyConfigDetails(config, details);
        SaltbootFactory.save(config);
        refreshPillars(configMinions(config));
        return config;
    }

    /**
     * Deletes a config that has no children and no assignments.
     *
     * @param user the acting user, an organization administrator
     * @param configId the config id
     */
    public void deleteConfig(User user, Long configId) {
        ensureOrgAdmin(user);
        SaltbootConfig config = lookupConfig(user, configId);
        if (!SaltbootFactory.listChildren(config).isEmpty()) {
            throw new SaltbootValidationException("Saltboot config " + config.getName() +
                    " is the parent of other configs");
        }
        if (!SaltbootFactory.listAssignments(config).isEmpty()) {
            throw new SaltbootValidationException("Saltboot config " + config.getName() +
                    " is still assigned to groups or systems");
        }
        SaltbootFactory.remove(config);
    }

    /**
     * @param user the acting user, an organization administrator
     * @param configId the config id
     * @return the config
     */
    public SaltbootConfig getConfig(User user, Long configId) {
        ensureOrgAdmin(user);
        return lookupConfig(user, configId);
    }

    /**
     * @param user the acting user, an organization administrator
     * @param configId the config id
     * @return the config with the settings of its ancestors merged in
     */
    public EffectiveConfig getEffectiveConfig(User user, Long configId) {
        ensureOrgAdmin(user);
        SaltbootConfig config = lookupConfig(user, configId);
        return SaltbootConfigResolver.effective(config.getId(), SaltbootFactory.loadConfigGraph(user.getOrg()));
    }

    /**
     * @param user the acting user, an organization administrator
     * @return all configs of the organization
     */
    public List<SaltbootConfig> listConfigs(User user) {
        ensureOrgAdmin(user);
        return SaltbootFactory.listConfigs(user.getOrg());
    }

    /**
     * @param user the acting user, an organization administrator
     * @param configId the config id
     * @return the assignments of the config
     */
    public List<SaltbootConfigAssignment> listAssignments(User user, Long configId) {
        ensureOrgAdmin(user);
        return SaltbootFactory.listAssignments(lookupConfig(user, configId));
    }

    // ---- assignments ----

    /**
     * Assigns a config to a group, replacing the current assignment of the group.
     *
     * @param user the acting user, an organization administrator
     * @param configId the config id
     * @param groupId the group id
     * @param priority the tie breaker between unrelated configs of one terminal, the higher wins
     */
    public void assignConfigToGroup(User user, Long configId, Long groupId, int priority) {
        ensureOrgAdmin(user);
        SaltbootConfig config = lookupConfig(user, configId);
        ManagedServerGroup group = lookupGroup(user, groupId);
        ensureNoFormula(SaltbootTerminalResolver.hasSaltbootFormula(group), "Group " + group.getName());

        Optional<SaltbootConfigAssignment> current = SaltbootFactory.lookupAssignment(group);
        if (current.isPresent()) {
            current.get().setConfig(config);
            current.get().setPriority(priority);
            SaltbootFactory.save(current.get());
        }
        else {
            SaltbootFactory.save(new SaltbootConfigAssignment(config, group, priority));
        }
        refreshPillars(groupMinions(group));
    }

    /**
     * Assigns a config to a system, replacing the current assignment of the system.
     *
     * @param user the acting user, an organization administrator
     * @param configId the config id
     * @param sid the system id
     */
    public void assignConfigToSystem(User user, Long configId, Long sid) {
        ensureOrgAdmin(user);
        SaltbootConfig config = lookupConfig(user, configId);
        MinionServer minion = lookupMinion(user, sid);
        ensureNoFormula(SaltbootTerminalResolver.hasSaltbootFormula(minion), "System " + minion.getName());

        Optional<SaltbootConfigAssignment> current = SaltbootFactory.lookupAssignment(minion);
        if (current.isPresent()) {
            current.get().setConfig(config);
            SaltbootFactory.save(current.get());
        }
        else {
            SaltbootFactory.save(new SaltbootConfigAssignment(config, minion));
        }
        refreshPillars(List.of(minion));
    }

    /**
     * Removes the config assignment of a group.
     *
     * @param user the acting user, an organization administrator
     * @param groupId the group id
     */
    public void unassignConfigFromGroup(User user, Long groupId) {
        ensureOrgAdmin(user);
        ManagedServerGroup group = lookupGroup(user, groupId);
        ensureNoFormula(SaltbootTerminalResolver.hasSaltbootFormula(group), "Group " + group.getName());
        SaltbootConfigAssignment assignment = SaltbootFactory.lookupAssignment(group).orElseThrow(() ->
                new SaltbootValidationException("Group " + group.getName() + " has no saltboot config assigned"));
        SaltbootFactory.remove(assignment);
        refreshPillars(groupMinions(group));
    }

    /**
     * Removes the config assignment of a system.
     *
     * @param user the acting user, an organization administrator
     * @param sid the system id
     */
    public void unassignConfigFromSystem(User user, Long sid) {
        ensureOrgAdmin(user);
        MinionServer minion = lookupMinion(user, sid);
        ensureNoFormula(SaltbootTerminalResolver.hasSaltbootFormula(minion), "System " + minion.getName());
        SaltbootConfigAssignment assignment = SaltbootFactory.lookupAssignment(minion).orElseThrow(() ->
                new SaltbootValidationException("System " + minion.getName() + " has no saltboot config assigned"));
        SaltbootFactory.remove(assignment);
        refreshPillars(List.of(minion));
    }

    // ---- terminals ----

    /**
     * @param user the acting user
     * @param sid the system id
     * @return the deployment state of the terminal, empty if nothing was recorded yet
     */
    public Optional<SaltbootTerminal> getTerminalState(User user, Long sid) {
        return SaltbootFactory.lookupTerminal(lookupMinionWithAccess(user, sid).getId());
    }

    /**
     * Resolves the saltboot settings that apply to a terminal, with the problems found.
     *
     * @param user the acting user
     * @param sid the system id
     * @return the settings
     */
    public TerminalSettings getTerminalSettings(User user, Long sid) {
        return SaltbootTerminalResolver.resolve(lookupMinionWithAccess(user, sid));
    }

    /**
     * @param user the acting user
     * @param sid the system id
     * @return true if a saltboot formula still owns the settings of the terminal
     */
    public boolean isFormulaManaged(User user, Long sid) {
        return SaltbootTerminalResolver.isFormulaManaged(lookupMinionWithAccess(user, sid));
    }

    /**
     * Flags a terminal for redeployment (and optionally repartitioning) at its next deployment. The flags are
     * cleared once the terminal booted from disk again.
     *
     * @param user the acting user
     * @param sid the system id
     * @param repartition true to also repartition the disks
     */
    public void scheduleForceRedeploy(User user, Long sid, boolean repartition) {
        MinionServer minion = lookupMinionWithAccess(user, sid);
        ensureNoFormula(SaltbootTerminalResolver.isFormulaManaged(minion), "System " + minion.getName());
        SaltbootTerminal terminal = SaltbootFactory.lookupOrCreateTerminal(minion.getId());
        terminal.setForceRedeploy(true);
        terminal.setForceRepartition(terminal.isForceRepartition() || repartition);
        SaltbootFactory.save(terminal);
        refreshPillars(List.of(minion));
    }

    // ---- helpers ----

    private void applyBranchDetails(SaltbootBranch branch, Map<String, Object> details) {
        details.keySet().stream()
                .filter(key -> !Set.of(BRANCH_NAMING, BRANCH_DISABLE_ID_PREFIX, BRANCH_DISABLE_UNIQUE_SUFFIX,
                        BRANCH_BOOT_IMAGE, BRANCH_KERNEL_PARAMETERS, BRANCH_PROXY_ID).contains(key))
                .findFirst().ifPresent(key -> {
                    throw new SaltbootValidationException("Unknown branch setting " + key);
                });
        if (details.containsKey(BRANCH_NAMING)) {
            String naming = String.valueOf(details.get(BRANCH_NAMING));
            if (!SaltbootBranch.NAMING_VALUES.contains(naming)) {
                throw new SaltbootValidationException("Invalid minion id naming " + naming + ", expected one of " +
                        SaltbootBranch.NAMING_VALUES);
            }
            branch.setMinionIdNaming(naming);
        }
        if (details.containsKey(BRANCH_DISABLE_ID_PREFIX)) {
            branch.setDisableIdPrefix(toBoolean(details.get(BRANCH_DISABLE_ID_PREFIX)));
        }
        if (details.containsKey(BRANCH_DISABLE_UNIQUE_SUFFIX)) {
            branch.setDisableUniqueSuffix(toBoolean(details.get(BRANCH_DISABLE_UNIQUE_SUFFIX)));
        }
        if (details.containsKey(BRANCH_BOOT_IMAGE)) {
            branch.setDefaultBootImage(StringUtils.defaultIfBlank(String.valueOf(details.get(BRANCH_BOOT_IMAGE)),
                    null));
        }
        if (details.containsKey(BRANCH_KERNEL_PARAMETERS)) {
            branch.setDefaultKernelParameters(StringUtils.defaultIfBlank(
                    String.valueOf(details.get(BRANCH_KERNEL_PARAMETERS)), null));
        }
        if (branch.isDisableUniqueSuffix() && SaltbootBranch.NAMING_HWTYPE.equals(branch.getMinionIdNaming())) {
            throw new SaltbootValidationException("The unique suffix cannot be disabled for the HWType naming");
        }
    }

    private void applyConfigDetails(SaltbootConfig config, Map<String, Object> details) {
        details.keySet().stream()
                .filter(key -> !Set.of(CONFIG_NAME, CONFIG_PARENT_ID, CONFIG_PARTITIONING, CONFIG_KERNEL_ACTION,
                        CONFIG_TERMINAL_PARAMETERS, CONFIG_FREEZE_IMAGE).contains(key))
                .findFirst().ifPresent(key -> {
                    throw new SaltbootValidationException("Unknown config setting " + key);
                });
        if (details.containsKey(CONFIG_PARTITIONING)) {
            Object partitioning = details.get(CONFIG_PARTITIONING);
            if (partitioning != null && !(partitioning instanceof Map)) {
                throw new SaltbootValidationException("The partitioning must be a map");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> value = (Map<String, Object>) partitioning;
            SaltbootPartitioning.validate(value);
            config.setPartitioning(value == null || value.isEmpty() ? null : value);
        }
        if (details.containsKey(CONFIG_KERNEL_ACTION)) {
            String action = StringUtils.defaultIfBlank(String.valueOf(details.get(CONFIG_KERNEL_ACTION)), null);
            SaltbootPartitioning.validateKernelAction(action);
            config.setKernelAction(action);
        }
        if (details.containsKey(CONFIG_TERMINAL_PARAMETERS)) {
            config.setTerminalKernelParameters(StringUtils.defaultIfBlank(
                    String.valueOf(details.get(CONFIG_TERMINAL_PARAMETERS)), null));
        }
        if (details.containsKey(CONFIG_FREEZE_IMAGE)) {
            config.setFreezeImage(details.get(CONFIG_FREEZE_IMAGE) == null ? null :
                    toBoolean(details.get(CONFIG_FREEZE_IMAGE)));
        }
    }

    private SaltbootConfig lookupParent(User user, SaltbootConfig config, Long parentId) {
        if (parentId == null || parentId == 0) {
            return null;
        }
        SaltbootConfig parent = lookupConfig(user, parentId);
        if (SaltbootConfigResolver.wouldCreateCycle(config.getId(), parent.getId(),
                SaltbootFactory.loadConfigGraph(user.getOrg()))) {
            throw new SaltbootValidationException("Making " + parent.getName() + " the parent of " +
                    config.getName() + " would create a cycle");
        }
        return parent;
    }

    private void syncBranchProfile(ServerGroup group) {
        try {
            profileCreator.accept(group);
        }
        catch (SaltbootException e) {
            LOG.warn(e.getMessage());
        }
    }

    private void ensureNoFormula(boolean formulaAssigned, String target) {
        if (formulaAssigned) {
            throw new SaltbootValidationException(target + " is still configured by a saltboot formula." +
                    FORMULA_HINT);
        }
    }

    private void ensureNoFormulaOnAssignments(SaltbootConfig config) {
        for (SaltbootConfigAssignment assignment : SaltbootFactory.listAssignments(config)) {
            if (assignment.getGroup() != null) {
                ensureNoFormula(SaltbootTerminalResolver.hasSaltbootFormula(assignment.getGroup()),
                        "Group " + assignment.getGroup().getName());
            }
        }
    }

    private void ensureOrgAdmin(User user) {
        if (!user.hasRole(RoleFactory.ORG_ADMIN)) {
            throw new PermissionException(RoleFactory.ORG_ADMIN);
        }
    }

    private boolean canAccessGroup(User user, ServerGroup group) {
        try {
            ensureGroupAccess(user, group);
            return true;
        }
        catch (PermissionException e) {
            return false;
        }
    }

    /** A group the user cannot see is reported by the group check as lookup failure, report it as denied. */
    private void ensureGroupAccess(User user, ServerGroup group) {
        try {
            FormulaUtil.ensureUserHasPermissionsOnServerGroup(user, group);
        }
        catch (LookupException e) {
            throw new PermissionException(LocalizationService.getInstance().getMessage("formula.accessdenied"));
        }
    }

    private ManagedServerGroup lookupGroup(User user, Long groupId) {
        ManagedServerGroup group = ServerGroupFactory.lookupByIdAndOrg(groupId, user.getOrg());
        if (group == null) {
            throw new SaltbootValidationException("Server group " + groupId + " does not exist");
        }
        ensureGroupAccess(user, group);
        return group;
    }

    private SaltbootBranch lookupBranch(User user, Long groupId) {
        ManagedServerGroup group = lookupGroup(user, groupId);
        return SaltbootFactory.lookupBranch(group).orElseThrow(() ->
                new SaltbootValidationException("Group " + group.getName() + " is not a saltboot branch"));
    }

    private Server lookupProxy(User user, Long proxyId) {
        if (proxyId == null || proxyId == 0) {
            return null;
        }
        Server proxy = ServerFactory.lookupByIdAndOrg(proxyId, user.getOrg());
        if (proxy == null || !proxy.isProxy()) {
            throw new SaltbootValidationException("System " + proxyId + " is not a proxy");
        }
        return proxy;
    }

    private SaltbootConfig lookupConfig(User user, Long configId) {
        return SaltbootFactory.lookupConfig(user.getOrg(), configId).orElseThrow(() ->
                new SaltbootValidationException("Saltboot config " + configId + " does not exist"));
    }

    private MinionServer lookupMinion(User user, Long sid) {
        Server server = ServerFactory.lookupByIdAndOrg(sid, user.getOrg());
        if (server == null) {
            throw new SaltbootValidationException("System " + sid + " does not exist");
        }
        return server.asMinionServer().orElseThrow(() ->
                new SaltbootValidationException("System " + sid + " is not a Salt minion"));
    }

    private MinionServer lookupMinionWithAccess(User user, Long sid) {
        MinionServer minion = lookupMinion(user, sid);
        FormulaUtil.ensureUserHasPermissionsOnServer(user, minion);
        return minion;
    }

    private List<MinionServer> groupMinions(ServerGroup group) {
        return group.getServers().stream().flatMap(server -> server.asMinionServer().stream()).toList();
    }

    /** The minions that use a config or one of its descendants. */
    private List<MinionServer> configMinions(SaltbootConfig config) {
        Set<MinionServer> minions = new LinkedHashSet<>();
        List<SaltbootConfig> pending = new ArrayList<>(List.of(config));
        while (!pending.isEmpty()) {
            SaltbootConfig current = pending.remove(0);
            pending.addAll(SaltbootFactory.listChildren(current));
            for (SaltbootConfigAssignment assignment : SaltbootFactory.listAssignments(current)) {
                if (assignment.getGroup() != null) {
                    minions.addAll(groupMinions(assignment.getGroup()));
                }
                else {
                    assignment.getServer().asMinionServer().ifPresent(minions::add);
                }
            }
        }
        return new ArrayList<>(minions);
    }

    private void refreshPillars(List<MinionServer> minions) {
        if (minions.isEmpty()) {
            return;
        }
        minions.forEach(SaltbootPillarGenerator.INSTANCE::generatePillarData);
        saltApi.refreshPillar(new MinionList(minions.stream().map(MinionServer::getMinionId).toList()));
    }

    private static Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new SaltbootValidationException("Expected a number but got " + value);
    }

    private static boolean toBoolean(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        throw new SaltbootValidationException("Expected a boolean but got " + value);
    }
}
