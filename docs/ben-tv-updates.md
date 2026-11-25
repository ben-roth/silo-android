# Keeping the household TV build up to date

The TV runs official Silo plus this branch's changes (genre limits, Save as
collection, admin-only Sign Out). To update it:

```bash
cd ~/Code/silo/silo-android && scripts/update-tv.sh
```

The script pulls in the official changes, keeps ours, runs the tests, builds,
saves the branch to GitHub, and installs on the TV. The first time, give it
the TV's address (`scripts/update-tv.sh 192.168.1.50`); it remembers it in
`local.properties`.

If it stops, nothing on the TV has changed:

- **"changed the same code as our changes"**: official Silo edited the same
  lines we did. Ask Claude to "merge upstream/main into ben/kids-genres".
- **"tests or build failed"**: the branch is back where it was. Ask Claude to
  look at it.
- **"Can't reach the TV"**: turn on the TV, check Developer options ›
  Network debugging, and accept the prompt on the TV.

## Things that must stay on this Mac

- `local.properties` (git-ignored) holds the genre config token
  (`silo.genreConfigToken`), the admin profile IDs
  (`silo.accountAdminProfileIds`) and the TV address (`silo.tvAddress`).
- `~/.android/debug.keystore` signs the APK. Android only updates an app with
  one signed by the same key, so a build from another computer, or the Play
  Store version, can't replace this one without uninstalling it first, which
  signs the TV out. Keep a backup of that file.

If the TV has the Play Store Silo (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`),
uninstall it once, then run the script again:

```bash
~/Library/Android/sdk/platform-tools/adb -s <TV address>:5555 uninstall org.siloserver.silo
```

Turn off automatic updates for Silo in the Play Store on the TV, or it may
offer to replace this build.
