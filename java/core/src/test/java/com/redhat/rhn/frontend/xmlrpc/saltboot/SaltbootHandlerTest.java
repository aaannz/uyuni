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

package com.redhat.rhn.frontend.xmlrpc.saltboot;

import static com.suse.manager.autoinstallation.saltboot.SaltbootTestUtils.addToGroup;
import static com.suse.manager.autoinstallation.saltboot.SaltbootTestUtils.createGroup;
import static com.suse.manager.autoinstallation.saltboot.SaltbootTestUtils.createMinion;
import static com.suse.manager.autoinstallation.saltboot.SaltbootTestUtils.createProxy;
import static com.suse.manager.autoinstallation.saltboot.SaltbootTestUtils.layout;
import static com.suse.manager.autoinstallation.saltboot.SaltbootTestUtils.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.redhat.rhn.domain.server.ManagedServerGroup;
import com.redhat.rhn.domain.server.MinionServer;
import com.redhat.rhn.domain.server.Server;
import com.redhat.rhn.frontend.xmlrpc.BaseHandlerTestCase;
import com.redhat.rhn.frontend.xmlrpc.InvalidParameterException;
import com.redhat.rhn.frontend.xmlrpc.PermissionCheckFailureException;

import com.suse.manager.autoinstallation.saltboot.SaltbootManager;
import com.suse.manager.webui.services.iface.SaltApi;
import com.suse.salt.netapi.datatypes.target.MinionList;

import org.jmock.Expectations;
import org.jmock.Mockery;
import org.jmock.junit5.JUnit5Mockery;
import org.jmock.lib.concurrent.Synchroniser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

@ExtendWith(JUnit5Mockery.class)
public class SaltbootHandlerTest extends BaseHandlerTestCase {

    @RegisterExtension
    protected final Mockery context = new JUnit5Mockery() {{
        setThreadingPolicy(new Synchroniser());
    }};

    private SaltbootHandler handler;

    @BeforeEach
    public void setUp() {
        SaltApi saltApi = context.mock(SaltApi.class);
        context.checking(new Expectations() {{
            allowing(saltApi).refreshPillar(with(any(MinionList.class)));
        }});
        handler = new SaltbootHandler(new SaltbootManager(saltApi, group -> { }, group -> { }));
    }

    @Test
    public void testBranchLifecycle() throws Exception {
        ManagedServerGroup group = createGroup(admin, "branch");
        Server proxy = createProxy(admin, "proxy.example.com");
        int groupId = group.getId().intValue();

        Map<String, Object> created = handler.createBranch(admin, groupId, proxy.getId().intValue(),
                map("minion_id_naming", "FQDN", "default_boot_image", "JeOS"));

        assertEquals(groupId, created.get("group_id"));
        assertEquals("branch", created.get("group_name"));
        assertEquals(proxy.getId().intValue(), created.get("proxy_id"));
        assertEquals("proxy.example.com", created.get("proxy_fqdn"));
        assertEquals("FQDN", created.get("minion_id_naming"));
        assertEquals(false, created.get("disable_id_prefix"));
        assertEquals("JeOS", created.get("default_boot_image"));
        assertFalse(created.containsKey("default_kernel_parameters"));
        assertEquals(created, handler.getBranch(admin, groupId));
        assertEquals(List.of(created), handler.listBranches(admin));

        Map<String, Object> updated = handler.updateBranch(admin, groupId, map("proxy_id", 0,
                "default_kernel_parameters", "quiet"));
        assertEquals(0, updated.get("proxy_id"));
        assertFalse(updated.containsKey("proxy_fqdn"));
        assertEquals("quiet", updated.get("default_kernel_parameters"));

        assertEquals(1, handler.deleteBranch(admin, groupId));
        assertTrue(handler.listBranches(admin).isEmpty());
    }

