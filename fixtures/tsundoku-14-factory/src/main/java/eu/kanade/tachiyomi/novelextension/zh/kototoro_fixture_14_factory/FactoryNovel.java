package eu.kanade.tachiyomi.novelextension.zh.kototoro_fixture_14_factory;

import eu.kanade.tachiyomi.source.Source;
import eu.kanade.tachiyomi.source.SourceFactory;

import java.util.Arrays;
import java.util.List;

/**
 * T0.3 fixture — extensions-lib 1.4 {@link SourceFactory} morphology.
 *
 * The metadata class is a factory, not a source. The host instantiate it, sees
 * {@code is SourceFactory}, calls {@link #createSources()} and then validates every returned
 * source individually:
 * <ul>
 *   <li>{@link FactoryNovelSource} — {@code isNovelSource = true}: accepted by the Tsundoku
 *       per-source contract ({@code isNovelSource || it is NovelSource});</li>
 *   <li>{@link FactoryMangaSource} — {@code isNovelSource = false} (a manga object shipped in a
 *       novel package): rejected with a per-source {@code TachiyomiSourceRejection} while the
 *       package itself still loads with the legal novel source (plan §6.3 / T2A.2).</li>
 * </ul>
 */
public final class FactoryNovel implements SourceFactory {

    @Override
    public List<Source> createSources() {
        return Arrays.asList(
            new FactoryNovelSource(),
            new FactoryMangaSource()
        );
    }
}
