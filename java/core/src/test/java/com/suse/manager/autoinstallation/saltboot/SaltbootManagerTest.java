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
import static com.suse.manager.autoinstallation.saltboot.SaltbootTestUtils.makeOrgAdmin;
import static com.suse.manager.autoinstallation.saltboot.SaltbootTestUtils.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.redhat.rhn.common.security.PermissionException;
import com.redhat.rhn.domain.formula.FormulaFactory;
import com.redhat.rhn.domain.server.ManagedServerGroup;
import com.redhat.rhn.domain.server.MinionServer;
import com.redhat.rhn.domain.server.Pillar;
import com.redhat.rhn.domain.server.Server;
import com.redhat.rhn.domain.server.ServerConstants;
import com.redhat.rhn.domain.server.ServerFactoryTest;
import com.redhat.rhn.domain.server.ServerGroup;
import com.redhat.rhn.domain.user.User;
import com.redhat.rhn.testing.JMockBaseTestCaseWithUser;
import com.redhat.rhn.testing.TestUtils;
import com.redhat.rhn.testing.UserTestUtils;

import com.suse.manager.saltboot.SaltbootException;
import com.suse.manager.webui.services.iface.SaltApi;
import com.suse.manager.webui.services.pillar.SaltbootPillarGenerator;
import com.suse.salt.netapi.datatypes.target.MinionList;

import org.jmock.Expectations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tests for {@link SaltbootManager}.
 */
public class SaltbootManagerTest extends JMockBaseTestCaseWithUser {

    private SaltApi saltApi;
    private SaltbootManager manager;
    private List<String> createdProfiles;
    private List<String> deletedProfiles;
    private SaltbootException deleteFailure;
    private User regular;

    @BeforeEach
    public void setUp() {
        saltApi = mock(SaltApi.class);
        createdProfiles = new ArrayList<>();
        deletedProfiles = new ArrayList<>();
        deleteFailure = null;
        manager = new SaltbootManager(saltApi,
                group -> createdProfiles.add(group.getName()),
                group -> {
                    if (deleteFailure != null) {
                        throw deleteFailure;
                    }
                    deletedProfiles.add(group.getName());
                });
        makeOrgAdmin(user);
        regular = new UserTestUtils.UserBuilder().orgId(user.getOrg().getId()).build();
        context().checking(new Expectations() {{
            allowing(saltApi).refreshPillar(with(any(MinionList.class)));
        }});
    }

    private static void assignFormula(ServerGroup group, String category) {
        group.getPillars().add(new Pillar(category, new HashMap<>(), group));
        TestUtils.saveAndFlush(group);
    }

    // ---- permissions ----

    @Test
    public void testConfigOperationsRequireAnOrgAdmin() {
        SaltbootConfig config = createConfig(user, "root", null, null);
        ManagedServerGroup group = createGroup(user, "hwtype");

        assertThrows(PermissionException.class, () -> manager.createConfig(regular, "x", null, Map.of()));
        assertThrows(PermissionException.class, () -> manager.getConfig(regular, config.getId()));
        assertThrows(PermissionException.class, () -> manager.listConfigs(regular));
        assertThrows(PermissionException.class, () -> manager.getEffectiveConfig(regular, config.getId()));
        assertThrows(PermissionException.class, () -> manager.updateConfig(regular, config.getId(), Map.of()));
        assertThrows(PermissionException.class, () -> manager.deleteConfig(regular, config.getId()));
        assertThrows(PermissionException.class,
                () -> manager.assignConfigToGroup(regular, config.getId(), group.getId(), 0));
        assertThrows(PermissionException.class, () -> manager.unassignConfigFromGroup(regular, group.getId()));
    }

    @Test
    public void testBranchOperationsNeedAccessToTheGroup() {
        ManagedServerGroup group = createGroup(user, "branch");

        assertThrows(PermissionException.class, () -> manager.createBranch(regular, group.getId(), 0L, Map.of()));
    }

    @Test
    public void testTerminalOperationsNeedAccessToTheSystem() {
        MinionServer minion = createMinion(user);

        assertThrows(PermissionException.class, () -> manager.scheduleForceRedeploy(regular, minion.getId(), false));
        assertThrows(PermissionException.class, () -> manager.getTerminalSettings(regular, minion.getId()));
    }

