-- Saltboot API namespace (autoinstallation overhaul step 3.2).
-- Must stay idempotent: scripts in upgrade/next are re-applied on every start.

INSERT INTO access.namespace (namespace, access_mode, description)
    SELECT 'api.saltboot.create_branch', 'W', 'Create the saltboot settings of a branch group'
    WHERE NOT EXISTS (SELECT 1 FROM access.namespace WHERE namespace = 'api.saltboot.create_branch' AND access_mode = 'W');

INSERT INTO access.endpoint (class_method, endpoint, http_method, scope, auth_required)
    SELECT 'com.redhat.rhn.frontend.xmlrpc.saltboot.SaltbootHandler.createBranch', '/manager/api/saltboot/createBranch', 'POST', 'A', True
    WHERE NOT EXISTS (SELECT 1 FROM access.endpoint WHERE endpoint = '/manager/api/saltboot/createBranch' AND http_method = 'POST');

INSERT INTO access.endpointNamespace (namespace_id, endpoint_id)
    SELECT ns.id, ep.id FROM access.namespace ns, access.endpoint ep
    WHERE ns.namespace = 'api.saltboot.create_branch' AND ns.access_mode = 'W'
    AND ep.endpoint = '/manager/api/saltboot/createBranch' AND ep.http_method = 'POST'
    ON CONFLICT DO NOTHING;

INSERT INTO access.namespace (namespace, access_mode, description)
    SELECT 'api.saltboot.get_branch', 'R', 'Get the saltboot settings of a branch group'
    WHERE NOT EXISTS (SELECT 1 FROM access.namespace WHERE namespace = 'api.saltboot.get_branch' AND access_mode = 'R');

INSERT INTO access.endpoint (class_method, endpoint, http_method, scope, auth_required)
    SELECT 'com.redhat.rhn.frontend.xmlrpc.saltboot.SaltbootHandler.getBranch', '/manager/api/saltboot/getBranch', 'GET', 'A', True
    WHERE NOT EXISTS (SELECT 1 FROM access.endpoint WHERE endpoint = '/manager/api/saltboot/getBranch' AND http_method = 'GET');

INSERT INTO access.endpointNamespace (namespace_id, endpoint_id)
    SELECT ns.id, ep.id FROM access.namespace ns, access.endpoint ep
    WHERE ns.namespace = 'api.saltboot.get_branch' AND ns.access_mode = 'R'
    AND ep.endpoint = '/manager/api/saltboot/getBranch' AND ep.http_method = 'GET'
    ON CONFLICT DO NOTHING;

INSERT INTO access.namespace (namespace, access_mode, description)
    SELECT 'api.saltboot.update_branch', 'W', 'Update the saltboot settings of a branch group'
    WHERE NOT EXISTS (SELECT 1 FROM access.namespace WHERE namespace = 'api.saltboot.update_branch' AND access_mode = 'W');

INSERT INTO access.endpoint (class_method, endpoint, http_method, scope, auth_required)
    SELECT 'com.redhat.rhn.frontend.xmlrpc.saltboot.SaltbootHandler.updateBranch', '/manager/api/saltboot/updateBranch', 'POST', 'A', True
    WHERE NOT EXISTS (SELECT 1 FROM access.endpoint WHERE endpoint = '/manager/api/saltboot/updateBranch' AND http_method = 'POST');

INSERT INTO access.endpointNamespace (namespace_id, endpoint_id)
    SELECT ns.id, ep.id FROM access.namespace ns, access.endpoint ep
    WHERE ns.namespace = 'api.saltboot.update_branch' AND ns.access_mode = 'W'
    AND ep.endpoint = '/manager/api/saltboot/updateBranch' AND ep.http_method = 'POST'
    ON CONFLICT DO NOTHING;

INSERT INTO access.namespace (namespace, access_mode, description)
    SELECT 'api.saltboot.delete_branch', 'W', 'Delete the saltboot settings of a branch group'
    WHERE NOT EXISTS (SELECT 1 FROM access.namespace WHERE namespace = 'api.saltboot.delete_branch' AND access_mode = 'W');

INSERT INTO access.endpoint (class_method, endpoint, http_method, scope, auth_required)
    SELECT 'com.redhat.rhn.frontend.xmlrpc.saltboot.SaltbootHandler.deleteBranch', '/manager/api/saltboot/deleteBranch', 'POST', 'A', True
    WHERE NOT EXISTS (SELECT 1 FROM access.endpoint WHERE endpoint = '/manager/api/saltboot/deleteBranch' AND http_method = 'POST');

