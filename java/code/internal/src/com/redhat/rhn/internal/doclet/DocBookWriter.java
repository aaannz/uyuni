/*
 * Copyright (c) 2012--2020 SUSE LLC
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

package com.redhat.rhn.internal.doclet;

import java.util.List;
import java.util.Map;

/**
 * DocBook Writer
 */
public class DocBookWriter extends DocWriter {

    private static final String[] OTHER_FILES = {"faqs", "scripts"};

    /**
     * @param outputIn path to the output folder
     * @param templatesIn path to the DocBook templates folder
     * @param productIn name of the product
     * @param apiVersionIn version of the api
     * @param debugIn whether to show debugging messages
     *
     */
    public DocBookWriter(String outputIn, String templatesIn, String productIn, String apiVersionIn, boolean debugIn) {
        super(outputIn, templatesIn, productIn, apiVersionIn, debugIn);
    }

    /**
     * {@inheritDoc}
     */
    public void write(List<Handler> handlers,
            Map<String, String> serializers) throws Exception {

        // First macro-tize the serializer's docs
        renderSerializers(templates, serializers);

        // Lets do the index first
        writeFile(output + "book.xml", generateIndex(handlers, templates));
        for (Handler handler : handlers) {
            writeFile(output + handler.getClassName() + ".xml",
                    generateHandler(handler, templates));
        }

        VelocityHelper vh = new VelocityHelper(templates);
        vh.addMatch("productName", product);
        vh.addMatch("apiVersion", apiVersion);
        for (String file : OTHER_FILES) {
            String content = vh.renderTemplateFile(file + ".txt");
            writeFile(output + file + ".xml", content);
        }
    }

    /**
     * Transcode simple HTML markup into DocBook XML or remove if not relevant.
     *
     * @param string any string containing HTML markup
     * @return string containing DocBook XML markup
     */
    public static String transcode(String string) {
        String ret = "";
        // Remove href, italic and br
        ret = string.replaceAll("<a [^>]*>", "");
        ret = ret.replaceAll("</a>", "");
        ret = ret.replaceAll("<i>", "");
        ret = ret.replaceAll("</i>", "");
        ret = ret.replaceAll("<br/>", "</para><para>");
        ret = ret.replaceAll("<br>", "</para><para>");
        // Transform lists
        ret = ret.replaceAll("<ul>", "</para><itemizedlist>");
        ret = ret.replaceAll("</ul>", "</itemizedlist><para>");
        ret = ret.replaceAll("<li>", "<listitem><para>");
        ret = ret.replaceAll("</li>", "</para></listitem>");
        // Remove arbitrary stuff
        ret = ret.replaceAll("<Specified System>", "Specified System");
        return ret;
    }
}
