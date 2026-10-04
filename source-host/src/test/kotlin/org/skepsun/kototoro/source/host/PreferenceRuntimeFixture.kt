package org.skepsun.kototoro.source.host

import java.nio.file.Path

/** Authored API fixture with executable callbacks; no third-party runtime classes enter test or production jars. */
internal class PreferenceRuntimeFixture(private val root: Path) {
    val jars = preferenceJarFixture(root)
    val jar: Path

    init {
        jars.compile(root.resolve("controls-api"), jars.apiClasses, mapOf(
            "android/content/Context.java" to "package android.content; public class Context {}",
            "androidx/preference/Preference.java" to """
                package androidx.preference;
                import android.content.*; import java.util.*;
                public class Preference {
                    private Context context; private SharedPreferences preferences;
                    private String key; private CharSequence title = "", summary = "";
                    private Object defaultValue; private boolean enabled = true, visible = true;
                    private OnPreferenceChangeListener listener;
                    public Preference(Context context) { this.context = context; }
                    public Context getContext() { return context; }
                    public void setSharedPreferences(SharedPreferences prefs) { preferences = prefs; }
                    public void setKey(String key) { this.key = key; } public String getKey() { return key; }
                    public void setTitle(CharSequence title) { this.title = title; } public CharSequence getTitle() { return title; }
                    public void setSummary(CharSequence summary) { this.summary = summary; } public CharSequence getSummary() { return summary; }
                    public void setEnabled(boolean value) { enabled = value; } public boolean isEnabled() { return enabled; }
                    public void setVisible(boolean value) { visible = value; } public boolean getVisible() { return visible; }
                    public void setDefaultValue(Object value) { defaultValue = value; } public Object getDefaultValue() { return defaultValue; }
                    public String getDefaultValueType() { return "String"; }
                    public Object getCurrentValue() {
                        switch (getDefaultValueType()) {
                            case "Boolean": return preferences.getBoolean(key, defaultValue != null && (Boolean) defaultValue);
                            case "Set<String>": return preferences.getStringSet(key, (Set<String>) defaultValue);
                            default: return preferences.getString(key, (String) defaultValue);
                        }
                    }
                    public void saveNewValue(Object value) {
                        SharedPreferences.Editor edit = preferences.edit();
                        switch (getDefaultValueType()) {
                            case "Boolean": edit.putBoolean(key, (Boolean) value); break;
                            case "Set<String>": edit.putStringSet(key, (Set<String>) value); break;
                            default: edit.putString(key, (String) value);
                        }
                        edit.apply();
                    }
                    public interface OnPreferenceChangeListener { boolean onPreferenceChange(Preference p, Object value); }
                    public void setOnPreferenceChangeListener(OnPreferenceChangeListener listener) { this.listener = listener; }
                    public boolean callChangeListener(Object value) { return listener == null || listener.onPreferenceChange(this, value); }
                }
            """.trimIndent(),
            "androidx/preference/PreferenceScreen.java" to """
                package androidx.preference;
                import android.content.*; import java.util.*;
                public class PreferenceScreen extends Preference {
                    private List<Preference> preferences = new ArrayList<>(); private SharedPreferences store;
                    public PreferenceScreen(Context context) { super(context); }
                    public void setSharedPreferences(SharedPreferences prefs) { super.setSharedPreferences(prefs); store = prefs; }
                    public boolean addPreference(Preference p) { p.setSharedPreferences(store); return preferences.add(p); }
                    public List<Preference> getPreferences() { return preferences; }
                }
            """.trimIndent(),
            "androidx/preference/PreferenceCategory.java" to """
                package androidx.preference;
                public class PreferenceCategory extends PreferenceScreen { public PreferenceCategory(android.content.Context c) { super(c); } }
            """.trimIndent(),
            "androidx/preference/ListPreference.java" to """
                package androidx.preference;
                public class ListPreference extends Preference {
                    private CharSequence[] entries, values;
                    public ListPreference(android.content.Context c) { super(c); }
                    public void setEntries(CharSequence[] v) { entries = v; } public CharSequence[] getEntries() { return entries; }
                    public void setEntryValues(CharSequence[] v) { values = v; } public CharSequence[] getEntryValues() { return values; }
                }
            """.trimIndent(),
            "androidx/preference/MultiSelectListPreference.java" to """
                package androidx.preference;
                public class MultiSelectListPreference extends ListPreference {
                    public MultiSelectListPreference(android.content.Context c) { super(c); }
                    public String getDefaultValueType() { return "Set<String>"; }
                }
            """.trimIndent(),
            "androidx/preference/SwitchPreferenceCompat.java" to """
                package androidx.preference;
                public class SwitchPreferenceCompat extends Preference {
                    public SwitchPreferenceCompat(android.content.Context c) { super(c); }
                    public String getDefaultValueType() { return "Boolean"; }
                }
            """.trimIndent(),
            "androidx/preference/EditTextPreference.java" to """
                package androidx.preference;
                public class EditTextPreference extends Preference {
                    private Object listener;
                    public EditTextPreference(android.content.Context c) { super(c); }
                    public void setOnBindEditTextListener(Object l) { listener = l; } public Object getOnBindEditTextListener() { return listener; }
                }
            """.trimIndent(),
            "eu/kanade/tachiyomi/source/ConfigurableSource.java" to """
                package eu.kanade.tachiyomi.source;
                public interface ConfigurableSource extends CatalogueSource {
                    android.content.SharedPreferences getSourcePreferences();
                    void setupPreferenceScreen(androidx.preference.PreferenceScreen screen);
                }
            """.trimIndent(),
        ))
        val classes = root.resolve("settings-classes")
        jars.compile(root.resolve("settings-src"), classes, mapOf("fixture/extension/Settings.java" to """
            package fixture.extension;
            import androidx.preference.*; import java.util.*;
            public class Settings implements eu.kanade.tachiyomi.source.ConfigurableSource {
                public static int calls;
                public static java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
                public static java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
                public long getId() { return 9007199254740993L; }
                public String getName() { return "settings"; } public String getLang() { return "zh"; }
                public boolean getSupportsLatest() { return false; }
                public List<?> getFilterList() { return Collections.emptyList(); }
                public android.content.SharedPreferences getSourcePreferences() { return fixture.PreferenceEnvironment.preferences; }
                public void setupPreferenceScreen(PreferenceScreen root) {
                    android.content.Context context = root.getContext();
                    Preference info = new Preference(context); info.setTitle("说明"); root.addPreference(info);
                    SwitchPreferenceCompat toggle = new SwitchPreferenceCompat(context);
                    toggle.setKey("enabled"); toggle.setDefaultValue(true); root.addPreference(toggle);
                    EditTextPreference text = new EditTextPreference(context);
                    text.setKey("comment"); text.setDefaultValue("初始"); root.addPreference(text);
                    ListPreference choice = new ListPreference(context); choice.setKey("domain"); choice.setDefaultValue("a");
                    choice.setEntries(new String[]{"相同", "相同", "拒绝", "异常", "阻塞"});
                    choice.setEntryValues(new String[]{"a", "b", "reject", "throws", "block"});
                    choice.setOnPreferenceChangeListener((p, value) -> {
                        calls++;
                        if (value.equals("block")) {
                            entered.countDown();
                            try { if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("release timeout"); }
                            catch (InterruptedException error) { throw new IllegalStateException(error); }
                        }
                        text.setEnabled(!value.equals("b"));
                        if (value.equals("throws")) {
                            getSourcePreferences().edit().putString("sideEffect", "retained").apply();
                            throw new IllegalStateException("token must not cross the protocol");
                        }
                        return !value.equals("reject");
                    });
                    root.addPreference(choice);
                    MultiSelectListPreference multiple = new MultiSelectListPreference(context); multiple.setKey("genres");
                    multiple.setDefaultValue(new HashSet<String>()); multiple.setEntries(new String[]{"One", "Two"});
                    multiple.setEntryValues(new String[]{"one", "two"}); root.addPreference(multiple);
                    Preference action = new Preference(context); action.setKey("action"); root.addPreference(action);
                    EditTextPreference input = new EditTextPreference(context); input.setKey("password");
                    input.setOnBindEditTextListener(new Object()); root.addPreference(input);
                    PreferenceCategory group = new PreferenceCategory(context); group.setTitle("分组"); group.setVisible(false);
                    EditTextPreference hidden = new EditTextPreference(context); hidden.setKey("hidden"); hidden.setDefaultValue("hidden");
                    group.addPreference(hidden); root.addPreference(group);
                    EditTextPreference duplicate1 = new EditTextPreference(context); duplicate1.setKey("duplicate");
                    EditTextPreference duplicate2 = new EditTextPreference(context); duplicate2.setKey("duplicate");
                    root.addPreference(duplicate1); root.addPreference(duplicate2);
                }
            }
        """.trimIndent()))
        jar = jars.jar(root.resolve("settings.jar"), JarFixture.manifest(entry = ".Settings"), classes)
    }

