package com.ez.zalopatch.xposed.features;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;

import com.ez.zalopatch.HookConfig;
import com.ez.zalopatch.Tweaks;
import com.ez.zalopatch.xposed.core.Feature;
import com.ez.zalopatch.xposed.core.SelfCheckRegistry;
import com.ez.zalopatch.xposed.core.XpHooks;
import com.ez.zalopatch.xposed.core.XpReflect;

import java.io.File;
import java.util.List;

/**
 * Chèn ảnh tuỳ chọn vào đầu giao diện Tin nhắn chính của Zalo.
 *
 * Hook trực tiếp vào constructor của
 * {@code com.zing.zalo.ui.maintab.msg.MessagesView} — tương tự pattern mà
 * {@link InboxFeature} dùng — thay vì dò theo tên layout (không ổn định).
 */
public final class InboxImageOverlayFeature extends Feature {

    private static final String FEATURE = Tweaks.KEY_INBOX_IMAGE_OVERLAY;
    private static final String MODULE_PACKAGE = "com.ez.zalopatch";

    /** Class chứa danh sách hội thoại chính của Zalo. */
    private static final String MESSAGES_VIEW_CLASS =
            "com.zing.zalo.ui.maintab.msg.MessagesView";

    /** Tên file ảnh lưu trong thư mục module. */
    public static final String OVERLAY_IMAGE_FILENAME = "inbox_overlay.jpg";

    private static final int DEFAULT_BANNER_HEIGHT_DP = 120;
    private static final int COLLAPSE_DURATION_MS = 250;

    /** Tag để tránh chèn ảnh nhiều lần vào cùng một instance. */
    private static final int VIEW_TAG_KEY = 0x7a700002;

    /** Cache bitmap để không decode lại mỗi lần. */
    private static volatile Bitmap cachedBitmap;
    private static volatile long cachedBitmapModified = -1;

    public InboxImageOverlayFeature(ClassLoader classLoader) {
        super(classLoader);
    }

    @Override
    public String getFeatureName() {
        return "InboxImageOverlay";
    }

    @Override
    public void doHook() {
        if (!HookConfig.isEnabled(FEATURE)) {
            SelfCheckRegistry.markDisabled(FEATURE, "inbox image banner");
            return;
        }

        // Tìm class MessagesView của Zalo
        Class<?> messagesViewClass = XpReflect.findClassIfExists(
                MESSAGES_VIEW_CLASS, classLoader);
        if (messagesViewClass == null) {
            SelfCheckRegistry.markStale(FEATURE,
                    MESSAGES_VIEW_CLASS + " constructor",
                    "MessagesView class not found");
            return;
        }

        // Hook constructor: sau khi MessagesView được tạo,
        // post một Runnable để chèn ảnh vào đầu view
        XpHooks.After afterConstruct = new XpHooks.After() {
            @Override
            public void after(XpHooks.HookParam param) {
                if (!HookConfig.isEnabled(FEATURE)) return;
                if (!(param.thisObject instanceof ViewGroup)) return;

                final ViewGroup messagesView = (ViewGroup) param.thisObject;
                messagesView.post(new Runnable() {
                    @Override
                    public void run() {
                        injectOverlay(messagesView);
                    }
                });
            }
        };

        List<XpHooks.Handle> installed = XpHooks.hookAllConstructors(
                messagesViewClass, null, afterConstruct);

        if (installed.isEmpty()) {
            SelfCheckRegistry.markStale(FEATURE,
                    MESSAGES_VIEW_CLASS + " constructor",
                    "no constructors found");
            return;
        }

        SelfCheckRegistry.markInstalled(FEATURE,
                MESSAGES_VIEW_CLASS + " constructor",
                installed.size());
    }

    // ──────────────────────────────────────────────────────────

