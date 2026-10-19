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

package com.redhat.rhn.frontend.xmlrpc.saltboot;

import com.redhat.rhn.common.security.PermissionException;
import com.redhat.rhn.domain.user.User;
import com.redhat.rhn.frontend.xmlrpc.BaseHandler;
import com.redhat.rhn.frontend.xmlrpc.InvalidParameterException;
import com.redhat.rhn.frontend.xmlrpc.PermissionCheckFailureException;

import com.suse.manager.api.ReadOnly;
import com.suse.manager.autoinstallation.saltboot.SaltbootBranch;
import com.suse.manager.autoinstallation.saltboot.SaltbootConfig;
import com.suse.manager.autoinstallation.saltboot.SaltbootConfigResolver.EffectiveConfig;
import com.suse.manager.autoinstallation.saltboot.SaltbootManager;
import com.suse.manager.autoinstallation.saltboot.SaltbootTerminal;
import com.suse.manager.autoinstallation.saltboot.SaltbootTerminalResolver;
import com.suse.manager.autoinstallation.saltboot.SaltbootTerminalResolver.TerminalSettings;
import com.suse.manager.autoinstallation.saltboot.SaltbootValidationException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * SaltbootHandler
 * @apidoc.namespace saltboot
 * @apidoc.doc Provides methods to manage the Saltboot deployment of retail terminals: branches, partitioning
 * configs and their assignments.
 * <p>
 * A partitioning config only holds what it changes against its parent config. The partitioning is keyed by disk
 * and partition symbolic IDs; to remove a disk or partition inherited from the parent, set its entry to the string
 * <code>__delete__</code>.
 * <p>
 * Branches, configs and assignments that are still managed by a Saltboot formula cannot be changed: unassign the
 * formula first.
 */
public class SaltbootHandler extends BaseHandler {

    /** Marker of a removed (tombstone) entry, XML-RPC cannot transport a null value. */
    static final String DELETE_MARKER = "__delete__";

    private final SaltbootManager saltbootManager;

    /**
     * Instantiates a new saltboot handler.
     *
     * @param saltbootManagerIn the saltboot manager
     */
    public SaltbootHandler(SaltbootManager saltbootManagerIn) {
        this.saltbootManager = saltbootManagerIn;
    }

    /**
     * Create the saltboot settings of a branch group.
     *
     * @param loggedInUser the current user
     * @param groupId the id of the branch group, its name is the minion id prefix of the terminals
     * @param proxyId the id of the proxy that serves the terminals of the branch, 0 for none
     * @param details the branch settings
     * @return the created branch
     *
     * @apidoc.doc Create the saltboot settings of a branch group.
     * @apidoc.param #session_key()
     * @apidoc.param #param_desc("int", "groupId", "the id of the branch system group")
     * @apidoc.param #param_desc("int", "proxyId", "the id of the proxy serving the branch terminals, 0 for none")
     * @apidoc.param
     *   #struct_begin("details")
     *     #prop_desc("string", "minion_id_naming", "FQDN, Hostname (default), HWType or MAC")
     *     #prop_desc("boolean", "disable_id_prefix", "do not prefix the minion id with the branch name")
     *     #prop_desc("boolean", "disable_unique_suffix", "do not append a unique suffix, not valid for HWType")
     *     #prop_desc("string", "default_boot_image", "boot image: name, name-version or name-version-revision")
     *     #prop_desc("string", "default_kernel_parameters", "default kernel parameters of the terminals")
     *   #struct_end()
     * @apidoc.returntype #param_desc("struct", "branch", "see getBranch")
     */
    public Map<String, Object> createBranch(User loggedInUser, Integer groupId, Integer proxyId,
            Map<String, Object> details) {
        return call(() -> branchToMap(
                saltbootManager.createBranch(loggedInUser, groupId.longValue(), proxyId.longValue(), details)));
    }

