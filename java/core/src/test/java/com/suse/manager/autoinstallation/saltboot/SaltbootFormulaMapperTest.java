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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.redhat.rhn.domain.formula.FormulaFactory;
import com.redhat.rhn.domain.notification.UserNotificationFactory;
import com.redhat.rhn.domain.notification.types.NotificationType;
import com.redhat.rhn.domain.server.ManagedServerGroup;
import com.redhat.rhn.domain.server.MinionServer;
import com.redhat.rhn.domain.server.Server;
import com.redhat.rhn.domain.server.ServerFQDN;
import com.redhat.rhn.testing.BaseTestCaseWithUser;
import com.redhat.rhn.testing.TestUtils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tests for {@link SaltbootFormulaMapper} and the dual-write of the formula-save hooks in {@link FormulaFactory}.
 */
public class SaltbootFormulaMapperTest extends BaseTestCaseWithUser {

    @BeforeEach
    public void setUp() {
        SaltbootTestUtils.useMockCobbler();
    }

    @AfterEach
    public void tearDown() {
        SaltbootTestUtils.resetCobbler();
    }

    private static Map<String, Object> branchFormula(String downloadServer) {
        return map("saltboot", map(
                "download_server", downloadServer,
                "containerized_proxy", true,
                "default_boot_image", "POS_Image_JeOS7",
                "default_boot_image_version", "7.1.0",
                "default_kernel_parameters", "quiet MY=1",
                "minion_id_naming", "FQDN",
                "disable_id_prefix", true,
                "disable_unique_suffix", false));
    }

    private static Map<String, Object> configFormula() {
        return map("partitioning", layout("JeOS", "1.0.0"),
                "terminal_kernel_parameters", "quiet",
                "saltboot", map("kernel_action", "reboot", "freeze_image", true));
    }

    // ---- branch ----

    @Test
    public void testBranchFromFormula() throws Exception {
        ManagedServerGroup group = createGroup(user, "branch");
        Server proxy = createProxy(user, "proxy.example.com");

        SaltbootBranch branch = SaltbootFormulaMapper.applyBranchFormula(group, branchFormula("proxy.example.com"));

        assertEquals(proxy.getId(), branch.getProxy().getId());
        assertEquals("FQDN", branch.getMinionIdNaming());
        assertTrue(branch.isDisableIdPrefix());
        assertFalse(branch.isDisableUniqueSuffix());
        assertEquals("POS_Image_JeOS7-7.1.0", branch.getDefaultBootImage());
        assertEquals("quiet MY=1", branch.getDefaultKernelParameters());
    }

    @Test
    public void testProxyIsMatchedByFqdnIgnoringCase() throws Exception {
        ManagedServerGroup group = createGroup(user, "branch");
        Server proxy = createProxy(user, "Proxy.Example.COM");

        SaltbootBranch branch = SaltbootFormulaMapper.applyBranchFormula(group, branchFormula("proxy.example.com"));

        assertEquals(proxy.getId(), branch.getProxy().getId());
    }

    @Test
    public void testNonProxyServersAreNotMatched() throws Exception {
        ManagedServerGroup group = createGroup(user, "branch");
        MinionServer minion = createMinion(user);
        ServerFQDN fqdn = new ServerFQDN(minion, "terminal.example.com");
        minion.getFqdns().add(fqdn);
        TestUtils.saveAndFlush(fqdn);

        SaltbootBranch branch = SaltbootFormulaMapper.applyBranchFormula(group,
                branchFormula("terminal.example.com"));

        assertNull(branch.getProxy());
    }

    @Test
    public void testUnknownDownloadServerNotifiesTheOrgAdmins() {
        makeOrgAdmin(user);
        ManagedServerGroup group = createGroup(user, "branch");
        long before = notifications();

        SaltbootBranch branch = SaltbootFormulaMapper.applyBranchFormula(group, branchFormula("nowhere.example.com"));

        assertNull(branch.getProxy());
        assertEquals(before + 1, notifications());
    }

    @Test
    public void testBlankDownloadServerIsNoProblem() {
        makeOrgAdmin(user);
        ManagedServerGroup group = createGroup(user, "branch");
        long before = notifications();

        SaltbootBranch branch = SaltbootFormulaMapper.applyBranchFormula(group, branchFormula(""));

        assertNull(branch.getProxy());
        assertEquals(before, notifications());
    }

