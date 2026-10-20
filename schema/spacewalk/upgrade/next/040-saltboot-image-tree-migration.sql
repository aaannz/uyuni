-- Queue the creation of the trees of the existing saltboot images (UpgradeCommand#createSaltbootTrees).
-- The creation itself is idempotent; avoid duplicate pending tasks.
INSERT INTO rhnTaskQueue (id, org_id, task_name, task_data)
SELECT nextval('rhn_task_queue_id_seq'), wc.id,
       'upgrade_satellite_create_saltboot_trees', 0
FROM web_customer wc
WHERE wc.id = 1
  AND NOT EXISTS (
      SELECT 1
      FROM rhnTaskQueue tq
      WHERE tq.org_id = wc.id
        AND tq.task_name = 'upgrade_satellite_create_saltboot_trees'
        AND tq.task_data = 0
  );
