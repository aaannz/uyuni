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
import com.redhat.rhn.domain.org.Org;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Partitioning and image configuration of saltboot terminals. Configs form a hierarchy: a config only holds what
 * it changes against its parent, the effective values are the merge of the chain from the root
 * (see {@link SaltbootConfigResolver}). Replaces the data of the saltboot formula.
 */
@Entity
@Table(name = "suseSaltbootConfig")
public class SaltbootConfig extends BaseDomainHelper {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne
    @JoinColumn(name = "org_id", nullable = false)
    private Org org;

    @Column(name = "name", nullable = false)
    private String name;

    @ManyToOne
    @JoinColumn(name = "parent_id")
    private SaltbootConfig parent;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "partitioning", columnDefinition = "jsonb")
    private Map<String, Object> partitioning;

    @Column(name = "kernel_action")
    private String kernelAction;

    @Column(name = "terminal_kernel_parameters")
    private String terminalKernelParameters;

    @Column(name = "freeze_image")
    private Boolean freezeImage;

    /**
     * Default constructor, for Hibernate.
     */
    protected SaltbootConfig() {
    }

    /**
     * Creates an empty config.
     *
     * @param orgIn the owning organization
     * @param nameIn the name, unique within the organization
     */
    public SaltbootConfig(Org orgIn, String nameIn) {
        this.org = orgIn;
        this.name = nameIn;
    }

    public Long getId() {
        return id;
    }

    public Org getOrg() {
        return org;
    }

    public String getName() {
        return name;
    }

    public void setName(String nameIn) {
        this.name = nameIn;
    }

    public SaltbootConfig getParent() {
        return parent;
    }

    public void setParent(SaltbootConfig parentIn) {
        this.parent = parentIn;
    }

    /**
     * @return the possibly partial partitioning keyed by disk and partition symbolic IDs, a null value being a
     * tombstone that removes the inherited entry. Null when the config does not touch the partitioning.
     */
    public Map<String, Object> getPartitioning() {
        return partitioning;
    }

    /**
     * @param partitioningIn the possibly partial partitioning, copied to keep the entity free of caller changes
     */
    public void setPartitioning(Map<String, Object> partitioningIn) {
        this.partitioning = partitioningIn == null ? null : new LinkedHashMap<>(partitioningIn);
    }

    public String getKernelAction() {
        return kernelAction;
    }

    public void setKernelAction(String kernelActionIn) {
        this.kernelAction = kernelActionIn;
    }

    public String getTerminalKernelParameters() {
        return terminalKernelParameters;
    }

    public void setTerminalKernelParameters(String terminalKernelParametersIn) {
        this.terminalKernelParameters = terminalKernelParametersIn;
    }

    public Boolean getFreezeImage() {
        return freezeImage;
    }

    public void setFreezeImage(Boolean freezeImageIn) {
        this.freezeImage = freezeImageIn;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof SaltbootConfig other &&
                Objects.equals(org, other.org) && Objects.equals(name, other.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(org, name);
    }
}
