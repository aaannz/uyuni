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

package com.suse.manager.autoinstallation;

import com.redhat.rhn.common.validator.ValidatorError;
import com.redhat.rhn.common.validator.ValidatorException;
import com.redhat.rhn.domain.image.ImageFile;
import com.redhat.rhn.domain.image.ImageInfo;
import com.redhat.rhn.domain.image.OSImageStoreUtils;
import com.redhat.rhn.domain.kickstart.KickstartFactory;
import com.redhat.rhn.domain.kickstart.KickstartInstallType;
import com.redhat.rhn.domain.kickstart.KickstartableTree;
import com.redhat.rhn.domain.org.Org;
import com.redhat.rhn.domain.user.User;
import com.redhat.rhn.manager.kickstart.cobbler.CobblerDistroCreateCommand;
import com.redhat.rhn.manager.kickstart.cobbler.CobblerDistroDeleteCommand;
import com.redhat.rhn.manager.kickstart.cobbler.CobblerDistroEditCommand;
import com.redhat.rhn.manager.kickstart.cobbler.CobblerDistroHelper;
import com.redhat.rhn.manager.kickstart.cobbler.CobblerXMLRPCHelper;

import com.suse.manager.saltboot.SaltbootUtils;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cobbler.CobblerConnection;
import org.cobbler.Distro;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Manages the autoinstallation trees backed by an OS image instead of a channel.
 * Every image tree owns a cobbler distro named like the saltboot distros.
 */
public final class ImageTreeManager {

    private static final Logger LOG = LogManager.getLogger(ImageTreeManager.class);

    private static final Pattern IMAGE_TREE_LABEL = Pattern.compile("^[-_.0-9A-Za-z]{1,64}$");

    private ImageTreeManager() { }

    /**
     * @param image the image
     * @return the label of the tree generated for the image: name-version-revision
     */
    public static String generatedLabel(ImageInfo image) {
        return image.getName() + "-" + image.getVersion() + "-" + image.getRevisionNumber();
    }

    /**
     * @param label the label to check
     * @return true if the label may be used by an image tree
     */
    public static boolean isValidLabel(String label) {
        return label != null && IMAGE_TREE_LABEL.matcher(label).matches();
    }

    /**
     * Ensure the tree generated for the image and its cobbler distro exist. An already existing distro of
     * the tree's name is adopted untouched. The tree is skipped, with a warning, when the image has no usable
     * boot files, the label is not valid or the label is taken by another tree.
     *
     * @param image the built image with kernel and initrd files
     * @return the generated tree, empty when skipped
     */
    public static Optional<KickstartableTree> ensureGeneratedTree(ImageInfo image) {
        return ensureGeneratedTree(image, CobblerXMLRPCHelper.getUncachedAutomatedConnection());
    }

    /**
     * Ensure the tree generated for the image and its cobbler distro exist.
     *
     * @param image the built image with kernel and initrd files
     * @param con the cobbler connection
     * @return the generated tree, empty when skipped
     * @see #ensureGeneratedTree(ImageInfo)
     */
    public static Optional<KickstartableTree> ensureGeneratedTree(ImageInfo image, CobblerConnection con) {
        String label = generatedLabel(image);
        KickstartableTree tree = KickstartFactory.lookupImageTreeByLabel(label, image.getOrg());
        if (tree == null) {
            tree = buildGeneratedTree(image, label);
        }
        else if (!image.equals(tree.getImage())) {
            LOG.warn("Label {} of the image tree of image {} is used by another image, skipping", label,
                    image.getId());
            return Optional.empty();
        }
        if (tree == null) {
            return Optional.empty();
        }

        try {
            adoptOrCreateDistro(tree, con);
        }
        catch (ValidatorException e) {
            LOG.warn("Boot files of the image {} not found, skipping its tree: {}", image.getId(),
                    e.getMessage());
            return Optional.empty();
        }
        return Optional.of(KickstartFactory.saveKickstartableTree(tree));
    }

    private static KickstartableTree buildGeneratedTree(ImageInfo image, String label) {
        if (!isValidLabel(label)) {
            LOG.warn("Label {} of the image {} is not valid for an image tree, skipping", label, image.getId());
            return null;
        }
        if (KickstartFactory.treeLabelExists(label, image.getOrg())) {
            LOG.warn("Label {} of the image {} is taken by another tree, skipping", label, image.getId());
            return null;
        }
        Optional<String> bootDir = bootFilesDirectory(image);
        if (bootDir.isEmpty()) {
            LOG.warn("Image {} has no kernel and initrd in one directory, skipping its tree", image.getId());
            return null;
        }
        return newTree(image.getOrg(), label, image, bootDir.get(), SaltbootUtils.DISTRO_KERNEL_OPTIONS, "");
    }