    /**
     * Get the saltboot settings of a branch group.
     *
     * @param loggedInUser the current user
     * @param groupId the id of the branch group
     * @return the branch
     *
     * @apidoc.doc Get the saltboot settings of a branch group.
     * @apidoc.param #session_key()
     * @apidoc.param #param_desc("int", "groupId", "the id of the branch system group")
     * @apidoc.returntype
     *   #struct_begin("branch")
     *     #prop_desc("int", "group_id", "the id of the branch group")
     *     #prop_desc("string", "group_name", "the name of the branch group")
     *     #prop_desc("int", "proxy_id", "the id of the proxy, 0 if none")
     *     #prop_desc("string", "proxy_fqdn", "the primary FQDN of the proxy, absent if none")
     *     #prop_desc("string", "minion_id_naming", "FQDN, Hostname, HWType or MAC")
     *     #prop_desc("boolean", "disable_id_prefix", "the minion id has no branch prefix")
     *     #prop_desc("boolean", "disable_unique_suffix", "the minion id has no unique suffix")
     *     #prop_desc("string", "default_boot_image", "the default boot image, absent if none")
     *     #prop_desc("string", "default_kernel_parameters", "the default kernel parameters, absent if none")
     *   #struct_end()
     */
    @ReadOnly
    public Map<String, Object> getBranch(User loggedInUser, Integer groupId) {
        return call(() -> branchToMap(saltbootManager.getBranch(loggedInUser, groupId.longValue())));
    }

    /**
     * Update the saltboot settings of a branch group.
     *
     * @param loggedInUser the current user
     * @param groupId the id of the branch group
     * @param details the changed settings, as for createBranch plus proxy_id (0 removes the proxy)
     * @return the updated branch
     *
     * @apidoc.doc Update the saltboot settings of a branch group, only the given settings change.
     * @apidoc.param #session_key()
     * @apidoc.param #param_desc("int", "groupId", "the id of the branch system group")
     * @apidoc.param
     *   #struct_begin("details")
     *     #prop_desc("int", "proxy_id", "the id of the proxy, 0 removes the proxy")
     *     #prop_desc("string", "minion_id_naming", "FQDN, Hostname, HWType or MAC")
     *     #prop_desc("boolean", "disable_id_prefix", "do not prefix the minion id with the branch name")
     *     #prop_desc("boolean", "disable_unique_suffix", "do not append a unique suffix, not valid for HWType")
     *     #prop_desc("string", "default_boot_image", "boot image: name, name-version or name-version-revision")
     *     #prop_desc("string", "default_kernel_parameters", "default kernel parameters of the terminals")
     *   #struct_end()
     * @apidoc.returntype #param_desc("struct", "branch", "see getBranch")
     */
    public Map<String, Object> updateBranch(User loggedInUser, Integer groupId, Map<String, Object> details) {
        return call(() -> branchToMap(saltbootManager.updateBranch(loggedInUser, groupId.longValue(), details)));
    }

    /**
     * Delete the saltboot settings of a branch group.
     *
     * @param loggedInUser the current user
     * @param groupId the id of the branch group
     * @return 1 on success, exception thrown otherwise
     *
     * @apidoc.doc Delete the saltboot settings of a branch group. Fails if terminals are still registered
     * to the branch.
     * @apidoc.param #session_key()
     * @apidoc.param #param_desc("int", "groupId", "the id of the branch system group")
     * @apidoc.returntype #return_int_success()
     */
    public int deleteBranch(User loggedInUser, Integer groupId) {
        return call(() -> {
            saltbootManager.deleteBranch(loggedInUser, groupId.longValue());
            return 1;
        });
    }

    /**
     * List the saltboot branches.
     *
     * @param loggedInUser the current user
     * @return the branches of the organization the user can access
     *
     * @apidoc.doc List the saltboot branches the user has access to.
     * @apidoc.param #session_key()
     * @apidoc.returntype
     *   #return_array_begin()
     *     #param_desc("struct", "branch", "see getBranch")
     *   #array_end()
     */
    @ReadOnly
    public List<Map<String, Object>> listBranches(User loggedInUser) {
        return call(() -> saltbootManager.listBranches(loggedInUser).stream().map(this::branchToMap).toList());
    }