    /** The same controls behind the anime ABI: `ConfigurableAnimeSource` and its `source_<id>` store. */
    val animeJar: Path by lazy {
        jars.compile(root.resolve("anime-settings-api"), jars.apiClasses, mapOf(
            "eu/kanade/tachiyomi/animesource/AnimeCatalogueSource.java" to """
                package eu.kanade.tachiyomi.animesource;
                public interface AnimeCatalogueSource { long getId(); String getName(); String getLang(); boolean getSupportsLatest(); }
            """.trimIndent(),
            "eu/kanade/tachiyomi/animesource/AnimeSourceFactory.java" to
                "package eu.kanade.tachiyomi.animesource; public interface AnimeSourceFactory { java.util.List<AnimeCatalogueSource> createSources(); }",
            "eu/kanade/tachiyomi/animesource/ConfigurableAnimeSource.java" to """
                package eu.kanade.tachiyomi.animesource;
                public interface ConfigurableAnimeSource extends AnimeCatalogueSource {
                    android.content.SharedPreferences getSourcePreferences();
                    void setupPreferenceScreen(androidx.preference.PreferenceScreen screen);
                }
            """.trimIndent(),
        ))
        val classes = root.resolve("anime-settings-classes")
        jars.compile(root.resolve("anime-settings-src"), classes, mapOf("fixture/anime/AnimeSettings.java" to """
            package fixture.anime;
            import androidx.preference.*;
            public class AnimeSettings implements eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource {
                public long getId() { return 4243L; }
                public String getName() { return "动画设置"; } public String getLang() { return "ja"; }
                public boolean getSupportsLatest() { return false; }
                public android.content.SharedPreferences getSourcePreferences() { return fixture.PreferenceEnvironment.animePreferences; }
                public void setupPreferenceScreen(PreferenceScreen root) {
                    android.content.Context context = root.getContext();
                    ListPreference quality = new ListPreference(context); quality.setKey("preferred_quality");
                    quality.setTitle("首选画质"); quality.setDefaultValue("1080");
                    quality.setEntries(new String[]{"1080p", "720p"}); quality.setEntryValues(new String[]{"1080", "720"});
                    root.addPreference(quality);
                    SwitchPreferenceCompat dub = new SwitchPreferenceCompat(context);
                    dub.setKey("prefer_dub"); dub.setTitle("优先配音"); dub.setDefaultValue(false); root.addPreference(dub);
                }
            }
        """.trimIndent()))
        jars.jar(root.resolve("anime-settings.jar"), JarFixture.animeManifest(packageName = "fixture.anime", entry = ".AnimeSettings"), classes)
    }
}