    @Test
    public void testBranchDefaultsForMissingOrUnknownValues() {
        ManagedServerGroup group = createGroup(user, "branch");

        SaltbootBranch branch = SaltbootFormulaMapper.applyBranchFormula(group,
                map("saltboot", map("minion_id_naming", "nonsense")));

        assertEquals("Hostname", branch.getMinionIdNaming());
        assertFalse(branch.isDisableIdPrefix());
        assertNull(branch.getDefaultBootImage());
        assertNull(branch.getDefaultKernelParameters());
    }

    @Test
    public void testBootImageWithoutVersion() {
        ManagedServerGroup group = createGroup(user, "branch");

        SaltbootBranch branch = SaltbootFormulaMapper.applyBranchFormula(group,
                map("saltboot", map("default_boot_image", "POS_Image", "default_boot_image_version", "")));

        assertEquals("POS_Image", branch.getDefaultBootImage());
    }

    @Test
    public void testBranchIsUpdatedNotDuplicated() {
        ManagedServerGroup group = createGroup(user, "branch");
        SaltbootFormulaMapper.applyBranchFormula(group, branchFormula(""));

        SaltbootFormulaMapper.applyBranchFormula(group, map("saltboot", map("minion_id_naming", "MAC")));

        assertEquals(1, SaltbootFactory.listBranches(user.getOrg()).size());
        assertEquals("MAC", SaltbootFactory.lookupBranch(group).orElseThrow().getMinionIdNaming());
        assertNull(SaltbootFactory.lookupBranch(group).orElseThrow().getDefaultBootImage());
    }

    // ---- configs ----

    @Test
    public void testGroupConfigFromFormula() {
        ManagedServerGroup group = createGroup(user, "HWTYPE:acme");

        SaltbootConfig config = SaltbootFormulaMapper.applyGroupConfigFormula(group, configFormula());

        assertEquals("HWTYPE:acme", config.getName());
        assertNull(config.getParent());
        assertEquals(layout("JeOS", "1.0.0"), config.getPartitioning());
        assertEquals("quiet", config.getTerminalKernelParameters());
        assertEquals("reboot", config.getKernelAction());
        assertEquals(Boolean.TRUE, config.getFreezeImage());
        SaltbootConfigAssignment assignment = SaltbootFactory.lookupAssignment(group).orElseThrow();
        assertEquals(config, assignment.getConfig());
        assertEquals(0, assignment.getPriority());
    }

