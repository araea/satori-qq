package com.satori.qq.ui;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;

/**
 * M3E 的弹簧动效，折算成框架动画能直接用的「时长 + 插值器」。
 *
 * <p>单位质量的阻尼谐振子从 0 走到 1：欠阻尼（ζ &lt; 1）会越过终点再回来，临界阻尼（ζ = 1）
 * 不越过。时长取包络衰减到 1/1000 的时刻，最后一帧强制落在 1，避免残留亚像素抖动。
 * 不依赖 androidx 的 dynamicanimation：整个应用只用框架 API，这里二十行就够。
 *
 * <p>系统关闭动画（开发者选项或「移除动画」无障碍设置）时 {@link #enabled()} 为 false，
 * 调用方直接跳到终态——动效从不承载唯一信息。
 */
final class Spring implements TimeInterpolator {
    final float damping;
    final float stiffness;
    final long duration;
    private final double omega;
    private final double decay;
    private final double damped;

    Spring(float damping, float stiffness) {
        this.damping = damping;
        this.stiffness = stiffness;
        this.omega = Math.sqrt(stiffness);
        this.decay = damping * omega;
        this.damped = damping < 1 ? omega * Math.sqrt(1 - damping * damping) : 0;
        this.duration = settle();
    }

    /** 位移（0 → 1），t 为秒。 */
    double at(double t) {
        if (damping < 1) {
            return 1 - Math.exp(-decay * t) * (Math.cos(damped * t) + decay / damped * Math.sin(damped * t));
        }
        return 1 - Math.exp(-omega * t) * (1 + omega * t);
    }

    private long settle() {
        double t = 0;
        double step = 0.001;
        double envelope;
        do {
            t += step;
            envelope = damping < 1
                    ? Math.exp(-decay * t) * Math.sqrt(1 + (decay / damped) * (decay / damped))
                    : Math.exp(-omega * t) * (1 + omega * t);
        } while (envelope > 0.001 && t < 2);
        return Math.max(80, Math.min(1000, Math.round(t * 1000)));
    }

    @Override public float getInterpolation(float fraction) {
        if (fraction >= 1f) return 1f;
        return (float) at(fraction * duration / 1000.0);
    }

    /** 把一个 ValueAnimator 配成这支弹簧。 */
    ValueAnimator apply(ValueAnimator animator) {
        animator.setDuration(duration);
        animator.setInterpolator(this);
        return animator;
    }

    static boolean enabled() {
        return ValueAnimator.areAnimatorsEnabled();
    }
}
