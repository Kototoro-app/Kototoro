package eu.kanade.tachiyomi.novelextension.zh.kototoro_fixture_16_suspend;

import eu.kanade.tachiyomi.source.RateLimited;
import eu.kanade.tachiyomi.source.Source;
import eu.kanade.tachiyomi.source.SourceTracker;
import eu.kanade.tachiyomi.source.model.MangasPage;
import eu.kanade.tachiyomi.source.model.Page;
import eu.kanade.tachiyomi.source.model.RefreshContext;
import eu.kanade.tachiyomi.source.model.SChapter;
import eu.kanade.tachiyomi.source.model.SManga;
import kotlin.Unit;
import kotlin.coroutines.Continuation;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * T0.3 fixture — extensions-lib 1.6 "suspend novel" morphology.
 *
 * The full 1.6 NovelSourcery surface, so the host integration round can exercise every
 * novel-specific seam against a real (self-built) APK:
 * <ul>
 *   <li>{@code isNovelSource = true} + {@code supportsLatest = true};</li>
 *   <li>{@code suspend fetchPageText(Page): String} — the single-text-fetch novel body API;</li>
 *   <li>{@link SourceTracker} — chapter read/unread + favorite/unfavorite sync callbacks;</li>
 *   <li>{@link RateLimited} — minimum/recommended delay + burst permits;</li>
 *   <li>{@code getChapterList(manga, RefreshContext)} — the fork-only 1.6 delta-refresh seam,
 *       implemented to skip the (fake) network when the context already holds the chapters.</li>
 * </ul>
 */
public final class NovelSuspendV16 implements Source, SourceTracker, RateLimited {

    public static final long SOURCE_ID = 1_600_001L;

    // ------------------------------------------------------------------ Source

    @Override
    public long getId() {
        return SOURCE_ID;
    }

    @Override
    public String getName() {
        return "Kototoro Fixture Novel 1.6 (suspend)";
    }

    @Override
    public boolean isNovelSource() {
        return true;
    }

    @Override
    public boolean getSupportsLatest() {
        return true;
    }

    @Override
    public Object getPopularManga(int page, Continuation<? super MangasPage> continuation) {
        if (page > 3) {
            return new MangasPage(Collections.emptyList(), false);
        }
        SManga manga = SManga.Companion.create();
        manga.setUrl("/fixture/1.6/novel/v16/" + page);
        manga.setTitle("Kototoro Fixture Novel 1.6 #" + page);
        manga.setAuthor("Kototoro Fixture");
        manga.setStatus(SManga.ONGOING);
        manga.setInitialized(true);
        return new MangasPage(Collections.singletonList(manga), page < 3);
    }

    @Override
    public Object getMangaDetails(SManga manga, Continuation<? super SManga> continuation) {
        manga.setDescription("Fixture 1.6 novel: fetchPageText + SourceTracker + RateLimited.");
        manga.setInitialized(true);
        return manga;
    }

    @Override
    public Object getChapterList(
        SManga manga,
        Continuation<? super List<? extends SChapter>> continuation
    ) {
        return chapters(manga.getUrl());
    }

    @Override
    public Object getChapterList(
        SManga manga,
        RefreshContext context,
        Continuation<? super List<? extends SChapter>> continuation
    ) {
        // 1.6 fork-only delta-refresh: if the host already has chapters (e.g. from an earlier
        // refresh that returned UNCHANGED), a well-behaved source can short-circuit the network.
        if (context.getExistingChapters() != null
            && !context.getExistingChapters().isEmpty()
            && !context.getForceRefresh()) {
            return context.getExistingChapters();
        }
        return chapters(manga.getUrl());
    }

    @Override
    public Object getPageList(
        SChapter chapter,
        Continuation<? super List<? extends Page>> continuation
    ) {
        // A novel chapter is a single Page; the body is delivered through fetchPageText.
        return Collections.singletonList(new Page(0, chapter.getUrl(), null));
    }

    @Override
    public Object fetchPageText(Page page, Continuation<? super String> continuation) {
        String index = page.getUrl().substring(page.getUrl().lastIndexOf('/') + 1);
        return "This is the novel text body of Kototoro fixture 1.6 chapter " + index + ".\n"
            + "Paragraph two of the fixture body, proving suspend text delivery works end to end.";
    }

    // ------------------------------------------------------------------ SourceTracker

    @Override
    public boolean getSupportsChapterTracking() {
        return true;
    }

    @Override
    public boolean getSupportsFavoritesTracking() {
        return true;
    }

    @Override
    public Object onChaptersRead(
        SManga manga,
        List<? extends SChapter> changedChapters,
        List<? extends SChapter> allChapters,
        List<String> categories,
        Continuation<? super Unit> continuation
    ) {
        return Unit.INSTANCE;
    }

    @Override
    public Object onChaptersUnread(
        SManga manga,
        List<? extends SChapter> changedChapters,
        List<? extends SChapter> allChapters,
        List<String> categories,
        Continuation<? super Unit> continuation
    ) {
        return Unit.INSTANCE;
    }

    @Override
    public Object onFavorited(
        SManga manga,
        List<String> categories,
        Continuation<? super Unit> continuation
    ) {
        return Unit.INSTANCE;
    }

    @Override
    public Object onUnfavorited(
        SManga manga,
        List<String> categories,
        Continuation<? super Unit> continuation
    ) {
        return Unit.INSTANCE;
    }

    // ------------------------------------------------------------------ RateLimited

    @Override
    public long getMinimumDelayMillis() {
        return 1_000L;
    }

    @Override
    public long getRecommendedDelayMillis() {
        return 2_000L;
    }

    @Override
    public int getRecommendedPermits() {
        return 3;
    }

    // ------------------------------------------------------------------ helpers

    private static List<SChapter> chapters(String mangaUrl) {
        SChapter c1 = SChapter.Companion.create();
        c1.setUrl(mangaUrl + "/ch/1");
        c1.setName("Chapter 1 — 1.6 fixture");
        c1.setChapter_number(1f);
        c1.setDate_upload(1_700_000_001_000L);

        SChapter c2 = SChapter.Companion.create();
        c2.setUrl(mangaUrl + "/ch/2");
        c2.setName("Chapter 2 — 1.6 fixture");
        c2.setChapter_number(2f);
        c2.setDate_upload(1_700_000_002_000L);

        return Arrays.asList(c1, c2);
    }
}
