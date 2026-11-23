# Community Features

Kototoro includes a decentralized, serverless community system for work ratings, discussions, leaderboards, and personalized recommendations.

Instead of relying on a centralized proprietary server, Kototoro's community runs on **public Nostr relays** for identity and live event exchange, combined with **periodic static snapshot indexing** hosted on GitHub Pages. Personalized recommendations and privacy filters are computed entirely on-device.

---

## Key Principles & Architecture

- **Zero Dedicated Servers**: Kototoro operates without a proprietary backend server. User identities and interactions exist on open Nostr protocols.
- **Fast & Lightweight**: Static snapshots (`manifest.json`, sharded work summaries, leaderboards, and moderation lists) are generated every 6 hours by the `community-indexer` and distributed via CDN. Clients read compact static snapshots instantly and only connect to live Nostr relays for real-time comments and publishing.
- **Local Privacy**: Your reading history, bookmarks, and favorite lists are never uploaded to any server. Recommendations ("Community picks") are calculated locally by correlating your local reading taste with public work-to-work similarity matrices.
- **Opt-In & Transparent**: Community features are turned off by default. You control your cryptographic keys, what you rate, whether adult works are included, and whether your sources are named.

```
 Client ── Signed Events (Rating / Comment) ──▶ Public Nostr Relays (×4–6)
    ▲                  ▲                                │
    │                  │                                │ Periodic Fetch
    │         Real-time Subscription                    ▼
    │         (Replies / Chapter Comments)     GitHub Actions (community-indexer)
    │                                                   │
    │                                                   ▼ Static Snapshot Generation
    └────────────── Download Cached Snapshots ◀──── GitHub Pages (community-index)
```

---

## Getting Started

### 1. Enabling Community

1. Open **Settings**.
2. Navigate to **Services → Community**.
3. Toggle on **Community ratings**.

When enabling the feature for the first time, a setup prompt will guide you through creating your cryptographic identity.

### 2. Community Identity & Recovery Key

Community identity does not require an email address, phone number, or third-party account:

- A standard **secp256k1 keypair** is generated locally on your device.
- Your public key (`npub1...`) serves as your anonymous identifier across relays.
- Your private key (`nsec1...`) is your **Recovery key**.

> [!CAUTION]
> **Keep your Recovery key safe!**  
> If you uninstall the app or switch devices, you must import your `nsec1...` key to retain ownership of your existing ratings and nickname.  
> For security reasons, the recovery key is **not** included in regular Kototoro backup ZIP files. Write it down or save it in your password manager.

### 3. Setting a Nickname

1. In **Settings → Services → Community**, tap **Community nickname**.
2. Enter your desired display name and tap **Save publicly**.
3. Kototoro publishes a Nostr Kind 0 profile event to the configured relays. Your nickname will appear alongside your comments and ratings.

---

## Privacy & Content Safety

Because events published to Nostr are signed and broadcast to public relays, Kototoro implements strict privacy safeguards:

### Public and Permanent Nature of Events
- Ratings and comments are cryptographically signed by your keypair and stored across distributed public relays.
- Withdrawing a rating or deleting a comment broadcasts a deletion event (NIP-09), and Kototoro's indexer removes it from subsequent snapshots. However, third-party relays may retain historical event logs.

### Adult (NSFW) Content Controls
- **Rate adult works** (`Settings → Services → Community → Rate adult works`): **Disabled by default**.
- When disabled, Kototoro prevents accidental publishing of ratings or comments on works categorized as adult/NSFW.
- When enabled, adult ratings are published with appropriate content warning tags (`content-warning: nsfw`), and adult leaderboards are segregated from general rankings.

### Source Sharing
- **Name the source when posting** (`Settings → Services → Community → Name the source when posting`):
  - When enabled (default), ratings and comments include a reference to the public source where you accessed the work. This helps other readers find where to read or watch it.
  - **Safe boundary**: Only public, universally accessible extensions and built-in sources are ever shared. Local file imports, custom TVBox/Legado/LNReader JSON endpoints, or user-defined source URLs are **never** published.

---

## Features in Detail

