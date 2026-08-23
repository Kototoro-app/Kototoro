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
 * The deliberately-unsuitable half of the 1.4 factory fixture: a <b>manga</b> object shipped
 * inside a novel package. {@code isNovelSource} stays {@code false}, so the host per-source
 * validation rejects it (plan §2A: "漫画对象……产生正确结构化错误") while keeping the sibling
 * {@link FactoryNovelSource}. This is what lets the integration tests prove the host's
 * "reject the manga object, keep the legal novel source" isolation behaviour.
 */
public final class FactoryMangaSource implements Source {

    public static final long SOURCE_ID = 1_400_102L;

    @Override
    public long getId() {
        return SOURCE_ID;
    }

    @Override
    public String getName() {
        return "Kototoro Fixture Manga (must be rejected)";
    }

    // Deliberately NOT a novel: this is the manga contract, and the field stays at the host
    // default false (we don't override isNovelSource at all).

    @Override
    public Object getPopularManga(int page, Continuation<? super MangasPage> continuation) {
        SManga manga = SManga.Companion.create();
        manga.setUrl("/fixture/1.4/factory/manga/1");
        manga.setTitle("Kototoro Fixture Manga (rejected)");
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
        chapter.setName("Chapter 1 — manga");
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
