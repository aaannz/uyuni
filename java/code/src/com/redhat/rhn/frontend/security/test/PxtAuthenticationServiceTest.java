/*
 * Copyright (c) 2009--2017 Red Hat, Inc.
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
package com.redhat.rhn.frontend.security.test;

import com.redhat.rhn.frontend.security.PxtAuthenticationService;

import org.apache.http.client.utils.URIBuilder;
import org.jmock.Expectations;

import java.util.Vector;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * PxtAuthenticationServiceTest
 */
// TODO Review Test classes in package to factor out common code
public class PxtAuthenticationServiceTest extends AuthenticationServiceAbstractTestCase {

    private class PxtAuthenticationServiceStub extends PxtAuthenticationService {
    }

    private PxtAuthenticationService service;

    public PxtAuthenticationServiceTest(String name) {
        super(name);
    }

    protected void setUp() throws Exception {
        super.setUp();
        service = new PxtAuthenticationServiceStub();
        service.setPxtSessionDelegate(getPxtDelegate());
    }

    private void setupPxtDelegate(final boolean sessionKeyValid,
            final boolean sessionExpired,
            final Long webUserId) {
        context().checking(new Expectations() { {
            allowing(mockPxtDelegate)
                    .isPxtSessionKeyValid(with(any(HttpServletRequest.class)));
            will(returnValue(sessionKeyValid));
            allowing(mockPxtDelegate)
                    .isPxtSessionExpired(with(any(HttpServletRequest.class)));
            will(returnValue(sessionExpired));
            allowing(mockPxtDelegate).getWebUserId(with(any(HttpServletRequest.class)));
            will(returnValue(webUserId));
        } });
    }

    private void setupGetRequestURI(final String requestUri) {
        context().checking(new Expectations() { {
            allowing(mockRequest).getRequestURI();
            will(returnValue(requestUri));
        } });
    }

    private void runValidateFailsTest() {
        context().checking(new Expectations() { {
            atLeast(1).of(mockPxtDelegate).invalidatePxtSession(
                    with(any(HttpServletRequest.class)),
                    with(any(HttpServletResponse.class)));
        } });

        assertFalse(service.validate(getRequest(), getResponse()));
    }

    private void runValidateSucceedsTest() {
        context().checking(new Expectations() { {
            never(mockPxtDelegate).refreshPxtSession(
                    with(any(HttpServletRequest.class)),
                    with(any(HttpServletResponse.class)));
        } });

        assertTrue(service.validate(getRequest(), getResponse()));
    }

    public final void testValidateFailsWhenPxtSessionKeyIsInvalid() {
        setupPxtDelegate(false, false, 1234L);
        setupGetRequestURI("/rhn/YourRhn.do");
        runValidateFailsTest();
    }

    public final void testValidateFailsWhenPxtSessionExpired() {
        setupPxtDelegate(true, true, 1234L);
        setupGetRequestURI("/rhn/YourRhn.do");
        runValidateFailsTest();
    }

    public final void testValidateFailsWhenWebUserIdIsNull() {
        setupPxtDelegate(true, false, null);
        setupGetRequestURI("/rhn/YourRhn.do");
        runValidateFailsTest();
    }

    public final void testValidateSucceedsWhenRequestURIUnprotected() {
        setupPxtDelegate(false, false, 1234L);
        setupGetRequestURI("/rhn/manager/login");
        assertTrue(service.validate(getRequest(), getResponse()));
    }

    public final void testValidateSucceeds() {
        setupPxtDelegate(true, false, 1234L);
        setupGetRequestURI("/rhn/YourRhn.do");
        runValidateSucceedsTest();
    }

    public final void testInvalidate() {
        setupPxtDelegate(true, false, 1234L);
        setupGetRequestURI("/rhn/YourRhn.do");

        context().checking(new Expectations() { {
            atLeast(1).of(mockPxtDelegate).invalidatePxtSession(
                    with(any(HttpServletRequest.class)),
                    with(any(HttpServletResponse.class)));
        } });

        service.invalidate(getRequest(), getResponse());
    }

    private void runRedirectToLoginTest() throws Exception {
        service.redirectToLogin(getRequest(), getResponse());
    }

    private void setUpRedirectToLogin() {
        context().checking(new Expectations() { {
            allowing(mockRequest).getParameterNames();
            will(returnValue(getParameterNames()));
            allowing(mockRequest).getParameter(requestParamNames[0]);
            will(returnValue(requestParamValues[0]));
            allowing(mockRequest).getParameter(requestParamNames[1]);
            will(returnValue(requestParamValues[1]));
            allowing(mockRequest).getRequestURL();
            will(returnValue(new StringBuffer(getRequestURL())));
            allowing(mockRequest).getQueryString();
            will(returnValue(null));
            allowing(mockRequest).getMethod();
            will(returnValue("POST"));
            allowing(mockRequest).getSession();
            will(returnValue(null));
            allowing(mockRequest).setAttribute(with(any(String.class)),
                    with(any(Object.class)));
        } });
    }

    public final void testRedirectoToLoginForwardsRequest() throws Exception {
        setupPxtDelegate(true, false, 1234L);
        setupGetRequestURI("/rhn/YourRhn.do");

        URIBuilder uriBuilder = new URIBuilder("/rhn/manager/login");
        uriBuilder.addParameter("url_bounce", "/rhn/YourRhn.do");
        uriBuilder.addParameter("request_method", "POST");

        context().checking(new Expectations() { {
            allowing(mockRequest).getParameterNames();
            will(returnValue(new Vector<String>().elements()));
            allowing(mockRequest).getRequestURL();
            will(returnValue(new StringBuffer(getRequestURL())));
            allowing(mockRequest).getQueryString();
            will(returnValue(null));
            allowing(mockRequest).getMethod();
            will(returnValue("POST"));
            allowing(mockRequest).getSession();
            will(returnValue(null));
            String uri = "/rhn/YourRhn.do";
            allowing(mockRequest).getRequestURI();
            will(returnValue(uri));
            allowing(mockPxtDelegate)
                    .isPxtSessionKeyValid(with(any(HttpServletRequest.class)));
            will(returnValue(false));
            oneOf(mockResponse).sendRedirect(uriBuilder.toString());
            will(returnValue(null));
            allowing(mockRequest).setAttribute("url_bounce", uri);
        } });

        runRedirectToLoginTest();
    }

    /**
     * @throws Exception something bad happened
     */
    public final void testRedirectToLoginSetsURLBounceRequestAttribute() throws Exception {
        setupPxtDelegate(true, false, 1234L);
        setupGetRequestURI("/rhn/YourRhn.do");
        setUpRedirectToLogin();

        URIBuilder uriBounceBuilder = new URIBuilder();
        uriBounceBuilder.setPath("/rhn/YourRhn.do");
        uriBounceBuilder.addParameter("question", "param 1 = 'Who is the one?'");
        uriBounceBuilder.addParameter("answer", "param 2 = 'Neo is the one!'");

        URIBuilder uriBuilder = new URIBuilder();
        uriBuilder.setPath("/rhn/manager/login");
        uriBuilder.addParameter("url_bounce", uriBounceBuilder.toString());
        uriBuilder.addParameter("request_method", "POST");

        context().checking(new Expectations() { {
            allowing(mockResponse).sendRedirect(uriBuilder.toString());
            will(returnValue(null));
            allowing(mockRequest).getRequestURI();
            will(returnValue("/rhn/YourRhn.do"));
        } });

        runRedirectToLoginTest();
    }
}
