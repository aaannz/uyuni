# `uyuni/` — base package, PAYG timer, storage setup

Small packaging/script directory with three subprojects; no Makefiles:

- `uyuni/base/` — `uyuni-base` RPM ("%{productprettyname} Base Package") with
  `common`/`server`/`proxy` subpackages providing the basic filesystem hierarchy;
  packaging only.
- `uyuni/uyuni-payg-timer/` — systemd timer/service for Cloud PAYG usage requiring
  `mgradm` (`uyuni-payg-timer.timer`, `uyuni-payg-timer.service`,
  `uyuni-payg-extract-data.py`).
- `uyuni/uyuni-storage-setup/` — scripts to set up additional storage for SUSE Manager
  and Uyuni; not a replacement for `mgradm` or `mgrpxy` (`uyuni/uyuni-storage-setup/README.md`).
  Usage: `mgr-storage-[server|proxy] block_device`. Scripts in
  `uyuni/uyuni-storage-setup/scripts/` (`mgr-storage-server`, `mgr-storage-proxy`).

Note: there is no Makefile here — the scripts are installed by the RPM
(`uyuni/uyuni-storage-setup/uyuni-storage-setup.spec`); the README's `make install`
mention is incorrect.
