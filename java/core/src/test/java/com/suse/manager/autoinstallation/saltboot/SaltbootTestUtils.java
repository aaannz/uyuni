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

import com.redhat.rhn.common.conf.Config;
import com.redhat.rhn.common.hibernate.HibernateFactory;
import com.redhat.rhn.domain.role.RoleFactory;
import com.redhat.rhn.domain.server.ManagedServerGroup;
import com.redhat.rhn.domain.server.MinionServer;
import com.redhat.rhn.domain.server.MinionServerFactoryTest;
import com.redhat.rhn.domain.server.Server;
import com.redhat.rhn.domain.server.ServerFQDN;
import com.redhat.rhn.domain.server.ServerFactory;
import com.redhat.rhn.domain.server.ServerFactoryTest;
import com.redhat.rhn.domain.user.User;
import com.redhat.rhn.domain.user.UserFactory;
import com.redhat.rhn.manager.kickstart.cobbler.CobblerXMLRPCHelper;
import com.redhat.rhn.manager.kickstart.cobbler.MockXMLRPCInvoker;
import com.redhat.rhn.testing.ServerGroupTestUtils;
import com.redhat.rhn.testing.TestUtils;

import org.cobbler.CobblerConnection;
import org.cobbler.MockConnection;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fixtures of the saltboot tests.
 */
public final class SaltbootTestUtils {

    private SaltbootTestUtils() {
    }

    /**
     * Builds a map from alternating keys and values, null values allowed (tombstones).
     *
     * @param keysAndValues the keys and values
     * @return the map
     */
    public static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            result.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return result;
    }

    /**
     * A small valid layout: one disk with an EFI and a root partition that deploys an image.
     *
     * @param image the image to deploy on the root partition
     * @param version the image version
     * @return the partitioning
     */
    public static Map<String, Object> layout(String image, String version) {
        return map("disk1", map(
                "type", "DISK",
                "device", "/dev/sda",
                "disklabel", "gpt",
                "partitions", map(
                        "p1", map("size_MiB", 500, "format", "vfat", "mountpoint", "/boot/efi", "flags", "esp"),
                        "p2", map("mountpoint", "/", "image", image, "image_version", version))));
    }

    /**
     * Routes the cobbler access of the code under test to the cobbler mock; pair with {@link #resetCobbler()}.
     */
    public static void useMockCobbler() {
        Config.get().setString(CobblerXMLRPCHelper.class.getName(), MockXMLRPCInvoker.class.getName());
        Config.get().setString(CobblerConnection.class.getName(), MockConnection.class.getName());
        MockConnection.clear();
    }

    /**
     * Clears the cobbler mock.
     */
    public static void resetCobbler() {
        MockConnection.clear();
    }

    /**
     * Makes the user an organization administrator, which the saltboot operations require.
     *
     * @param user the user
     * @return the user
     */
    public static User makeOrgAdmin(User user) {
        user.addPermanentRole(RoleFactory.ORG_ADMIN);
        UserFactory.save(user);
        return user;
    }

    /**
     * @param user the owner
     * @param name the group name
     * @return a new managed server group
     */
    public static ManagedServerGroup createGroup(User user, String name) {
        ManagedServerGroup group = ServerGroupTestUtils.createManaged(user);
        group.setName(name);
        TestUtils.saveAndFlush(group);
        return group;
    }

    /**
     * @param user the owner
     * @return a new minion
     */
    public static MinionServer createMinion(User user) {
        return MinionServerFactoryTest.createTestMinionServer(user);
    }

    /**
     * Adds a minion to a group, refreshing the entities.
     *
     * @param minion the minion
     * @param group the group
     */
    public static void addToGroup(Server minion, ManagedServerGroup group) {
        ServerFactory.addServerToGroup(minion, group);
        HibernateFactory.getSession().flush();
        HibernateFactory.getSession().refresh(minion);
    }

    /**
     * @param user the owner
     * @param fqdn the primary FQDN of the proxy
     * @return a new proxy
     * @throws Exception if the fixture cannot be created
     */
    public static Server createProxy(User user, String fqdn) throws Exception {
        Server proxy = ServerFactoryTest.createTestProxyServer(user, true);
        ServerFQDN serverFqdn = new ServerFQDN(proxy, fqdn);
        serverFqdn.setPrimary(true);
        proxy.getFqdns().add(serverFqdn);
        ServerFactory.save(proxy);
        TestUtils.saveAndFlush(serverFqdn);
        return proxy;
    }

    /**
     * @param user the owner
     * @param name the config name
     * @param parent the parent config or null
     * @param partitioning the partitioning or null
     * @return a new stored config
     */
    public static SaltbootConfig createConfig(User user, String name, SaltbootConfig parent,
            Map<String, Object> partitioning) {
        SaltbootConfig config = new SaltbootConfig(user.getOrg(), name);
        config.setParent(parent);
        config.setPartitioning(partitioning);
        return SaltbootFactory.save(config);
    }
}
