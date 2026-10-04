package org.skepsun.kototoro.source.host

import java.nio.file.Path
import java.nio.file.Files
import java.io.ByteArrayInputStream
import java.io.DataInputStream

/** Test-only Java ABI authored for these tests; not an AndroidCompat or Mihon implementation. */
internal class RuntimeJarFixture(private val root: Path) {
    val jars = JarFixture(root, runtimeApi = true)
    fun sourceJar(): Path {
        val classes = root.resolve("runtime-classes")
        jars.compile(root.resolve("runtime-src"), classes, mapOf(
            "fixture/extension/RuntimeSource.java" to source,
            "fixture/extension/Late.java" to "package fixture.extension; public class Late {}",
            "fixture/extension/AfterClose.java" to "package fixture.extension; public class AfterClose {}",
        ))
        markSuspendBridges(classes.resolve("fixture/extension/RuntimeSource.class"))
        return jars.jar(root.resolve("runtime.jar"), JarFixture.manifest(entry = ".RuntimeSource"), classes)
    }

    /** An anime (Aniyomi) extension on the animesource ABI: lists, details, episodes, plain and hoster video lists. */
    fun animeJar(): Path {
        val classes = root.resolve("anime-classes")
        jars.compile(root.resolve("anime-src"), classes, mapOf("fixture/anime/AnimeSource.java" to AnimeExtensionFixture.source))
        return jars.jar(root.resolve("anime.jar"), JarFixture.animeManifest(), classes)
    }
    /** A novel (Tsundoku) extension: same ABI, novel manifest keys, text through isNovelSource / fetchPageText. */
    fun novelJar(libraryMarker: Boolean = true, versionName: String = "1.6.3", extra: String = ""): Path {
        val classes = root.resolve("novel-classes")
        jars.compile(root.resolve("novel-src"), classes, mapOf("fixture/novel/NovelSource.java" to novelSource))
        return jars.jar(root.resolve("novel.jar"), JarFixture.novelManifest(
            libraryMarker = libraryMarker, versionName = versionName, extra = extra), classes)
    }
    companion object {
        /** Emulate Kotlin's ACC_BRIDGE-only default suspend forwarders in the real pinned HttpSource ABI. */
        private fun markSuspendBridges(path: Path) {
            val bytes = Files.readAllBytes(path)
            val input = DataInputStream(ByteArrayInputStream(bytes))
            input.skipNBytes(8)
            val constants = mutableMapOf<Int, String>()
            val count = input.readUnsignedShort()
            var index = 1
            while (index < count) {
                when (val tag = input.readUnsignedByte()) {
                    1 -> constants[index] = input.readUTF()
                    3, 4 -> input.skipNBytes(4)
                    5, 6 -> { input.skipNBytes(8); index++ }
                    7, 8, 16, 19, 20 -> input.skipNBytes(2)
                    9, 10, 11, 12, 17, 18 -> input.skipNBytes(4)
                    15 -> input.skipNBytes(3)
                    else -> error("Unexpected class constant $tag")
                }
                index++
            }
            fun attributes() {
                repeat(input.readUnsignedShort()) { input.readUnsignedShort(); input.skipNBytes(input.readInt().toLong()) }
            }
            input.skipNBytes(6)
            input.skipNBytes(input.readUnsignedShort() * 2L)
            repeat(input.readUnsignedShort()) { input.skipNBytes(6); attributes() }
            repeat(input.readUnsignedShort()) {
                val accessOffset = bytes.size - input.available()
                input.readUnsignedShort()
                val name = constants[input.readUnsignedShort()].orEmpty()
                input.readUnsignedShort()
                if (name in setOf("getPopularManga", "getLatestUpdates", "getSearchManga", "getMangaUpdate", "getPageList")) {
                    bytes[accessOffset + 1] = (bytes[accessOffset + 1].toInt() or 0x40).toByte()
                }
                attributes()
            }
            Files.write(path, bytes)
        }

        private val novelSource = """
            package fixture.novel;
            import eu.kanade.tachiyomi.source.model.*;
            import kotlin.coroutines.Continuation;
            public class NovelSource extends eu.kanade.tachiyomi.source.online.HttpSource {
                public long getId() { return 4242L; }
                public String getName() { return "夹具小说"; } public String getLang() { return "zh"; }
                public boolean getSupportsLatest() { return false; }
                public boolean isNovelSource() { return true; }
                public FilterList getFilterList() { return new FilterList(java.util.Collections.emptyList()); }
                // Chapter /chapter/fail has no readable page at all; every other chapter has text, a failing, a blank, a markup and an image page.
                public Object getPageList(SChapterImpl chapter, Continuation<Object> continuation) {
                    return java.util.Arrays.asList(new Page(0, chapter.getUrl() + "#0", null, null),
                        new Page(1, chapter.getUrl() + "#1", null, null), new Page(2, chapter.getUrl() + "#2", null, null),
                        new Page(3, chapter.getUrl() + "#3", null, null),
                        new Page(4, chapter.getUrl() + "#4", "https://fixture.invalid/figure.png", null));
                }
                public Object fetchPageText(Page page, Continuation<Object> continuation) {
                    if (page.getUrl().startsWith("/chapter/fail")) throw new IllegalStateException("text unavailable");
                    switch (page.getIndex()) {
                        case 0: return "Line one & <two>";
                        case 1: throw new IllegalStateException("one page failed");
                        case 3: return "<p>Marked <b>up</b> text</p>";
                        default: return "  ";
                    }
                }
            }
        """
        private fun bean(name: String, properties: Map<String, String>, extra: String = ""): String {
            val fields = properties.entries.joinToString("\n") { (property, type) ->
                val default = if (type == "String") " = \"\"" else ""
                "private $type $property$default; public $type get${property.replaceFirstChar(Char::uppercaseChar)}() " +
                    "{ return $property; } public void set${property.replaceFirstChar(Char::uppercaseChar)}($type value) " +
                    "{ $property = value; }"
            }
            // The pinned compatibility ABI's legacy copy methods do not copy memo.
            val copy = properties.keys.filter { it != "memo" }.joinToString(" ") { "other.$it = this.$it;" }
            return "package eu.kanade.tachiyomi.source.model; public class $name { $fields " +
                "public $name copy() { $name other = new $name(); $copy return other; } $extra }"
        }

        val apiSources = mapOf(
            "eu/kanade/tachiyomi/source/model/SMangaImpl.java" to bean("SMangaImpl", linkedMapOf(
                "url" to "String", "title" to "String", "author" to "String", "artist" to "String",
                "description" to "String", "genre" to "String", "thumbnail_url" to "String",
                "status" to "int", "initialized" to "boolean", "memo" to "kotlinx.serialization.json.JsonObject",
            )),
            "eu/kanade/tachiyomi/source/model/SChapterImpl.java" to bean("SChapterImpl", linkedMapOf(
                "url" to "String", "name" to "String", "chapter_number" to "float", "scanlator" to "String",
                "date_upload" to "long", "memo" to "kotlinx.serialization.json.JsonObject",
            ), """
                public void copyFrom(SChapterImpl original) {
                    setUrl(original.getUrl()); setName(original.getName()); setChapter_number(original.getChapter_number());
                    setScanlator(original.getScanlator()); setDate_upload(original.getDate_upload());
                }
            """),
            "eu/kanade/tachiyomi/source/model/MangasPage.java" to """
                package eu.kanade.tachiyomi.source.model;
                public class MangasPage {
                    private final java.util.List<SMangaImpl> mangas;
                    public MangasPage(java.util.List<SMangaImpl> mangas) { this.mangas = mangas; }
                    public java.util.List<SMangaImpl> getMangas() { return mangas; }
                }
            """,
            "eu/kanade/tachiyomi/source/model/SMangaUpdate.java" to """
                package eu.kanade.tachiyomi.source.model;
                public class SMangaUpdate {
                    private final SMangaImpl manga; private final java.util.List<SChapterImpl> chapters;
                    public SMangaUpdate(SMangaImpl manga, java.util.List<SChapterImpl> chapters) {
                        this.manga = manga; this.chapters = chapters;
                    }
                    public SMangaImpl getManga() { return manga; }
                    public java.util.List<SChapterImpl> getChapters() { return chapters; }
                }
            """,
            "eu/kanade/tachiyomi/source/model/FilterList.java" to """
                package eu.kanade.tachiyomi.source.model;
                public class FilterList extends java.util.ArrayList<Filter<?>> {
                    public FilterList(java.util.List<Filter<?>> filters) { super(filters); }
                    public java.util.List<Filter<?>> getList() { return this; }
                }
            """,
            "eu/kanade/tachiyomi/source/model/Page.java" to """
                package eu.kanade.tachiyomi.source.model;
                public class Page {
                    private final int index; private final String url; private final String imageUrl;
                    public Page(int index, String url, String imageUrl, Object uri) {
                        this.index = index; this.url = url; this.imageUrl = imageUrl;
                    }
                    public int getIndex() { return index; } public String getUrl() { return url; }
                    public String getImageUrl() { return imageUrl; }
                }
            """,
            "eu/kanade/tachiyomi/source/online/HttpSource.java" to """
                package eu.kanade.tachiyomi.source.online;
                public abstract class HttpSource implements eu.kanade.tachiyomi.source.CatalogueSource {
                    public String getBaseUrl() { return "https://fixture.invalid"; }
                    public String getMangaUrl(eu.kanade.tachiyomi.source.model.SMangaImpl manga) {
                        return getBaseUrl() + manga.getUrl();
                    }
                    public Object getImageUrl(eu.kanade.tachiyomi.source.model.Page page, kotlin.coroutines.Continuation<?> c) {
                        return "https://fixture.invalid/image?index=" + page.getIndex() + "&page=" + page.getUrl();
                    }
                    protected Request imageRequest(eu.kanade.tachiyomi.source.model.Page page) { return new Request(page); }
                    public static class Request {
                        private final eu.kanade.tachiyomi.source.model.Page page;
                        public Request(eu.kanade.tachiyomi.source.model.Page page) { this.page = page; }
                        public Headers headers() { return new Headers(page.getUrl()); }
                    }
                    public static class Headers {
                        private final String referer;
                        public Headers(String referer) { this.referer = referer; }
                        public int size() { return 1; } public String name(int index) { return "Referer"; }
                        public String value(int index) { return referer; }
                    }
                }
            """,
        ) + FilterApiFixture.sources + AnimeApiFixture.sources

        private val source = """
            package fixture.extension;
            import eu.kanade.tachiyomi.source.model.*;
            import kotlin.coroutines.Continuation;
            public class RuntimeSource extends eu.kanade.tachiyomi.source.online.HttpSource {
                public long getId() { return 9007199254740993L; }
                public String getName() { return "Fixture"; } public String getLang() { return "zh"; }
                public boolean getSupportsLatest() { return true; }
                private String imageMode = "normal";
                private String imageContext;
                private Continuation<Object> pendingImage;
                private final java.util.concurrent.atomic.AtomicInteger imageCloses = new java.util.concurrent.atomic.AtomicInteger();
                private final java.util.concurrent.CountDownLatch imageStarted = new java.util.concurrent.CountDownLatch(1);
                public void setImageMode(String value) { imageMode = value; }
                public String getImageContext() { return imageContext; }
                public int getImageCloses() { return imageCloses.get(); }
                public java.util.concurrent.CountDownLatch getImageStarted() { return imageStarted; }
                public Object getImage(Page page, Continuation<Object> continuation) {
                    imageContext = page.getIndex() + "|" + page.getUrl() + "|" + page.getImageUrl();
                    if (imageMode.equals("missing")) return null;
                    if (imageMode.equals("deferred")) {
                        pendingImage = continuation; imageStarted.countDown();
                        return kotlin.coroutines.intrinsics.IntrinsicsKt.getCOROUTINE_SUSPENDED();
                    }
                    return new ImageResponse();
                }
                public void completeImage() throws Exception {
                    Class.forName("fixture.extension.Late", true, getClass().getClassLoader());
                    pendingImage.resumeWith(new ImageResponse());
                }
                public class ImageResponse implements java.io.Closeable {
                    private final java.util.concurrent.CountDownLatch closed = new java.util.concurrent.CountDownLatch(1);
                    private final byte[] bytes = imageMode.equals("html") ? "<html>error</html>".getBytes() :
                        java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aXuoAAAAASUVORK5CYII=");
                    public int code() { return imageMode.equals("status") ? 403 : 200; }
                    public ImageBody body() { return new ImageBody(); }
                    public class ImageBody {
                        public long contentLength() { return bytes.length; }
                        public java.io.InputStream byteStream() {
                            if (!imageMode.equals("blocking")) return new java.io.ByteArrayInputStream(bytes);
                            return new java.io.InputStream() {
                                public int read() throws java.io.IOException {
                                    imageStarted.countDown();
                                    try { if (!closed.await(10, java.util.concurrent.TimeUnit.SECONDS))
                                        throw new java.io.IOException("close timeout");
                                    } catch (InterruptedException e) { throw new java.io.IOException(e); }
                                    throw new java.io.IOException("closed image body");
                                }
                            };
                        }
                    }
                    public void close() throws java.io.IOException {
                        if (Thread.currentThread().getContextClassLoader() != RuntimeSource.this.getClass().getClassLoader())
                            throw new java.io.IOException("wrong image close ClassLoader");
                        imageCloses.incrementAndGet(); closed.countDown();
                    }
                }
                private final Filter.CheckBox flag = new Filter.CheckBox("Flag", true);
                private final Filter.TriState genre = new Filter.TriState("Genre", 0);
                private final Filter.Select<Object> choice = new Filter.Select<>("Choice", new Object[] {
                    new Object() { public String toString() { return "ThemeInfo(name=爱情, key=a)"; } },
                    "key=fragment)", "Second" }, 0);
                private final Filter.Sort sort = new Filter.Sort("Order", new String[] { "Name", "Date" },
                    new Filter.Sort.Selection(0, false));
                private final Filter.Text author = new Filter.Text("Author", "default");
                private final Filter.Text authorExtra = new Filter.Text("AuthorExtra", "extra");
                private final Filter.CheckBox nested = new Filter.CheckBox("NestedFlag", false);
                private final FilterList controls = new FilterList(java.util.Arrays.asList(new Filter.Header("General"),
                    new Filter.Separator(""), flag, genre, choice, sort, author, authorExtra,
                    new Filter.Group<>("Group", java.util.Collections.singletonList(
                        new Filter.Group<>("Nested", java.util.Collections.singletonList(nested)))),
                    new Filter<Object>("Custom", "opaque")));
                public FilterList getFilterList() { return controls; }
                public String filterState() {
                    return flag.getState() + "/" + genre.getState() + "/" + choice.getState() + "/" +
                        (sort.getState() == null ? "null" : sort.getState().getIndex() + ":" + sort.getState().getAscending()) +
                        "/" + author.getState() + "/" + authorExtra.getState() + "/" + nested.getState();
                }
                private Continuation<Object> pending;
                private final java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
                public java.util.concurrent.CountDownLatch getStarted() { return started; }
                public void complete() throws Exception {
                    Class.forName("fixture.extension.Late", true, getClass().getClassLoader());
                    pending.resumeWith(list("completed"));
                }
                public Object getPopularManga(int page, Continuation<Object> continuation) {
                    if (page == 77) { pending = continuation; started.countDown();
                        return kotlin.coroutines.intrinsics.IntrinsicsKt.getCOROUTINE_SUSPENDED(); }
                    if (page == 88) {
                        new Thread(() -> continuation.resumeWith(list("async"))).start();
                        return kotlin.coroutines.intrinsics.IntrinsicsKt.getCOROUTINE_SUSPENDED();
                    }
                    if (page == 99) throw new IllegalStateException("private URL or token");
                    return list("popular " + page);
                }
                public Object getLatestUpdates(int page, Continuation<Object> continuation) { return list("latest " + page); }
                public Object getSearchManga(int page, String query, FilterList filters, Continuation<Object> continuation) {
                    if (query.equals("filters")) return list(filterState());
                    if (query.equals("failFilters")) throw new IllegalStateException("fixture search error");
                    if (query.equals("deferredFilters")) {
                        pending = continuation; started.countDown();
                        return kotlin.coroutines.intrinsics.IntrinsicsKt.getCOROUTINE_SUSPENDED();
                    }
                    return list(query + " " + page);
                }
                public void completeFilters() { pending.resumeWith(list(filterState())); }
                private MangasPage list(String title) {
                    SMangaImpl manga = new SMangaImpl() {
                        @Override public String getTitle() {
                            if (Thread.currentThread().getContextClassLoader() != getClass().getClassLoader())
                                throw new IllegalStateException("wrong extension ClassLoader context");
                            return super.getTitle();
                        }
                    };
                    manga.setUrl("/manga/漫画"); manga.setTitle(title); manga.setThumbnail_url("/cover.png");
                    manga.setGenre("ThemeInfo(name=爱情, pathWord=aiqing), safe, nsfw");
                    manga.setAuthor("Author"); manga.setArtist("Artist"); manga.setStatus(1);
                    manga.setMemo((kotlinx.serialization.json.JsonObject) kotlinx.serialization.json.Json.Default
                        .parseToJsonElement("{\"token\":\"opaque\"}"));
                    return new MangasPage(java.util.Collections.singletonList(manga));
                }
                public Object getMangaUpdate(SMangaImpl manga, java.util.List<SChapterImpl> existing,
                    boolean details, boolean chapters, Continuation<Object> continuation) {
                    if (!details || !chapters) throw new IllegalArgumentException("missing update flags");
                    SMangaImpl partial = new SMangaImpl(); partial.setUrl("wrong URL"); partial.setThumbnail_url(manga.getUrl());
                    partial.setDescription("<p>description</p>");
                    SChapterImpl newest = chapter("/chapter/2", -1, "new");
                    SChapterImpl oldest = chapter("/chapter/1", -1, "old"); oldest.setDate_upload(1720000000000L);
                    return new SMangaUpdate(partial, java.util.Arrays.asList(newest, oldest));
                }
                private SChapterImpl chapter(String url, float number, String name) {
                    SChapterImpl chapter = new SChapterImpl(); chapter.setUrl(url); chapter.setName(name);
                    chapter.setChapter_number(number); chapter.setScanlator("team");
                    chapter.setMemo((kotlinx.serialization.json.JsonObject) kotlinx.serialization.json.Json.Default
                        .parseToJsonElement("{\"chapterToken\":\"opaque\"}")); return chapter;
                }
                public Object getPageList(SChapterImpl chapter, Continuation<Object> continuation) {
                    if (chapter.getMemo() == null) throw new IllegalStateException("Lost chapter memo");
                    return java.util.Arrays.asList(
                        new Page(7, "https://fixture.invalid/chapter?title=漫画&key=+", null, null),
                        new Page(12, "https://fixture.invalid/origin", "https://fixture.invalid/a.png#key=secret", null)
                    );
                }
            }
        """.trimIndent()
    }
}
