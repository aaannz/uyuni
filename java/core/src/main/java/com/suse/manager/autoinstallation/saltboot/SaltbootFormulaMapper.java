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
import com.redhat.rhn.domain.notification.UserNotificationFactory;
import com.redhat.rhn.domain.notification.types.SaltbootConfigurationProblem;
import com.redhat.rhn.domain.org.Org;
import com.redhat.rhn.domain.role.RoleFactory;
import com.redhat.rhn.domain.server.MinionServer;
import com.redhat.rhn.domain.server.Server;
import com.redhat.rhn.domain.server.ServerFactory;
import com.redhat.rhn.domain.server.ServerGroup;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Maps the data of the saltboot formulas onto the saltboot domain model. Used by the upgrade migration and by the
 * dual-write in {@link FormulaFactory}, so both produce the same rows.
 * <p>
 * The formula data is taken as is, it is not validated: it is what the saltboot states run with today.
 */
public final class SaltbootFormulaMapper {

    private static final Logger LOG = LogManager.getLogger(SaltbootFormulaMapper.class);

    private SaltbootFormulaMapper() {
    }

    /**
     * Creates or updates the branch settings of a group from its saltboot-group formula data.
     * The download server becomes the proxy with that FQDN; if there is none, the branch has no proxy and the
     * organization administrators are notified to pick one.
     *
     * @param group the branch group
     * @param formData the saved data of the formula
     * @return the stored branch
     */
    public static SaltbootBranch applyBranchFormula(ServerGroup group, Map<String, Object> formData) {
        Map<String, Object> saltboot = asMap(formData.get("saltboot"));
        SaltbootBranch branch = SaltbootFactory.lookupBranch(group).orElseGet(() -> new SaltbootBranch(group));

        String naming = String.valueOf(saltboot.get("minion_id_naming"));
        branch.setMinionIdNaming(SaltbootBranch.NAMING_VALUES.contains(naming) ?
                naming : SaltbootBranch.NAMING_HOSTNAME);
        branch.setDisableIdPrefix(Boolean.TRUE.equals(saltboot.get("disable_id_prefix")));
        branch.setDisableUniqueSuffix(Boolean.TRUE.equals(saltboot.get("disable_unique_suffix")));
        branch.setDefaultKernelParameters(blankToNull(saltboot.get("default_kernel_parameters")));
        branch.setDefaultBootImage(bootImage(saltboot));
        branch.setProxy(findProxy(group, blankToNull(saltboot.get("download_server"))));
        return SaltbootFactory.save(branch);
    }

    /**
     * Creates or updates the config of a group from its saltboot formula data and assigns it to the group.
     *
     * @param group the group
     * @param formData the saved data of the formula
     * @return the stored config
     */
    public static SaltbootConfig applyGroupConfigFormula(ServerGroup group, Map<String, Object> formData) {
        SaltbootConfig config = SaltbootFactory.lookupAssignment(group)
                .map(SaltbootConfigAssignment::getConfig)
                .orElseGet(() -> {
                    SaltbootConfig created = SaltbootFactory.save(
                            new SaltbootConfig(group.getOrg(), uniqueName(group.getOrg(), group.getName())));
                    SaltbootFactory.save(new SaltbootConfigAssignment(created, group, 0));
                    return created;
                });
        return fillConfig(config, formData);
    }

    /**
     * Creates or updates the config of a system from its saltboot formula data and assigns it to the system.
     * The config is a root config with the complete partitioning of the system: today the system value replaces
     * the group value as a whole, so inheriting from a group config would change the effective result.
     *
     * @param minion the system
     * @param formData the saved data of the formula
     * @return the stored config
     */
    public static SaltbootConfig applyServerConfigFormula(MinionServer minion, Map<String, Object> formData) {
        SaltbootConfig config = SaltbootFactory.lookupAssignment(minion)
                .map(SaltbootConfigAssignment::getConfig)
                .orElseGet(() -> {
                    SaltbootConfig created = SaltbootFactory.save(
                            new SaltbootConfig(minion.getOrg(), uniqueName(minion.getOrg(), minion.getMinionId())));
                    SaltbootFactory.save(new SaltbootConfigAssignment(created, minion));
                    return created;
                });
        return fillConfig(config, formData);
    }

    private static SaltbootConfig fillConfig(SaltbootConfig config, Map<String, Object> formData) {
        Map<String, Object> partitioning = asMap(formData.get("partitioning"));
        config.setPartitioning(partitioning.isEmpty() ? null : FormulaFactory.convertIntegers(
                SaltbootConfigResolver.mergePartitioning(Collections.emptyMap(), partitioning)));
        config.setTerminalKernelParameters(blankToNull(formData.get("terminal_kernel_parameters")));

        Map<String, Object> saltboot = asMap(formData.get("saltboot"));
        String kernelAction = blankToNull(saltboot.get("kernel_action"));
        config.setKernelAction(kernelAction != null && SaltbootPartitioning.KERNEL_ACTIONS.contains(kernelAction) ?
                kernelAction : null);
        config.setFreezeImage(saltboot.get("freeze_image") instanceof Boolean freeze ? freeze : null);
        return SaltbootFactory.save(config);
    }

    private static String bootImage(Map<String, Object> saltboot) {
        String image = blankToNull(saltboot.get("default_boot_image"));
        String version = blankToNull(saltboot.get("default_boot_image_version"));
        if (image == null) {
            return null;
        }
        return version == null ? image : image + "-" + version;
    }

    private static Server findProxy(ServerGroup group, String downloadServer) {
        if (downloadServer == null) {
            return null;
        }
        List<Server> proxies = ServerFactory.listByFqdn(downloadServer).stream().filter(Server::isProxy).toList();
        if (proxies.isEmpty()) {
            String details = "No proxy with the FQDN " + downloadServer + " exists. Select the proxy of the " +
                    "saltboot branch in the branch settings.";
            LOG.warn("Saltboot branch {}: {}", group.getName(), details);
            notifyOrgAdmins(group.getOrg(), "branch " + group.getName(), details);
            return null;
        }
        if (proxies.size() > 1) {
            LOG.warn("Saltboot branch {}: several proxies match {}, using the first", group.getName(), downloadServer);
        }
        return proxies.get(0);
    }

    private static void notifyOrgAdmins(Org org, String subject, String details) {
        UserNotificationFactory.storeNotificationMessageFor(
                UserNotificationFactory.createNotificationMessage(new SaltbootConfigurationProblem(subject, details)),
                RoleFactory.ORG_ADMIN, Optional.of(org));
    }

    private static String uniqueName(Org org, String wanted) {
        String name = wanted;
        for (int i = 2; SaltbootFactory.lookupConfig(org, name).isPresent(); i++) {
            name = wanted + "-" + i;
        }
        return name;
    }

    private static String blankToNull(Object value) {
        return value instanceof String text && StringUtils.isNotBlank(text) ? text : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Collections.emptyMap();
    }
}
