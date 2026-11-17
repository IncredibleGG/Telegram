/*
 * LuminaGram - AdShield ("隱藏重複廣告"): Telegram objects -> engine input (SPEC §9.1).
 * Copyright (C) 2026 LuminaGram contributors. Licensed under GPL-3.0-or-later.
 */
package org.telegram.messenger.lumina;

import androidx.collection.LongSparseArray;

import org.telegram.messenger.ChatObject;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_bots;
import org.telegram.tgnet.tl.TL_keyboard;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Copies plain fields out of Telegram objects into {@link LuminaAdShield.MessageInput} /
 * {@link LuminaAdShield.ChatContext}, exactly per the engine author's integration notes. UI thread only
 * (it reads MessagesController caches); the results are plain objects that the engine thread owns.
 *
 * <p>Media whitelist (SPEC §3): only {@code TL_messageMediaPhoto.photo} and {@code
 * TL_messageMediaDocument.document} ids are used; never {@code MessageObject.getPhoto()/getDocument()},
 * which would also return web-page preview media.
 */
final class LuminaAdShieldMapper {

    /** Bot-API id offset of channels / supergroups: -100&lt;id&gt;. */
    static final long CHANNEL_BASE = 1000000000000L;

    private LuminaAdShieldMapper() {
    }

    /** One message to observe; {@code photo} is set when the similar-image hook applies. */
    static final class Item {
        LuminaAdShield.MessageInput input;
        byte[] stripped;            // inline stripped thumbnail of the photo (copied reference)
        int bucket;                 // aspect-ratio bucket
        long photoId;               // the photo's own id
        boolean incoming;           // not sent by me (for the "all new messages folded" check)
    }

    /** Roles and known member handles of one chat (SPEC §5.1, §9.1). */
    static final class Roles {
        final HashMap<Long, String> roleByUser = new HashMap<Long, String>();
        final ArrayList<String> handles = new ArrayList<String>();
        boolean known;
        long signature;
    }

    // =====================================================================================
    // Chat
    // =====================================================================================

    /** SPEC §6.1 white list; null = ineligible (private, secret, Saved Messages, broadcast, monoforum). */
    static String chatType(TLRPC.Chat chat) {
        if (chat == null || ChatObject.isMonoForum(chat)) {
            return null;
        }
        if (ChatObject.isChannel(chat)) {
            if (chat.gigagroup) {
                return LuminaAdShield.CHAT_GIGAGROUP;
            }
            if (!chat.megagroup) {
                return null;
            }
            return ChatObject.isForum(chat) ? LuminaAdShield.CHAT_FORUM : LuminaAdShield.CHAT_SUPERGROUP;
        }
        return LuminaAdShield.CHAT_GROUP;
    }

    static long botApiChatId(TLRPC.Chat chat) {
        return ChatObject.isChannel(chat) ? -(CHANNEL_BASE + chat.id) : -chat.id;
    }

    static long botApiChannelId(long channelId) {
        return -(CHANNEL_BASE + channelId);
    }

