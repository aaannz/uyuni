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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.redhat.rhn.domain.image.ImageInfo;
import com.redhat.rhn.testing.JMockBaseTestCaseWithUser;

import com.suse.manager.saltboot.SaltbootUtils;

import org.cobbler.CobblerConnection;
import org.cobbler.Distro;
import org.cobbler.Profile;
import org.cobbler.test.MockConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link SaltbootUtils}.
 */
public class SaltbootUtilsTest extends JMockBaseTestCaseWithUser {

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
    public void testCobblerName() {
        String orgName = user.getOrg().getName();
        Long orgId = user.getOrg().getId();

        // Test with a simple label
        String label = "my-test-label";
        String expectedName = String.format("%s:S:%d:%s", label, orgId, orgName);
        assertEquals(expectedName, SaltbootUtils.makeCobblerName(user.getOrg(), label));

        // Test with a label containing spaces
        String labelWithSpaces = "my test label";
        String expectedNameWithSpaces = String.format("my_test_label:S:%d:%s", orgId, orgName);
        assertEquals(expectedNameWithSpaces, SaltbootUtils.makeCobblerName(user.getOrg(), labelWithSpaces));

        // Test with a label containing spaces and special chars
        String labelWithSpacesAndChars = "my test %label%2";
        String expectedNameWithSpacesAndChars = String.format("my_test_label2:S:%d:%s", orgId, orgName);
        assertEquals(expectedNameWithSpacesAndChars, SaltbootUtils.makeCobblerName(
                user.getOrg(), labelWithSpacesAndChars));
    }

    @Test
    public void testMakeCobblerNameVR() throws Exception {
        String imageName = "my-image";
        String imageVersion = "1.2.3";
        int imageRevision = 4;
        ImageInfo imageInfo = createImageHelper(user, imageName, imageVersion, imageRevision);

        String orgName = user.getOrg().getName();
        Long orgId = user.getOrg().getId();

        String expectedName = String.format("%s-%s-%d:S:%d:%s",
                imageName, imageVersion, imageRevision, orgId, orgName);
        assertEquals(expectedName, SaltbootUtils.makeCobblerNameVR(imageInfo));

        // Test with spaces in name
        imageInfo.setName("my image");
        String expectedNameWithSpaces = String.format("my_image-%s-%d:S:%d:%s",
                imageVersion, imageRevision, orgId, orgName);
        assertEquals(expectedNameWithSpaces, SaltbootUtils.makeCobblerNameVR(imageInfo));
    }

    @Test
    public void testCreateSaltbootDistro() throws Exception {
        ImageInfo imageInfo = createImageHelper(user, "my-image", "1.2.3", 4);
        SaltbootUtils.createSaltbootDistro(imageInfo, Distro.list(client), client);

        String nameVR = SaltbootUtils.makeCobblerNameVR(imageInfo);
        String nameV = SaltbootUtils.makeCobblerName(user.getOrg(), "my-image-1.2.3");
        String name = SaltbootUtils.makeCobblerName(user.getOrg(), "my-image");
        String defaultName = SaltbootUtils.makeCobblerName(user.getOrg(), SaltbootUtils.DEFAULT_BOOT_IMAGE);

        // Check that the distro was created
        Distro distro = Distro.lookupByName(client, nameVR);
        assertNotNull(distro);
        assertEquals(nameVR, distro.getName());

        // Check that all profiles were created and point to the new distro
        Profile profileVR = Profile.lookupByName(client, nameVR);
        assertNotNull(profileVR);
        assertEquals(distro.getName(), profileVR.getDistro().getName());

        Profile profileV = Profile.lookupByName(client, nameV);
        assertNotNull(profileV);
        assertEquals(distro.getName(), profileV.getDistro().getName());

        Profile profile = Profile.lookupByName(client, name);
        assertNotNull(profile);
        assertEquals(distro.getName(), profile.getDistro().getName());

        Profile defaultProfile = Profile.lookupByName(client, defaultName);
        assertNotNull(defaultProfile);
        assertEquals(distro.getName(), defaultProfile.getDistro().getName());
    }

    @Test
    public void testCreateSaltbootDistroIdempotent() throws Exception {
        ImageInfo imageInfo = createImageHelper(user, "my-image", "1.2.3", 4);

        // First call
        SaltbootUtils.createSaltbootDistro(imageInfo, Distro.list(client), client);

        // Check that the distro was created
        String nameVR = SaltbootUtils.makeCobblerNameVR(imageInfo);
        Distro distro = Distro.lookupByName(client, nameVR);
        assertNotNull(distro);
        int distroCount = Distro.list(client).size();
        int profileCount = Profile.list(client).size();

        // Second call should be a no-op
        SaltbootUtils.createSaltbootDistro(imageInfo, Distro.list(client), client);

        assertEquals(distroCount, Distro.list(client).size());
        assertEquals(profileCount, Profile.list(client).size());
    }

    @Test
    public void testDeleteSaltbootDistro() throws Exception {
        // Create two versions of an image
        ImageInfo imageV1 = createImageHelper(user, "my-image", "1.0.0", 1);
        ImageInfo imageV2 = createImageHelper(user, "my-image", "1.0.0", 2);

        SaltbootUtils.createSaltbootDistro(imageV1, Distro.list(client), client);
        SaltbootUtils.createSaltbootDistro(imageV2, Distro.list(client), client);

        String nameV1VR = SaltbootUtils.makeCobblerNameVR(imageV1);
        String nameV2VR = SaltbootUtils.makeCobblerNameVR(imageV2);
        String nameV = SaltbootUtils.makeCobblerName(user.getOrg(), "my-image-1.0.0");
        String name = SaltbootUtils.makeCobblerName(user.getOrg(), "my-image");
        String defaultName = SaltbootUtils.makeCobblerName(user.getOrg(), SaltbootUtils.DEFAULT_BOOT_IMAGE);

        // Profiles should point to the newest version (v2)
        assertEquals(nameV2VR, Profile.lookupByName(client, nameV).getDistro().getName());
        assertEquals(nameV2VR, Profile.lookupByName(client, name).getDistro().getName());
        assertEquals(nameV2VR, Profile.lookupByName(client, defaultName).getDistro().getName());

        // Delete the newer version
        SaltbootUtils.deleteSaltbootDistro(imageV2, client);

        // Check that distro and its private profile are gone
        assertNull(Distro.lookupByName(client, nameV2VR));
        assertNull(Profile.lookupByName(client, nameV2VR));

        // Check that other profiles now point to the older version (v1)
        assertEquals(nameV1VR, Profile.lookupByName(client, nameV).getDistro().getName());
        assertEquals(nameV1VR, Profile.lookupByName(client, name).getDistro().getName());
        assertEquals(nameV1VR, Profile.lookupByName(client, defaultName).getDistro().getName());

        // Delete the last version
        SaltbootUtils.deleteSaltbootDistro(imageV1, client);

        // Check that distro and its private profile are gone
        assertNull(Distro.lookupByName(client, nameV1VR));
        assertNull(Profile.lookupByName(client, nameV1VR));

        // The other profiles should still exist, but point to a now-deleted distro.
        // The delete logic doesn't clean them up if there's no replacement.
        assertNotNull(Profile.lookupByName(client, nameV));
        assertNotNull(Profile.lookupByName(client, name));
        assertNotNull(Profile.lookupByName(client, defaultName));
    }
}