    @Test
    public void testConfigLifecycleWithTombstones() {
        Map<String, Object> root = handler.createConfig(admin, "root", 0,
                map("partitioning", layout("JeOS", "1.0.0"), "kernel_action", "reboot", "freeze_image", false));
        int rootId = (Integer) root.get("id");
        assertEquals(0, root.get("parent_id"));
        assertEquals("reboot", root.get("kernel_action"));
        assertEquals(false, root.get("freeze_image"));
        assertEquals(layout("JeOS", "1.0.0"), root.get("partitioning"));

        // the delete marker removes the inherited partition, XML-RPC cannot carry null
        Map<String, Object> child = handler.createConfig(admin, "child", rootId,
                map("partitioning", map("disk1", map("partitions", map("p1", "__delete__")))));
        int childId = (Integer) child.get("id");
        assertEquals(rootId, child.get("parent_id"));
        Map<String, Object> storedDisk = (Map<String, Object>) ((Map<String, Object>) child.get("partitioning"))
                .get("disk1");
        assertEquals("__delete__", ((Map<String, Object>) storedDisk.get("partitions")).get("p1"));

        Map<String, Object> effective = handler.getEffectiveConfig(admin, childId);
        Map<String, Object> effectiveDisk = (Map<String, Object>) ((Map<String, Object>) effective
                .get("partitioning")).get("disk1");
        assertEquals(List.of("p2"), List.copyOf(((Map<String, Object>) effectiveDisk.get("partitions")).keySet()));
        assertEquals("reboot", effective.get("kernel_action"));

        Map<String, Object> renamed = handler.updateConfig(admin, childId, map("name", "renamed"));
        assertEquals("renamed", renamed.get("name"));
        assertEquals(renamed, handler.getConfig(admin, childId));
        assertEquals(2, handler.listConfigs(admin).size());

        assertEquals(1, handler.deleteConfig(admin, childId));
        assertEquals(1, handler.deleteConfig(admin, rootId));
        assertTrue(handler.listConfigs(admin).isEmpty());
    }

    @Test
    public void testAssignmentsAndTerminals() {
        MinionServer minion = createMinion(admin);
        ManagedServerGroup group = createGroup(admin, "hwtype");
        addToGroup(minion, group);
        int sid = minion.getId().intValue();
        int configId = (Integer) handler.createConfig(admin, "hw", 0,
                map("partitioning", layout("JeOS", "1.0.0"))).get("id");

        assertEquals(1, handler.assignConfigToGroup(admin, configId, group.getId().intValue(), 2));

        Map<String, Object> terminalConfig = handler.getEffectiveTerminalConfig(admin, sid);
        assertEquals(false, terminalConfig.get("formula_managed"));
        assertEquals(configId, terminalConfig.get("config_id"));
        assertEquals(layout("JeOS", "1.0.0"), ((Map<String, Object>) terminalConfig.get("effective"))
                .get("partitioning"));
        assertEquals(List.of(), terminalConfig.get("problems"));

        Map<String, Object> state = handler.getTerminalState(admin, sid);
        assertEquals(sid, state.get("sid"));
        assertEquals(false, state.get("force_redeploy"));

        assertEquals(1, handler.scheduleForceRedeploy(admin, sid, true));
        state = handler.getTerminalState(admin, sid);
        assertEquals(true, state.get("force_redeploy"));
        assertEquals(true, state.get("force_repartition"));

        assertEquals(1, handler.unassignConfigFromGroup(admin, group.getId().intValue()));
        assertEquals(1, handler.assignConfigToSystem(admin, configId, sid));
        assertEquals(1, handler.unassignConfigFromSystem(admin, sid));
        assertFalse(handler.getEffectiveTerminalConfig(admin, sid).containsKey("config_id"));
    }

    @Test
    public void testPermissionFailuresBecomeFaults() {
        ManagedServerGroup group = createGroup(admin, "branch");

        assertThrows(PermissionCheckFailureException.class, () -> handler.createConfig(regular, "x", 0, Map.of()));
        assertThrows(PermissionCheckFailureException.class, () -> handler.listConfigs(regular));
        assertThrows(PermissionCheckFailureException.class,
                () -> handler.createBranch(regular, group.getId().intValue(), 0, Map.of()));
    }

    @Test
    public void testValidationFailuresBecomeInvalidParameterFaults() {
        assertThrows(InvalidParameterException.class, () -> handler.getBranch(admin, 999999));
        assertThrows(InvalidParameterException.class, () -> handler.getConfig(admin, 999999));
        assertThrows(InvalidParameterException.class, () -> handler.createConfig(admin, "", 0, Map.of()));
        assertThrows(InvalidParameterException.class,
                () -> handler.createConfig(admin, "x", 0, map("kernel_action", "halt")));
    }
}