INSERT INTO access.endpointNamespace (namespace_id, endpoint_id)
    SELECT ns.id, ep.id FROM access.namespace ns, access.endpoint ep
    WHERE ns.namespace = 'api.saltboot.delete_branch' AND ns.access_mode = 'W'
    AND ep.endpoint = '/manager/api/saltboot/deleteBranch' AND ep.http_method = 'POST'
    ON CONFLICT DO NOTHING;

INSERT INTO access.namespace (namespace, access_mode, description)
    SELECT 'api.saltboot.list_branches', 'R', 'List the saltboot branches'
    WHERE NOT EXISTS (SELECT 1 FROM access.namespace WHERE namespace = 'api.saltboot.list_branches' AND access_mode = 'R');

INSERT INTO access.endpoint (class_method, endpoint, http_method, scope, auth_required)
    SELECT 'com.redhat.rhn.frontend.xmlrpc.saltboot.SaltbootHandler.listBranches', '/manager/api/saltboot/listBranches', 'GET', 'A', True
    WHERE NOT EXISTS (SELECT 1 FROM access.endpoint WHERE endpoint = '/manager/api/saltboot/listBranches' AND http_method = 'GET');

INSERT INTO access.endpointNamespace (namespace_id, endpoint_id)
    SELECT ns.id, ep.id FROM access.namespace ns, access.endpoint ep
    WHERE ns.namespace = 'api.saltboot.list_branches' AND ns.access_mode = 'R'
    AND ep.endpoint = '/manager/api/saltboot/listBranches' AND ep.http_method = 'GET'
    ON CONFLICT DO NOTHING;

INSERT INTO access.namespace (namespace, access_mode, description)
    SELECT 'api.saltboot.create_config', 'W', 'Create a saltboot config'
    WHERE NOT EXISTS (SELECT 1 FROM access.namespace WHERE namespace = 'api.saltboot.create_config' AND access_mode = 'W');

INSERT INTO access.endpoint (class_method, endpoint, http_method, scope, auth_required)
    SELECT 'com.redhat.rhn.frontend.xmlrpc.saltboot.SaltbootHandler.createConfig', '/manager/api/saltboot/createConfig', 'POST', 'A', True
    WHERE NOT EXISTS (SELECT 1 FROM access.endpoint WHERE endpoint = '/manager/api/saltboot/createConfig' AND http_method = 'POST');

INSERT INTO access.endpointNamespace (namespace_id, endpoint_id)
    SELECT ns.id, ep.id FROM access.namespace ns, access.endpoint ep
    WHERE ns.namespace = 'api.saltboot.create_config' AND ns.access_mode = 'W'
    AND ep.endpoint = '/manager/api/saltboot/createConfig' AND ep.http_method = 'POST'
    ON CONFLICT DO NOTHING;

INSERT INTO access.namespace (namespace, access_mode, description)
    SELECT 'api.saltboot.get_config', 'R', 'Get a saltboot config'
    WHERE NOT EXISTS (SELECT 1 FROM access.namespace WHERE namespace = 'api.saltboot.get_config' AND access_mode = 'R');

INSERT INTO access.endpoint (class_method, endpoint, http_method, scope, auth_required)
    SELECT 'com.redhat.rhn.frontend.xmlrpc.saltboot.SaltbootHandler.getConfig', '/manager/api/saltboot/getConfig', 'GET', 'A', True
    WHERE NOT EXISTS (SELECT 1 FROM access.endpoint WHERE endpoint = '/manager/api/saltboot/getConfig' AND http_method = 'GET');

INSERT INTO access.endpointNamespace (namespace_id, endpoint_id)
    SELECT ns.id, ep.id FROM access.namespace ns, access.endpoint ep
    WHERE ns.namespace = 'api.saltboot.get_config' AND ns.access_mode = 'R'
    AND ep.endpoint = '/manager/api/saltboot/getConfig' AND ep.http_method = 'GET'
    ON CONFLICT DO NOTHING;

INSERT INTO access.namespace (namespace, access_mode, description)
    SELECT 'api.saltboot.get_effective_config', 'R', 'Get the effective values of a saltboot config'
    WHERE NOT EXISTS (SELECT 1 FROM access.namespace WHERE namespace = 'api.saltboot.get_effective_config' AND access_mode = 'R');

