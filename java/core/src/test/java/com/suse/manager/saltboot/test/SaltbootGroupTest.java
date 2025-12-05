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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.redhat.rhn.domain.image.ImageInfo;
import com.redhat.rhn.domain.server.MinionServer;
import com.redhat.rhn.domain.server.ServerGroup;
import com.redhat.rhn.domain.server.test.MinionServerFactoryTest;
import com.redhat.rhn.testing.JMockBaseTestCaseWithUser;
import com.redhat.rhn.testing.TestUtils;

import com.suse.manager.saltboot.SaltbootGroup;
import com.suse.manager.saltboot.SaltbootServer;

import org.junit.jupiter.api.Test;

import javax.persistence.PersistenceException;

/**
 * Test for {@link SaltbootGroup}.
 */
public class SaltbootGroupTest  extends JMockBaseTestCaseWithUser {

    @Test
    public void testCreateSaltbootProfile() throws Exception {
        // Create a server group and an image
        createImageHelper(user, "my-image", "1.0.0", 1, "mykernel", "myinitrd");

        ServerGroup group = createSaltbootGroupHelper(user, "my-saltboot-group", "my-image", "1.0.0");

        // Create SaltbootGroup from the group data
        group.setSaltbootGroup(new SaltbootGroup(group));

        // Verify saltboot group is present and generating correct results
        assertTrue(group.getSaltbootGroup().isPresent());
        SaltbootGroup sg = group.getSaltbootGroup().get();
        assertNotNull(sg);

        @SuppressWarnings("checkstyle:LineLength")
        String expectedGrubEntry = """
menuentry 'my-saltboot-group:S:1:Org' --class gnu-linux --class gnu --class os {
echo 'Loading kernel ...'
clinux /images/mykernel panic=60 splash=silent DISABLE_ID_PREFIX=1 MINION_ID_PREFIX=my-saltboot-group MASTER=my-saltboot-group.example.com
echo 'Loading initial ramdisk ...'
cinitrd /images/myinitrd
echo '...done'
}
""";
        assertEquals(expectedGrubEntry, sg.getGrubEntry());
    }

    @Test
    public void testCreateSaltbootProfileWithDefault() throws Exception {
        // Create a server group and an image
        createImageHelper(user, "my-image", "2.0.0", 1);

        // Create Saltboot Profile for group
        ServerGroup group = createSaltbootGroupHelper(user, "my-saltboot-group-2");
        // Create SaltbootGroup from the group data
        group.setSaltbootGroup(new SaltbootGroup(group));

        // Verify saltboot group is present and generating correct results
        assertTrue(group.getSaltbootGroup().isPresent());
        SaltbootGroup sg = group.getSaltbootGroup().get();
        assertNotNull(sg);

        @SuppressWarnings("checkstyle:LineLength")
        String expectedGrubEntry = """
menuentry 'my-saltboot-group:S:1:Org' --class gnu-linux --class gnu --class os {
echo 'Loading kernel ...'
clinux /images/kernel panic=60 splash=silent DISABLE_ID_PREFIX=1 MINION_ID_PREFIX=my-saltboot-group-2 MASTER=my-saltboot-group-2.example.com
echo 'Loading initial ramdisk ...'
cinitrd /images/initrd
echo '...done'
}
""";
        assertEquals(expectedGrubEntry, sg.getGrubEntry());
    }

    @Test
    public void testCreateSaltbootProfileWithImageOnlyRevision() throws Exception {
        // Create a server group and the images
        ImageInfo image1 = createImageHelper(user, "my-branch-image", "1.0.0", 1, "branchkernel", "branchinitrd");
        ImageInfo image2 = createImageHelper(user, "my-branch-image", "1.0.0", 2, "branchkernel.2", "branchinitrd.2");

        // Create Saltboot Profile for group
        ServerGroup group = createSaltbootGroupHelper(user, "my-saltboot-group-3", "my-branch-image", null);
        // Create SaltbootGroup from the group data
        group.setSaltbootGroup(new SaltbootGroup(group));

        // Verify saltboot group is present and generating correct results
        assertTrue(group.getSaltbootGroup().isPresent());
        SaltbootGroup sg = group.getSaltbootGroup().get();
        assertNotNull(sg);

        @SuppressWarnings("checkstyle:LineLength")
        String expectedGrubEntry = """
menuentry 'my-saltboot-group:S:1:Org' --class gnu-linux --class gnu --class os {
echo 'Loading kernel ...'
clinux /images/branchkernel.2 panic=60 splash=silent DISABLE_ID_PREFIX=1 MINION_ID_PREFIX=my-saltboot-group-2 MASTER=my-saltboot-group-3.example.com
echo 'Loading initial ramdisk ...'
cinitrd /images/branchinitrd.2
echo '...done'
}
""";
        assertEquals(expectedGrubEntry, sg.getGrubEntry());
    }

