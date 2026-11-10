package demo.desktop;

import eu.kanade.tachiyomi.source.model.*;
import eu.kanade.tachiyomi.source.online.HttpSource;
import okhttp3.*;
import rx.Observable;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * The generated demo library as a Mihon source for the desktop app. Everything is read from resources packed into
 * this JAR by build.py, so nothing touches the network.
 */
public final class DemoSource extends HttpSource {
    private static final String BASE = "https://demo.invalid";

    private static final class Title {
        String name, author, status, tags, description;
        final List<String[]> chapters = new ArrayList<>(); // {folder, page count}
    }

    private final List<Title> titles = readCatalog();

    private final OkHttpClient client = getNetwork().getClient().newBuilder().cache(null)
        .addInterceptor(chain -> {
            Request request = chain.request();
            // The path is the resource name: /manga/<title>/<chapter>/<page>.png
            String resource = "/demo" + String.join("/", request.url().pathSegments().stream()
                .map(segment -> "/" + segment).toArray(String[]::new)).replace("//", "/");
            try (InputStream input = DemoSource.class.getResourceAsStream(resource)) {
                if (input == null) {
                    return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(404)
                        .message("missing").body(ResponseBody.create("", MediaType.get("text/plain"))).build();
                }
                return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("demo")
                    .body(ResponseBody.create(input.readAllBytes(), MediaType.get("image/png"))).build();
            }
        }).build();

    private static List<Title> readCatalog() {
        List<Title> result = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            DemoSource.class.getResourceAsStream("/demo/catalog.tsv"), StandardCharsets.UTF_8))) {
            Title current = null;
            for (String line; (line = reader.readLine()) != null; ) {
                String[] cells = line.split("\t", -1);
                if (cells[0].equals("M")) {
                    current = new Title(); current.name = cells[1]; current.author = cells[2];
                    current.status = cells[3]; current.tags = cells[4]; current.description = cells[5];
                    result.add(current);
                } else if (cells[0].equals("C") && current != null) {
                    current.chapters.add(new String[]{cells[1], cells[2]});
                }
            }
        } catch (IOException error) { throw new UncheckedIOException(error); }
        return result;
    }

    private static HttpUrl.Builder url() { return HttpUrl.get(BASE).newBuilder().addPathSegment("manga"); }

    private SManga manga(Title title) {
        SManga manga = new SMangaImpl();
        manga.setTitle(title.name);
        manga.setUrl("/manga/" + title.name);
        manga.setAuthor(title.author);
        manga.setDescription(title.description);
        manga.setGenre(title.tags);
        manga.setStatus(title.status.equals("FINISHED") ? SManga.COMPLETED : SManga.ONGOING);
        manga.setThumbnail_url(url().addPathSegment(title.name).addPathSegment(title.chapters.get(0)[0])
            .addPathSegment("001.png").build().toString());
        manga.setInitialized(true);
        return manga;
    }

    private Title find(String url) {
        for (Title title : titles) if (url.startsWith("/manga/" + title.name)) return title;
        throw new NoSuchElementException(url);
    }

    public long getId() { return 9007199254740881L; }
    public String getName() { return "Demo Library"; }
    public String getLang() { return "en"; }
    public boolean getSupportsLatest() { return false; }
    public String getBaseUrl() { return BASE; }
    @Override public OkHttpClient getClient() { return client; }

    @Override public Observable<MangasPage> fetchPopularManga(int page) {
        List<SManga> mangas = new ArrayList<>();
        if (page == 1) for (Title title : titles) mangas.add(manga(title));
        return Observable.just(new MangasPage(mangas, false));
    }
    @Override public Observable<MangasPage> fetchSearchManga(int page, String query, FilterList filters) {
        List<SManga> mangas = new ArrayList<>();
        if (page == 1) for (Title title : titles) {
            if (title.name.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) mangas.add(manga(title));
        }
        return Observable.just(new MangasPage(mangas, false));
    }
    @Override public Observable<SManga> fetchMangaDetails(SManga original) {
        return Observable.just(manga(find(original.getUrl())));
    }
    @Override public Observable<List<SChapter>> fetchChapterList(SManga original) {
        Title title = find(original.getUrl());
        List<SChapter> chapters = new ArrayList<>();
        for (int index = title.chapters.size() - 1; index >= 0; index--) {
            SChapter chapter = new SChapterImpl();
            chapter.setName(title.chapters.get(index)[0]);
            chapter.setUrl("/manga/" + title.name + "/" + title.chapters.get(index)[0]);
            chapter.setChapter_number(index + 1);
            chapters.add(chapter);
        }
        return Observable.just(chapters);
    }
    @Override public Observable<List<Page>> fetchPageList(SChapter chapter) {
        String[] parts = chapter.getUrl().split("/"); // "", manga, title, chapter
        Title title = find(chapter.getUrl());
        int count = 0;
        for (String[] entry : title.chapters) if (entry[0].equals(parts[3])) count = Integer.parseInt(entry[1]);
        List<Page> pages = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            String image = url().addPathSegment(parts[2]).addPathSegment(parts[3])
                .addPathSegment(String.format(Locale.ROOT, "%03d.png", index + 1)).build().toString();
            pages.add(new Page(index, image, image, null));
        }
        return Observable.just(pages);
    }
}
