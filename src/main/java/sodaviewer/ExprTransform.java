package sodaviewer;

/** Shared expression display transforms (gene.plot-style). */
public final class ExprTransform {
    private ExprTransform() {}

    public static float log2p1(float v) {
        return (float) (Math.log(1.0 + Math.max(0, v)) / Math.log(2));
    }

    public static float[] log2p1(float[] v) {
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = log2p1(v[i]);
        }
        return out;
    }

    /** Z-score across cells; non-finite → 0. */
    public static float[] zscore(float[] v) {
        double sum = 0;
        double sum2 = 0;
        int n = 0;
        for (float x : v) {
            if (Float.isFinite(x)) {
                sum += x;
                sum2 += (double) x * x;
                n++;
            }
        }
        float[] out = new float[v.length];
        if (n < 2) {
            return out;
        }
        double mean = sum / n;
        double var = (sum2 - sum * sum / n) / (n - 1);
        double sd = Math.sqrt(Math.max(var, 1e-12));
        for (int i = 0; i < v.length; i++) {
            float x = v[i];
            out[i] = Float.isFinite(x) ? (float) ((x - mean) / sd) : 0f;
        }
        return out;
    }

    /** log2(1+x) then z-score — default scatter gene coloring. */
    public static float[] log2p1Z(float[] raw) {
        return zscore(log2p1(raw));
    }
}
