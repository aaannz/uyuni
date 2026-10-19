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

package com.suse.manager.webui.services.pillar;

import static com.suse.manager.autoinstallation.saltboot.SaltbootTestUtils.addToGroup;
import static com.suse.manager.autoinstallation.saltboot.SaltbootTestUtils.createConfig;
import static com.suse.manager.autoinstallation.saltboot.SaltbootTestUtils.createGroup;
import static com.suse.manager.autoinstallation.saltboot.SaltbootTestUtils.createMinion;
import static com.suse.manager.autoinstallation.saltboot.SaltbootTestUtils.createProxy;
import static com.suse.manager.autoinstallation.saltboot.SaltbootTestUtils.layout;
import static com.suse.manager.autoinstallation.saltboot.SaltbootTestUtils.makeOrgAdmin;
import static com.suse.manager.autoinstallation.saltboot.SaltbootTestUtils.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.redhat.rhn.domain.formula.FormulaFactory;
import com.redhat.rhn.domain.notification.UserNotificationFactory;
import com.redhat.rhn.domain.notification.types.NotificationType;
import com.redhat.rhn.domain.server.ManagedServerGroup;
import com.redhat.rhn.domain.server.MinionServer;
import com.redhat.rhn.domain.server.Pillar;
import com.redhat.rhn.domain.user.User;
import com.redhat.rhn.testing.BaseTestCaseWithUser;
import com.redhat.rhn.testing.TestUtils;

import com.suse.manager.autoinstallation.saltboot.SaltbootBranch;
import com.suse.manager.autoinstallation.saltboot.SaltbootConfig;
import com.suse.manager.autoinstallation.saltboot.SaltbootConfigAssignment;
import com.suse.manager.autoinstallation.saltboot.SaltbootConfigResolver.EffectiveConfig;
import com.suse.manager.autoinstallation.saltboot.SaltbootConfigResolver.Resolution;
import com.suse.manager.autoinstallation.saltboot.SaltbootFactory;
import com.suse.manager.autoinstallation.saltboot.SaltbootTerminal;
import com.suse.manager.autoinstallation.saltboot.SaltbootTerminalResolver.TerminalSettings;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tests for {@link SaltbootPillarGenerator}.
 */
public class SaltbootPillarGeneratorTest extends BaseTestCaseWithUser {

    private static TerminalSettings settings(EffectiveConfig effective, SaltbootBranch... branches) {
        Resolution resolution = new Resolution(effective == null ? null : 1L, List.of());
        return new TerminalSettings(resolution, Optional.ofNullable(effective), List.of(branches), List.of());
    }

    private static long saltbootNotifications(User user) {
        return UserNotificationFactory.listAllByUser(user).stream()
                .filter(n -> n.getMessage().getType() == NotificationType.SALTBOOT_CONFIGURATION_PROBLEM)
                .count();
    }

    // ---- buildPillar, free of persistence ----

    @Test
    public void testNothingAppliesNothingIsGenerated() {
        assertTrue(SaltbootPillarGenerator.buildPillar(settings(null), Optional.empty()).isEmpty());
    }

    @Test
    public void testConfigWithoutOptionalValuesStillGetsTheSaltbootDictionary() {
        EffectiveConfig effective = new EffectiveConfig(Map.of(), null, null, null);

        Map<String, Object> pillar = SaltbootPillarGenerator.buildPillar(settings(effective), Optional.empty());

        // the states use the presence of the saltboot dictionary as the managed switch
        assertEquals(map("saltboot", Map.of()), pillar);
    }

    @Test
    public void testEffectiveValuesAreEmittedUnderTheKeysOfTheStates() {
        EffectiveConfig effective = new EffectiveConfig(layout("JeOS", "1.0.0"), "reboot", "quiet", true);

        Map<String, Object> pillar = SaltbootPillarGenerator.buildPillar(settings(effective), Optional.empty());

        assertEquals(layout("JeOS", "1.0.0"), pillar.get("partitioning"));
        assertEquals("quiet", pillar.get("terminal_kernel_parameters"));
        assertEquals(map("kernel_action", "reboot", "freeze_image", true), pillar.get("saltboot"));
    }

    @Test
    public void testForceFlagsAreEmittedOnlyWhenSet() {
        EffectiveConfig effective = new EffectiveConfig(Map.of(), null, null, null);
        SaltbootTerminal terminal = new SaltbootTerminal(1L);

        Map<String, Object> unset = SaltbootPillarGenerator.buildPillar(settings(effective), Optional.of(terminal));
        assertEquals(Map.of(), unset.get("saltboot"));

        terminal.setForceRedeploy(true);
        terminal.setForceRepartition(true);
        Map<String, Object> set = SaltbootPillarGenerator.buildPillar(settings(effective), Optional.of(terminal));
        assertEquals(map("force_redeploy", true, "force_repartition", true), set.get("saltboot"));
    }

    @Test
    public void testBlockedTerminalGetsNoPartitioning() {
        Resolution tie = new Resolution(null, List.of(1L, 2L));
        TerminalSettings blocked = new TerminalSettings(tie, Optional.empty(), List.of(), List.of("tie"));

        assertTrue(SaltbootPillarGenerator.buildPillar(blocked, Optional.empty()).isEmpty());
    }

    // ---- generation from the database ----

