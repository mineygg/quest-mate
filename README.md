<p align="center">
  <img src="assets/icon.svg" width="120" alt="Quest Mate icon">
</p>

<h1 align="center">Quest Mate - For Discord Quest Automation</h1>

<p align="center">
  Sign in once and Quest Mate enrolls in the active Discord Quests and finishes them in the background,<br>
  with live progress, a history of what it completed and optional notifications.
</p>

<p align="center">
  <a href="https://github.com/mineygg/quest-mate/releases/latest"><img src="https://img.shields.io/github/v/release/mineygg/quest-mate?label=release" alt="Latest release"></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84" alt="Android 8.0+">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-blue" alt="GPL-3.0"></a>
</p>

> **Unofficial.** Not affiliated with Discord. Automating quests may go against Discord's Terms of Service and can
> get an account restricted or banned. Use it at your own risk. See [Disclaimer](#more-details).

## Features

- **Automatic quests:** finds the quests that are live on your account, enrolls in them and completes them.
- **Easy sign-in:** log in on Discord's own page inside the app, or paste a token if you prefer.
- **Runs on its own** in the background, every 3 days by default (12 hours up to 7 days), or on demand with
  **Run quests now**.
- **Orb quests only** by default, so it skips quests that don't pay Orbs (can be switched off).
- **Live progress** on Home while a run is going, and a **history** in Activity with the reward and when it
  happened, plus a raw log for troubleshooting.
- **Notifications** when a quest is completed and when your sign-in expired.
- **Discord webhook** (optional) to post finished quests to a channel of your server.
- **Private:** the token is stored encrypted on the device and only sent to Discord.
- **Updates itself** from GitHub releases through an **Update** button (can be turned off).

### What each quest type supports

| Quest type | Supported | How it's done |
| --- | :---: | --- |
| Watch a video (desktop and mobile) | ✔ | Watch progress is sent in small steps at a human pace |
| Play a game | ✔ | The game is shown as played on your account, with a heartbeat every 30 s |
| Stream | — | Skipped |
| Activity (achievement inside a Discord Activity) | — | Skipped |
| Console only (PlayStation, Xbox) | — | Skipped |

Quests this app can't do are set aside with a note in the log. Quests that are already completed, expired or not started yet are skipped.

## Install

1. Download `questmate.apk` from the [latest release](https://github.com/mineygg/quest-mate/releases/latest) and install it.
   Android will ask you to allow installs from your browser or file manager the first time.
2. Open Quest Mate, accept the notice, and tap **Connect Discord**. Sign in on Discord's own page, or use
   **Paste token** under *More options*.
3. Allow notifications so you hear about finished quests and expired sign-ins.
4. In Settings, tap **Allow** under *Unrestricted battery usage*. Nothing runs until this is allowed, see below.

It runs on its own from there, or tap **Run quests now** on Home.

**Requirements:** Android 8.0 or newer, an internet connection and a Discord account.

---

## More details

**How a run works**
- A run connects to Discord, lists the quests of your account, enrolls in the ones that are live and does them one
  after the other. A quest takes about 15 minutes or more, so a run can take a while when there are several.
- Watch-video quests: Discord ignores progress that runs ahead of real time, so the progress is sent in small
  steps at a human pace, with a final update at the target.
- Play-a-game quests: the game is shown as played on your account and a heartbeat is sent every 30 seconds until
  the target is reached.
- A run is a foreground task with a notification, so Android keeps it alive while the app is closed. The
  connection to Discord stays open for a couple of minutes afterwards so a back-to-back run can reuse it, then it closes.
- Only one run goes at a time. Tapping **Run quests now** while one is going does nothing.

**Schedule**
- *Run automatically* is on by default. *Run every…* is a slider that snaps to 12 hours, 1, 2, 3 (recommended),
  4, 5, 6 or 7 days. **Use recommended** puts it back to 3 days.
- The automatic run is a periodic background job (Android WorkManager) that only starts with a connection, so it
  happens even when the app is closed.
- Turning *Run automatically* off stops the timer; **Run quests now** still works.

**Orb quests only**
- On by default. Only quests that pay Orbs are done. Switch it off in Settings to do every supported quest that is live.

**Battery**
- Quest Mate keeps a connection to Discord open while it works. When Android restricts the app's battery use it cuts
  that connection and the quests stall. That is why the app asks for *Unrestricted battery usage* and doesn't run until it has it.
- If no prompt appears, open the app's settings, then Battery, and choose **Unrestricted**. Settings shows whether it is
  allowed or restricted.

**Activity**
- *What the app did for you* lists every completed quest with its reward and when it happened. **Clear history** empties it.
- *Technical details* holds the raw log of the last runs, for troubleshooting.

**Notifications**
- One notification per finished quest, a summary after a scheduled run that completed quests, and an alert when
  Discord rejects the saved login. Turn them off in Settings. While a run is going, Android also shows a
  "Working on your quests…" notification; it is needed to keep the run alive.

**Discord webhook**
- Optional. In your server go to *Server Settings → Integrations → Webhooks*, create a webhook and paste its URL
  under *Discord webhook* in Settings. Finished quests are posted to that channel.
- The URL is stored encrypted and never shown again; use **Replace or remove** to change it.

**No internet**
- A run waits up to 90 seconds for a connection. If there is none it is retried later by Android.

**Sign-in and privacy**
- You sign in on Discord's own page; the session token is kept encrypted on this device (Android Keystore) and is
  only sent to Discord. **Paste token** checks the token with Discord first, then stores it the same way.
- **Sign out & delete token** removes the token and stops the automatic runs until you sign in again.
- Besides Discord, the app only talks to GitHub: to check for new releases and to refresh a small file of
  compatibility values from this repository. Neither sends anything from your account.

**Updates**
- Quest Mate checks this repository's latest GitHub release and offers an **Update** button in the app. The check can
  be turned off in Settings. Updates only install over a build signed with the same key.

**Appearance**
- Dark by default; switch to light or follow the system theme in Settings.

**Disclaimer**
Unofficial, not affiliated with Discord. Automating quests may go against Discord's Terms of Service and can get
an account restricted or banned. Use it at your own risk. Provided without warranty under the GNU GPL v3.

**Development**
See [BUILDING.md](BUILDING.md).

**Name, icon and identifiers**
The name "Quest Mate", the app icon (`assets/icon.svg`, `assets/icon_background.svg`, `assets/icon_foreground.svg`
and the launcher icon files in the app), the name "mineygg" and the application ID and package names
`com.mineygg.questmate` (`applicationId` and `namespace` in `app/build.gradle.kts`, and the Kotlin source
packages) are **not** covered by the GPL v3, and no rights to use them are granted. If you fork or modify Quest
Mate, remove them and use your own name, icon, application ID and package names, and don't suggest your version
comes from or is endorsed by the original author. The copyright notice and the link to this project must stay,
as `NOTICE` requires. The source code itself stays under the GPL v3.

**License**
Copyright (C) 2026 mineygg. GNU General Public License v3.0 (see `LICENSE`), with the attribution terms in `NOTICE`.
The name, icon and identifiers are excluded, as described above.
