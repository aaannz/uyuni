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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validation of the partitioning stored in {@link SaltbootConfig}. The shape follows the form of the saltboot
 * formula that the saltboot states consume, but a config only holds a part of it, so nothing is mandatory and a
 * null value is a tombstone that removes an inherited entry.
 */
public final class SaltbootPartitioning {

    public static final Set<String> KERNEL_ACTIONS = Set.of("kexec", "reboot", "fail");

    private static final Set<String> DEVICE_TYPES = Set.of("DISK", "RAID");
    private static final Set<String> DISKLABELS = Set.of("gpt", "msdos", "none");
    private static final Set<String> FORMATS = Set.of("", "btrfs", "ext4", "xfs", "vfat", "swap");
    private static final Set<String> FLAGS = Set.of("", "swap", "raid", "bios_grub", "esp", "boot");
    private static final Set<String> IMAGE_KEYS = Set.of("mountpoint", "format", "image", "image_version",
            "luks_pass");
    private static final Set<String> DISK_KEYS = Set.of("type", "device", "level", "devices", "disklabel",
            "partitions", "mountpoint", "format", "image", "image_version", "luks_pass");
    private static final Set<String> PARTITION_KEYS = Set.of("size_MiB", "mountpoint", "format", "image",
            "image_version", "luks_pass", "flags");
    private static final Pattern MOUNTPOINT = Pattern.compile("^(/.*)|(swap)|");

    private SaltbootPartitioning() {
    }

    /**
     * Validates a possibly partial partitioning.
     *
     * @param partitioning the partitioning keyed by disk symbolic ID, may be null
     * @throws SaltbootValidationException describing the first problem found
     */
    public static void validate(Map<String, Object> partitioning) {
        if (partitioning == null) {
            return;
        }
        partitioning.forEach((diskId, disk) -> {
            if (disk != null) {
                validateDisk(diskId, asMap(disk, "disk " + diskId));
            }
        });
    }

    /**
     * Validates a kernel action value.
     *
     * @param kernelAction the value, may be null
     * @throws SaltbootValidationException if the value is not a known kernel action
     */
    public static void validateKernelAction(String kernelAction) {
        if (kernelAction != null && !KERNEL_ACTIONS.contains(kernelAction)) {
            throw new SaltbootValidationException("Invalid kernel action '" + kernelAction +
                    "', expected one of " + KERNEL_ACTIONS);
        }
    }

    private static void validateDisk(String diskId, Map<String, Object> disk) {
        String where = "disk " + diskId;
        checkKeys(disk, DISK_KEYS, where);
        checkEnum(disk, "type", DEVICE_TYPES, where);
        checkEnum(disk, "disklabel", DISKLABELS, where);
        checkImageFields(disk, where);

        Object level = disk.get("level");
        if (level != null && !"1".equals(String.valueOf(level)) && !(level instanceof Number n && n.intValue() == 1)) {
            throw new SaltbootValidationException("Unsupported RAID level '" + level + "' of " + where);
        }
        Object devices = disk.get("devices");
        if (devices != null) {
            if (!(devices instanceof List<?> list) || !list.stream().allMatch(String.class::isInstance)) {
                throw new SaltbootValidationException("'devices' of " + where + " must be a list of symbolic IDs");
            }
        }
        Object partitions = disk.get("partitions");
        if (partitions != null) {
            asMap(partitions, "partitions of " + where).forEach((partitionId, partition) -> {
                if (partition != null) {
                    validatePartition(where + ", partition " + partitionId,
                            asMap(partition, "partition " + partitionId + " of " + where));
                }
            });
        }
    }

    private static void validatePartition(String where, Map<String, Object> partition) {
        checkKeys(partition, PARTITION_KEYS, where);
        checkImageFields(partition, where);
        checkEnum(partition, "flags", FLAGS, where);
        Object size = partition.get("size_MiB");
        if (size != null && !isNonNegativeNumber(size)) {
            throw new SaltbootValidationException("'size_MiB' of " + where + " must be a non-negative number");
        }
    }

    private static void checkImageFields(Map<String, Object> entry, String where) {
        checkEnum(entry, "format", FORMATS, where);
        Object mountpoint = entry.get("mountpoint");
        if (mountpoint != null && !MOUNTPOINT.matcher(String.valueOf(mountpoint)).matches()) {
            throw new SaltbootValidationException("'mountpoint' of " + where +
                    " must be an absolute path or 'swap'");
        }
        IMAGE_KEYS.forEach(key -> {
            Object value = entry.get(key);
            if (value != null && !(value instanceof String)) {
                throw new SaltbootValidationException("'" + key + "' of " + where + " must be a string");
            }
        });
    }

    private static void checkKeys(Map<String, Object> entry, Set<String> known, String where) {
        entry.keySet().stream().filter(key -> !known.contains(key)).findFirst().ifPresent(key -> {
            throw new SaltbootValidationException("Unknown key '" + key + "' in " + where);
        });
    }

    private static void checkEnum(Map<String, Object> entry, String key, Set<String> allowed, String where) {
        Object value = entry.get(key);
        if (value != null && !allowed.contains(String.valueOf(value))) {
            throw new SaltbootValidationException("Invalid '" + key + "' value '" + value + "' in " + where);
        }
    }

    private static boolean isNonNegativeNumber(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue() >= 0;
        }
        return value instanceof String text && text.matches("[0-9]*");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value, String what) {
        if (!(value instanceof Map)) {
            throw new SaltbootValidationException("The " + what + " must be a map");
        }
        return (Map<String, Object>) value;
    }
}