    @Test
    public void testPillarOfAGroupConfigAndBranch() throws Exception {
        MinionServer minion = createMinion(user);
        ManagedServerGroup branchGroup = createGroup(user, "branch");
        ManagedServerGroup hwtype = createGroup(user, "hwtype");
        addToGroup(minion, branchGroup);
        addToGroup(minion, hwtype);
        SaltbootBranch branch = new SaltbootBranch(branchGroup);
        branch.setProxy(createProxy(user, "proxy.example.com"));
        SaltbootFactory.save(branch);
        SaltbootConfig config = createConfig(user, "hw", null, layout("JeOS", "1.0.0"));
        config.setKernelAction("fail");
        SaltbootFactory.save(new SaltbootConfigAssignment(config, hwtype, 0));

        Pillar pillar = SaltbootPillarGenerator.INSTANCE.generatePillarData(minion).orElseThrow();

        assertEquals(SaltbootPillarGenerator.CATEGORY, pillar.getCategory());
        assertEquals(map("download_server", "proxy.example.com", "kernel_action", "fail"),
                pillar.getPillar().get("saltboot"));
        assertEquals(layout("JeOS", "1.0.0"), pillar.getPillar().get("partitioning"));
        assertEquals(pillar, minion.getPillarByCategory("saltboot").orElseThrow());
    }

    @Test
    public void testRegenerationUpdatesAndRemovesThePillar() {
        MinionServer minion = createMinion(user);
        ManagedServerGroup hwtype = createGroup(user, "hwtype");
        addToGroup(minion, hwtype);
        SaltbootConfig config = createConfig(user, "hw", null, layout("JeOS", "1.0.0"));
        SaltbootConfigAssignment assignment = SaltbootFactory.save(new SaltbootConfigAssignment(config, hwtype, 0));
        SaltbootPillarGenerator.INSTANCE.generatePillarData(minion);

        config.setPartitioning(layout("JeOS", "2.0.0"));
        SaltbootFactory.save(config);
        Pillar updated = SaltbootPillarGenerator.INSTANCE.generatePillarData(minion).orElseThrow();
        assertEquals(layout("JeOS", "2.0.0"), updated.getPillar().get("partitioning"));

        SaltbootFactory.remove(assignment);
        assertTrue(SaltbootPillarGenerator.INSTANCE.generatePillarData(minion).isEmpty());
        assertTrue(minion.getPillarByCategory("saltboot").isEmpty());
    }

    @Test
    public void testNothingIsGeneratedWhileAFormulaApplies() {
        MinionServer minion = createMinion(user);
        ManagedServerGroup hwtype = createGroup(user, "hwtype");
        addToGroup(minion, hwtype);
        SaltbootConfig config = createConfig(user, "hw", null, layout("JeOS", "1.0.0"));
        SaltbootFactory.save(new SaltbootConfigAssignment(config, hwtype, 0));
        assertTrue(SaltbootPillarGenerator.INSTANCE.generatePillarData(minion).isPresent());

        hwtype.getPillars().add(new Pillar(FormulaFactory.PREFIX + FormulaFactory.SALTBOOT, new HashMap<>(), hwtype));
        TestUtils.saveAndFlush(hwtype);

        assertTrue(SaltbootPillarGenerator.INSTANCE.generatePillarData(minion).isEmpty());
        assertTrue(minion.getPillarByCategory("saltboot").isEmpty());
    }

    @Test
    public void testForceFlagsComeFromTheTerminalState() {
        MinionServer minion = createMinion(user);
        SaltbootConfig config = createConfig(user, "single", null, layout("JeOS", "1.0.0"));
        SaltbootFactory.save(new SaltbootConfigAssignment(config, minion));
        SaltbootTerminal terminal = SaltbootFactory.lookupOrCreateTerminal(minion.getId());
        terminal.setForceRedeploy(true);
        SaltbootFactory.save(terminal);

        Pillar pillar = SaltbootPillarGenerator.INSTANCE.generatePillarData(minion).orElseThrow();

        assertEquals(map("force_redeploy", true), pillar.getPillar().get("saltboot"));
    }

    @Test
    public void testAmbiguousConfigsBlockThePartitioningAndNotifyOnce() {
        makeOrgAdmin(user);
        MinionServer minion = createMinion(user);
        ManagedServerGroup first = createGroup(user, "first");
        ManagedServerGroup second = createGroup(user, "second");
        addToGroup(minion, first);
        addToGroup(minion, second);
        SaltbootConfigAssignment firstAssignment = SaltbootFactory.save(
                new SaltbootConfigAssignment(createConfig(user, "a", null, layout("A", "1")), first, 1));
        SaltbootFactory.save(new SaltbootConfigAssignment(createConfig(user, "b", null, layout("B", "1")), second, 1));
        long before = saltbootNotifications(user);

        assertTrue(SaltbootPillarGenerator.INSTANCE.generatePillarData(minion).isEmpty());
        assertTrue(SaltbootPillarGenerator.INSTANCE.generatePillarData(minion).isEmpty());

        assertEquals(before + 1, saltbootNotifications(user));
        assertTrue(SaltbootFactory.lookupTerminal(minion.getId()).orElseThrow().getLastResult()
                .startsWith("config problem: "));

        // a priority resolves the tie and clears the recorded problem
        firstAssignment.setPriority(5);
        SaltbootFactory.save(firstAssignment);
        Pillar pillar = SaltbootPillarGenerator.INSTANCE.generatePillarData(minion).orElseThrow();
        assertEquals(layout("A", "1"), pillar.getPillar().get("partitioning"));
        assertNull(SaltbootFactory.lookupTerminal(minion.getId()).orElseThrow().getLastResult());
        assertFalse(saltbootNotifications(user) > before + 1);
    }
}
