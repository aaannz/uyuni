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

import static com.suse.manager.saltboot.SaltbootUtils.makeCobblerName;

import com.redhat.rhn.common.hibernate.HibernateFactory;
import com.redhat.rhn.domain.BaseDomainHelper;
import com.redhat.rhn.domain.image.ImageFile;
import com.redhat.rhn.domain.image.ImageInfo;
import com.redhat.rhn.domain.org.Org;
import com.redhat.rhn.domain.server.ServerGroup;
import com.redhat.rhn.domain.server.ServerGroupFactory;

import com.suse.utils.Strings;

import java.util.Map;
import java.util.Optional;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.ColumnResult;
import javax.persistence.ConstructorResult;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.NamedNativeQuery;
import javax.persistence.NoResultException;
import javax.persistence.OneToOne;
import javax.persistence.SqlResultSetMapping;
import javax.persistence.Table;

@Entity
@Table(name = "suseSaltbootGroup")
@NamedNativeQuery(
        name = "SaltbootGroup.getMaster",
        query = "SELECT pillar->'saltboot'->>'download_server' AS salt_master FROM suseSaltPillar " +
                "WHERE group_id=:group_id"
)
@NamedNativeQuery(
        name = "SaltbootGroup.getDefaultKernelOptions",
        query = "SELECT pillar->'saltboot'->>'kernel_options' AS kernel_options FROM suseSaltPillar " +
                "WHERE group_id=:group_id"
)
@NamedNativeQuery(
        name = "SaltbootGroup.getNaming",
        query = "SELECT CAST(pillar->'saltboot'->'disable_id_prefix' AS boolean) AS disable_prefix," +
                "CAST(pillar->'saltboot'->'disable_unique_suffix' AS boolean) AS disable_suffix," +
                "pillar->'saltboot'->>'minion_id_naming' AS minion_naming FROM suseSaltPillar " +
                "WHERE group_id=:group_id",
        resultSetMapping = "SaltbootGroup.TerminalNaming"
)
@NamedNativeQuery(
        name = "SaltbootGroup.getImage",
        query = "SELECT pillar->'saltboot'->>'default_boot_image' AS image, " +
                "pillar->'saltboot'->>'default_boot_image_version' AS image_version " +
                "FROM suseSaltPillar WHERE group_id = :group_id",
        resultSetMapping = "SaltbootGroup.ImageDetails"
)
@NamedNativeQuery(
        name = "SaltbootGroup.getSaltbootGroupByFQDN",
        query = "SELECT sg.* FROM suseSaltbootGroup AS sg JOIN suseSaltPillar AS sp ON sg.group_id = sp.group_id " +
                "WHERE sp.pillar->'saltboot'->>'download_server'=:fqdn",
        resultClass = SaltbootGroup.class
)
@SqlResultSetMapping(
        name = "SaltbootGroup.ImageDetails",
        classes = @ConstructorResult(
                targetClass = SaltbootGroup.ImageDetails.class,
                columns = {
                        @ColumnResult(name = "image", type = String.class),
                        @ColumnResult(name = "image_version", type = String.class)
                }
        )
)
@SqlResultSetMapping(
        name = "SaltbootGroup.TerminalNaming",
        classes = @ConstructorResult(
                targetClass = SaltbootGroup.TerminalNaming.class,
                columns = {
                        @ColumnResult(name = "disable_prefix", type = Boolean.class),
                        @ColumnResult(name = "disable_suffix", type = Boolean.class),
                        @ColumnResult(name = "minion_naming", type = String.class)
                }
        )
)
public class SaltbootGroup extends BaseDomainHelper {

    @SuppressWarnings("checkstyle:LineLength")
    private static final String GRUB_TEMPLATE = """
menuentry '${cobbler_name}' --class gnu-linux --class gnu --class os {
echo 'Loading kernel ...'
clinux /images/${kernel_file} panic=60 splash=silent ${kernel_options} ${minion_naming} MINION_ID_PREFIX=${branch_name} MASTER=${salt_master}
echo 'Loading initial ramdisk ...'
cinitrd /images/${initrd_file}
echo '...done'
}
""";

