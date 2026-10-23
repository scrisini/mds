# config/secrets

SOPS-encrypted files only (`*.enc.env`, `*.enc.yaml`, `*.enc.json`); the pre-commit hook and the
`secrets` workflow reject anything here that isn't encrypted. See README "Secrets in git (SOPS)".

- `local.enc.env`: secrets for running locally. If it exists, `scripts/dev.sh run` decrypts it into
  the app's environment (`MDS_INFLUX_TOKEN=...`); nothing decrypted is written to disk.
