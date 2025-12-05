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

package com.suse.manager.saltboot.test;

import static com.suse.manager.saltboot.test.SaltbootTestUtils.createImageHelper;
import static com.suse.manager.saltboot.test.SaltbootTestUtils.createSaltbootGroupHelper;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.redhat.rhn.domain.image.ImageInfo;
import com.redhat.rhn.domain.server.MinionServer;
import com.redhat.rhn.domain.server.ServerGroup;
import com.redhat.rhn.domain.server.test.MinionServerFactoryTest;
import com.redhat.rhn.testing.JMockBaseTestCaseWithUser;

import com.suse.manager.saltboot.SaltbootUtils;

import org.cobbler.CobblerConnection;
import org.cobbler.Distro;
import org.cobbler.Network;
import org.cobbler.Profile;
import org.cobbler.SystemRecord;
import org.cobbler.test.MockConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

public class SaltbootServerTest extends JMockBaseTestCaseWithUser {

    private CobblerConnection client;

    @BeforeEach
    public void setUp() throws Exception {
        super.setUp();
        MockConnection.clear();
        client = new MockConnection("http://localhost", "token");
    }

    @AfterEach
    public void teardown() throws Exception {
        MockConnection.clear();
        super.tearDown();
    }

    @Test
    public void testCreateSaltbootSystem() throws Exception {
        // 1. Setup
        ImageInfo image = createImageHelper(user, "my-image", "1.0.0", 1);
        SaltbootUtils.createSaltbootDistro(image, Distro.list(client), client);
        String imageProfileName = SaltbootUtils.makeCobblerNameVR(image);

        ServerGroup group = createSaltbootGroupHelper(user, "my-saltboot-group");
//        SaltbootUtils.createSaltbootProfile(group, imageProfileName, false, client);
        String groupName = group.getName();

        MinionServer minion = MinionServerFactoryTest.createTestMinionServer(user);
        minion.setMinionId("test-minion-for-system");

        List<String> hwAddresses = List.of("AA:BB:CC:DD:EE:FF");
        String kernelParams = "custom_param=value otherparam=\"quoted value\"";

        // 2. Test system creation
//        SaltbootUtils.createSaltbootSystem(minion, "my-image-1.0.0-1", groupName, hwAddresses, kernelParams, client);

        String systemName = SaltbootUtils.makeCobblerName(user.getOrg(), minion.getMinionId());
        SystemRecord system = SystemRecord.lookupByName(client, systemName);
        assertNotNull(system);
        assertEquals(imageProfileName, system.getProfile().getName());
        // Test system has one network interface by default, we need to look for our entry
        assertEquals(2, system.getNetworkInterfaces().size());
        assertTrue(system.getNetworkInterfaces().stream().anyMatch(
                nic -> nic.getMacAddress().equals("AA:BB:CC:DD:EE:FF")));
        assertTrue(system.isNetbootEnabled());

        String[] expectedOpts =
                ("custom_param=value otherparam=\"quoted value\" MINION_ID_PREFIX=my-saltboot-group " +
                        "MASTER=mybranch.example.com DISABLE_ID_PREFIX=1")
                        .split(" ");
        String[] actualOpts = system.getKernelOptions().get().entrySet().stream().map(
                entry -> entry.getKey() + "=" + entry.getValue()).toArray(String[]::new);
        Arrays.sort(expectedOpts);
        Arrays.sort(actualOpts);
        assertEquals(String.join(" ", expectedOpts), String.join(" ", actualOpts));

        // 3. Test system update
        ImageInfo newImage = createImageHelper(user, "my-new-image", "2.0.0", 1);
        SaltbootUtils.createSaltbootDistro(newImage, Distro.list(client), client);
        String newImageProfileName = SaltbootUtils.makeCobblerNameVR(newImage);
//        SaltbootUtils.createSaltbootSystem(minion, "my-new-image-2.0.0-1",
//                groupName, hwAddresses, kernelParams, client);

        system = SystemRecord.lookupByName(client, systemName);
        assertNotNull(system);
        assertEquals(newImageProfileName, system.getProfile().getName());

        // 4. Test error conditions
//        assertThrows(SaltbootException.class, () -> SaltbootUtils.createSaltbootSystem(
//                        minion, "non-existent-image", groupName, hwAddresses, kernelParams, client),
//                "Should throw when image profile doesn't exist");
//        assertThrows(SaltbootException.class, () -> SaltbootUtils.createSaltbootSystem(
//                        minion, imageProfileName, "non-existent-group", hwAddresses, kernelParams, client),
//                "Should throw when group profile doesn't exist");

        // 5. Test MAC conflict resolution
        MinionServer conflictingMinion = MinionServerFactoryTest.createTestMinionServer(user);
        conflictingMinion.setMinionId("conflicting-minion");
        String conflictingSystemName = SaltbootUtils.makeCobblerName(user.getOrg(), "some-other-system");
        SystemRecord conflictingSystem = SystemRecord.create(client, conflictingSystemName,
                Profile.lookupByName(client, imageProfileName));
        Network net = new Network(client, "00:11:22:33:44:55");
        net.setMacAddress("00:11:22:33:44:55");
        conflictingSystem.setNetworkInterfaces(List.of(net));
        conflictingSystem.save();

//        SaltbootUtils.createSaltbootSystem(conflictingMinion, "my-image-1.0.0-1", groupName,
//                List.of("00:11:22:33:44:55"), "", client);

        assertNull(SystemRecord.lookupByName(client,
                conflictingSystemName), "Conflicting system should be deleted");
        assertNotNull(SystemRecord.lookupByName(client,
                SaltbootUtils.makeCobblerName(user.getOrg(), "conflicting-minion")));
    }

    @Test
    public void testCreateSaltbootServerFromPXEevent() throws Exception {

    }
}
