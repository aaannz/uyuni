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

import static com.suse.manager.autoinstallation.saltboot.SaltbootTestUtils.createConfig;
import static com.suse.manager.autoinstallation.saltboot.SaltbootTestUtils.createGroup;
import static com.suse.manager.autoinstallation.saltboot.SaltbootTestUtils.createMinion;
import static com.suse.manager.autoinstallation.saltboot.SaltbootTestUtils.createProxy;
import static com.suse.manager.autoinstallation.saltboot.SaltbootTestUtils.layout;
import static com.suse.manager.autoinstallation.saltboot.SaltbootTestUtils.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.redhat.rhn.domain.org.Org;
import com.redhat.rhn.domain.server.ManagedServerGroup;
import com.redhat.rhn.domain.server.MinionServer;
import com.redhat.rhn.domain.server.Server;
import com.redhat.rhn.domain.server.ServerGroupFactory;
import com.redhat.rhn.testing.BaseTestCaseWithUser;
import com.redhat.rhn.testing.TestUtils;
import com.redhat.rhn.testing.UserTestUtils;

import com.suse.manager.autoinstallation.saltboot.SaltbootConfigResolver.ConfigData;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tests for {@link SaltbootFactory} and the mapping of the saltboot entities.
 */
public class SaltbootFactoryTest extends BaseTestCaseWithUser {

    @Test
    public void testBranchRoundTrip() throws Exception {
        ManagedServerGroup group = createGroup(user, "branch1");
        Server proxy = createProxy(user, "proxy1.example.com");

        SaltbootBranch branch = new SaltbootBranch(group);
        branch.setProxy(proxy);
        branch.setMinionIdNaming(SaltbootBranch.NAMING_FQDN);
        branch.setDisableIdPrefix(true);
        branch.setDefaultBootImage("POS_Image_JeOS7-7.1.0");
        branch.setDefaultKernelParameters("quiet MY_OPT=\"a b\"");
        SaltbootFactory.save(branch);
        TestUtils.flushAndClearSession();

        SaltbootBranch loaded = SaltbootFactory.lookupBranch(
                ServerGroupFactory.lookupByIdAndOrg(group.getId(), user.getOrg()))
                .orElseThrow();
        assertNotNull(loaded.getId());
        assertNotNull(loaded.getCreated());
        assertEquals(proxy.getId(), loaded.getProxy().getId());
        assertEquals("FQDN", loaded.getMinionIdNaming());
        assertTrue(loaded.isDisableIdPrefix());
        assertFalse(loaded.isDisableUniqueSuffix());
        assertEquals("POS_Image_JeOS7-7.1.0", loaded.getDefaultBootImage());
        assertEquals("quiet MY_OPT=\"a b\"", loaded.getDefaultKernelParameters());
        assertEquals(Optional.of("proxy1.example.com"), SaltbootTerminalResolver.proxyFqdn(loaded));
    }

    @Test
    public void testBranchDefaults() {
        ManagedServerGroup group = createGroup(user, "branch2");

        SaltbootFactory.save(new SaltbootBranch(group));
        TestUtils.flushAndClearSession();

        SaltbootBranch loaded = SaltbootFactory.listBranches(user.getOrg()).get(0);
        assertEquals("Hostname", loaded.getMinionIdNaming());
        assertFalse(loaded.isDisableIdPrefix());
        assertFalse(loaded.isDisableUniqueSuffix());
        assertNull(loaded.getProxy());
        assertNull(loaded.getDefaultBootImage());
    }

    @Test
    public void testListBranchesOfGroups() {
        ManagedServerGroup withBranch = createGroup(user, "branch3");
        ManagedServerGroup without = createGroup(user, "plain");
        SaltbootFactory.save(new SaltbootBranch(withBranch));

        assertEquals(1, SaltbootFactory.listBranches(List.of(withBranch, without)).size());
        assertTrue(SaltbootFactory.listBranches(List.of(without)).isEmpty());
        assertTrue(SaltbootFactory.listBranches(List.<ManagedServerGroup>of()).isEmpty());
    }

    @Test
    public void testPartitioningKeepsTombstonesAndTypes() {
        Map<String, Object> partitioning = map(
                "disk1", map("partitions", map("p3", null, "p2", map("size_MiB", 20000, "image_version", "7.2.0"))),
                "disk2", null,
                "md0", map("devices", List.of("disk1p1", "disk2p1"), "level", 1));
        SaltbootConfig config = createConfig(user, "child", null, partitioning);
        Long id = config.getId();
        TestUtils.flushAndClearSession();

        Map<String, Object> loaded = SaltbootFactory.lookupConfig(user.getOrg(), id).orElseThrow().getPartitioning();

        Map<String, Object> partitions = (Map<String, Object>) ((Map<String, Object>) loaded.get("disk1"))
                .get("partitions");
        assertTrue(partitions.containsKey("p3"));
        assertNull(partitions.get("p3"));
        assertEquals(20000, ((Number) ((Map<String, Object>) partitions.get("p2")).get("size_MiB")).intValue());
        assertTrue(loaded.containsKey("disk2"));
        assertNull(loaded.get("disk2"));
        assertEquals(List.of("disk1p1", "disk2p1"), ((Map<String, Object>) loaded.get("md0")).get("devices"));
    }

