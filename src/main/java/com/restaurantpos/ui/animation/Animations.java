package com.restaurantpos.ui.animation;

import javafx.animation.*;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.util.Duration;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;

public final class Animations {
    private Animations() {}

    public static void fadeIn(Node node, double ms, double delay) {
        node.setOpacity(0);
        FadeTransition ft = new FadeTransition(Duration.millis(ms), node);
        ft.setFromValue(0); ft.setToValue(1);
        ft.setDelay(Duration.millis(delay));
        ft.setInterpolator(Interpolator.EASE_OUT);
        ft.play();
    }

    public static void slideUp(Node node, double ms, double delay, double distance) {
        node.setOpacity(0);
        node.setTranslateY(distance);
        FadeTransition ft = new FadeTransition(Duration.millis(ms), node);
        ft.setFromValue(0); ft.setToValue(1); ft.setDelay(Duration.millis(delay)); ft.setInterpolator(Interpolator.EASE_OUT);
        TranslateTransition tt = new TranslateTransition(Duration.millis(ms), node);
        tt.setFromY(distance); tt.setToY(0); tt.setDelay(Duration.millis(delay)); tt.setInterpolator(Interpolator.EASE_OUT);
        new ParallelTransition(ft, tt).play();
    }

    public static void pop(Node node) {
        ScaleTransition st = new ScaleTransition(Duration.millis(160), node);
        st.setFromX(0.96); st.setFromY(0.96);
        st.setToX(1); st.setToY(1);
        st.setInterpolator(Interpolator.EASE_OUT);
        st.play();
    }

    public static void pulse(Node node) {
        ScaleTransition up = new ScaleTransition(Duration.millis(140), node);
        up.setToX(1.06); up.setToY(1.06);
        ScaleTransition down = new ScaleTransition(Duration.millis(180), node);
        down.setToX(1); down.setToY(1);
        SequentialTransition seq = new SequentialTransition(up, down);
        seq.play();
    }

    public static void hoverLift(Node node) {
        ScaleTransition st = new ScaleTransition(Duration.millis(140), node);
        st.setToX(1.025); st.setToY(1.025);
        st.setInterpolator(Interpolator.EASE_OUT);
        st.play();
    }

    public static void hoverReset(Node node) {
        ScaleTransition st = new ScaleTransition(Duration.millis(140), node);
        st.setToX(1); st.setToY(1);
        st.setInterpolator(Interpolator.EASE_OUT);
        st.play();
    }

    public static void countUp(Label label, BigDecimal target, String prefix) {
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.US);
        nf.setMinimumFractionDigits(0);
        nf.setMaximumFractionDigits(0);
        AnimationTimer timer = new AnimationTimer() {
            private long start = -1;
            @Override public void handle(long now) {
                if (start < 0) start = now;
                double progress = Math.min(1, (now - start) / 900_000_000.0);
                double eased = 1 - Math.pow(1 - progress, 3);
                double value = target.doubleValue() * eased;
                label.setText(prefix + nf.format(value));
                if (progress >= 1) stop();
            }
        };
        timer.start();
    }

    public static void stagger(Node[] nodes, double delayStep) {
        for (int i = 0; i < nodes.length; i++) slideUp(nodes[i], 360, i * delayStep, 14);
    }
}
