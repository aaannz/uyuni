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
package com.suse.manager.autoinstallation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.redhat.rhn.common.validator.ValidatorError;
import com.redhat.rhn.domain.channel.ChannelFactoryTest;
import com.redhat.rhn.domain.image.ImageInfo;
import com.redhat.rhn.domain.image.OSImageStoreUtils;
import com.redhat.rhn.domain.kickstart.KickstartFactory;
import com.redhat.rhn.domain.kickstart.KickstartTestUtils;
import com.redhat.rhn.domain.kickstart.KickstartableTree;
import com.redhat.rhn.domain.kickstart.KickstartableTreeTest;
import com.redhat.rhn.manager.kickstart.cobbler.CobblerXMLRPCHelper;
import com.redhat.rhn.testing.KickstartBaseTest;
import com.redhat.rhn.testing.SaltTestCaseExtension;
import com.redhat.rhn.testing.TestUtils;

import com.suse.manager.saltboot.SaltbootUtils;

import org.cobbler.CobblerConnection;
import org.cobbler.Distro;
import org.cobbler.Profile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tests for {@link ImageTreeManager}, run against the mocked cobbler.
 */
@ExtendWith(SaltTestCaseExtension.class)
public class ImageTreeManagerTest extends KickstartBaseTest {

    private final List<ImageInfo> images = new ArrayList<>();

    @AfterEach
    public void removeImageFiles() {
        images.forEach(KickstartTestUtils::deleteBootFiles);
    }

    private ImageInfo createImage() throws Exception {
        ImageInfo image = KickstartTestUtils.createSaltbootImageWithBootFiles(user);
        images.add(image);
        return image;
    }

    /**
     * The mocked cobbler returns the kernel options as typed, the real one as a map
     */
    @SuppressWarnings("unchecked")
    private static KernelOptionsList kernelOptions(Distro distro) {
        Optional<?> raw = distro.getKernelOptions();
        Object options = raw.orElse(null);
        if (options instanceof Map) {
            return new KernelOptionsList((Map<String, Object>) options);
        }
        return new KernelOptionsList((String) options);
    }

    private static long distrosNamed(String name) {
        return Distro.list(con()).stream().filter(d -> name.equals(d.getName())).count();
    }

    private static CobblerConnection con() {
        return CobblerXMLRPCHelper.getUncachedAutomatedConnection();
    }

    @Test
    public void testGeneratedLabel() throws Exception {
        ImageInfo image = KickstartTestUtils.createSaltbootImage(user);

        assertEquals(image.getName() + "-1.0.0-" + image.getRevisionNumber(), ImageTreeManager.generatedLabel(image));
    }

    @Test
    public void testLabelRule() {
        assertTrue(ImageTreeManager.isValidLabel("POS_Image-1.2.3-4"));
        assertTrue(ImageTreeManager.isValidLabel("a".repeat(64)));
        assertFalse(ImageTreeManager.isValidLabel("a".repeat(65)));
        assertFalse(ImageTreeManager.isValidLabel(""));
        assertFalse(ImageTreeManager.isValidLabel("with space"));
        assertFalse(ImageTreeManager.isValidLabel(null));
    }

    @Test
    public void testEnsureCreatesTreeAndDistro() throws Exception {
        ImageInfo image = createImage();

        KickstartableTree tree = ImageTreeManager.ensureGeneratedTree(image).orElseThrow();

        assertEquals(ImageTreeManager.generatedLabel(image), tree.getLabel());
        assertTrue(tree.isGeneratedImageTree());
        assertTrue(tree.getInstallType().isSaltboot());
        assertEquals(user.getOrg(), tree.getOrg());
        assertEquals(OSImageStoreUtils.getOSImageStorePathForImage(image) + ImageTreeManager.generatedLabel(image),
                tree.getBasePath());

        String distroName = SaltbootUtils.makeCobblerNameVR(image);
        assertEquals(distroName, tree.getCobblerDistroName());
        Distro distro = Distro.lookupByName(con(), distroName);
        assertNotNull(distro);
        assertEquals(distro.getUid(), tree.getCobblerId());
        assertEquals("generic", distro.getBreed());
        assertEquals(image.getImageArch().getName(), distro.getArch());
        assertEquals(tree.getKernelPath(), distro.getKernel());
        assertEquals(tree.getInitrdPath(), distro.getInitrd());
        KernelOptionsList options = kernelOptions(distro);
        assertTrue(options.hasOption("panic", "60"));
        assertTrue(options.hasOption("splash", "silent"));
        assertTrue(distro.getKsMeta().map(Map::isEmpty).orElse(true));
    }

