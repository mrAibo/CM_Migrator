# Security Policy

## Secrets and local configuration

Do not commit passwords, encoded credentials, private keys, keystores, production host details, journal databases, generated reports, or WebGUI run snapshots.

Create local configuration files from the committed examples:

```bash
cp conf/migration.properties.example conf/migration.properties
cp conf/webgui.properties.example conf/webgui.properties
cp conf/cmbcmenv.properties.example conf/cmbcmenv.properties
cp conf/cmbicmsrvs.ini.example conf/cmbicmsrvs.ini
# Optional, only when required by the approved IBM SDK setup:
# cp conf/ibmcmconfig.properties.example conf/ibmcmconfig.properties
chmod 600 conf/migration.properties conf/webgui.properties \
  conf/cmbcmenv.properties conf/cmbicmsrvs.ini
```

Prefer environment variables or an operating-system secret facility for credentials. The legacy CM password encoding is reversible and must not be treated as encryption.

## Required response to an exposed credential

1. Rotate the credential immediately.
2. Remove it from the current branch.
3. Purge it from Git history with an approved history-rewrite procedure.
4. Invalidate cached clones or deployment copies that still contain it.
5. Verify that no generated reports, logs, run snapshots, or release archives contain the value.

## WebGUI exposure

The WebGUI should remain bound to `127.0.0.1` and be accessed through an SSH tunnel. Do not expose HTTP Basic Authentication over an unencrypted network connection.

## Reporting a vulnerability

Report suspected vulnerabilities privately to the repository owner. Do not open a public issue containing credentials, internal hostnames, customer data, document identifiers, or exploit details.