    @Test
    public void testObjectsOfOtherOrgsAreNotVisible() {
        User foreigner = makeOrgAdmin(new UserTestUtils.UserBuilder().build());
        SaltbootConfig config = createConfig(user, "root", null, null);
        ManagedServerGroup group = createGroup(user, "hwtype");

        assertThrows(SaltbootValidationException.class, () -> manager.getConfig(foreigner, config.getId()));
        assertThrows(SaltbootValidationException.class,
                () -> manager.assignConfigToGroup(foreigner, config.getId(), group.getId(), 0));
    }

    // ---- configs ----

    @Test
    public void testCreateConfig() {
        SaltbootConfig root = manager.createConfig(user, "root", null,
                map("partitioning", layout("JeOS", "1.0.0"), "kernel_action", "reboot",
                        "terminal_kernel_parameters", "quiet", "freeze_image", true));
        SaltbootConfig child = manager.createConfig(user, "child", root.getId(), Map.of());

        assertEquals(layout("JeOS", "1.0.0"), root.getPartitioning());
        assertEquals("reboot", root.getKernelAction());
        assertEquals("quiet", root.getTerminalKernelParameters());
        assertEquals(Boolean.TRUE, root.getFreezeImage());
        assertEquals(root, child.getParent());
        assertNull(child.getPartitioning());
        assertEquals(List.of("child", "root"),
                manager.listConfigs(user).stream().map(SaltbootConfig::getName).toList());
    }

    @Test
    public void testCreateConfigValidation() {
        SaltbootConfig existing = createConfig(user, "taken", null, null);

        assertThrows(SaltbootValidationException.class, () -> manager.createConfig(user, " ", null, Map.of()));
        assertThrows(SaltbootValidationException.class, () -> manager.createConfig(user, "taken", null, Map.of()));
        assertThrows(SaltbootValidationException.class, () -> manager.createConfig(user, "a", 999999L, Map.of()));
        assertThrows(SaltbootValidationException.class,
                () -> manager.createConfig(user, "a", null, map("partitioning", map("disk1", map("colour", "red")))));
        assertThrows(SaltbootValidationException.class,
                () -> manager.createConfig(user, "a", null, map("partitioning", "disk1")));
        assertThrows(SaltbootValidationException.class,
                () -> manager.createConfig(user, "a", null, map("kernel_action", "halt")));
        assertThrows(SaltbootValidationException.class,
                () -> manager.createConfig(user, "a", null, map("unknown", "x")));
        assertThrows(SaltbootValidationException.class,
                () -> manager.createConfig(user, "a", null, map("freeze_image", "yes")));
        assertEquals(1, manager.listConfigs(user).size());
        assertEquals(existing, manager.listConfigs(user).get(0));
    }

    @Test
    public void testUpdateConfigChangesOnlyTheGivenValues() {
        SaltbootConfig config = manager.createConfig(user, "root", null,
                map("partitioning", layout("JeOS", "1.0.0"), "kernel_action", "reboot"));

        SaltbootConfig updated = manager.updateConfig(user, config.getId(), map("terminal_kernel_parameters", "quiet"));

        assertEquals("quiet", updated.getTerminalKernelParameters());
        assertEquals("reboot", updated.getKernelAction());
        assertEquals(layout("JeOS", "1.0.0"), updated.getPartitioning());

        manager.updateConfig(user, config.getId(), map("kernel_action", "", "name", "renamed"));
        assertNull(config.getKernelAction());
        assertEquals("renamed", config.getName());

        manager.updateConfig(user, config.getId(), map("partitioning", map()));
        assertNull(config.getPartitioning());
    }

    @Test
    public void testUpdateConfigNameMustStayUnique() {
        createConfig(user, "taken", null, null);
        SaltbootConfig config = createConfig(user, "mine", null, null);

        assertThrows(SaltbootValidationException.class,
                () -> manager.updateConfig(user, config.getId(), map("name", "taken")));
        // keeping the own name is fine
        manager.updateConfig(user, config.getId(), map("name", "mine"));
    }

    @Test
    public void testUpdateConfigParentRules() {
        SaltbootConfig root = createConfig(user, "root", null, null);
        SaltbootConfig child = createConfig(user, "child", root, null);
        SaltbootConfig other = createConfig(user, "other", null, null);

        assertThrows(SaltbootValidationException.class,
                () -> manager.updateConfig(user, root.getId(), map("parent_id", child.getId().intValue())));
        assertThrows(SaltbootValidationException.class,
                () -> manager.updateConfig(user, root.getId(), map("parent_id", root.getId().intValue())));

        manager.updateConfig(user, child.getId(), map("parent_id", other.getId().intValue()));
        assertEquals(other, child.getParent());

        manager.updateConfig(user, child.getId(), map("parent_id", 0));
        assertNull(child.getParent());
    }