    @Test
    public void testConfigWithoutPartitioningStaysNull() {
        SaltbootConfig config = createConfig(user, "empty", null, null);
        config.setKernelAction("reboot");
        config.setTerminalKernelParameters("quiet");
        config.setFreezeImage(true);
        Long id = config.getId();
        TestUtils.flushAndClearSession();

        SaltbootConfig loaded = SaltbootFactory.lookupConfig(user.getOrg(), id).orElseThrow();
        assertNull(loaded.getPartitioning());
        assertEquals("reboot", loaded.getKernelAction());
        assertEquals("quiet", loaded.getTerminalKernelParameters());
        assertEquals(Boolean.TRUE, loaded.getFreezeImage());
    }

    @Test
    public void testConfigLookupAndHierarchy() {
        SaltbootConfig root = createConfig(user, "root", null, layout("JeOS", "1.0.0"));
        SaltbootConfig child = createConfig(user, "child", root, null);
        createConfig(user, "other", null, null);

        assertEquals(child, SaltbootFactory.lookupConfig(user.getOrg(), "child").orElseThrow());
        assertEquals(child, SaltbootFactory.lookupConfig(user.getOrg(), child.getId()).orElseThrow());
        assertTrue(SaltbootFactory.lookupConfig(user.getOrg(), "missing").isEmpty());
        assertEquals(List.of("child", "other", "root"),
                SaltbootFactory.listConfigs(user.getOrg()).stream().map(SaltbootConfig::getName).toList());
        assertEquals(List.of(child), SaltbootFactory.listChildren(root));

        Map<Long, ConfigData> graph = SaltbootFactory.loadConfigGraph(user.getOrg());
        assertEquals(3, graph.size());
        assertEquals(root.getId(), graph.get(child.getId()).parentId());
        assertNull(graph.get(root.getId()).parentId());
        assertEquals("child", graph.get(child.getId()).name());
    }

    @Test
    public void testConfigsAreScopedByOrg() {
        SaltbootConfig config = createConfig(user, "scoped", null, null);

        Org otherOrg = UserTestUtils.createOrg();

        assertTrue(SaltbootFactory.lookupConfig(otherOrg, config.getId()).isEmpty());
        assertTrue(SaltbootFactory.listConfigs(otherOrg).isEmpty());
    }

    @Test
    public void testAssignments() {
        ManagedServerGroup group = createGroup(user, "hwtype");
        ManagedServerGroup otherGroup = createGroup(user, "rollout");
        MinionServer minion = createMinion(user);
        SaltbootConfig config = createConfig(user, "root", null, null);

        SaltbootFactory.save(new SaltbootConfigAssignment(config, group, 7));
        SaltbootFactory.save(new SaltbootConfigAssignment(config, minion));

        assertEquals(7, SaltbootFactory.lookupAssignment(group).orElseThrow().getPriority());
        assertEquals(config, SaltbootFactory.lookupAssignment(minion).orElseThrow().getConfig());
        assertTrue(SaltbootFactory.lookupAssignment(otherGroup).isEmpty());
        assertEquals(1, SaltbootFactory.listGroupAssignments(List.of(group, otherGroup)).size());
        assertTrue(SaltbootFactory.listGroupAssignments(List.<ManagedServerGroup>of()).isEmpty());
        assertEquals(2, SaltbootFactory.listAssignments(config).size());
    }

    @Test
    public void testTerminalState() {
        MinionServer minion = createMinion(user);

        assertTrue(SaltbootFactory.lookupTerminal(minion.getId()).isEmpty());

        SaltbootTerminal terminal = SaltbootFactory.lookupOrCreateTerminal(minion.getId());
        assertFalse(terminal.isForceRedeploy());
        assertFalse(terminal.isForceRepartition());
        terminal.setForceRedeploy(true);
        terminal.setBootImage("JeOS-1.0.0-1");
        terminal.setLastResult("ok");
        SaltbootFactory.save(terminal);
        TestUtils.flushAndClearSession();

        SaltbootTerminal loaded = SaltbootFactory.lookupOrCreateTerminal(minion.getId());
        assertTrue(loaded.isForceRedeploy());
        assertEquals("JeOS-1.0.0-1", loaded.getBootImage());
        assertEquals("ok", loaded.getLastResult());
    }
}
