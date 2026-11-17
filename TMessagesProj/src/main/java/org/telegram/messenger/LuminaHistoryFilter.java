package org.telegram.messenger;

import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

/**
 * LuminaGram #22 paging: the keyword filter for a loaded history page.
 *
 * Removes keyword-matched messages from the page in place, with the same rule and the same result as
 * {@link LuminaConfig#filterMessages} ({@link LuminaConfig#isMessageFiltered} stays the only rule),
 * and also reports what it removed so ChatActivity can page by the unfiltered page (see
 * {@link LuminaHistoryPaging}). Returns null when nothing was removed, which is always the case with
 * the default empty keyword list; every paging hook in ChatActivity is then a no-op.
 */
public final class LuminaHistoryFilter {

    private LuminaHistoryFilter() {
    }

    public static LuminaHistoryPaging.Page filterPage(ArrayList<MessageObject> list, boolean encrypted) {
        if (list == null || list.isEmpty() || !LuminaConfig.hasMessageFilter()) {
            return null;
        }
        LuminaHistoryPaging.Page page = null;
        for (int i = list.size() - 1; i >= 0; i--) {
            final MessageObject messageObject = list.get(i);
            if (!LuminaConfig.isMessageFiltered(messageObject)) {
                continue;
            }
            if (page == null) {
                page = new LuminaHistoryPaging.Page(encrypted);
            }
            final TLRPC.Message owner = messageObject.messageOwner;
            final int id = messageObject.getId();
            page.add(id, owner != null ? owner.date : 0, owner instanceof TLRPC.TL_messageEmpty, MessageObject.isEphemeralMessageId(id));
            list.remove(i);
        }
        return page;
    }
}