    /**
     * Create a saltboot config.
     *
     * @param loggedInUser the current user
     * @param name the unique name of the config
     * @param parentId the id of the parent config, 0 for none
     * @param details the config settings
     * @return the created config
     *
     * @apidoc.doc Create a saltboot config with optional parent. Requires the organization administrator role.
     * @apidoc.param #session_key()
     * @apidoc.param #param_desc("string", "name", "the unique name of the config")
     * @apidoc.param #param_desc("int", "parentId", "the id of the parent config, 0 for none")
     * @apidoc.param
     *   #struct_begin("details")
     *     #prop_desc("struct", "partitioning", "the disks keyed by their symbolic ID, see the Saltboot formula")
     *     #prop_desc("string", "kernel_action", "kexec, reboot or fail")
     *     #prop_desc("string", "terminal_kernel_parameters", "additional kernel parameters of the terminal")
     *     #prop_desc("boolean", "freeze_image", "keep the deployed image even if a new version is available")
     *   #struct_end()
     * @apidoc.returntype #param_desc("struct", "config", "see getConfig")
     */
    public Map<String, Object> createConfig(User loggedInUser, String name, Integer parentId,
            Map<String, Object> details) {
        return call(() -> configToMap(saltbootManager.createConfig(loggedInUser, name,
                parentId == 0 ? null : parentId.longValue(), withTombstones(details))));
    }

    /**
     * Get a saltboot config.
     *
     * @param loggedInUser the current user
     * @param configId the id of the config
     * @return the config as stored, without the values of its parents
     *
     * @apidoc.doc Get a saltboot config as stored, without the values of its parents. Requires the organization
     * administrator role.
     * @apidoc.param #session_key()
     * @apidoc.param #param_desc("int", "configId", "the id of the config")
     * @apidoc.returntype
     *   #struct_begin("config")
     *     #prop_desc("int", "id", "the id of the config")
     *     #prop_desc("string", "name", "the name of the config")
     *     #prop_desc("int", "parent_id", "the id of the parent config, 0 if none")
     *     #prop_desc("struct", "partitioning", "the partitioning changes against the parent, absent if none")
     *     #prop_desc("string", "kernel_action", "kexec, reboot or fail, absent to inherit")
     *     #prop_desc("string", "terminal_kernel_parameters", "absent to inherit")
     *     #prop_desc("boolean", "freeze_image", "absent to inherit")
     *   #struct_end()
     */
    @ReadOnly
    public Map<String, Object> getConfig(User loggedInUser, Integer configId) {
        return call(() -> configToMap(saltbootManager.getConfig(loggedInUser, configId.longValue())));
    }

    /**
     * Get the effective values of a saltboot config.
     *
     * @param loggedInUser the current user
     * @param configId the id of the config
     * @return the config merged with its ancestors
     *
     * @apidoc.doc Get the effective values of a saltboot config: the config merged with its ancestors. Requires
     * the organization administrator role.
     * @apidoc.param #session_key()
     * @apidoc.param #param_desc("int", "configId", "the id of the config")
     * @apidoc.returntype
     *   #struct_begin("effective config")
     *     #prop_desc("struct", "partitioning", "the complete partitioning")
     *     #prop_desc("string", "kernel_action", "absent if no config of the chain sets it")
     *     #prop_desc("string", "terminal_kernel_parameters", "absent if no config of the chain sets it")
     *     #prop_desc("boolean", "freeze_image", "absent if no config of the chain sets it")
     *   #struct_end()
     */
    @ReadOnly
    public Map<String, Object> getEffectiveConfig(User loggedInUser, Integer configId) {
        return call(() -> effectiveToMap(saltbootManager.getEffectiveConfig(loggedInUser, configId.longValue())));
    }

    /**
     * Update a saltboot config.
     *
     * @param loggedInUser the current user
     * @param configId the id of the config
     * @param details the changed settings
     * @return the updated config
     *
     * @apidoc.doc Update a saltboot config, only the given settings change. Requires the organization
     * administrator role.
     * @apidoc.param #session_key()
     * @apidoc.param #param_desc("int", "configId", "the id of the config")
     * @apidoc.param
     *   #struct_begin("details")
     *     #prop_desc("string", "name", "the new name")
     *     #prop_desc("int", "parent_id", "the id of the new parent config, 0 removes the parent")
     *     #prop_desc("struct", "partitioning", "the partitioning changes against the parent, replaces the
     *       stored changes")
     *     #prop_desc("string", "kernel_action", "kexec, reboot or fail, empty to inherit")
     *     #prop_desc("string", "terminal_kernel_parameters", "empty to inherit")
     *     #prop_desc("boolean", "freeze_image", "keep the deployed image even if a new version is available")
     *   #struct_end()
     * @apidoc.returntype #param_desc("struct", "config", "see getConfig")
     */
    public Map<String, Object> updateConfig(User loggedInUser, Integer configId, Map<String, Object> details) {
        return call(() -> configToMap(
                saltbootManager.updateConfig(loggedInUser, configId.longValue(), withTombstones(details))));
    }

