/*
 * Copyright (c) 2026 SUSE LLC
 *
 * This software is licensed to you under the GNU General Public License,
 * version 2 (GPLv2). There is NO WARRANTY for this software, express or
 * implied, including the implied warranties of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. You should have received a copy of GPLv2
 * along with this software; if not, see
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.txt.
 */
package com.suse.manager.autoinstallation.builder;

import com.redhat.rhn.domain.kickstart.KickstartableTree;
import com.redhat.rhn.domain.user.User;

import com.suse.manager.autoinstallation.KernelOptionsList;
import com.suse.manager.autoinstallation.saltboot.SaltbootBranch;
import com.suse.manager.autoinstallation.saltboot.SaltbootTerminal;

import org.apache.commons.lang3.StringUtils;
import org.cobbler.Profile;
import org.cobbler.SystemRecord;

/**
 * Kernel options builder for the saltboot install type. Saltboot distros are of the generic cobbler breed, so
 * Uyuni owns the whole command line. The branch and the terminal the options are computed for are bound at
 * construction; a builder without them only supports {@link #distroOptions(KickstartableTree)}.
 */
public class SaltbootKernelOptionsBuilder extends AbstractKernelOptionsBuilder {

    private static final String DISTRO_OPTIONS = "panic=60 splash=silent";

    private final SaltbootBranch branch;
    private final SaltbootTerminal terminal;
    private final String masterFqdn;

    /**
     * Constructor of a builder for distro options only
     * @param serverFqdnIn the kickstart host
     * @param userIn optional user context
     */
    public SaltbootKernelOptionsBuilder(String serverFqdnIn, User userIn) {
        this(serverFqdnIn, userIn, null, null);
    }

    /**
     * Constructor
     * @param proxyFqdnIn the FQDN of the proxy the terminals connect through, the MASTER of the terminals
     * @param proxyCreatorIn the creator of that proxy, optional user context
     * @param branchIn the saltboot branch, null for a distro-only builder
     * @param terminalIn the terminal, null when computing the options of the branch
     */
    public SaltbootKernelOptionsBuilder(String proxyFqdnIn, User proxyCreatorIn, SaltbootBranch branchIn,
                                        SaltbootTerminal terminalIn) {
        super(proxyFqdnIn, proxyCreatorIn);
        this.masterFqdn = proxyFqdnIn;
        this.branch = branchIn;
        this.terminal = terminalIn;
    }

    /**
     * {@inheritDoc}
     *
     * The options of the image distro, the tree is not needed.
     */
    @Override
    public KernelOptionsList distroOptions(KickstartableTree tree) {
        return new KernelOptionsList(DISTRO_OPTIONS);
    }

    /**
     * {@inheritDoc}
     *
     * Not applicable, saltboot images have no installer updates.
     */
    @Override
    public KernelOptionsList selfUpdateOption(KickstartableTree tree) {
        return new KernelOptionsList();
    }

    /**
     * {@inheritDoc}
     *
     * The options of the cobbler group profile of the branch: the minion id prefix, the master (a bare flag when
     * the branch has no proxy) and the minion id naming flags. The default kernel parameters of the branch win
     * over them.
     */
    @Override
    public KernelOptionsList profileOptions(Profile profile) {
        SaltbootBranch bound = require(branch, "branch");
        KernelOptionsList computed = new KernelOptionsList();
        computed.setOptionIfNotPresent("MINION_ID_PREFIX", bound.getGroup().getName());
        if (StringUtils.isEmpty(masterFqdn)) {
            computed.setFlagIfNotPresent("MASTER");
        }
        else {
            computed.setOptionIfNotPresent("MASTER", masterFqdn);
        }
        if (bound.isDisableIdPrefix()) {
            computed.setOptionIfNotPresent("DISABLE_ID_PREFIX", "1");
        }
        if (bound.isDisableUniqueSuffix()) {
            computed.setOptionIfNotPresent("DISABLE_UNIQUE_SUFFIX", "1");
        }
        if (SaltbootBranch.NAMING_FQDN.equals(bound.getMinionIdNaming())) {
            computed.setOptionIfNotPresent("USE_FQDN_MINION_ID", "1");
        }
        else if (SaltbootBranch.NAMING_HWTYPE.equals(bound.getMinionIdNaming())) {
            computed.setOptionIfNotPresent("DISABLE_HOSTNAME_ID", "1");
        }
        else if (SaltbootBranch.NAMING_MAC.equals(bound.getMinionIdNaming())) {
            computed.setOptionIfNotPresent("USE_MAC_MINION_ID", "1");
        }
        return new KernelOptionsList(bound.getDefaultKernelParameters()).addMissingOptions(computed);
    }

    /**
     * {@inheritDoc}
     *
     * Not applicable, saltboot images are not fetched from an installation source.
     */
    @Override
    public KernelOptionsList mediaOptions(String mediaUrl) {
        return new KernelOptionsList();
    }

    /**
     * {@inheritDoc}
     *
     * The complete options of the cobbler system record of the terminal: root and salt device and the terminal
     * kernel parameters, winning over the {@link #profileOptions(Profile) options of the branch}. A terminal
     * without a root device is only accepted with terminal kernel parameters, which then carry the root device.
     */
    @Override
    public KernelOptionsList systemOptions(SystemRecord system) {
        SaltbootTerminal bound = require(terminal, "terminal");
        if (StringUtils.isEmpty(bound.getRootDevice()) && StringUtils.isEmpty(bound.getTerminalKernelParameters())) {
            throw new KernelOptionsBuilderException("terminal has neither a root device nor kernel parameters");
        }
        KernelOptionsList options = new KernelOptionsList(bound.getTerminalKernelParameters());
        if (StringUtils.isNotEmpty(bound.getRootDevice())) {
            options.setOptionIfNotPresent("root", bound.getRootDevice());
        }
        if (StringUtils.isNotEmpty(bound.getSaltDevice())) {
            options.setOptionIfNotPresent("salt_device", bound.getSaltDevice());
        }
        return options.addMissingOptions(profileOptions(null));
    }
}
