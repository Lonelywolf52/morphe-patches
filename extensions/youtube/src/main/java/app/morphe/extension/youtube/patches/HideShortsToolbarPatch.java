package app.morphe.extension.youtube.patches;

import android.app.Activity;
import android.app.Application;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.ShortsPlayerState;

/**
 * Hides the top bar of the Shorts player (back button, and the chat, search and menu buttons),
 * and optionally the system status bar while the Shorts player is open.
 */
@SuppressWarnings("unused")
public final class HideShortsToolbarPatch {

    private static final boolean HIDE_SHORTS_TOOLBAR = Settings.HIDE_SHORTS_TOOLBAR.get();

    /**
     * The global "Hide status bar" setting already hides it everywhere.
     */
    private static final boolean HIDE_SHORTS_STATUS_BAR = Settings.HIDE_SHORTS_STATUS_BAR.get()
            && !Settings.HIDE_STATUS_BAR.get();

    private static WeakReference<Activity> activityRef = new WeakReference<>(null);

    private static boolean initialized;

    /**
     * Re-hides the status bar while Shorts are open, as the app shows it again after
     * dialogs, the keyboard, and returning to the app.
     */
    @Nullable
    private static ViewTreeObserver.OnGlobalLayoutListener layoutListener;

    /**
     * Injection point.
     * <p>
     * Called with the Shorts player top bar after it is looked up.
     * The app sets the top bar visible again whenever a Short is bound, so instead of only
     * changing the visibility the bar is also given no size. A view with no size draws nothing
     * and receives no touches.
     */
    public static void hideShortsToolbar(@Nullable View toolbar) {
        if (!HIDE_SHORTS_TOOLBAR || toolbar == null) {
            return;
        }

        try {
            ViewGroup.LayoutParams params = toolbar.getLayoutParams();
            if (params != null) {
                params.width = 0;
                params.height = 0;
                toolbar.setLayoutParams(params);
            }
            toolbar.setVisibility(View.GONE);
        } catch (Exception ex) {
            Logger.printException(() -> "hideShortsToolbar failure", ex);
        }
    }

    /**
     * Injection point.
     * <p>
     * Called when the main activity is created.
     */
    public static void initialize(Activity activity) {
        if (!HIDE_SHORTS_STATUS_BAR) {
            return;
        }

        try {
            activityRef = new WeakReference<>(activity);

            if (initialized) {
                return;
            }
            initialized = true;

            ShortsPlayerState.getOnChange().addObserver((Boolean isOpen) -> updateStatusBar());

            activity.getApplication().registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityResumed(@NonNull Activity a) {
                    if (a == activityRef.get()) {
                        updateStatusBar();
                    }
                }

                @Override
                public void onActivityCreated(@NonNull Activity a, @Nullable Bundle b) {}
                @Override
                public void onActivityStarted(@NonNull Activity a) {}
                @Override
                public void onActivityPaused(@NonNull Activity a) {}
                @Override
                public void onActivityStopped(@NonNull Activity a) {}
                @Override
                public void onActivitySaveInstanceState(@NonNull Activity a, @NonNull Bundle b) {}
                @Override
                public void onActivityDestroyed(@NonNull Activity a) {}
            });
        } catch (Exception ex) {
            Logger.printException(() -> "initialize failure", ex);
        }
    }

    private static void updateStatusBar() {
        try {
            Activity activity = activityRef.get();
            if (activity == null) {
                return;
            }

            Window window = activity.getWindow();
            View decorView = window.getDecorView();
            final boolean shortsOpen = ShortsPlayerState.isOpen();

            Logger.printDebug(() -> "Shorts open: " + shortsOpen + ", hiding status bar: " + shortsOpen);

            if (shortsOpen) {
                setStatusBarHidden(window, decorView, true);

                if (layoutListener == null) {
                    layoutListener = () -> {
                        if (ShortsPlayerState.isOpen() && isStatusBarVisible(decorView)) {
                            setStatusBarHidden(window, decorView, true);
                        }
                    };
                    decorView.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
                }
            } else {
                if (layoutListener != null) {
                    decorView.getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);
                    layoutListener = null;
                }

                setStatusBarHidden(window, decorView, false);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "updateStatusBar failure", ex);
        }
    }

    private static boolean isStatusBarVisible(View decorView) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsets insets = decorView.getRootWindowInsets();
            return insets == null || insets.isVisible(WindowInsets.Type.statusBars());
        }

        //noinspection deprecation
        return (decorView.getSystemUiVisibility() & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0;
    }

    @SuppressWarnings("deprecation")
    private static void setStatusBarHidden(Window window, View decorView, boolean hide) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller == null) {
                return;
            }

            if (hide) {
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.statusBars());
            } else {
                controller.show(WindowInsets.Type.statusBars());
            }
            return;
        }

        final int flags = View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        final int current = decorView.getSystemUiVisibility();
        decorView.setSystemUiVisibility(hide ? (current | flags) : (current & ~flags));
    }
}
