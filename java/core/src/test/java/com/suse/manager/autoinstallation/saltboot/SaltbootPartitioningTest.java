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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tests for {@link SaltbootPartitioning}.
 */
public class SaltbootPartitioningTest {

    private static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            result.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return result;
    }

    private static Map<String, Object> diskWithPartition(Object... partitionKeysAndValues) {
        return map("disk1", map("type", "DISK", "disklabel", "gpt", "partitions",
                map("p1", map(partitionKeysAndValues))));
    }

    @Test
    public void testValidLayoutOfTheFormulaExample() {
        Map<String, Object> partitioning = map(
                "disk1", map("type", "DISK", "device", "/dev/sda", "disklabel", "msdos", "partitions", map(
                        "p1", map("size_MiB", 2000, "flags", "raid"),
                        "p2", map("size_MiB", 2000, "flags", "swap", "format", "swap"),
                        "p3", map("mountpoint", "/", "image", "JeOS", "image_version", "6.0.5"),
                        "p4", map("size_MiB", 4000, "flags", "", "format", "btrfs", "luks_pass", "secret"))),
                "md0", map("type", "RAID", "level", 1, "devices", List.of("disk1p1", "disk2p1"), "format", "ext4"),
                "disk3", map("type", "DISK", "disklabel", "none", "mountpoint", "/data", "format", "xfs"));

        assertDoesNotThrow(() -> SaltbootPartitioning.validate(partitioning));
    }

    @Test
    public void testNothingToValidate() {
        assertDoesNotThrow(() -> SaltbootPartitioning.validate(null));
        assertDoesNotThrow(() -> SaltbootPartitioning.validate(Map.of()));
    }

    @Test
    public void testTombstonesAreValid() {
        assertDoesNotThrow(() -> SaltbootPartitioning.validate(map("disk1", null)));
        assertDoesNotThrow(() -> SaltbootPartitioning.validate(map("disk1", map("partitions",
                map("p1", null, "p2", map("image_version", null))))));
    }

    @Test
    public void testPartialValuesAreValid() {
        assertDoesNotThrow(() -> SaltbootPartitioning.validate(diskWithPartition("image_version", "7.2.0")));
    }

    @Test
    public void testBlankSizeFromTheFormIsValid() {
        assertDoesNotThrow(() -> SaltbootPartitioning.validate(diskWithPartition("size_MiB", "")));
        assertDoesNotThrow(() -> SaltbootPartitioning.validate(diskWithPartition("size_MiB", "1024")));
    }

    @Test
    public void testUnknownKeysAreRejected() {
        assertThrows(SaltbootValidationException.class, () -> SaltbootPartitioning.validate(
                map("disk1", map("colour", "red"))));
        assertThrows(SaltbootValidationException.class, () -> SaltbootPartitioning.validate(
                diskWithPartition("colour", "red")));
        // a disk without partition table carries the image fields itself
        assertDoesNotThrow(() -> SaltbootPartitioning.validate(
                map("disk1", map("disklabel", "none", "format", "ext4"))));
        // flags and size exist on partitions only
        assertThrows(SaltbootValidationException.class, () -> SaltbootPartitioning.validate(
                map("disk1", map("flags", "esp"))));
    }

    @Test
    public void testEnumsAreChecked() {
        assertThrows(SaltbootValidationException.class, () -> SaltbootPartitioning.validate(
                map("disk1", map("type", "TAPE"))));
        assertThrows(SaltbootValidationException.class, () -> SaltbootPartitioning.validate(
                map("disk1", map("disklabel", "bsd"))));
        assertThrows(SaltbootValidationException.class, () -> SaltbootPartitioning.validate(
                diskWithPartition("format", "ntfs")));
        assertThrows(SaltbootValidationException.class, () -> SaltbootPartitioning.validate(
                diskWithPartition("flags", "lvm")));
    }

    @Test
    public void testValueTypesAreChecked() {
        assertThrows(SaltbootValidationException.class, () -> SaltbootPartitioning.validate(map("disk1", "sda")));
        assertThrows(SaltbootValidationException.class, () -> SaltbootPartitioning.validate(
                map("disk1", map("partitions", "p1"))));
        assertThrows(SaltbootValidationException.class, () -> SaltbootPartitioning.validate(
                diskWithPartition("size_MiB", -1)));
        assertThrows(SaltbootValidationException.class, () -> SaltbootPartitioning.validate(
                diskWithPartition("size_MiB", "big")));
        assertThrows(SaltbootValidationException.class, () -> SaltbootPartitioning.validate(
                diskWithPartition("image", 5)));
        assertThrows(SaltbootValidationException.class, () -> SaltbootPartitioning.validate(
                map("md0", map("type", "RAID", "devices", "disk1p1"))));
        assertThrows(SaltbootValidationException.class, () -> SaltbootPartitioning.validate(
                map("md0", map("type", "RAID", "level", 5))));
    }

    @Test
    public void testMountpointMustBeAbsoluteOrSwap() {
        assertDoesNotThrow(() -> SaltbootPartitioning.validate(diskWithPartition("mountpoint", "swap")));
        assertDoesNotThrow(() -> SaltbootPartitioning.validate(diskWithPartition("mountpoint", "")));
        assertThrows(SaltbootValidationException.class, () -> SaltbootPartitioning.validate(
                diskWithPartition("mountpoint", "var")));
    }

    @Test
    public void testKernelAction() {
        assertDoesNotThrow(() -> SaltbootPartitioning.validateKernelAction(null));
        assertDoesNotThrow(() -> SaltbootPartitioning.validateKernelAction("kexec"));
        assertDoesNotThrow(() -> SaltbootPartitioning.validateKernelAction("reboot"));
        assertDoesNotThrow(() -> SaltbootPartitioning.validateKernelAction("fail"));
        assertThrows(SaltbootValidationException.class, () -> SaltbootPartitioning.validateKernelAction("halt"));
    }
}
