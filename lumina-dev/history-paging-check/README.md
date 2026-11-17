# Keyword-filter paging checker (Android / Java)

Dev-only checks for `LuminaHistoryPaging` (LuminaGram #22 paging): the bookkeeping that lets
`ChatActivity` page through history when the message keyword filter hides messages.
Nothing in `lumina-dev/` is part of any Gradle source set, so the app build never compiles it.

```sh
sh lumina-dev/history-paging-check/run.sh      # JDK >= 9 (compiles with javac --release 8)
```

| Section | What |
|---|---|
| page | removed count/ids, older/newer cursor and date bounds exactly like the cursor loop in `didReceivedNotification_messagesDidLoad` (empty messages, ephemeral ids, zero dates, secret-chat ids) |
| older / newer cursor | the older cursor is only used while it still holds the value the filter wrote; the newer cursor never records while `minMessageId` is unset and drops out as soon as `minMessageId` changes |
| throttle / retry slot | spacing starts after 5 consecutive fully hidden server pages, resets on new rows and on a chat reset (`clearChatData`, topic / saved-chat switch); one pending retry, earlier wins, a stale slot reposts |
| model: older | open at the bottom and scroll to the top over a fake history (every 10th hidden, a 130-message hidden run, the newest 40 hidden, nothing hidden): the fixed paging reaches every visible message with strictly decreasing offsets, the unfixed paging stops early, and with nothing hidden both send the identical request sequence |
| model: newer | after a jump, scroll down across hidden runs: no gap, offsets strictly increase, `minMessageId` (read receipts) only ever holds a visible id |
| model: throttle | 40 fully hidden pages answered instantly: the first 5 go out back to back, then at least 1 s apart |

The models mirror ChatActivity's decisions, not ChatActivity itself; device testing still covers the UI.
