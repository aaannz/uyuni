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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pure logic of the saltboot config hierarchy: the chain merge that yields the effective config of a config node and
 * the resolution of the config that applies to a terminal. Free of persistence so that it can be tested without
 * a database.
 */
public final class SaltbootConfigResolver {

    private SaltbootConfigResolver() {
    }

    /**
     * Snapshot of a {@link SaltbootConfig} with its parent reference resolved to an id.
     *
     * @param id the config id
     * @param name the config name, for messages
     * @param parentId the id of the parent config or null for a root
     * @param partitioning the possibly partial partitioning or null
     * @param kernelAction the kernel action or null to inherit
     * @param terminalKernelParameters the terminal kernel parameters or null to inherit
     * @param freezeImage the freeze image flag or null to inherit
     */
    public record ConfigData(Long id, String name, Long parentId, Map<String, Object> partitioning,
                             String kernelAction, String terminalKernelParameters, Boolean freezeImage) {
    }

    /**
     * Effective values of a config: the chain from the root merged into one.
     *
     * @param partitioning complete partitioning, empty when no config of the chain defines one
     * @param kernelAction the kernel action or null if no config of the chain sets one
     * @param terminalKernelParameters the terminal kernel parameters or null
     * @param freezeImage the freeze image flag or null
     */
    public record EffectiveConfig(Map<String, Object> partitioning, String kernelAction,
                                  String terminalKernelParameters, Boolean freezeImage) {
    }

    /**
     * A group assignment of one of the groups of the terminal.
     *
     * @param configId the assigned config
     * @param priority the assignment priority
     */
    public record GroupAssignment(Long configId, int priority) {
    }

    /**
     * Outcome of the resolution for a terminal: no config, the winning config, or unresolvable tie.
     *
     * @param configId the winning config, null when none applies or the candidates tie
     * @param tiedConfigIds the candidates that could not be separated, empty unless there is a tie
     */
    public record Resolution(Long configId, List<Long> tiedConfigIds) {

        /**
         * @return true if a config applies
         */
        public boolean isResolved() {
            return configId != null;
        }

        /**
         * @return true if more than one config applies and none can be preferred
         */
        public boolean isTie() {
            return !tiedConfigIds.isEmpty();
        }
    }

    /**
     * Merges a partial partitioning over a base one. Disks and partitions are merged by their symbolic IDs, a null
     * value removes the inherited entry (tombstone), any other value replaces the inherited one.
     * The inputs are not modified, the result holds no nulls.
     *
     * @param base the inherited partitioning
     * @param override the partial partitioning of the more derived config
     * @return the merged partitioning
     */
    public static Map<String, Object> mergePartitioning(Map<String, Object> base, Map<String, Object> override) {
        Map<String, Object> result = new LinkedHashMap<>();
        base.forEach((key, value) -> result.put(key, copyValue(value)));
        override.forEach((key, value) -> {
            if (value == null) {
                result.remove(key);
            }
            else if (value instanceof Map<?, ?> overrideMap) {
                Object inherited = result.get(key);
                Map<String, Object> baseMap = inherited instanceof Map<?, ?> m ? castMap(m) : Collections.emptyMap();
                result.put(key, mergePartitioning(baseMap, castMap(overrideMap)));
            }
            else {
                result.put(key, copyValue(value));
            }
        });
        return result;
    }

    /**
     * Lists the configs of the chain of a config.
     *
     * @param configId the config to start from
     * @param configs all configs by id
     * @return the configs from the root to the given one
     * @throws SaltbootValidationException if a config is missing or the parent references form a cycle
     */
    public static List<ConfigData> chain(Long configId, Map<Long, ConfigData> configs) {
        LinkedList<ConfigData> chain = new LinkedList<>();
        Set<Long> seen = new HashSet<>();
        Long current = configId;
        while (current != null) {
            if (!seen.add(current)) {
                throw new SaltbootValidationException("Saltboot config hierarchy contains a cycle at config " +
                        current);
            }
            ConfigData config = configs.get(current);
            if (config == null) {
                throw new SaltbootValidationException("Saltboot config " + current + " does not exist");
            }
            chain.addFirst(config);
            current = config.parentId();
        }
        return chain;
    }

    /**
     * Computes the effective values of a config: the chain merged from the root, the nearest non null scalar wins.
     *
     * @param configId the config
     * @param configs all configs by id
     * @return the effective config
     * @throws SaltbootValidationException if the chain is broken
     */
    public static EffectiveConfig effective(Long configId, Map<Long, ConfigData> configs) {
        Map<String, Object> partitioning = new LinkedHashMap<>();
        String kernelAction = null;
        String terminalKernelParameters = null;
        Boolean freezeImage = null;
        for (ConfigData config : chain(configId, configs)) {
            if (config.partitioning() != null) {
                partitioning = mergePartitioning(partitioning, config.partitioning());
            }
            kernelAction = config.kernelAction() != null ? config.kernelAction() : kernelAction;
            terminalKernelParameters = config.terminalKernelParameters() != null ?
                    config.terminalKernelParameters() : terminalKernelParameters;
            freezeImage = config.freezeImage() != null ? config.freezeImage() : freezeImage;
        }
        return new EffectiveConfig(partitioning, kernelAction, terminalKernelParameters, freezeImage);
    }

    /**
     * Tells if making a config the parent of another one would close a cycle.
     *
     * @param configId the config that gets a new parent
     * @param parentId the proposed parent
     * @param configs all configs by id
     * @return true if the parent is the config itself or one of its descendants
     */
    public static boolean wouldCreateCycle(Long configId, Long parentId, Map<Long, ConfigData> configs) {
        Set<Long> seen = new HashSet<>();
        Long current = parentId;
        while (current != null && seen.add(current)) {
            if (current.equals(configId)) {
                return true;
            }
            ConfigData config = configs.get(current);
            current = config == null ? null : config.parentId();
        }
        return false;
    }

    /**
     * Resolves the config that applies to a terminal: a system assignment wins; otherwise the most derived config
     * of the assigned group configs (configs that are ancestors of another candidate are dropped); remaining
     * unrelated candidates are separated by the highest assignment priority.
     *
     * @param systemConfigId the config assigned to the system itself or null
     * @param groupAssignments the assignments of all groups of the terminal
     * @param configs all configs by id
     * @return the resolution
     */
    public static Resolution resolve(Long systemConfigId, List<GroupAssignment> groupAssignments,
                                     Map<Long, ConfigData> configs) {
        if (systemConfigId != null) {
            return new Resolution(systemConfigId, List.of());
        }

        Map<Long, Integer> candidates = new LinkedHashMap<>();
        groupAssignments.forEach(a -> candidates.merge(a.configId(), a.priority(), Math::max));

        Set<Long> ancestors = new HashSet<>();
        candidates.keySet().forEach(id -> {
            List<ConfigData> chain = chain(id, configs);
            chain.subList(0, chain.size() - 1).forEach(ancestor -> ancestors.add(ancestor.id()));
        });
        candidates.keySet().removeAll(ancestors);

        if (candidates.isEmpty()) {
            return new Resolution(null, List.of());
        }
        int highest = Collections.max(candidates.values());
        List<Long> best = candidates.entrySet().stream()
                .filter(e -> e.getValue() == highest)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        return best.size() == 1 ? new Resolution(best.get(0), List.of()) : new Resolution(null, best);
    }

    private static Object copyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return mergePartitioning(Collections.emptyMap(), castMap(map));
        }
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
