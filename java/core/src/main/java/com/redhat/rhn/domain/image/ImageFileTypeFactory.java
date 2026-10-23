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
package com.redhat.rhn.domain.image;

import com.redhat.rhn.common.hibernate.HibernateFactory;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.List;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;

/**
 * Factory for {@link ImageFileType}
 */
public class ImageFileTypeFactory extends HibernateFactory {

    private static final Logger LOG = LogManager.getLogger(ImageFileTypeFactory.class);

    private static ImageFileTypeFactory instance = new ImageFileTypeFactory();

    private ImageFileTypeFactory() {
        super();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Logger getLogger() {
        return LOG;
    }

    /**
     * Return the extensions of all enabled file types
     * @return list of enabled extensions
     */
    public static List<String> lookupEnabledExtensions() {
        CriteriaBuilder builder = getSession().getCriteriaBuilder();
        CriteriaQuery<String> criteria = builder.createQuery(String.class);
        Root<ImageFileType> root = criteria.from(ImageFileType.class);
        criteria.select(root.get("extension")).where(builder.isTrue(root.get("enabled")));
        return getSession().createQuery(criteria).getResultList();
    }

    /**
     * Keep only the files which are the image itself (file named exactly as the basename, which is an
     * uncompressed PXE/KIS image) or which have an enabled extension.
     *
     * @param paths file paths or file names
     * @param basename the basename of the image
     * @return the allowed subset of the given paths, in the original order
     */
    public static List<String> filterAllowedFiles(List<String> paths, String basename) {
        List<String> extensions = lookupEnabledExtensions();
        return paths.stream()
                .filter(path -> {
                    String fileName = Path.of(path).getFileName().toString();
                    return fileName.equals(basename) || extensions.stream().anyMatch(fileName::endsWith);
                })
                .toList();
    }
}