    @Test
    public void testEnsureIsIdempotent() throws Exception {
        ImageInfo image = createImage();
        KickstartableTree first = ImageTreeManager.ensureGeneratedTree(image).orElseThrow();

        KickstartableTree second = ImageTreeManager.ensureGeneratedTree(image).orElseThrow();

        assertEquals(first.getId(), second.getId());
        assertEquals(1, distrosNamed(first.getCobblerDistroName()));
        assertEquals(1, KickstartFactory.listImageTrees(image).size());
    }

    @Test
    public void testEnsureAdoptsExistingDistro() throws Exception {
        ImageInfo image = createImage();
        String distroName = SaltbootUtils.makeCobblerNameVR(image);
        String prefix = OSImageStoreUtils.getOSImageStorePathForImage(image) + ImageTreeManager.generatedLabel(image);
        Distro existing = new Distro.Builder<String>()
                .setName(distroName)
                .setKernel(prefix + "/kernel")
                .setInitrd(prefix + "/initrd")
                .setKernelOptions(Optional.of("legacy=1"))
                .setBreed("generic")
                .build(con());
        existing.save();

        KickstartableTree tree = ImageTreeManager.ensureGeneratedTree(image).orElseThrow();

        assertEquals(existing.getUid(), tree.getCobblerId());
        assertEquals(1, distrosNamed(distroName));
        assertEquals("legacy=1", kernelOptions(Distro.lookupByName(con(), distroName)).toString());
    }

    @Test
    public void testEnsureRecreatesMissingDistro() throws Exception {
        ImageInfo image = createImage();
        ImageTreeManager.ensureGeneratedTree(image).orElseThrow();
        String distroName = SaltbootUtils.makeCobblerNameVR(image);
        Distro.lookupByName(con(), distroName).remove();

        KickstartableTree tree = ImageTreeManager.ensureGeneratedTree(image).orElseThrow();

        Distro distro = Distro.lookupByName(con(), distroName);
        assertNotNull(distro);
        assertEquals(distro.getUid(), tree.getCobblerId());
    }

    @Test
    public void testEnsureSkipsLabelTakenByChannelTree() throws Exception {
        ImageInfo image = createImage();
        KickstartableTree channelTree = KickstartableTreeTest.createTestKickstartableTree(
                ChannelFactoryTest.createTestChannel(user));
        channelTree.setLabel(ImageTreeManager.generatedLabel(image));
        KickstartFactory.saveKickstartableTree(channelTree);

        assertTrue(ImageTreeManager.ensureGeneratedTree(image).isEmpty());
        assertTrue(KickstartFactory.listImageTrees(image).isEmpty());
    }

    @Test
    public void testEnsureSkipsLabelTakenByTreeOfAnotherImage() throws Exception {
        ImageInfo image = createImage();
        ImageInfo other = createImage();
        KickstartTestUtils.createImageTree(user, other, ImageTreeManager.generatedLabel(image));

        assertTrue(ImageTreeManager.ensureGeneratedTree(image).isEmpty());
        assertNull(Distro.lookupByName(con(), SaltbootUtils.makeCobblerNameVR(image)));
    }

