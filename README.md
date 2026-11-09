# Cyber Hayat Ijmāʿ (إجماع)
Private WhatsApp-style messenger for one organization. Kotlin WebView shell + offline-first HTML app + Google Apps Script/Sheet backend.

## Get the APK (GitHub)
1. Unzip, then upload the **contents of the `CyberHayatIjma` folder** to a new GitHub repo so that `.github/workflows/main.yml` sits at the repo root.
   (Terminal: `cd CyberHayatIjma && git init && git add . && git commit -m init && git branch -M main && git remote add origin <repo-url> && git push -u origin main`)
2. Open the repo's **Actions** tab. The workflow runs on every push (or press *Run workflow*).
3. Download `CyberHayatIjma.apk` from the run's Artifacts or from the repo's **Releases** page.

## Connect the backend
1. Use `Relay.gs` with the laptop server (see IjmaServer/README_SETUP.md). The old standalone Code.gs is no longer used.
2. Run `setup()` once. The first owner invite code is printed in the execution log.
3. Deploy → New deployment → Web app → Execute as *Me*, Access *Anyone*. Copy the `/exec` URL.
4. Put it in `app/src/main/assets/www/js/config.js` (`apiUrl`), commit, and let the workflow rebuild.
5. Install the APK, enter the owner invite code. Create more codes with the `invite_create` action.

The APK is signed with the debug key so it installs directly (like Nijah). Use your own keystore before publishing to the Play Store.
