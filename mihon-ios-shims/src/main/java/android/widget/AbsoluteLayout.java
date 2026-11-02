package android.widget;

import android.content.Context;
import android.content.res.Resources;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.View;
import android.view.ViewParent;
import java.util.ArrayList;
import java.util.List;

/** Non-rendering WebView base: WKWebView owns layout and input, while extensions can read resources. */
public class AbsoluteLayout extends ViewGroup {
    private final Resources resources;
    private final List<View> children = new ArrayList<>();
    private ViewGroup.LayoutParams layoutParams;
    private ViewGroup.OnHierarchyChangeListener hierarchyListener;

    public AbsoluteLayout(Context context) { this(context, null); }
    public AbsoluteLayout(Context context, AttributeSet attrs) { this(context, attrs, 0); }
    public AbsoluteLayout(Context context, AttributeSet attrs, int style) { this(context, attrs, style, 0); }
    public AbsoluteLayout(Context context, AttributeSet attrs, int style, int resource) {
        super(context, attrs, style, resource);
        resources = context.getResources();
    }

    @Override public Resources getResources() { return resources; }
    public void kototoroSetParent(ViewParent parent) { mParent = parent; }
    @Override public ViewGroup.LayoutParams getLayoutParams() { return layoutParams; }
    @Override public void setLayoutParams(ViewGroup.LayoutParams params) { layoutParams = params; }
    @Override public void addView(View child) { addView(child, children.size(), generateDefaultLayoutParams()); }
    @Override public void addView(View child, ViewGroup.LayoutParams params) {
        addView(child, children.size(), params);
    }
    @Override public void addView(View child, int index) { addView(child, index, generateDefaultLayoutParams()); }
    @Override public void addView(View child, int index, ViewGroup.LayoutParams params) {
        if (!(child instanceof AbsoluteLayout))
            throw new UnsupportedOperationException("Only browser-compatible child views are supported");
        if (child.getParent() != null) throw new IllegalStateException("View already has a parent");
        children.add(index < 0 ? children.size() : index, child);
        ((AbsoluteLayout) child).kototoroSetParent(this);
        child.setLayoutParams(params);
        if (hierarchyListener != null) hierarchyListener.onChildViewAdded(this, child);
    }
    @Override public void removeView(View child) {
        if (!children.remove(child)) return;
        ((AbsoluteLayout) child).kototoroSetParent(null);
        if (hierarchyListener != null) hierarchyListener.onChildViewRemoved(this, child);
    }
    @Override public void removeAllViews() {
        for (View child : new ArrayList<>(children)) removeView(child);
    }
    @Override public int getChildCount() { return children.size(); }
    @Override public View getChildAt(int index) {
        return index >= 0 && index < children.size() ? children.get(index) : null;
    }
    @Override public void setOnHierarchyChangeListener(ViewGroup.OnHierarchyChangeListener listener) {
        hierarchyListener = listener;
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) { return onTouchEvent(event); }
    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {}
    @Override protected void onMeasure(int width, int height) {}
    @Override protected ViewGroup.LayoutParams generateDefaultLayoutParams() {
        return new ViewGroup.LayoutParams(-2, -2);
    }
    @Override public ViewGroup.LayoutParams generateLayoutParams(AttributeSet attrs) {
        return generateDefaultLayoutParams();
    }
    @Override protected ViewGroup.LayoutParams generateLayoutParams(ViewGroup.LayoutParams params) { return params; }
    @Override protected boolean checkLayoutParams(ViewGroup.LayoutParams params) { return params != null; }
    @Override public boolean shouldDelayChildPressedState() { return false; }
}