    @SuppressWarnings("checkstyle:LineLength")
    private static final String PXELINUX_TEMPLATE = """
LABEL ${cobbler_name}
    MENU LABEL ${cobbler_name}
    kernel /images/${kernel_file}
    append initrd=/images/${initrd_file} panic=60 splash=silent ${kernel_options} ${minion_naming} MINION_ID_PREFIX=${branch_name} MASTER=${salt_master}
    ipappend 2
""";

    @Id
    @Column(name = "id")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "group_id", nullable = false, referencedColumnName = "id")
    private ServerGroup serverGroup;

    @Column(name = "image_name")
    private String imageName;

    @Column(name = "image_version")
    private String imageVersion;

    private SaltbootGroup() {
    }

    /**
     * Constructor for SaltbootGroup based on server group details
     * @param serverGroupIn Server group for the SaltbootGroup
     * @throws SaltbootException when no suitable image is found
     */
    public SaltbootGroup(ServerGroup serverGroupIn) throws SaltbootException {
        this.serverGroup = serverGroupIn;
        updateDataFromGroup();
    }

    /**
     * This is a helper class for NativeQuery results mapping of image details in the SaltbootGroup formula
     */
    public static class ImageDetails {
        private final String name;
        private final String version;

        /**
         * Constructor for ImageDetails helper class to process NativeQuery results
         * @param nameIn Name of the image
         * @param versionIn Version of the image
         */
        public ImageDetails(String nameIn, String versionIn) {
            this.name = nameIn;
            this.version = versionIn;
        }

        public String getName() {
            return name;
        }

        public String getVersion() {
            return version;
        }
    }

    /**
     * This is a helper class for NativeQuery results mapping of terminal naming
     */
    public static class TerminalNaming {
        private final boolean disableIdPrefix;
        private final boolean disableUniqueSuffix;
        private final String minionNaming;

        /**
         * Terminal naming helper class to process NativeQuery results
         * @param disableIdPrefixIn Boolean if id prefix should be disabled
         * @param disableUniqueSuffixIn Boolean if random unique suffix should be disabled
         * @param minionNamingIn Overall terminal naming scheme (HWType, MAC, FQDN, Hostname)
         */
        public TerminalNaming(boolean disableIdPrefixIn, boolean disableUniqueSuffixIn, String minionNamingIn) {
            this.disableIdPrefix = disableIdPrefixIn;
            this.disableUniqueSuffix = disableUniqueSuffixIn;
            this.minionNaming = minionNamingIn;
        }

        @Override
        public String toString() {
            StringBuilder result = new StringBuilder();
            if (this.disableIdPrefix) {
                result.append("DISABLE_ID_PREFIX=1");
            }
            if (this.disableUniqueSuffix) {
                result.append("DISABLE_UNIQUE_SUFFIX=1");
            }
            switch(this.minionNaming) {
                case "FQDN":
                    result.append("USE_FQDN_MINION_ID=1");
                    break;
                case "HWType":
                    result.append("DISABLE_HOSTNAME_ID=1");
                    break;
                case "MAC":
                    result.append("USE_MAC_MINION_ID=1");
                    break;
                case "Hostname":
                default:
            }
            return result.toString();
        }
    }

    /**
     * Trigger refresh of stored image data from the server group saltboot pillar
     */
    public void updateDataFromGroup() {
        ImageDetails image;
        try {
            image = (ImageDetails) HibernateFactory.getSession().
                    getNamedNativeQuery("SaltbootGroup.getImage").
                    setParameter("group_id", serverGroup.getId()).getSingleResult();
        }
        catch (NoResultException e) {
            image = new ImageDetails("", "");
        }
        this.imageName = image.getName();
        this.imageVersion = image.getVersion();
        HibernateFactory.getSession().save(this);
    }

    /**
     * This helper resolves what image should be used as the default boot image
     * if image name with version (and optionally revision) is specified -> lookup concrete image
     * if image name without version -> lookup latest version of the specified image
     * if nothing is specified -> lookup default image
     * @return Optional of ImageInfo of empty is there is no suitable image
     */
    private Optional<ImageInfo> resolveImage() {
        if (imageName != null && !imageName.isEmpty()) {
            if (imageVersion != null && !imageVersion.isEmpty()) {
                return SaltbootImage.lookupImageFromImageString(imageName + "-" + imageVersion, serverGroup.getOrg());
            }
            return SaltbootImage.lookupImageFromImageString(imageName, serverGroup.getOrg());
        }
        return SaltbootImage.getOrgDefaultImage(serverGroup.getOrg());
    }

    private Map<String, String> getValuesMap() {
        ImageInfo image = resolveImage().orElseThrow(
                () -> new SaltbootException("No suitable image found for saltboot group " + serverGroup.getName())
        );
        ImageFile kernel = image.getImageFiles().stream().filter(
                imageFile -> imageFile.getType().equals("kernel")).findFirst().orElseThrow(
                () -> new SaltbootException("Cannot find kernel file for image " + image.getName())
        );
        ImageFile initrd = image.getImageFiles().stream().filter(
                imageFile -> imageFile.getType().equals("initrd")).findFirst().orElseThrow(
                () -> new SaltbootException("Cannot find initrd file for image " + image.getName())
        );
        String name = makeCobblerName(serverGroup.getOrg(), serverGroup.getName());

        String saltMaster = HibernateFactory.getSession().
                getNamedNativeQuery("SaltbootGroup.getMaster").
                setParameter("group_id", serverGroup.getId()).getSingleResult().toString();

        TerminalNaming naming;
        try {
            naming = (TerminalNaming) HibernateFactory.getSession().
                    getNamedNativeQuery("SaltbootGroup.getNaming").
                    setParameter("group_id", serverGroup.getId()).getSingleResult();
        }
        catch (NoResultException e) {
            naming = new TerminalNaming(false, false, "Hostname");
        }

        String kernelOptions = Optional.ofNullable(HibernateFactory.getSession().
                getNamedNativeQuery("SaltbootGroup.getDefaultKernelOptions").
                setParameter("group_id", serverGroup.getId()).getSingleResult()).map(Object::toString).orElse("");

        return Map.of(
                "cobbler_name", name,
                "kernel_file", kernel.getFile(),
                "initrd_file", initrd.getFile(),
                "branch_name", serverGroup.getName(),
                "kernel_options", kernelOptions,
                "minion_naming", naming.toString(),
                "salt_master", saltMaster
        );
    }

    /**
     * Get associated server group
     * @return SaltbootGroup related server group
     */
    public ServerGroup getServerGroup() {
        return serverGroup;
    }

    /**
     * Helper to lookup saltbootgroup based on branch id
     * @param branchId Branch id
     * @param org An organization to lookup in
     * @return Optional of SaltbootGroup or emtpy if not found
     */
    public static Optional<SaltbootGroup> getSaltbootGroupByBranchId(String branchId, Org org) {
        ServerGroup group = ServerGroupFactory.lookupByNameAndOrg(branchId, org);
        if (group == null) {
            return Optional.empty();
        }
        return group.getSaltbootGroup();
    }

    /**
     * Helper to lookup saltbootgroup based on branch proxy fqdn
     * @param fqdn FQDN of the branch proxy
     * @return Optional of SaltbootGroup or emtpy if not found
     */
    public static Optional<SaltbootGroup> getSaltbootGroupByBranchFQDN(String fqdn) {
        if (fqdn.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of((SaltbootGroup) HibernateFactory.getSession().
                    getNamedNativeQuery("SaltbootGroup.getSaltbootGroupByFQDN").
                    setParameter("fqdn", fqdn).getSingleResult());
        }
        catch (NoResultException e) {
            return Optional.empty();
        }
    }

    /**
     * Generate GRUB entry for the SaltbootGroup
     * @return Text of the GRUB entry
     */
    public String getGrubEntry() throws SaltbootException {
        try {
            return Strings.fillTemplate(GRUB_TEMPLATE, getValuesMap());
        }
        catch (NoSuchFieldException e) {
            throw new SaltbootException("Error when processing GRUB template. This is a bug", e);
        }
    }

    /**
     * Generate SYSLINUX PXE entry for the SaltbootGroup
     * @return Text of the PXE entry
     */
    public String getPXEEntry() throws SaltbootException {
        try {
            return Strings.fillTemplate(PXELINUX_TEMPLATE, getValuesMap());
        }
        catch (NoSuchFieldException e) {
            throw new SaltbootException("Error when processing PXELINUX template. This is a bug", e);
        }
    }
}
