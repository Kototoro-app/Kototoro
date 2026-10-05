package fixture.desktop;

import androidx.preference.ListPreference;
import androidx.preference.PreferenceScreen;
import eu.kanade.tachiyomi.source.ConfigurableSource;
import eu.kanade.tachiyomi.source.model.*;
import eu.kanade.tachiyomi.source.online.HttpSource;
import okhttp3.*;
import rx.Observable;
import kotlinx.serialization.json.Json;
import kotlinx.serialization.json.JsonElement;
import kotlinx.serialization.json.JsonObject;
import kotlinx.serialization.json.JsonPrimitive;
import kotlinx.serialization.json.okio.OkioStreamsKt;
import okio.Buffer;
import java.util.*;

/** Independently authored fixture: actual APIs/client/interceptors, terminal responses never access DNS or sockets. */
public final class OfflineSource extends HttpSource implements ConfigurableSource {
    private final String domain = getSourcePreferences().getString("domain", "initial");
    private final OkHttpClient client = getNetwork().getClient().newBuilder().cache(null)
        .addInterceptor(chain -> {
            try (Response encrypted = chain.proceed(chain.request())) {
                byte[] bytes = encrypted.body().bytes();
                for (int index = 0; index < bytes.length; index++) bytes[index] ^= 0x5a;
                return encrypted.newBuilder().body(ResponseBody.create(bytes, MediaType.get("image/png"))).build();
            }
        })
        .addInterceptor(chain -> {
            if (!"12".equals(chain.request().header("X-Page-Index"))) throw new java.io.IOException("page index lost");
            byte[] png = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aXuoAAAAASUVORK5CYII=");
            for (int index = 0; index < png.length; index++) png[index] ^= 0x5a;
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("offline").body(ResponseBody.create(png, MediaType.get("application/octet-stream"))).build();
        }).build();
    public long getId() { return 9007199254740993L; }
    public String getName() { return domain; }
    public String getLang() { return "zh"; }
    public boolean getSupportsLatest() { return false; }
    public String getBaseUrl() { return "https://fixture.invalid"; }
    @Override public OkHttpClient getClient() { return client; }
    @Override public Observable<MangasPage> fetchPopularManga(int page) {
        if (getNetwork().getClient().interceptors().stream().noneMatch(interceptor ->
            interceptor instanceof eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor)) {
            throw new IllegalStateException("CloudflareInterceptor must be present in default client");
        }
        // Exercise the streaming JSON ABI used by real extensions, including inside the packaged JVM.
        JsonElement data = OkioStreamsKt.decodeFromBufferedSource(Json.Default, JsonElement.Companion.serializer(),
            new Buffer().writeUtf8("{\"title\":\"offline " + page + "\"}"));
        String title = ((JsonPrimitive) ((JsonObject) data).get("title")).getContent();
        SManga manga = new SMangaImpl(); manga.setTitle(title); manga.setUrl("/manga");
        return Observable.just(new MangasPage(Collections.singletonList(manga), false));
    }
    @Override public Observable<MangasPage> fetchSearchManga(int page, String query, FilterList filters) {
        SManga manga = new SMangaImpl(); manga.setTitle("search " + query); manga.setUrl("/manga");
        return Observable.just(new MangasPage(Collections.singletonList(manga), false));
    }
    @Override public Observable<SManga> fetchMangaDetails(SManga original) { return Observable.just(original); }
    @Override public Observable<List<SChapter>> fetchChapterList(SManga original) {
        SChapter chapter = new SChapterImpl(); chapter.setName("Chapter"); chapter.setUrl("/chapter");
        chapter.setChapter_number(1); return Observable.just(Collections.singletonList(chapter));
    }
    @Override public Observable<List<Page>> fetchPageList(SChapter chapter) {
        return Observable.just(Collections.singletonList(new Page(12, "https://fixture.invalid/origin",
            "https://fixture.invalid/image.png#opaque", null)));
    }
    @Override protected Request imageRequest(Page page) {
        // Like Mihon's default image request, start from the source's default headers.
        return new Request.Builder().url(page.getImageUrl()).headers(getHeaders())
            .header("X-Page-Index", Integer.toString(page.getIndex()))
            .header("Referer", page.getUrl()).build();
    }
    public void setupPreferenceScreen(PreferenceScreen screen) {
        ListPreference preference = new ListPreference(screen.getContext()); preference.setKey("domain");
        preference.setTitle("域名设置");
        preference.setDefaultValue("initial"); preference.setEntries(new String[]{"Initial", "Saved", "Rejected"});
        preference.setEntryValues(new String[]{"initial", "saved", "rejected"});
        preference.setOnPreferenceChangeListener((p, value) -> !value.equals("rejected"));
        screen.addPreference(preference);
    }
}