    /**
     * Delete a saltboot config.
     *
     * @param loggedInUser the current user
     * @param configId the id of the config
     * @return 1 on success, exception thrown otherwise
     *
     * @apidoc.doc Delete a saltboot config that has no child configs and no assignments. Requires the
     * organization administrator role.
     * @apidoc.param #session_key()
     * @apidoc.param #param_desc("int", "configId", "the id of the config")
     * @apidoc.returntype #return_int_success()
     */
    public int deleteConfig(User loggedInUser, Integer configId) {
        return call(() -> {
            saltbootManager.deleteConfig(loggedInUser, configId.longValue());
            return 1;
        });
    }

    /**
     * List the saltboot configs.
     *
     * @param loggedInUser the current user
     * @return the configs of the organization
     *
     * @apidoc.doc List the saltboot configs of the organization. Requires the organization administrator role.
     * @apidoc.param #session_key()
     * @apidoc.returntype
     *   #return_array_begin()
     *     #param_desc("struct", "config", "see getConfig")
     *   #array_end()
     */
    @ReadOnly
    public List<Map<String, Object>> listConfigs(User loggedInUser) {
        return call(() -> saltbootManager.listConfigs(loggedInUser).stream().map(this::configToMap).toList());
    }

    /**
     * Assign a saltboot config to a system group.
     *
     * @param loggedInUser the current user
     * @param configId the id of the config
     * @param groupId the id of the system group
     * @param priority the tie breaker when a terminal gets unrelated configs from several groups, the higher
     * wins
     * @return 1 on success, exception thrown otherwise
     *
     * @apidoc.doc Assign a saltboot config to a system group, replacing its current assignment. Requires the
     * organization administrator role.
     * @apidoc.param #session_key()
     * @apidoc.param #param_desc("int", "configId", "the id of the config")
     * @apidoc.param #param_desc("int", "groupId", "the id of the system group")
     * @apidoc.param #param_desc("int", "priority", "tie breaker between unrelated configs, the higher wins")
     * @apidoc.returntype #return_int_success()
     */
    public int assignConfigToGroup(User loggedInUser, Integer configId, Integer groupId, Integer priority) {
        return call(() -> {
            saltbootManager.assignConfigToGroup(loggedInUser, configId.longValue(), groupId.longValue(), priority);
            return 1;
        });
    }

    /**
     * Assign a saltboot config to a system.
     *
     * @param loggedInUser the current user
     * @param configId the id of the config
     * @param sid the id of the system
     * @return 1 on success, exception thrown otherwise
     *
     * @apidoc.doc Assign a saltboot config to a system, replacing its current assignment. A system assignment
     * wins over the configs of the groups of the system. Requires the organization administrator role.
     * @apidoc.param #session_key()
     * @apidoc.param #param_desc("int", "configId", "the id of the config")
     * @apidoc.param #param_desc("int", "sid", "the system ID")
     * @apidoc.returntype #return_int_success()
     */
    public int assignConfigToSystem(User loggedInUser, Integer configId, Integer sid) {
        return call(() -> {
            saltbootManager.assignConfigToSystem(loggedInUser, configId.longValue(), sid.longValue());
            return 1;
        });
    }

    /**
     * Remove the saltboot config assignment of a system group.
     *
     * @param loggedInUser the current user
     * @param groupId the id of the system group
     * @return 1 on success, exception thrown otherwise
     *
     * @apidoc.doc Remove the saltboot config assignment of a system group. Requires the organization
     * administrator role.
     * @apidoc.param #session_key()
     * @apidoc.param #param_desc("int", "groupId", "the id of the system group")
     * @apidoc.returntype #return_int_success()
     */
    public int unassignConfigFromGroup(User loggedInUser, Integer groupId) {
        return call(() -> {
            saltbootManager.unassignConfigFromGroup(loggedInUser, groupId.longValue());
            return 1;
        });
    }

