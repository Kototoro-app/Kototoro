package eu.kanade.tachiyomi.novelextension.zh.kototoro_fixture_14_factory;

import eu.kanade.tachiyomi.source.Source;
import eu.kanade.tachiyomi.source.model.MangasPage;
import eu.kanade.tachiyomi.source.model.Page;
import eu.kanade.tachiyomi.source.model.SChapter;
import eu.kanade.tachiyomi.source.model.SManga;
import kotlin.coroutines.Continuation;

import java.util.Collections;
import java.util.List;

/**
 * The legal half of the 1.4 factory fixture: a real novel source produced by
 * {@link FactoryNovel#createSources()}.
 */
public final class FactoryNovelSource implements Source {

    public static final long SOURCE_ID = 1_400_101L;

    @Override
    public long getId() {
        return SOURCE_ID;
    }

    @Override
    public String getName() {
        return "Kototoro Fixture Factory Novel 1.4";
    }

    @Override
    public boolean isNovelSource() {
        return true;
    }

    @Override
    public Object getPopularManga(int page, Continuation<? super MangasPage> continuation) {
        SManga manga = SManga.Companion.create();
        manga.setUrl("/fixture/1.4/factory/novel/1");
        manga.setTitle("Kototoro Fixture Factory Novel #1");
        manga.setStatus(SManga.ONGOING);
        manga.setInitialized(true);
        return new MangasPage(Collections.singletonList(manga), false);
    }

    @Override
    public Object getChapterList(
        SManga manga,
        Continuation<? super List<? extends SChapter>> continuation
    ) {
        SChapter chapter = SChapter.Companion.create();
        chapter.setUrl(manga.getUrl() + "/ch/1");
        chapter.setName("Chapter 1 — factory novel");
        chapter.setChapter_number(1f);
        return Collections.singletonList(chapter);
    }

    @Override
    public Object getPageList(
        SChapter chapter,
        Continuation<? super List<? extends Page>> continuation
    ) {
        return Collections.singletonList(new Page(0, chapter.getUrl(), null));
    }
}
