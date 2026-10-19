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
import static com.suse.manager.autoinstallation.saltboot.SaltbootTestUtils.createGroup;
import static com.suse.manager.autoinstallation.saltboot.SaltbootTestUtils.createMinion;
import static com.suse.manager.autoinstallation.saltboot.SaltbootTestUtils.createProxy;
import static com.suse.manager.autoinstallation.saltboot.SaltbootTestUtils.layout;
import static com.suse.manager.autoinstallation.saltboot.SaltbootTestUtils.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.redhat.rhn.domain.formula.FormulaFactory;
import com.redhat.rhn.domain.org.CustomDataKey;
import com.redhat.rhn.domain.org.CustomDataKeyTest;
import com.redhat.rhn.domain.server.CustomDataValueTest;
import com.redhat.rhn.domain.server.ManagedServerGroup;
import com.redhat.rhn.domain.server.MinionServer;
import com.redhat.rhn.domain.server.Pillar;
import com.redhat.rhn.domain.server.ServerFactory;
import com.redhat.rhn.manager.satellite.UpgradeCommand;
import com.redhat.rhn.testing.BaseTestCaseWithUser;
import com.redhat.rhn.testing.TestUtils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * Tests for {@link SaltbootDomainMigration}.
 */
public class SaltbootDomainMigrationTest extends BaseTestCaseWithUser {

    private static final String BRANCH_FORMULA = FormulaFactory.SALTBOOT_PILLAR;
    private static final String CONFIG_FORMULA = FormulaFactory.PREFIX + FormulaFactory.SALTBOOT;

    @BeforeEach
    public void setUp() {
        SaltbootTestUtils.useMockCobbler();
    }

    @AfterEach
    public void tearDown() {
        SaltbootTestUtils.resetCobbler();
    }

    private static void addFormulaData(ManagedServerGroup group, String category, Map<String, Object> data) {
        group.getPillars().add(new Pillar(category, data, group));
        TestUtils.saveAndFlush(group);
    }

    private static void addFormulaData(MinionServer minion, String category, Map<String, Object> data) {
        minion.getPillars().add(new Pillar(category, data, minion));
        ServerFactory.save(minion);
        TestUtils.flushSession();
    }

    @Test
    public void testMigratesTheFormulaData() throws Exception {
        createProxy(user, "proxy.example.com");
        ManagedServerGroup branchGroup = createGroup(user, "branch");
        ManagedServerGroup hwtype = createGroup(user, "HWTYPE:acme");
        MinionServer special = createMinion(user);
        addFormulaData(branchGroup, BRANCH_FORMULA, map("saltboot", map(
                "download_server", "proxy.example.com", "default_boot_image", "JeOS",
                "default_boot_image_version", "1.0.0", "minion_id_naming", "MAC")));
        addFormulaData(hwtype, CONFIG_FORMULA, map("partitioning", layout("JeOS", "1.0.0"),
                "saltboot", map("kernel_action", "reboot")));
        addFormulaData(special, CONFIG_FORMULA, map("partitioning", layout("Special", "3.0.0")));

        SaltbootDomainMigration.migrate();

        SaltbootBranch branch = SaltbootFactory.lookupBranch(branchGroup).orElseThrow();
        assertEquals("JeOS-1.0.0", branch.getDefaultBootImage());
        assertEquals("MAC", branch.getMinionIdNaming());
        assertEquals("proxy.example.com", SaltbootTerminalResolver.proxyFqdn(branch).orElseThrow());
        SaltbootConfig groupConfig = SaltbootFactory.lookupAssignment(hwtype).orElseThrow().getConfig();
        assertEquals(layout("JeOS", "1.0.0"), groupConfig.getPartitioning());
        assertEquals("reboot", groupConfig.getKernelAction());
        SaltbootConfig systemConfig = SaltbootFactory.lookupAssignment(special).orElseThrow().getConfig();
        assertEquals(layout("Special", "3.0.0"), systemConfig.getPartitioning());
    }

    @Test
    public void testFormulaAssignmentsAreLeftAlone() {
        ManagedServerGroup hwtype = createGroup(user, "HWTYPE:acme");
        addFormulaData(hwtype, CONFIG_FORMULA, map("partitioning", layout("JeOS", "1.0.0")));

        SaltbootDomainMigration.migrate();

        assertTrue(hwtype.getPillarByCategory(CONFIG_FORMULA).isPresent());
        assertEquals(layout("JeOS", "1.0.0"), hwtype.getPillarByCategory(CONFIG_FORMULA).orElseThrow()
                .getPillar().get("partitioning"));
    }

