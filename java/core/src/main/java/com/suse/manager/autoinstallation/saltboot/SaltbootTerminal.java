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

import java.util.Date;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Deployment state of one saltboot terminal: what the last pxe_update reported and the one-shot force flags.
 */
@Entity
@Table(name = "suseSaltbootTerminal")
public class SaltbootTerminal extends BaseDomainHelper {

    @Id
    @Column(name = "server_id")
    private Long serverId;

    @Column(name = "boot_image")
    private String bootImage;

    @Column(name = "root_device")
    private String rootDevice;

    @Column(name = "salt_device")
    private String saltDevice;

    @Column(name = "terminal_kernel_parameters")
    private String terminalKernelParameters;

    @Column(name = "force_redeploy", nullable = false)
    private boolean forceRedeploy;

    @Column(name = "force_repartition", nullable = false)
    private boolean forceRepartition;

    @Column(name = "last_pxe_update")
    private Date lastPxeUpdate;

    @Column(name = "last_result")
    private String lastResult;

    /**
     * Default constructor, for Hibernate.
     */
    protected SaltbootTerminal() {
    }

    /**
     * Creates the state record of a terminal.
     *
     * @param serverIdIn the id of the terminal system
     */
    public SaltbootTerminal(Long serverIdIn) {
        this.serverId = serverIdIn;
    }

    public Long getServerId() {
        return serverId;
    }

    public String getBootImage() {
        return bootImage;
    }

    public void setBootImage(String bootImageIn) {
        this.bootImage = bootImageIn;
    }

    public String getRootDevice() {
        return rootDevice;
    }

    public void setRootDevice(String rootDeviceIn) {
        this.rootDevice = rootDeviceIn;
    }

    public String getSaltDevice() {
        return saltDevice;
    }

    public void setSaltDevice(String saltDeviceIn) {
        this.saltDevice = saltDeviceIn;
    }

    public String getTerminalKernelParameters() {
        return terminalKernelParameters;
    }

    public void setTerminalKernelParameters(String terminalKernelParametersIn) {
        this.terminalKernelParameters = terminalKernelParametersIn;
    }

    public boolean isForceRedeploy() {
        return forceRedeploy;
    }

    public void setForceRedeploy(boolean forceRedeployIn) {
        this.forceRedeploy = forceRedeployIn;
    }

    public boolean isForceRepartition() {
        return forceRepartition;
    }

    public void setForceRepartition(boolean forceRepartitionIn) {
        this.forceRepartition = forceRepartitionIn;
    }

    public Date getLastPxeUpdate() {
        return lastPxeUpdate;
    }

    public void setLastPxeUpdate(Date lastPxeUpdateIn) {
        this.lastPxeUpdate = lastPxeUpdateIn;
    }

    public String getLastResult() {
        return lastResult;
    }

    public void setLastResult(String lastResultIn) {
        this.lastResult = lastResultIn;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof SaltbootTerminal other && Objects.equals(serverId, other.serverId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(serverId);
    }
}
