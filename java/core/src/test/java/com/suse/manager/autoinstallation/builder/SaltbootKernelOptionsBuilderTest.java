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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.redhat.rhn.domain.server.ServerGroup;

import com.suse.manager.autoinstallation.KernelOptionsList;
import com.suse.manager.autoinstallation.saltboot.SaltbootBranch;
import com.suse.manager.autoinstallation.saltboot.SaltbootTerminal;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Tests the saltboot kernel options builder: the distro constant, the options of the branch per naming mode and
 * the options of a terminal with their precedence.
 */
public class SaltbootKernelOptionsBuilderTest {

    private static final String PROXY = "proxy.example.com";

    private static SaltbootBranch branch(String naming) {
        ServerGroup group = new ServerGroup();
        group.setName("branch1");
        SaltbootBranch branch = new SaltbootBranch(group);
        branch.setMinionIdNaming(naming);
        return branch;
    }

    private static SaltbootKernelOptionsBuilder builder(SaltbootBranch branch, SaltbootTerminal terminal) {
        return new SaltbootKernelOptionsBuilder(PROXY, null, branch, terminal);
    }

    private static SaltbootTerminal terminal(String root, String saltDevice, String params) {
        SaltbootTerminal terminal = new SaltbootTerminal(1L);
        terminal.setRootDevice(root);
        terminal.setSaltDevice(saltDevice);
        terminal.setTerminalKernelParameters(params);
        return terminal;
    }

    private static Set<String> tokens(KernelOptionsList options) {
        return new TreeSet<>(Arrays.asList(options.toString().split(" ")));
    }

    /**
     * The distro options are constant and need neither a tree nor a branch.
     */
    @Test
    public void testDistroOptions() {
        assertEquals("panic=60 splash=silent",
                new SaltbootKernelOptionsBuilder(null, null, null, null).distroOptions(null).toString());
    }

    /**
     * There is no installer update and no installation source for a saltboot image.
     */
    @Test
    public void testSelfUpdateAndMediaOptionsEmpty() {
        SaltbootKernelOptionsBuilder builder = builder(branch(SaltbootBranch.NAMING_HOSTNAME), null);
        assertEquals("", builder.selfUpdateOption(null).toString());
        assertEquals("", builder.mediaOptions("http://example.com/media").toString());
    }

    /**
     * The branch options of the hostname naming have the prefix and the master only.
     */
    @Test
    public void testProfileOptionsHostnameNaming() {
        assertEquals("MINION_ID_PREFIX=branch1 MASTER=" + PROXY,
                builder(branch(SaltbootBranch.NAMING_HOSTNAME), null).profileOptions(null).toString());
    }

    /**
     * Each naming mode and flag adds its option.
     */
    @Test
    public void testProfileOptionsNamingModes() {
        SaltbootBranch branch = branch(SaltbootBranch.NAMING_FQDN);
        branch.setDisableIdPrefix(true);
        branch.setDisableUniqueSuffix(true);
        assertEquals(Set.of("DISABLE_ID_PREFIX=1", "DISABLE_UNIQUE_SUFFIX=1", "MASTER=" + PROXY,
                "MINION_ID_PREFIX=branch1", "USE_FQDN_MINION_ID=1"),
                tokens(builder(branch, null).profileOptions(null)));

        assertTrue(builder(branch(SaltbootBranch.NAMING_HWTYPE), null).profileOptions(null)
                .hasOption("DISABLE_HOSTNAME_ID", "1"));
        assertTrue(builder(branch(SaltbootBranch.NAMING_MAC), null).profileOptions(null)
                .hasOption("USE_MAC_MINION_ID", "1"));
    }

    /**
     * Without a proxy the master is a bare flag, like the saltboot formula always had it.
     */
    @Test
    public void testProfileOptionsWithoutProxy() {
        KernelOptionsList options = new SaltbootKernelOptionsBuilder(null, null,
                branch(SaltbootBranch.NAMING_HOSTNAME), null).profileOptions(null);
        assertTrue(options.hasOption("MASTER"));
        assertNull(options.getValue("MASTER"));
        assertEquals("", new SaltbootKernelOptionsBuilder("", null,
                branch(SaltbootBranch.NAMING_HOSTNAME), null).profileOptions(null).toMap().get("MASTER"));
    }