    /**
     * Admin / creator roles and the known member handles (loaded admins + group bots). {@code known} is
     * the SPEC's adminsKnown: the list was actually loaded and is non-empty. There is no time-out fallback:
     * while the list is unknown every decision stays pending (fail-open, nothing folds), so a real admin
     * is never mistaken for a member (SPEC §6.9).
     */
    static Roles roles(int account, TLRPC.Chat chat, TLRPC.ChatFull full) {
        MessagesController mc = MessagesController.getInstance(account);
        Roles r = new Roles();
        TreeMap<Long, String> sorted = new TreeMap<Long, String>();
        if (ChatObject.isChannel(chat)) {
            LongSparseArray<TLRPC.ChannelParticipant> admins = mc.getLuminaChannelAdmins(chat.id);
            if (admins != null) {
                for (int i = 0; i < admins.size(); i++) {
                    long uid = admins.keyAt(i);
                    TLRPC.ChannelParticipant p = admins.valueAt(i);
                    String role = p instanceof TLRPC.TL_channelParticipantCreator ? LuminaAdShield.ROLE_CREATOR
                            : p instanceof TLRPC.TL_channelParticipantAdmin ? LuminaAdShield.ROLE_ADMIN : null;
                    if (role != null && uid > 0) {
                        sorted.put(uid, role);
                    }
                }
                r.known = admins.size() > 0;
            }
        } else if (full != null && full.participants != null && full.participants.participants != null) {
            for (TLRPC.ChatParticipant p : full.participants.participants) {
                if (p instanceof TLRPC.TL_chatParticipantCreator) {
                    sorted.put(p.user_id, LuminaAdShield.ROLE_CREATOR);
                } else if (p instanceof TLRPC.TL_chatParticipantAdmin) {
                    sorted.put(p.user_id, LuminaAdShield.ROLE_ADMIN);
                }
            }
            r.known = !full.participants.participants.isEmpty();
        }
        long sig = r.known ? 1 : 0;
        for (Map.Entry<Long, String> e : sorted.entrySet()) {
            r.roleByUser.put(e.getKey(), e.getValue());
            sig = sig * 31 + e.getKey() * 7 + e.getValue().hashCode();
            addHandle(r, UserObject.getPublicUsername(mc.getUser(e.getKey())));
        }
        if (full != null && full.bot_info != null) {
            TreeMap<Long, Boolean> bots = new TreeMap<Long, Boolean>();
            for (TL_bots.BotInfo bi : full.bot_info) {
                if (bi != null && bi.user_id != 0) {
                    bots.put(bi.user_id, Boolean.TRUE);
                }
            }
            for (Long uid : bots.keySet()) {
                sig = sig * 31 + uid;
                addHandle(r, UserObject.getPublicUsername(mc.getUser(uid)));
            }
        }
        r.signature = sig;
        return r;
    }

    private static void addHandle(Roles r, String username) {
        if (username != null && !username.isEmpty() && !r.handles.contains(username)) {
            r.handles.add(username);
        }
    }

    /** SPEC §9.1 ChatContext. */
    static LuminaAdShield.ChatContext context(int account, TLRPC.Chat chat, String type, TLRPC.ChatFull full,
                                              Collection<MessageObject> pinned, Roles roles) {
        MessagesController mc = MessagesController.getInstance(account);
        LuminaAdShield.ChatContext.Builder b = LuminaAdShield.ChatContext.builder(botApiChatId(chat))
                .type(type)
                .username(ChatObject.getPublicUsername(chat))
                .adminsKnown(roles.known)
                .memberHandles(new ArrayList<String>(roles.handles))
                .selfUsername(UserObject.getPublicUsername(UserConfig.getInstance(account).getCurrentUser()));
        if (full != null) {
            b.description(full.about != null ? full.about : "");
            if (full.linked_chat_id != 0) {
                b.linkedChannel(botApiChannelId(full.linked_chat_id),
                        ChatObject.getPublicUsername(mc.getChat(full.linked_chat_id)));
            }
        }
        ArrayList<String> pins = new ArrayList<String>();
        if (pinned != null) {
            TreeMap<Integer, String> byId = new TreeMap<Integer, String>();
            for (MessageObject mo : pinned) {
                if (mo != null && mo.messageOwner != null && mo.messageOwner.message != null
                        && !mo.messageOwner.message.isEmpty()) {
                    byId.put(mo.getId(), mo.messageOwner.message);
                }
            }
            pins.addAll(byId.values());
        }
        b.pinnedTexts(pins);
        return b.build();
    }

    /** Cheap change detector for the pinned texts (they feed the trusted tokens of SPEC §5.1). */
    static long pinnedSignature(Collection<MessageObject> pinned) {
        long sig = 0;
        if (pinned != null) {
            for (MessageObject mo : pinned) {
                if (mo != null && mo.messageOwner != null) {
                    sig += (mo.getId() * 1000003L) ^ (mo.messageOwner.message != null ? mo.messageOwner.message.hashCode() : 0);
                }
            }
        }
        return sig;
    }

    // =====================================================================================
    // Message
    // =====================================================================================

