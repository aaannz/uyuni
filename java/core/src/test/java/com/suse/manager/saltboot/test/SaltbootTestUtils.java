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

import com.redhat.rhn.domain.formula.FormulaFactory;
import com.redhat.rhn.domain.image.ImageInfo;
import com.redhat.rhn.domain.image.ImageProfile;
import com.redhat.rhn.domain.image.ImageProfileFactory;
import com.redhat.rhn.domain.server.MinionServer;
import com.redhat.rhn.domain.server.MinionServerFactory;
import com.redhat.rhn.domain.server.Pillar;
import com.redhat.rhn.domain.server.ServerFactory;
import com.redhat.rhn.domain.server.ServerGroup;
import com.redhat.rhn.domain.server.test.MinionServerFactoryTest;
import com.redhat.rhn.domain.token.ActivationKey;
import com.redhat.rhn.domain.user.User;
import com.redhat.rhn.testing.ImageTestUtils;
import com.redhat.rhn.testing.ServerGroupTestUtils;
import com.redhat.rhn.testing.TestUtils;

import java.util.HashMap;
import java.util.Map;

public class SaltbootTestUtils {
    private SaltbootTestUtils() { }

    static ImageInfo createImageHelper(User user, String label, String version, int revision) throws Exception {
        return createImageHelper(user, label, version, revision, "kernel", "initrd");
    };

    static ImageInfo createImageHelper(User user, String label, String version, int revision,
                                       String kernel, String initrd) throws Exception {
        MinionServer server = MinionServerFactory.findByMinionId("minion.local").orElseGet(
                () -> {
                    MinionServer s = MinionServerFactoryTest.createTestMinionServer(user);
                    s.setMinionId("minion.local");
                    s.setServerArch(ServerFactory.lookupServerArchByLabel("x86_64-redhat-linux"));
                    ServerFactory.save(s);
                    return s;
                }
        );
        ActivationKey key = ImageTestUtils.createActivationKey(user);
        ImageProfile profile = ImageProfileFactory.lookupByLabelAndOrg(label, user.getOrg()).orElseGet(
                () -> ImageTestUtils.createKiwiImageProfile(label, key, user)
        );
        ImageInfo image = ImageTestUtils.createImageInfo(profile, server, version, user);
        image.setRevisionNumber(revision);
        image.setImageType(ImageProfile.TYPE_KIWI);
        image.setBuilt(true);
        ImageTestUtils.createImageFile(image, kernel, "kernel");
        ImageTestUtils.createImageFile(image, initrd, "initrd");
        ImageTestUtils.createImageFile(image, "image", "image");
        TestUtils.saveAndFlush(image);
        return image;
    }

    static ServerGroup createSaltbootGroupHelper(User user, String label) {
        return createSaltbootGroupHelper(user, label, null, null);
    }

    static ServerGroup createSaltbootGroupHelper(User user, String label, String image, String version) {
        ServerGroup group = ServerGroupTestUtils.createManaged(user);
        group.setName(label);

        Map<String, Object> saltboot = new HashMap<>();
        saltboot.put("download_server", label + ".example.com");
        saltboot.put("disable_id_prefix", true);
        saltboot.put("disable_unique_suffix", false);
        saltboot.put("minion_id_naming", "Hostname");
        saltboot.put("default_kernel_parameters", "");
        if (image != null && !image.isEmpty()) {
            saltboot.put("default_boot_image", image);
            if (version != null && !version.isEmpty()) {
                saltboot.put("default_boot_image_version", version);
            }
        }

        Map<String, Object> pillar = new HashMap<>();
        pillar.put("saltboot", saltboot);

        group.getPillars().add(new Pillar(FormulaFactory.SALTBOOT_PILLAR, pillar, group));
        TestUtils.saveAndFlush(group);
        return group;
    }
}
