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
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.NamedNativeQuery;
import javax.persistence.NoResultException;
import javax.persistence.OneToOne;
import javax.persistence.Query;
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
        name = "SaltbootGroup.getImage",
        query = "SELECT pillar->'saltboot'->>'default_boot_image' AS image, " +
                "pillar->'saltboot'->>'default_boot_image_version' AS image_version " +
                "FROM suseSaltPillar WHERE group_id = :group_id"
)
@NamedNativeQuery(
        name = "SaltbootGroup.getSaltbootGroupByFQDN",
        query = "SELECT sg.* FROM suseSaltbootGroup AS sg JOIN suseSaltPillar AS sp ON sg.group_id = sp.group_id " +
                "WHERE sp.pillar->'saltboot'->>'download_server'=:fqdn",
        resultClass = SaltbootGroup.class
)
public class SaltbootGroup extends BaseDomainHelper {

    private static final String GRUB_TEMPLATE = """
menuentry '${cobbler_name}' --class gnu-linux --class gnu --class os {
echo 'Loading kernel ...'
clinux /images/${kernel_file} ${kernel_options} MINION_ID_PREFIX=${branch_name} MASTER=${salt_master}
echo 'Loading initial ramdisk ...'
cinitrd /images/${initrd_file}
echo '...done'
}
""";

    private static final String PXELINUX_TEMPLATE = """
LABEL ${cobbler_name}
    MENU LABEL ${cobbler_name}
    kernel /images/${kernel_file}
    append initrd=/images/${initrd_file} ${kernel_options} MINION_ID_PREFIX=${branch_name} MASTER=${salt_master}
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
     * Trigger refresh of stored image data from the server group saltboot pillar
     */
    public void updateDataFromGroup() {
        Query query = HibernateFactory.getSession().getNamedNativeQuery("SaltbootGroup.getImage").
                setParameter("group_id", this.serverGroup.getId());
        Object[] row = (Object[]) query.getSingleResult();
        if (row != null) {
            this.imageName = (String) row[0];
            this.imageVersion = (String) row[1];
        }
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

        String kernelOptions = HibernateFactory.getSession().
                getNamedNativeQuery("SaltbootGroup.getDefaultKernelOptions").
                setParameter("group_id", serverGroup.getId()).getSingleResult().toString();

        return Map.of(
                "cobbler_name", name,
                "kernel_file", kernel.getFile(),
                "initrd_file", initrd.getFile(),
                "branch_name", serverGroup.getName(),
                "kernel_options", kernelOptions,
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
