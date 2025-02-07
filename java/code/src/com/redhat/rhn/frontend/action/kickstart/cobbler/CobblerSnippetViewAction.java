/*
 * Copyright (c) 2009--2010 Red Hat, Inc.
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
package com.redhat.rhn.frontend.action.kickstart.cobbler;

import com.redhat.rhn.common.validator.ValidatorException;
import com.redhat.rhn.domain.kickstart.cobbler.CobblerSnippet;
import com.redhat.rhn.frontend.action.common.BadParameterException;
import com.redhat.rhn.frontend.struts.RequestContext;
import com.redhat.rhn.frontend.struts.RhnAction;
import com.redhat.rhn.frontend.struts.RhnHelper;

import org.apache.log4j.Logger;
import org.apache.struts.action.ActionForm;
import org.apache.struts.action.ActionForward;
import org.apache.struts.action.ActionMapping;

import java.io.File;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;


/**
 * CobblerSnippetDetailsAction
 */
public class CobblerSnippetViewAction extends RhnAction {
    private static final Logger LOG =
            Logger.getLogger(CobblerSnippetViewAction.class);
    public static final String PATH = "path";
    public static final String DATA = "data";
    /** {@inheritDoc} */
    public ActionForward execute(ActionMapping mapping,
                                  ActionForm formIn,
                                  HttpServletRequest request,
                                  HttpServletResponse response) {
        RequestContext ctx = new RequestContext(request);
        try {
            CobblerSnippet snip = CobblerSnippet.loadReadOnly(
                    new File(ctx.getParam(PATH, true)));
            request.setAttribute(PATH, snip.getDisplayPath());
            request.setAttribute(DATA, snip.getContents());
            CobblerSnippetDetailsAction.bindSnippet(request, snip);
            return mapping.findForward(RhnHelper.DEFAULT_FORWARD);
        }
        catch (ValidatorException ve) {
            LOG.error(ve);
            throw new BadParameterException(
                    "The parameter " + PATH + " is required.");
        }
    }

}