    @Test
    public void testDeleteConfigRefusesWhileInUse() {
        SaltbootConfig root = createConfig(user, "root", null, null);
        SaltbootConfig child = createConfig(user, "child", root, null);
        ManagedServerGroup group = createGroup(user, "hwtype");
        SaltbootFactory.save(new SaltbootConfigAssignment(child, group, 0));

        assertThrows(SaltbootValidationException.class, () -> manager.deleteConfig(user, root.getId()));
        assertThrows(SaltbootValidationException.class, () -> manager.deleteConfig(user, child.getId()));

        manager.unassignConfigFromGroup(user, group.getId());
        manager.deleteConfig(user, child.getId());
        manager.deleteConfig(user, root.getId());
        assertTrue(manager.listConfigs(user).isEmpty());
    }

    @Test
    public void testEffectiveConfig() {
        SaltbootConfig root = manager.createConfig(user, "root", null,
                map("partitioning", layout("JeOS", "1.0.0"), "kernel_action", "reboot"));
        SaltbootConfig child = manager.createConfig(user, "child", root.getId(),
                map("partitioning", map("disk1", map("partitions", map("p2", map("image_version", "2.0.0"))))));

        SaltbootConfigResolver.EffectiveConfig effective = manager.getEffectiveConfig(user, child.getId());

        assertEquals("reboot", effective.kernelAction());
        Map<String, Object> disk1 = (Map<String, Object>) effective.partitioning().get("disk1");
        Map<String, Object> p2 = (Map<String, Object>) ((Map<String, Object>) disk1.get("partitions")).get("p2");
        assertEquals("2.0.0", p2.get("image_version"));
        assertEquals("JeOS", p2.get("image"));
    }

    // ---- assignments ----

    @Test
    public void testAssigningToAGroupGeneratesThePillarOfItsMembers() {
        MinionServer member = createMinion(user);
        MinionServer other = createMinion(user);
        ManagedServerGroup hwtype = createGroup(user, "hwtype");
        addToGroup(member, hwtype);
        SaltbootConfig config = createConfig(user, "hw", null, layout("JeOS", "1.0.0"));

        manager.assignConfigToGroup(user, config.getId(), hwtype.getId(), 3);

        assertEquals(3, SaltbootFactory.lookupAssignment(hwtype).orElseThrow().getPriority());
        assertEquals(layout("JeOS", "1.0.0"),
                member.getPillarByCategory(SaltbootPillarGenerator.CATEGORY).orElseThrow()
                        .getPillar().get("partitioning"));
        assertTrue(other.getPillarByCategory(SaltbootPillarGenerator.CATEGORY).isEmpty());

        manager.unassignConfigFromGroup(user, hwtype.getId());
        assertTrue(member.getPillarByCategory(SaltbootPillarGenerator.CATEGORY).isEmpty());
        assertThrows(SaltbootValidationException.class, () -> manager.unassignConfigFromGroup(user, hwtype.getId()));
    }

    @Test
    public void testAssigningAgainReplacesTheAssignment() {
        ManagedServerGroup hwtype = createGroup(user, "hwtype");
        SaltbootConfig first = createConfig(user, "first", null, null);
        SaltbootConfig second = createConfig(user, "second", null, null);

        manager.assignConfigToGroup(user, first.getId(), hwtype.getId(), 1);
        manager.assignConfigToGroup(user, second.getId(), hwtype.getId(), 2);

        SaltbootConfigAssignment assignment = SaltbootFactory.lookupAssignment(hwtype).orElseThrow();
        assertEquals(second, assignment.getConfig());
        assertEquals(2, assignment.getPriority());
        assertTrue(SaltbootFactory.listAssignments(first).isEmpty());
    }

    @Test
    public void testAssigningToASystem() {
        MinionServer minion = createMinion(user);
        SaltbootConfig config = createConfig(user, "single", null, layout("JeOS", "1.0.0"));

        manager.assignConfigToSystem(user, config.getId(), minion.getId());

        assertTrue(minion.getPillarByCategory(SaltbootPillarGenerator.CATEGORY).isPresent());
        manager.unassignConfigFromSystem(user, minion.getId());
        assertTrue(minion.getPillarByCategory(SaltbootPillarGenerator.CATEGORY).isEmpty());
        assertThrows(SaltbootValidationException.class, () -> manager.unassignConfigFromSystem(user, minion.getId()));
    }

