
package jp.inara.sample.navdrawer;

import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

public class NavDrawer extends ViewGroup {

    private static final float MAXIMUM_MINOR_VELOCITY = 150.0f;
    private static final float MAXIMUM_MAJOR_VELOCITY = 200.0f;
    private static final float MAXIMUM_ACCELERATION = 2000.0f;
    private static final int VELOCITY_UNITS = 1000;
    private static final int MSG_ANIMATE = 1000;
    private static final int ANIMATION_FRAME_DURATION = 1000 / 60;

    private static final int EXPANDED_FULL_OPEN = -10001;
    private static final int COLLAPSED_FULL_CLOSED = -10002;

    /** ナビゲーション部分 */
    private View mNavigation;
    /** コンテンツ部分 */
    private View mContent;

    private final Rect mFrame = new Rect();
    private final Rect mInvalidate = new Rect();

    /** 画面に触れたかどうかの判定 */
    private boolean mTracking;

    private VelocityTracker mVelocityTracker;

    private boolean mExpanded = true;

    /** コンテンツの残る部分 */
    private int mOffset;

    private final Handler mHandler = new SlidingHandler();
    private float mAnimatedAcceleration;
    private float mAnimatedVelocity;
    private float mAnimationPosition;
    private long mAnimationLastTime;
    private long mCurrentAnimationTime;
    private int mTouchDelta;
    private boolean mAnimating;

    private final int mMaximumMinorVelocity;
    private final int mMaximumMajorVelocity;
    private final int mMaximumAcceleration;
    private final int mVelocityUnits;

    /**
     * Position of the last motion event.
     */
    private float mLastMotionX;
    private float mLastMotionY;

    private int mTouchSlop;
    private boolean mIsUnableToDrag = false;
    
