# Recommendations & Discovery

Kototoro features a hybrid, privacy-preserving recommendation engine designed to help readers discover new manga, novels, and anime across installed sources without sending personal browsing habits to external servers.

The recommendation system combines **local content-based taste profiling**, **source catalog exploration**, and **decentralized community collaborative filtering**.

---

## How Recommendations Work

Recommendations appear on the **Discover / Suggestions** screen. Rather than presenting a single undifferentiated list, Kototoro categorizes picks into five distinct **directions**:

```
 ┌─────────────────────────────────────────────────────────────┐
 │                    Reader's Local Library                   │
 │       (Favorites, Reading Progress, Recency, Ratings)       │
 └──────────────┬──────────────────────────────┬───────────────┘
                │                              │
       Taste Profiling                  Seed Selection
       (Tags + TF-IDF)                  (High-rated & Favorites)
                │                              │
                ▼                              ▼
 ┌─────────────────────────────┐┌──────────────────────────────┐
 │    Source-Based Engine      ││     Community Engine         │
 │  • Taste (Favorite genres)  ││  • Community Picks           │
 │  • Popular (Trending)       ││    ("Because you liked...")  │
 │  • New (Freshly updated)    ││  • Quality Score Modulation  │
 │  • Explore (Untried themes) ││    (Rating boosts / damping) │
 └──────────────┬──────────────┘└──────────────┬───────────────┘
                │                              │
                └──────────────┬───────────────┘
                               ▼
 ┌─────────────────────────────────────────────────────────────┐
 │       On-Device Diversity, Filtering, and Deduplication     │
 │    (Language, Tag Blacklist, NSFW, Exclude Owned Library)    │
 └─────────────────────────────┬───────────────────────────────┘
                               ▼
                       Suggestions Feed
```

### The 5 Recommendation Directions

| Direction | Badge | How It Works |
| :--- | :--- | :--- |
| **Taste** | `TASTE` | Analyzes tags in your library weighted by reading progress, completion, and recency. Queries your active sources for popular works matching your core genres. |
| **Popular** | `POPULAR` | Highlights broadly popular and highly regarded titles on your installed sources regardless of genre. |
| **New** | `NEW` | Discovers newly published or freshly updated releases from active sources. |
| **Explore** | `EXPLORE` | Proactively introduces titles from genres or themes you haven't explored yet, preventing filter bubbles and monotony. |
| **Community** | `COMMUNITY` | Suggests titles derived from decentralized Nostr ratings and item-item similarity matrices ("Because you liked [Title]"). |

---

## Intersection with Community Features

The recommendation system directly interfaces with Kototoro's decentralized [Community Features](./community.md) in two primary ways:

### 1. "Community Picks" (Collaborative Filtering)

When Community features are enabled in `Settings → Services → Community`:
- Kototoro identifies "seed works" from your library: titles you rated $\ge 7/10$, marked as favorites, or completed reading.
- It maps these seeds to the **item-item cosine similarity matrix** downloaded in the latest static community snapshot (`v1/works/*.json`).
- If other readers who loved your favorite titles also enjoyed title $X$, title $X$ is queued as a candidate with a visible reason: **"Because you liked [Title]"**.
- Kototoro automatically checks your installed extensions and sources to resolve an available, readable version of the work.

### 2. Community Quality Modulation (`SuggestionQuality`)

Even when generating ordinary source-based suggestions (Taste, Popular, or New), Kototoro consults the community snapshot:
- **High-confidence ratings ($W \ge 5.0$)**: If the community has verified ratings for a candidate work, the Bayesian average modulates its final score.
- **Quality Boost ($\text{Score} > 7.0$)**: Well-received titles receive a ranking multiplier (up to $1.6\times$), pushing them above generic popular lists.
- **Suppression of Low-Quality Works ($\text{Score} < 6.0$)**: If community consensus flags a work as poor ($<6.0$), its score is heavily damped ($0.4\times$), filtering out clickbait or low-quality source spam.

### 3. Similar Works on Details Page
On any title's details screen, the **"Readers who liked this also liked"** carousel shows titles with the highest co-occurrence and rating correlation from the community index, showing the number of shared readers or matching tags.

---

## Local Privacy Guarantee

Kototoro's recommendation architecture is **100% on-device**:

- **No Remote Tracking**: Your reading history, bookmarks, ratings, and tag preferences never leave your phone or computer.
- **Offline Calculation**: Tag weights, frequency analysis (TF-IDF `TagRarity`), and candidate rankings are processed within local background workers (`SuggestionsWorker` on Android, `SourceSuggestions` on Desktop/iOS).
- **Public Snapshot Indexing**: For community recommendations, your device downloads the public static snapshot index; your local seed matching happens purely on the client.

---

## Customizing & Tuning Recommendations

You can customize recommendations in **Settings → Services → Recommendations**:

### 1. Source Preferences
- **Preferred Sources**: Select sources you trust most. Kototoro prioritizes candidates from these sources.
- **Excluded Sources**: Exclude specific sources from contributing to the suggestions feed.

### 2. Tag Blacklists and Whitelists
- **Tag Blacklist**: Completely hide works carrying specific genre or content tags (e.g., Gore, Horror, Harem).
- **Tag Whitelist**: Pin specific tags so the recommendation engine prioritizes those genres during taste profiling.
- **Global Tag Blacklist**: Respects global content filtering defined in `Settings → Browse`.

### 3. Language & Regional Settings
- **Filter by Language**: Restrict recommendations to sources matching your system language or preferred reading languages.

### 4. Content Rating & Adult Works
- **Exclude Adult / NSFW**: When enabled, adult works and Hentai/18+ sources are excluded from all recommendation directions.
- Also respects the global `Rate adult works` community toggle.

### 5. Refreshing & Shuffling
- Recommendations rotate daily using a deterministic date-based seed so the feed remains consistent throughout the day.
- Tap **Refresh** ("换一批") at the top of the Suggestions page to shuffle the seed and pull a fresh batch of candidates immediately.

---

## Related Documents

- [Community Features](./community.md)
- [Reader Features](./reader-features.md)
- [Source Integrations](./source-integrations.md)
- [Getting Started](./getting-started.md)
