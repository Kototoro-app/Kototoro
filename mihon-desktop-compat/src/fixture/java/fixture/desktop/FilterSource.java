package fixture.desktop;

import eu.kanade.tachiyomi.source.model.*;
import eu.kanade.tachiyomi.source.online.HttpSource;
import rx.Observable;
import java.util.*;

/** Own cached native controls: no HTTP, DNS, sockets or representative-site assumptions. */
public final class FilterSource extends HttpSource {
    public static final class Flag extends Filter.CheckBox { public Flag() { super("已完结", true); } }
    public static final class Genre extends Filter.TriState { public Genre() { super("冒险", STATE_INCLUDE); } }
    public static final class Choice {
        final String key;
        Choice(String key) { this.key = key; }
        @Override public String toString() { return "同名选项"; }
    }
    public static final class Edition extends Filter.Select<Choice> {
        public Edition() { super("版本", new Choice[]{new Choice("original"), new Choice("alternate")}, 0); }
    }
    public static final class Author extends Filter.Text { public Author() { super("作者", "默认作者"); } }
    public static final class Order extends Filter.Sort {
        public Order() { super("排序", new String[]{"名称", "更新"}, null); }
    }
    public static final class Nested extends Filter.Group<Filter<?>> {
        public Nested(List<Filter<?>> children) { super("嵌套条件", children); }
    }
    public static final class Group extends Filter.Group<Filter<?>> {
        public Group(List<Filter<?>> children) { super("作品条件", children); }
    }
    private final Flag flag = new Flag();
    private final Genre genre = new Genre();
    private final Edition edition = new Edition();
    private final Author author = new Author();
    private final Order order = new Order();
    private final Filter<String> opaque = new Filter<>("自定义控件", "opaque");
    private final FilterList controls = new FilterList(new Filter.Header("原生筛选"), flag,
        new Group(Arrays.asList(genre, edition, new Nested(Collections.singletonList(author)))),
        new Filter.Separator(), order, opaque);
    public long getId() { return 9007199254740997L; }
    public String getName() { return "筛选测试来源"; }
    public String getLang() { return "zh"; }
    public boolean getSupportsLatest() { return true; }
    public String getBaseUrl() { return "https://fixture.invalid"; }
    @Override public FilterList getFilterList() { return controls; }
    private void defaults() {
        if (!flag.getState() || genre.getState() != 1 || edition.getState() != 0 || order.getState() != null
                || !"默认作者".equals(author.getState()) || !"opaque".equals(opaque.getState())) {
            throw new IllegalStateException("Cached native controls were not restored");
        }
    }
    private Observable<MangasPage> result(String title) {
        SManga manga = new SMangaImpl(); manga.setTitle(title); manga.setUrl("/filtered");
        return Observable.just(new MangasPage(Collections.singletonList(manga), true));
    }
    @Override public Observable<MangasPage> fetchPopularManga(int page) {
        defaults(); System.setProperty("fixture.filters.last", "popular:" + page);
        return result("热门 " + page);
    }
    @Override public Observable<MangasPage> fetchLatestUpdates(int page) {
        defaults(); System.setProperty("fixture.filters.last", "latest:" + page);
        // A slow site: the window must stay usable and a newer navigation must replace this request.
        if (Boolean.getBoolean("fixture.filters.slow")) {
            return result("最新 " + page).delay(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        return result("最新 " + page);
    }
    @Override public Observable<MangasPage> fetchSearchManga(int page, String query, FilterList filters) {
        if (filters != controls || !(filters.get(1) instanceof Flag) || !"opaque".equals(opaque.getState())) {
            throw new IllegalStateException("Native filter identity was lost");
        }
        String sort = order.getState() == null ? "null" : order.getState().getIndex() + ":" + order.getState().getAscending();
        String received = page + "|" + query + "|" + flag.getState() + "|" + genre.getState() + "|"
            + edition.getValues()[edition.getState()].key + "|" + author.getState() + "|" + sort;
        System.setProperty("fixture.filters.last", received);
        if (Boolean.getBoolean("fixture.filters.fail")) return Observable.error(new java.io.IOException("Fixture filter failure"));
        return result("筛选结果 " + page + " " + query);
    }
    @Override public Observable<SManga> fetchMangaDetails(SManga manga) { return Observable.just(manga); }
    @Override public Observable<List<SChapter>> fetchChapterList(SManga manga) { return Observable.just(Collections.emptyList()); }
    @Override public Observable<List<Page>> fetchPageList(SChapter chapter) { return Observable.just(Collections.emptyList()); }
}
