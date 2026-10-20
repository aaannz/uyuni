-- Image-based kickstartable trees for saltboot

ALTER TABLE rhnKickstartableTree ALTER COLUMN channel_id DROP NOT NULL;

ALTER TABLE rhnKickstartableTree ADD COLUMN IF NOT EXISTS image_id NUMERIC
    CONSTRAINT rhn_kstree_iid_fk REFERENCES suseImageInfo (id) ON DELETE CASCADE;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'rhn_kstree_source_ck') THEN
        ALTER TABLE rhnKickstartableTree ADD CONSTRAINT rhn_kstree_source_ck
            CHECK (num_nonnulls(channel_id, image_id) = 1);
    END IF;
END
$$;

CREATE INDEX IF NOT EXISTS rhn_kstree_iid_idx ON rhnKickstartableTree (image_id);

INSERT INTO rhnKSInstallType (id, label, name)
SELECT sequence_nextval('rhn_ksinstalltype_id_seq'), 'saltboot', 'Saltboot image'
ON CONFLICT (label) DO NOTHING;