    /**
     * Remove the saltboot config assignment of a system.
     *
     * @param loggedInUser the current user
     * @param sid the id of the system
     * @return 1 on success, exception thrown otherwise
     *
     * @apidoc.doc Remove the saltboot config assignment of a system. Requires the organization administrator
     * role.
     * @apidoc.param #session_key()
     * @apidoc.param #param_desc("int", "sid", "the system ID")
     * @apidoc.returntype #return_int_success()
     */
    public int unassignConfigFromSystem(User loggedInUser, Integer sid) {
        return call(() -> {
            saltbootManager.unassignConfigFromSystem(loggedInUser, sid.longValue());
            return 1;
        });
    }

    /**
     * Get the deployment state of a terminal.
     *
     * @param loggedInUser the current user
     * @param sid the id of the terminal
     * @return the state
     *
     * @apidoc.doc Get the deployment state of a saltboot terminal.
     * @apidoc.param #session_key()
     * @apidoc.param #param_desc("int", "sid", "the system ID")
     * @apidoc.returntype
     *   #struct_begin("terminal state")
     *     #prop_desc("int", "sid", "the system ID")
     *     #prop_desc("boolean", "force_redeploy", "the image is redeployed at the next deployment")
     *     #prop_desc("boolean", "force_repartition", "the disks are repartitioned at the next deployment")
     *     #prop_desc("string", "boot_image", "the boot image last reported by the terminal, absent if unknown")
     *     #prop_desc("string", "root_device", "the root device last reported, absent if unknown")
     *     #prop_desc("string", "salt_device", "the salt device last reported, absent if unknown")
     *     #prop_desc("string", "terminal_kernel_parameters", "the kernel parameters last reported, absent if
     *       unknown")
     *     #prop_desc("dateTime.iso8601", "last_pxe_update", "time of the last report, absent if none")
     *     #prop_desc("string", "last_result", "the outcome of the last deployment step, absent if none")
     *   #struct_end()
     */
    @ReadOnly
    public Map<String, Object> getTerminalState(User loggedInUser, Integer sid) {
        return call(() -> terminalToMap(sid.longValue(), saltbootManager.getTerminalState(loggedInUser,
                sid.longValue())));
    }

    /**
     * Get the saltboot settings that apply to a terminal.
     *
     * @param loggedInUser the current user
     * @param sid the id of the terminal
     * @return the resolution
     *
     * @apidoc.doc Get the saltboot settings that apply to a terminal: the resolved config with its effective
     * values, the branch and the problems that block the deployment, such as unrelated configs with the same
     * priority.
     * @apidoc.param #session_key()
     * @apidoc.param #param_desc("int", "sid", "the system ID")
     * @apidoc.returntype
     *   #struct_begin("terminal config")
     *     #prop_desc("boolean", "formula_managed", "a Saltboot formula still owns the settings of the terminal")
     *     #prop_desc("int", "config_id", "the id of the resolved config, absent if none")
     *     #prop_desc("struct", "effective", "the effective config values, absent if no config applies")
     *     #prop_desc("int", "branch_group_id", "the id of the branch group, absent if none")
     *     #prop_desc("string", "proxy_fqdn", "the FQDN of the branch proxy, absent if none")
     *     #prop_desc("array", "problems", "descriptions of what blocks the deployment")
     *   #struct_end()
     */
    @ReadOnly
    public Map<String, Object> getEffectiveTerminalConfig(User loggedInUser, Integer sid) {
        return call(() -> {
            TerminalSettings settings = saltbootManager.getTerminalSettings(loggedInUser, sid.longValue());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("formula_managed", saltbootManager.isFormulaManaged(loggedInUser, sid.longValue()));
            if (settings.configResolution().isResolved()) {
                result.put("config_id", settings.configResolution().configId().intValue());
            }
            settings.effective().ifPresent(effective -> result.put("effective", effectiveToMap(effective)));
            settings.branch().ifPresent(branch -> {
                result.put("branch_group_id", branch.getGroup().getId().intValue());
                SaltbootTerminalResolver.proxyFqdn(branch).ifPresent(fqdn -> result.put("proxy_fqdn", fqdn));
            });
            result.put("problems", settings.problems());
            return result;
        });
    }

    /**
     * Flag a terminal for redeployment.
     *
     * @param loggedInUser the current user
     * @param sid the id of the terminal
     * @param repartition true to also repartition the disks
     * @return 1 on success, exception thrown otherwise
     *
     * @apidoc.doc Flag a saltboot terminal for redeployment of its image, optionally with repartitioning, at its
     * next deployment. The flags are cleared once the terminal boots from disk again.
     * @apidoc.param #session_key()
     * @apidoc.param #param_desc("int", "sid", "the system ID")
     * @apidoc.param #param_desc("boolean", "repartition", "also repartition the disks")
     * @apidoc.returntype #return_int_success()
     */
    public int scheduleForceRedeploy(User loggedInUser, Integer sid, Boolean repartition) {
        return call(() -> {
            saltbootManager.scheduleForceRedeploy(loggedInUser, sid.longValue(), repartition);
            return 1;
        });
    }