    private static KickstartableTree newTree(Org org, String label, ImageInfo image,
                                             String basePath, String kernelOptions, String kernelOptionsPost) {
        KickstartableTree tree = new KickstartableTree();
        tree.setLabel(label);
        tree.setOrg(org);
        tree.setImage(image);
        tree.setInstallType(KickstartFactory.lookupKickstartInstallTypeByLabel(KickstartInstallType.SALTBOOT));
        tree.setTreeType(KickstartFactory.TREE_TYPE_EXTERNAL);
        tree.setBasePath(basePath);
        tree.setKernelOptions(StringUtils.defaultString(kernelOptions));
        tree.setKernelOptionsPost(StringUtils.defaultString(kernelOptionsPost));
        tree.setCreated(new Date());
        return tree;
    }

    private static void adoptOrCreateDistro(KickstartableTree tree, CobblerConnection con) {
        Distro distro = Distro.lookupByName(con, tree.getCobblerDistroName());
        if (distro != null) {
            tree.setCobblerId(distro.getUid());
        }
        else {
            CobblerDistroHelper.getInstance().createDistroFromTree(con, tree);
        }
    }

    /**
     * The directory holding the kernel and the initrd of the image
     * @param image the image
     * @return the directory, empty if a file is missing or the files are in different directories
     */
    private static Optional<String> bootFilesDirectory(ImageInfo image) {
        Optional<Path> kernel = imageFilePath(image, "kernel");
        Optional<Path> initrd = imageFilePath(image, "initrd");
        if (kernel.isEmpty() || initrd.isEmpty() || !kernel.get().getParent().equals(initrd.get().getParent())) {
            return Optional.empty();
        }
        return Optional.of(kernel.get().getParent().toString());
    }

    private static Optional<Path> imageFilePath(ImageInfo image, String type) {
        String prefix = OSImageStoreUtils.getOSImageStorePathForImage(image);
        return image.getImageFiles().stream()
                .filter(f -> type.equals(f.getType()))
                .map(ImageFile::getFile)
                .map(file -> Paths.get(prefix + file))
                .findFirst();
    }

    /**
     * Create a user defined tree on an image together with its cobbler distro.
     *
     * @param user the user, owner of the tree
     * @param label the tree label
     * @param image the image of the user's org
     * @param kernelOptions the kernel options, may be empty
     * @param kernelOptionsPost the post install kernel options, may be empty
     * @return the error, null on success
     */
    public static ValidatorError create(User user, String label, ImageInfo image, String kernelOptions,
                                        String kernelOptionsPost) {
        if (!isValidLabel(label)) {
            return new ValidatorError("kickstart.tree.image.invalidlabel", label);
        }
        if (generatedLabel(image).equals(label)) {
            return new ValidatorError("kickstart.tree.image.reservedlabel", label);
        }
        if (KickstartFactory.treeLabelExists(label, user.getOrg())) {
            return new ValidatorError("distribution.tree.exists", label);
        }
        Optional<String> bootDir = bootFilesDirectory(image);
        if (bootDir.isEmpty()) {
            return new ValidatorError("kickstart.tree.image.nobootfiles", label);
        }

        KickstartableTree tree = newTree(user.getOrg(), label, image, bootDir.get(), kernelOptions,
                kernelOptionsPost);
        try {
            tree = KickstartFactory.saveKickstartableTree(tree);
            return new CobblerDistroCreateCommand(tree, user).store();
        }
        catch (ValidatorException e) {
            return e.getResult().getErrors().get(0);
        }
    }

    /**
     * Update the kernel options of an image tree and its cobbler distro.
     *
     * @param user the user
     * @param tree the image tree
     * @param kernelOptions the kernel options
     * @param kernelOptionsPost the post install kernel options
     * @return the error, null on success
     */
    public static ValidatorError update(User user, KickstartableTree tree, String kernelOptions,
                                        String kernelOptionsPost) {
        tree.setKernelOptions(StringUtils.defaultString(kernelOptions));
        tree.setKernelOptionsPost(StringUtils.defaultString(kernelOptionsPost));
        tree.setLastModified(new Date());
        KickstartFactory.saveKickstartableTree(tree);
        return new CobblerDistroEditCommand(tree, user).store();
    }

    /**
     * Delete a user defined image tree together with its cobbler distro.
     *
     * @param user the user
     * @param tree the image tree
     * @return the error, null on success
     */
    public static ValidatorError delete(User user, KickstartableTree tree) {
        if (tree.isGeneratedImageTree()) {
            return new ValidatorError("kickstart.tree.image.generated", tree.getLabel());
        }
        return remove(user, tree);
    }

    /**
     * Delete all trees of an image together with their cobbler distros, to be used when the image is deleted.
     *
     * @param image the image
     */
    public static void deleteImageTrees(ImageInfo image) {
        List<KickstartableTree> trees = KickstartFactory.listImageTrees(image);
        for (KickstartableTree tree : trees) {
            ValidatorError error = remove(null, tree);
            if (error != null) {
                LOG.error("Unable to delete the cobbler distro of the image tree {}: {}", tree.getLabel(),
                        error.getKey());
            }
        }
    }

    private static ValidatorError remove(User user, KickstartableTree tree) {
        ValidatorError error = new CobblerDistroDeleteCommand(tree, user).store();
        if (error == null) {
            KickstartFactory.removeKickstartableTree(tree);
        }
        return error;
    }
}
