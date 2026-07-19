# Android runtime assets

This directory contains the compressed runtime files copied into the app package.

- `rootfs/rootfs-usr.zip` contains the runtime userland.
- `rootfs/rootfs-libs.zip` contains the required shared libraries.

These archives are release inputs, not build output. Do not replace them with files from an installed Termux application. A runtime update must record its upstream source, architecture, version, checksum, and device verification in the release change.
