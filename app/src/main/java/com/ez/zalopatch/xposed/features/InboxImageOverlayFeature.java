package com.ez.zalopatch.xposed.features;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;

import com.ez.zalopatch.HookConfig;
import com.ez.zalopatch.Tweaks;
import com.ez.zalopatch.xposed.core.Feature;
import com.ez.zalopatch.xposed.core.SelfCheckRegistry;
import com.ez.zalopatch.xposed.core.XpHooks;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Chèn hình ảnh tuỳ chọn vào đầu danh sách hội thoại chính (giao diện Tin nhắn).
 *
 * <p>Hook vào {@link LayoutInflater#inflate} tương tự {@link ZcloudBannerFeature}.
 * Khi layout {@code messageslist} được inflate, một {@link ImageView} được thêm vào
 * vị trí đầu tiên của ViewGroup gốc và tải ảnh từ file đã lưu trong thư mục của module.
 *
 * <p>Ảnh lưu tại {@code <module_files_dir>/inbox_overlay.jpg}. Chiều cao banner đọc từ
 * preference {@link Tweaks#KEY_INBOX_IMAGE_HEIGHT} (đơn vị dp).
 * Nhấn vào banner để thu gọn/mở rộng trong phiên làm việc.
 */
public final class InboxImageOverlayFeature extends Feature {

    /** Key cho SelfCheckRegistry. */
    private static final String FEATURE = Tweaks.KEY_INBOX_IMAGE_OVERLAY;

    /** Package Zalo để tra cứu resource id. */
    private static final String TARGET_PACKAGE = "com.zing.zalo";

    /** Tên layout chứa danh sách hội thoại chính. */
    private static final String MESSAGES_LIST_LAYOUT = "messageslist";

    /** Tên file ảnh lưu trong thư mục module. */
    public static final String OVERLAY_IMAGE_FILENAME = "inbox_overlay.jpg";

    /** Chiều cao banner mặc định (dp) — dùng khi preference chưa lưu. */
    private static final int DEFAULT_BANNER_HEIGHT_DP = 120;

    /** Thời gian animation thu gọn/mở rộng (ms). */
    private static final int COLLAPSE_DURATION_MS = 250;

    /**
     * Tag view để tránh thêm banner nhiều lần vào cùng một instance.
     * Dùng số nguyên cố định thay cho R.id để tránh phụ thuộc tài nguyên host.
     */
    private static final int VIEW_TAG_KEY = 0x7a700001; // 'zp' + 0x0001

    private static final AtomicBoolean OBSERVED = new AtomicBoolean(false);

    // Resource id — resolve một lần, cache mãi.
    private static final Object IDS_LOCK = new Object();
    private static volatile boolean idsResolved;
    private static volatile int messagesListId;

    // Bitmap cache — giữ trong bộ nhớ để không decode lại mỗi lần inflate.
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
        XpHooks.After onInflated = new XpHooks.After() {
            @Override
            public void after(XpHooks.HookParam param) {
                OBSERVED.set(true);

                if (!HookConfig.isEnabled(FEATURE)) return;

                if (!(param.getResult() instanceof View)
                        || param.args == null
                        || param.args.length == 0
                        || !(param.args[0] instanceof Integer)) {
                    return;
                }

                View root = (View) param.getResult();
                if (!(root instanceof ViewGroup)) return;
                if (!isMessagesListInflate(root, (Integer) param.args[0])) return;

                // Chỉ đánh dấu sau khi đã xác nhận ảnh hợp lệ; nếu ảnh được chọn sau đó,
                // lần inflate tiếp theo vẫn có thể chèn banner.
                if (root.getTag(VIEW_TAG_KEY) != null) return;
                Bitmap bitmap = getOverlayBitmap(root.getContext());
                if (bitmap == null) return;

                Context context = root.getContext();
                float density = context.getResources().getDisplayMetrics().density;
                int heightDp = HookConfig.getLevel(Tweaks.KEY_INBOX_IMAGE_HEIGHT);
                if (heightDp <= 0) heightDp = DEFAULT_BANNER_HEIGHT_DP;
                final int heightPx = (int) (heightDp * density);

                ImageView imageView = new ImageView(context);
                imageView.setImageBitmap(bitmap);
                int opacity = HookConfig.getLevel(Tweaks.KEY_INBOX_IMAGE_OPACITY);
                if (opacity <= 0 || opacity > 100) opacity = 100;
                imageView.setAlpha(opacity / 100f);
                imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                imageView.setAdjustViewBounds(false);
                imageView.setClickable(true);
                imageView.setFocusable(true);

                // Nhấn vào banner: thu gọn → mở rộng xen kẽ.
                final boolean[] collapsed = {false};
                imageView.setOnClickListener(v -> {
                    int targetH = collapsed[0] ? heightPx : 0;
                    int startH = collapsed[0] ? 0 : heightPx;
                    collapsed[0] = !collapsed[0];
                    ValueAnimator anim = ValueAnimator.ofInt(startH, targetH);
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
                ((ViewGroup) root).addView(imageView, 0, lp);
                root.setTag(VIEW_TAG_KEY, Boolean.TRUE);

                SelfCheckRegistry.incrementHit(FEATURE,
                        "LayoutInflater#inflate(messageslist)",
                        "image banner injected h=" + heightDp + "dp");
            }
        };

        List<XpHooks.Handle> installed = XpHooks.hookAllMethods(
                "image_overlay", LayoutInflater.class, "inflate", onInflated);

        if (installed.isEmpty()) {
            SelfCheckRegistry.markStale(FEATURE,
                    "LayoutInflater#inflate(messageslist)",
                    "no inflate overloads found");
            return;
        }
        if (!HookConfig.isEnabled(FEATURE)) {
            SelfCheckRegistry.markDisabled(FEATURE, "inbox image banner");
            return;
        }
        SelfCheckRegistry.markInstalled(FEATURE,
                "LayoutInflater#inflate(messageslist)", installed.size());
    }

    // ──────────────────────── helpers ────────────────────────

    /**
     * Nhận diện layout bằng resource id hoặc tên resource thực tế. Một số bản Zalo
     * inflate layout bằng id khác package hoặc resource id không tra được qua getIdentifier.
     */
    private static boolean isMessagesListInflate(View root, int layoutId) {
        ensureIds(root);
        if (messagesListId != 0 && layoutId == messagesListId) return true;
        try {
            String entry = root.getResources().getResourceEntryName(layoutId);
            String normalized = entry == null ? "" : entry.toLowerCase(java.util.Locale.ROOT);
            return MESSAGES_LIST_LAYOUT.equals(normalized)
                    || "message_list".equals(normalized)
                    || "fragment_messages_list".equals(normalized)
                    || "messages_list".equals(normalized);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Resolve và cache id layout {@code messageslist}. Thử lại nếu chưa tìm được. */
    private static void ensureIds(View root) {
        if (idsResolved) return;
        synchronized (IDS_LOCK) {
            if (idsResolved) return;
            try {
                messagesListId = root.getResources().getIdentifier(
                        MESSAGES_LIST_LAYOUT, "layout", TARGET_PACKAGE);
            } catch (Throwable ignored) {
            }
            idsResolved = messagesListId != 0;
        }
    }

    /**
     * Trả về {@link Bitmap} đã cache; tải lại nếu file thay đổi.
     * Cache được chia sẻ giữa các lần inflate trong cùng process.
     */
    private static Bitmap getOverlayBitmap(Context hostContext) {
        try {
            Context moduleCtx = HookConfig.resolveModuleContextForHooks();
            // resolveModuleContextForHooks có thể trả về context của Zalo khi module context
            // không truy cập được. Không được đọc filesDir của Zalo vì ảnh được lưu trong module.
            if (moduleCtx == null || !"com.ez.zalopatch".equals(moduleCtx.getPackageName())) {
                try {
                    moduleCtx = hostContext.createPackageContext(
                            "com.ez.zalopatch", Context.CONTEXT_IGNORE_SECURITY);
                } catch (Throwable ignored) {
                    return null;
                }
            }
            File imageFile = new File(moduleCtx.getFilesDir(), OVERLAY_IMAGE_FILENAME);
            if (!imageFile.exists() || imageFile.length() == 0) {
                cachedBitmap = null;
                cachedBitmapModified = -1;
                return null;
            }
            long lastModified = imageFile.lastModified();
            if (cachedBitmap != null && lastModified == cachedBitmapModified) {
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
}
