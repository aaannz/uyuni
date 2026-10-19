/*
 * Copyright (c) 2026 SUSE LLC
 *
 * This software is licensed to you under the GNU General Public License,
 * version 2 (GPLv2). There is NO WARRANTY for this software, express or
 * implied, including the implied warranties of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. You should have received a copy of GPLv2
 * along with this software; if not, see
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.txt.
 */

package com.redhat.rhn.domain.notification.types;

import com.redhat.rhn.common.localization.LocalizationService;

/**
 * Notification about a saltboot configuration that cannot be applied, e.g. ambiguous configs for a terminal.
 */
public class SaltbootConfigurationProblem implements NotificationData {

    private final String subject;
    private final String details;

    /**
     * Constructor
     * @param subjectIn the affected terminal, branch group or config
     * @param detailsIn what is wrong and how to resolve it
     */
    public SaltbootConfigurationProblem(String subjectIn, String detailsIn) {
        this.subject = subjectIn;
        this.details = detailsIn;
    }

    @Override
    public String getSummary() {
        return LocalizationService.getInstance().getMessage("notification.saltbootconfigurationproblem", subject);
    }

    @Override
    public String getDetails() {
        return details;
    }
}
