package fixture.desktop;

import eu.kanade.tachiyomi.source.model.*;
import eu.kanade.tachiyomi.source.online.HttpSource;
import okhttp3.*;
import rx.Observable;
import java.util.*;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;

/** Authored colored page fixture; terminal image responses do not access DNS or sockets. */
public final class ReaderSource extends HttpSource {
    private final OkHttpClient client = getNetwork().getClient().newBuilder().cache(null)
        .addInterceptor(chain -> {
            Request request = chain.request();
            int index = Integer.parseInt(request.header("X-Page-Index"));
            if (index < 12 || index > 16 || !request.header("Referer").contains("origin-" + index)) {
                throw new java.io.IOException("Original page context was lost");
            }
            int count = Integer.parseInt(System.getProperty("fixture.reader.requests", "0"));
            System.setProperty("fixture.reader.requests", Integer.toString(count + 1));
            if (Integer.toString(index).equals(System.getProperty("fixture.reader.delay.index"))) {
                System.setProperty("fixture.reader.waiting", "true");
                try {
                    while (Integer.toString(index).equals(System.getProperty("fixture.reader.delay.index"))) {
                        if (chain.call().isCanceled()) throw new java.io.IOException("Authored reader canceled");
                        try { Thread.sleep(10); }
                        catch (InterruptedException error) {
                            Thread.currentThread().interrupt(); throw new java.io.IOException(error);
                        }
                    }
                } finally { System.clearProperty("fixture.reader.waiting"); }
            }
            if (Boolean.getBoolean("fixture.reader.offline")) {
                throw new java.io.IOException("Authored offline reader");
            }
            if (Integer.toString(index).equals(System.getProperty("fixture.reader.failure.index"))) {
                return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(503)
                    .message("authored image failure").body(ResponseBody.create("fixture", MediaType.get("text/plain"))).build();
            }
            int position = index - 12;
            String supplied = System.getProperty("fixture.reader.image.path");
            if (position == 0 && supplied != null) {
                return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                    .message("authored supplied image").body(ResponseBody.create(
                        java.nio.file.Files.readAllBytes(java.nio.file.Path.of(supplied)),
                        MediaType.get("image/webp"))).build();
            }
            int width = position == 2 ? 900 : 400;
            boolean tall = position == 0 && Boolean.getBoolean("fixture.reader.tall");
            int height = tall ? 24000 : position == 2 ? 300 : 600;
            Color[] colors = {new Color(0xD84949), new Color(0x3575B5), new Color(0xDAAE30),
                new Color(0x339467), new Color(0x8950A8)};
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            try {
                graphics.setColor(colors[position]); graphics.fillRect(0, 0, width, height);
                if (tall) {
                    graphics.setColor(colors[1]); graphics.fillRect(0, 8000, width, 8000);
                    graphics.setColor(colors[3]); graphics.fillRect(0, 16000, width, 8000);
                }
                graphics.setColor(Color.WHITE); graphics.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 64));
                graphics.drawString(Integer.toString(position + 1), width / 2 - 20, height / 2);
            } finally { graphics.dispose(); }
            ByteArrayOutputStream output = new ByteArrayOutputStream(); ImageIO.write(image, "png", output);
            return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                .message("authored image").body(ResponseBody.create(output.toByteArray(), MediaType.get("image/png"))).build();
        }).build();
    public long getId() { return 9007199254740995L; }
    public String getName() { return "阅读测试来源"; }
    public String getLang() { return "zh"; }
    public boolean getSupportsLatest() { return false; }
    public String getBaseUrl() { return "https://fixture.invalid"; }
    @Override public OkHttpClient getClient() { return client; }
    @Override public Observable<MangasPage> fetchPopularManga(int page) {
        SManga manga = new SMangaImpl(); manga.setTitle("Reader fixture"); manga.setUrl("/reader");
        return Observable.just(new MangasPage(Collections.singletonList(manga), false));
    }
    @Override public Observable<SManga> fetchMangaDetails(SManga original) { return Observable.just(original); }
    @Override public Observable<List<SChapter>> fetchChapterList(SManga original) {
        List<SChapter> chapters = new ArrayList<>();
        if (Boolean.getBoolean("fixture.reader.branches")) {
            SChapter alternate = new SChapterImpl(); alternate.setName("Other branch");
            alternate.setUrl("/reader/other-branch"); alternate.setChapter_number(3);
            alternate.setScanlator("other branch"); chapters.add(alternate);
        }
        for (int number = 2; number >= 1; number--) {
            SChapter chapter = new SChapterImpl(); chapter.setName("Chapter " + number);
            chapter.setUrl("/reader/chapter-" + number); chapter.setChapter_number(number); chapters.add(chapter);
        }
        return Observable.just(chapters);
    }
    @Override public Observable<List<Page>> fetchPageList(SChapter chapter) {
        int calls = Integer.parseInt(System.getProperty("fixture.reader.page_lists", "0"));
        System.setProperty("fixture.reader.page_lists", Integer.toString(calls + 1));
        if (Boolean.getBoolean("fixture.reader.pages.offline")) {
            return Observable.error(new java.io.IOException("Authored offline page list"));
        }
        if (Integer.toString((int)chapter.getChapter_number()).equals(System.getProperty("fixture.reader.failure.chapter"))) {
            return Observable.error(new java.io.IOException("Authored chapter failure"));
        }
        List<Page> pages = new ArrayList<>();
        int count = chapter.getChapter_number() == 1 ? 5 : 2;
        for (int index = 12; index < 12 + count; index++) {
            pages.add(new Page(index, getBaseUrl() + "/origin-" + index,
                getBaseUrl() + "/image-" + index + ".png#original", null));
        }
        return Observable.just(pages);
    }
    @Override protected Request imageRequest(Page page) {
        return new Request.Builder().url(page.getImageUrl()).header("X-Page-Index", Integer.toString(page.getIndex()))
            .header("Referer", page.getUrl()).build();
    }
}
