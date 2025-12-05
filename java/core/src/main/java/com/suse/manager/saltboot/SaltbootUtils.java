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

import com.redhat.rhn.common.conf.ConfigDefaults;
import com.redhat.rhn.common.hibernate.HibernateFactory;
import com.redhat.rhn.domain.image.ImageFile;
import com.redhat.rhn.domain.image.ImageInfo;
import com.redhat.rhn.domain.image.OSImageStoreUtils;
import com.redhat.rhn.domain.org.CustomDataKey;
import com.redhat.rhn.domain.org.Org;
import com.redhat.rhn.domain.org.OrgFactory;
import com.redhat.rhn.domain.server.CustomDataValue;
import com.redhat.rhn.domain.server.MinionServer;
import com.redhat.rhn.domain.server.MinionServerFactory;
import com.redhat.rhn.domain.server.ServerFactory;
import com.redhat.rhn.manager.kickstart.cobbler.CobblerXMLRPCHelper;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cobbler.CobblerConnection;
import org.cobbler.CobblerObject;
import org.cobbler.Distro;
import org.cobbler.Profile;
import org.cobbler.SystemRecord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class SaltbootUtils {
    private static final Logger LOG = LogManager.getLogger(SaltbootUtils.class);
    public static final String DEFAULT_BOOT_IMAGE = "DEFAULT_IMAGE";
    private SaltbootUtils() { }

    private static String makeCobblerFilterNameV(ImageInfo imageInfo) {
        String sep = ConfigDefaults.get().getCobblerNameSeparator();
        String label = imageInfo.getName() + "-" + imageInfo.getVersion();
        label = label.replace(' ', '_').replaceAll("[^a-zA-Z0-9_.-]", "");
        String orgName = imageInfo.getOrg().getName().replaceAll("[^a-zA-Z0-9_-]", "");
        String suffix = sep + "S" + sep + imageInfo.getOrg().getId() + sep + orgName;
        return "^(" + Pattern.quote(label) + "-(\\d+))" + Pattern.quote(suffix) + "$";
    }

    private static String makeCobblerFilterName(ImageInfo imageInfo) {
        String sep = ConfigDefaults.get().getCobblerNameSeparator();
        String label = imageInfo.getName();
        label = label.replace(' ', '_').replaceAll("[^a-zA-Z0-9_.-]", "");
        String orgName = imageInfo.getOrg().getName().replaceAll("[^a-zA-Z0-9_-]", "");
        String suffix = sep + "S" + sep + imageInfo.getOrg().getId() + sep + orgName;
        return "^(" + Pattern.quote(label) + "-\\d+\\.\\d+\\.\\d+-\\d+)" + Pattern.quote(suffix) + "$";
    }

    private static String makeCobblerFilterDefault(Org org) {
        String sep = ConfigDefaults.get().getCobblerNameSeparator();
        String orgName = org.getName().replaceAll("[^a-zA-Z0-9_-]", "");
        String suffix = sep + "S" + sep + org.getId() + sep + orgName;
        return "^(.*-\\d+\\.\\d+\\.\\d+-\\d+)" + Pattern.quote(suffix) + "$";
    }

    /**
     * Makes a simple saltboot profile or distro object name that fit our cobbler naming convention
     * See also @code com.redhat.rhn.manager.kickstart.cobbler.CobblerCommand.makeCobblerName
     *
     * Created name follows pattern:
     *      label:S:orgId:orgName
     * where spaces are replaced by _ and not allowed characters are removed.
     * S flag between label and orgId indicates a Saltboot entry.
     *
     * @param org the org to appropriately add the org info
     * @param label the distro or profile label
     * @return the cobbler name.
     */
    public static String makeCobblerName(Org org, String label) {
        String sep = ConfigDefaults.get().getCobblerNameSeparator();
        label = label.replace(' ', '_').replaceAll("[^a-zA-Z0-9_.-]", "");

        String orgName = org.getName().replaceAll("[^a-zA-Z0-9_-]", "");
        // mark the saltboot entries with 'S' so the namespaces do not conflict
        String format = "%s" + sep + "S" + sep + "%s" + sep + "%s";
        return String.format(format, label, org.getId(), orgName);
    }

    private static String makeCobblerName(Org org, String name, String version, String release) {
        if (name == null || name.isEmpty()) {
            return makeCobblerName(org, DEFAULT_BOOT_IMAGE);
        }
        else if (version == null || version.isEmpty()) {
            return makeCobblerName(org, name);
        }
        else if (release == null || release.isEmpty()) {
            return makeCobblerName(org, name + "-" + version);
        }
        else {
            return makeCobblerName(org, name + "-" + version + "-" + release);
        }
    }

    private static String makeCobblerName(Org org, String name, String version) {
        return makeCobblerName(org, name, version, "");
    }

    /**
     * Makes a simple saltboot profile or distro object name that fit our cobbler naming convention
     * See also @code com.redhat.rhn.manager.kickstart.cobbler.CobblerCommand.makeCobblerName
     *
     * Created name follows pattern:
     *      label:S:orgId:orgName
     * where spaces are replaced by _ and not allowed characters are removed.
     * S flag between label and orgId indicates a Saltboot entry.
     *
     * @param imageInfo Image details
     * @return the cobbler name.
     */
    public static String makeCobblerNameVR(ImageInfo imageInfo) {
        return makeCobblerName(imageInfo.getOrg(), imageInfo.getName(), imageInfo.getVersion(),
                String.valueOf(imageInfo.getRevisionNumber()));
    }

    private static String makeCobblerNameV(ImageInfo imageInfo) {
        return makeCobblerName(imageInfo.getOrg(), imageInfo.getName(), imageInfo.getVersion());
    }

    private static String makeCobblerName(ImageInfo imageInfo) {
        return makeCobblerName(imageInfo.getOrg(), imageInfo.getName());
    }

    private static String makeCobblerNameDefault(Org org) {
        return makeCobblerName(org, DEFAULT_BOOT_IMAGE);
    }

    private static Map<String, String> splitStringIgnoreQuotes(String input) {
        Map<String, String> result = new HashMap<>();
        // This regexp mather the sub-strings separated by whitespace characters except if the whitespace is quoted
        String regex = "(?:\s+|^)([^\s\"]+=\"[^\"]+\"|[^\s']+='[^']+'|[^\s\"']+)";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(input);

        while (matcher.find()) {
            String tmp = matcher.group(1).replace("'\"", ""); // Remove quotes
            // Now we should have individual options, let's get key=value
            String[] keyvalues = tmp.split("=", 2);
            if (keyvalues.length != 2) {
                result.put(keyvalues[0], "");
            }
            else {
                result.put(keyvalues[0], keyvalues[1]);
            }
        }
        return result;
    }

    /**
     * Create saltboot distribution based on provided image and boot image info
     * For each distribution, new profile is created as well
     * <p>Distro name is: imageName-imageVersion:S:orgId:orgName</p>
     * @param imageInfo image info
     */
    public static void createSaltbootDistro(ImageInfo imageInfo) {
        CobblerConnection con = CobblerXMLRPCHelper.getUncachedAutomatedConnection();
        List<Distro> distros = Distro.list(con);
        try {
            con.transactionBegin();
            createSaltbootDistro(imageInfo, distros, con);
            con.transactionCommit();
        }
        catch (Exception e) {
            con.transactionAbort();
            throw e;
        }
    }

    /**
     * Create saltboot distribution based on provided image and boot image info
     * For each distribution, new profile is created as well
     * <p>Distro name is: imageName-imageVersion:S:orgId:orgName</p>
     * @param imageInfo image info
     * @param distros list of existing distributions
     * @param con Cobbler connection
     */
    public static void createSaltbootDistro(ImageInfo imageInfo, List<Distro> distros, CobblerConnection con) {
        String nameVR = makeCobblerNameVR(imageInfo);
        String nameV = makeCobblerNameV(imageInfo);
        String name = makeCobblerName(imageInfo);

        // Return early when distribution already exists
        if (distros.stream().anyMatch(d -> nameVR.equals(d.getName()))) {
            LOG.debug("Saltboot distribution {} already exists", nameVR);
            return;
        }

        final String pathPrefix = OSImageStoreUtils.getOSImageStorePathForImage(imageInfo);

        Map<String, String> imageFilePaths = imageInfo.getImageFiles().stream()
                .filter(f -> "kernel".equals(f.getType()) || "initrd".equals(f.getType()))
                .collect(Collectors.toMap(ImageFile::getType, f -> pathPrefix + f.getFile()));

        String kernel = imageFilePaths.get("kernel");
        String initrd = imageFilePaths.get("initrd");

        if (initrd == null || kernel == null) {
            throw new SaltbootException("Missing initrd or kernel files from the image");
        }

        // First create actual distro object
        // Generic breed is required for cobbler not appending any autoyast or kickstart keywords
        Distro cd = new Distro.Builder<String>()
            .setName(nameVR)
            .setInitrd(initrd)
            .setKernel(kernel)
            .setKernelOptions(Optional.of("panic=60 splash=silent"))
            .setArch(imageInfo.getImageArch().getName()).setBreed("generic")
            .build(con);
        cd.setComment("Distro for image " + nameVR + " belonging to organization " + imageInfo.getOrg().getName());
        cd.save();

        // Each distro have its own private profile for individual system records
        // SystemRecords need to be decoupled from saltboot group default profiles
        updateDistroProfile(con, nameVR, cd, "Distro " + nameVR + " private profile");

        // Make a copy of passed distros, as that list can be immutable
        List<Distro> distributions = new ArrayList<>(distros);
        distributions.add(cd);


        // Update DEFAULT_BOOT_IMAGE profile to point to this one
        // As generic default boot image use the latest built image, which is usually this one
        // Reason is that we want latest patches to be generally available
        String defaultImage = makeCobblerNameDefault(imageInfo.getOrg());
        updateDistroProfile(con, defaultImage, cd, "Default image");

        // Update profile when just image name is used
        selectDistro(distributions, makeCobblerFilterName(imageInfo)).ifPresent(n -> {
            if (nameVR.equals(n)) {
                updateDistroProfile(con, name, cd, "Default image for " + name);
            }
        });

        // Update profile when image name-version is used
        selectDistro(distributions, makeCobblerFilterNameV(imageInfo)).ifPresent(n -> {
            if (nameVR.equals(n)) {
                updateDistroProfile(con, nameV, cd, "Default image for " + nameV);
            }
        });
    }

    /**
     * Delete saltboot distribution
     * If distribution is not found, does nothing
     * @param info ImageInfo
     */
    public static void deleteSaltbootDistro(ImageInfo info) throws SaltbootException {
        CobblerConnection con = CobblerXMLRPCHelper.getUncachedAutomatedConnection();
        deleteSaltbootDistro(info, con);
    }

    /**
     * Delete saltboot distribution
     * If distribution is not found, does nothing
     * @param info ImageInfo
     * @param con CobblerConnection
     */
    public static void deleteSaltbootDistro(ImageInfo info, CobblerConnection con) throws SaltbootException {
        Long orgId = info.getOrg().getId();
        String nameVR = makeCobblerNameVR(info);
        String nameV = makeCobblerNameV(info);
        String name = makeCobblerName(info);

        Distro distroToDelete = Distro.lookupByName(con, nameVR);
        if (distroToDelete == null) {
            return;
        }

        List<Distro> distros = Distro.list(con);

        con.transactionBegin();
        try {
            // First delete hidden distro profile
            deleteSaltbootProfile(nameVR, con);

            List<Distro> remainingDistros = distros.stream().filter(
                d -> !nameVR.equals(d.getName())).collect(Collectors.toList());

            selectDistro(remainingDistros, makeCobblerFilterDefault(info.getOrg()))
                 .map(n -> Distro.lookupByName(con, n))
                 .ifPresentOrElse(
                     d -> updateDistroProfile(con, makeCobblerNameDefault(info.getOrg()), d, "Default image"),
                     () -> LOG.error("Can't update the profile for {}", orgId + "-" + DEFAULT_BOOT_IMAGE));

            selectDistro(remainingDistros, makeCobblerFilterName(info))
                 .map(n -> Distro.lookupByName(con, n))
                 .ifPresentOrElse(
                     d -> updateDistroProfile(con, name, d, "Default image for " + name),
                     () -> LOG.error("Can't update the profile for {}", name));

            selectDistro(remainingDistros, makeCobblerFilterName(info))
                 .map(n -> Distro.lookupByName(con, n))
                 .ifPresentOrElse(
                     d -> updateDistroProfile(con, nameV, d, "Default image for " + nameV),
                     () -> LOG.error("Can't update the profile for {}", nameV));

            // then distro itself
            distroToDelete.remove();
            con.transactionCommit();
        }
        catch (Exception e) {
            con.transactionAbort();
            throw e;
        }
    }

    private static Optional<String> selectDistro(List<Distro> distros, String filter) {
        Pattern pattern = Pattern.compile(filter);
        SaltbootVersionCompare saltbootCompare = new SaltbootVersionCompare(pattern);
        return distros
               .stream()
               .map(CobblerObject::getName)
               .filter(s -> pattern.matcher(s).matches())
               .min(saltbootCompare);
    }

    private static void updateDistroProfile(CobblerConnection con, String name, Distro d, String comment) {
        Profile p = Profile.lookupByName(con, name);
        if (p == null) {
            p = Profile.create(con, name, d);
        }
        else {
            p.setDistro(d);
        }
        p.setEnableMenu(false);
        p.setKickstart("");
        p.setComment(comment);
        p.save();
    }

    /**
     * Delete saltboot profile
     * If profile is not found, does nothing
     * @param profileName
     * @param con Use this Cobbler connection
     */
    public static void deleteSaltbootProfile(String profileName, CobblerConnection con) {
        Profile p = Profile.lookupByName(con, profileName);

        if (p != null) {
            List<SystemRecord> systems = SystemRecord.listByAssociatedProfile(con, p.getName());
            if (!systems.isEmpty()) {
                throw new SaltbootException("Unable to delete distro, systems are still registered to it");
            }
            if (!p.remove()) {
                throw new SaltbootException("Unable to delete image saltboot distribution for image " + profileName);
            }
        }
    }

    /**
     * Remove saltboot:force_redeploy and saltboot:force_repartition flags
     * These flags can be both as a saltboot:force* pillars ( from saltboot formula)
     * or custom info saltboot_force_* keys
     *
     * Consumer of these flags is saltboot state
     * @param minionId
     */
    public static void resetSaltbootRedeployFlags(String minionId) {
        MinionServerFactory.findByMinionId(minionId).ifPresentOrElse(
            minion -> {
                // Look for custom_info or formula_saltboot category.
                // If flag is set somewhere else, then we can't reset it
                removeSaltbootRedeployPillar(minion);
                removeSaltbootRedeployCustomInfo(minion);
            },
            () -> LOG.error("Trying to reset saltboot flag for nonexisting minion {}", minionId));
    }

    /**
     * Remove saltboot:force_redeploy and saltboot:force_repartition from saltboot pillar data
     * @param minion
     */
    private static void removeSaltbootRedeployPillar(MinionServer minion) {
        minion.getPillarByCategory("tuning-saltboot").ifPresent(
            pillar -> {
                Map<String, Object> pillarData = pillar.getPillar();
                Map<String, String> saltboot = (Map<String, String>)pillarData.get("saltboot");

                // Check if saltboot data are present at all, remove pillar if there is nothing else
                if (saltboot == null) {
                    if (pillarData.isEmpty()) {
                        minion.getPillars().remove(pillar);
                        HibernateFactory.getSession().remove(pillar);
                    }
                    return;
                }
                boolean changed = false;
                if (saltboot.remove("force_redeploy") != null) {
                    changed = true;
                }
                if (saltboot.remove("force_repartition") != null) {
                    changed = true;
                }
                if (changed) {
                    LOG.debug("saltboot redeploy flags removed");
                    if (saltboot.isEmpty() && pillarData.size() == 1) {
                        // Remove pillar completely if we cleared saltboot data and it was the only entry
                        minion.getPillars().remove(pillar);
                        HibernateFactory.getSession().remove(pillar);
                    }
                    else {
                        pillar.setPillar(pillarData);
                    }
                }
            }
        );
    }

    /**
     * Remove saltboot_force_redeploy and saltboot_force_repartition custom info values from the minion
     * @param minion
     */
    private static void removeSaltbootRedeployCustomInfo(MinionServer minion) {
        CustomDataKey saltbootRedeploy = OrgFactory.lookupKeyByLabelAndOrg("saltboot_force_redeploy", minion.getOrg());
        CustomDataValue redeploy = minion.getCustomDataValue(saltbootRedeploy);
        if (redeploy != null) {
            ServerFactory.removeCustomDataValue(minion, saltbootRedeploy);
        }
        CustomDataKey saltbootRepart = OrgFactory.lookupKeyByLabelAndOrg("saltboot_force_repartition", minion.getOrg());
        CustomDataValue repart = minion.getCustomDataValue(saltbootRepart);
        if (repart != null) {
            ServerFactory.removeCustomDataValue(minion, saltbootRepart);
        }
        if (redeploy != null || repart != null) {
            LOG.debug("saltboot custom info redeploy flags removed");
        }
    }
}
