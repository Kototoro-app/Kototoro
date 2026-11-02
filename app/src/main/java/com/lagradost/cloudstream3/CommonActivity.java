package com.lagradost.cloudstream3;

import android.app.Activity;
import android.widget.Toast;

import java.lang.ref.WeakReference;

/**
 * Activity bridge for plugins compiled against the Cloudstream application.
 *
 * <p>Cloudstream declares {@code CommonActivity} as a Kotlin {@code object}, so a plugin that calls
 * {@code showToast("text")} without the optional duration actually invokes the compiler-generated
 * bridge {@code showToast$default(CommonActivity, String, Integer, int, Object)}. Kotlin cannot declare
 * a method whose name contains {@code $}, which is why this class is written in Java; every member keeps
 * the upstream shape (an {@code INSTANCE} field plus instance methods) so both the plugin's DEX
 * references and the host compatibility check resolve.
 */
public final class CommonActivity {

	/** Mirrors the {@code INSTANCE} field Kotlin generates for the upstream object. */
	public static final CommonActivity INSTANCE = new CommonActivity();

	private static WeakReference<Activity> activityRef;

	private CommonActivity() {
	}

	public Activity getActivity() {
		WeakReference<Activity> ref = activityRef;
		return ref == null ? null : ref.get();
	}

	public void setActivityInstance(Activity newActivity) {
		activityRef = newActivity == null ? null : new WeakReference<>(newActivity);
	}

	/** @param duration {@link Toast#LENGTH_SHORT} if {@code null}, as in Cloudstream */
	public void showToast(String message, Integer duration) {
		showToast(getActivity(), message, duration);
	}

	/** @param duration {@link Toast#LENGTH_SHORT} if {@code null}, as in Cloudstream */
	public void showToast(int message, Integer duration) {
		Activity activity = getActivity();
		if (activity != null) {
			showToast(activity, activity.getString(message), duration);
		}
	}

	/** Kotlin default-argument bridge emitted for {@link #showToast(String, Integer)}. */
	public static void showToast$default(
		CommonActivity self,
		String message,
		Integer duration,
		int mask,
		Object marker
	) {
		if ((mask & 2) != 0) {
			duration = null;
		}
		self.showToast(message, duration);
	}

	/** Kotlin default-argument bridge emitted for {@link #showToast(int, Integer)}. */
	public static void showToast$default(
		CommonActivity self,
		int message,
		Integer duration,
		int mask,
		Object marker
	) {
		if ((mask & 2) != 0) {
			duration = null;
		}
		self.showToast(message, duration);
	}

	private static void showToast(Activity activity, String message, Integer duration) {
		if (activity == null || message == null) {
			return;
		}
		int length = duration == null ? Toast.LENGTH_SHORT : duration;
		activity.runOnUiThread(() -> Toast.makeText(activity, message, length).show());
	}
}