    /** SPEC §9.1 MessageInput for one loaded message, or null when it carries nothing to observe. */
    static Item map(int account, TLRPC.Chat chat, TLRPC.ChatFull full, Roles roles, MessageObject mo) {
        TLRPC.Message m = mo.messageOwner;
        if (m == null || m.id <= 0) {
            return null;
        }
        MessagesController mc = MessagesController.getInstance(account);
        long selfId = UserConfig.getInstance(account).getClientUserId();
        LuminaAdShield.MessageInput in = new LuminaAdShield.MessageInput();
        in.id = m.id;
        in.date = m.date & 0xFFFFFFFFL;
        in.sender = sender(account, mc, chat, roles, m, selfId);
        in.text = m.message != null ? m.message : "";
        in.entities = entities(m);
        in.album = m.grouped_id;
        in.forward = forward(mc, m);
        in.buttons = buttons(m);
        in.viaBot = m.via_bot_id != 0;
        in.replyTo = replyTo(m);
        in.replyToMe = in.replyTo != 0 && mo.replyMessageObject != null && mo.replyMessageObject.messageOwner != null
                && (mo.replyMessageObject.isOutOwner()
                || mo.replyMessageObject.messageOwner.from_id instanceof TLRPC.TL_peerUser
                && mo.replyMessageObject.messageOwner.from_id.user_id == selfId);
        in.mentionsMe = m.mentioned;
        in.pinned = m.pinned;
        in.autoForward = m.fwd_from != null && m.fwd_from.saved_from_peer instanceof TLRPC.TL_peerChannel
                && full != null && full.linked_chat_id != 0 && m.fwd_from.saved_from_peer.channel_id == full.linked_chat_id;
        in.service = m instanceof TLRPC.TL_messageService;
        in.sponsored = mo.isSponsored();
        in.scheduled = false;           // sessions never exist in scheduled mode
        Item it = new Item();
        it.input = in;
        it.incoming = !m.out;
        in.media = media(m.media, it);
        return it;
    }

    /**
     * SPEC §9.1 role, first match in the order self > creator / admin / anon_admin / channel > bot > contact >
     * member. {@code out} covers my own messages sent as a channel or as an anonymous admin. Channel senders
     * (send-as, auto-forwards) carry the channel id, anonymous admins the group id.
     *
     * <p>An admin bot stays an admin, as in v2 on Android: with the per-group option off its posts keep endorsing
     * and protecting members' copies exactly as before (owner rule: everything else keeps working as before).
     * SPEC §9.1 / R3-19 (v3) ranks bot above the admin roles instead; that change waits for the owner's decision.
     */
    private static LuminaAdShield.Sender sender(int account, MessagesController mc, TLRPC.Chat chat, Roles roles,
                                                TLRPC.Message m, long selfId) {
        LuminaAdShield.Sender s = new LuminaAdShield.Sender();
        TLRPC.Peer from = m.from_id;
        if (from instanceof TLRPC.TL_peerUser) {
            long uid = from.user_id;
            TLRPC.User u = mc.getUser(uid);
            s.id = uid;
            s.username = UserObject.getPublicUsername(u);
            String adminRole = roles.roleByUser.get(uid);
            if (uid == selfId || m.out) {
                s.role = LuminaAdShield.ROLE_SELF;
            } else if (adminRole != null) {
                s.role = adminRole;     // real admins / creator only (also admin bots); a custom rank alone never counts
            } else if (u != null && u.bot) {
                s.role = LuminaAdShield.ROLE_BOT;
            } else if (u != null && (u.contact || ContactsController.getInstance(account).contactsDict.get(uid) != null)) {
                s.role = LuminaAdShield.ROLE_CONTACT;
            } else {
                s.role = LuminaAdShield.ROLE_MEMBER;
            }
        } else if (from instanceof TLRPC.TL_peerChannel) {
            long cid = from.channel_id;
            s.id = botApiChannelId(cid);
            if (cid == chat.id) {
                s.role = m.out ? LuminaAdShield.ROLE_SELF : LuminaAdShield.ROLE_ANON_ADMIN;   // sender id = the group
            } else {
                s.role = m.out ? LuminaAdShield.ROLE_SELF : LuminaAdShield.ROLE_CHANNEL;      // send-as channel
                s.username = ChatObject.getPublicUsername(mc.getChat(cid));
            }
        } else if (from instanceof TLRPC.TL_peerChat) {
            s.id = -from.chat_id;
            s.role = m.out ? LuminaAdShield.ROLE_SELF
                    : from.chat_id == chat.id ? LuminaAdShield.ROLE_ANON_ADMIN : LuminaAdShield.ROLE_CHANNEL;
        } else {
            // no author: posted as the group itself
            s.id = botApiChatId(chat);
            s.role = m.out ? LuminaAdShield.ROLE_SELF : LuminaAdShield.ROLE_ANON_ADMIN;
        }
        return s;
    }

