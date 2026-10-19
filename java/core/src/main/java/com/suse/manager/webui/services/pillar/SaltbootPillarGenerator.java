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

import com.redhat.rhn.common.hibernate.HibernateFactory;
import com.redhat.rhn.domain.notification.UserNotificationFactory;
import com.redhat.rhn.domain.notification.types.SaltbootConfigurationProblem;
import com.redhat.rhn.domain.server.MinionServer;
import com.redhat.rhn.domain.server.Pillar;
import com.redhat.rhn.domain.server.ServerFactory;

import com.suse.manager.autoinstallation.saltboot.SaltbootBranch;
import com.suse.manager.autoinstallation.saltboot.SaltbootConfigResolver.EffectiveConfig;
import com.suse.manager.autoinstallation.saltboot.SaltbootFactory;
import com.suse.manager.autoinstallation.saltboot.SaltbootTerminal;
import com.suse.manager.autoinstallation.saltboot.SaltbootTerminalResolver;
import com.suse.manager.autoinstallation.saltboot.SaltbootTerminalResolver.TerminalSettings;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Generates the pillar the saltboot states consume (partitioning, terminal kernel parameters and the
 * saltboot:* keys) from the saltboot domain model.
 * <p>
 * While a saltboot formula applies to the minion, the formula pillar stays authoritative and nothing is generated,
 * so that formula and domain data are never merged.
 */
public class SaltbootPillarGenerator extends MinionPillarGeneratorBase {

    public static final SaltbootPillarGenerator INSTANCE = new SaltbootPillarGenerator();

    public static final String CATEGORY = "saltboot";

    private static final Logger LOG = LogManager.getLogger(SaltbootPillarGenerator.class);
    private static final String PROBLEM_PREFIX = "config problem: ";

    @Override
    public Optional<Pillar> generatePillarData(MinionServer minion) {
        if (SaltbootTerminalResolver.isFormulaManaged(minion)) {
            removePillar(minion);
            return Optional.empty();
        }

        TerminalSettings settings = SaltbootTerminalResolver.resolve(minion);
        Optional<SaltbootTerminal> terminal = SaltbootFactory.lookupTerminal(minion.getId());
        reportProblems(minion, settings, terminal);

        Map<String, Object> data = buildPillar(settings, terminal);
        if (data.isEmpty()) {
            removePillar(minion);
            return Optional.empty();
        }

        Pillar pillar = minion.getPillarByCategory(CATEGORY).orElseGet(() -> {
            Pillar newPillar = new Pillar(CATEGORY, new LinkedHashMap<>(), minion);
            minion.getPillars().add(newPillar);
            return newPillar;
        });
        pillar.setPillar(data);
        return Optional.of(pillar);
    }

    @Override
    public String getCategory() {
        return CATEGORY;
    }

    /**
     * Removes the pillar and flushes right away: a following generation inserts a new row for the same minion and
     * category, which would run into the unique constraint before the pending delete.
     */
    @Override
    public void removePillar(MinionServer minion) {
        boolean present = minion.getPillarByCategory(CATEGORY).isPresent();
        super.removePillar(minion);
        if (present) {
            HibernateFactory.getSession().flush();
        }
    }

    /**
     * Builds the pillar content. Values the states default themselves are omitted when unset, a resolved terminal
     * always gets a saltboot dictionary because the states use its presence as the saltboot managed switch.
     *
     * @param settings the settings resolved for the terminal
     * @param terminal the state of the terminal
     * @return the pillar content, empty if nothing applies to the terminal
     */
    static Map<String, Object> buildPillar(TerminalSettings settings, Optional<SaltbootTerminal> terminal) {
        Optional<SaltbootBranch> branch = settings.branch();
        if (settings.effective().isEmpty() && branch.isEmpty()) {
            return Map.of();
        }

        Map<String, Object> saltboot = new LinkedHashMap<>();
        Map<String, Object> pillar = new LinkedHashMap<>();
        pillar.put("saltboot", saltboot);

        branch.flatMap(SaltbootTerminalResolver::proxyFqdn)
                .ifPresent(fqdn -> saltboot.put("download_server", fqdn));
        settings.effective().ifPresent(effective -> addConfig(pillar, saltboot, effective));
        terminal.ifPresent(state -> {
            if (state.isForceRedeploy()) {
                saltboot.put("force_redeploy", true);
            }
            if (state.isForceRepartition()) {
                saltboot.put("force_repartition", true);
            }
        });
        return pillar;
    }

    private static void addConfig(Map<String, Object> pillar, Map<String, Object> saltboot,
                                  EffectiveConfig effective) {
        if (!effective.partitioning().isEmpty()) {
            pillar.put("partitioning", effective.partitioning());
        }
        if (effective.terminalKernelParameters() != null) {
            pillar.put("terminal_kernel_parameters", effective.terminalKernelParameters());
        }
        if (effective.kernelAction() != null) {
            saltboot.put("kernel_action", effective.kernelAction());
        }
        if (effective.freezeImage() != null) {
            saltboot.put("freeze_image", effective.freezeImage());
        }
    }

    /**
     * Notifies the administrators about configuration problems once: the last reported problem is remembered in the
     * terminal state and cleared when the problem is gone.
     */
    private static void reportProblems(MinionServer minion, TerminalSettings settings,
                                       Optional<SaltbootTerminal> terminal) {
        String problem = settings.problems().isEmpty() ? null : String.join(" ", settings.problems());
        String reported = terminal.map(SaltbootTerminal::getLastResult)
                .filter(result -> result != null && result.startsWith(PROBLEM_PREFIX)).orElse(null);
        if (problem == null && reported == null) {
            return;
        }
        if (problem != null) {
            LOG.warn("Saltboot terminal {} is blocked: {}", minion.getMinionId(), problem);
        }
        if (problem != null && !(PROBLEM_PREFIX + problem).equals(reported)) {
            UserNotificationFactory.storeForUsers(
                    UserNotificationFactory.createNotificationMessage(
                            new SaltbootConfigurationProblem(minion.getMinionId(), problem)),
                    new HashSet<>(ServerFactory.listAdministrators(minion)));
        }
        SaltbootTerminal state = terminal.orElseGet(() -> SaltbootFactory.lookupOrCreateTerminal(minion.getId()));
        state.setLastResult(problem == null ? null : PROBLEM_PREFIX + problem);
        SaltbootFactory.save(state);
    }
}