INSERT INTO access.endpoint (class_method, endpoint, http_method, scope, auth_required)
    SELECT 'com.redhat.rhn.frontend.xmlrpc.saltboot.SaltbootHandler.getEffectiveConfig', '/manager/api/saltboot/getEffectiveConfig', 'GET', 'A', True
    WHERE NOT EXISTS (SELECT 1 FROM access.endpoint WHERE endpoint = '/manager/api/saltboot/getEffectiveConfig' AND http_method = 'GET');

INSERT INTO access.endpointNamespace (namespace_id, endpoint_id)
    SELECT ns.id, ep.id FROM access.namespace ns, access.endpoint ep
    WHERE ns.namespace = 'api.saltboot.get_effective_config' AND ns.access_mode = 'R'
    AND ep.endpoint = '/manager/api/saltboot/getEffectiveConfig' AND ep.http_method = 'GET'
    ON CONFLICT DO NOTHING;

INSERT INTO access.namespace (namespace, access_mode, description)
    SELECT 'api.saltboot.update_config', 'W', 'Update a saltboot config'
    WHERE NOT EXISTS (SELECT 1 FROM access.namespace WHERE namespace = 'api.saltboot.update_config' AND access_mode = 'W');

INSERT INTO access.endpoint (class_method, endpoint, http_method, scope, auth_required)
    SELECT 'com.redhat.rhn.frontend.xmlrpc.saltboot.SaltbootHandler.updateConfig', '/manager/api/saltboot/updateConfig', 'POST', 'A', True
    WHERE NOT EXISTS (SELECT 1 FROM access.endpoint WHERE endpoint = '/manager/api/saltboot/updateConfig' AND http_method = 'POST');

INSERT INTO access.endpointNamespace (namespace_id, endpoint_id)
    SELECT ns.id, ep.id FROM access.namespace ns, access.endpoint ep
    WHERE ns.namespace = 'api.saltboot.update_config' AND ns.access_mode = 'W'
    AND ep.endpoint = '/manager/api/saltboot/updateConfig' AND ep.http_method = 'POST'
    ON CONFLICT DO NOTHING;

INSERT INTO access.namespace (namespace, access_mode, description)
    SELECT 'api.saltboot.delete_config', 'W', 'Delete a saltboot config'
    WHERE NOT EXISTS (SELECT 1 FROM access.namespace WHERE namespace = 'api.saltboot.delete_config' AND access_mode = 'W');

INSERT INTO access.endpoint (class_method, endpoint, http_method, scope, auth_required)
    SELECT 'com.redhat.rhn.frontend.xmlrpc.saltboot.SaltbootHandler.deleteConfig', '/manager/api/saltboot/deleteConfig', 'POST', 'A', True
    WHERE NOT EXISTS (SELECT 1 FROM access.endpoint WHERE endpoint = '/manager/api/saltboot/deleteConfig' AND http_method = 'POST');

INSERT INTO access.endpointNamespace (namespace_id, endpoint_id)
    SELECT ns.id, ep.id FROM access.namespace ns, access.endpoint ep
    WHERE ns.namespace = 'api.saltboot.delete_config' AND ns.access_mode = 'W'
    AND ep.endpoint = '/manager/api/saltboot/deleteConfig' AND ep.http_method = 'POST'
    ON CONFLICT DO NOTHING;

INSERT INTO access.namespace (namespace, access_mode, description)
    SELECT 'api.saltboot.list_configs', 'R', 'List the saltboot configs'
    WHERE NOT EXISTS (SELECT 1 FROM access.namespace WHERE namespace = 'api.saltboot.list_configs' AND access_mode = 'R');

INSERT INTO access.endpoint (class_method, endpoint, http_method, scope, auth_required)
    SELECT 'com.redhat.rhn.frontend.xmlrpc.saltboot.SaltbootHandler.listConfigs', '/manager/api/saltboot/listConfigs', 'GET', 'A', True
    WHERE NOT EXISTS (SELECT 1 FROM access.endpoint WHERE endpoint = '/manager/api/saltboot/listConfigs' AND http_method = 'GET');

INSERT INTO access.endpointNamespace (namespace_id, endpoint_id)
    SELECT ns.id, ep.id FROM access.namespace ns, access.endpoint ep
    WHERE ns.namespace = 'api.saltboot.list_configs' AND ns.access_mode = 'R'
    AND ep.endpoint = '/manager/api/saltboot/listConfigs' AND ep.http_method = 'GET'
    ON CONFLICT DO NOTHING;