    @Test
    public void testNumbersFromTheFormulaFormBecomeIntegers() {
        ManagedServerGroup group = createGroup(user, "HWTYPE:acme");
        Map<String, Object> data = map("partitioning", map("disk1", map("partitions",
                map("p1", map("size_MiB", 500.0, "format", "vfat")))));

        SaltbootConfig config = SaltbootFormulaMapper.applyGroupConfigFormula(group, data);

        Map<String, Object> p1 = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>)
                config.getPartitioning().get("disk1")).get("partitions")).get("p1");
        assertEquals(500, p1.get("size_MiB"));
    }

    @Test
    public void testEmptyAndUnknownFormulaValuesAreDropped() {
        ManagedServerGroup group = createGroup(user, "HWTYPE:acme");

        SaltbootConfig config = SaltbootFormulaMapper.applyGroupConfigFormula(group,
                map("partitioning", map(), "terminal_kernel_parameters", " ",
                        "saltboot", map("kernel_action", "halt", "freeze_image", "yes")));

        assertNull(config.getPartitioning());
        assertNull(config.getTerminalKernelParameters());
        assertNull(config.getKernelAction());
        assertNull(config.getFreezeImage());
    }

    @Test
    public void testGroupConfigIsUpdatedNotDuplicated() {
        ManagedServerGroup group = createGroup(user, "HWTYPE:acme");
        SaltbootConfig first = SaltbootFormulaMapper.applyGroupConfigFormula(group, configFormula());

        SaltbootConfig second = SaltbootFormulaMapper.applyGroupConfigFormula(group,
                map("partitioning", layout("JeOS", "2.0.0")));

        assertEquals(first, second);
        assertEquals(1, SaltbootFactory.listConfigs(user.getOrg()).size());
        assertEquals(layout("JeOS", "2.0.0"), second.getPartitioning());
        assertNull(second.getKernelAction());
    }

    @Test
    public void testConfigNameCollisionGetsASuffix() {
        createConfig(user, "HWTYPE:acme", null, null);
        ManagedServerGroup group = createGroup(user, "HWTYPE:acme");

        SaltbootConfig config = SaltbootFormulaMapper.applyGroupConfigFormula(group, configFormula());

        assertEquals("HWTYPE:acme-2", config.getName());
    }

    @Test
    public void testSystemConfigIsAnIndependentRoot() {
        MinionServer minion = createMinion(user);
        ManagedServerGroup group = createGroup(user, "HWTYPE:acme");
        addToGroup(minion, group);
        SaltbootFormulaMapper.applyGroupConfigFormula(group, map("partitioning", layout("Group", "1.0.0")));

        SaltbootConfig config = SaltbootFormulaMapper.applyServerConfigFormula(minion,
                map("partitioning", layout("System", "9.0.0")));

        assertEquals(minion.getMinionId(), config.getName());
        assertNull(config.getParent());
        assertEquals(config, SaltbootFactory.lookupAssignment(minion).orElseThrow().getConfig());
        // the system value replaces the group value as a whole, as the formula merge does today
        SaltbootTerminalResolver.TerminalSettings settings = SaltbootTerminalResolver.resolve(minion);
        assertEquals(layout("System", "9.0.0"), settings.effective().orElseThrow().partitioning());
    }

    // ---- dual-write in FormulaFactory ----

    @Test
    public void testSavingTheBranchFormulaWritesTheBranch() throws Exception {
        ManagedServerGroup group = createGroup(user, "branch");
        createProxy(user, "proxy.example.com");

        FormulaFactory.saveGroupFormulaData(branchFormula("proxy.example.com"), group, FormulaFactory.SALTBOOT_GROUP);

        SaltbootBranch branch = SaltbootFactory.lookupBranch(group).orElseThrow();
        assertEquals("POS_Image_JeOS7-7.1.0", branch.getDefaultBootImage());
        assertEquals("proxy.example.com", SaltbootTerminalResolver.proxyFqdn(branch).orElseThrow());
    }

    @Test
    public void testSavingTheGroupConfigFormulaWritesTheConfig() {
        ManagedServerGroup group = createGroup(user, "HWTYPE:acme");

        FormulaFactory.saveGroupFormulaData(configFormula(), group, FormulaFactory.SALTBOOT);

        assertEquals(layout("JeOS", "1.0.0"),
                SaltbootFactory.lookupAssignment(group).orElseThrow().getConfig().getPartitioning());
    }

    @Test
    public void testSavingTheSystemConfigFormulaWritesTheConfig() {
        MinionServer minion = createMinion(user);

        FormulaFactory.saveServerFormulaData(configFormula(), minion, FormulaFactory.SALTBOOT);

        assertEquals(layout("JeOS", "1.0.0"),
                SaltbootFactory.lookupAssignment(minion).orElseThrow().getConfig().getPartitioning());
    }

    @Test
    public void testOtherFormulasAreNotMirrored() {
        ManagedServerGroup group = createGroup(user, "other");

        FormulaFactory.saveGroupFormulaData(map("some", "data"), group, "dhcpd");

        assertTrue(SaltbootFactory.lookupBranch(group).isEmpty());
        assertTrue(SaltbootFactory.lookupAssignment(group).isEmpty());
    }

    @Test
    public void testMirrorFailureDoesNotPreventSavingTheFormula() {
        ManagedServerGroup group = createGroup(user, "HWTYPE:acme");
        Map<Object, Object> malformedPartitioning = new HashMap<>();
        malformedPartitioning.put(1, "not a symbolic disk id");

        // data the mapper cannot digest must not make the formula unsavable
        assertDoesNotThrow(() -> FormulaFactory.saveGroupFormulaData(
                map("partitioning", malformedPartitioning), group, FormulaFactory.SALTBOOT));

        assertTrue(group.getPillarByCategory(FormulaFactory.PREFIX + FormulaFactory.SALTBOOT).isPresent());
    }

    @Test
    public void testUnassigningTheBranchFormulaKeepsTheBranch() {
        ManagedServerGroup group = createGroup(user, "branch");
        FormulaFactory.saveGroupFormulaData(branchFormula(""), group, FormulaFactory.SALTBOOT_GROUP);
        assertTrue(SaltbootFactory.lookupBranch(group).isPresent());

        FormulaFactory.saveGroupFormulas(group, List.of());

        assertTrue(group.getPillarByCategory(FormulaFactory.SALTBOOT_PILLAR).isEmpty());
        assertTrue(SaltbootFactory.lookupBranch(group).isPresent());
    }

    private long notifications() {
        return UserNotificationFactory.listAllByUser(user).stream()
                .filter(n -> n.getMessage().getType() == NotificationType.SALTBOOT_CONFIGURATION_PROBLEM)
                .count();
    }
}
