# Google Play Internal Track Runbook

This runbook documents the configuration and day-to-day operation of the Google Play internal testing track lane for the Lararium Android client (`app.lararium`).

---

## 1. One-Time Console Setup (Manual, for App Owner)

These setup steps are performed manually by the account owner in the Google Play Console and Google Cloud Console. They are not automated by CI.

### 1.1 Create App Entry
1. Open the [Google Play Console](https://play.google.com/console).
2. Click **Create app**.
3. Fill in the initial application details:
   - **App name**: `Lararium`
   - **Default language**: `English (United States)`
   - **App or game**: `App`
   - **Free or paid**: `Free`
4. Accept the Declarations (Developer Program Policies and US export laws) and click **Create app**.
5. Confirm that the application ID matches `app.lararium` (as configured in `app/build.gradle.kts`).

### 1.2 Play App Signing Decision
- **Recommendation: Enroll in Google Play App Signing.**
- **Rationale**:
  - Treat the repo's existing keystore (`ANDROID_KEYSTORE_BASE64`) as a **rotatable upload key**.
  - Google creates and safely stores the master app signing key in secure key management infrastructure.
  - If the CI upload key is ever lost, leaked, or needs rotation, you can request an upload key reset directly through Play Console support without stranding existing users or breaking update paths.
  - The CI workflow signs the Android App Bundle (AAB) using the upload key via `-PLARARIUM_UPLOAD_*` Gradle properties. Google Play verifies this upload signature and re-signs the delivered APKs with the master app signing key.

### 1.3 Service Account & Secret Setup
To allow GitHub Actions CI to upload releases via `r0adkll/upload-google-play`:
1. In the [Google Cloud Console](https://console.cloud.google.com/) for the project linked to the Play Console developer account:
   - Go to **APIs & Services** > **Library**, search for **Google Play Android Developer API**, and enable it.
   - Go to **IAM & Admin** > **Service Accounts** and click **Create Service Account** (e.g. `lararium-play-deployer`).
   - Create a private key for this service account in **JSON** format and download it.
2. In the [Google Play Console](https://play.google.com/console):
   - Navigate to **Users and permissions** and click **Invite new user**.
   - Enter the email address of the newly created service account.
   - Under **App permissions**, click **Add app**, select `app.lararium`, and grant:
     - *Release apps to testing tracks*
     - *Manage testing tracks and edit tester lists*
     - *View app information and download bulk data*
   - Click **Invite user** to grant access.
3. In the GitHub repository:
   - Navigate to **Settings** > **Secrets and variables** > **Actions**.
   - Create a repository secret named `PLAY_SERVICE_ACCOUNT_JSON`.
   - Paste the raw, plain-text contents of the service account JSON key file.

### 1.4 Data Safety Form
- Under **App content** > **Data safety**, complete the questionnaire:
- **Declaration: Lararium collects and shares NOTHING (zero telemetry).**
  - Data collection: Select **No** (the app does not collect or share user data).
  - Tracking/Analytics: The app contains no third-party tracking, advertising SDKs, Firebase, or analytics SDKs.
  - Network communication: All communication occurs exclusively with user-configured hearth endpoints over secure transport.
  - Account/data deletion: Select appropriate declarations matching the local-first, zero-retention policy.

### 1.5 Content Rating
1. Under **App content** > **Content rating**, start the questionnaire (IARC).
2. Enter the contact email and select category **Utility, Productivity, Communication, or Other**.
3. Complete the questions (no violence, no sexual content, no profanity, no real-money gambling, no user location sharing).
4. Save and apply the calculated rating summary (e.g. PEGI 3 / Everyone).

### 1.6 Privacy Policy URL
1. Under **App content** > **Privacy policy**, specify the public privacy policy URL (e.g. `https://lararium.io/privacy`).
2. The policy confirms that Lararium operates locally and against self-hosted infrastructure with zero data harvesting.

### 1.7 Internal Testing Track & Tester List
1. In Google Play Console, navigate to **Testing** > **Internal testing**.
2. Select or create the track.
3. Under the **Testers** tab:
   - Create an email list (e.g. "Core Testers") or link an authorized Google Group.
   - Add tester email addresses.
   - Copy the **Join on Android** / **Join on the web** opt-in URL and distribute it to testers.
   - Testers must open this link once on their device and accept the invitation to receive internal updates from Google Play.

---

## 2. Day-to-Day Operations

### 2.1 Version Bumps (`app/build.gradle.kts`)
Google Play strictly rejects uploads with duplicate or non-increasing version codes.
1. Before releasing to Google Play, create a pull request that updates `app/build.gradle.kts`:
   - Increment `versionCode` (integer, e.g. `17` -> `18`).
   - Bump `versionName` (e.g. `"0.9.6-alpha"` -> `"0.9.7-alpha"`).
2. Review and merge the PR into `main`.

### 2.2 Running the Play Internal Workflow
The workflow `.github/workflows/play-internal.yml` is triggered on demand via `workflow_dispatch`:
1. In GitHub, go to **Actions** > **Play Internal**.
2. Click **Run workflow**:
   - **`draft` (default: `true`)**:
     - When set to `true`: Uploads the release bundle as a draft on the internal testing track. This allows reviewing the release notes, bundle details, and device compatibility in the console before rolling it out.
     - When set to `false`: Automatically rolls out the release directly to internal testers.
3. If `PLAY_SERVICE_ACCOUNT_JSON` is not yet configured in repository secrets, the workflow builds the signed AAB, uploads it as a workflow artifact (`lararium-<versionName>-internal.aab`), and skips the Play upload step with a clear informational message.

### 2.3 Console Promotion and Monitoring
1. In the Google Play Console under **Testing** > **Internal testing**:
   - If uploaded as a draft release: review the release, add release notes, and click **Save** > **Review release** > **Start rollout to Internal testing**.
   - Monitor the **Pre-launch report** for crashes, ANRs, visual defects, and security warnings detected on automated test devices.
   - Monitor tester feedback and **Android vitals**.
2. When ready to promote to broader audiences, click **Promote release** from the internal track to **Closed testing**, **Open testing**, or **Production**.

### 2.4 Sideload APK Lane (Unchanged & First-Class)
- The GitHub Releases APK lane (`.github/workflows/release-apk.yml`, triggered on tags matching `v*`) remains completely unchanged and continues as a first-class distribution path.
- Sideload users and users running alternative Android environments can download signed APKs directly from GitHub Releases without interacting with Google Play.
- Both lanes sign with the same upload keystore, preserving signing consistency across distribution channels.