### 1. Work Ratings & Bayesian Scoring

- **Rating a Work**: Open any title's details page and tap **Rate**. Choose a score from 1 to 10 (or 1 to 5 stars with half-star precision).
- **Withdrawing a Rating**: You can withdraw your rating at any time from the rating dialog.
- **Bayesian Average**: Kototoro aggregates ratings across sources and titles using Bayesian averaging:
  $$\text{Score} = \frac{v \cdot R + m \cdot C}{v + m}$$
  where $v$ is the number of ratings, $R$ is the title's average, $m$ is the confidence threshold, and $C$ is the global category mean. This prevents titles with only one 10-star rating from dominating charts.
- **Cross-Source Work Identification**: Titles are resolved using strong external IDs (such as AniList, MyAnimeList, Bangumi, Kitsu, Shikimori, MangaUpdates) and normalized title folding, ensuring ratings from different sources and languages converge on the same entry.

### 2. Comments & Discussions

- **Work Comments**: Tap the comments entry on any work's details page to open the discussion sheet. Supports top-level comments and nested threaded replies.
- **Chapter Comments**: When reading manga, novels, or comics, tap **Comments for this chapter** at the end of a chapter to view or join chapter-specific discussions.
- **Spoiler Protection**: Comments marked with the spoiler tag are blurred and hidden behind a warning. Readers can tap to reveal them.
- **Author Moderation**: Tap and hold any comment to reply, copy text, report abuse, or block the author. Blocking an author hides all their comments and ratings locally.

### 3. Community Charts & Leaderboards

Under the **Discover / Suggestions** section, Kototoro displays community leaderboards:
- **Time Windows**: View top-rated and trending works for **This week**, **This month**, or **All time**.
- **Media Segregation**: Independent rankings for Comics, Novels, Videos, and other media types.
- **Minimum Threshold**: Titles must reach a minimum threshold of verified ratings before appearing on official charts.

### 4. Community Picks (On-Device Recommendations)

- **"Because you liked [Title]"**: Kototoro analyzes works you rated favorably ($\ge 7/10$), works in your favorites, and completed reading history.
- **Local Matching**: Matches your taste profile against the offline item-item cosine similarity matrix bundled in the latest snapshot.
- **No Cloud Profiling**: The calculation happens entirely within your device's background worker. Your personal library is never transmitted to an external recommendation engine.
- **Smart Filtering**: Works already present in your library or matching your blocked tags are automatically excluded.

### 5. "Readers Here" & Other Sources

When viewing recommendations or community charts, you may wonder where to access a title:
- Titles display the number of community readers who accessed the work on specific sources (e.g., *"12 readers here"*).
- Long-pressing a work card or tapping **Other sources** displays compatible extensions or built-in sources available on your device, allowing you to begin reading immediately without manual searching.

---

## Network & Relays

Kototoro communicates with standard Nostr WebSocket relays.

### Default Relays
The app ships with tested, high-availability public relays, including:
- `wss://nos.lol`
- `wss://relay.primal.net`
- `wss://nostr.mom`
- `wss://relay.damus.io`
- `wss://relay.nostr.wirednet.jp`

### Outbox & Offline Queue
When you submit a rating or comment without an active internet connection, Kototoro queues the signed event in its local outbox table. An automatic WorkManager background worker retries publishing when network connectivity is restored.

### Resilient DNS & Restricted Networks
In environments where standard DNS or relay hostnames are subject to SNI resets or DNS poisoning, Kototoro incorporates a multi-tier DoH (DNS-over-HTTPS) resolver (`CommunityRelayDns`), querying Cloudflare, Google, and DNSPod before falling back to system DNS.

---

## Related Documents

- [Documentation Hub](./README.md)
- [Getting Started](./getting-started.md)
- [Recommendations & Discovery](./recommendations.md)
- [Reader Features](./reader-features.md)
- [Source Integrations](./source-integrations.md)
- [WebDAV Sync](./webdav-sync.md)
- [Community Architecture Plan (Nostr + Static Index)](./architecture/community-nostr-plan-2026-11.md)
