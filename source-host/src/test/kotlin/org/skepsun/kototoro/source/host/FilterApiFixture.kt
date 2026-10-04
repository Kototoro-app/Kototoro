package org.skepsun.kototoro.source.host

/** Minimal independently authored filter ABI fixture, matching the runtime method signatures only. */
internal object FilterApiFixture {
    val sources = mapOf("eu/kanade/tachiyomi/source/model/Filter.java" to """
        package eu.kanade.tachiyomi.source.model;
        public class Filter<T> {
            private final String name; private T state;
            public Filter(String name, T state) { this.name = name; this.state = state; }
            public final String getName() { return name; } public final T getState() { return state; }
            public final void setState(T value) { state = value; }
            public static class Header extends Filter<Object> { public Header(String name) { super(name, 0); } }
            public static class Separator extends Filter<Object> { public Separator(String name) { super(name, 0); } }
            public static class CheckBox extends Filter<Boolean> {
                public CheckBox(String name, boolean state) { super(name, state); }
            }
            public static class TriState extends Filter<Integer> { public TriState(String name, int state) { super(name, state); } }
            public static class Text extends Filter<String> { public Text(String name, String state) { super(name, state); } }
            public static class Select<V> extends Filter<Integer> {
                private final V[] values;
                public Select(String name, V[] values, int state) { super(name, state); this.values = values; }
                public final V[] getValues() { return values; }
            }
            public static class Group<V> extends Filter<java.util.List<V>> {
                public Group(String name, java.util.List<V> children) { super(name, children); }
            }
            public static class Sort extends Filter<Sort.Selection> {
                private final String[] values;
                public Sort(String name, String[] values, Selection state) { super(name, state); this.values = values; }
                public final String[] getValues() { return values; }
                public static class Selection {
                    private final int index; private final boolean ascending;
                    public Selection(int index, boolean ascending) { this.index = index; this.ascending = ascending; }
                    public int getIndex() { return index; } public boolean getAscending() { return ascending; }
                }
            }
        }
    """)
}
