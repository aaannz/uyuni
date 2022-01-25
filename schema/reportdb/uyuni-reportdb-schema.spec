#
# spec file for package susemanager-reportdb-schema
#
# Copyright (c) 2021 SUSE LLC
# Copyright (c) 2008-2018 Red Hat, Inc.
#
# All modifications and additions to the file contributed by third parties
# remain the property of their copyright owners, unless otherwise agreed
# upon. The license for this file, and modifications and additions to the
# file, is the same license as for the pristine package itself (unless the
# license for the pristine package is not an Open Source License, in which
# case the license is the MIT License). An "Open Source License" is a
# license that conforms to the Open Source Definition (Version 1.9)
# published by the Open Source Initiative.

# Please submit bugfixes or comments via https://bugs.opensuse.org/
#

%define build_tar_ball 0
%{!?fedora: %global sbinpath /sbin}%{?fedora: %global sbinpath %{_sbindir}}

Name:           uyuni-reportdb-schema
Summary:        Report DB SQL schema for Spacewalk server
License:        GPL-2.0-only
Group:          Applications/Internet

Version:        4.3.0
Release:        1
%if %{build_tar_ball}
Source0:        %{name}-%{version}.tar.gz
%else
Source0:        _service
%endif

Source1:        %{name}-rpmlintrc

URL:            https://github.com/uyuni-project/uyuni
BuildArch:      noarch
BuildRoot:      %{_tmppath}/%{name}-%{version}-build

Requires:       susemanager-schema-utility
Requires:       susemanager-schema-sanity

%if 0%{?suse_version}
BuildRequires:  fdupes
%endif

%define rhnroot /etc/sysconfig/rhn/

#TODO the folder should be the same where spacewalk-startup-helper expects to have schema files  
%define postgres %{rhnroot}/reportdb

%description
susemanager-reportdb-schema is the SQL schema for the SUSE Manager server.

%install
install -m 0755 -d $RPM_BUILD_ROOT%{rhnroot}
install -m 0755 -d $RPM_BUILD_ROOT%{postgres}
#TODO Install SQL Script
#install -m 0644 reportdb/main.sql $RPM_BUILD_ROOT%{reportdb}
#install -m 0644 reportdb/end.sql $RPM_BUILD_ROOT%{reportdb}/upgrade-end.sql


#TODO Install SQL Upgrade Script
#install -m 0755 -d $RPM_BUILD_ROOT%{rhnroot}/reportdb-upgrade
#( cd upgrade && tar cf - --exclude='*.sql' . | ( cd $RPM_BUILD_ROOT%{rhnroot}/reportdb-upgrade && tar xf - ) )

%posttrans
#TODO Run uyuni-check-reportdb.service. We should probably not start report service if it fails
#systemctl is-active --quiet uyuni-check-database.service && {
#  echo "  Running DB schema upgrade. This may take a while."
#  echo "  Call the following command to see progress: journalctl -f -u uyuni-check-database.service"
#} ||:
#systemctl try-restart uyuni-check-database.service ||:

%files
%defattr(-,root,root)
%dir %{rhnroot}
%{reportdb}
%if 0%{?suse_version}
%dir /usr/share/susemanager
%endif

%changelog
