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

import static com.suse.manager.autoinstallation.saltboot.SaltbootTestUtils.addToGroup;
import static com.suse.manager.autoinstallation.saltboot.SaltbootTestUtils.createConfig;
import static com.suse.manager.autoinstallation.saltboot.SaltbootTestUtils.createGroup;
import static com.suse.manager.autoinstallation.saltboot.SaltbootTestUtils.createMinion;
import static com.suse.manager.autoinstallation.saltboot.SaltbootTestUtils.createProxy;
import static com.suse.manager.autoinstallation.saltboot.SaltbootTestUtils.layout;
import static com.suse.manager.autoinstallation.saltboot.SaltbootTestUtils.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.redhat.rhn.domain.formula.FormulaFactory;
import com.redhat.rhn.domain.server.ManagedServerGroup;
import com.redhat.rhn.domain.server.MinionServer;
import com.redhat.rhn.domain.server.Pillar;
import com.redhat.rhn.domain.server.Server;
import com.redhat.rhn.testing.BaseTestCaseWithUser;
import com.redhat.rhn.testing.TestUtils;

import com.suse.manager.autoinstallation.saltboot.SaltbootTerminalResolver.TerminalSettings;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Tests for {@link SaltbootTerminalResolver}.
 */
public class SaltbootTerminalResolverTest extends BaseTestCaseWithUser {

    private static void assignFormula(ManagedServerGroup group, String category) {
        group.getPillars().add(new Pillar(category, new HashMap<>(), group));
        TestUtils.saveAndFlush(group);
    }

    @Test
    public void testFormulaDetectionOnGroups() {
        ManagedServerGroup plain = createGroup(user, "plain");
        ManagedServerGroup branch = createGroup(user, "branch");
        ManagedServerGroup hwtype = createGroup(user, "hwtype");
        assignFormula(branch, FormulaFactory.SALTBOOT_PILLAR);
        assignFormula(hwtype, FormulaFactory.PREFIX + FormulaFactory.SALTBOOT);

        assertFalse(SaltbootTerminalResolver.hasSaltbootFormula(plain));
        assertTrue(SaltbootTerminalResolver.hasSaltbootFormula(branch));
        assertTrue(SaltbootTerminalResolver.hasSaltbootFormula(hwtype));
    }

    @Test
    public void testFormulaDetectionOnTerminals() {
        MinionServer free = createMinion(user);
        MinionServer viaGroup = createMinion(user);
        MinionServer direct = createMinion(user);
        ManagedServerGroup hwtype = createGroup(user, "hwtype");
        assignFormula(hwtype, FormulaFactory.PREFIX + FormulaFactory.SALTBOOT);
        addToGroup(viaGroup, hwtype);
        direct.getPillars().add(new Pillar(FormulaFactory.PREFIX + FormulaFactory.SALTBOOT, new HashMap<>(), direct));
        TestUtils.saveAndFlush(direct);

        assertFalse(SaltbootTerminalResolver.isFormulaManaged(free));
        assertTrue(SaltbootTerminalResolver.isFormulaManaged(viaGroup));
        assertFalse(SaltbootTerminalResolver.hasSaltbootFormula(viaGroup));
        assertTrue(SaltbootTerminalResolver.isFormulaManaged(direct));
        assertTrue(SaltbootTerminalResolver.hasSaltbootFormula(direct));
    }

    @Test
    public void testTerminalWithoutSaltbootData() {
        MinionServer minion = createMinion(user);

        TerminalSettings settings = SaltbootTerminalResolver.resolve(minion);

        assertFalse(settings.configResolution().isResolved());
        assertFalse(settings.configResolution().isTie());
        assertTrue(settings.effective().isEmpty());
        assertTrue(settings.branch().isEmpty());
        assertTrue(settings.problems().isEmpty());
    }

    @Test
    public void testGroupConfigAndBranch() throws Exception {
        MinionServer minion = createMinion(user);
        ManagedServerGroup branchGroup = createGroup(user, "branch");
        ManagedServerGroup hwtype = createGroup(user, "hwtype");
        addToGroup(minion, branchGroup);
        addToGroup(minion, hwtype);
        Server proxy = createProxy(user, "proxy.example.com");
        SaltbootBranch branch = new SaltbootBranch(branchGroup);
        branch.setProxy(proxy);
        SaltbootFactory.save(branch);
        SaltbootConfig config = createConfig(user, "hw", null, layout("JeOS", "1.0.0"));
        config.setKernelAction("reboot");
        SaltbootFactory.save(new SaltbootConfigAssignment(config, hwtype, 0));

        TerminalSettings settings = SaltbootTerminalResolver.resolve(minion);

        assertEquals(config.getId(), settings.configResolution().configId());
        assertEquals("reboot", settings.effective().orElseThrow().kernelAction());
        assertEquals(layout("JeOS", "1.0.0"), settings.effective().orElseThrow().partitioning());
        assertEquals(branch, settings.branch().orElseThrow());
        assertEquals(Optional.of("proxy.example.com"), SaltbootTerminalResolver.proxyFqdn(branch));
        assertTrue(settings.problems().isEmpty());
    }