    /** Chèn ImageView vào đầu MessagesView. Bỏ qua nếu đã có rồi. */
    private void injectOverlay(final ViewGroup parent) {
        if (parent.getTag(VIEW_TAG_KEY) != null) return;

        Bitmap bitmap = getOverlayBitmap(parent.getContext());
        if (bitmap == null) return;

        parent.setTag(VIEW_TAG_KEY, Boolean.TRUE);

        Context context = parent.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        int heightDp = HookConfig.getLevel(Tweaks.KEY_INBOX_IMAGE_HEIGHT);
        if (heightDp <= 0) heightDp = DEFAULT_BANNER_HEIGHT_DP;
        final int heightPx = (int) (heightDp * density);

        ImageView imageView = new ImageView(context);
        imageView.setImageBitmap(bitmap);
        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        imageView.setAdjustViewBounds(false);
        imageView.setClickable(true);
        imageView.setFocusable(true);

        // Chạm để thu gọn / mở rộng banner
        final boolean[] collapsed = {false};
        imageView.setOnClickListener(v -> {
            int from = collapsed[0] ? 0 : heightPx;
            int to   = collapsed[0] ? heightPx : 0;
            collapsed[0] = !collapsed[0];
            ValueAnimator anim = ValueAnimator.ofInt(from, to);
            anim.setDuration(COLLAPSE_DURATION_MS);
            anim.setInterpolator(new DecelerateInterpolator());
            anim.addUpdateListener(animation -> {
                ViewGroup.LayoutParams lp = v.getLayoutParams();
                if (lp != null) {
                    lp.height = (int) animation.getAnimatedValue();
                    v.setLayoutParams(lp);
                }
            });
            anim.start();
        });

        ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, heightPx);
        parent.addView(imageView, 0, lp);

        SelfCheckRegistry.incrementHit(FEATURE,
                MESSAGES_VIEW_CLASS + " constructor",
                "banner injected h=" + heightDp + "dp");
    }

    // ──────────────────────────────────────────────────────────

    /**
     * Tải Bitmap, ưu tiên cache. Thử nhiều đường dẫn để đảm bảo đọc được file
     * dù chạy trong process của Zalo.
     */
    private static Bitmap getOverlayBitmap(Context hostContext) {
        try {
            File imageFile = resolveImageFile(hostContext);
            if (imageFile == null || !imageFile.exists() || imageFile.length() == 0) {
                cachedBitmap = null;
                cachedBitmapModified = -1;
                return null;
            }
            long lastModified = imageFile.lastModified();
            if (cachedBitmap != null && !cachedBitmap.isRecycled()
                    && lastModified == cachedBitmapModified) {
                return cachedBitmap;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap bmp = BitmapFactory.decodeFile(imageFile.getAbsolutePath(), opts);
            if (bmp != null) {
                cachedBitmap = bmp;
                cachedBitmapModified = lastModified;
            }
            return bmp;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Tìm file ảnh theo thứ tự ưu tiên:
     * 1. /data/data/... (đường dẫn cố định, hoạt động khi setReadable)
     * 2. /data/user/0/... (Android multi-user)
     * 3. Module context qua resolveModuleContextForHooks
     * 4. createPackageContext từ host
     */
    private static File resolveImageFile(Context hostContext) {
        String[] directPaths = {
            "/data/data/"   + MODULE_PACKAGE + "/files/" + OVERLAY_IMAGE_FILENAME,
            "/data/user/0/" + MODULE_PACKAGE + "/files/" + OVERLAY_IMAGE_FILENAME,
        };
        for (String path : directPaths) {
            File f = new File(path);
            if (f.exists() && f.canRead()) return f;
        }

        try {
            Context moduleCtx = HookConfig.resolveModuleContextForHooks();
            if (moduleCtx != null) {
                File f = new File(moduleCtx.getFilesDir(), OVERLAY_IMAGE_FILENAME);
                if (f.exists()) return f;
            }
        } catch (Throwable ignored) { }

        try {
            Context moduleCtx = hostContext.createPackageContext(
                    MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY);
            File f = new File(moduleCtx.getFilesDir(), OVERLAY_IMAGE_FILENAME);
            if (f.exists()) return f;
        } catch (Throwable ignored) { }

        return null;
    }
}
