package eu.kanade.tachiyomi.novelextension.zh.kototoro_fixture_ambiguous;

import eu.kanade.tachiyomi.source.Source;
import eu.kanade.tachiyomi.source.model.MangasPage;
import kotlin.coroutines.Continuation;

import java.util.Collections;

/**
 * T0.3 fixture — AMBIGUOUS rejection path.
 *
 * This source is never instantiated by the strict Tsundoku classifier: the manifest declares
 * BOTH {@code tachiyomi.novelextension} and {@code tachiyomi.extension}, so
 * {@code TachiyomiApkClassifier.classify(...)} returns {@code Ambiguous} before any class
 * loading happens (plan §7.2 / T2A.2 — "double feature → AMBIGUOUS"). The class exists so the
 * package is a fully-formed, installable APK that integration tests can prove is refused
 * without ever touching its classes.
 */
public final class AmbiguousNovel implements Source {

    public static final long SOURCE_ID = 1_400_301L;

    @Override
    public long getId() {
        return SOURCE_ID;
    }

    @Override
    public String getName() {
        return "Kototoro Fixture Ambiguous (novel+manga)";
    }

    @Override
    public boolean isNovelSource() {
        return true;
    }

    @Override
    public Object getPopularManga(int page, Continuation<? super MangasPage> continuation) {
        return new MangasPage(Collections.emptyList(), false);
    }
}
