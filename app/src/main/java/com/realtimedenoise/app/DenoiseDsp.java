package com.realtimedenoise.app;

/**
 * Pure-Java DSP chain (no NDK). Matches the calibrated v2 params:
 *   highpass 85Hz -> peaking EQ 200+2.5dB, 700+3.5dB, 2300+4.5dB, 5000+2dB -> limiter 0.92
 * Processed in 20ms blocks, 48000Hz mono.
 */
public class DenoiseDsp {

    private final Biquad highpass;
    private final Biquad eq200, eq700, eq2300, eq5000;
    private final double limit;

    public DenoiseDsp(int sampleRate) {
        highpass = Biquad.highpass(sampleRate, 85.0, 0.707);
        eq200 = Biquad.peaking(sampleRate, 200.0, 2.5, 1.0);
        eq700 = Biquad.peaking(sampleRate, 700.0, 3.5, 1.0);
        eq2300 = Biquad.peaking(sampleRate, 2300.0, 4.5, 1.0);
        eq5000 = Biquad.peaking(sampleRate, 5000.0, 2.0, 1.0);
        limit = 0.92;
    }

    /** In-place process a float block (values roughly in [-1,1]). */
    public void process(float[] buf, int len) {
        highpass.run(buf, len);
        eq200.run(buf, len);
        eq700.run(buf, len);
        eq2300.run(buf, len);
        eq5000.run(buf, len);
        // soft limiter: y = limit * tanh(x / limit)
        double l = limit;
        for (int i = 0; i < len; i++) {
            double x = buf[i];
            buf[i] = (float) (l * Math.tanh(x / l));
        }
    }

    private static final class Biquad {
        private double b0, b1, b2, a1, a2;
        private double x1, x2, y1, y2;

        static Biquad highpass(int fs, double f0, double q) {
            double w = 2.0 * Math.PI * f0 / fs;
            double cosw = Math.cos(w);
            double alpha = Math.sin(w) / (2.0 * q);
            double a0 = 1.0 + alpha;
            Biquad b = new Biquad();
            b.b0 = (1.0 + cosw) / 2.0 / a0;
            b.b1 = -(1.0 + cosw) / a0;
            b.b2 = (1.0 + cosw) / 2.0 / a0;
            b.a1 = -2.0 * cosw / a0;
            b.a2 = (1.0 - alpha) / a0;
            return b;
        }

        static Biquad peaking(int fs, double f0, double gainDb, double q) {
            double A = Math.pow(10.0, gainDb / 40.0);
            double w = 2.0 * Math.PI * f0 / fs;
            double cosw = Math.cos(w);
            double alpha = Math.sin(w) / (2.0 * q);
            double a0 = 1.0 + alpha / A;
            Biquad b = new Biquad();
            b.b0 = (1.0 + alpha * A) / a0;
            b.b1 = -2.0 * cosw / a0;
            b.b2 = (1.0 - alpha * A) / a0;
            b.a1 = -2.0 * cosw / a0;
            b.a2 = (1.0 - alpha / A) / a0;
            return b;
        }

        void run(float[] buf, int len) {
            double b0_ = b0, b1_ = b1, b2_ = b2, a1_ = a1, a2_ = a2;
            double x1_ = x1, x2_ = x2, y1_ = y1, y2_ = y2;
            for (int i = 0; i < len; i++) {
                double x = buf[i];
                double y = b0_ * x + b1_ * x1_ + b2_ * x2_ - a1_ * y1_ - a2_ * y2_;
                x2_ = x1_; x1_ = x;
                y2_ = y1_; y1_ = y;
                buf[i] = (float) y;
            }
            x1 = x1_; x2 = x2_; y1 = y1_; y2 = y2_;
        }
    }
}