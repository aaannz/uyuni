#!/usr/bin/python3

import logging
import os
import re
import yaml
import sys

from typing import Tuple

config_path = "/etc/uyuni/"

# read from files
with open(config_path + "config.yaml") as source:
    config = yaml.safe_load(source)

    # log_level is the value for rhn.conf and should be a positive integer
    log_level = logging.WARNING if config.get("log_level") == 1 else logging.DEBUG
    logging.getLogger().setLevel(log_level)

with open(config_path + "httpd.yaml") as httpdSource:
    httpdConfig = yaml.safe_load(httpdSource).get("httpd")
   
    # store SSL CA certificate
    with open("/etc/pki/trust/anchors/RHN-ORG-TRUSTED-SSL-CERT", "w") as file:
        file.write(config.get("ca_crt"))
    os.system("/usr/sbin/update-ca-certificates")

    # store server certificate files
    with open("/etc/apache2/ssl.crt/server.crt", "w") as file:
        file.write(httpdConfig.get("server_crt"))
    with open("/etc/apache2/ssl.key/server.key", "w") as file:
        file.write(httpdConfig.get("server_key"))

    with open("/etc/apache2/httpd.conf", "r+") as file:
        file_content = file.read()
        # make sure to send logs to stdout/stderr instead to file
        file_content = re.sub(r"ErrorLog .*", "ErrorLog /proc/self/fd/2", file_content)
        # writing back the content
        file.seek(0,0)
        file.write(file_content)
        file.truncate()

    with open("/etc/apache2/conf.d/cobbler-proxy.conf", "w") as file:
        file.write(f'''ProxyPass /cobbler_api https://{config['server']}/download/cobbler_api
ProxyPassReverse /cobbler_api https://{config['server']}/download/cobbler_api
RewriteRule ^/cblr/svc/op/ks/(.*)$ /download/$0 [P,L]
RewriteRule ^/cblr/svc/op/autoinstall/(.*)$ /download/$0 [P,L]
ProxyPass /cblr https://{config['server']}/cblr
ProxyPassReverse /cblr https://{config['server']}/cblr
ProxyPass /cobbler https://{config['server']}/cobbler
ProxyPassReverse /cobbler https://{config['server']}/cobbler
        ''')

    with open("/etc/apache2/vhosts.d/ssl.conf", "w") as file:
        file.write(f'''
<IfDefine SSL>
<IfDefine !NOSSL>
<VirtualHost _default_:443>

	DocumentRoot "/srv/www/htdocs"
	ServerName {config['proxy_fqdn']}

	ErrorLog /proc/self/fd/2
	TransferLog /proc/self/fd/1
	CustomLog /proc/self/fd/1   ssl_combined

	SSLEngine on
	SSLUseStapling  on

    SSLCertificateFile /etc/apache2/ssl.crt/server.crt
    SSLCertificateKeyFile /etc/apache2/ssl.key/server.key

    SSLProtocol all -SSLv2 -SSLv3
    RewriteEngine on
    RewriteOptions inherit
    SSLProxyEngine on
</VirtualHost>
</IfDefine>
</IfDefine>
''')

    with open("/etc/apache2/conf.d/uyuni-proxy.conf", "w") as file:
        file.write(f'''
# prevent warnings about hostname, set ServerName globaly
ServerName {config['proxy_fqdn']}

# enable caching for all requests; cache content on local disk
CacheEnable disk /
CacheRoot /var/cache/apache2/
# 15GiB max file size - for os images
CacheMaxFileSize 16106127360

# Disable QuickHandler so we can check Locations and Directory handlers
CacheQuickHandler off

# cache control
CacheIgnoreNoLastMod On
CacheIgnoreCacheControl On
CacheIgnoreQueryString On

# unset headers from upstream server
Header unset Expires
Header unset Cache-Control
Header unset Pragma

# set expiration headers for static content
ExpiresActive On

# Saltboot images - virtually unlimited
<LocationMatch "^/(os-images|saltboot|tftp/images)/.*$">
  ExpiresDefault "now plus 1 month"
  CacheMaxExpire 2678400
</LocationMatch>
# The rest of tftp data - 10 minutes
<LocationMatch "^/tftp/.$">
  ExpiresDefault "now plus 10 minutes"
  CacheMaxExpire 600
</LocationMatch>

<LocationMatch "^/pub/repositories/.*/(repodata|venv-enabled-)/.*$>
  ExpiresDefault "now plus 10 minutes"
  CacheMaxExpire 600
</LocationMatch>

# Disable caching for cobbler and api
<LocationMatch "^/(cobbler|cblr)/.*$">
  CacheDisable on
</LocationMatch>
<LocationMatch "^/(rpc/api|rhn/manager/api)/.*$">
  CacheDisable on
</LocationMatch>

# reverse proxy requests to upstream server
ProxyRequests Off # used for forward proxying
SSLProxyEngine On # required if proxying to https
ProxyPass / https://{config['server']}/
ProxyPassReverse / https://{config['server']}/
''')

os.system("chown -R wwwrun:www /var/cache/apache2")
