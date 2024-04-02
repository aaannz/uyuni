#!/bin/bash

set -xe

# HACK: avoid adding repos if building inside of OBS (repos come from project configuration)
if [ -n "$1" ]; then
#    zypper addrepo http://download.opensuse.org/distribution/leap/15.5/repo/oss/ main
#    zypper addrepo http://download.opensuse.org/update/leap/15.5/sle/ updates
    zypper addrepo https://download.suse.de/ibs/SUSE:/SLE-15-SP5:/GA/standard/ main_sle
    zypper addrepo http://download.suse.de/ibs/SUSE:/SLE-15-SP5:/Update/standard/ updates_sle

    zypper addrepo $1 product
fi
