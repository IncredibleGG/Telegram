# LuminaGram Android de-brand — human review queue

Purpose: strings where the word **Telegram** is *ambiguous* between "the app describing
itself" (safe to rebrand) and "the Telegram network / service / other users" (must NOT be
rebranded — renaming those is misleading and a ToS violation). These were **deliberately
NOT changed** by the automated pass. Decide each and, for any you approve, add its key to
`LUMINA_BRAND_LOCKED_KEYS` in `TMessagesProj/src/main/java/org/telegram/messenger/LocaleController.java`.

## How the replacement mechanism works (context for reviewers)
- `LUMINA_BRAND_LOCKED_KEYS` (a HashSet in `LocaleController.java`, just above `getStringInternal`)
  lists the STOCK string keys whose resolved value should have the brand word swapped.
- Every value goes through `luminaRebrand(value)` (new helper) at three resolution sites
  (`getStringInternal`, `formatString`, `formatSpannable`) **only when the key is in the set**.
- `luminaRebrand` swaps `TELEGRAM`→`LUMINAGRAM`, `Telegram`→`LuminaGram` directly, then does a
  **URL-guarded** lowercase pass (`telegram`→`luminagram`) that skips any `telegram` glued to
  URL/domain characters, so embedded `telegram.org` / `*.telegram.org` / `t.me` links in cloud
  translations are never corrupted.
- The swap is **whole-value**: it hits *every* occurrence of the brand word in the string. So a
  string that mixes an app-self reference with a network reference **cannot** be added as-is —
  it would wrongly rename the network reference too. Those are marked MIXED below.

Note: this queue lists only the *ambiguous* keys. The ~440 clear network/service/Premium/Stars/
Fragment/sponsored/official/URL strings (e.g. "Anyone on Telegram will be able to join",
"Telegram Premium", "Telegram moderators", "telegram.org/...") were left untouched and are not
listed here — they are unambiguous KEEPs.

Suggestion legend: **change** = looks app-self / **keep** = looks network / **unsure** = genuinely split.

---

## A. MIXED — do NOT whitelist as-is (would rename a network reference)
- **ContactsPermissionAlert** — "**Telegram** needs access to your contacts so that you can connect with your friends across all your devices. Your contacts will be continuously synced with Telegram's heavily encrypted cloud servers."
  - suggestion: **unsure / manual** — 1st "Telegram" = app asking permission (change), 2nd "Telegram's ... cloud servers" = the network (keep). Whitelisting swaps both. If wanted, hand-edit only the first occurrence in `strings.xml`; do not add the key. (This is the string the auto-classifier previously mis-bucketed.)

## B. Login/verification code sent to "the Telegram app" on another device
- **SentAppCode** — "We've sent the code to the **Telegram** app on your other device."
- **SentAppCodeWithPhone** — "We've sent the code to the **Telegram** app for %1$s on your other device."
- **SentAppCodeTitle** — "Check your Telegram messages"
- **OtherLoginCode** — "Your login code is **%1$s**. Enter it in the Telegram app where you are trying to log in.\n\nDo not give this code to anyone."
  - suggestion (all): **unsure** — "the Telegram app on your other device" may be an *official* Telegram client, not our fork; renaming would be inaccurate. (Note: the maintainers already whitelisted the sibling `SentCallCode`, so parity would argue for change — human call.)

## C. Call-UI product branding ("Telegram Call")
These are the labels our app shows for its own call feature (system call UI / notifications).
Lean app-self, but there are many and some are network-flavored.
- **VoipInCallBranding** — "Telegram Call"  → suggestion: **change**
- **VoipInVideoCallBranding** — "Telegram Video Call"  → **change**
- **VoipInConferenceCallBranding** — "Telegram Group Call"  → **change**
- **VoipOutgoingCall** — "Ongoing Telegram call"  → **change**
- **CallViaTelegram** — "Telegram Call"  → **change**
- **VideoCallViaTelegram** — "Telegram Video Call"  → **change**
- **VoiceCallViaTelegram** — "Telegram Voice Call"  → **change**
- **VoipInCallBrandingWithName** — "Telegram Call to %s"  → **change**
- **VoipInVideoCallBrandingWithName** — "Telegram Video Call to %s"  → **change**
- **VoipErrorUnknown** — "Can't place a Telegram call at the moment."  → **change**
- **VoipRateCallAlert** — "Please rate the quality of your Telegram call"  → **change**
- **CallReportSent** — "Thank you for helping make Telegram calls better."  → **change**
- **AllowBackgroundActivityInfo** — "This will improve reliability for incoming notifications and Telegram calls."  → **change**
- **AllowBackgroundActivityInfoOneUIBelowS** — same + "Tap Settings -> Battery ..."  → **change**
- **AllowBackgroundActivityInfoOneUIAboveS** — same + "select Optimised or Unrestricted ..."  → **change**
- **VoipRingtoneInfo** — "You can customize the ringtone used when this contact calls you on Telegram."  → suggestion: **keep** ("calls you on Telegram" reads as the network)
- **VoipQuickRepliesExplain** — "These quick responses will be available when you respond to an incoming call with a Telegram message. Change them to say anything you like."  → **unsure** ("a Telegram message" = a message on the network)
- **GroupCallLinkText** — "You are invited to join a Telegram call."  → suggestion: **keep** (invitation surfaced to any Telegram user)

