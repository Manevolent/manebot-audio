package io.manebot.plugin.audio.mixer.filter.type;

import io.manebot.plugin.audio.mixer.filter.AbstractFilter;

import io.manebot.plugin.audio.mixer.filter.SingleChannelFilter;

public class FilterLimiter extends AbstractFilter implements SingleChannelFilter {
    public float xdn1 = 0f;
    private float[] lookAheadBuffer = new float[64];
    private float threshold;

    /**
     * Params
     */
    private float slope = 1f, rt = 0.4f, at = 0.4f;

    public FilterLimiter(float sampleRate, float threshold, float attack, float release, float slope) {
        super(sampleRate);

        this.threshold = threshold;
        this.slope = slope;
        this.at = attack;
        this.rt = release;
    }

    @Override
    public int process(float[] samples, int offs, int len) {
        float xn, a, fn, xdn;

        for (int i = 0; i < len; i++) {
            xn = samples[i+offs];
            a = Math.abs(xn) - xdn1;

            if (a < 0) a = 0;

            xdn = xdn1 * (1 - rt) + (at * a);
            if (xdn > threshold) fn = (float) Math.pow(10, -slope * (Math.log10(xdn) - Math.log10(threshold)));
            else fn = 1;

            this.xdn1 = xdn;
            lookAheadBuffer[63] = xn;
            System.arraycopy(lookAheadBuffer, 1, lookAheadBuffer, 0, 63);
            samples[i+offs] = lookAheadBuffer[0] * fn;
        }

        return len;
    }

    @Override
    public void reset() {
        xdn1 = 0f;

        for (int i = 0; i < lookAheadBuffer.length; i ++)
            lookAheadBuffer[i] = 0f;
    }
}
