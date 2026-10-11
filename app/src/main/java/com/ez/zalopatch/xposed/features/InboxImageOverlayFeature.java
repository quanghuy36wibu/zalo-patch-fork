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
 * Pattern từ TikGoon: SectionActivity dùng root copy ảnh vào
 * /data/user/0/com.zing.zalo/files/ — hook đọc lại từ context.getFilesDir()
 * của chính Zalo, không cần cross-process file access.
 */
public final class InboxImageOverlayFeature extends Feature {

    private static final String FEATURE = Tweaks.KEY_INBOX_IMAGE_OVERLAY;
    private static final String MESSAGES_VIEW_CLASS =
            "com.zing.zalo.ui.maintab.msg.MessagesView";

    public static final String OVERLAY_IMAGE_FILENAME = "inbox_overlay.jpg";

    private static final int DEFAULT_BANNER_HEIGHT_DP = 120;
    private static final int COLLAPSE_DURATION_MS = 250;
    private static final int VIEW_TAG_KEY = 0x7a700002;

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

        Class<?> messagesViewClass = XpReflect.findClassIfExists(
                MESSAGES_VIEW_CLASS, classLoader);
        if (messagesViewClass == null) {
            SelfCheckRegistry.markStale(FEATURE,
                    MESSAGES_VIEW_CLASS + " constructor",
                    "MessagesView class not found");
            return;
        }

        List<XpHooks.Handle> installed = XpHooks.hookAllConstructors(
                messagesViewClass, null, new XpHooks.After() {
                    @Override
                    public void after(XpHooks.HookParam param) {
                        if (!HookConfig.isEnabled(FEATURE)) return;
                        if (!(param.thisObject instanceof ViewGroup)) return;
                        final ViewGroup mv = (ViewGroup) param.thisObject;
                        mv.post(() -> injectOverlay(mv));
                    }
                });

        if (installed.isEmpty()) {
            SelfCheckRegistry.markStale(FEATURE,
                    MESSAGES_VIEW_CLASS + " constructor",
                    "no constructors found");
            return;
        }
        SelfCheckRegistry.markInstalled(FEATURE,
                MESSAGES_VIEW_CLASS + " constructor", installed.size());
    }

    private void injectOverlay(ViewGroup parent) {
        if (parent.getTag(VIEW_TAG_KEY) != null) return;

        // Đọc ảnh từ Zalo's OWN files dir (được copy vào bằng root từ SectionActivity)
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
        imageView.setClickable(true);
        imageView.setFocusable(true);

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

        parent.addView(imageView, 0, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, heightPx));

        SelfCheckRegistry.incrementHit(FEATURE,
                MESSAGES_VIEW_CLASS + " constructor",
                "banner injected h=" + heightDp + "dp");
    }

    /**
     * Đọc ảnh từ Zalo's files directory — context ở đây là Zalo's own context
     * nên getFilesDir() trả về /data/user/0/com.zing.zalo/files/ mà không cần quyền đặc biệt.
     */
    private static Bitmap getOverlayBitmap(Context zaloContext) {
        try {
            File imageFile = new File(zaloContext.getFilesDir(), OVERLAY_IMAGE_FILENAME);
            if (!imageFile.exists() || imageFile.length() == 0) {
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
}
