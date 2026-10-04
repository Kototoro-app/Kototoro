package fixture.desktop;

import eu.kanade.tachiyomi.animesource.model.*;
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource;
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource;
import androidx.preference.ListPreference;
import androidx.preference.PreferenceScreen;
import okhttp3.*;
import java.util.*;

/**
 * Authored anime fixture on the desktop animesource ABI. Catalogue requests are answered in-process; the streams point
 * at whatever `fixture.anime.video.url` names (a local server in the probe), and need the Referer they declare.
 */
public final class AnimeSource extends AnimeHttpSource implements ConfigurableAnimeSource {
    private final OkHttpClient client = getNetwork().getClient().newBuilder().cache(null)
        .addInterceptor(chain -> new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
            .message("authored").body(ResponseBody.create("", MediaType.get("text/html"))).build())
        .build();

    @Override public OkHttpClient getClient() { return client; }
    @Override public String getName() { return "Anime desktop fixture"; }
    @Override public String getLang() { return "zh"; }
    @Override public String getBaseUrl() { return "https://anime.fixture"; }
    @Override public boolean getSupportsLatest() { return false; }

    private static Request get(String url) { return new Request.Builder().url(url).build(); }

    private AnimesPage page() {
        SAnime anime = SAnime.Companion.create();
        anime.setUrl("/anime/1"); anime.setTitle("测试动画"); anime.setDescription("Authored anime");
        return new AnimesPage(Collections.singletonList(anime), false);
    }

    @Override protected Request popularAnimeRequest(int page) { return get("https://anime.fixture/popular"); }
    @Override protected AnimesPage popularAnimeParse(Response response) { return page(); }
    @Override protected Request searchAnimeRequest(int page, String query, AnimeFilterList filters) {
        return get("https://anime.fixture/search");
    }
    @Override protected AnimesPage searchAnimeParse(Response response) { return page(); }
    @Override protected Request latestUpdatesRequest(int page) { return get("https://anime.fixture/latest"); }
    @Override protected AnimesPage latestUpdatesParse(Response response) { return page(); }
    @Override protected SAnime animeDetailsParse(Response response) {
        SAnime anime = SAnime.Companion.create();
        anime.setTitle("测试动画"); anime.setDescription("Authored anime"); anime.setStatus(SAnime.COMPLETED);
        return anime;
    }
    @Override protected List<SEpisode> episodeListParse(Response response) {
        List<SEpisode> episodes = new ArrayList<>();
        for (int number = 2; number >= 1; number--) {
            SEpisode episode = SEpisode.Companion.create();
            episode.setUrl("/episode/" + number); episode.setName("第 " + number + " 集"); episode.setEpisode_number(number);
            episodes.add(episode);
        }
        return episodes;
    }
    /** Which line is offered first: the source's own setting, read from its `source_<id>` store. */
    @Override public void setupPreferenceScreen(PreferenceScreen screen) {
        ListPreference line = new ListPreference(screen.getContext());
        line.setKey("preferred_line"); line.setTitle("首选线路"); line.setDefaultValue("main");
        line.setEntries(new String[]{"主线路", "备用线路"}); line.setEntryValues(new String[]{"main", "mirror"});
        screen.addPreference(line);
    }

    @Override protected List<Video> videoListParse(Response response) {
        String url = System.getProperty("fixture.anime.video.url", "http://127.0.0.1:9/missing.mkv");
        Headers headers = new Headers.Builder().add("Referer", "https://anime.fixture/").build();
        List<Video> videos = new ArrayList<>(Arrays.asList(
            new Video(url, "720p", url, headers, Collections.emptyList(), Collections.emptyList()),
            new Video(url + "?mirror=2", "备用线路", url + "?mirror=2", headers, Collections.emptyList(), Collections.emptyList())));
        if ("mirror".equals(getSourcePreferences().getString("preferred_line", "main"))) Collections.reverse(videos);
        return videos;
    }
}