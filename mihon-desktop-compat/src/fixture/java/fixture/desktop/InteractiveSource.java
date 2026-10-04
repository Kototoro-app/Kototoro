package fixture.desktop;

import eu.kanade.tachiyomi.network.OkHttpExtensionsKt;
import eu.kanade.tachiyomi.source.model.*;
import eu.kanade.tachiyomi.source.online.HttpSource;
import okhttp3.*;
import rx.Observable;
import java.util.*;

/** Authored loopback fixture using the real SDK client and cancellable Rx HTTP adapter. */
public final class InteractiveSource extends HttpSource {
    public static final class Criterion extends Filter.Text { public Criterion() { super("验证条件", ""); } }
    private final FilterList filters = new FilterList(new Criterion());
    public long getId() { return 9007199254740994L; }
    public String getName() { return "交互测试来源"; }
    public String getLang() { return "zh"; }
    public boolean getSupportsLatest() { return false; }
    public String getBaseUrl() { return System.getProperty("fixture.interaction.url"); }
    private Observable<MangasPage> fetch(String mode) {
        Request request = new Request.Builder().url(getBaseUrl() + "/popular?mode=" + mode)
            .header("X-Source-Fixture", "interactive").build();
        return OkHttpExtensionsKt.asObservable(getClient().newCall(request)).doOnError(Throwable::printStackTrace).map(response -> {
            try (Response owned = response) {
                if (!owned.isSuccessful()) throw new IllegalStateException("HTTP " + owned.code());
                SManga manga = new SMangaImpl(); manga.setTitle(owned.body().string()); manga.setUrl("/manga");
                return new MangasPage(Collections.singletonList(manga), false);
            } catch (java.io.IOException error) { throw rx.exceptions.Exceptions.propagate(error); }
        });
    }
    @Override public Observable<MangasPage> fetchPopularManga(int page) { return fetch("success"); }
    @Override public FilterList getFilterList() { return filters; }
    @Override public Observable<MangasPage> fetchSearchManga(int page, String query, FilterList filters) {
        System.setProperty("fixture.interaction.filter", ((Filter.Text) filters.get(0)).getState());
        return fetch(query);
    }
    @Override public Observable<SManga> fetchMangaDetails(SManga manga) { return Observable.just(manga); }
    @Override public Observable<List<SChapter>> fetchChapterList(SManga manga) { return Observable.just(Collections.emptyList()); }
    @Override public Observable<List<Page>> fetchPageList(SChapter chapter) { return Observable.just(Collections.emptyList()); }
}
