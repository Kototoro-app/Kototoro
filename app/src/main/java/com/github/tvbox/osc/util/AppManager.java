package com.github.tvbox.osc.util;

import android.app.Activity;

import java.util.Stack;

public final class AppManager {
    private static final AppManager INSTANCE = new AppManager();
    private final Stack<Activity> activityStack = new Stack<>();

    private AppManager() {
    }

    public static AppManager getInstance() {
        return INSTANCE;
    }

    public synchronized void addActivity(Activity activity) {
        if (activity == null) return;
        activityStack.remove(activity);
        activityStack.add(activity);
    }

    public synchronized void setCurrentActivity(Activity activity) {
        addActivity(activity);
    }

    public synchronized boolean isActivity() {
        return currentActivity() != null;
    }

    public synchronized Activity currentActivity() {
        while (!activityStack.empty()) {
            Activity activity = activityStack.lastElement();
            if (!activity.isDestroyed()) return activity;
            activityStack.pop();
        }
        return null;
    }

    public synchronized void finishActivity(Activity activity) {
        activityStack.remove(activity);
    }

    public synchronized Activity getActivity(Class<?> type) {
        for (int index = activityStack.size() - 1; index >= 0; index--) {
            Activity activity = activityStack.get(index);
            if (activity.getClass().equals(type)) return activity;
        }
        return null;
    }
}