## D. In-app browser / in-app navigation feature naming
- **OpenInTelegramBrowser** — "Open in Telegram"  → **change** (note: sibling `OpenInTelegramBrowser2` was already reworded to "Open In-App")
- **BrowserSettingsCustomTabs** — "New Pages within Telegram"  → **change**
- **BrowserSettingsNoCustomTabsInfo** — "You can choose to open external link without leaving Telegram."  → **change**
- **BrowserSettingsEnableInfo** — "Open links inside Telegram without starting an external browser."  → **change**
- **BrowserSettingsCookiesInfo** — "Delete all cookies and cache from the Telegram in-app browser and miniapps."  → **change**
- **BrowserExternalRestricted** — "Telegram will now open links from this website in the external browser. You can change this in **Settings.**"  → **change**
- **IncomingCallsSystemSettingDescription** — "You can change ringtone sound in Telegram -> Settings -> Notifications and Sounds."  → **change** (in-app menu path)

## E. Invite family (parity with already-whitelisted InviteToTelegram / InviteText2)
- **InviteToTelegramShort** — "Invite To Telegram"  → **change?** (the full `InviteToTelegram` is already whitelisted → "INVITE TO LUMINAGRAM"; parity argues change)
- **ContactNotRegisteredTitle** — "Invite to Telegram"  → **change?** (same CTA; but its body `ContactNotRegistered` "%1$s is not on Telegram yet" is a clear network KEEP)
- **InviteTextNum_one** — "Hey, I'm using Telegram to chat – and so are %1$d of our other contact. Join us! Download it here: %2$s"  → **change?** (parity with whitelisted `InviteText2`; "Join us" has network flavor)
- **InviteTextNum_other** — plural of the above  → **change?**
  - suggestion (group): parity-driven; decide together with the existing invite whitelist entries.

## F. App capability / cloud-storage descriptions (app-self vs service)
- **DownloadedFilesMessage** — "Telegram lets you store all documents you send and receive in the cloud and save storage space on your device."  → **unsure** ("lets you store ... in the cloud" describes the service)
- **LowDiskSpaceMessage** — "Your device is almost out of disk space. To free some space, you can set up Telegram to cache only recent media."  → **change?** (configuring the app's own cache)

## G. "on Telegram" phrasing for an in-app action
- **ScreenCaptureAlert** — "... but you will not be able to take screenshots on Telegram.\n\nYou may need to restart the app for this to take effect."  → **unsure** ("on Telegram" = in this app, but phrased like the network)

## H. App-self update text sitting inside a monetization/gift flow
- **Gift2ExportTONUpdateRequiredText** — "Please update your Telegram application to the latest version."  → **change?** (text itself is app-self; deferred only because it lives in the TON/gift flow)

## I. SMS delivery (service infrastructure vs app)
- **SMSFee2Text** — "Telegram needs to send you an SMS with a verification code to confirm your phone number."  → suggestion: **keep** (the SMS is sent by Telegram's server infrastructure, not the client app; phrasing merely resembles a permission)

## J. Historical app-version reference
- **EnableAllStreamingInfo** — "Some videos can't be streamed – e.g., all videos that were sent from Android using Telegram 4.7 and earlier versions."  → **change?** (refers to the app version; low value)

## K. Background connection (network)
- **NotificationsServiceConnectionInfo** — "Keep a low impact background connection to Telegram for reliable notifications."  → suggestion: **keep** (a connection *to the Telegram network*)
