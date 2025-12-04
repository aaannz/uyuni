/*
 * Copyright (c) 2025 SUSE LLC
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

package com.suse.utils;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Strings {
    private static final Pattern REPLACE_PATTERN = Pattern.compile("\\$\\{(\\w+)\\}");

    private Strings() {
    }

    /**
     * Replaces named placeholders in a string template.
     * @param template The string template, e.g., "Hello, ${name}!"
     * @param values A map of placeholder names to their replacement values.
     * @return The fully substituted string.
     */
    public static String fillTemplate(String template, Map<String, String> values) throws NoSuchFieldException {
        if (values == null || values.isEmpty()) {
            return template;
        }

        Matcher matcher = REPLACE_PATTERN.matcher(template);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String key = matcher.group(1); // The name inside ${...}
            Object value = values.get(key);
            if (value == null) {
                throw new NoSuchFieldException("Key " + key + " not found in the value map");
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(String.valueOf(value)));
        }
        matcher.appendTail(result);
        return result.toString();
    }

}