    /**
     * The default kernel parameters of the branch win over the computed options, quoted values stay intact.
     */
    @Test
    public void testProfileOptionsDefaultParametersWin() {
        SaltbootBranch branch = branch(SaltbootBranch.NAMING_HOSTNAME);
        branch.setDefaultKernelParameters("quiet MASTER=other opt=\"two words\"");
        KernelOptionsList options = builder(branch, null).profileOptions(null);
        assertEquals("other", options.getValue("MASTER"));
        assertEquals("\"two words\"", options.getValue("opt"));
        assertTrue(options.hasOption("quiet"));
        assertEquals("branch1", options.getValue("MINION_ID_PREFIX"));
    }

    /**
     * The branch options need a branch.
     */
    @Test
    public void testProfileOptionsWithoutBranchThrow() {
        assertThrows(KernelOptionsBuilderException.class,
                () -> new SaltbootKernelOptionsBuilder(PROXY, null).profileOptions(null));
    }

    /**
     * The options of a terminal are root, salt device and the terminal parameters on top of the branch options.
     */
    @Test
    public void testSystemOptions() {
        KernelOptionsList options = builder(branch(SaltbootBranch.NAMING_HOSTNAME),
                terminal("/dev/sda1", "/dev/sdb2", "custom=option")).systemOptions(null);
        assertEquals(Set.of("root=/dev/sda1", "salt_device=/dev/sdb2", "custom=option",
                "MINION_ID_PREFIX=branch1", "MASTER=" + PROXY), tokens(options));
    }

    /**
     * Terminal values win over the branch values, and the terminal parameters over root and salt device.
     */
    @Test
    public void testSystemOptionsPrecedence() {
        SaltbootBranch branch = branch(SaltbootBranch.NAMING_HOSTNAME);
        branch.setDefaultKernelParameters("custom=branch");
        KernelOptionsList options = builder(branch, terminal("/dev/sda1", null, "custom=terminal root=/dev/sdc1"))
                .systemOptions(null);
        assertEquals("terminal", options.getValue("custom"));
        assertEquals("/dev/sdc1", options.getValue("root"));
        assertFalse(options.hasOption("salt_device"));
    }

    /**
     * The terminal parameters of a legacy PXE entry carry the complete line, so no root device is needed then.
     */
    @Test
    public void testSystemOptionsWithoutRootDevice() {
        KernelOptionsList options = builder(branch(SaltbootBranch.NAMING_HOSTNAME),
                terminal(null, null, "root=/dev/sda2 quiet")).systemOptions(null);
        assertEquals("/dev/sda2", options.getValue("root"));
        assertTrue(options.hasOption("quiet"));
    }

    /**
     * Missing inputs are rejected.
     */
    @Test
    public void testSystemOptionsMissingInputsThrow() {
        SaltbootBranch branch = branch(SaltbootBranch.NAMING_HOSTNAME);
        assertThrows(KernelOptionsBuilderException.class, () -> builder(branch, null).systemOptions(null));
        assertThrows(KernelOptionsBuilderException.class,
                () -> builder(null, terminal("/dev/sda1", null, null)).systemOptions(null));
        assertThrows(KernelOptionsBuilderException.class,
                () -> builder(branch, terminal(null, "/dev/sdb2", "")).systemOptions(null));
    }

    /**
     * The cobbler map form of the system options.
     */
    @Test
    public void testSystemOptionsAsMap() {
        Map<String, Object> map = builder(branch(SaltbootBranch.NAMING_HOSTNAME),
                terminal("/dev/sda1", null, "flag")).systemOptions(null).toMap();
        assertEquals("/dev/sda1", map.get("root"));
        assertEquals("", map.get("flag"));
        assertEquals(PROXY, map.get("MASTER"));
    }
}
