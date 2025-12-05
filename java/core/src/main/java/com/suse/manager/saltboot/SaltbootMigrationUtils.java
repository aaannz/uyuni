/*
 * Copyright (c) 2025 SUSE LLC
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

package com.suse.manager.saltboot;

import com.redhat.rhn.domain.formula.FormulaFactory;
import com.redhat.rhn.domain.image.ImageInfo;
import com.redhat.rhn.domain.image.ImageInfoFactory;
import com.redhat.rhn.domain.image.ImageProfile;
import com.redhat.rhn.domain.server.MinionServer;
import com.redhat.rhn.domain.server.MinionServerFactory;
import com.redhat.rhn.domain.server.Pillar;
import com.redhat.rhn.domain.server.ServerGroup;
import com.redhat.rhn.manager.kickstart.cobbler.CobblerXMLRPCHelper;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cobbler.CobblerConnection;
import org.cobbler.Distro;
import org.cobbler.Profile;
import org.cobbler.SystemRecord;
import org.cobbler.XmlRpcException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class SaltbootMigrationUtils {
    private static final Logger LOG = LogManager.getLogger(SaltbootMigrationUtils.class);
    private SaltbootMigrationUtils() { }

    private static String getOldNamingScheme(ImageInfo imageInfo) {
        return imageInfo.getOrg().getId() + "-" +
                imageInfo.getName() + "-" +
                imageInfo.getVersion() + "-" +
                imageInfo.getRevisionNumber();
    }

    private static void migrateSaltbootDistros(CobblerConnection con) throws SaltbootMigrationException {
        List<Distro> distros = Distro.list(con);
        boolean allOk = true;
        con.transactionBegin();
        // Image list is sorted from the oldest to the newest.
        // createSaltbootDistro automatically sets default distro to currently created, so it depends on this order
        for (ImageInfo imageInfo : ImageInfoFactory.list()) {
            if (!imageInfo.getImageType().equals(ImageProfile.TYPE_KIWI) || !imageInfo.isBuilt()) {
                continue;
            }
            try {
                SaltbootUtils.createSaltbootDistro(imageInfo, distros, con);

                String newName = SaltbootUtils.makeCobblerNameVR(imageInfo);
                Profile newDistroProfile = Profile.lookupByName(con, newName);
                if (newDistroProfile == null) {
                    throw new SaltbootMigrationException("Could not find new distribution profile " + newName);
                }
                String oldName = getOldNamingScheme(imageInfo);
                Profile oldDistroProfile = Profile.lookupByName(con, oldName);
                if (oldDistroProfile != null) {
                    migrateSaltbootSystems(con, oldDistroProfile, newDistroProfile);
                    oldDistroProfile.remove();
                }
                Distro oldDistro = Distro.lookupByName(con, oldName);
                if (oldDistro != null) {
                    oldDistro.remove();
                }
            }
            catch (XmlRpcException | SaltbootMigrationException e) {
                LOG.error("Error migrating {}-{}-{}", imageInfo.getName(),
                        imageInfo.getVersion(), imageInfo.getRevisionNumber(), e);
                allOk = false;
            }
        }
        con.transactionCommit();
        if (!allOk) {
            throw new SaltbootMigrationException(
                    "Errors encountered when creating new saltboot distributions, see log files");
        }
    }

    private static void migrateSaltbootProfiles(CobblerConnection con) {
        boolean allOk = true;
        con.transactionBegin();
        for (ServerGroup group : Pillar.getGroupsForCategory(FormulaFactory.SALTBOOT_PILLAR)) {
            try {
                group.getSaltbootGroup().ifPresentOrElse(
                        SaltbootGroup::updateDataFromGroup,
                        () -> {
                            SaltbootGroup sg = new SaltbootGroup(group);
                            group.setSaltbootGroup(sg);
                        }
                );
                // Remove both old and new profile name if present
                Profile profile = Profile.lookupByName(con,
                        group.getOrg().getId() + "-" + group.getName());
                if (profile != null) {
                    profile.remove();
                }
                profile = Profile.lookupByName(con, SaltbootUtils.makeCobblerName(group.getOrg(), group.getName()));
                if (profile != null) {
                    profile.remove();
                }
            }
            catch (XmlRpcException | SaltbootException e) {
                LOG.error("Error migrating {}", group.getName(), e);
                allOk = false;
            }
        }
        con.transactionCommit();
        if (!allOk) {
            throw new SaltbootMigrationException(
                    "Errors encountered when migrating to new saltboot profiles, see log files");
        }
    }

    private static void migrateSaltbootSystems(CobblerConnection con, Profile oldProfile, Profile newProfile) {
        Pattern oldNamePattern = Pattern.compile("[0-9]+-(.*)");
        Pattern newNamePattern = Pattern.compile("([^:]+):S:[0-9]+:[^:]+");

        List<SystemRecord> systems = SystemRecord.listByAssociatedProfile(con, oldProfile.getName());
        systems.addAll(SystemRecord.listByAssociatedProfile(con, newProfile.getName()));
        for (SystemRecord system : systems) {
            Map<String, Object> kernel = system.getKernelOptions().orElseGet(
                    HashMap::new
            );
            String branchId = (String)kernel.get("MINION_ID_PREFIX");
            if (branchId == null) {
                // This is not a valid Saltboot system
                continue;
            }

            // extract system name from the parent name
            String systemName = system.getName();
            String minionId = null;
            Matcher oldMatcher = oldNamePattern.matcher(systemName);
            Matcher newMatcher = newNamePattern.matcher(systemName);
            if (oldMatcher.matches()) {
                minionId = oldMatcher.group(1);
            }
            else if (newMatcher.matches()) {
                minionId = newMatcher.group(1);
            }
            else {
                throw new SaltbootMigrationException("Cannot determine minion id from the profile " + systemName);
            }

            // find the MinionServer for the system entry
            MinionServer server = MinionServerFactory.findByMinionId(minionId).orElseThrow(
                    () -> new SaltbootMigrationException("Cannot find server object for system " + systemName)
            );

            String parentName = system.getParent();
            String imageName;

            // extract image name from the parent name
            oldMatcher = oldNamePattern.matcher(parentName);
            newMatcher = newNamePattern.matcher(parentName);
            if (oldMatcher.matches()) {
                imageName = oldMatcher.group(1);
            }
            else if (newMatcher.matches()) {
                imageName = newMatcher.group(1);
            }
            else {
                throw new SaltbootMigrationException("Cannot find image assigned to profile " + systemName);
            }
            ImageInfo image = SaltbootImage.lookupImageFromImageString(imageName, server.getOrg()).orElseThrow(
                    () -> new SaltbootMigrationException("Cannot find image " + imageName)
            );

            // Get saltboot group
            SaltbootGroup group = SaltbootGroup.getSaltbootGroupByBranchId(branchId, server.getOrg()).orElseThrow(
                    () -> new SaltbootMigrationException("Cannot find saltboot group for branch id " + branchId)
            );

            // Prepare kernel options
            String rootDevice = (String)kernel.get("root");
            String saltDevice = (String)kernel.getOrDefault("salt_device", "");
            String kernelOptions = kernel.entrySet().stream()
                    .filter(entry ->
                            !entry.getKey().equals("root") &&
                            !entry.getKey().equals("salt_device") &&
                            !entry.getKey().equals("MINION_ID_PREFIX") &&
                            !entry.getKey().equals("MASTER")
                    )
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .collect(Collectors.joining(" "));
            server.getSaltbootServer().ifPresentOrElse(
                    s -> s.updateFromOptions(group, rootDevice, saltDevice, image, kernelOptions),
                    () -> server.setSaltbootServer(
                            new SaltbootServer(server, group, rootDevice, saltDevice, image, kernelOptions))
            );

            // Remove cobbler entry
            system.remove();
        }
    }

    /**
     * Migrate saltboot cobbler entries to the new naming scheme.
     * Workflow:
     * 1) create new distro and profile entries based on new naming scheme
     * 2) migrate branch profiles and existing system entries to new profile names
     * 3) remove old distro and distro profiles entries
     */
    public static void migrateSaltboot() {
        CobblerConnection con = CobblerXMLRPCHelper.getUncachedAutomatedConnection();
        migrateSaltboot(con);
    }

    /**
     * Migrate saltboot cobbler entries to the new naming scheme.
     * @param con Cobbler connection
     */
    public static void migrateSaltboot(CobblerConnection con) {
        migrateSaltbootDistros(con);
        migrateSaltbootProfiles(con);
    }
}