    @Test
    public void testEnsureSkipsInvalidLabel() throws Exception {
        ImageInfo image = createImage();
        image.setName("name with space");

        assertTrue(ImageTreeManager.ensureGeneratedTree(image).isEmpty());
    }

    @Test
    public void testEnsureSkipsMissingBootFiles() throws Exception {
        ImageInfo image = createImage();
        image.getImageFiles().removeIf(f -> "initrd".equals(f.getType()));

        assertTrue(ImageTreeManager.ensureGeneratedTree(image).isEmpty());
    }

    @Test
    public void testEnsureSkipsBootFilesInDifferentDirectories() throws Exception {
        ImageInfo image = createImage();
        image.getImageFiles().stream().filter(f -> "kernel".equals(f.getType())).forEach(f -> f.setFile("x/kernel"));

        assertTrue(ImageTreeManager.ensureGeneratedTree(image).isEmpty());
    }

    @Test
    public void testEnsureSkipsBootFilesNotOnDisk() throws Exception {
        ImageInfo image = createImage();
        KickstartTestUtils.deleteBootFiles(image);

        assertTrue(ImageTreeManager.ensureGeneratedTree(image).isEmpty());
        assertNull(Distro.lookupByName(con(), SaltbootUtils.makeCobblerNameVR(image)));
    }

    @Test
    public void testCreateUserTree() throws Exception {
        ImageInfo image = createImage();
        String label = "my-tree-1.0-" + TestUtils.randomString(5);

        ValidatorError error = ImageTreeManager.create(user, label, image, "console=ttyS0", "quiet");

        assertNull(error);
        KickstartableTree tree = KickstartFactory.lookupImageTreeByLabel(label, user.getOrg());
        assertNotNull(tree);
        assertFalse(tree.isGeneratedImageTree());
        assertEquals("console=ttyS0", tree.getKernelOptions());
        assertEquals("quiet", tree.getKernelOptionsPost());
        Distro distro = Distro.lookupByName(con(), SaltbootUtils.makeCobblerName(user.getOrg(), label));
        assertNotNull(distro);
        assertEquals(distro.getUid(), tree.getCobblerId());
        assertEquals("console=ttyS0", kernelOptions(distro).toString());
    }

    @Test
    public void testCreateRejectsInvalidLabel() throws Exception {
        ValidatorError error = ImageTreeManager.create(user, "no good", createImage(), "", "");

        assertEquals("kickstart.tree.image.invalidlabel", error.getKey());
    }

    @Test
    public void testCreateRejectsGeneratedLabel() throws Exception {
        ImageInfo image = createImage();

        ValidatorError error = ImageTreeManager.create(user, ImageTreeManager.generatedLabel(image), image, "", "");

        assertEquals("kickstart.tree.image.reservedlabel", error.getKey());
        assertTrue(KickstartFactory.listImageTrees(image).isEmpty());
    }

    @Test
    public void testCreateRejectsLabelOfChannelTree() throws Exception {
        KickstartableTree channelTree = KickstartableTreeTest.createTestKickstartableTree(
                ChannelFactoryTest.createTestChannel(user));

        ValidatorError error = ImageTreeManager.create(user, channelTree.getLabel(), createImage(), "", "");

        assertEquals("distribution.tree.exists", error.getKey());
    }

    @Test
    public void testCreateRejectsDuplicateImageTreeLabel() throws Exception {
        ImageInfo image = createImage();
        assertNull(ImageTreeManager.create(user, "dup-tree", image, "", ""));

        assertEquals("distribution.tree.exists", ImageTreeManager.create(user, "dup-tree", image, "", "").getKey());
    }

    @Test
    public void testCreateRejectsImageWithoutBootFiles() throws Exception {
        ImageInfo image = createImage();
        image.getImageFiles().removeIf(f -> "kernel".equals(f.getType()));

        ValidatorError error = ImageTreeManager.create(user, "no-boot-files", image, "", "");

        assertEquals("kickstart.tree.image.nobootfiles", error.getKey());
    }

