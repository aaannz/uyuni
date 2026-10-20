-- Image based autoinstallation tree API (autoinstallation overhaul step 3.4a-2).
-- Must stay idempotent: scripts in upgrade/next are re-applied on every start.

INSERT INTO access.namespace (namespace, access_mode, description)
    SELECT 'api.kickstart.tree.create_from_image', 'W', 'Create a Kickstart Tree (Distribution) based on an OS image in #product().'
    WHERE NOT EXISTS (SELECT 1 FROM access.namespace WHERE namespace = 'api.kickstart.tree.create_from_image' AND access_mode = 'W');

INSERT INTO access.endpoint (class_method, endpoint, http_method, scope, auth_required)
    SELECT 'com.redhat.rhn.frontend.xmlrpc.kickstart.tree.KickstartTreeHandler.createFromImage', '/manager/api/kickstart/tree/createFromImage', 'POST', 'A', True
    WHERE NOT EXISTS (SELECT 1 FROM access.endpoint WHERE endpoint = '/manager/api/kickstart/tree/createFromImage' AND http_method = 'POST');

INSERT INTO access.endpointNamespace (namespace_id, endpoint_id)
    SELECT ns.id, ep.id FROM access.namespace ns, access.endpoint ep
    WHERE ns.namespace = 'api.kickstart.tree.create_from_image' AND ns.access_mode = 'W'
    AND ep.endpoint = '/manager/api/kickstart/tree/createFromImage' AND ep.http_method = 'POST'
    ON CONFLICT DO NOTHING;

INSERT INTO access.namespace (namespace, access_mode, description)
    SELECT 'api.kickstart.tree.list_image_trees', 'R', 'List the Kickstart Trees (Distributions) based on OS images.'
    WHERE NOT EXISTS (SELECT 1 FROM access.namespace WHERE namespace = 'api.kickstart.tree.list_image_trees' AND access_mode = 'R');

INSERT INTO access.endpoint (class_method, endpoint, http_method, scope, auth_required)
    SELECT 'com.redhat.rhn.frontend.xmlrpc.kickstart.tree.KickstartTreeHandler.listImageTrees', '/manager/api/kickstart/tree/listImageTrees', 'GET', 'A', True
    WHERE NOT EXISTS (SELECT 1 FROM access.endpoint WHERE endpoint = '/manager/api/kickstart/tree/listImageTrees' AND http_method = 'GET');

INSERT INTO access.endpointNamespace (namespace_id, endpoint_id)
    SELECT ns.id, ep.id FROM access.namespace ns, access.endpoint ep
    WHERE ns.namespace = 'api.kickstart.tree.list_image_trees' AND ns.access_mode = 'R'
    AND ep.endpoint = '/manager/api/kickstart/tree/listImageTrees' AND ep.http_method = 'GET'
    ON CONFLICT DO NOTHING;

INSERT INTO access.namespace (namespace, access_mode, description)
    SELECT 'api.kickstart.tree.get_image_tree_details', 'R', 'The detailed information about a tree based on an OS image.'
    WHERE NOT EXISTS (SELECT 1 FROM access.namespace WHERE namespace = 'api.kickstart.tree.get_image_tree_details' AND access_mode = 'R');

INSERT INTO access.endpoint (class_method, endpoint, http_method, scope, auth_required)
    SELECT 'com.redhat.rhn.frontend.xmlrpc.kickstart.tree.KickstartTreeHandler.getImageTreeDetails', '/manager/api/kickstart/tree/getImageTreeDetails', 'GET', 'A', True
    WHERE NOT EXISTS (SELECT 1 FROM access.endpoint WHERE endpoint = '/manager/api/kickstart/tree/getImageTreeDetails' AND http_method = 'GET');

INSERT INTO access.endpointNamespace (namespace_id, endpoint_id)
    SELECT ns.id, ep.id FROM access.namespace ns, access.endpoint ep
    WHERE ns.namespace = 'api.kickstart.tree.get_image_tree_details' AND ns.access_mode = 'R'
    AND ep.endpoint = '/manager/api/kickstart/tree/getImageTreeDetails' AND ep.http_method = 'GET'
    ON CONFLICT DO NOTHING;

INSERT INTO access.namespace (namespace, access_mode, description)
    SELECT 'api.kickstart.tree.update_image_tree', 'W', 'Update the kernel options of a Kickstart Tree (Distribution) based on an OS image.'
    WHERE NOT EXISTS (SELECT 1 FROM access.namespace WHERE namespace = 'api.kickstart.tree.update_image_tree' AND access_mode = 'W');

INSERT INTO access.endpoint (class_method, endpoint, http_method, scope, auth_required)
    SELECT 'com.redhat.rhn.frontend.xmlrpc.kickstart.tree.KickstartTreeHandler.updateImageTree', '/manager/api/kickstart/tree/updateImageTree', 'POST', 'A', True
    WHERE NOT EXISTS (SELECT 1 FROM access.endpoint WHERE endpoint = '/manager/api/kickstart/tree/updateImageTree' AND http_method = 'POST');

INSERT INTO access.endpointNamespace (namespace_id, endpoint_id)
    SELECT ns.id, ep.id FROM access.namespace ns, access.endpoint ep
    WHERE ns.namespace = 'api.kickstart.tree.update_image_tree' AND ns.access_mode = 'W'
    AND ep.endpoint = '/manager/api/kickstart/tree/updateImageTree' AND ep.http_method = 'POST'
    ON CONFLICT DO NOTHING;

INSERT INTO access.namespace (namespace, access_mode, description)
    SELECT 'api.kickstart.tree.delete_image_tree', 'W', 'Delete a Kickstart Tree (Distribution) based on an OS image.'
    WHERE NOT EXISTS (SELECT 1 FROM access.namespace WHERE namespace = 'api.kickstart.tree.delete_image_tree' AND access_mode = 'W');

INSERT INTO access.endpoint (class_method, endpoint, http_method, scope, auth_required)
    SELECT 'com.redhat.rhn.frontend.xmlrpc.kickstart.tree.KickstartTreeHandler.deleteImageTree', '/manager/api/kickstart/tree/deleteImageTree', 'POST', 'A', True
    WHERE NOT EXISTS (SELECT 1 FROM access.endpoint WHERE endpoint = '/manager/api/kickstart/tree/deleteImageTree' AND http_method = 'POST');

INSERT INTO access.endpointNamespace (namespace_id, endpoint_id)
    SELECT ns.id, ep.id FROM access.namespace ns, access.endpoint ep
    WHERE ns.namespace = 'api.kickstart.tree.delete_image_tree' AND ns.access_mode = 'W'
    AND ep.endpoint = '/manager/api/kickstart/tree/deleteImageTree' AND ep.http_method = 'POST'
    ON CONFLICT DO NOTHING;

INSERT INTO access.accessGroupNamespace
    SELECT ag.id, ns.id
    FROM access.accessGroup ag, access.namespace ns
    WHERE ag.label = 'config_admin'
    AND ns.namespace IN (
        'api.kickstart.tree.create_from_image',
        'api.kickstart.tree.list_image_trees',
        'api.kickstart.tree.get_image_tree_details',
        'api.kickstart.tree.update_image_tree',
        'api.kickstart.tree.delete_image_tree'
    )
    ON CONFLICT DO NOTHING;
