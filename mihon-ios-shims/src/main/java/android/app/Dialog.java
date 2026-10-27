package android.app;

import android.content.Context;
import android.content.DialogInterface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsoluteLayout;

/** Ownership and lifecycle for browser presentations; ordinary Android dialogs are not rendered on iOS. */
public class Dialog implements DialogInterface {
    private final Context context;
    private final AbsoluteLayout container;
    private View content;
    private boolean showing;

    public Dialog(Context context) {
        this.context = context;
        container = new AbsoluteLayout(context);
    }
    public Context getContext() { return context; }
    public void setContentView(View view, ViewGroup.LayoutParams params) {
        if (showing) throw new UnsupportedOperationException("Replace content before showing a presentation");
        container.removeAllViews();
        container.addView(view, params);
        content = view;
    }
    protected View kototoroContent() { return content; }
    protected void kototoroShow() { throw new UnsupportedOperationException("Android dialogs are unavailable"); }
    protected void kototoroHide() {}
    public void show() {
        if (showing) return;
        try {
            if (content != null && content.getParent() == null) container.addView(content, content.getLayoutParams());
            kototoroShow();
            showing = true;
        } catch (RuntimeException | Error failure) {
            container.removeAllViews();
            throw failure;
        }
    }
    public boolean isShowing() { return showing; }
    @Override public void dismiss() {
        if (!showing) return;
        showing = false;
        try { kototoroHide(); } finally { container.removeAllViews(); }
    }
    @Override public void cancel() { dismiss(); }
}
