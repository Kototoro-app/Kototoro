package eu.kanade.tachiyomi.novelextension.zh.kototoro_fixture_14_single;

import eu.kanade.tachiyomi.source.Source;
import eu.kanade.tachiyomi.source.model.MangasPage;
import eu.kanade.tachiyomi.source.model.Page;
import eu.kanade.tachiyomi.source.model.SChapter;
import eu.kanade.tachiyomi.source.model.SManga;
import kotlin.coroutines.Continuation;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * T0.3 fixture — extensions-lib 1.4 "direct source" morphology.
 *
 * A real 1.4 NovelSourcery source knows only the pre-1.6 surface of the novel ABI:
 * <ul>
 *   <li>no {@link eu.kanade.tachiyomi.source.SourceTracker} (1.6 only);</li>
 *   <li>no {@link eu.kanade.tachiyomi.source.RateLimited} (1.6 only);</li>
 *   <li>no {@code fetchPageText} override — the host {@link Source} default throws
 *       {@link UnsupportedOperationException}, which is exactly what a 1.4-compiled source
 *       would exhibit (the member didn't exist yet);</li>
 *   <li>no {@code RefreshContext} chapter-list overload (1.6 only);</li>
 *   <li>{@code supportsLatest} stays at the host default {@code false}.</li>
 * </ul>
 *
 * Novelty is signalled exactly the way the 1.5+ host detects it: the {@code isNovelSource}
 * property. The fixture is compiled {@code compileOnly} against the exported host
 * {@code tachiyomi-abi.jar}; at runtime {@code eu.kanade.tachiyomi.source.*} is delegated to
 * the host classloader, so this class implements the host's {@link Source} interface directly
 * (no classloader / binary drift by construction).
 */
public final class SingleNovel implements Source {

    /** Stable unique source id for the loaded source (host checks duplicates across the package). */
    public static final long SOURCE_ID = 1_400_001L;

    @Override
    public long getId() {
        return SOURCE_ID;
    }

    @Override
    public String getName() {
        return "Kototoro Fixture Novel 1.4 (single)";
    }

    @Override
    public boolean isNovelSource() {
        return true;
    }

    @Override
    public Object getPopularManga(int page, Continuation<? super MangasPage> continuation) {
        return new MangasPage(Collections.singletonList(fixtureManga(1)), false);
    }

    @Override
    public Object getMangaDetails(SManga manga, Continuation<? super SManga> continuation) {
        SManga updated = fixtureManga(parseIndex(manga.getUrl()));
        return updated;
    }

    @Override
    public Object getChapterList(
        SManga manga,
        Continuation<? super List<? extends SChapter>> continuation
    ) {
        return chapters(manga);
    }

    @Override
    public Object getPageList(
        SChapter chapter,
        Continuation<? super List<? extends Page>> continuation
    ) {
        // Novel chapters are a single Page whose body is fetched by the host 1.5+ path
        // (this 1.4 fixture does not override fetchPageText, so reading throws by design).
        return Collections.singletonList(new Page(0, chapter.getUrl(), null));
    }

    // ------------------------------------------------------------------ data helpers

    private static SManga fixtureManga(int index) {
        SManga manga = SManga.Companion.create();
        manga.setUrl("/fixture/1.4/single/novel/" + index);
        manga.setTitle("Kototoro Fixture Novel 1.4 (single) #" + index);
        manga.setAuthor("Kototoro Fixture");
        manga.setGenre("Fixture, Test");
        manga.setStatus(SManga.ONGOING);
        manga.setInitialized(true);
        manga.setThumbnail_url("https://fixture.local/covers/" + index + ".jpg");
        return manga;
    }

    private static List<SChapter> chapters(SManga manga) {
        SChapter c1 = SChapter.Companion.create();
        c1.setUrl(manga.getUrl() + "/ch/1");
        c1.setName("Chapter 1 — fixture start");
        c1.setChapter_number(1f);
        c1.setDate_upload(1_700_000_000_000L);

        SChapter c2 = SChapter.Companion.create();
        c2.setUrl(manga.getUrl() + "/ch/2");
        c2.setName("Chapter 2 — fixture middle");
        c2.setChapter_number(2f);
        c2.setDate_upload(1_700_000_000_001L);

        return Arrays.asList(c1, c2);
    }

    private static int parseIndex(String url) {
        String last = url.substring(url.lastIndexOf('/') + 1);
        try {
            return Integer.parseInt(last);
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