    @Test
    public void testAssigningToTraditionalSystemsIsRefused() throws Exception {
        Server traditional = ServerFactoryTest.createTestServer(user, true,
                ServerConstants.getServerGroupTypeEnterpriseEntitled());
        SaltbootConfig config = createConfig(user, "single", null, null);

        assertThrows(SaltbootValidationException.class,
                () -> manager.assignConfigToSystem(user, config.getId(), traditional.getId()));
    }

    @Test
    public void testChangingAConfigRegeneratesThePillarOfDescendantUsers() {
        MinionServer minion = createMinion(user);
        ManagedServerGroup rollout = createGroup(user, "rollout");
        addToGroup(minion, rollout);
        SaltbootConfig root = manager.createConfig(user, "root", null, map("partitioning", layout("JeOS", "1.0.0")));
        SaltbootConfig child = manager.createConfig(user, "child", root.getId(), Map.of());
        manager.assignConfigToGroup(user, child.getId(), rollout.getId(), 0);

        manager.updateConfig(user, root.getId(), map("partitioning", layout("JeOS", "2.0.0")));

        assertEquals(layout("JeOS", "2.0.0"), minion.getPillarByCategory(SaltbootPillarGenerator.CATEGORY)
                .orElseThrow().getPillar().get("partitioning"));
    }

    @Test
    public void testChangesAreRefusedWhileAFormulaOwnsTheTarget() {
        MinionServer minion = createMinion(user);
        ManagedServerGroup hwtype = createGroup(user, "hwtype");
        ManagedServerGroup branch = createGroup(user, "branch");
        SaltbootConfig config = createConfig(user, "hw", null, null);
        assignFormula(hwtype, FormulaFactory.PREFIX + FormulaFactory.SALTBOOT);
        assignFormula(branch, FormulaFactory.SALTBOOT_PILLAR);
        minion.getPillars().add(new Pillar(FormulaFactory.PREFIX + FormulaFactory.SALTBOOT, new HashMap<>(), minion));
        TestUtils.saveAndFlush(minion);

        assertThrows(SaltbootValidationException.class,
                () -> manager.assignConfigToGroup(user, config.getId(), hwtype.getId(), 0));
        assertThrows(SaltbootValidationException.class,
                () -> manager.assignConfigToSystem(user, config.getId(), minion.getId()));
        assertThrows(SaltbootValidationException.class, () -> manager.createBranch(user, branch.getId(), 0L, Map.of()));
        assertThrows(SaltbootValidationException.class,
                () -> manager.scheduleForceRedeploy(user, minion.getId(), true));
        assertTrue(createdProfiles.isEmpty());
    }

    // ---- branches ----

    @Test
    public void testCreateBranch() throws Exception {
        MinionServer member = createMinion(user);
        ManagedServerGroup group = createGroup(user, "branch");
        addToGroup(member, group);
        Server proxy = createProxy(user, "proxy.example.com");

        SaltbootBranch branch = manager.createBranch(user, group.getId(), proxy.getId(),
                map("minion_id_naming", "MAC", "disable_id_prefix", true, "default_boot_image", "JeOS-1.0.0",
                        "default_kernel_parameters", "quiet"));

        assertEquals(proxy.getId(), branch.getProxy().getId());
        assertEquals("MAC", branch.getMinionIdNaming());
        assertTrue(branch.isDisableIdPrefix());
        assertFalse(branch.isDisableUniqueSuffix());
        assertEquals("JeOS-1.0.0", branch.getDefaultBootImage());
        assertEquals("quiet", branch.getDefaultKernelParameters());
        assertEquals(List.of("branch"), createdProfiles);
        assertEquals(Map.of("download_server", "proxy.example.com"),
                member.getPillarByCategory(SaltbootPillarGenerator.CATEGORY).orElseThrow().getPillar()
                        .get("saltboot"));
        assertEquals(List.of(branch), manager.listBranches(user));
        assertEquals(branch, manager.getBranch(user, group.getId()));
    }