    @Test
    public void testUpdateChangesTreeAndDistro() throws Exception {
        ImageInfo image = createImage();
        assertNull(ImageTreeManager.create(user, "update-tree", image, "a=1", ""));
        KickstartableTree tree = KickstartFactory.lookupImageTreeByLabel("update-tree", user.getOrg());

        assertNull(ImageTreeManager.update(user, tree, "b=2", "c=3"));

        assertEquals("b=2", tree.getKernelOptions());
        assertEquals("c=3", tree.getKernelOptionsPost());
        Distro distro = Distro.lookupById(con(), tree.getCobblerId());
        assertEquals("b=2", kernelOptions(distro).toString());
    }

    @Test
    public void testDeleteUserTree() throws Exception {
        ImageInfo image = createImage();
        assertNull(ImageTreeManager.create(user, "delete-tree", image, "", ""));
        KickstartableTree tree = KickstartFactory.lookupImageTreeByLabel("delete-tree", user.getOrg());
        String distroId = tree.getCobblerId();

        assertNull(ImageTreeManager.delete(user, tree));

        assertNull(KickstartFactory.lookupImageTreeByLabel("delete-tree", user.getOrg()));
        assertNull(Distro.lookupById(con(), distroId));
    }

    @Test
    public void testDeleteRefusesGeneratedTree() throws Exception {
        KickstartableTree tree = ImageTreeManager.ensureGeneratedTree(createImage()).orElseThrow();

        ValidatorError error = ImageTreeManager.delete(user, tree);

        assertEquals("kickstart.tree.image.generated", error.getKey());
        assertNotNull(KickstartFactory.lookupImageTreeByLabel(tree.getLabel(), user.getOrg()));
        assertNotNull(Distro.lookupById(con(), tree.getCobblerId()));
    }

    @Test
    public void testDeleteImageTrees() throws Exception {
        ImageInfo image = createImage();
        KickstartableTree generated = ImageTreeManager.ensureGeneratedTree(image).orElseThrow();
        assertNull(ImageTreeManager.create(user, "extra-tree", image, "", ""));
        KickstartableTree extra = KickstartFactory.lookupImageTreeByLabel("extra-tree", user.getOrg());
        String generatedDistro = generated.getCobblerId();
        String extraDistro = extra.getCobblerId();

        ImageTreeManager.deleteImageTrees(image);

        assertTrue(KickstartFactory.listImageTrees(image).isEmpty());
        assertNull(Distro.lookupById(con(), generatedDistro));
        assertNull(Distro.lookupById(con(), extraDistro));
    }

    @Test
    public void testCreateSaltbootDistroUsesTreeDistro() throws Exception {
        ImageInfo image = createImage();
        KickstartableTree tree = ImageTreeManager.ensureGeneratedTree(image).orElseThrow();
        String distroName = tree.getCobblerDistroName();

        SaltbootUtils.createSaltbootDistro(image, Distro.list(con()), con());

        assertEquals(1, distrosNamed(distroName));
        assertEquals(distroName, Profile.lookupByName(con(), SaltbootUtils.makeCobblerNameVR(image))
                .getDistro().getName());
        assertEquals(distroName, Profile.lookupByName(con(),
                SaltbootUtils.makeCobblerName(user.getOrg(), SaltbootUtils.DEFAULT_BOOT_IMAGE)).getDistro().getName());
    }

    @Test
    public void testCreateSaltbootDistroWithoutTreeCreatesLegacyDistro() throws Exception {
        ImageInfo image = createImage();

        SaltbootUtils.createSaltbootDistro(image, Distro.list(con()), con());

        assertNotNull(Distro.lookupByName(con(), SaltbootUtils.makeCobblerNameVR(image)));
        assertNotNull(Profile.lookupByName(con(), SaltbootUtils.makeCobblerNameVR(image)));
        assertTrue(KickstartFactory.listImageTrees(image).isEmpty());
    }
}
