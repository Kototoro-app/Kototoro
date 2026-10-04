package fixture.desktop;

import eu.kanade.tachiyomi.source.model.*;
import eu.kanade.tachiyomi.source.online.HttpSource;
import okhttp3.*;
import okio.*;
import rx.Observable;
import java.util.*;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;

/** Independently authored covers: custom requests, chapter-only fallback, decrypted client bodies and cancellation. */
public final class CoverSource extends HttpSource {
    private final OkHttpClient client = getNetwork().getClient().newBuilder().cache(null)
        .addInterceptor(chain -> {
            try (Response encrypted = chain.proceed(chain.request())) {
                byte[] bytes = encrypted.body().bytes();
                for (int i = 0; i < bytes.length; i++) bytes[i] ^= 0x5a;
                return encrypted.newBuilder().body(ResponseBody.create(bytes, MediaType.get("image/png"))).build();
            }
        })
        .addInterceptor(chain -> {
            Request request = chain.request(); String path = request.url().encodedPath();
            if (System.getProperty("fixture.cover.offline") != null) throw new java.io.IOException("Fixture is offline");
            String counter = "fixture.cover.requests." + path;
            int count = Integer.parseInt(System.getProperty(counter, "0"));
            System.setProperty(counter, Integer.toString(count + 1));
            if (path.equals("/fallback.png")) {
                if (!"default".equals(request.header("X-Cover-Default"))) throw new java.io.IOException("Fallback headers lost");
            } else if (!"custom".equals(request.header("X-Cover-Request"))) {
                throw new java.io.IOException("Extension cover request lost");
            }
            if (path.equals("/slow.png")) {
                System.setProperty("fixture.cover.slow.entered", "yes");
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
                while (!"yes".equals(System.getProperty("fixture.cover.slow.release"))) {
                    if (System.nanoTime() >= deadline) throw new java.io.IOException("Fixture release timed out");
                    try { Thread.sleep(20); } catch (InterruptedException error) { throw new java.io.IOException(error); }
                }
            }
            byte[] bytes;
            if (path.equals("/broken.png") && System.getProperty("fixture.cover.repaired") == null) {
                bytes = "<html>authored broken image</html>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            } else {
                BufferedImage image = new BufferedImage(300, 420, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = image.createGraphics();
                try {
                    graphics.setColor(path.contains("fallback") ? new Color(0x3677B5) : new Color(0x278D6C));
                    graphics.fillRect(0, 0, 300, 420); graphics.setColor(Color.WHITE);
                    graphics.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 36));
                    graphics.drawString(path.contains("fallback") ? "FALLBACK" : "COVER", 45, 220);
                } finally { graphics.dispose(); }
                ByteArrayOutputStream output = new ByteArrayOutputStream(); ImageIO.write(image, "png", output);
                bytes = output.toByteArray();
            }
            for (int i = 0; i < bytes.length; i++) bytes[i] ^= 0x5a;
            BufferedSource source = Okio.buffer(new ForwardingSource(new Buffer().write(bytes)) {
                @Override public void close() throws java.io.IOException {
                    System.setProperty("fixture.cover.closed." + path, "yes"); super.close();
                }
            });
            final int length = bytes.length;
            ResponseBody body = new ResponseBody() {
                public MediaType contentType() { return MediaType.get("application/octet-stream"); }
                public long contentLength() { return length; }
                public BufferedSource source() { return source; }
            };
            return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                .message("authored cover").body(body).build();
        }).build();
    public long getId() { return 9007199254740996L; }
    public String getName() { return "封面测试来源"; }
    public String getLang() { return "zh"; }
    public boolean getSupportsLatest() { return false; }
    public String getBaseUrl() { return "https://fixture.invalid"; }
    @Override public OkHttpClient getClient() { return client; }
    @Override protected Headers.Builder headersBuilder() {
        return super.headersBuilder().add("X-Cover-Default", "default");
    }
    @Override protected Request imageRequest(Page page) {
        if (page.getImageUrl().contains("fallback")) throw new IllegalArgumentException("Chapter context is required");
        return new Request.Builder().url(page.getImageUrl()).header("X-Cover-Request", "custom").build();
    }
    @Override public Observable<MangasPage> fetchPopularManga(int page) {
        List<SManga> mangas = new ArrayList<>();
        for (String name : new String[]{"primary", "fallback", "broken"}) {
            SManga manga = new SMangaImpl(); manga.setTitle(name + " cover"); manga.setUrl("/manga/" + name);
            manga.setThumbnail_url(getBaseUrl() + "/" + name + ".png#cover-fragment"); mangas.add(manga);
        }
        return Observable.just(new MangasPage(mangas, false));
    }
    @Override public Observable<SManga> fetchMangaDetails(SManga original) {
        return Observable.just(original);
    }
    @Override public Observable<List<SChapter>> fetchChapterList(SManga original) {
        SChapter chapter = new SChapterImpl(); chapter.setUrl("/chapter"); chapter.setName("Chapter");
        chapter.setChapter_number(1); return Observable.just(Collections.singletonList(chapter));
    }
    @Override public Observable<List<Page>> fetchPageList(SChapter chapter) {
        return Observable.just(Collections.singletonList(new Page(12, getBaseUrl(), getBaseUrl() + "/page.png", null)));
    }
}