    @Test
    public void testCreateBranchValidation() throws Exception {
        ManagedServerGroup group = createGroup(user, "branch");
        MinionServer notAProxy = createMinion(user);

        assertThrows(SaltbootValidationException.class, () -> manager.createBranch(user, 999999L, 0L, Map.of()));
        assertThrows(SaltbootValidationException.class,
                () -> manager.createBranch(user, group.getId(), notAProxy.getId(), Map.of()));
        assertThrows(SaltbootValidationException.class,
                () -> manager.createBranch(user, group.getId(), 0L, map("minion_id_naming", "Nonsense")));
        assertThrows(SaltbootValidationException.class,
                () -> manager.createBranch(user, group.getId(), 0L, map("disable_id_prefix", "yes")));
        assertThrows(SaltbootValidationException.class,
                () -> manager.createBranch(user, group.getId(), 0L, map("unknown", "x")));
        assertThrows(SaltbootValidationException.class, () -> manager.createBranch(user, group.getId(), 0L,
                map("minion_id_naming", "HWType", "disable_unique_suffix", true)));
        assertTrue(SaltbootFactory.lookupBranch(group).isEmpty());

        manager.createBranch(user, group.getId(), 0L, Map.of());
        assertThrows(SaltbootValidationException.class, () -> manager.createBranch(user, group.getId(), 0L, Map.of()));
    }

    @Test
    public void testUpdateBranchChangesOnlyTheGivenValues() throws Exception {
        ManagedServerGroup group = createGroup(user, "branch");
        Server proxy = createProxy(user, "proxy.example.com");
        manager.createBranch(user, group.getId(), proxy.getId(), map("default_boot_image", "JeOS"));
        createdProfiles.clear();

        SaltbootBranch updated = manager.updateBranch(user, group.getId(), map("minion_id_naming", "FQDN"));

        assertEquals("FQDN", updated.getMinionIdNaming());
        assertEquals("JeOS", updated.getDefaultBootImage());
        assertEquals(proxy.getId(), updated.getProxy().getId());
        assertEquals(List.of("branch"), createdProfiles);

        manager.updateBranch(user, group.getId(), map("proxy_id", 0, "default_boot_image", ""));
        assertNull(updated.getProxy());
        assertNull(updated.getDefaultBootImage());
    }

    @Test
    public void testDeleteBranchRemovesTheProfileAndTheSettings() {
        ManagedServerGroup group = createGroup(user, "branch");
        manager.createBranch(user, group.getId(), 0L, Map.of());

        manager.deleteBranch(user, group.getId());

        assertEquals(List.of("branch"), deletedProfiles);
        assertTrue(SaltbootFactory.lookupBranch(group).isEmpty());
        assertThrows(SaltbootValidationException.class, () -> manager.getBranch(user, group.getId()));
    }

    @Test
    public void testDeleteBranchKeepsTheSettingsWhenTheProfileIsInUse() {
        ManagedServerGroup group = createGroup(user, "branch");
        manager.createBranch(user, group.getId(), 0L, Map.of());
        deleteFailure = new SaltbootException("Unable to delete distro, systems are still registered to it");

        SaltbootValidationException e = assertThrows(SaltbootValidationException.class,
                () -> manager.deleteBranch(user, group.getId()));

        assertTrue(e.getMessage().contains("systems are still registered"));
        assertTrue(SaltbootFactory.lookupBranch(group).isPresent());
    }

    // ---- terminals ----

    @Test
    public void testScheduleForceRedeploy() {
        MinionServer minion = createMinion(user);
        SaltbootConfig config = createConfig(user, "single", null, layout("JeOS", "1.0.0"));
        manager.assignConfigToSystem(user, config.getId(), minion.getId());

        manager.scheduleForceRedeploy(user, minion.getId(), false);

        SaltbootTerminal terminal = manager.getTerminalState(user, minion.getId()).orElseThrow();
        assertTrue(terminal.isForceRedeploy());
        assertFalse(terminal.isForceRepartition());
        assertEquals(Boolean.TRUE, ((Map<String, Object>) minion.getPillarByCategory(SaltbootPillarGenerator.CATEGORY)
                .orElseThrow().getPillar().get("saltboot")).get("force_redeploy"));

        manager.scheduleForceRedeploy(user, minion.getId(), true);
        assertTrue(manager.getTerminalState(user, minion.getId()).orElseThrow().isForceRepartition());
    }

    @Test
    public void testTerminalSettingsAndFormulaState() {
        MinionServer minion = createMinion(user);

        assertTrue(manager.getTerminalState(user, minion.getId()).isEmpty());
        assertTrue(manager.getTerminalSettings(user, minion.getId()).effective().isEmpty());
        assertFalse(manager.isFormulaManaged(user, minion.getId()));
    }
}
