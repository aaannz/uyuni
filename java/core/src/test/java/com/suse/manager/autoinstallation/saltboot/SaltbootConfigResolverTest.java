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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.suse.manager.autoinstallation.saltboot.SaltbootConfigResolver.ConfigData;
import com.suse.manager.autoinstallation.saltboot.SaltbootConfigResolver.EffectiveConfig;
import com.suse.manager.autoinstallation.saltboot.SaltbootConfigResolver.GroupAssignment;
import com.suse.manager.autoinstallation.saltboot.SaltbootConfigResolver.Resolution;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tests for {@link SaltbootConfigResolver}, free of any persistence.
 */
public class SaltbootConfigResolverTest {

    /** Builds a map from alternating keys and values, null values allowed (tombstones). */
    private static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            result.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return result;
    }

    private static ConfigData config(long id, Long parentId, Map<String, Object> partitioning) {
        return new ConfigData(id, "config" + id, parentId, partitioning, null, null, null);
    }

    private static Map<Long, ConfigData> graph(ConfigData... configs) {
        Map<Long, ConfigData> result = new HashMap<>();
        for (ConfigData config : configs) {
            result.put(config.id(), config);
        }
        return result;
    }

    /** The HW-POS-4000 root layout of the A/B rollout example of the design. */
    private static Map<String, Object> posLayout() {
        return map("disk1", map(
                "type", "DISK",
                "device", "/dev/sda",
                "disklabel", "gpt",
                "partitions", map(
                        "p1", map("size_MiB", 500, "format", "vfat", "mountpoint", "/boot/efi", "flags", "esp"),
                        "p2", map("size_MiB", 20000, "format", "ext4", "mountpoint", "/",
                                "image", "POS_Image_JeOS7", "image_version", "7.1.0"),
                        "p3", map("format", "ext4", "mountpoint", "/data"))));
    }

    @Test
    public void testChildOnlyChangesWhatItOverrides() {
        ConfigData root = config(1, null, posLayout());
        ConfigData rolloutB = config(2, 1L, map("disk1", map("partitions", map("p2", map("image_version", "7.2.0")))));

        Map<Long, ConfigData> configs = graph(root, rolloutB);
        Map<String, Object> effective = SaltbootConfigResolver.effective(2L, configs).partitioning();

        Map<String, Object> expected = posLayout();
        ((Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) expected.get("disk1"))
                .get("partitions")).get("p2")).put("image_version", "7.2.0");
        assertEquals(expected, effective);
        // the rollout-A style config without changes keeps the root values
        assertEquals(posLayout(), SaltbootConfigResolver.effective(1L, configs).partitioning());
    }

    @Test
    public void testTombstoneRemovesInheritedEntries() {
        ConfigData root = config(1, null, posLayout());
        ConfigData child = config(2, 1L, map("disk1", map("partitions", map("p3", null))));
        ConfigData grandChild = config(3, 2L, map("disk1", null));

        Map<Long, ConfigData> configs = graph(root, child, grandChild);

        Map<String, Object> disk1 = (Map<String, Object>) SaltbootConfigResolver.effective(2L, configs)
                .partitioning().get("disk1");
        Map<String, Object> partitions = (Map<String, Object>) disk1.get("partitions");
        assertEquals(List.of("p1", "p2"), List.copyOf(partitions.keySet()));

        assertTrue(SaltbootConfigResolver.effective(3L, configs).partitioning().isEmpty());
    }

    @Test
    public void testTombstonesInTheRootAreDropped() {
        ConfigData root = config(1, null, map("disk1", map("partitions", map("p1", null, "p2", map("size_MiB", 1)))));

        Map<String, Object> effective = SaltbootConfigResolver.effective(1L, graph(root)).partitioning();

        Map<String, Object> partitions = (Map<String, Object>) ((Map<String, Object>) effective.get("disk1"))
                .get("partitions");
        assertEquals(List.of("p2"), List.copyOf(partitions.keySet()));
    }

    @Test
    public void testNonMapValuesAreReplaced() {
        Map<String, Object> base = map("md0", map("type", "RAID", "devices", List.of("disk1p1", "disk2p1")));
        Map<String, Object> override = map("md0", map("devices", List.of("disk1p2")));

        Map<String, Object> merged = SaltbootConfigResolver.mergePartitioning(base, override);

        assertEquals(map("md0", map("type", "RAID", "devices", List.of("disk1p2"))), merged);
    }

    @Test
    public void testMergeDoesNotModifyItsInput() {
        Map<String, Object> base = posLayout();
        Map<String, Object> override = map("disk1", map("partitions", map("p3", null)));

        SaltbootConfigResolver.mergePartitioning(base, override);

        assertEquals(posLayout(), base);
        assertEquals(map("disk1", map("partitions", map("p3", null))), override);
    }

    @Test
    public void testScalarsInheritFromTheNearestConfigThatSetsThem() {
        ConfigData root = new ConfigData(1L, "root", null, null, "reboot", "quiet", true);
        ConfigData child = new ConfigData(2L, "child", 1L, null, "fail", null, null);
        ConfigData grandChild = new ConfigData(3L, "grandchild", 2L, null, null, null, false);

        EffectiveConfig effective = SaltbootConfigResolver.effective(3L, graph(root, child, grandChild));

        assertEquals("fail", effective.kernelAction());
        assertEquals("quiet", effective.terminalKernelParameters());
        assertEquals(false, effective.freezeImage());
        assertTrue(effective.partitioning().isEmpty());
    }

    @Test
    public void testUnsetScalarsStayNull() {
        EffectiveConfig effective = SaltbootConfigResolver.effective(1L, graph(config(1, null, null)));

        assertNull(effective.kernelAction());
        assertNull(effective.terminalKernelParameters());
        assertNull(effective.freezeImage());
    }

    @Test
    public void testChainOrderAndBrokenChains() {
        ConfigData root = config(1, null, null);
        ConfigData child = config(2, 1L, null);

        assertEquals(List.of(1L, 2L), SaltbootConfigResolver.chain(2L, graph(root, child)).stream()
                .map(ConfigData::id).toList());

        assertThrows(SaltbootValidationException.class, () -> SaltbootConfigResolver.chain(2L, graph(child)));
        assertThrows(SaltbootValidationException.class, () -> SaltbootConfigResolver.chain(9L, graph(root)));

        ConfigData loopA = config(3, 4L, null);
        ConfigData loopB = config(4, 3L, null);
        assertThrows(SaltbootValidationException.class, () -> SaltbootConfigResolver.chain(3L, graph(loopA, loopB)));
    }

    @Test
    public void testWouldCreateCycle() {
        ConfigData root = config(1, null, null);
        ConfigData child = config(2, 1L, null);
        ConfigData grandChild = config(3, 2L, null);
        ConfigData other = config(4, null, null);
        Map<Long, ConfigData> configs = graph(root, child, grandChild, other);

        assertTrue(SaltbootConfigResolver.wouldCreateCycle(1L, 1L, configs));
        assertTrue(SaltbootConfigResolver.wouldCreateCycle(1L, 3L, configs));
        assertFalse(SaltbootConfigResolver.wouldCreateCycle(3L, 1L, configs));
        assertFalse(SaltbootConfigResolver.wouldCreateCycle(1L, 4L, configs));
        assertFalse(SaltbootConfigResolver.wouldCreateCycle(1L, null, configs));
    }

    @Test
    public void testSystemAssignmentWins() {
        Map<Long, ConfigData> configs = graph(config(1, null, null), config(2, null, null));

        Resolution resolution = SaltbootConfigResolver.resolve(2L, List.of(new GroupAssignment(1L, 100)), configs);

        assertEquals(2L, resolution.configId());
        assertFalse(resolution.isTie());
    }

    @Test
    public void testNoAssignmentResolvesToNothing() {
        Resolution resolution = SaltbootConfigResolver.resolve(null, List.of(), graph(config(1, null, null)));

        assertFalse(resolution.isResolved());
        assertFalse(resolution.isTie());
    }

    @Test
    public void testSingleGroupConfig() {
        Map<Long, ConfigData> configs = graph(config(1, null, null));

        Resolution resolution = SaltbootConfigResolver.resolve(null, List.of(new GroupAssignment(1L, 0)), configs);

        assertEquals(1L, resolution.configId());
    }

    @Test
    public void testMostDerivedConfigOfOneChainWinsRegardlessOfPriority() {
        // HWTYPE group has the root, the rollout group the derived config
        Map<Long, ConfigData> configs = graph(config(1, null, null), config(2, 1L, null));

        Resolution resolution = SaltbootConfigResolver.resolve(null,
                List.of(new GroupAssignment(1L, 50), new GroupAssignment(2L, 0)), configs);

        assertEquals(2L, resolution.configId());
    }

    @Test
    public void testAncestorsOfDeeperConfigsAreEliminated() {
        Map<Long, ConfigData> configs = graph(config(1, null, null), config(2, 1L, null), config(3, 2L, null));

        Resolution resolution = SaltbootConfigResolver.resolve(null,
                List.of(new GroupAssignment(1L, 0), new GroupAssignment(2L, 0), new GroupAssignment(3L, 0)),
                configs);

        assertEquals(3L, resolution.configId());
    }

    @Test
    public void testPrioritySeparatesUnrelatedChains() {
        Map<Long, ConfigData> configs = graph(config(1, null, null), config(2, null, null));

        Resolution resolution = SaltbootConfigResolver.resolve(null,
                List.of(new GroupAssignment(1L, 1), new GroupAssignment(2L, 5)), configs);

        assertEquals(2L, resolution.configId());
    }

    @Test
    public void testEqualPriorityOfUnrelatedChainsIsATie() {
        Map<Long, ConfigData> configs = graph(config(1, null, null), config(2, null, null));

        Resolution resolution = SaltbootConfigResolver.resolve(null,
                List.of(new GroupAssignment(1L, 3), new GroupAssignment(2L, 3)), configs);

        assertFalse(resolution.isResolved());
        assertTrue(resolution.isTie());
        assertEquals(List.of(1L, 2L), resolution.tiedConfigIds());
    }

    @Test
    public void testTheSameConfigFromSeveralGroupsIsNoTie() {
        Map<Long, ConfigData> configs = graph(config(1, null, null));

        Resolution resolution = SaltbootConfigResolver.resolve(null,
                List.of(new GroupAssignment(1L, 0), new GroupAssignment(1L, 0)), configs);

        assertEquals(1L, resolution.configId());
    }
}
