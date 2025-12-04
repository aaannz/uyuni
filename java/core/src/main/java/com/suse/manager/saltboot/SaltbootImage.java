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

package com.suse.manager.saltboot;

import com.redhat.rhn.common.hibernate.HibernateFactory;
import com.redhat.rhn.domain.image.ImageFile;
import com.redhat.rhn.domain.image.ImageInfo;
import com.redhat.rhn.domain.image.ImageInfoFactory;
import com.redhat.rhn.domain.org.Org;

import java.util.Optional;

import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Join;
import javax.persistence.criteria.JoinType;
import javax.persistence.criteria.Root;

public class SaltbootImage {
    private SaltbootImage() {
    }

    /**
     * Returns an organization wide default image which can be used in a Saltboot
     * @param org the organization which should own the image
     * @return Optional of ImageInfo. It there is no suitable image, returns emtpy Optional
     */
    public static Optional<ImageInfo> getOrgDefaultImage(Org org) {
        CriteriaBuilder builder = HibernateFactory.getSession().getCriteriaBuilder();
        CriteriaQuery<ImageInfo> query = builder.createQuery(ImageInfo.class);

        Root<ImageInfo> root = query.from(ImageInfo.class);
        Join<ImageInfo, ImageFile> imageFileJoin = root.join("imageFiles", JoinType.INNER);
        query.where(builder.and(
                builder.equal(imageFileJoin.get("type"), "kernel"),
                builder.equal(root.get("org"), org)));
        query.orderBy(builder.desc(root.get("created")));
        return HibernateFactory.getSession().createQuery(query).setMaxResults(1).uniqueResultOptional();
    }

    /**
     * Lookup a ImageInfo object based on the image string image_name-image_version-image_revision
     * @param imageString Image string to lookup
     * @param org the organization which should own the image
     * @return Optional of ImageInfo. It there is no image, returns emtpy Optional
     */
    public static Optional<ImageInfo> lookupImageFromImageString(String imageString, Org org) {
        String imageName = imageString;
        String imageVersion = "";
        Long imageRevision = null;
        int revisionHyphen = imageName.lastIndexOf('-');
        if (revisionHyphen > 0) {
            imageRevision = Long.valueOf(imageName.substring(revisionHyphen));
            imageName = imageString.substring(0, revisionHyphen - 1);
        }
        int versionHyphen = imageName.lastIndexOf('-');
        if (versionHyphen > 0) {
            imageVersion = imageName.substring(versionHyphen);
            imageName = imageName.substring(0, versionHyphen - 1);
        }

        if (imageRevision == null) {
            if (imageVersion.isEmpty()) {
                return ImageInfoFactory.lookupByName(imageName, org);
            }
            else {
                return ImageInfoFactory.lookupByName(imageName, imageVersion, org);
            }
        }
        return ImageInfoFactory.lookupByName(imageName, imageVersion, imageRevision, org);
    }
}