    private static List<LuminaAdShield.Entity> entities(TLRPC.Message m) {
        ArrayList<LuminaAdShield.Entity> out = new ArrayList<LuminaAdShield.Entity>();
        if (m.entities == null) {
            return out;
        }
        for (TLRPC.MessageEntity e : m.entities) {
            if (e instanceof TLRPC.TL_messageEntityTextUrl) {
                if (e.url != null) {
                    out.add(LuminaAdShield.Entity.textUrl(e.url));
                }
            } else if (e instanceof TLRPC.TL_messageEntityMentionName) {
                out.add(LuminaAdShield.Entity.mentionName(((TLRPC.TL_messageEntityMentionName) e).user_id));
            } else if (e instanceof TLRPC.TL_inputMessageEntityMentionName) {
                TLRPC.InputUser iu = ((TLRPC.TL_inputMessageEntityMentionName) e).user_id;
                if (iu != null) {
                    out.add(LuminaAdShield.Entity.mentionName(iu.user_id));
                }
            }
        }
        return out;
    }

    private static LuminaAdShield.Forward forward(MessagesController mc, TLRPC.Message m) {
        TLRPC.MessageFwdHeader f = m.fwd_from;
        if (f == null) {
            return null;
        }
        LuminaAdShield.Forward fw = new LuminaAdShield.Forward();
        fw.date = f.date & 0xFFFFFFFFL;
        if (f.from_id instanceof TLRPC.TL_peerChannel) {
            fw.fromKind = LuminaAdShield.FWD_CHANNEL;
            fw.fromId = botApiChannelId(f.from_id.channel_id);
            fw.postId = f.channel_post;
            fw.username = ChatObject.getPublicUsername(mc.getChat(f.from_id.channel_id));
        } else if (f.from_id instanceof TLRPC.TL_peerUser) {
            fw.fromKind = LuminaAdShield.FWD_USER;
            fw.fromId = f.from_id.user_id;
            fw.username = UserObject.getPublicUsername(mc.getUser(f.from_id.user_id));
        } else {
            fw.fromKind = LuminaAdShield.FWD_HIDDEN;
            fw.fromName = f.from_name;
        }
        return fw;
    }

    private static List<String> buttons(TLRPC.Message m) {
        ArrayList<String> out = new ArrayList<String>();
        if (!(m.reply_markup instanceof TLRPC.TL_replyInlineMarkup)) {
            return out;
        }
        ArrayList<TL_keyboard.KeyboardInlineButtonRow> rows = ((TLRPC.TL_replyInlineMarkup) m.reply_markup).rows;
        if (rows == null) {
            return out;
        }
        for (TL_keyboard.KeyboardInlineButtonRow row : rows) {
            if (row == null || row.buttons == null) {
                continue;
            }
            for (TL_keyboard.KeyboardInlineButton b : row.buttons) {
                if (b != null && b.type instanceof TL_keyboard.TL_inlineButtonTypeUrl) {
                    String url = ((TL_keyboard.TL_inlineButtonTypeUrl) b.type).url;
                    if (url != null) {
                        out.add(url);
                    }
                }
            }
        }
        return out;
    }

