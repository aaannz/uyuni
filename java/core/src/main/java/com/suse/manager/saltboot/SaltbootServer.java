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
import com.redhat.rhn.domain.image.ImageInfoFactory;
import com.redhat.rhn.domain.server.MinionServer;

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
import javax.persistence.ManyToOne;
import javax.persistence.NamedQuery;
import javax.persistence.NoResultException;
import javax.persistence.NonUniqueResultException;
import javax.persistence.OneToOne;
import javax.persistence.Query;
import javax.persistence.Table;

@Entity
@NamedQuery(
        name = "SaltbootServer.lookupByHwAddress",
        query = "FROM SaltbootServer AS s JOIN NetworkInterface AS net ON s.minion = net.server " +
                "WHERE net.hwaddr = :hwAddress"
)
@Table(name = "suseSaltbootServer")
public class SaltbootServer extends BaseDomainHelper {

    @SuppressWarnings("checkstyle:LineLength")
    private static final String GRUB_TEMPLATE = """
menuentry '${cobbler_name}' --class gnu-linux --class gnu --class os {
echo 'Loading kernel ...'
clinux /images/${kernel_file} root=${root_device} salt_device=${salt_device} ${kernel_options} MINION_ID_PREFIX=${branch_name} MASTER=${salt_master}
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
    append initrd=/images/${initrd_file} root=${root_device} salt_device=${salt_device} ${kernel_options} MINION_ID_PREFIX=${branch_name} MASTER=${salt_master}
    ipappend 2
""";

    @Id
    @Column(name = "id")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "server_id", nullable = false, referencedColumnName = "server_id")
    private MinionServer minion;
    @OneToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "saltboot_group_id", nullable = false, referencedColumnName = "id")
    private SaltbootGroup saltbootGroup;
    @Column(name = "root_device", nullable = false)
    private String rootDevice;
    @Column(name = "salt_device")
    private String saltDevice;
    @Column(name = "kernel_parameters")
    private String kernelParameters;
    @ManyToOne
    @JoinColumn(name = "image_id", nullable = false)
    private ImageInfo image;

    private SaltbootServer() {
    }

    /**
     * Constructor for SaltbootServer
     * @param minionIn Terminal Server
     * @param saltbootGroupIn SaltbootGroup to which terminal belongs
     * @param rootDeviceIn Root device
     * @param saltDeviceIn Device with salt configuration
     * @param imageIn Assigned image
     * @param kernelParametersIn additional kernel options
     */
    public SaltbootServer(MinionServer minionIn, SaltbootGroup saltbootGroupIn, String rootDeviceIn,
                          String saltDeviceIn, ImageInfo imageIn, String kernelParametersIn) {
        this.minion = minionIn;
        this.saltbootGroup = saltbootGroupIn;
        this.rootDevice = rootDeviceIn;
        this.saltDevice = saltDeviceIn;
        this.image = imageIn;
        this.kernelParameters = kernelParametersIn;
    }

    /**
     * Constructor for SaltbootServer with data from PXEEvent
     * @param minionIn Terminal Server
     * @param pxeEvent Received PXEEvent data
     */
    public SaltbootServer(MinionServer minionIn, PXEEvent pxeEvent) throws SaltbootException {
        if (pxeEvent.getRoot().isEmpty()) {
            throw new SaltbootException("Root device not specified in PXE event for minion " +
                    minionIn.getMinionId());
        }
        ImageInfo imageIn = ImageInfoFactory.lookupByName(pxeEvent.getBootImage(), "", 0,
                minionIn.getOrg()).orElseThrow(
                () -> new SaltbootException("Unable to find image " + pxeEvent.getBootImage() +
                        " for minion id " + minionIn.getMinionId()));

        SaltbootGroup group = SaltbootGroup.getSaltbootGroupByBranchId(pxeEvent.getSaltbootGroup(), minionIn.getOrg()).
                orElseThrow(
                () -> new SaltbootException("Unable to find saltboot group for the branch id " +
                        pxeEvent.getSaltbootGroup())
        );

        this.minion = minionIn;
        this.saltbootGroup = group;
        this.rootDevice = pxeEvent.getRoot();
        this.saltDevice = pxeEvent.getSaltDevice().orElse(null);
        this.image = imageIn;
        this.kernelParameters = pxeEvent.getKernelParameters().orElse(null);
    }

    private Map<String, String> getValuesMap() {
        ImageFile kernel = image.getImageFiles().stream().filter(
                imageFile -> imageFile.getType().equals("kernel")).findFirst().orElseThrow(
                () -> new SaltbootException("Cannot find kernel file for image " + image.getName())
        );
        ImageFile initrd = image.getImageFiles().stream().filter(
                imageFile -> imageFile.getType().equals("initrd")).findFirst().orElseThrow(
                () -> new SaltbootException("Cannot find initrd file for image " + image.getName())
        );
        String name = makeCobblerName(minion.getOrg(), minion.getMinionId());

        String saltMaster = HibernateFactory.getSession().getNamedQuery("SaltbootGroup.getMaster").
                setParameter("group_id", saltbootGroup.getServerGroup().getId()).uniqueResult().toString();

        return Map.of(
                "cobbler_name", name,
                "kernel_file", kernel.getFile(),
                "initrd_file", initrd.getFile(),
                "branch_name", saltbootGroup.getServerGroup().getName(),
                "kernel_options", Optional.ofNullable(kernelParameters).orElse(""),
                "salt_master", saltMaster,
                "root_device", rootDevice,
                "salt_device", Optional.ofNullable(saltDevice).orElse("")
        );
    }

    /**
     * Update existing SaltbootServer entry from the received PXEEvent
     * @param pxeEvent PXEEvent received from the minion
     * @return Updated SaltbootServer entry
     */
    public SaltbootServer updateFromEvent(PXEEvent pxeEvent) throws SaltbootException {
        if (pxeEvent.getRoot().isEmpty()) {
            throw new SaltbootException("Root device not specified in PXE event for minion " +
                    minion.getMinionId());
        }
        ImageInfo imageIn = ImageInfoFactory.lookupByName(pxeEvent.getBootImage(), "", 0,
                minion.getOrg()).orElseThrow(
                () -> new SaltbootException("Unable to find image " + pxeEvent.getBootImage() +
                        " for minion id " + minion.getMinionId()));

        SaltbootGroup group = SaltbootGroup.getSaltbootGroupByBranchId(pxeEvent.getSaltbootGroup(), minion.getOrg()).
                orElseThrow(() -> new SaltbootException("Unable to find saltboot group for the branch id " +
                        pxeEvent.getSaltbootGroup()));

        this.saltbootGroup = group;
        this.rootDevice = pxeEvent.getRoot();
        this.saltDevice = pxeEvent.getSaltDevice().orElse(null);
        this.image = imageIn;
        this.kernelParameters = pxeEvent.getKernelParameters().orElse(null);
        return this;
    }

    /**
     * Lookup SaltbootServer entry by hwAddress
     * @param hwAddress MAC address of the system
     * @return Optional of SaltbootServer or empty if not found
     * @throws SaltbootException when multiple servers are found
     */
    public static Optional<SaltbootServer> getSaltbootServerByHwAddress(String hwAddress) throws SaltbootException {
        Query query = HibernateFactory.getSession().
                getNamedQuery("SaltbootServer.lookupByHwAddress").
                setParameter("hwAddress", hwAddress);
        try {
            return Optional.of((SaltbootServer) query.getSingleResult());
        }
        catch (NoResultException e) {
            return Optional.empty();
        }
        catch (NonUniqueResultException e) {
            throw new SaltbootException("Multiple servers found for a MAC address", e);
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