    @Test
    public void testSystemConfigWinsOverGroupConfigs() {
        MinionServer minion = createMinion(user);
        ManagedServerGroup hwtype = createGroup(user, "hwtype");
        addToGroup(minion, hwtype);
        SaltbootConfig groupConfig = createConfig(user, "group", null, layout("Group", "1.0.0"));
        SaltbootConfig systemConfig = createConfig(user, "system", groupConfig, layout("System", "2.0.0"));
        SaltbootFactory.save(new SaltbootConfigAssignment(groupConfig, hwtype, 0));
        SaltbootFactory.save(new SaltbootConfigAssignment(systemConfig, minion));

        TerminalSettings settings = SaltbootTerminalResolver.resolve(minion);

        assertEquals(systemConfig.getId(), settings.configResolution().configId());
    }

    @Test
    public void testMostDerivedConfigOfTheGroupsWins() {
        MinionServer minion = createMinion(user);
        ManagedServerGroup hwtype = createGroup(user, "hwtype");
        ManagedServerGroup rollout = createGroup(user, "rollout-b");
        addToGroup(minion, hwtype);
        addToGroup(minion, rollout);
        SaltbootConfig root = createConfig(user, "hw", null, layout("JeOS", "7.1.0"));
        SaltbootConfig rolloutB = createConfig(user, "rollout-b", root,
                map("disk1", map("partitions",
                        map("p2", map("image_version", "7.2.0")))));
        SaltbootFactory.save(new SaltbootConfigAssignment(root, hwtype, 100));
        SaltbootFactory.save(new SaltbootConfigAssignment(rolloutB, rollout, 0));

        TerminalSettings settings = SaltbootTerminalResolver.resolve(minion);

        assertEquals(rolloutB.getId(), settings.configResolution().configId());
        Object disk1 = settings.effective().orElseThrow().partitioning().get("disk1");
        Object partitions = ((Map<String, Object>) disk1).get("partitions");
        Object p2 = ((Map<String, Object>) partitions).get("p2");
        assertEquals("7.2.0", ((Map<String, Object>) p2).get("image_version"));
        assertEquals("JeOS", ((Map<String, Object>) p2).get("image"));
    }

    @Test
    public void testUnrelatedConfigsWithEqualPriorityAreAProblem() {
        MinionServer minion = createMinion(user);
        ManagedServerGroup first = createGroup(user, "first");
        ManagedServerGroup second = createGroup(user, "second");
        addToGroup(minion, first);
        addToGroup(minion, second);
        SaltbootFactory.save(new SaltbootConfigAssignment(createConfig(user, "a", null, null), first, 1));
        SaltbootFactory.save(new SaltbootConfigAssignment(createConfig(user, "b", null, null), second, 1));

        TerminalSettings settings = SaltbootTerminalResolver.resolve(minion);

        assertTrue(settings.configResolution().isTie());
        assertTrue(settings.effective().isEmpty());
        assertEquals(1, settings.problems().size());
        assertTrue(settings.problems().get(0).contains("a, b") || settings.problems().get(0).contains("b, a"));
    }

    @Test
    public void testSeveralBranchesAreAProblem() {
        MinionServer minion = createMinion(user);
        ManagedServerGroup first = createGroup(user, "branch-a");
        ManagedServerGroup second = createGroup(user, "branch-b");
        addToGroup(minion, first);
        addToGroup(minion, second);
        SaltbootFactory.save(new SaltbootBranch(first));
        SaltbootFactory.save(new SaltbootBranch(second));

        TerminalSettings settings = SaltbootTerminalResolver.resolve(minion);

        assertEquals(2, settings.branches().size());
        assertTrue(settings.branch().isEmpty());
        assertEquals(1, settings.problems().size());
    }

    @Test
    public void testProxyWithoutFqdnHasNoDownloadServer() {
        ManagedServerGroup group = createGroup(user, "branch");
        SaltbootBranch branch = new SaltbootBranch(group);

        assertTrue(SaltbootTerminalResolver.proxyFqdn(branch).isEmpty());
    }
}