    @Test
    public void testMigrationIsIdempotentAndKeepsDomainChanges() {
        ManagedServerGroup hwtype = createGroup(user, "HWTYPE:acme");
        ManagedServerGroup branchGroup = createGroup(user, "branch");
        addFormulaData(hwtype, CONFIG_FORMULA, map("partitioning", layout("JeOS", "1.0.0")));
        addFormulaData(branchGroup, BRANCH_FORMULA, map("saltboot", map("minion_id_naming", "FQDN")));
        SaltbootDomainMigration.migrate();

        // changes made in the domain after the first run must survive further runs
        SaltbootConfig config = SaltbootFactory.lookupAssignment(hwtype).orElseThrow().getConfig();
        config.setPartitioning(layout("JeOS", "9.9.9"));
        SaltbootFactory.save(config);
        SaltbootBranch branch = SaltbootFactory.lookupBranch(branchGroup).orElseThrow();
        branch.setMinionIdNaming("MAC");
        SaltbootFactory.save(branch);

        SaltbootDomainMigration.migrate();
        SaltbootDomainMigration.migrate();

        assertEquals(1, SaltbootFactory.listConfigs(user.getOrg()).size());
        assertEquals(1, SaltbootFactory.listBranches(user.getOrg()).size());
        assertEquals(layout("JeOS", "9.9.9"),
                SaltbootFactory.lookupAssignment(hwtype).orElseThrow().getConfig().getPartitioning());
        assertEquals("MAC", SaltbootFactory.lookupBranch(branchGroup).orElseThrow().getMinionIdNaming());
    }

    @Test
    public void testMinionWithSeveralConfigGroupsGetsEqualPriorities() {
        MinionServer minion = createMinion(user);
        ManagedServerGroup first = createGroup(user, "HWTYPE:one");
        ManagedServerGroup second = createGroup(user, "HWTYPE:two");
        addToGroup(minion, first);
        addToGroup(minion, second);
        addFormulaData(first, CONFIG_FORMULA, map("partitioning", layout("One", "1")));
        addFormulaData(second, CONFIG_FORMULA, map("partitioning", layout("Two", "2")));

        SaltbootDomainMigration.migrate();

        assertEquals(0, SaltbootFactory.lookupAssignment(first).orElseThrow().getPriority());
        assertEquals(0, SaltbootFactory.lookupAssignment(second).orElseThrow().getPriority());
        // the conflict is only visible once the formulas are unassigned
        assertTrue(SaltbootTerminalResolver.isFormulaManaged(minion));
        first.getPillars().clear();
        second.getPillars().clear();
        TestUtils.saveAndFlush(first);
        TestUtils.saveAndFlush(second);
        assertTrue(SaltbootTerminalResolver.resolve(minion).configResolution().isTie());
    }

    @Test
    public void testMigratesLegacyForceFlags() throws Exception {
        MinionServer viaPillar = createMinion(user);
        MinionServer viaCustomInfo = createMinion(user);
        MinionServer untouched = createMinion(user);
        addFormulaData(viaPillar, "tuning-saltboot", map("saltboot", map("force_redeploy", true,
                "force_repartition", true)));
        CustomDataKey redeploy = CustomDataKeyTest.createTestCustomDataKey(user, "saltboot_force_redeploy");
        CustomDataValueTest.createTestCustomDataValue(user, redeploy, viaCustomInfo, "true");

        SaltbootDomainMigration.migrate();

        SaltbootTerminal pillarTerminal = SaltbootFactory.lookupTerminal(viaPillar.getId()).orElseThrow();
        assertTrue(pillarTerminal.isForceRedeploy());
        assertTrue(pillarTerminal.isForceRepartition());
        SaltbootTerminal customInfoTerminal = SaltbootFactory.lookupTerminal(viaCustomInfo.getId()).orElseThrow();
        assertTrue(customInfoTerminal.isForceRedeploy());
        assertFalse(customInfoTerminal.isForceRepartition());
        assertTrue(SaltbootFactory.lookupTerminal(untouched.getId()).isEmpty());
    }

    @Test
    public void testFalseCustomInfoFlagDoesNotCreateTerminalState() throws Exception {
        MinionServer minion = createMinion(user);
        CustomDataKey redeploy = CustomDataKeyTest.createTestCustomDataKey(user, "saltboot_force_redeploy");
        CustomDataValueTest.createTestCustomDataValue(user, redeploy, minion, "false");

        SaltbootDomainMigration.migrate();

        assertTrue(SaltbootFactory.lookupTerminal(minion.getId()).isEmpty());
    }

    @Test
    public void testUpgradeCommandRunsTheMigrationTask() {
        assertEquals("upgrade_satellite_migrate_saltboot_domain", UpgradeCommand.MIGRATE_SALTBOOT_DOMAIN);
    }
}
