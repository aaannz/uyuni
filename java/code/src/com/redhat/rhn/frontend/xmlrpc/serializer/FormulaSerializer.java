/*
 * Copyright (c) 2021 SUSE LLC
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
package com.redhat.rhn.frontend.xmlrpc.serializer;


import com.redhat.rhn.domain.formula.Formula;
import com.redhat.rhn.frontend.xmlrpc.serializer.util.SerializerHelper;

import java.io.IOException;
import java.io.Writer;

import redstone.xmlrpc.XmlRpcException;
import redstone.xmlrpc.XmlRpcSerializer;

/**
*
* FormulaSerializer
*
* @xmlrpc.doc
*
* #struct_begin("formula")
*     #prop("string", "name")
*     #prop("string", "description")
*     #prop("string", "formula_group")
* #struct_end()
*/
public class FormulaSerializer extends RhnXmlRpcCustomSerializer {

    /**
     * {@inheritDoc}
     */
    @Override
    public Class getSupportedClass() {
        return Formula.class;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doSerialize(Object value, Writer output, XmlRpcSerializer serializer)
        throws XmlRpcException, IOException {
        Formula formula = (Formula) value;
        SerializerHelper helper = new SerializerHelper(serializer);
        helper.add("name", formula.getName());
        helper.add("description", formula.getDescription());
        helper.add("formula_group", formula.getGroup());

        helper.writeTo(output);
    }
}
