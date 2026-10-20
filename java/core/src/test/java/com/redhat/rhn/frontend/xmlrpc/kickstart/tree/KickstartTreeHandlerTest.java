/*
 * Copyright (c) 2009--2014 Red Hat, Inc.
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
package com.redhat.rhn.frontend.xmlrpc.kickstart.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.redhat.rhn.domain.channel.Channel;
import com.redhat.rhn.domain.channel.ChannelFactoryTest;
import com.redhat.rhn.domain.image.ImageInfo;
import com.redhat.rhn.domain.kickstart.KickstartData;
import com.redhat.rhn.domain.kickstart.KickstartDataTest;
import com.redhat.rhn.domain.kickstart.KickstartFactory;
import com.redhat.rhn.domain.kickstart.KickstartInstallType;
import com.redhat.rhn.domain.kickstart.KickstartTestUtils;
import com.redhat.rhn.domain.kickstart.KickstartableTree;
import com.redhat.rhn.domain.kickstart.KickstartableTreeTest;
import com.redhat.rhn.frontend.dto.kickstart.KickstartableTreeDetail;
import com.redhat.rhn.frontend.xmlrpc.BaseHandlerTestCase;
import com.redhat.rhn.frontend.xmlrpc.InvalidChannelLabelException;
import com.redhat.rhn.frontend.xmlrpc.NoSuchImageException;
import com.redhat.rhn.frontend.xmlrpc.kickstart.KickstartHandler;
import com.redhat.rhn.testing.TestUtils;

import com.suse.manager.autoinstallation.ImageTreeManager;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * KickstartHandlerTest
 */
public class KickstartTreeHandlerTest extends BaseHandlerTestCase {

    private KickstartTreeHandler handler = new KickstartTreeHandler();
    private KickstartHandler ksHandler = new KickstartHandler();

    @Test
    public void testListKickstartableTrees() throws Exception {
        Channel baseChan = ChannelFactoryTest.createTestChannel(admin);
        KickstartableTree testTree = KickstartableTreeTest.
            createTestKickstartableTree(baseChan);
        List ksTrees = handler.list(admin,
                baseChan.getLabel());
        assertFalse(ksTrees.isEmpty());

        boolean found = false;
        for (Object ksTreeIn : ksTrees) {
            KickstartableTree t = (KickstartableTree) ksTreeIn;
            if (t.getId().equals(testTree.getId())) {
                found = true;
                break;
            }
        }
        assertTrue(found);
    }

    @Test
    public void testCreateKickstartableTree() throws Exception {
        String label = TestUtils.randomString();
        List trees = KickstartFactory.
            lookupAccessibleTreesByOrg(admin.getOrg());
        int origCount = 0;
        if (trees != null) {
            origCount = trees.size();
        }
        Channel baseChan = ChannelFactoryTest.createTestChannel(admin);
        String kernelOptions = "self_update=0";
        String postKernelOptions = "self_update=1";
        handler.create(admin, label,
                KickstartableTreeTest.KICKSTART_TREE_PATH.getAbsolutePath(),
                baseChan.getLabel(), KickstartInstallType.RHEL_7, kernelOptions, postKernelOptions);
        KickstartableTreeDetail details = handler.getDetails(admin, label);
        assertEquals(origCount + 1, KickstartFactory.
                lookupAccessibleTreesByOrg(admin.getOrg()).size());
        assertEquals(details.getBasePath(), KickstartableTreeTest.KICKSTART_TREE_PATH.getAbsolutePath());
        assertEquals(details.getChannel().getLabel(), baseChan.getLabel());
        assertEquals(details.getInstallType().getLabel(), KickstartInstallType.RHEL_7);
        assertTrue(details.getKernelOptions().contains(kernelOptions));
        assertEquals(details.getKernelOptionsPost(), postKernelOptions);
    }

    @Test
    public void testEditKickstartableTree() throws Exception {
        Channel baseChan = ChannelFactoryTest.createTestChannel(admin);
        KickstartableTree testTree = KickstartableTreeTest.
            createTestKickstartableTree(baseChan);
        String newBase = "/tmp/kickstart/new-base-path";
        KickstartableTreeTest.createKickstartTreeItems(new File(newBase), admin);
        Channel newChan = ChannelFactoryTest.createTestChannel(admin);
        String kernelOptions = "self_update=0";
        String postKernelOptions = "self_update=1";
        handler.update(admin, testTree.getLabel(),
                newBase, newChan.getLabel(),
                testTree.getInstallType().getLabel(), kernelOptions, postKernelOptions);

        assertEquals(testTree.getBasePath(), newBase);
        assertEquals(testTree.getChannel(), newChan);
        assertNotNull(testTree.getInstallType());
        assertTrue(testTree.getKernelOptions().contains(kernelOptions));
        assertEquals(testTree.getKernelOptionsPost(), postKernelOptions);
    }