    /** reply_to_msg_id, except the implicit "reply" every forum-topic message carries to its topic root. */
    private static int replyTo(TLRPC.Message m) {
        TLRPC.MessageReplyHeader r = m.reply_to;
        if (r == null || r.reply_to_msg_id <= 0) {
            return 0;
        }
        if (r.forum_topic && r.reply_to_top_id == 0) {
            return 0;
        }
        return r.reply_to_msg_id;
    }

    private static LuminaAdShield.Media media(TLRPC.MessageMedia md, Item it) {
        if (md == null || md instanceof TLRPC.TL_messageMediaEmpty) {
            return null;
        }
        LuminaAdShield.Media out = new LuminaAdShield.Media();
        if (md instanceof TLRPC.TL_messageMediaPhoto) {
            out.kind = "photo";
            TLRPC.Photo p = md.photo;
            if (p != null && !(p instanceof TLRPC.TL_photoEmpty) && p.id != 0) {
                out.id = Long.toString(p.id);
                photoHash(p, it);
            }
        } else if (md instanceof TLRPC.TL_messageMediaDocument) {
            TLRPC.Document d = md.document;
            if (d == null) {
                out.kind = "document";
            } else if (MessageObject.isStickerDocument(d) || MessageObject.isAnimatedStickerDocument(d, true)
                    || MessageObject.isVideoStickerDocument(d)) {
                out.kind = "sticker";
            } else if (MessageObject.isGifDocument(d)) {
                out.kind = "gif";
            } else if (MessageObject.isVoiceDocument(d)) {
                out.kind = "voice";
            } else if (MessageObject.isRoundVideoDocument(d)) {
                out.kind = "round";
            } else if (MessageObject.isVideoDocument(d)) {
                out.kind = "video";
                out.id = d.id != 0 ? Long.toString(d.id) : null;
            } else if (MessageObject.isMusicDocument(d)) {
                out.kind = "audio";
                out.id = d.id != 0 ? Long.toString(d.id) : null;
            } else {
                out.kind = "document";
                out.id = d.id != 0 ? Long.toString(d.id) : null;
            }
        } else if (md instanceof TLRPC.TL_messageMediaContact) {
            out.kind = "contact";
            out.userId = md.user_id;
            out.phone = md.phone_number;
        } else if (md instanceof TLRPC.TL_messageMediaPaidMedia) {
            out.kind = "paid";          // caption only
        } else if (md instanceof TLRPC.TL_messageMediaDice) {
            out.kind = "dice";
        } else if (md instanceof TLRPC.TL_messageMediaVenue) {
            out.kind = "venue";
        } else if (md instanceof TLRPC.TL_messageMediaGeo || md instanceof TLRPC.TL_messageMediaGeoLive) {
            out.kind = "location";
        } else if (md instanceof TLRPC.TL_messageMediaPoll) {
            out.kind = "poll";
        } else if (md instanceof TLRPC.TL_messageMediaWebPage) {
            out.kind = "webpage";
        } else if (md instanceof TLRPC.TL_messageMediaGame) {
            out.kind = "game";
        } else if (md instanceof TLRPC.TL_messageMediaStory) {
            out.kind = "story";
        } else if (md instanceof TLRPC.TL_messageMediaInvoice) {
            out.kind = "invoice";
        } else if (md instanceof TLRPC.TL_messageMediaGiveaway || md instanceof TLRPC.TL_messageMediaGiveawayResults) {
            out.kind = "giveaway";
        } else {
            return null;
        }
        return out;
    }

    private static void photoHash(TLRPC.Photo p, Item it) {
        if (p.sizes == null) {
            return;
        }
        byte[] stripped = null;
        int w = 0, h = 0;
        for (TLRPC.PhotoSize s : p.sizes) {
            if (s instanceof TLRPC.TL_photoStrippedSize) {
                stripped = s.bytes;
            } else if (s != null && s.w > 0 && s.h > 0 && (long) s.w * s.h > (long) w * h) {
                w = s.w;
                h = s.h;
            }
        }
        if (stripped != null && stripped.length >= 3) {
            it.stripped = stripped;
            it.bucket = LuminaAdShieldImages.bucket(w, h);
            it.photoId = p.id;
        }
    }
}