    private <T> T call(Supplier<T> action) {
        try {
            return action.get();
        }
        catch (PermissionException e) {
            throw new PermissionCheckFailureException(e.getMessage());
        }
        catch (SaltbootValidationException e) {
            throw new InvalidParameterException(e.getMessage());
        }
    }

    private Map<String, Object> branchToMap(SaltbootBranch branch) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("group_id", branch.getGroup().getId().intValue());
        result.put("group_name", branch.getGroup().getName());
        result.put("proxy_id", branch.getProxy() == null ? 0 : branch.getProxy().getId().intValue());
        SaltbootTerminalResolver.proxyFqdn(branch).ifPresent(fqdn -> result.put("proxy_fqdn", fqdn));
        result.put("minion_id_naming", branch.getMinionIdNaming());
        result.put("disable_id_prefix", branch.isDisableIdPrefix());
        result.put("disable_unique_suffix", branch.isDisableUniqueSuffix());
        putIfNotNull(result, "default_boot_image", branch.getDefaultBootImage());
        putIfNotNull(result, "default_kernel_parameters", branch.getDefaultKernelParameters());
        return result;
    }

    private Map<String, Object> configToMap(SaltbootConfig config) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", config.getId().intValue());
        result.put("name", config.getName());
        result.put("parent_id", config.getParent() == null ? 0 : config.getParent().getId().intValue());
        putIfNotNull(result, "partitioning", config.getPartitioning() == null ? null :
                markTombstones(config.getPartitioning()));
        putIfNotNull(result, "kernel_action", config.getKernelAction());
        putIfNotNull(result, "terminal_kernel_parameters", config.getTerminalKernelParameters());
        putIfNotNull(result, "freeze_image", config.getFreezeImage());
        return result;
    }

    private Map<String, Object> effectiveToMap(EffectiveConfig effective) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("partitioning", effective.partitioning());
        putIfNotNull(result, "kernel_action", effective.kernelAction());
        putIfNotNull(result, "terminal_kernel_parameters", effective.terminalKernelParameters());
        putIfNotNull(result, "freeze_image", effective.freezeImage());
        return result;
    }

    private Map<String, Object> terminalToMap(long sid, Optional<SaltbootTerminal> terminal) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sid", (int) sid);
        result.put("force_redeploy", terminal.map(SaltbootTerminal::isForceRedeploy).orElse(false));
        result.put("force_repartition", terminal.map(SaltbootTerminal::isForceRepartition).orElse(false));
        terminal.ifPresent(state -> {
            putIfNotNull(result, "boot_image", state.getBootImage());
            putIfNotNull(result, "root_device", state.getRootDevice());
            putIfNotNull(result, "salt_device", state.getSaltDevice());
            putIfNotNull(result, "terminal_kernel_parameters", state.getTerminalKernelParameters());
            putIfNotNull(result, "last_pxe_update", state.getLastPxeUpdate());
            putIfNotNull(result, "last_result", state.getLastResult());
        });
        return result;
    }

    private static void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    /** Replaces the delete markers in the partitioning of config details by real tombstones. */
    private static Map<String, Object> withTombstones(Map<String, Object> details) {
        Map<String, Object> result = new LinkedHashMap<>(details);
        if (result.get("partitioning") instanceof Map<?, ?> partitioning) {
            result.put("partitioning", replace(partitioning, DELETE_MARKER, null));
        }
        return result;
    }

    private static Map<String, Object> markTombstones(Map<String, Object> partitioning) {
        return replace(partitioning, null, DELETE_MARKER);
    }

    private static Map<String, Object> replace(Map<?, ?> source, Object from, Object to) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (Objects.equals(value, from)) {
                result.put((String) key, to);
            }
            else if (value instanceof Map<?, ?> nested) {
                result.put((String) key, replace(nested, from, to));
            }
            else {
                result.put((String) key, value);
            }
        });
        return result;
    }
}
