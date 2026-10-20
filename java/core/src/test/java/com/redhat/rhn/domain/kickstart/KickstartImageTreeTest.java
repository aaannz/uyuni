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
package com.redhat.rhn.domain.kickstart;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.redhat.rhn.common.hibernate.HibernateFactory;
import com.redhat.rhn.domain.channel.ChannelFactoryTest;
import com.redhat.rhn.domain.image.ImageInfo;
import com.redhat.rhn.domain.image.OSImageStoreUtils;
import com.redhat.rhn.testing.KickstartBaseTest;
import com.redhat.rhn.testing.SaltTestCaseExtension;
import com.redhat.rhn.testing.TestUtils;

import org.hibernate.Session;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

/**
 * Tests for image based {@link KickstartableTree}s (saltboot) and for the factory lookups that must not hand
 * them out to the kickstart code.
 */
@ExtendWith(SaltTestCaseExtension.class)
public class KickstartImageTreeTest extends KickstartBaseTest {

    private ImageInfo image;
    private KickstartableTree imageTree;

    @BeforeEach
    public void setUp() throws Exception {
        image = KickstartTestUtils.createSaltbootImage(user);
        imageTree = KickstartTestUtils.createImageTree(user, image, "saltboot-tree-" + TestUtils.randomString());
    }

    @Test
    public void testImageTreePersistsWithoutChannel() {
        TestUtils.flushAndEvict(imageTree);
        KickstartableTree reloaded = HibernateFactory.getSession().get(KickstartableTree.class, imageTree.getId());

        assertTrue(reloaded.isImageTree());
        assertNull(reloaded.getChannel());
        assertEquals(image.getId(), reloaded.getImage().getId());
        assertTrue(reloaded.getInstallType().isSaltboot());
    }

    @Test
    public void testKernelAndInitrdPathsComeFromImageFiles() {
        String prefix = OSImageStoreUtils.getOSImageStorePathForImage(image);

        assertArrayEquals(new String[] {prefix + "kernel"}, imageTree.getDefaultKernelPaths());
        assertArrayEquals(new String[] {prefix + "initrd"}, imageTree.getDefaultInitrdPaths());
        assertNull(imageTree.getKernelXenPath());
        assertNull(imageTree.getInitrdXenPath());
        assertFalse(imageTree.doesParaVirt());
    }

    @Test
    public void testMissingKernelFileGivesNoPaths() throws Exception {
        ImageInfo noKernel = KickstartTestUtils.createSaltbootImage(user);
        noKernel.getImageFiles().removeIf(f -> "kernel".equals(f.getType()));
        KickstartableTree tree = KickstartTestUtils.createImageTree(user, noKernel, "no-kernel-" +
                TestUtils.randomString());

        assertEquals(0, tree.getDefaultKernelPaths().length);
        assertEquals(1, tree.getDefaultInitrdPaths().length);
    }

    @Test
    public void testSourceCheckRejectsChannelAndImage() throws Exception {
        imageTree.setChannel(ChannelFactoryTest.createTestChannel(user));

        assertThrows(ConstraintViolationException.class, () -> TestUtils.saveAndFlush(imageTree));
    }

    @Test
    public void testSourceCheckRejectsNeitherChannelNorImage() {
        imageTree.setImage(null);

        assertThrows(ConstraintViolationException.class, () -> TestUtils.saveAndFlush(imageTree));
    }

    @Test
    public void testKickstartLookupsIgnoreImageTrees() {
        String label = imageTree.getLabel();
        imageTree.setCobblerId("cobbler-id-" + TestUtils.randomString());
        KickstartFactory.saveKickstartableTree(imageTree);

        assertNull(KickstartFactory.lookupKickstartTreeByLabel(label, user.getOrg()));
        assertNull(KickstartFactory.lookupKickstartTreeByLabel(label));
        assertNull(KickstartFactory.lookupKickstartTreeByIdAndOrg(imageTree.getId(), user.getOrg()));
        assertNull(KickstartFactory.findTreeById(imageTree.getId(), user.getOrg().getId()));
        assertNull(KickstartFactory.lookupKickstartTreeByCobblerIdOrXenId(imageTree.getCobblerId()));
        assertFalse(KickstartFactory.listTreesByOrg(user.getOrg()).contains(imageTree));
        assertFalse(KickstartFactory.lookupAccessibleTreesByOrg(user.getOrg()).contains(imageTree));
        assertFalse(KickstartFactory.listCandidatesForBacksync().contains(imageTree));
        assertFalse(KickstartFactory.lookupKickstartTrees().contains(imageTree));
        assertFalse(KickstartFactory.listUnsyncedKickstartTrees().contains(imageTree));
    }

    @Test
    public void testInstallTypeListHidesSaltboot() {
        List<KickstartInstallType> types = KickstartFactory.lookupKickstartInstallTypes();

        assertFalse(types.isEmpty());
        assertTrue(types.stream().noneMatch(KickstartInstallType::isSaltboot));
        assertNotNull(KickstartFactory.lookupKickstartInstallTypeByLabel(KickstartInstallType.SALTBOOT));
    }

    @Test
    public void testTreeLabelExistsSeesAllTrees() throws Exception {
        KickstartableTree channelTree = KickstartableTreeTest.createTestKickstartableTree(
                ChannelFactoryTest.createTestChannel(user));

        assertTrue(KickstartFactory.treeLabelExists(imageTree.getLabel(), user.getOrg()));
        assertTrue(KickstartFactory.treeLabelExists(channelTree.getLabel(), channelTree.getOrg()));
        assertFalse(KickstartFactory.treeLabelExists("unused-" + TestUtils.randomString(), user.getOrg()));
    }

    @Test
    public void testImageTreeLookups() throws Exception {
        KickstartableTree second = KickstartTestUtils.createImageTree(user, image, "second-" +
                TestUtils.randomString());
        ImageInfo other = KickstartTestUtils.createSaltbootImage(user);
        KickstartableTree otherTree = KickstartTestUtils.createImageTree(user, other, "other-" +
                TestUtils.randomString());

        assertEquals(imageTree, KickstartFactory.lookupImageTreeByLabel(imageTree.getLabel(), user.getOrg()));
        assertEquals(List.of(imageTree, second).stream().map(KickstartableTree::getLabel).sorted().toList(),
                KickstartFactory.listImageTrees(image).stream().map(KickstartableTree::getLabel).toList());
        assertTrue(KickstartFactory.listImageTreesByOrg(user.getOrg()).containsAll(
                List.of(imageTree, second, otherTree)));

        KickstartableTree channelTree = KickstartableTreeTest.createTestKickstartableTree(
                ChannelFactoryTest.createTestChannel(user));
        assertNull(KickstartFactory.lookupImageTreeByLabel(channelTree.getLabel(), channelTree.getOrg()));
        assertFalse(KickstartFactory.listImageTreesByOrg(user.getOrg()).contains(channelTree));
    }

    @Test
    public void testImageDeletionCascadesToTrees() {
        Long treeId = imageTree.getId();
        Session session = HibernateFactory.getSession();
        session.flush();
        session.createNativeQuery("DELETE FROM suseImageInfo WHERE id = :id", Integer.class)
                .setParameter("id", image.getId())
                .executeUpdate();
        session.clear();

        assertNull(session.get(KickstartableTree.class, treeId));
    }
}