INSERT INTO access.namespace (namespace, access_mode, description)
    SELECT 'api.saltboot.assign_config_to_group', 'W', 'Assign a saltboot config to a system group'
    WHERE NOT EXISTS (SELECT 1 FROM access.namespace WHERE namespace = 'api.saltboot.assign_config_to_group' AND access_mode = 'W');

INSERT INTO access.endpoint (class_method, endpoint, http_method, scope, auth_required)
    SELECT 'com.redhat.rhn.frontend.xmlrpc.saltboot.SaltbootHandler.assignConfigToGroup', '/manager/api/saltboot/assignConfigToGroup', 'POST', 'A', True
    WHERE NOT EXISTS (SELECT 1 FROM access.endpoint WHERE endpoint = '/manager/api/saltboot/assignConfigToGroup' AND http_method = 'POST');

INSERT INTO access.endpointNamespace (namespace_id, endpoint_id)
    SELECT ns.id, ep.id FROM access.namespace ns, access.endpoint ep
    WHERE ns.namespace = 'api.saltboot.assign_config_to_group' AND ns.access_mode = 'W'
    AND ep.endpoint = '/manager/api/saltboot/assignConfigToGroup' AND ep.http_method = 'POST'
    ON CONFLICT DO NOTHING;

INSERT INTO access.namespace (namespace, access_mode, description)
    SELECT 'api.saltboot.assign_config_to_system', 'W', 'Assign a saltboot config to a system'
    WHERE NOT EXISTS (SELECT 1 FROM access.namespace WHERE namespace = 'api.saltboot.assign_config_to_system' AND access_mode = 'W');

INSERT INTO access.endpoint (class_method, endpoint, http_method, scope, auth_required)
    SELECT 'com.redhat.rhn.frontend.xmlrpc.saltboot.SaltbootHandler.assignConfigToSystem', '/manager/api/saltboot/assignConfigToSystem', 'POST', 'A', True
    WHERE NOT EXISTS (SELECT 1 FROM access.endpoint WHERE endpoint = '/manager/api/saltboot/assignConfigToSystem' AND http_method = 'POST');

INSERT INTO access.endpointNamespace (namespace_id, endpoint_id)
    SELECT ns.id, ep.id FROM access.namespace ns, access.endpoint ep
    WHERE ns.namespace = 'api.saltboot.assign_config_to_system' AND ns.access_mode = 'W'
    AND ep.endpoint = '/manager/api/saltboot/assignConfigToSystem' AND ep.http_method = 'POST'
    ON CONFLICT DO NOTHING;

INSERT INTO access.namespace (namespace, access_mode, description)
    SELECT 'api.saltboot.unassign_config_from_group', 'W', 'Remove the saltboot config assignment of a system group'
    WHERE NOT EXISTS (SELECT 1 FROM access.namespace WHERE namespace = 'api.saltboot.unassign_config_from_group' AND access_mode = 'W');

INSERT INTO access.endpoint (class_method, endpoint, http_method, scope, auth_required)
    SELECT 'com.redhat.rhn.frontend.xmlrpc.saltboot.SaltbootHandler.unassignConfigFromGroup', '/manager/api/saltboot/unassignConfigFromGroup', 'POST', 'A', True
    WHERE NOT EXISTS (SELECT 1 FROM access.endpoint WHERE endpoint = '/manager/api/saltboot/unassignConfigFromGroup' AND http_method = 'POST');

INSERT INTO access.endpointNamespace (namespace_id, endpoint_id)
    SELECT ns.id, ep.id FROM access.namespace ns, access.endpoint ep
    WHERE ns.namespace = 'api.saltboot.unassign_config_from_group' AND ns.access_mode = 'W'
    AND ep.endpoint = '/manager/api/saltboot/unassignConfigFromGroup' AND ep.http_method = 'POST'
    ON CONFLICT DO NOTHING;

INSERT INTO access.namespace (namespace, access_mode, description)
    SELECT 'api.saltboot.unassign_config_from_system', 'W', 'Remove the saltboot config assignment of a system'
    WHERE NOT EXISTS (SELECT 1 FROM access.namespace WHERE namespace = 'api.saltboot.unassign_config_from_system' AND access_mode = 'W');

INSERT INTO access.endpoint (class_method, endpoint, http_method, scope, auth_required)
    SELECT 'com.redhat.rhn.frontend.xmlrpc.saltboot.SaltbootHandler.unassignConfigFromSystem', '/manager/api/saltboot/unassignConfigFromSystem', 'POST', 'A', True
    WHERE NOT EXISTS (SELECT 1 FROM access.endpoint WHERE endpoint = '/manager/api/saltboot/unassignConfigFromSystem' AND http_method = 'POST');

