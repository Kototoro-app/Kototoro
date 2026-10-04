package org.skepsun.kototoro.source.host

internal object AnimeExtensionFixture {
    val source = """
        package fixture.anime;
        import eu.kanade.tachiyomi.animesource.model.*;
        import kotlin.coroutines.Continuation;
        import java.util.*;
        public class AnimeSource implements eu.kanade.tachiyomi.animesource.AnimeCatalogueSource {
            public long getId() { return 777L; }
            public String getName() { return "夹具动画"; } public String getLang() { return "ja"; }
            public boolean getSupportsLatest() { return true; }
            public String getBaseUrl() { return "https://anime.invalid"; }

            private final AnimeFilter.CheckBox dub = new AnimeFilter.CheckBox("Dub", false);
            public AnimeFilterList getFilterList() {
                return new AnimeFilterList(Arrays.<AnimeFilter<?>>asList(new AnimeFilter.Header("General"), dub));
            }

            private SAnimeImpl anime(String title) {
                SAnimeImpl anime = new SAnimeImpl();
                anime.setUrl("/anime/one"); anime.setTitle(title); anime.setThumbnail_url("/cover.jpg");
                anime.setGenre("Action, Ecchi"); anime.setAuthor("Studio"); anime.setStatus(1);
                return anime;
            }
            public Object getPopularAnime(int page, Continuation<Object> continuation) {
                return new AnimesPage(Collections.singletonList(anime("popular " + page)), true);
            }
            public Object getLatestUpdates(int page, Continuation<Object> continuation) {
                return new AnimesPage(Collections.singletonList(anime("latest " + page)), false);
            }
            public Object getSearchAnime(int page, String query, AnimeFilterList filters, Continuation<Object> continuation) {
                return new AnimesPage(Collections.singletonList(anime(query + " " + page + " dub=" + dub.getState())), false);
            }
            public Object getAnimeDetails(SAnimeImpl original, Continuation<Object> continuation) {
                SAnimeImpl details = new SAnimeImpl();
                details.setUrl("wrong"); details.setDescription("About the anime"); details.setStatus(2);
                details.setGenre("Action, Safe");
                return details;
            }
            private SEpisodeImpl episode(String url, String name, float number) {
                SEpisodeImpl episode = new SEpisodeImpl();
                episode.setUrl(url); episode.setName(name); episode.setEpisode_number(number);
                episode.setDate_upload(1720000000000L); episode.setScanlator("Fansub");
                return episode;
            }
            public Object getEpisodeList(SAnimeImpl anime, Continuation<Object> continuation) {
                // Newest first, as sites list them; the second one has no number.
                return Arrays.asList(episode("/ep/hoster", "Hosters", 3f), episode("/ep/1", "First", -1f),
                    episode("/ep/failing", "Failing", 0f));
            }

            private Video video(String url, String title, Integer resolution, boolean preferred) {
                return new Video(url, title, resolution, new FixtureHeaders("Referer", "https://anime.invalid/", "X-Token", "t"),
                    preferred, Arrays.asList(new Track("https://anime.invalid/en.vtt", "en")),
                    Arrays.asList(new Track("https://anime.invalid/jp.aac", "ja")));
            }
            public Object getVideoList(SEpisodeImpl episode, Continuation<Object> continuation) {
                if (episode.getUrl().equals("/ep/failing")) throw new IllegalStateException("no videos");
                if (episode.getUrl().equals("/ep/hoster")) return new ArrayList<Video>();
                return Arrays.asList(
                    video("https://cdn.invalid/360.m3u8", "360p", 360, false),
                    video("", "broken", null, false),
                    video("https://cdn.invalid/720.m3u8", "720p", 720, true));
            }
            public Object getHosterList(SEpisodeImpl episode, Continuation<Object> continuation) {
                if (!episode.getUrl().equals("/ep/hoster")) return new ArrayList<Hoster>();
                return Arrays.asList(
                    new Hoster("", "inline", Arrays.asList(video("https://cdn.invalid/inline.mp4", "inline", 1080, false))),
                    new Hoster("https://hoster.invalid/lazy", "lazy", null));
            }
            public Object getVideoList(Hoster hoster, Continuation<Object> continuation) {
                return Arrays.asList(video("https://cdn.invalid/lazy.mp4", "lazy " + hoster.getHosterUrl(), 480, false));
            }
        }
    """.trimIndent()
}