package com.example.eyevoice;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

public class CalibrationModel {

    public static class Sample {
        public final double gx, gy, sx, sy;
        public Sample(double gx, double gy, double sx, double sy) {
            this.gx = gx; this.gy = gy; this.sx = sx; this.sy = sy;
        }
    }

    public static boolean fitAndSave(List<Sample> samples, SharedPreferences prefs) {
        if (samples.size() < 6) return false;

        double[][] m = new double[3][3];
        double[] bx = new double[3];
        double[] by = new double[3];

        for (Sample s : samples) {
            double[] v = {1.0, s.gx, s.gy};
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 3; c++) m[r][c] += v[r] * v[c];
                bx[r] += v[r] * s.sx;
                by[r] += v[r] * s.sy;
            }
        }

        double[] ax = solve3(copy(m), bx.clone());
        double[] ay = solve3(copy(m), by.clone());
        if (ax == null || ay == null) return false;

        prefs.edit()
                .putFloat("ax0", (float) ax[0]).putFloat("ax1", (float) ax[1]).putFloat("ax2", (float) ax[2])
                .putFloat("ay0", (float) ay[0]).putFloat("ay1", (float) ay[1]).putFloat("ay2", (float) ay[2])
                .putBoolean("calibrated", true)
                .apply();
        return true;
    }

    public static float[] map(double gx, double gy, SharedPreferences prefs) {
        if (!prefs.getBoolean("calibrated", false)) {
            return new float[]{0.5f, 0.5f};
        }
        double x = prefs.getFloat("ax0", 0.5f)
                + prefs.getFloat("ax1", 0f) * gx
                + prefs.getFloat("ax2", 0f) * gy;
        double y = prefs.getFloat("ay0", 0.5f)
                + prefs.getFloat("ay1", 0f) * gx
                + prefs.getFloat("ay2", 0f) * gy;
        return new float[]{clamp((float)x), clamp((float)y)};
    }

    public static List<Sample> loadSamples(SharedPreferences prefs) {
        List<Sample> out = new ArrayList<>();
        int count = prefs.getInt("cal_sample_count", 0);
        for (int i = 0; i < count; i++) {
            out.add(new Sample(
                    prefs.getFloat("cal_" + i + "_gx", 0f),
                    prefs.getFloat("cal_" + i + "_gy", 0f),
                    prefs.getFloat("cal_" + i + "_sx", 0f),
                    prefs.getFloat("cal_" + i + "_sy", 0f)
            ));
        }
        return out;
    }

    private static float clamp(float v) {
        return Math.max(0.02f, Math.min(0.98f, v));
    }

    private static double[][] copy(double[][] src) {
        double[][] dst = new double[src.length][];
        for (int i = 0; i < src.length; i++) dst[i] = src[i].clone();
        return dst;
    }

    private static double[] solve3(double[][] a, double[] b) {
        for (int i = 0; i < 3; i++) {
            int max = i;
            for (int r = i + 1; r < 3; r++) {
                if (Math.abs(a[r][i]) > Math.abs(a[max][i])) max = r;
            }
            if (Math.abs(a[max][i]) < 1e-8) return null;

            double[] tmp = a[i]; a[i] = a[max]; a[max] = tmp;
            double tb = b[i]; b[i] = b[max]; b[max] = tb;

            double pivot = a[i][i];
            for (int c = i; c < 3; c++) a[i][c] /= pivot;
            b[i] /= pivot;

            for (int r = 0; r < 3; r++) {
                if (r == i) continue;
                double f = a[r][i];
                for (int c = i; c < 3; c++) a[r][c] -= f * a[i][c];
                b[r] -= f * b[i];
            }
        }
        return b;
    }
}
