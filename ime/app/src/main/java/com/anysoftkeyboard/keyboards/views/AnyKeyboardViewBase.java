/*
 * Copyright (c) 2013 Menny Even-Danan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.anysoftkeyboard.keyboards.views;

import static com.anysoftkeyboard.overlay.OverlyDataCreatorForAndroid.OS_SUPPORT_FOR_ACCENT;
import static com.menny.android.anysoftkeyboard.AnyApplication.getKeyboardThemeFactory;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.Align;
import android.graphics.Paint.FontMetrics;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.NinePatchDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.text.Layout.Alignment;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Toast;
import androidx.annotation.CallSuper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.collection.ArrayMap;
import androidx.core.view.ViewCompat;
import com.anysoftkeyboard.addons.AddOn;
import com.anysoftkeyboard.addons.DefaultAddOn;
import com.anysoftkeyboard.api.KeyCodes;
import com.anysoftkeyboard.base.utils.CompatUtils;
import com.anysoftkeyboard.base.utils.Logger;
import com.anysoftkeyboard.ime.InputViewBinder;
import com.anysoftkeyboard.keyboards.AnyKeyboard;
import com.anysoftkeyboard.keyboards.AnyKeyboard.AnyKey;
import com.anysoftkeyboard.keyboards.GenericKeyboard;
import com.anysoftkeyboard.keyboards.Keyboard;
import com.anysoftkeyboard.keyboards.KeyboardDimens;
import com.anysoftkeyboard.keyboards.KeyboardSupport;
import com.anysoftkeyboard.keyboards.views.preview.KeyPreviewsController;
import com.anysoftkeyboard.keyboards.views.preview.NullKeyPreviewsManager;
import com.anysoftkeyboard.keyboards.views.preview.PreviewPopupTheme;
import com.anysoftkeyboard.overlay.OverlayData;
import com.anysoftkeyboard.overlay.OverlayDataImpl;
import com.anysoftkeyboard.overlay.ThemeOverlayCombiner;
import com.anysoftkeyboard.overlay.ThemeResourcesHolder;
import com.anysoftkeyboard.prefs.AnimationsLevel;
import com.anysoftkeyboard.prefs.RxSharedPrefs;
import com.anysoftkeyboard.rx.GenericOnError;
import com.anysoftkeyboard.theme.KeyboardTheme;
import com.anysoftkeyboard.utils.EmojiUtils;
import com.menny.android.anysoftkeyboard.AnyApplication;
import com.menny.android.anysoftkeyboard.BuildConfig;
import com.menny.android.anysoftkeyboard.R;
import io.reactivex.Observable;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.subjects.BehaviorSubject;
import io.reactivex.subjects.Subject;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public class AnyKeyboardViewBase extends View implements InputViewBinder, PointerTracker.UIProxy {
  // Miscellaneous constants
  public static final int NOT_A_KEY = -1;
  static final String TAG = "ASKKbdViewBase";
  private static final int[] ACTION_KEY_TYPES =
      new int[] {R.attr.action_done, R.attr.action_search, R.attr.action_go};
  private static final int[] KEY_TYPES =
      new int[] {R.attr.key_type_function, R.attr.key_type_action};
  private static final long TWO_FINGERS_LINGER_TIME = 30;
  protected final DefaultAddOn mDefaultAddOn;

  /** The canvas for the above mutable keyboard bitmap */
  // private Canvas mCanvas;
  protected final Paint mPaint;

  @NonNull protected final KeyboardDimensFromTheme mKeyboardDimens = new KeyboardDimensFromTheme();

  protected final PreviewPopupTheme mPreviewPopupTheme = new PreviewPopupTheme();
  protected final KeyPressTimingHandler mKeyPressTimingHandler;
  // TODO: Let the PointerTracker class manage this pointer queue
  final PointerQueue mPointerQueue = new PointerQueue();
  // Timing constants
  private final int mKeyRepeatInterval;
  /* keys icons */
  private final SparseArray<DrawableBuilder> mKeysIconBuilders = new SparseArray<>(64);
  private final SparseArray<Drawable> mKeysIcons = new SparseArray<>(64);

  @NonNull protected final PointerTracker.SharedPointerTrackersData mSharedPointerTrackersData =
      new PointerTracker.SharedPointerTrackersData();

  private final SparseArray<PointerTracker> mPointerTrackers = new SparseArray<>();
  @NonNull private final KeyDetector mKeyDetector;

  /** The dirty region in the keyboard bitmap */
  private final Rect mDirtyRect = new Rect();

  private final Rect mKeyBackgroundPadding;
  private final Rect mClipRegion = new Rect(0, 0, 0, 0);
  private final Map<TextWidthCacheKey, TextWidthCacheValue> mTextWidthCache = new ArrayMap<>();
  protected final CompositeDisposable mDisposables = new CompositeDisposable();

  /** Listener for {@link OnKeyboardActionListener}. */
  protected OnKeyboardActionListener mKeyboardActionListener;

  @Nullable private KeyboardTheme mLastSetTheme = null;

  /** Notes if the keyboard just changed, so that we could possibly reallocate the mBuffer. */
  protected boolean mKeyboardChanged;

  protected float mBackgroundDimAmount;
  protected float mOriginalVerticalCorrection;
  protected CharSequence mNextAlphabetKeyboardName;
  protected CharSequence mNextSymbolsKeyboardName;
  int mSwipeVelocityThreshold;
  int mSwipeXDistanceThreshold;
  int mSwipeYDistanceThreshold;
  int mSwipeSpaceXDistanceThreshold;
  int mKeyboardActionType = EditorInfo.IME_ACTION_UNSPECIFIED;
  private KeyDrawableStateProvider mDrawableStatesProvider;
  // XML attribute
  private float mKeyTextSize;
  private FontMetrics mTextFontMetrics;
  private Typeface mKeyTextStyle = Typeface.DEFAULT;
  private float mLabelTextSize;
  private FontMetrics mLabelFontMetrics;
  private float mKeyboardNameTextSize;
  private FontMetrics mKeyboardNameFontMetrics;
  private float mHintTextSize;
  float mHintTextSizeMultiplier;
  private FontMetrics mHintTextFontMetrics;
  private int mThemeHintLabelAlign;
  private int mThemeHintLabelVAlign;
  private int mShadowColor;
  private int mShadowRadius;
  private int mShadowOffsetX;
  private int mShadowOffsetY;
  // Main keyboard
  private AnyKeyboard mKeyboard;
  private CharSequence mKeyboardName;

  // Drawing
  private Keyboard.Key[] mKeys;
  private KeyPreviewsController mKeyPreviewsManager = new NullKeyPreviewsManager();
  private long mLastTimeHadTwoFingers = 0;

  private Keyboard.Key mInvalidatedKey;
  private boolean mTouchesAreDisabledTillLastFingerIsUp = false;
  private int mTextCaseForceOverrideType;
  private int mTextCaseType;

  protected boolean mAlwaysUseDrawText;

  private boolean mShowKeyboardNameOnKeyboard;
  private boolean mShowHintsOnKeyboard;
  private int mCustomHintGravity;
  private final float mDisplayDensity;
  protected final Subject<AnimationsLevel> mAnimationLevelSubject =
      BehaviorSubject.createDefault(AnimationsLevel.Some);
  private float mKeysHeightFactor = 1f;
  @NonNull protected OverlayData mThemeOverlay = new OverlayDataImpl();
  // overrideable theme resources
  private final ThemeOverlayCombiner mThemeOverlayCombiner = new ThemeOverlayCombiner();

  public AnyKeyboardViewBase(Context context, AttributeSet attrs) {
    this(context, attrs, R.style.PlainLightAnySoftKeyboard);
  }

  public AnyKeyboardViewBase(Context context, AttributeSet attrs, int defStyle) {
    super(context, attrs, defStyle);
    setWillNotDraw(true /*starting with not-drawing. Once keyboard and theme are set we'll draw*/);

    mDisplayDensity = getResources().getDisplayMetrics().density;
    mDefaultAddOn = new DefaultAddOn(context, context);

    mKeyPressTimingHandler = new KeyPressTimingHandler(this);

    mPaint = new Paint();
    mPaint.setAntiAlias(true);
    mPaint.setTextAlign(Align.CENTER);
    mPaint.setAlpha(255);

    mKeyBackgroundPadding = new Rect(0, 0, 0, 0);

    final Resources res = getResources();

    final float slide = res.getDimension(R.dimen.mini_keyboard_slide_allowance);
    mKeyDetector = createKeyDetector(slide);

    mKeyRepeatInterval = 50;

    mNextAlphabetKeyboardName = getResources().getString(R.string.change_lang_regular);
    mNextSymbolsKeyboardName = getResources().getString(R.string.change_symbols_regular);

    final RxSharedPrefs rxSharedPrefs = AnyApplication.prefs(context);

    mDisposables.add(
        rxSharedPrefs
            .getBoolean(
                R.string.settings_key_show_keyboard_name_text_key,
                R.bool.settings_default_show_keyboard_name_text_value)
            .asObservable()
            .subscribe(
                value -> mShowKeyboardNameOnKeyboard = value,
                GenericOnError.onError(
                    "failed to get" + " settings_default_show_keyboard_name_text_value")));
    mDisposables.add(
        rxSharedPrefs
            .getBoolean(
                R.string.settings_key_show_hint_text_key,
                R.bool.settings_default_show_hint_text_value)
            .asObservable()
            .subscribe(
                value -> mShowHintsOnKeyboard = value,
                GenericOnError.onError("failed to get settings_default_show_hint_text_value")));

    mDisposables.add(
        Observable.combineLatest(
                rxSharedPrefs
                    .getBoolean(
                        R.string.settings_key_use_custom_hint_align_key,
                        R.bool.settings_default_use_custom_hint_align_value)
                    .asObservable(),
                rxSharedPrefs
                    .getString(
                        R.string.settings_key_custom_hint_align_key,
                        R.string.settings_default_custom_hint_align_value)
                    .asObservable()
                    .map(Integer::parseInt),
                rxSharedPrefs
                    .getString(
                        R.string.settings_key_custom_hint_valign_key,
                        R.string.settings_default_custom_hint_valign_value)
                    .asObservable()
                    .map(Integer::parseInt),
                (enabled, align, verticalAlign) -> {
                  if (enabled) {
                    return align | verticalAlign;
                  } else {
                    return Gravity.NO_GRAVITY;
                  }
                })
            .subscribe(
                calculatedGravity -> mCustomHintGravity = calculatedGravity,
                GenericOnError.onError("failed to get calculate hint-gravity")));

    mDisposables.add(
        rxSharedPrefs
            .getString(
                R.string.settings_key_swipe_distance_threshold,
                R.string.settings_default_swipe_distance_threshold)
            .asObservable()
            .map(Integer::parseInt)
            .subscribe(
                integer -> {
                  mSwipeXDistanceThreshold = (int) (integer * mDisplayDensity);
                  calculateSwipeDistances();
                },
                GenericOnError.onError("failed to get settings_key_swipe_distance_threshold")));
    mDisposables.add(
        rxSharedPrefs
            .getString(
                R.string.settings_key_swipe_velocity_threshold,
                R.string.settings_default_swipe_velocity_threshold)
            .asObservable()
            .map(Integer::parseInt)
            .subscribe(
                integer -> mSwipeVelocityThreshold = (int) (integer * mDisplayDensity),
                GenericOnError.onError(
                    "failed to get" + " settings_default_swipe_velocity_threshold")));
    mDisposables.add(
        rxSharedPrefs
            .getString(
                R.string.settings_key_theme_case_type_override,
                R.string.settings_default_theme_case_type_override)
            .asObservable()
            .subscribe(
                this::updatePrefSettings,
                GenericOnError.onError("failed to get settings_key_theme_case_type_override")));
    mDisposables.add(
        rxSharedPrefs
            .getBoolean(
                R.string.settings_key_workaround_disable_rtl_fix,
                R.bool.settings_default_workaround_disable_rtl_fix)
            .asObservable()
            .subscribe(
                value -> mAlwaysUseDrawText = value,
                GenericOnError.onError("failed to get settings_key_workaround_disable_rtl_fix")));

    mDisposables.add(
        KeyboardSupport.getKeyboardHeightFactor(context)
            .subscribe(
                factor -> {
                  mKeysHeightFactor = factor;
                  mTextWidthCache.clear();
                  invalidateAllKeys();
                },
                GenericOnError.onError("Failed to getKeyboardHeightFactor")));

    mDisposables.add(
        rxSharedPrefs
            .getString(R.string.settings_key_hint_size, R.string.settings_key_hint_size_default)
            .asObservable()
            .subscribe(
                this::updatePrefSettingsHintTextSizeFactor,
                GenericOnError.onError("failed to get settings_key_hint_size")));

    mDisposables.add(
        AnimationsLevel.createPrefsObservable(context)
            .subscribe(
                mAnimationLevelSubject::onNext, GenericOnError.onError("mAnimationLevelSubject")));

    mDisposables.add(
        rxSharedPrefs
            .getString(
                R.string.settings_key_long_press_timeout,
                R.string.settings_default_long_press_timeout)
            .asObservable()
            .map(Integer::parseInt)
            .subscribe(
                value ->
                    mSharedPointerTrackersData.delayBeforeKeyRepeatStart =
                        mSharedPointerTrackersData.longPressKeyTimeout = value,
                GenericOnError.onError("failed to get settings_key_long_press_timeout")));
    mDisposables.add(
        rxSharedPrefs
            .getString(
                R.string.settings_key_long_press_timeout,
                R.string.settings_default_long_press_timeout)
            .asObservable()
            .map(Integer::parseInt)
            .subscribe(
                value ->
                    mSharedPointerTrackersData.delayBeforeKeyRepeatStart =
                        mSharedPointerTrackersData.longPressKeyTimeout = value,
                GenericOnError.onError("failed to get settings_key_long_press_timeout")));
    mDisposables.add(
        rxSharedPrefs
            .getString(
                R.string.settings_key_multitap_timeout, R.string.settings_default_multitap_timeout)
            .asObservable()
            .map(Integer::parseInt)
            .subscribe(
                value -> mSharedPointerTrackersData.multiTapKeyTimeout = value,
                GenericOnError.onError("failed to get settings_key_multitap_timeout")));
  }

  protected static boolean isSpaceKey(final AnyKey key) {
    return key.getPrimaryCode() == KeyCodes.SPACE;
  }

  public boolean areTouchesDisabled(MotionEvent motionEvent) {
    if (motionEvent != null && mTouchesAreDisabledTillLastFingerIsUp) {
      // calculate new value for mTouchesAreDisabledTillLastFingerIsUp
      // when do we reset the mTouchesAreDisabledTillLastFingerIsUp flag:
      // Only if we have a single pointer
      // and:
      // CANCEL - the single pointer has been cancelled. So no pointers
      // UP - the single pointer has been lifted. So now we have no pointers down.
      // DOWN - this is the first action from the single pointer, so we already were in
      // no-pointers down state.
      final int action = motionEvent.getActionMasked();
      if (motionEvent.getPointerCount() == 1
          && (action == MotionEvent.ACTION_CANCEL
              || action == MotionEvent.ACTION_DOWN
              || action == MotionEvent.ACTION_UP)) {
        mTouchesAreDisabledTillLastFingerIsUp = false;
        // If the action is UP then we will return the previous value (which is TRUE), since
        // the motion events are disabled until AFTER
        // the UP event, so if this event resets the flag, this event should still be
        // disregarded.
        return action == MotionEvent.ACTION_UP;
      }
    }
    return mTouchesAreDisabledTillLastFingerIsUp;
  }

  @Override
  public boolean isAtTwoFingersState() {
    // this is a hack, I know.
    // I know that this is a swipe ONLY after the second finger is up, so I already lost the
    // two-fingers count in the motion event.
    return SystemClock.elapsedRealtime() - mLastTimeHadTwoFingers < TWO_FINGERS_LINGER_TIME;
  }

  @CallSuper
  public void disableTouchesTillFingersAreUp() {
    mKeyPressTimingHandler.cancelAllMessages();
    mKeyPreviewsManager.cancelAllPreviews();

    for (int trackerIndex = 0, trackersCount = mPointerTrackers.size();
        trackerIndex < trackersCount;
        trackerIndex++) {
      PointerTracker tracker = mPointerTrackers.valueAt(trackerIndex);
      sendOnXEvent(MotionEvent.ACTION_CANCEL, 0, 0, 0, tracker);
      tracker.setAlreadyProcessed();
    }

    mTouchesAreDisabledTillLastFingerIsUp = true;
  }

  @Nullable protected KeyboardTheme getLastSetKeyboardTheme() {
    return mLastSetTheme;
  }

  @SuppressWarnings("ReferenceEquality")
  @Override
  public void setKeyboardTheme(@NonNull KeyboardTheme theme) {
    if (theme == mLastSetTheme) return;

    clearKeyIconsCache(true);
    mKeysIconBuilders.clear();
    mTextWidthCache.clear();
    mLastSetTheme = theme;
    if (mKeyboard != null) setWillNotDraw(false);

    // the new theme might be of a different size
    requestLayout();
    // Hint to reallocate the buffer if the size changed
    mKeyboardChanged = true;
    invalidateAllKeys();

    final int keyboardThemeStyleResId = getKeyboardStyleResId(theme);

    final int[] remoteKeyboardThemeStyleable =
        theme
            .getResourceMapping()
            .getRemoteStyleableArrayFromLocal(R.styleable.AnyKeyboardViewTheme);
    final int[] remoteKeyboardIconsThemeStyleable =
        theme
            .getResourceMapping()
            .getRemoteStyleableArrayFromLocal(R.styleable.AnyKeyboardViewIconsTheme);

    final int[] padding = new int[] {0, 0, 0, 0};

    int keyTypeFunctionAttrId = R.attr.key_type_function;
    int keyActionAttrId = R.attr.key_type_action;
    int keyActionTypeDoneAttrId = R.attr.action_done;
    int keyActionTypeSearchAttrId = R.attr.action_search;
    int keyActionTypeGoAttrId = R.attr.action_go;

    HashSet<Integer> doneLocalAttributeIds = new HashSet<>();
    TypedArray a =
        theme
            .getPackageContext()
            .obtainStyledAttributes(keyboardThemeStyleResId, remoteKeyboardThemeStyleable);
    final int n = a.getIndexCount();
    for (int i = 0; i < n; i++) {
      final int remoteIndex = a.getIndex(i);
      final int localAttrId =
          theme.getResourceMapping().getLocalAttrId(remoteKeyboardThemeStyleable[remoteIndex]);

      if (setValueFromThemeInternal(a, padding, localAttrId, remoteIndex)) {
        doneLocalAttributeIds.add(localAttrId);
        if (localAttrId == R.attr.keyBackground) {
          // keyTypeFunctionAttrId and keyActionAttrId are remote
          final int[] keyStateAttributes =
              theme.getResourceMapping().getRemoteStyleableArrayFromLocal(KEY_TYPES);
          keyTypeFunctionAttrId = keyStateAttributes[0];
          keyActionAttrId = keyStateAttributes[1];
        }
      }
    }
    a.recycle();
    // taking icons
    int iconSetStyleRes = getKeyboardIconsStyleResId(theme);
    if (iconSetStyleRes != 0) {
      a =
          theme
              .getPackageContext()
              .obtainStyledAttributes(iconSetStyleRes, remoteKeyboardIconsThemeStyleable);
      final int iconsCount = a.getIndexCount();
      for (int i = 0; i < iconsCount; i++) {
        final int remoteIndex = a.getIndex(i);
        final int localAttrId =
            theme
                .getResourceMapping()
                .getLocalAttrId(remoteKeyboardIconsThemeStyleable[remoteIndex]);

        if (setKeyIconValueFromTheme(theme, a, localAttrId, remoteIndex)) {
          doneLocalAttributeIds.add(localAttrId);
          if (localAttrId == R.attr.iconKeyAction) {
            // keyActionTypeDoneAttrId and keyActionTypeSearchAttrId and
            // keyActionTypeGoAttrId are remote
            final int[] keyStateAttributes =
                theme.getResourceMapping().getRemoteStyleableArrayFromLocal(ACTION_KEY_TYPES);
            keyActionTypeDoneAttrId = keyStateAttributes[0];
            keyActionTypeSearchAttrId = keyStateAttributes[1];
            keyActionTypeGoAttrId = keyStateAttributes[2];
          }
        }
      }
      a.recycle();
    }
    // filling what's missing
    KeyboardTheme fallbackTheme = getKeyboardThemeFactory(getContext()).getFallbackTheme();
    final int keyboardFallbackThemeStyleResId = getKeyboardStyleResId(fallbackTheme);
    a =
        fallbackTheme
            .getPackageContext()
            .obtainStyledAttributes(
                keyboardFallbackThemeStyleResId, R.styleable.AnyKeyboardViewTheme);

    final int fallbackCount = a.getIndexCount();
    for (int i = 0; i < fallbackCount; i++) {
      final int index = a.getIndex(i);
      final int attrId = R.styleable.AnyKeyboardViewTheme[index];
      if (doneLocalAttributeIds.contains(attrId)) {
        continue;
      }
      setValueFromThemeInternal(a, padding, attrId, index);
    }
    a.recycle();
    // taking missing icons
    int fallbackIconSetStyleId = fallbackTheme.getIconsThemeResId();
    a =
        fallbackTheme
            .getPackageContext()
            .obtainStyledAttributes(fallbackIconSetStyleId, R.styleable.AnyKeyboardViewIconsTheme);

    final int fallbackIconsCount = a.getIndexCount();
    for (int i = 0; i < fallbackIconsCount; i++) {
      final int index = a.getIndex(i);
      final int attrId = R.styleable.AnyKeyboardViewIconsTheme[index];
      if (doneLocalAttributeIds.contains(attrId)) {
        continue;
      }
      setKeyIconValueFromTheme(fallbackTheme, a, attrId, index);
    }
    a.recycle();
    // creating the key-drawable state provider, as we suppose to have the entire data now
    mDrawableStatesProvider =
        new KeyDrawableStateProvider(
            keyTypeFunctionAttrId,
            keyActionAttrId,
            keyActionTypeDoneAttrId,
            keyActionTypeSearchAttrId,
            keyActionTypeGoAttrId);

    // settings.
    // don't forget that there are THREE padding,
    // the theme's and the
    // background image's padding and the
    // View
    Drawable keyboardBackground = super.getBackground();
    if (keyboardBackground != null) {
      Rect backgroundPadding = new Rect();
      keyboardBackground.getPadding(backgroundPadding);
      padding[0] += backgroundPadding.left;
      padding[1] += backgroundPadding.top;
      padding[2] += backgroundPadding.right;
      padding[3] += backgroundPadding.bottom;
    }
    setPadding(padding[0], padding[1], padding[2], padding[3]);

    final Resources res = getResources();
    final int viewWidth = (getWidth() > 0) ? getWidth() : res.getDisplayMetrics().widthPixels;
    mKeyboardDimens.setKeyboardMaxWidth(viewWidth - padding[0] - padding[2]);
    mPaint.setTextSize(mKeyTextSize);
  }

  @Override
  @CallSuper
  public void setThemeOverlay(OverlayData overlay) {
    mThemeOverlay = overlay;
    if (OS_SUPPORT_FOR_ACCENT) {
      clearKeyIconsCache(true);
      mThemeOverlayCombiner.setOverlayData(overlay);
      final ThemeResourcesHolder themeResources = mThemeOverlayCombiner.getThemeResources();
      ViewCompat.setBackground(this, themeResources.getKeyboardBackground());
      invalidateAllKeys();
    }
  }

  protected KeyDetector createKeyDetector(final float slide) {
    return new MiniKeyboardKeyDetector(slide);
  }

  private boolean setValueFromThemeInternal(
      TypedArray remoteTypedArray, int[] padding, int localAttrId, int remoteTypedArrayIndex) {
    try {
      return setValueFromTheme(remoteTypedArray, padding, localAttrId, remoteTypedArrayIndex);
    } catch (RuntimeException e) {
      Logger.w(
          TAG,
          e,
          "Failed to parse resource with local id  %s, and remote index %d",
          localAttrId,
          remoteTypedArrayIndex);
      if (BuildConfig.DEBUG) throw e;
      return false;
    }
  }

  protected boolean setValueFromTheme(
      TypedArray remoteTypedArray,
      final int[] padding,
      final int localAttrId,
      final int remoteTypedArrayIndex) {
    switch (localAttrId) {
      case android.R.attr.background -> {
        Drawable keyboardBackground = remoteTypedArray.getDrawable(remoteTypedArrayIndex);
        if (keyboardBackground == null) return false;
        mThemeOverlayCombiner.setThemeKeyboardBackground(keyboardBackground);
        ViewCompat.setBackground(
            this, mThemeOverlayCombiner.getThemeResources().getKeyboardBackground());
      }
      case android.R.attr.paddingLeft -> {
        padding[0] = remoteTypedArray.getDimensionPixelSize(remoteTypedArrayIndex, -1);
        if (padding[0] == -1) return false;
      }
      case android.R.attr.paddingTop -> {
        padding[1] = remoteTypedArray.getDimensionPixelSize(remoteTypedArrayIndex, -1);
        if (padding[1] == -1) return false;
      }
      case android.R.attr.paddingRight -> {
        padding[2] = remoteTypedArray.getDimensionPixelSize(remoteTypedArrayIndex, -1);
        if (padding[2] == -1) return false;
      }
      case android.R.attr.paddingBottom -> {
        padding[3] = remoteTypedArray.getDimensionPixelSize(remoteTypedArrayIndex, -1);
        if (padding[3] == -1) return false;
        mKeyboardDimens.setPaddingBottom(padding[3]);
      }
      case R.attr.keyBackground -> {
        Drawable keyBackground = remoteTypedArray.getDrawable(remoteTypedArrayIndex);
        if (keyBackground == null) {
          return false;
        } else {
          mThemeOverlayCombiner.setThemeKeyBackground(keyBackground);
        }
      }
      case R.attr.verticalCorrection -> {
        mOriginalVerticalCorrection =
            remoteTypedArray.getDimensionPixelOffset(remoteTypedArrayIndex, -1);
        if (mOriginalVerticalCorrection == -1) return false;
      }
      case R.attr.keyTextSize -> {
        mKeyTextSize = remoteTypedArray.getDimensionPixelSize(remoteTypedArrayIndex, -1);
        if (mKeyTextSize == -1) return false;
        mKeyTextSize = mKeyTextSize * mKeysHeightFactor;
        Logger.d(TAG, "AnySoftKeyboardTheme_keyTextSize " + mKeyTextSize);
      }
      case R.attr.keyTextColor -> {
        ColorStateList keyTextColor = remoteTypedArray.getColorStateList(remoteTypedArrayIndex);
        if (keyTextColor == null) {
          keyTextColor =
              new ColorStateList(
                  new int[][] {{0}},
                  new int[] {remoteTypedArray.getColor(remoteTypedArrayIndex, 0xFF000000)});
        }
        mThemeOverlayCombiner.setThemeTextColor(keyTextColor);
      }
      case R.attr.labelTextSize -> {
        mLabelTextSize = remoteTypedArray.getDimensionPixelSize(remoteTypedArrayIndex, -1);
        if (mLabelTextSize == -1) return false;
        mLabelTextSize *= mKeysHeightFactor;
      }
      case R.attr.keyboardNameTextSize -> {
        mKeyboardNameTextSize = remoteTypedArray.getDimensionPixelSize(remoteTypedArrayIndex, -1);
        if (mKeyboardNameTextSize == -1) return false;
        mKeyboardNameTextSize *= mKeysHeightFactor;
      }
      case R.attr.keyboardNameTextColor ->
          mThemeOverlayCombiner.setThemeNameTextColor(
              remoteTypedArray.getColor(remoteTypedArrayIndex, Color.WHITE));
      case R.attr.shadowColor -> mShadowColor = remoteTypedArray.getColor(remoteTypedArrayIndex, 0);
      case R.attr.shadowRadius ->
          mShadowRadius = remoteTypedArray.getDimensionPixelOffset(remoteTypedArrayIndex, 0);
      case R.attr.shadowOffsetX ->
          mShadowOffsetX = remoteTypedArray.getDimensionPixelOffset(remoteTypedArrayIndex, 0);
      case R.attr.shadowOffsetY ->
          mShadowOffsetY = remoteTypedArray.getDimensionPixelOffset(remoteTypedArrayIndex, 0);
      case R.attr.backgroundDimAmount -> {
        mBackgroundDimAmount = remoteTypedArray.getFloat(remoteTypedArrayIndex, -1f);
        if (mBackgroundDimAmount == -1f) return false;
      }
      case R.attr.keyPreviewBackground -> {
        Drawable keyPreviewBackground = remoteTypedArray.getDrawable(remoteTypedArrayIndex);
        if (keyPreviewBackground == null) return false;
        mPreviewPopupTheme.setPreviewKeyBackground(keyPreviewBackground);
      }
      case R.attr.keyPreviewTextColor ->
          mPreviewPopupTheme.setPreviewKeyTextColor(
              remoteTypedArray.getColor(remoteTypedArrayIndex, 0xFFF));
      case R.attr.keyPreviewTextSize -> {
        int keyPreviewTextSize = remoteTypedArray.getDimensionPixelSize(remoteTypedArrayIndex, -1);
        if (keyPreviewTextSize == -1) return false;
        keyPreviewTextSize = (int) (keyPreviewTextSize * mKeysHeightFactor);
        mPreviewPopupTheme.setPreviewKeyTextSize(keyPreviewTextSize);
      }
      case R.attr.keyPreviewLabelTextSize -> {
        int keyPreviewLabelTextSize =
            remoteTypedArray.getDimensionPixelSize(remoteTypedArrayIndex, -1);
        if (keyPreviewLabelTextSize == -1) return false;
        keyPreviewLabelTextSize = (int) (keyPreviewLabelTextSize * mKeysHeightFactor);
        mPreviewPopupTheme.setPreviewLabelTextSize(keyPreviewLabelTextSize);
      }
      case R.attr.keyPreviewOffset ->
          mPreviewPopupTheme.setVerticalOffset(
              remoteTypedArray.getDimensionPixelOffset(remoteTypedArrayIndex, 0));
      case R.attr.previewAnimationType -> {
        int previewAnimationType = remoteTypedArray.getInteger(remoteTypedArrayIndex, -1);
        if (previewAnimationType == -1) return false;
        mPreviewPopupTheme.setPreviewAnimationType(previewAnimationType);
      }
      case R.attr.keyTextStyle -> {
        int textStyle = remoteTypedArray.getInt(remoteTypedArrayIndex, 0);
        switch (textStyle) {
          case 0 -> mKeyTextStyle = Typeface.DEFAULT;
          case 1 -> mKeyTextStyle = Typeface.DEFAULT_BOLD;
          case 2 -> mKeyTextStyle = Typeface.defaultFromStyle(Typeface.ITALIC);
          default -> mKeyTextStyle = Typeface.defaultFromStyle(textStyle);
        }
        mPreviewPopupTheme.setKeyStyle(mKeyTextStyle);
      }
      case R.attr.keyHorizontalGap -> {
        float themeHorizontalKeyGap =
            remoteTypedArray.getDimensionPixelOffset(remoteTypedArrayIndex, -1);
        if (themeHorizontalKeyGap == -1) return false;
        mKeyboardDimens.setHorizontalKeyGap(themeHorizontalKeyGap);
      }
      case R.attr.keyVerticalGap -> {
        float themeVerticalRowGap =
            remoteTypedArray.getDimensionPixelOffset(remoteTypedArrayIndex, -1);
        if (themeVerticalRowGap == -1) return false;
        mKeyboardDimens.setVerticalRowGap(themeVerticalRowGap);
      }
      case R.attr.keyNormalHeight -> {
        int themeNormalKeyHeight =
            remoteTypedArray.getDimensionPixelOffset(remoteTypedArrayIndex, -1);
        if (themeNormalKeyHeight == -1) return false;
        mKeyboardDimens.setNormalKeyHeight(themeNormalKeyHeight);
      }
      case R.attr.keyLargeHeight -> {
        int themeLargeKeyHeight =
            remoteTypedArray.getDimensionPixelOffset(remoteTypedArrayIndex, -1);
        if (themeLargeKeyHeight == -1) return false;
        mKeyboardDimens.setLargeKeyHeight(themeLargeKeyHeight);
      }
      case R.attr.keySmallHeight -> {
        int themeSmallKeyHeight =
            remoteTypedArray.getDimensionPixelOffset(remoteTypedArrayIndex, -1);
        if (themeSmallKeyHeight == -1) return false;
        mKeyboardDimens.setSmallKeyHeight(themeSmallKeyHeight);
      }
      case R.attr.hintTextSize -> {
        mHintTextSize = remoteTypedArray.getDimensionPixelSize(remoteTypedArrayIndex, -1);
        if (mHintTextSize == -1) return false;
        mHintTextSize *= mKeysHeightFactor;
      }
      case R.attr.hintTextColor ->
          mThemeOverlayCombiner.setThemeHintTextColor(
              remoteTypedArray.getColor(remoteTypedArrayIndex, 0xFF000000));
      case R.attr.hintLabelVAlign ->
          mThemeHintLabelVAlign = remoteTypedArray.getInt(remoteTypedArrayIndex, Gravity.BOTTOM);
      case R.attr.hintLabelAlign ->
          mThemeHintLabelAlign = remoteTypedArray.getInt(remoteTypedArrayIndex, Gravity.RIGHT);
      case R.attr.keyTextCaseStyle ->
          mTextCaseType = remoteTypedArray.getInt(remoteTypedArrayIndex, 0);
    }
    return true;
  }

  private boolean setKeyIconValueFromTheme(
      KeyboardTheme theme,
      TypedArray remoteTypeArray,
      final int localAttrId,
      final int remoteTypedArrayIndex) {
    final int keyCode =
        switch (localAttrId) {
          case R.attr.iconKeyShift -> KeyCodes.SHIFT;
          case R.attr.iconKeyControl -> KeyCodes.CTRL;
          case R.attr.iconKeyAction -> KeyCodes.ENTER;
          case R.attr.iconKeyBackspace -> KeyCodes.DELETE;
          case R.attr.iconKeyCancel -> KeyCodes.CANCEL;
          case R.attr.iconKeyGlobe -> KeyCodes.MODE_ALPHABET;
          case R.attr.iconKeySpace -> KeyCodes.SPACE;
          case R.attr.iconKeyTab -> KeyCodes.TAB;
          case R.attr.iconKeyArrowDown -> KeyCodes.ARROW_DOWN;
          case R.attr.iconKeyArrowLeft -> KeyCodes.ARROW_LEFT;
          case R.attr.iconKeyArrowRight -> KeyCodes.ARROW_RIGHT;
          case R.attr.iconKeyArrowUp -> KeyCodes.ARROW_UP;
          case R.attr.iconKeyInputMoveHome -> KeyCodes.MOVE_HOME;
          case R.attr.iconKeyInputMoveEnd -> KeyCodes.MOVE_END;
          case R.attr.iconKeyMic -> KeyCodes.VOICE_INPUT;
          case R.attr.iconKeySettings -> KeyCodes.SETTINGS;
          case R.attr.iconKeyCondenseNormal -> KeyCodes.MERGE_LAYOUT;
          case R.attr.iconKeyCondenseSplit -> KeyCodes.SPLIT_LAYOUT;
          case R.attr.iconKeyCondenseCompactToRight -> KeyCodes.COMPACT_LAYOUT_TO_RIGHT;
          case R.attr.iconKeyCondenseCompactToLeft -> KeyCodes.COMPACT_LAYOUT_TO_LEFT;
          case R.attr.iconKeyClipboardCopy -> KeyCodes.CLIPBOARD_COPY;
          case R.attr.iconKeyClipboardCut -> KeyCodes.CLIPBOARD_CUT;
          case R.attr.iconKeyClipboardPaste -> KeyCodes.CLIPBOARD_PASTE;
          case R.attr.iconKeyClipboardSelect -> KeyCodes.CLIPBOARD_SELECT_ALL;
          case R.attr.iconKeyClipboardFineSelect -> KeyCodes.CLIPBOARD_SELECT;
          case R.attr.iconKeyQuickTextPopup -> KeyCodes.QUICK_TEXT_POPUP;
          case R.attr.iconKeyQuickText -> KeyCodes.QUICK_TEXT;
          case R.attr.iconKeyUndo -> KeyCodes.UNDO;
          case R.attr.iconKeyRedo -> KeyCodes.REDO;
          case R.attr.iconKeyForwardDelete -> KeyCodes.FORWARD_DELETE;
          case R.attr.iconKeyImageInsert -> KeyCodes.IMAGE_MEDIA_POPUP;
          case R.attr.iconKeyClearQuickTextHistory -> KeyCodes.CLEAR_QUICK_TEXT_HISTORY;
          default -> 0;
        };
    if (keyCode == 0) {
      if (BuildConfig.DEBUG) {
        throw new IllegalArgumentException(
            "No valid keycode for attr " + remoteTypeArray.getResourceId(remoteTypedArrayIndex, 0));
      }
      Logger.w(
          TAG,
          "No valid keycode for attr %d",
          remoteTypeArray.getResourceId(remoteTypedArrayIndex, 0));
      return false;
    } else {
      mKeysIconBuilders.put(
          keyCode, DrawableBuilder.build(theme, remoteTypeArray, remoteTypedArrayIndex));
      Logger.d(
          TAG,
          "DrawableBuilders size is %d, newest key code %d for resId %d (at index %d)",
          mKeysIconBuilders.size(),
          keyCode,
          remoteTypeArray.getResourceId(remoteTypedArrayIndex, 0),
          remoteTypedArrayIndex);
      return true;
    }
  }

  protected int getKeyboardStyleResId(KeyboardTheme theme) {
    return theme.getPopupThemeResId();
  }

  protected int getKeyboardIconsStyleResId(KeyboardTheme theme) {
    return theme.getPopupIconsThemeResId();
  }

  /**
   * Returns the {@link OnKeyboardActionListener} object.
   *
   * @return the listener attached to this keyboard
   */
  protected OnKeyboardActionListener getOnKeyboardActionListener() {
    return mKeyboardActionListener;
  }

  @Override
  public void setOnKeyboardActionListener(OnKeyboardActionListener listener) {
    mKeyboardActionListener = listener;
    for (int trackerIndex = 0, trackersCount = mPointerTrackers.size();
        trackerIndex < trackersCount;
        trackerIndex++) {
      PointerTracker tracker = mPointerTrackers.valueAt(trackerIndex);
      tracker.setOnKeyboardActionListener(listener);
    }
  }

  protected void setKeyboard(@NonNull AnyKeyboard keyboard, float verticalCorrection) {
    if (mKeyboard != null) {
      dismissAllKeyPreviews();
    }
    if (mLastSetTheme != null) setWillNotDraw(false);

    // Remove any pending messages, except dismissing preview
    mKeyPressTimingHandler.cancelAllMessages();
    mKeyPreviewsManager.cancelAllPreviews();
    mKeyboard = keyboard;
    mKeyboardName = keyboard.getKeyboardName();
    mKeys = mKeyDetector.setKeyboard(keyboard, keyboard.getShiftKey());
    mKeyDetector.setCorrection(-getPaddingLeft(), -getPaddingTop() + verticalCorrection);
    for (int trackerIndex = 0, trackersCount = mPointerTrackers.size();
        trackerIndex < trackersCount;
        trackerIndex++) {
      PointerTracker tracker = mPointerTrackers.valueAt(trackerIndex);
      tracker.setKeyboard(mKeys);
    }
    // setting the icon/text
    setSpecialKeysIconsAndLabels();

    // the new keyboard might be of a different size
    requestLayout();

    // Hint to reallocate the buffer if the size changed
    mKeyboardChanged = true;
    invalidateAllKeys();
    computeProximityThreshold(keyboard);
    calculateSwipeDistances();
  }

  private void clearKeyIconsCache(boolean withOverlay) {
    for (int i = 0; i < mKeysIcons.size(); i++) {
      Drawable d = mKeysIcons.valueAt(i);
      if (withOverlay) mThemeOverlayCombiner.clearFromIcon(d);
      CompatUtils.unbindDrawable(d);
    }
    mKeysIcons.clear();
  }

  private void calculateSwipeDistances() {
    final AnyKeyboard kbd = getKeyboard();
    if (kbd == null) {
      mSwipeYDistanceThreshold = 0;
    } else {
      mSwipeYDistanceThreshold =
          (int)
              (mSwipeXDistanceThreshold
                  * (((float) kbd.getHeight()) / ((float) kbd.getMinWidth())));
    }
    mSwipeSpaceXDistanceThreshold = mSwipeXDistanceThreshold / 2;
    mSwipeYDistanceThreshold = mSwipeYDistanceThreshold / 2;
  }

  /**
   * Returns the current keyboard being displayed by this view.
   *
   * @return the currently attached keyboard
   */
  public AnyKeyboard getKeyboard() {
    return mKeyboard;
  }

  @Override
  public final void setKeyboard(
      AnyKeyboard currentKeyboard,
      CharSequence nextAlphabetKeyboard,
      CharSequence nextSymbolsKeyboard) {
    mNextAlphabetKeyboardName = nextAlphabetKeyboard;
    if (TextUtils.isEmpty(mNextAlphabetKeyboardName)) {
      mNextAlphabetKeyboardName = getResources().getString(R.string.change_lang_regular);
    }
    mNextSymbolsKeyboardName = nextSymbolsKeyboard;
    if (TextUtils.isEmpty(mNextSymbolsKeyboardName)) {
      mNextSymbolsKeyboardName = getResources().getString(R.string.change_symbols_regular);
    }
    setKeyboard(currentKeyboard, mOriginalVerticalCorrection);
  }

  @Override
  public boolean setShifted(boolean shifted) {
    if (mKeyboard != null && mKeyboard.setShifted(shifted)) {
      // The whole keyboard probably needs to be redrawn
      invalidateAllKeys();
      return true;
    }
    return false;
  }

  @Override
  public boolean setShiftLocked(boolean shiftLocked) {
    AnyKeyboard keyboard = getKeyboard();
    if (keyboard != null && keyboard.setShiftLocked(shiftLocked)) {
      invalidateAllKeys();
      return true;
    }
    return false;
  }

  /**
   * Returns the state of the shift key of the UI, if any.
   *
   * @return true if the shift is in a pressed state, false otherwise. If there is no shift key on
   *     the keyboard or there is no keyboard attached, it returns false.
   */
  @Override
  public boolean isShifted() {
    // if there no keyboard is set, then the shift state is false
    return mKeyboard != null && mKeyboard.isShifted();
  }

  @Override
  public boolean setControl(boolean control) {
    if (mKeyboard != null && mKeyboard.setControl(control)) {
      // The whole keyboard probably needs to be redrawn
      invalidateAllKeys();
      return true;
    }
    return false;
  }

  /**
   * When enabled, calls to {@link OnKeyboardActionListener#onKey} will include key mCodes for
   * adjacent keys. When disabled, only the primary key code will be reported.
   *
   * @param enabled whether or not the proximity correction is enabled
   */
  public void setProximityCorrectionEnabled(boolean enabled) {
    mKeyDetector.setProximityCorrectionEnabled(enabled);
  }

  private boolean isShiftedAccordingToCaseType(boolean keyShiftState) {
    switch (mTextCaseForceOverrideType) {
      case -1 -> {
        return switch (mTextCaseType) {
          case 0 -> keyShiftState; // auto
          case 1 -> false; // lowercase always
          case 2 -> true; // uppercase always
          default -> keyShiftState;
        };
      }
      case 1 -> {
        return false; // lowercase always
      }
      case 2 -> {
        return true; // uppercase always
      }
      default -> {
        return keyShiftState;
      }
    }
  }

  @VisibleForTesting
  CharSequence adjustLabelToShiftState(AnyKey key) {
    CharSequence label = key.label;
    if (isShiftedAccordingToCaseType(mKeyboard.isShifted())) {
      if (!TextUtils.isEmpty(key.shiftedKeyLabel)) {
        return key.shiftedKeyLabel;
      } else if (key.shiftedText != null) {
        label = key.shiftedText;
      } else if (label != null && label.length() == 1) {
        label =
            Character.toString(
                (char)
                    key.getCodeAtIndex(
                        0, isShiftedAccordingToCaseType(mKeyDetector.isKeyShifted(key))));
      }
      // remembering for next time
      if (key.isShiftCodesAlways()) key.shiftedKeyLabel = label;
    }
    return label;
  }

  @Override
  public void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    // Round up a little
    if (mKeyboard == null) {
      super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    } else {
      int width = mKeyboard.getMinWidth() + getPaddingLeft() + getPaddingRight();
      if (MeasureSpec.getSize(widthMeasureSpec) < width + 10) {
        width = MeasureSpec.getSize(widthMeasureSpec);
      }
      int height = mKeyboard.getHeight() + getPaddingTop() + getPaddingBottom();
      setMeasuredDimension(width, height);
    }
  }

  /**
   * Compute the average distance between adjacent keys (horizontally and vertically) and square it
   * to get the proximity threshold. We use a square here and in computing the touch distance from a
   * key's center to avoid taking a square root.
   */
  private void computeProximityThreshold(Keyboard keyboard) {
    if (keyboard == null) {
      return;
    }
    final Keyboard.Key[] keys = mKeys;
    if (keys == null) {
      return;
    }
    int length = keys.length;
    int dimensionSum = 0;
    for (Keyboard.Key key : keys) {
      dimensionSum += Math.min(key.width, key.height) + key.gap;
    }
    if (dimensionSum < 0 || length == 0) {
      return;
    }
    mKeyDetector.setProximityThreshold((int) (dimensionSum * 1.4f / length));
  }

  @Override
  @CallSuper
  public void onDraw(@NonNull final Canvas canvas) {
    super.onDraw(canvas);
    if (mKeyboardChanged) {
      invalidateAllKeys();
      mKeyboardChanged = false;
    }

    canvas.getClipBounds(mDirtyRect);

    if (mKeyboard == null) {
      return;
    }

    final Paint paint = mPaint;
    final boolean drawKeyboardNameText =
        mShowKeyboardNameOnKeyboard && (mKeyboardNameTextSize > 1f);

    final boolean drawHintText = (mHintTextSize > 1) && mShowHintsOnKeyboard;

    final ThemeResourcesHolder themeResourcesHolder = mThemeOverlayCombiner.getThemeResources();
    final ColorStateList keyTextColor = themeResourcesHolder.getKeyTextColor();

    // allow preferences to override theme settings for hint text position
    final int hintAlign =
        mCustomHintGravity == Gravity.NO_GRAVITY
            ? mThemeHintLabelAlign
            : mCustomHintGravity & Gravity.HORIZONTAL_GRAVITY_MASK;
    final int hintVAlign =
        mCustomHintGravity == Gravity.NO_GRAVITY
            ? mThemeHintLabelVAlign
            : mCustomHintGravity & Gravity.VERTICAL_GRAVITY_MASK;

    final Drawable keyBackground = themeResourcesHolder.getKeyBackground();
    final Rect clipRegion = mClipRegion;
    final int kbdPaddingLeft = getPaddingLeft();
    final int kbdPaddingTop = getPaddingTop();
    final Keyboard.Key[] keys = mKeys;
    final Keyboard.Key invalidKey = mInvalidatedKey;

    boolean drawSingleKey = false;
    // TODO we should use Rect.inset and Rect.contains here.
    // Is clipRegion completely contained within the invalidated key?
    if (invalidKey != null
        && canvas.getClipBounds(clipRegion)
        && invalidKey.x + kbdPaddingLeft - 1 <= clipRegion.left
        && invalidKey.y + kbdPaddingTop - 1 <= clipRegion.top
        && Keyboard.Key.getEndX(invalidKey) + kbdPaddingLeft + 1 >= clipRegion.right
        && Keyboard.Key.getEndY(invalidKey) + kbdPaddingTop + 1 >= clipRegion.bottom) {
      drawSingleKey = true;
    }

    for (Keyboard.Key keyBase : keys) {
      final AnyKey key = (AnyKey) keyBase;
      final boolean keyIsSpace = isSpaceKey(key);

      if (drawSingleKey && (invalidKey != key)) {
        continue;
      }
      if (!mDirtyRect.intersects(
          key.x + kbdPaddingLeft,
          key.y + kbdPaddingTop,
          Keyboard.Key.getEndX(key) + kbdPaddingLeft,
          Keyboard.Key.getEndY(key) + kbdPaddingTop)) {
        continue;
      }
      int[] drawableState = key.getCurrentDrawableState(mDrawableStatesProvider);

      if (keyIsSpace) {
        paint.setColor(themeResourcesHolder.getNameTextColor());
      } else {
        paint.setColor(keyTextColor.getColorForState(drawableState, 0xFF000000));
      }
      keyBackground.setState(drawableState);

      // Switch the character to uppercase if shift is pressed
      CharSequence label = key.label == null ? null : adjustLabelToShiftState(key);

      final Rect bounds = keyBackground.getBounds();
      if ((key.width != bounds.right) || (key.height != bounds.bottom)) {
        keyBackground.setBounds(0, 0, key.width, key.height);
      }
      canvas.translate(key.x + kbdPaddingLeft, key.y + kbdPaddingTop);
      keyBackground.draw(canvas);

      if (TextUtils.isEmpty(label)) {
        Drawable iconToDraw = getIconToDrawForKey(key, false);
        if (iconToDraw != null /* && shouldDrawIcon */) {
          // http://developer.android.com/reference/android/graphics/drawable/Drawable.html#getCurrent()
          // http://stackoverflow.com/a/103600/1324235
          final boolean is9Patch = iconToDraw.getCurrent() instanceof NinePatchDrawable;

          // Special handing for the upper-right number hint icons
          final int drawableWidth;
          final int drawableHeight;
          final int drawableX;
          final int drawableY;

          drawableWidth = is9Patch ? key.width : iconToDraw.getIntrinsicWidth();
          drawableHeight = is9Patch ? key.height : iconToDraw.getIntrinsicHeight();
          drawableX =
              (key.width + mKeyBackgroundPadding.left - mKeyBackgroundPadding.right - drawableWidth)
                  / 2;
          drawableY =
              (key.height
                      + mKeyBackgroundPadding.top
                      - mKeyBackgroundPadding.bottom
                      - drawableHeight)
                  / 2;

          canvas.translate(drawableX, drawableY);
          iconToDraw.setBounds(0, 0, drawableWidth, drawableHeight);
          iconToDraw.draw(canvas);
          canvas.translate(-drawableX, -drawableY);
          if (keyIsSpace && drawKeyboardNameText) {
            // now a little hack, I'll set the label now, so it get
            // drawn.
            label = mKeyboardName;
          }
        } else {
          // ho... no icon.
          // I'll try to guess the text
          label = guessLabelForKey(key.getPrimaryCode());
        }
      }

      if (label != null) {
        // For characters, use large font. For labels like "Done", use
        // small font.
        final FontMetrics fm;
        if (keyIsSpace) {
          paint.setTextSize(mKeyboardNameTextSize);
          paint.setTypeface(Typeface.DEFAULT_BOLD);
          if (mKeyboardNameFontMetrics == null) {
            mKeyboardNameFontMetrics = paint.getFontMetrics();
          }
          fm = mKeyboardNameFontMetrics;
        } else if (label.length() > 1 && key.getCodesCount() < 2) {
          setPaintForLabelText(paint);
          if (mLabelFontMetrics == null) mLabelFontMetrics = paint.getFontMetrics();
          fm = mLabelFontMetrics;
        } else {
          setPaintToKeyText(paint);
          if (mTextFontMetrics == null) mTextFontMetrics = paint.getFontMetrics();
          fm = mTextFontMetrics;
        }

        if (EmojiUtils.isLabelOfEmoji(label)) {
          paint.setTextSize(2f * paint.getTextSize());
        }

        final float labelHeight = -fm.top;
        // Draw a drop shadow for the text
        paint.setShadowLayer(mShadowRadius, mShadowOffsetX, mShadowOffsetY, mShadowColor);

        final float textWidth = adjustTextSizeForLabel(paint, label, key.width);

        // the center of the drawable space, which is value used
        // previously for vertically
        // positioning the key label
        final float centerY =
            mKeyBackgroundPadding.top
                + ((float) (key.height - mKeyBackgroundPadding.top - mKeyBackgroundPadding.bottom)
                    / (keyIsSpace ? 3 : 2)); // the label on the space is a bit higher

        // the X coordinate for the center of the main label text is
        // unaffected by the hints
        final float textX =
            mKeyBackgroundPadding.left
                + (key.width - mKeyBackgroundPadding.left - mKeyBackgroundPadding.right) / 2f;
        final float textY;
        // Some devices (mostly pre-Honeycomb, have issues with RTL text
        // drawing.
        // Of course, there is no issue with a single character :)
        // so, we'll use the RTL secured drawing (via StaticLayout) for
        // labels.
        if (label.length() > 1 && !mAlwaysUseDrawText) {
          // calculate Y coordinate of top of text based on center
          // location
          textY = centerY - ((labelHeight - paint.descent()) / 2);
          canvas.translate(textX, textY);
          // RTL fix. But it costs, let do it when in need (more than
          // 1 character)
          StaticLayout labelText =
              new StaticLayout(
                  label,
                  new TextPaint(paint),
                  (int) textWidth,
                  Alignment.ALIGN_NORMAL,
                  1.0f,
                  0.0f,
                  false);
          labelText.draw(canvas);
        } else {
          // to get Y coordinate of baseline from center of text,
          // first add half the height (to get to
          // bottom of text), then subtract the part below the
          // baseline. Note that fm.top is negative.
          textY = centerY + ((labelHeight - paint.descent()) / 2);
          canvas.translate(textX, textY);
          canvas.drawText(label, 0, label.length(), 0, 0, paint);
        }
        canvas.translate(-textX, -textY);
        // (-)

        // Turn off drop shadow
        paint.setShadowLayer(0, 0, 0, 0);
      }

      if (drawHintText
          && (mHintTextSizeMultiplier > 0)
          && ((key.popupCharacters != null && key.popupCharacters.length() > 0)
              || (key.popupResId != 0)
              || (key.longPressCode != 0))) {
        Align oldAlign = paint.getTextAlign();

        String hintText = "";

        if (key.hintLabel != null && key.hintLabel.length() > 0) {
          hintText = key.hintLabel.toString();
          // it is the responsibility of the keyboard layout
          // designer to ensure that they do
          // not put too many characters in the hint label...
        } else if (key.longPressCode != 0) {
          if (Character.isLetterOrDigit(key.longPressCode)) {
            hintText = new String(new int[] {key.longPressCode}, 0, 1);
          }
        } else if (key.popupCharacters != null) {
          final String hintString = key.popupCharacters.toString();
          final int hintLength = Character.codePointCount(hintString, 0, hintString.length());
          if (hintLength <= 3) {
            hintText = hintString;
          } else {
            hintText = hintString.substring(0, Character.offsetByCodePoints(hintString, 0, 3));
          }
        }

        if (mKeyboard.isShifted()) {
          hintText = hintText.toUpperCase(getKeyboard().getLocale());
        }

        // now draw hint
        paint.setTypeface(Typeface.DEFAULT);
        paint.setColor(themeResourcesHolder.getHintTextColor());
        paint.setTextSize(mHintTextSize * mHintTextSizeMultiplier);
        // get the hint text font metrics so that we know the size
        // of the hint when
        // we try to position the main label (to try to make sure
        // they don't overlap)
        if (mHintTextFontMetrics == null) {
          mHintTextFontMetrics = paint.getFontMetrics();
        }

        final float hintX;
        final float hintY;

        // the (float) 0.5 value is added or subtracted to just give
        // a little more room
        // in case the theme designer didn't account for the hint
        // label location
        if (hintAlign == Gravity.START) {
          paint.setTextAlign(Align.LEFT);
          hintX = mKeyBackgroundPadding.left + 0.5f;
        } else if (hintAlign == Gravity.CENTER_HORIZONTAL) {
          // center
          paint.setTextAlign(Align.CENTER);
          hintX =
              mKeyBackgroundPadding.left
                  + (float) (key.width - mKeyBackgroundPadding.left - mKeyBackgroundPadding.right)
                      / 2;
        } else {
          // right
          paint.setTextAlign(Align.RIGHT);
          hintX = key.width - mKeyBackgroundPadding.right - 0.5f;
        }

        if (hintVAlign == Gravity.TOP) {
          // above
          hintY = mKeyBackgroundPadding.top - mHintTextFontMetrics.top + 0.5f;
        } else {
          // below
          hintY = key.height - mKeyBackgroundPadding.bottom - mHintTextFontMetrics.bottom - 0.5f;
        }

        canvas.drawText(hintText, hintX, hintY, paint);
        paint.setTextAlign(oldAlign);
      }

      canvas.translate(-key.x - kbdPaddingLeft, -key.y - kbdPaddingTop);
    }
    mInvalidatedKey = null;

    mDirtyRect.setEmpty();
  }

  private float adjustTextSizeForLabel(
      final Paint paint, final CharSequence label, final int width) {
    TextWidthCacheKey cacheKey = new TextWidthCacheKey(label, width);
    if (mTextWidthCache.containsKey(cacheKey)) {
      return mTextWidthCache.get(cacheKey).setCachedValues(paint);
    }
    float textSize = paint.getTextSize();
    float textWidth = paint.measureText(label, 0, label.length());
    // I'm going to try something if the key is too small for the
    // text:
    // 1) divide the text size by 1.5
    // 2) if still too large, divide by 2.5
    // 3) show no text
    if (textWidth > width) {
      textSize = mKeyTextSize / 1.5f;
      paint.setTextSize(textSize);
      textWidth = paint.measureText(label, 0, label.length());
      if (textWidth > width) {
        textSize = mKeyTextSize / 2.5f;
        paint.setTextSize(textSize);
        textWidth = paint.measureText(label, 0, label.length());
        if (textWidth > width) {
          textSize = 0f;
          paint.setTextSize(textSize);
          textWidth = paint.measureText(label, 0, label.length());
        }
      }
    }

    mTextWidthCache.put(cacheKey, new TextWidthCacheValue(textSize, textWidth));
    return textWidth;
  }

  protected void setPaintForLabelText(Paint paint) {
    paint.setTextSize(mLabelTextSize);
    paint.setTypeface(Typeface.DEFAULT_BOLD);
  }

  public void setPaintToKeyText(final Paint paint) {
    paint.setTextSize(mKeyTextSize);
    paint.setTypeface(mKeyTextStyle);
  }

  @Override
  public void setKeyboardActionType(final int imeOptions) {
    if ((imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) {
      // IME_FLAG_NO_ENTER_ACTION:
      // Flag of imeOptions: used in conjunction with one of the actions masked by
      // IME_MASK_ACTION.
      // If this flag is not set, IMEs will normally replace the "enter" key with the action
      // supplied.
      // This flag indicates that the action should not be available in-line as a replacement
      // for the "enter" key.
      // Typically this is because the action has such a significant impact or is not
      // recoverable enough
      // that accidentally hitting it should be avoided, such as sending a message.
      // Note that TextView will automatically set this flag for you on multi-line text views.
      mKeyboardActionType = EditorInfo.IME_ACTION_NONE;
    } else {
      mKeyboardActionType = (imeOptions & EditorInfo.IME_MASK_ACTION);
    }

    // setting the icon/text
    setSpecialKeysIconsAndLabels();
  }

  private void setSpecialKeysIconsAndLabels() {
    Keyboard.Key enterKey = findKeyByPrimaryKeyCode(KeyCodes.ENTER);
    if (enterKey != null) {
      enterKey.icon = null;
      enterKey.iconPreview = null;
      enterKey.label = null;
      ((AnyKey) enterKey).shiftedKeyLabel = null;
      Drawable icon = getIconToDrawForKey(enterKey, false);
      if (icon != null) {
        enterKey.icon = icon;
        enterKey.iconPreview = icon;
      } else {
        CharSequence label = guessLabelForKey(enterKey.getPrimaryCode());
        enterKey.label = label;
        ((AnyKey) enterKey).shiftedKeyLabel = label;
      }
      // making sure something is shown
      if (enterKey.icon == null && TextUtils.isEmpty(enterKey.label)) {
        Logger.i(
            TAG, "Wow. Unknown ACTION ID " + mKeyboardActionType + ". Will default to ENTER icon.");
        // I saw devices (Galaxy Tab 10") which say the action
        // type is 255...
        // D/ASKKbdViewBase( 3594): setKeyboardActionType
        // imeOptions:33554687 action:255
        // which means it is not a known ACTION
        Drawable enterIcon = getIconForKeyCode(KeyCodes.ENTER);
        enterIcon.setState(mDrawableStatesProvider.DRAWABLE_STATE_ACTION_NORMAL);
        enterKey.icon = enterIcon;
        enterKey.iconPreview = enterIcon;
      }
    }
    // these are dynamic keys
    setSpecialKeyIconOrLabel(KeyCodes.MODE_ALPHABET);
    setSpecialKeyIconOrLabel(KeyCodes.MODE_SYMBOLS);
    setSpecialKeyIconOrLabel(KeyCodes.KEYBOARD_MODE_CHANGE);

    mTextWidthCache.clear();
  }

  private void setSpecialKeyIconOrLabel(int keyCode) {
    Keyboard.Key key = findKeyByPrimaryKeyCode(keyCode);
    if (key != null && TextUtils.isEmpty(key.label)) {
      if (key.dynamicEmblem == Keyboard.KEY_EMBLEM_TEXT) {
        key.label = guessLabelForKey(keyCode);
      } else {
        key.icon = getIconForKeyCode(keyCode);
      }
    }
  }

  @NonNull private CharSequence guessLabelForKey(int keyCode) {
    switch (keyCode) {
      case KeyCodes.ENTER -> {
        switch (mKeyboardActionType) {
          case EditorInfo.IME_ACTION_DONE:
            return getContext().getText(R.string.label_done_key);
          case EditorInfo.IME_ACTION_GO:
            return getContext().getText(R.string.label_go_key);
          case EditorInfo.IME_ACTION_NEXT:
            return getContext().getText(R.string.label_next_key);
          case 0x00000007: // API 11: EditorInfo.IME_ACTION_PREVIOUS:
            return getContext().getText(R.string.label_previous_key);
          case EditorInfo.IME_ACTION_SEARCH:
            return getContext().getText(R.string.label_search_key);
          case EditorInfo.IME_ACTION_SEND:
            return getContext().getText(R.string.label_send_key);
          default:
            return "";
        }
      }
      case KeyCodes.KEYBOARD_MODE_CHANGE -> {
        if (mKeyboard instanceof GenericKeyboard) {
          return guessLabelForKey(KeyCodes.MODE_ALPHABET);
        } else {
          return guessLabelForKey(KeyCodes.MODE_SYMBOLS);
        }
      }
      case KeyCodes.MODE_ALPHABET -> {
        return mNextAlphabetKeyboardName;
      }
      case KeyCodes.MODE_SYMBOLS -> {
        return mNextSymbolsKeyboardName;
      }
      case KeyCodes.TAB -> {
        return getContext().getText(R.string.label_tab_key);
      }
      case KeyCodes.MOVE_HOME -> {
        return getContext().getText(R.string.label_home_key);
      }
      case KeyCodes.MOVE_END -> {
        return getContext().getText(R.string.label_end_key);
      }
      case KeyCodes.ARROW_DOWN -> {
        return "▼";
      }
      case KeyCodes.ARROW_LEFT -> {
        return "◀";
      }
      case KeyCodes.ARROW_RIGHT -> {
        return "▶";
      }
      case KeyCodes.ARROW_UP -> {
        return "▲";
      }
      default -> {
        return "";
      }
    }
  }

  private Drawable getIconToDrawForKey(Keyboard.Key key, boolean feedback) {
    if (key.dynamicEmblem == Keyboard.KEY_EMBLEM_TEXT) {
      return null;
    }

    if (feedback && key.iconPreview != null) {
      return key.iconPreview;
    }
    if (key.icon != null) {
      return key.icon;
    }

    return getIconForKeyCode(key.getPrimaryCode());
  }

  @Nullable public Drawable getDrawableForKeyCode(int keyCode) {
    Drawable icon = mKeysIcons.get(keyCode);

    if (icon == null) {
      DrawableBuilder builder = mKeysIconBuilders.get(keyCode);
      if (builder == null) {
        return null; // no builder assigned to the key-code
      }

      // building needed icon
      Logger.d(TAG, "Building icon for key-code %d", keyCode);
      icon = builder.buildDrawable();

      if (icon != null) {
        mThemeOverlayCombiner.applyOnIcon(icon);
        mKeysIcons.put(keyCode, icon);
        Logger.v(TAG, "Current drawable cache size is %d", mKeysIcons.size());
      } else {
        Logger.w(TAG, "Can not find drawable for keyCode %d. Context lost?", keyCode);
      }
    }

    return icon;
  }

  @Nullable private Drawable getIconForKeyCode(int keyCode) {
    Drawable icon = getDrawableForKeyCode(keyCode);
    // maybe a drawable state is required
    if (icon != null) {
      switch (keyCode) {
        case KeyCodes.ENTER -> {
          Logger.d(TAG, "Action key action ID is %d", mKeyboardActionType);
          switch (mKeyboardActionType) {
            case EditorInfo.IME_ACTION_DONE:
              icon.setState(mDrawableStatesProvider.DRAWABLE_STATE_ACTION_DONE);
              break;
            case EditorInfo.IME_ACTION_GO:
              icon.setState(mDrawableStatesProvider.DRAWABLE_STATE_ACTION_GO);
              break;
            case EditorInfo.IME_ACTION_SEARCH:
              icon.setState(mDrawableStatesProvider.DRAWABLE_STATE_ACTION_SEARCH);
              break;
            case EditorInfo.IME_ACTION_NONE:
            case EditorInfo.IME_ACTION_UNSPECIFIED:
            default:
              icon.setState(mDrawableStatesProvider.DRAWABLE_STATE_ACTION_NORMAL);
              break;
          }
        }
        case KeyCodes.SHIFT -> {
          if (mKeyboard.isShiftLocked()) {
            icon.setState(mDrawableStatesProvider.DRAWABLE_STATE_MODIFIER_LOCKED);
          } else if (mKeyboard.isShifted()) {
            icon.setState(mDrawableStatesProvider.DRAWABLE_STATE_MODIFIER_PRESSED);
          } else {
            icon.setState(mDrawableStatesProvider.DRAWABLE_STATE_MODIFIER_NORMAL);
          }
        }
        case KeyCodes.CTRL -> {
          if (mKeyboard.isControl()) {
            icon.setState(mDrawableStatesProvider.DRAWABLE_STATE_MODIFIER_PRESSED);
          } else {
            icon.setState(mDrawableStatesProvider.DRAWABLE_STATE_MODIFIER_NORMAL);
          }
        }
      }
    }
    return icon;
  }

  void dismissAllKeyPreviews() {
    /*for (int trackerIndex = 0, trackersCount = mPointerTrackers.size(); trackerIndex < trackersCount; trackerIndex++) {
        PointerTracker tracker = mPointerTrackers.valueAt(trackerIndex);
        tracker.updateKey(NOT_A_KEY);
    }*/
    mKeyPreviewsManager.cancelAllPreviews();
  }

  @Override
  public void hidePreview(int keyIndex, PointerTracker tracker) {
    final Keyboard.Key key = tracker.getKey(keyIndex);
    if (keyIndex != NOT_A_KEY && key != null) {
      mKeyPreviewsManager.hidePreviewForKey(key);
    }
  }

  @Override
  public void showPreview(int keyIndex, PointerTracker tracker) {
    // We should re-draw popup preview when 1) we need to hide the preview,
    // 2) we will show
    // the space key preview and 3) pointer moves off the space key to other
    // letter key, we
    // should hide the preview of the previous key.
    final boolean hidePreviewOrShowSpaceKeyPreview = (tracker == null);
    // If key changed and preview is on or the key is space (language switch
    // is enabled)
    final Keyboard.Key key = hidePreviewOrShowSpaceKeyPreview ? null : tracker.getKey(keyIndex);
    // this will ensure that in case the key is marked as NO preview, we will just dismiss the
    // previous popup.
    if (keyIndex != NOT_A_KEY && key != null) {
      Drawable iconToDraw = getIconToDrawForKey(key, true);

      // Should not draw hint icon in key preview
      if (iconToDraw != null) {
        mKeyPreviewsManager.showPreviewForKey(key, iconToDraw, this, mPreviewPopupTheme);
      } else {
        CharSequence label = tracker.getPreviewText(key);
        if (TextUtils.isEmpty(label)) {
          label = guessLabelForKey(key.getPrimaryCode());
        }

        mKeyPreviewsManager.showPreviewForKey(key, label, this, mPreviewPopupTheme);
      }
    }
  }

  /**
   * Requests a redraw of the entire keyboard. Calling {@link #invalidate} is not sufficient because
   * the keyboard renders the keys to an off-screen buffer and an invalidate() only draws the cached
   * buffer.
   *
   * @see #invalidateKey(Keyboard.Key)
   */
  public void invalidateAllKeys() {
    mDirtyRect.union(0, 0, getWidth(), getHeight());
    invalidate();
  }

  /**
   * Invalidates a key so that it will be redrawn on the next repaint. Use this method if only one
   * key is changing it's content. Any changes that affect the position or size of the key may not
   * be honored.
   *
   * @param key key in the attached {@link Keyboard}.
   * @see #invalidateAllKeys
   */
  @Override
  public void invalidateKey(Keyboard.Key key) {
    if (key == null) {
      return;
    }
    mInvalidatedKey = key;
    // TODO we should clean up this and record key's region to use in
    // onBufferDraw.
    mDirtyRect.union(
        key.x + getPaddingLeft(),
        key.y + getPaddingTop(),
        Keyboard.Key.getEndX(key) + getPaddingLeft(),
        Keyboard.Key.getEndY(key) + getPaddingTop());
    // doOnBufferDrawWithMemProtection(mCanvas);
    invalidate(
        key.x + getPaddingLeft(),
        key.y + getPaddingTop(),
        Keyboard.Key.getEndX(key) + getPaddingLeft(),
        Keyboard.Key.getEndY(key) + getPaddingTop());
  }

  @NonNull @Override
  public KeyboardDimens getThemedKeyboardDimens() {
    return mKeyboardDimens;
  }

  public float getLabelTextSize() {
    return mLabelTextSize;
  }

  public float getKeyTextSize() {
    return mKeyTextSize;
  }

  public ThemeResourcesHolder getCurrentResourcesHolder() {
    return mThemeOverlayCombiner.getThemeResources();
  }

  /**
   * Called when a key is long pressed. By default this will open any popup keyboard associated with
   * this key through the attributes popupLayout and popupCharacters.
   *
   * @param keyboardAddOn the owning keyboard that starts this long-press operation
   * @param key the key that was long pressed
   * @return true if the long press is handled, false otherwise. Subclasses should call the method
   *     on the base class if the subclass doesn't wish to handle the call.
   */
  protected boolean onLongPress(
      AddOn keyboardAddOn, Keyboard.Key key, boolean isSticky, @NonNull PointerTracker tracker) {
    if (key instanceof AnyKey anyKey) {
      if (anyKey.getKeyTags().size() > 0) {
        Object[] tags = anyKey.getKeyTags().toArray();
        for (int tagIndex = 0; tagIndex < tags.length; tagIndex++) {
          tags[tagIndex] = ":" + tags[tagIndex];
        }
        String joinedTags = TextUtils.join(", ", tags);
        final Toast tagsToast =
            Toast.makeText(getContext().getApplicationContext(), joinedTags, Toast.LENGTH_SHORT);
        tagsToast.setGravity(Gravity.CENTER, 0, 0);
        tagsToast.show();
      }
      if (anyKey.longPressCode != 0) {
        getOnKeyboardActionListener()
            .onKey(anyKey.longPressCode, key, 0 /*not multi-tap*/, null, true);
        if (!anyKey.repeatable) {
          onCancelEvent(tracker);
        }
        return true;
      }
    }

    return false;
  }

  protected PointerTracker getPointerTracker(@NonNull final MotionEvent motionEvent) {
    final int index = motionEvent.getActionIndex();
    final int id = motionEvent.getPointerId(index);
    return getPointerTracker(id);
  }

  protected PointerTracker getPointerTracker(final int id) {
    final Keyboard.Key[] keys = mKeys;
    final OnKeyboardActionListener listener = mKeyboardActionListener;

    if (mPointerTrackers.get(id) == null) {
      final PointerTracker tracker =
          new PointerTracker(
              id,
              mKeyPressTimingHandler,
              mKeyDetector,
              this,
              getResources().getDimensionPixelOffset(R.dimen.key_hysteresis_distance),
              mSharedPointerTrackersData);
      if (keys != null) {
        tracker.setKeyboard(keys);
      }
      if (listener != null) {
        tracker.setOnKeyboardActionListener(listener);
      }
      mPointerTrackers.put(id, tracker);
    }

    return mPointerTrackers.get(id);
  }

  @Override
  public boolean onTouchEvent(@NonNull MotionEvent nativeMotionEvent) {
    if (mKeyboard == null) {
      // I mean, if there isn't any keyboard I'm handling, what's the point?
      return false;
    }

    final int action = nativeMotionEvent.getActionMasked();
    final int pointerCount = nativeMotionEvent.getPointerCount();
    if (pointerCount > 1) {
      mLastTimeHadTwoFingers =
          SystemClock.elapsedRealtime(); // marking the time. Read isAtTwoFingersState()
    }

    if (mTouchesAreDisabledTillLastFingerIsUp) {
      if (!areTouchesDisabled(nativeMotionEvent) /*this means it was just reset*/) {
        mTouchesAreDisabledTillLastFingerIsUp = false;
        // continue with onTouchEvent flow.
        if (action != MotionEvent.ACTION_DOWN) {
          // swallowing the event.
          // in case this is a DOWN event, we do want to pass it
          return true;
        }
      } else {
        // swallowing touch event until we reset mTouchesAreDisabledTillLastFingerIsUp
        return true;
      }
    }

    final long eventTime = nativeMotionEvent.getEventTime();
    final int index = nativeMotionEvent.getActionIndex();
    final int id = nativeMotionEvent.getPointerId(index);
    final int x = (int) nativeMotionEvent.getX(index);
    final int y = (int) nativeMotionEvent.getY(index);

    if (mKeyPressTimingHandler.isInKeyRepeat()) {
      // It will keep being in the key repeating mode while the key is
      // being pressed.
      if (action == MotionEvent.ACTION_MOVE) {
        return true;
      }
      final PointerTracker tracker = getPointerTracker(id);
      // Key repeating timer will be canceled if 2 or more keys are in
      // action, and current
      // event (UP or DOWN) is non-modifier key.
      if (pointerCount > 1 && !tracker.isModifier()) {
        mKeyPressTimingHandler.cancelKeyRepeatTimer();
      }
      // Up event will pass through.
    }

    if (action == MotionEvent.ACTION_MOVE) {
      for (int i = 0; i < pointerCount; i++) {
        PointerTracker tracker = getPointerTracker(nativeMotionEvent.getPointerId(i));
        tracker.onMoveEvent(
            (int) nativeMotionEvent.getX(i), (int) nativeMotionEvent.getY(i), eventTime);
      }
    } else {
      PointerTracker tracker = getPointerTracker(id);
      sendOnXEvent(action, eventTime, x, y, tracker);
    }

    return true;
  }

  @NonNull public final KeyDetector getKeyDetector() {
    return mKeyDetector;
  }

  protected boolean isFirstDownEventInsideSpaceBar() {
    return false;
  }

  private void sendOnXEvent(
      final int action, final long eventTime, final int x, final int y, PointerTracker tracker) {
    switch (action) {
      case MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
          onDownEvent(tracker, x, y, eventTime);
      case MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
          onUpEvent(tracker, x, y, eventTime);
      case MotionEvent.ACTION_CANCEL -> onCancelEvent(tracker);
    }
  }

  protected void onDownEvent(PointerTracker tracker, int x, int y, long eventTime) {
    if (tracker.isOnModifierKey(x, y)) {
      // Before processing a down event of modifier key, all pointers
      // already being tracked
      // should be released.
      mPointerQueue.releaseAllPointersExcept(tracker, eventTime);
    }
    tracker.onDownEvent(x, y, eventTime);
    mPointerQueue.add(tracker);
  }

  protected void onUpEvent(PointerTracker tracker, int x, int y, long eventTime) {
    if (tracker.isModifier()) {
      // Before processing an up event of modifier key, all pointers
      // already being tracked
      // should be released.
      mPointerQueue.releaseAllPointersExcept(tracker, eventTime);
    } else {
      int index = mPointerQueue.lastIndexOf(tracker);
      if (index >= 0) {
        mPointerQueue.releaseAllPointersOlderThan(tracker, eventTime);
      } else {
        Logger.w(
            TAG,
            "onUpEvent: corresponding down event not found for pointer %d",
            tracker.mPointerId);
        return;
      }
    }
    tracker.onUpEvent(x, y, eventTime);
    mPointerQueue.remove(tracker);
  }

  protected void onCancelEvent(PointerTracker tracker) {
    tracker.onCancelEvent();
    mPointerQueue.remove(tracker);
  }

  @Nullable protected Keyboard.Key findKeyByPrimaryKeyCode(int keyCode) {
    if (getKeyboard() == null) {
      return null;
    }

    for (Keyboard.Key key : getKeyboard().getKeys()) {
      if (key.getPrimaryCode() == keyCode) return key;
    }
    return null;
  }

  @CallSuper
  @Override
  public boolean resetInputView() {
    mKeyPreviewsManager.cancelAllPreviews();
    mKeyPressTimingHandler.cancelAllMessages();
    mPointerQueue.cancelAllPointers();

    return false;
  }

  @Override
  public void onStartTemporaryDetach() {
    mKeyPreviewsManager.cancelAllPreviews();
    mKeyPressTimingHandler.cancelAllMessages();
    super.onStartTemporaryDetach();
  }

  @Override
  public void onViewNotRequired() {
    mDisposables.dispose();
    resetInputView();
    // cleaning up memory
    CompatUtils.unbindDrawable(getBackground());
    clearKeyIconsCache(false);
    mKeysIconBuilders.clear();

    mKeyboardActionListener = null;
    mKeyboard = null;
  }

  @Override
  public void setWatermark(@NonNull List<Drawable> watermark) {}

  private void updatePrefSettings(final String overrideValue) {
    switch (overrideValue) {
      case "auto" -> mTextCaseForceOverrideType = 0;
      case "lower" -> mTextCaseForceOverrideType = 1;
      case "upper" -> mTextCaseForceOverrideType = 2;
      default -> mTextCaseForceOverrideType = -1;
    }
  }

  private void updatePrefSettingsHintTextSizeFactor(final String overrideValue) {
    switch (overrideValue) {
      case "none" -> mHintTextSizeMultiplier = 0f;
      case "small" -> mHintTextSizeMultiplier = 0.7f;
      case "big" -> mHintTextSizeMultiplier = 1.3f;
      default -> mHintTextSizeMultiplier = 1;
    }
  }

  public void setKeyPreviewController(@NonNull KeyPreviewsController controller) {
    mKeyPreviewsManager = controller;
  }

  protected static class KeyPressTimingHandler extends Handler {

    private static final int MSG_REPEAT_KEY = 3;
    private static final int MSG_LONG_PRESS_KEY = 4;

    private final WeakReference<AnyKeyboardViewBase> mKeyboard;
    private boolean mInKeyRepeat;

    KeyPressTimingHandler(AnyKeyboardViewBase keyboard) {
      super(Looper.getMainLooper());
      mKeyboard = new WeakReference<>(keyboard);
    }

    @Override
    public void handleMessage(@NonNull Message msg) {
      AnyKeyboardViewBase keyboard = mKeyboard.get();
      if (keyboard == null) {
        return;
      }
      final PointerTracker tracker = (PointerTracker) msg.obj;
      Keyboard.Key keyForLongPress = tracker.getKey(msg.arg1);
      switch (msg.what) {
        case MSG_REPEAT_KEY -> {
          if (keyForLongPress instanceof AnyKey && ((AnyKey) keyForLongPress).longPressCode != 0) {
            keyboard.onLongPress(
                keyboard.getKeyboard().getKeyboardAddOn(), keyForLongPress, false, tracker);
          } else {
            tracker.repeatKey(msg.arg1);
          }
          startKeyRepeatTimer(keyboard.mKeyRepeatInterval, msg.arg1, tracker);
        }
        case MSG_LONG_PRESS_KEY -> {
          if (keyForLongPress != null
              && keyboard.onLongPress(
                  keyboard.getKeyboard().getKeyboardAddOn(), keyForLongPress, false, tracker)) {
            keyboard.mKeyboardActionListener.onLongPressDone(keyForLongPress);
          }
        }
        default -> super.handleMessage(msg);
      }
    }

    public void startKeyRepeatTimer(long delay, int keyIndex, PointerTracker tracker) {
      mInKeyRepeat = true;
      sendMessageDelayed(obtainMessage(MSG_REPEAT_KEY, keyIndex, 0, tracker), delay);
    }

    void cancelKeyRepeatTimer() {
      mInKeyRepeat = false;
      removeMessages(MSG_REPEAT_KEY);
    }

    boolean isInKeyRepeat() {
      return mInKeyRepeat;
    }

    public void startLongPressTimer(long delay, int keyIndex, PointerTracker tracker) {
      removeMessages(MSG_LONG_PRESS_KEY);
      sendMessageDelayed(obtainMessage(MSG_LONG_PRESS_KEY, keyIndex, 0, tracker), delay);
    }

    public void cancelLongPressTimer() {
      removeMessages(MSG_LONG_PRESS_KEY);
    }

    public void cancelAllMessages() {
      cancelKeyRepeatTimer();
      cancelLongPressTimer();
    }
  }

  static class PointerQueue {
    private final ArrayList<PointerTracker> mQueue = new ArrayList<>();
    private static final PointerTracker[] EMPTY_TRACKERS = new PointerTracker[0];

    public void add(PointerTracker tracker) {
      mQueue.add(tracker);
    }

    int lastIndexOf(PointerTracker tracker) {
      ArrayList<PointerTracker> queue = mQueue;
      for (int index = queue.size() - 1; index >= 0; index--) {
        PointerTracker t = queue.get(index);
        if (t == tracker) {
          return index;
        }
      }
      return -1;
    }

    void releaseAllPointersOlderThan(final PointerTracker tracker, final long eventTime) {
      // doing a copy to prevent ConcurrentModificationException
      PointerTracker[] trackers = mQueue.toArray(EMPTY_TRACKERS);
      for (PointerTracker t : trackers) {
        if (t == tracker) break;
        if (!t.isModifier()) {
          t.onUpEvent(t.getLastX(), t.getLastY(), eventTime);
          t.setAlreadyProcessed();
          mQueue.remove(t);
        }
      }
    }

    void cancelAllPointers() {
      for (PointerTracker t : mQueue) {
        t.onCancelEvent();
      }
      mQueue.clear();
    }

    void releaseAllPointersExcept(@Nullable PointerTracker tracker, long eventTime) {
      for (PointerTracker t : mQueue) {
        if (t == tracker) {
          continue;
        }
        t.onUpEvent(t.getLastX(), t.getLastY(), eventTime);
        t.setAlreadyProcessed();
      }
      mQueue.clear();
      if (tracker != null) mQueue.add(tracker);
    }

    public void remove(PointerTracker tracker) {
      mQueue.remove(tracker);
    }

    public int size() {
      return mQueue.size();
    }
  }

  private static class TextWidthCacheValue {
    private final float mTextSize;
    private final float mTextWidth;

    private TextWidthCacheValue(float textSize, float textWidth) {
      mTextSize = textSize;
      mTextWidth = textWidth;
    }

    float setCachedValues(Paint paint) {
      paint.setTextSize(mTextSize);
      return mTextWidth;
    }
  }

  private static class TextWidthCacheKey {
    private final CharSequence mLabel;
    private final int mKeyWidth;

    private TextWidthCacheKey(CharSequence label, int keyWidth) {
      mLabel = label;
      mKeyWidth = keyWidth;
    }

    @Override
    public int hashCode() {
      return mLabel.hashCode() + mKeyWidth;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof TextWidthCacheKey
          && ((TextWidthCacheKey) o).mKeyWidth == mKeyWidth
          && TextUtils.equals(((TextWidthCacheKey) o).mLabel, mLabel);
    }
  }
}