    @Test
    public void testRenameKickstartableTree() throws Exception {
        Channel baseChan = ChannelFactoryTest.createTestChannel(admin);
        KickstartableTree testTree = KickstartableTreeTest.
            createTestKickstartableTree(baseChan);
        String newLabel = "newlabel-" + TestUtils.randomString();
        handler.rename(admin, testTree.getLabel(), newLabel);
        assertEquals(newLabel, testTree.getLabel());
    }

    @Test
    public void testDeleteKickstartableTree() throws Exception {
        Channel baseChan = ChannelFactoryTest.createTestChannel(admin);
        KickstartableTree testTree = KickstartableTreeTest.
            createTestKickstartableTree(baseChan);
        String label = testTree.getLabel();
        handler.delete(admin, label);
        assertNull(KickstartFactory.lookupKickstartTreeByLabel(label, admin.getOrg()));
    }

    @Test
    public void testDeleteTreeAndProfiles() throws Exception {

        KickstartData ks  = KickstartDataTest.createKickstartWithProfile(admin);
        KickstartableTree testTree = ks.getKickstartDefaults().getKstree();
        Channel channel = testTree.getChannel();

        // verify our setup... should have 1 tree and 1 profile associated w/it
        List ksTrees = handler.list(admin, channel.getLabel());
        List ksProfiles = ksHandler.listKickstarts(admin);
        assertNotNull(ksTrees);
        assertNotNull(ksProfiles);
        int numKsTrees = ksTrees.size();
        int numKsProfiles = ksProfiles.size();

        // execute test...
        int result = handler.deleteTreeAndProfiles(admin, testTree.getLabel());
        assertEquals(1, result);

        // verify that both the tree and associated profile no longer exist
        ksTrees = handler.list(admin, channel.getLabel());
        ksProfiles = ksHandler.listKickstarts(admin);
        assertNotNull(ksTrees);
        assertNotNull(ksProfiles);
        assertEquals(numKsTrees - 1, ksTrees.size());
        assertTrue(ksProfiles.size() < numKsProfiles);
    }

    @Test
    public void testListTreeTypes() {
        List types = handler.listInstallTypes(admin);
        assertNotNull(types);
        assertFalse(types.isEmpty());
        System.out.println("type: " + types.get(0).getClass().getName());
        assertInstanceOf(KickstartInstallType.class, types.get(0));
    }

    @Test
    public void testListInstallTypesHidesSaltboot() {
        List types = handler.listInstallTypes(admin);

        assertTrue(types.stream().noneMatch(t -> ((KickstartInstallType) t).isSaltboot()));
    }

    @Test
    public void testCreateRejectsSaltbootInstallType() throws Exception {
        Channel baseChan = ChannelFactoryTest.createTestChannel(admin);

        assertThrows(NoSuchKickstartInstallTypeException.class, () -> handler.create(admin, TestUtils.randomString(),
                KickstartableTreeTest.KICKSTART_TREE_PATH.getAbsolutePath(), baseChan.getLabel(),
                KickstartInstallType.SALTBOOT, "", ""));
    }

    @Test
    public void testUpdateRejectsSaltbootInstallType() throws Exception {
        Channel baseChan = ChannelFactoryTest.createTestChannel(admin);
        KickstartableTree testTree = KickstartableTreeTest.createTestKickstartableTree(baseChan);

        assertThrows(NoSuchKickstartInstallTypeException.class, () -> handler.update(admin, testTree.getLabel(),
                testTree.getBasePath(), baseChan.getLabel(), KickstartInstallType.SALTBOOT, "", ""));
    }

    @Test
    public void testImageTreeIsInvisibleToTreeCalls() throws Exception {
        String label = "image-tree-" + TestUtils.randomString();
        KickstartTestUtils.createImageTree(admin, KickstartTestUtils.createSaltbootImage(admin), label);

        assertThrows(InvalidChannelLabelException.class, () -> handler.getDetails(admin, label));
        assertThrows(InvalidKickstartTreeException.class, () -> handler.delete(admin, label));
        assertThrows(InvalidKickstartTreeException.class, () -> handler.rename(admin, label, "renamed-" + label));
    }

