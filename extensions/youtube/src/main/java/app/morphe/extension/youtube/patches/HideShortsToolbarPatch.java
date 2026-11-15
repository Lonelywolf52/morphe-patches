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
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
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
        Logger.printDebug(() -> "ShortsToolbar: hook called, enabled: " + HIDE_SHORTS_TOOLBAR + ", view: " + toolbar);
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
        try {
            activityRef = new WeakReference<>(activity);

            if (initialized) {
                return;
            }
            initialized = true;

            // Diagnostics: logs the views at the top of the screen when Shorts open,
            // to find the top bar on app versions where the hook does not reach it.
            if (Logger.isDebugEnabled()) {
                ShortsPlayerState.getOnChange().addObserver((Boolean isOpen) -> {
                    if (isOpen) {
                        Utils.runOnMainThreadDelayed(HideShortsToolbarPatch::logTopViews, 2000);
                    }
                });
            }

            if (!HIDE_SHORTS_STATUS_BAR && !HIDE_SHORTS_TOOLBAR) {
                return;
            }

            ShortsPlayerState.getOnChange().addObserver((Boolean isOpen) -> updateShortsUi());

            activity.getApplication().registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityResumed(@NonNull Activity a) {
                    if (a == activityRef.get()) {
                        updateShortsUi();
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

    private static void logTopViews() {
        try {
            Activity activity = activityRef.get();
            if (activity == null) return;
            View decorView = activity.getWindow().getDecorView();
            final int limit = decorView.getHeight() / 6;
            StringBuilder sb = new StringBuilder("ShortsToolbar: views at top of screen (limit " + limit + "px):\n");
            logTopViews(decorView, limit, 0, sb);
            String text = sb.toString();
            // Logcat lines are limited in length.
            for (int i = 0; i < text.length(); i += 3000) {
                String part = text.substring(i, Math.min(text.length(), i + 3000));
                Logger.printDebug(() -> part);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "logTopViews failure", ex);
        }
    }

    private static void logTopViews(View view, int limit, int depth, StringBuilder sb) {
        if (view.getVisibility() != View.VISIBLE || view.getWidth() == 0 || view.getHeight() == 0) {
            return;
        }
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        if (location[1] > limit) {
            return;
        }

        String idName = "no_id";
        if (view.getId() != View.NO_ID) {
            try {
                idName = view.getResources().getResourceEntryName(view.getId());
            } catch (Exception ignored) {
                idName = "0x" + Integer.toHexString(view.getId());
            }
        }
        CharSequence description = view.getContentDescription();
        sb.append(depth).append(' ')
                .append(view.getClass().getName()).append(' ')
                .append(idName).append(' ')
                .append(location[0]).append(',').append(location[1]).append(' ')
                .append(view.getWidth()).append('x').append(view.getHeight());
        if (description != null) sb.append(" desc=").append(description);
        sb.append('\n');

        if (view instanceof ViewGroup group) {
            for (int i = 0, count = group.getChildCount(); i < count; i++) {
                logTopViews(group.getChildAt(i), limit, depth + 1, sb);
            }
        }
    }

    /**
     * The main app toolbar (back button, messages, search and menu) is shown on top of the
     * Shorts player. It is the same toolbar used on every other screen, so it is only hidden
     * while Shorts are open and restored afterwards.
     */
    @Nullable
    private static View findAppToolbar(View decorView) {
        final int id = ResourceUtils.getIdIdentifier("appbar_layout");
        return id == 0 ? null : decorView.findViewById(id);
    }

    /**
     * If the app toolbar was hidden by this patch, and must be restored when Shorts close.
     */
    private static boolean toolbarHidden;

    private static void applyShortsUi(Window window, View decorView) {
        if (HIDE_SHORTS_STATUS_BAR && isStatusBarVisible(decorView)) {
            setStatusBarHidden(window, decorView, true);
        }

        if (HIDE_SHORTS_TOOLBAR) {
            View toolbar = findAppToolbar(decorView);
            if (toolbar != null && toolbar.getVisibility() != View.GONE) {
                toolbar.setVisibility(View.GONE);
                toolbarHidden = true;
            }
        }
    }

    private static void updateShortsUi() {
        try {
            Activity activity = activityRef.get();
            if (activity == null) {
                return;
            }

            Window window = activity.getWindow();
            View decorView = window.getDecorView();
            final boolean shortsOpen = ShortsPlayerState.isOpen();

            Logger.printDebug(() -> "Shorts open: " + shortsOpen);

            if (shortsOpen) {
                applyShortsUi(window, decorView);

                if (layoutListener == null) {
                    // The app shows the status bar and toolbar again after dialogs,
                    // the keyboard, and returning to the app.
                    layoutListener = () -> {
                        if (ShortsPlayerState.isOpen()) {
                            applyShortsUi(window, decorView);
                        }
                    };
                    decorView.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
                }
            } else {
                if (layoutListener != null) {
                    decorView.getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);
                    layoutListener = null;
                }

                if (HIDE_SHORTS_STATUS_BAR) {
                    setStatusBarHidden(window, decorView, false);
                }

                if (toolbarHidden) {
                    toolbarHidden = false;
                    View toolbar = findAppToolbar(decorView);
                    if (toolbar != null) {
                        toolbar.setVisibility(View.VISIBLE);
                    }
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "updateShortsUi failure", ex);
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