    public NavDrawer(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NavDrawer(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        final float density = getResources().getDisplayMetrics().density;
        mMaximumMinorVelocity = (int) (MAXIMUM_MINOR_VELOCITY * density + 0.5f);
        mMaximumMajorVelocity = (int) (MAXIMUM_MAJOR_VELOCITY * density + 0.5f);
        mMaximumAcceleration = (int) (MAXIMUM_ACCELERATION * density + 0.5f);
        mVelocityUnits = (int) (VELOCITY_UNITS * density + 0.5f);

        // Youtubeアプリと同じくらいのだいたい80dip
        mOffset = (int) density * 80; // 80dip
        
        final ViewConfiguration configuration = ViewConfiguration.get(context);
        mTouchSlop = configuration.getScaledTouchSlop();

        setAlwaysDrawnWithCacheEnabled(false);
    }

    @Override
    protected void onFinishInflate() {
        mContent = findViewById(R.id.content);
        mNavigation = findViewById(R.id.nav);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // コンテンツ部分の大きさはメニューと同じサイズにする。
        // MeasureSpec.EXACTLYを使って厳密にコンテンツ部のサイズを指定する

        int widthSpecMode = MeasureSpec.getMode(widthMeasureSpec);
        int widthSpecSize = MeasureSpec.getSize(widthMeasureSpec);

        int heightSpecMode = MeasureSpec.getMode(heightMeasureSpec);
        int heightSpecSize = MeasureSpec.getSize(heightMeasureSpec);

        if (widthSpecMode == MeasureSpec.UNSPECIFIED
                || heightSpecMode == MeasureSpec.UNSPECIFIED) {
            throw new RuntimeException(
                    "SlidingDrawer cannot have UNSPECIFIED dimensions");
        }

        mContent.measure(
                MeasureSpec.makeMeasureSpec(widthSpecSize, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(heightSpecSize, MeasureSpec.EXACTLY));

        mNavigation.measure(
                MeasureSpec.makeMeasureSpec(widthSpecSize - mOffset, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(heightSpecSize, MeasureSpec.EXACTLY));

        // onMeasureで呼ぶお約束のメソッド。サイズを記録するらしい。
        setMeasuredDimension(widthSpecSize, heightSpecSize);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        if (mTracking) {
            return;
        }

        final int width = r - l;
        final int height = b - t;

        final View content = mContent;
        content.layout(0, 0, width, height);
        
        final View nav = mNavigation;
        nav.layout(0, 0, width - mOffset, height);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {

        final int action = event.getAction() & MotionEvent.ACTION_MASK;
        
        if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP) {
            // ドラッグが終了したのでパラメータをリセット
            mTracking = false;
            mIsUnableToDrag = false;
            if (mVelocityTracker != null) {
                mVelocityTracker.recycle();
                mVelocityTracker = null;
            }
            return false;
        }

        if (action != MotionEvent.ACTION_DOWN) {
            if (mTracking) {
                // ドラッグ中ならこのタッチをハンドルする
                return true;
            }
            if (mIsUnableToDrag) {
                // 子ビューのスクロール中ならこのタッチをハンドルしない
                return false;
            }
        }

        float x = event.getX();
        float y = event.getY();

        final Rect frame = mFrame;
        final View content = mContent;

        // まだトラッキングが開始されていなくて、コンテントの外がタップされた場合は終了。
        content.getHitRect(frame);
        if (!mTracking && !frame.contains((int) x, (int) y)) {
            return false;
        }

        switch (action) {
            case MotionEvent.ACTION_MOVE: {
                final float xDiff = Math.abs(x - mLastMotionX);
                final float yDiff = Math.abs(y - mLastMotionY);

                if (xDiff > mTouchSlop && xDiff > yDiff) {
                    // 横方向のドラッグ量のほうが縦より多い
                    mTracking = true;
                    mLastMotionX = x;

                    prepareContent();

                    final int left = mContent.getLeft();

                    // タッチした位置とコンテンツ領域の左端の差
                    mTouchDelta = (int) x - left;

                    prepareTracking(left);
                    mVelocityTracker.addMovement(event);

                } else if (yDiff > mTouchSlop) {
                    // 縦方向のドラッグ量の方が横より多い
                    // 縦にスクロールするので横方向のドラッグはできないようにフラグをたてる
                    mIsUnableToDrag = true;
                     if(!mExpanded) {
                         return true;
                     }
                }

                break;
            }

            case MotionEvent.ACTION_DOWN: {
                // 最初の位置を保存
                mLastMotionX = x;
                mLastMotionY = y;

                mTracking = false;
                mIsUnableToDrag = false;
                break;
            }
        }

        return mTracking;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mTracking && !mIsUnableToDrag) {
            mVelocityTracker.addMovement(event);

            final int action = event.getAction();

            switch (action) {
                case MotionEvent.ACTION_MOVE:
                    // コンテンツを移動
                    moveContent((int) event.getX() - mTouchDelta);
                    break;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    // 速度を計算
                    final VelocityTracker velocityTracker = mVelocityTracker;
                    velocityTracker.computeCurrentVelocity(mVelocityUnits);

                    float yVelocity = velocityTracker.getYVelocity();
                    float xVelocity = velocityTracker.getXVelocity();
                    boolean negative;

                    negative = xVelocity < 0;
                    if (yVelocity < 0) {
                        yVelocity = -yVelocity;
                    }
                    if (yVelocity > mMaximumMinorVelocity) {
                        yVelocity = mMaximumMinorVelocity;
                    }

                    float velocity = (float) Math.hypot(xVelocity, yVelocity);
                    if (negative) {
                        velocity = -velocity;
                    }

                    final int left = mContent.getLeft();

                    performFling(left, velocity);
                }
                    break;
            }
        }

        return mTracking || mAnimating || super.onTouchEvent(event);
    }

    private void performFlingLikeGooglePlus(int position, float velocity) {
        // アニメーションさせるコンテンツの現在の位置
        mAnimationPosition = position;
        // アニメーションの初速度
        mAnimatedVelocity = velocity;

        // 速度の方向にそのままスクロールする
        if (velocity > 0) {
            mAnimatedAcceleration = mMaximumAcceleration;
        }
        else {
            mAnimatedAcceleration = -mMaximumAcceleration;
        }
    }

    private void performFlingLikeYoutube(int position, float velocity) {
        // アニメーションさせるコンテンツの現在の位置
        mAnimationPosition = position;
        // アニメーションの初速度
        mAnimatedVelocity = velocity;

        // 速度が基準値より大きければその方向にそのままスクロールする
        if (velocity > mMaximumMajorVelocity) {
            mAnimatedAcceleration = mMaximumAcceleration;
            return;
        }
        if (velocity < -mMaximumMajorVelocity) {
            mAnimatedAcceleration = -mMaximumAcceleration;
            return;
        }

        // 速度が基準値より小さければ位置に応じて近い方にそのままスクロールする
        if (position > getWidth() / 2) {
            mAnimatedAcceleration = mMaximumAcceleration;
            if (velocity < 0) {
                mAnimatedVelocity = 0;
            }
            return;
        }
        else {
            mAnimatedAcceleration = -mMaximumAcceleration;
            if (velocity > 0) {
                mAnimatedVelocity = 0;
            }
        }
    }

    /**
     * 現在の速度に応じて最後までアニメーションを実行する
     * 
     * @param position アニメーション開始位置
     * @param velocity 速度
     */
    private void performFling(int position, float velocity) {

        // performFlingLikeGooglePlus(position, velocity);
        performFlingLikeYoutube(position, velocity);

        long now = SystemClock.uptimeMillis();
        mAnimationLastTime = now;

        // 現在時刻より ANIMATION_FRAME_DURATION より後に
        // MSG_ANIMATE メッセージを Handler になげる
        mCurrentAnimationTime = now + ANIMATION_FRAME_DURATION;
        mAnimating = true;

        mHandler.removeMessages(MSG_ANIMATE);
        mHandler.sendMessageAtTime(mHandler.obtainMessage(MSG_ANIMATE),
                mCurrentAnimationTime);
        stopTracking();
    }

    private void prepareTracking(int position) {
        mTracking = true;
        mVelocityTracker = VelocityTracker.obtain();
        boolean opening = !mExpanded;
        if (opening) {
            mAnimatedAcceleration = mMaximumAcceleration;
            mAnimatedVelocity = mMaximumMajorVelocity;
            mAnimationPosition = getWidth() - mOffset;
            moveContent((int) mAnimationPosition);
            mAnimating = true;
            mHandler.removeMessages(MSG_ANIMATE);
            long now = SystemClock.uptimeMillis();
            mAnimationLastTime = now;
            mCurrentAnimationTime = now + ANIMATION_FRAME_DURATION;
            mAnimating = true;
        } else {
            if (mAnimating) {
                mAnimating = false;
                mHandler.removeMessages(MSG_ANIMATE);
            }
            moveContent(position);
        }
    }

    private void moveContent(int position) {
        final View content = mContent;

        if (position == EXPANDED_FULL_OPEN) {
            content.offsetLeftAndRight(0 - content.getLeft());
            invalidate();
        } else if (position == COLLAPSED_FULL_CLOSED) {
            content.offsetLeftAndRight(getRight() - getLeft() - mOffset - content.getLeft());
            invalidate();
        } else {
            final int left = content.getLeft();
            int deltaX = position - left;
            if (position < 0) {
                deltaX = 0 - left;
            } else if (position > getRight() - getLeft() - mOffset) {
                deltaX = getRight() - getLeft() - mOffset - left;
            }
            content.offsetLeftAndRight(deltaX);

            final Rect frame = mFrame;
            final Rect region = mInvalidate;

            content.getHitRect(frame);
            region.set(frame);

            region.union(frame.left - deltaX, frame.top, frame.right - deltaX,
                    frame.bottom);
            region.union(frame.right - deltaX, 0, frame.right - deltaX
                    + mContent.getWidth(), getHeight());

            invalidate(region);
        }
    }

    private void prepareContent() {
        if (mAnimating) {
            return;
        }

        final View content = mContent;
        // isLayoutRequested() = レイアウト更新フラグの取得
        if (content.isLayoutRequested()) {
            content.measure(
                    MeasureSpec.makeMeasureSpec(getRight() - getLeft(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(getBottom() - getTop(), MeasureSpec.EXACTLY));

            content.layout(0, 0, content.getMeasuredWidth(), content.getMeasuredHeight());
        }
        content.getViewTreeObserver().dispatchOnPreDraw();
    }

    private void stopTracking() {
        mTracking = false;

        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }
    }

    private void doAnimation() {
        if (mAnimating) {
            incrementAnimation();
            if (mAnimationPosition >= (getWidth()) - 1) {
                mAnimating = false;
                closeDrawer();
            } else if (mAnimationPosition < 0) {
                mAnimating = false;
                openDrawer();
            } else {
                moveContent((int) mAnimationPosition);
                mCurrentAnimationTime += ANIMATION_FRAME_DURATION;
                mHandler.sendMessageAtTime(mHandler.obtainMessage(MSG_ANIMATE),
                        mCurrentAnimationTime);
            }
        }
    }

    private void incrementAnimation() {
        long now = SystemClock.uptimeMillis();
        float t = (now - mAnimationLastTime) / 1000.0f; // ms -> s
        final float position = mAnimationPosition;
        final float v = mAnimatedVelocity; // px/s
        final float a = mAnimatedAcceleration; // px/s/s
        mAnimationPosition = position + (v * t) + (0.5f * a * t * t); // px
        mAnimatedVelocity = v + (a * t); // px/s
        mAnimationLastTime = now; // ms
    }

    private void closeDrawer() {
        moveContent(COLLAPSED_FULL_CLOSED);

        if (!mExpanded) {
            return;
        }

        mExpanded = false;
    }

    private void openDrawer() {
        moveContent(EXPANDED_FULL_OPEN);

        if (mExpanded) {
            return;
        }
        mExpanded = true;
    }

    private class SlidingHandler extends Handler {
        public void handleMessage(Message m) {
            switch (m.what) {
                case MSG_ANIMATE:
                    doAnimation();
                    break;
            }
        }
    }
}
