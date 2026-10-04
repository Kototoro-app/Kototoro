package org.skepsun.kototoro.source.host

/**
 * Independently authored Aniyomi ABI for the tests: the signatures the runtime reflects over, none of the real
 * implementation. Production loads the animesource ABI the desktop compat module ships.
 */
internal object AnimeApiFixture {
    private const val MODEL = "package eu.kanade.tachiyomi.animesource.model;"

    private fun bean(name: String, fields: Map<String, String>, extra: String = ""): String {
        val members = fields.entries.joinToString("\n") { (property, type) ->
            val default = if (type == "String") " = \"\"" else ""
            val capital = property.replaceFirstChar(Char::uppercaseChar)
            "private $type $property$default; public $type get$capital() { return $property; } " +
                "public void set$capital($type value) { $property = value; }"
        }
        return "$MODEL public class $name { $members $extra }"
    }

    val sources = mapOf(
        "eu/kanade/tachiyomi/animesource/AnimeCatalogueSource.java" to """
            package eu.kanade.tachiyomi.animesource;
            public interface AnimeCatalogueSource {
                long getId(); String getName(); String getLang(); boolean getSupportsLatest();
            }
        """.trimIndent(),
        "eu/kanade/tachiyomi/animesource/AnimeSourceFactory.java" to """
            package eu.kanade.tachiyomi.animesource;
            public interface AnimeSourceFactory { java.util.List<AnimeCatalogueSource> createSources(); }
        """.trimIndent(),
        "eu/kanade/tachiyomi/animesource/model/SAnimeImpl.java" to bean("SAnimeImpl", linkedMapOf(
            "url" to "String", "title" to "String", "artist" to "String", "author" to "String",
            "description" to "String", "genre" to "String", "status" to "int", "thumbnail_url" to "String",
            "background_url" to "String", "initialized" to "boolean", "season_number" to "double",
        ), "public java.util.List<String> getGenres() { return genre.isEmpty() ? null : java.util.Arrays.asList(genre.split(\", \")); }"),
        "eu/kanade/tachiyomi/animesource/model/SEpisodeImpl.java" to bean("SEpisodeImpl", linkedMapOf(
            "url" to "String", "name" to "String", "date_upload" to "long", "episode_number" to "float",
            "fillermark" to "boolean", "scanlator" to "String", "summary" to "String", "preview_url" to "String",
        )),
        "eu/kanade/tachiyomi/animesource/model/AnimesPage.java" to """
            $MODEL
            public class AnimesPage {
                private final java.util.List<SAnimeImpl> animes; private final boolean hasNextPage;
                public AnimesPage(java.util.List<SAnimeImpl> animes, boolean hasNextPage) {
                    this.animes = animes; this.hasNextPage = hasNextPage;
                }
                public java.util.List<SAnimeImpl> getAnimes() { return animes; }
                public boolean getHasNextPage() { return hasNextPage; }
            }
        """.trimIndent(),
        "eu/kanade/tachiyomi/animesource/model/AnimeFilterList.java" to """
            $MODEL
            public class AnimeFilterList extends java.util.ArrayList<AnimeFilter<?>> {
                public AnimeFilterList(java.util.List<AnimeFilter<?>> filters) { super(filters); }
            }
        """.trimIndent(),
        "eu/kanade/tachiyomi/animesource/model/Track.java" to """
            $MODEL
            public class Track {
                private final String url, lang;
                public Track(String url, String lang) { this.url = url; this.lang = lang; }
                public String getUrl() { return url; } public String getLang() { return lang; }
            }
        """.trimIndent(),
        "eu/kanade/tachiyomi/animesource/model/FixtureHeaders.java" to """
            $MODEL
            public class FixtureHeaders {
                private final String[] pairs;
                public FixtureHeaders(String... pairs) { this.pairs = pairs; }
                public int size() { return pairs.length / 2; }
                public String name(int index) { return pairs[index * 2]; }
                public String value(int index) { return pairs[index * 2 + 1]; }
            }
        """.trimIndent(),
        "eu/kanade/tachiyomi/animesource/model/Video.java" to """
            $MODEL
            public class Video {
                private final String videoUrl, videoTitle; private final Integer resolution; private final FixtureHeaders headers;
                private final boolean preferred; private final java.util.List<Track> subtitleTracks, audioTracks;
                public Video(String videoUrl, String videoTitle, Integer resolution, FixtureHeaders headers, boolean preferred,
                    java.util.List<Track> subtitleTracks, java.util.List<Track> audioTracks) {
                    this.videoUrl = videoUrl; this.videoTitle = videoTitle; this.resolution = resolution;
                    this.headers = headers; this.preferred = preferred;
                    this.subtitleTracks = subtitleTracks; this.audioTracks = audioTracks;
                }
                public String getVideoUrl() { return videoUrl; } public String getVideoTitle() { return videoTitle; }
                public Integer getResolution() { return resolution; } public FixtureHeaders getHeaders() { return headers; }
                public boolean getPreferred() { return preferred; } public boolean getInitialized() { return true; }
                public java.util.List<Track> getSubtitleTracks() { return subtitleTracks; }
                public java.util.List<Track> getAudioTracks() { return audioTracks; }
            }
        """.trimIndent(),
        "eu/kanade/tachiyomi/animesource/model/Hoster.java" to """
            $MODEL
            public class Hoster {
                private final String hosterUrl, hosterName; private final java.util.List<Video> videoList;
                public Hoster(String hosterUrl, String hosterName, java.util.List<Video> videoList) {
                    this.hosterUrl = hosterUrl; this.hosterName = hosterName; this.videoList = videoList;
                }
                public String getHosterUrl() { return hosterUrl; } public String getHosterName() { return hosterName; }
                public java.util.List<Video> getVideoList() { return videoList; }
            }
        """.trimIndent(),
        // The anime filter hierarchy has the manga one's shape under other names.
        "eu/kanade/tachiyomi/animesource/model/AnimeFilter.java" to
            FilterApiFixture.sources.values.single()
                .replace("package eu.kanade.tachiyomi.source.model", "package eu.kanade.tachiyomi.animesource.model")
                .replace("Filter", "AnimeFilter"),
    )
}
