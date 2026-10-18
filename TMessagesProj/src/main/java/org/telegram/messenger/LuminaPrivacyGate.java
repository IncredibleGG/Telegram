package org.telegram.messenger;

import org.telegram.tgnet.TLRPC;

/**
 * LuminaGram: hard privacy exclusions for content that may leave the device for a third-party
 * translation / AI / speech-to-text provider.
 *
 * <p>Two rules live here so every call site asks the same question:
 * <ul>
 *   <li>{@link #isProtectedDialog}: chats whose content is never handed to a provider by an
 *       AUTOMATIC flow (whole-chat / dual-language translation, eager auto-enable, auto-translate
 *       of voice transcripts, the receive-side voice pipeline). These are the user's own Saved
 *       Messages, Telegram's service-notification account 777000 (login codes), the other
 *       Telegram service accounts, BotFather, the verification-code accounts and any user Telegram
 *       flags as {@code support}. Manual actions on a single message (tap Translate, Explain,
 *       Transcribe) are not gated by this.</li>
 *   <li>{@link #isSelfDestructingMedia}: view-once / timed self-destructing media, which must
 *       never be uploaded to a CLOUD speech-to-text engine (on-device engines stay allowed).
 *       Chat-level auto-delete timers are deliberately not considered here.</li>
 * </ul>
 * Secret chats keep their existing, separate exclusions.
 */
public final class LuminaPrivacyGate {

    private LuminaPrivacyGate() {
    }

    /** BotFather's user id. */
    public static final long BOTFATHER_USER_ID = 93372553L;

    /**
     * Telegram official / system accounts identified by id alone: the service accounts
     * (333000, 777000 "Telegram" notifications with login codes, 42777), BotFather and the
     * "Verification Codes" / OAuth accounts that deliver codes for other services.
     */
    public static boolean isOfficialUserId(long userId) {
        return UserObject.isService(userId)
                || userId == BOTFATHER_USER_ID
                || userId == UserObject.VERIFY
                || userId == UserObject.OAUTH;
    }

    /**
     * True when {@code dialogId} must never feed an automatic third-party flow: the account's own
     * Saved Messages, a Telegram official / system account, or a user carrying Telegram's
     * {@code support} flag. Group / channel / secret dialogs return false here (secret chats are
     * excluded by their own checks). Never throws.
     */
    public static boolean isProtectedDialog(int account, long dialogId) {
        if (dialogId == 0 || !DialogObject.isUserDialog(dialogId)) {
            return false;
        }
        if (isOfficialUserId(dialogId)) {
            return true;
        }
        try {
            if (account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT
                    && UserConfig.getInstance(account).getClientUserId() == dialogId) {
                return true;
            }
        } catch (Throwable ignore) {
        }
        try {
            final TLRPC.User user = MessagesController.getInstance(account).getUser(dialogId);
            if (user != null && (user.support || user.self)) {
                return true;
            }
        } catch (Throwable ignore) {
        }
        return false;
    }

    /**
     * True for view-once ("play once") voice / round-video messages and for any media carrying a
     * per-media self-destruct timer ({@code ttl_seconds}). Chat-level auto-delete timers
     * ({@code ttl_period}) are not media timers and are not matched.
     */
    public static boolean isSelfDestructingMedia(MessageObject mo) {
        if (mo == null || mo.messageOwner == null) {
            return false;
        }
        try {
            if (mo.isVoiceOnce() || mo.isRoundOnce()) {
                return true;
            }
            final TLRPC.MessageMedia media = MessageObject.getMedia(mo.messageOwner);
            return media != null && media.ttl_seconds != 0;
        } catch (Throwable ignore) {
        }
        return false;
    }
}