    @Test
    public void testImageTreeRoundTrip() throws Exception {
        ImageInfo image = KickstartTestUtils.createSaltbootImageWithBootFiles(admin);
        try {
            String label = "image-tree-" + TestUtils.randomString(5);
            Integer imageId = image.getId().intValue();

            assertEquals(1, handler.createFromImage(admin, label, imageId, KickstartInstallType.SALTBOOT,
                    "a=1", "b=2"));

            Map<String, Object> details = handler.getImageTreeDetails(admin, label);
            assertEquals(label, details.get("label"));
            assertEquals(imageId, details.get("image_id"));
            assertEquals(KickstartInstallType.SALTBOOT, details.get("install_type"));
            assertEquals("a=1", details.get("kernel_options"));
            assertEquals("b=2", details.get("kernel_options_post"));
            assertEquals(false, details.get("generated"));
            assertTrue(handler.listImageTrees(admin).stream().anyMatch(t -> label.equals(t.get("label"))));
            assertEquals(1, handler.listImageTrees(admin, imageId).size());

            assertEquals(1, handler.updateImageTree(admin, label, "c=3", ""));
            assertEquals("c=3", handler.getImageTreeDetails(admin, label).get("kernel_options"));

            assertEquals(1, handler.deleteImageTree(admin, label));
            assertThrows(InvalidKickstartTreeException.class, () -> handler.getImageTreeDetails(admin, label));
            assertTrue(handler.listImageTrees(admin, imageId).isEmpty());
        }
        finally {
            KickstartTestUtils.deleteBootFiles(image);
        }
    }

    @Test
    public void testCreateFromImageWithoutOptions() throws Exception {
        ImageInfo image = KickstartTestUtils.createSaltbootImageWithBootFiles(admin);
        try {
            String label = "image-tree-" + TestUtils.randomString(5);

            handler.createFromImage(admin, label, image.getId().intValue(), KickstartInstallType.SALTBOOT);

            assertEquals("", handler.getImageTreeDetails(admin, label).get("kernel_options"));
        }
        finally {
            KickstartTestUtils.deleteBootFiles(image);
        }
    }

    @Test
    public void testCreateFromImageRejectsNonImageInstallType() throws Exception {
        ImageInfo image = KickstartTestUtils.createSaltbootImage(admin);

        assertThrows(NoSuchKickstartInstallTypeException.class, () -> handler.createFromImage(admin,
                TestUtils.randomString(), image.getId().intValue(), KickstartInstallType.RHEL_7));
    }

    @Test
    public void testCreateFromImageRejectsUnknownImage() {
        assertThrows(NoSuchImageException.class, () -> handler.createFromImage(admin, TestUtils.randomString(),
                Integer.MAX_VALUE, KickstartInstallType.SALTBOOT));
    }

    @Test
    public void testCreateFromImageRejectsImageWithoutBootFiles() throws Exception {
        ImageInfo image = KickstartTestUtils.createSaltbootImage(admin);
        image.getImageFiles().removeIf(f -> "kernel".equals(f.getType()));

        assertThrows(InvalidKickstartTreeException.class, () -> handler.createFromImage(admin,
                "no-boot-files-" + TestUtils.randomString(5), image.getId().intValue(),
                KickstartInstallType.SALTBOOT));
    }

    @Test
    public void testImageTreeCallsRejectUnknownTree() {
        assertThrows(InvalidKickstartTreeException.class, () -> handler.getImageTreeDetails(admin, "unknown"));
        assertThrows(InvalidKickstartTreeException.class, () -> handler.updateImageTree(admin, "unknown", "", ""));
        assertThrows(InvalidKickstartTreeException.class, () -> handler.deleteImageTree(admin, "unknown"));
    }

    @Test
    public void testDeleteImageTreeRefusesGeneratedTree() throws Exception {
        ImageInfo image = KickstartTestUtils.createSaltbootImageWithBootFiles(admin);
        try {
            KickstartableTree tree = ImageTreeManager.ensureGeneratedTree(image).orElseThrow();

            assertTrue((Boolean) handler.getImageTreeDetails(admin, tree.getLabel()).get("generated"));
            assertThrows(InvalidKickstartTreeException.class, () -> handler.deleteImageTree(admin, tree.getLabel()));
        }
        finally {
            KickstartTestUtils.deleteBootFiles(image);
        }
    }

    @Test
    public void testCreateFromImageRejectsLabelOfChannelTree() throws Exception {
        ImageInfo image = KickstartTestUtils.createSaltbootImageWithBootFiles(admin);
        try {
            KickstartableTree channelTree = KickstartableTreeTest.createTestKickstartableTree(
                    ChannelFactoryTest.createTestChannel(admin));

            assertThrows(InvalidKickstartTreeException.class, () -> handler.createFromImage(admin,
                    channelTree.getLabel(), image.getId().intValue(), KickstartInstallType.SALTBOOT));
        }
        finally {
            KickstartTestUtils.deleteBootFiles(image);
        }
    }
}