INSERT INTO access.endpointNamespace (namespace_id, endpoint_id)
    SELECT ns.id, ep.id FROM access.namespace ns, access.endpoint ep
    WHERE ns.namespace = 'api.saltboot.unassign_config_from_system' AND ns.access_mode = 'W'
    AND ep.endpoint = '/manager/api/saltboot/unassignConfigFromSystem' AND ep.http_method = 'POST'
    ON CONFLICT DO NOTHING;

INSERT INTO access.namespace (namespace, access_mode, description)
    SELECT 'api.saltboot.get_terminal_state', 'R', 'Get the deployment state of a saltboot terminal'
    WHERE NOT EXISTS (SELECT 1 FROM access.namespace WHERE namespace = 'api.saltboot.get_terminal_state' AND access_mode = 'R');

INSERT INTO access.endpoint (class_method, endpoint, http_method, scope, auth_required)
    SELECT 'com.redhat.rhn.frontend.xmlrpc.saltboot.SaltbootHandler.getTerminalState', '/manager/api/saltboot/getTerminalState', 'GET', 'A', True
    WHERE NOT EXISTS (SELECT 1 FROM access.endpoint WHERE endpoint = '/manager/api/saltboot/getTerminalState' AND http_method = 'GET');

INSERT INTO access.endpointNamespace (namespace_id, endpoint_id)
    SELECT ns.id, ep.id FROM access.namespace ns, access.endpoint ep
    WHERE ns.namespace = 'api.saltboot.get_terminal_state' AND ns.access_mode = 'R'
    AND ep.endpoint = '/manager/api/saltboot/getTerminalState' AND ep.http_method = 'GET'
    ON CONFLICT DO NOTHING;

INSERT INTO access.namespace (namespace, access_mode, description)
    SELECT 'api.saltboot.get_effective_terminal_config', 'R', 'Get the saltboot settings that apply to a terminal'
    WHERE NOT EXISTS (SELECT 1 FROM access.namespace WHERE namespace = 'api.saltboot.get_effective_terminal_config' AND access_mode = 'R');

INSERT INTO access.endpoint (class_method, endpoint, http_method, scope, auth_required)
    SELECT 'com.redhat.rhn.frontend.xmlrpc.saltboot.SaltbootHandler.getEffectiveTerminalConfig', '/manager/api/saltboot/getEffectiveTerminalConfig', 'GET', 'A', True
    WHERE NOT EXISTS (SELECT 1 FROM access.endpoint WHERE endpoint = '/manager/api/saltboot/getEffectiveTerminalConfig' AND http_method = 'GET');

INSERT INTO access.endpointNamespace (namespace_id, endpoint_id)
    SELECT ns.id, ep.id FROM access.namespace ns, access.endpoint ep
    WHERE ns.namespace = 'api.saltboot.get_effective_terminal_config' AND ns.access_mode = 'R'
    AND ep.endpoint = '/manager/api/saltboot/getEffectiveTerminalConfig' AND ep.http_method = 'GET'
    ON CONFLICT DO NOTHING;

INSERT INTO access.namespace (namespace, access_mode, description)
    SELECT 'api.saltboot.schedule_force_redeploy', 'W', 'Flag a saltboot terminal for redeployment'
    WHERE NOT EXISTS (SELECT 1 FROM access.namespace WHERE namespace = 'api.saltboot.schedule_force_redeploy' AND access_mode = 'W');

INSERT INTO access.endpoint (class_method, endpoint, http_method, scope, auth_required)
    SELECT 'com.redhat.rhn.frontend.xmlrpc.saltboot.SaltbootHandler.scheduleForceRedeploy', '/manager/api/saltboot/scheduleForceRedeploy', 'POST', 'A', True
    WHERE NOT EXISTS (SELECT 1 FROM access.endpoint WHERE endpoint = '/manager/api/saltboot/scheduleForceRedeploy' AND http_method = 'POST');

INSERT INTO access.endpointNamespace (namespace_id, endpoint_id)
    SELECT ns.id, ep.id FROM access.namespace ns, access.endpoint ep
    WHERE ns.namespace = 'api.saltboot.schedule_force_redeploy' AND ns.access_mode = 'W'
    AND ep.endpoint = '/manager/api/saltboot/scheduleForceRedeploy' AND ep.http_method = 'POST'
    ON CONFLICT DO NOTHING;