    @Test
    public void testCreateSaltbootProfileWithImage() throws Exception {
        // Create a server group and the images
        createImageHelper(user, "my-branch-image-3", "1.0.0", 1, "branchkernel", "branchinitrd");
        createImageHelper(user, "my-branch-image-3", "1.0.0", 2, "branchkernel", "branchinitrd");
        createImageHelper(user, "my-branch-image-3", "2.0.0", 1, "branchkernel2", "branchinitrd2");

        // Create Saltboot Profile for group
        ServerGroup group = createSaltbootGroupHelper(user, "my-saltboot-group-3", "my-branch-image-3", null);
        // Create SaltbootGroup from the group data
        group.setSaltbootGroup(new SaltbootGroup(group));

        // Verify saltboot group is present and generating correct results
        assertTrue(group.getSaltbootGroup().isPresent());
        SaltbootGroup sg = group.getSaltbootGroup().get();
        assertNotNull(sg);

        @SuppressWarnings("checkstyle:LineLength")
        String expectedGrubEntry = """
menuentry 'my-saltboot-group:S:1:Org' --class gnu-linux --class gnu --class os {
echo 'Loading kernel ...'
clinux /images/branchkernel2 panic=60 splash=silent DISABLE_ID_PREFIX=1 MINION_ID_PREFIX=my-saltboot-group-2 MASTER=my-saltboot-group-3.example.com
echo 'Loading initial ramdisk ...'
cinitrd /images/branchinitrd2
echo '...done'
}
""";
        assertEquals(expectedGrubEntry, sg.getGrubEntry());
    }

    @Test
    public void testCreateSaltbootProfileWithImageVersion() throws Exception {
        createImageHelper(user, "my-branch-image-4", "3.0.0", 1, "branchkernel", "branchinitrd");
        createImageHelper(user, "my-branch-image-4", "4.0.0", 1, "branchkernel4", "branchinitrd4");

        ServerGroup group = createSaltbootGroupHelper(user, "my-saltboot-group-4", "my-branch-image-4", "4.0.0");
        group.setSaltbootGroup(new SaltbootGroup(group));

        // Verify saltboot group is present and generating correct results
        assertTrue(group.getSaltbootGroup().isPresent());
        SaltbootGroup sg = group.getSaltbootGroup().get();
        assertNotNull(sg);

        @SuppressWarnings("checkstyle:LineLength")
        String expectedGrubEntry = """
menuentry 'my-saltboot-group:S:1:Org' --class gnu-linux --class gnu --class os {
echo 'Loading kernel ...'
clinux /images/branchkernel4 panic=60 splash=silent DISABLE_ID_PREFIX=1 MINION_ID_PREFIX=my-saltboot-group-4 MASTER=my-saltboot-group-3.example.com
echo 'Loading initial ramdisk ...'
cinitrd /images/branchinitrd4
echo '...done'
}
""";
        assertEquals(expectedGrubEntry, sg.getGrubEntry());
    }

    @Test
    public void testDeleteSaltbootProfile() throws Exception {
        createImageHelper(user, "my-image-branch5", "1.0.0", 1);
        ServerGroup group = createSaltbootGroupHelper(user, "my-group-to-delete", "my-image-branch5", "1.0.0");

        // Verify saltboot group is present
        assertTrue(group.getSaltbootGroup().isPresent());
        SaltbootGroup sg = group.getSaltbootGroup().get();
        assertNotNull(sg);

        // Deletion should not cause any trouble
        TestUtils.removeObject(sg);
        assertNull(group.getSaltbootGroup());
    }

    @Test
    public void testDeleteSaltbootProfileWhenSystemIsAssigned() throws Exception {
        ImageInfo image = createImageHelper(user, "my-image-branch6", "1.0.0", 1);
        ServerGroup group = createSaltbootGroupHelper(user, "my-group-to-delete2", "my-image-branch5", "1.0.0");

        // Verify saltboot group is present
        assertTrue(group.getSaltbootGroup().isPresent());
        SaltbootGroup sg = group.getSaltbootGroup().get();
        assertNotNull(sg);

        MinionServer minion = MinionServerFactoryTest.createTestMinionServer(user);
        minion.setMinionId("test-minion");
        new SaltbootServer(minion, sg, "root", "salt", image, "");

        // Deletion should not be possible
        assertThrows(PersistenceException.class, () -> TestUtils.removeObject(sg));
        assertNotNull(group.getSaltbootGroup());

        // Remove the system and try again
        TestUtils.removeObject(minion);
        assertDoesNotThrow(() -> TestUtils.removeObject(sg));
        assertNull(group.getSaltbootGroup());
    }
}
