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

import com.redhat.rhn.domain.BaseDomainHelper;
import com.redhat.rhn.domain.server.Server;
import com.redhat.rhn.domain.server.ServerGroup;

import java.util.Objects;
import java.util.Set;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

/**
 * Saltboot settings of a branch: one branch server group, the proxy serving its terminals and the defaults for
 * minion naming, boot image and kernel parameters. Replaces the data of the saltboot-group formula.
 */
@Entity
@Table(name = "suseSaltbootBranch")
public class SaltbootBranch extends BaseDomainHelper {

    public static final String NAMING_FQDN = "FQDN";
    public static final String NAMING_HOSTNAME = "Hostname";
    public static final String NAMING_HWTYPE = "HWType";
    public static final String NAMING_MAC = "MAC";
    public static final Set<String> NAMING_VALUES = Set.of(NAMING_FQDN, NAMING_HOSTNAME, NAMING_HWTYPE, NAMING_MAC);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @OneToOne
    @JoinColumn(name = "group_id", nullable = false)
    private ServerGroup group;

    @ManyToOne
    @JoinColumn(name = "proxy_server_id")
    private Server proxy;

    @Column(name = "minion_id_naming", nullable = false)
    private String minionIdNaming = NAMING_HOSTNAME;

    @Column(name = "disable_id_prefix", nullable = false)
    private boolean disableIdPrefix;

    @Column(name = "disable_unique_suffix", nullable = false)
    private boolean disableUniqueSuffix;

    @Column(name = "default_boot_image")
    private String defaultBootImage;

    @Column(name = "default_kernel_parameters")
    private String defaultKernelParameters;

    /**
     * Default constructor, for Hibernate.
     */
    protected SaltbootBranch() {
    }

    /**
     * Creates the branch settings of a group with the form defaults.
     *
     * @param groupIn the branch server group
     */
    public SaltbootBranch(ServerGroup groupIn) {
        this.group = groupIn;
    }

    public Long getId() {
        return id;
    }

    public ServerGroup getGroup() {
        return group;
    }

    public Server getProxy() {
        return proxy;
    }

    public void setProxy(Server proxyIn) {
        this.proxy = proxyIn;
    }

    public String getMinionIdNaming() {
        return minionIdNaming;
    }

    public void setMinionIdNaming(String minionIdNamingIn) {
        this.minionIdNaming = minionIdNamingIn;
    }

    public boolean isDisableIdPrefix() {
        return disableIdPrefix;
    }

    public void setDisableIdPrefix(boolean disableIdPrefixIn) {
        this.disableIdPrefix = disableIdPrefixIn;
    }

    public boolean isDisableUniqueSuffix() {
        return disableUniqueSuffix;
    }

    public void setDisableUniqueSuffix(boolean disableUniqueSuffixIn) {
        this.disableUniqueSuffix = disableUniqueSuffixIn;
    }

    /**
     * @return lax boot image reference: name, name-version or name-version-revision
     */
    public String getDefaultBootImage() {
        return defaultBootImage;
    }

    public void setDefaultBootImage(String defaultBootImageIn) {
        this.defaultBootImage = defaultBootImageIn;
    }

    /**
     * @return the raw user typed default kernel parameters
     */
    public String getDefaultKernelParameters() {
        return defaultKernelParameters;
    }

    public void setDefaultKernelParameters(String defaultKernelParametersIn) {
        this.defaultKernelParameters = defaultKernelParametersIn;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof SaltbootBranch other && Objects.equals(group, other.group);
    }

    @Override
    public int hashCode() {
        return Objects.hash(group);
    }
}
