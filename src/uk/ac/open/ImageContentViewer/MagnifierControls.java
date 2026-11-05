package uk.ac.open.ImageContentViewer;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JSlider;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;

/**
 * Magnifier toolbar controls and the enhancement used inside the magnifier lens.
 *
 * Usage (once per panel, in buildToolbar after the zoom buttons):
 *
 *     MagnifierControls.addTo(toolbar, canvas);
 *
 * The lens itself is painted by ChannelImagePanel.ImageCanvas.
 */
final class MagnifierControls {

    /** Enhancement modes. The order matches the drop-down. */
    static final int MODE_EQUALISE = 0;
    static final int MODE_AUTO_CONTRAST = 1;
    static final int MODE_AUTO_CONTRAST_CHANNEL = 2;
    static final int MODE_NONE = 3;

    private static final String[] MODE_NAMES = {
        "Histogram equalisation", "Auto contrast", "Auto contrast by channel", "None"};

    private MagnifierControls() {
    }

    /** Adds the magnifier toggle, enhancement drop-down and 2x-8x slider to the toolbar. */
    static void addTo(JToolBar toolbar, ChannelImagePanel.ImageCanvas canvas) {
        JToggleButton toggle = new JToggleButton("Magnifier");
        toggle.setFocusable(false);
        toggle.setToolTipText("Show a magnifying lens under the mouse pointer");

        JComboBox<String> enhancement = new JComboBox<>(MODE_NAMES);
        enhancement.setSelectedIndex(MODE_NONE);
        enhancement.setMaximumSize(new Dimension(170, 24));
        enhancement.setToolTipText("Enhancement applied to the area inside the magnifier");
        enhancement.setEnabled(false);

        JSlider magnification = new JSlider(2, 8, 4);
        magnification.setPreferredSize(new Dimension(90, 24));
        magnification.setMaximumSize(new Dimension(90, 24));
        magnification.setToolTipText("Magnification (2x - 8x)");
        magnification.setEnabled(false);

        JLabel magnificationLabel = new JLabel("4x");
        magnificationLabel.setPreferredSize(new Dimension(24, 24));

        toggle.addActionListener(e -> {
            boolean on = toggle.isSelected();
            enhancement.setEnabled(on);
            magnification.setEnabled(on);
            canvas.setMagnifierEnabled(on);
        });
        enhancement.addActionListener(e -> canvas.setMagnifierEnhancement(enhancement.getSelectedIndex()));
        magnification.addChangeListener(e -> {
            magnificationLabel.setText(magnification.getValue() + "x");
            canvas.setMagnification(magnification.getValue());
        });

        toolbar.addSeparator();
        toolbar.add(toggle);
        toolbar.add(enhancement);
        toolbar.add(magnification);
        toolbar.add(magnificationLabel);
    }

    // ------------------------------------------------------------ enhancement

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    /** Returns an enhanced copy of the image (the input is not modified). */
    static BufferedImage enhance(BufferedImage src, int mode) {
        final int w = src.getWidth();
        final int h = src.getHeight();
        int[] px = src.getRGB(0, 0, w, h, null, 0, w);
        final int n = px.length;

        switch (mode) {
            case MODE_EQUALISE: {
                // equalise the luminance and shift all channels by the change, which keeps the colours
                int[] hist = new int[256];
                for (int p : px) {
                    hist[(((p >> 16) & 0xFF) * 77 + ((p >> 8) & 0xFF) * 150 + (p & 0xFF) * 29) >> 8]++;
                }
                int[] map = equalisationMap(hist, n);
                for (int i = 0; i < n; i++) {
                    int p = px[i];
                    int r = (p >> 16) & 0xFF;
                    int g = (p >> 8) & 0xFF;
                    int b = p & 0xFF;
                    int l = (r * 77 + g * 150 + b * 29) >> 8;
                    int d = map[l] - l;
                    px[i] = (p & 0xFF000000) | (clamp(r + d) << 16) | (clamp(g + d) << 8) | clamp(b + d);
                }
                break;
            }
            case MODE_AUTO_CONTRAST: {
                // one stretch for all channels, so colours keep their balance
                int[] hist = new int[256];
                for (int p : px) {
                    hist[(p >> 16) & 0xFF]++;
                    hist[(p >> 8) & 0xFF]++;
                    hist[p & 0xFF]++;
                }
                int[] lut = stretchMap(hist, 3L * n);
                for (int i = 0; i < n; i++) {
                    int p = px[i];
                    px[i] = (p & 0xFF000000) | (lut[(p >> 16) & 0xFF] << 16) | (lut[(p >> 8) & 0xFF] << 8)
                            | lut[p & 0xFF];
                }
                break;
            }
            case MODE_AUTO_CONTRAST_CHANNEL: {
                int[][] hist = new int[3][256];
                for (int p : px) {
                    hist[0][(p >> 16) & 0xFF]++;
                    hist[1][(p >> 8) & 0xFF]++;
                    hist[2][p & 0xFF]++;
                }
                int[] lr = stretchMap(hist[0], n);
                int[] lg = stretchMap(hist[1], n);
                int[] lb = stretchMap(hist[2], n);
                for (int i = 0; i < n; i++) {
                    int p = px[i];
                    px[i] = (p & 0xFF000000) | (lr[(p >> 16) & 0xFF] << 16) | (lg[(p >> 8) & 0xFF] << 8)
                            | lb[p & 0xFF];
                }
                break;
            }
            default:
                return src;
        }

        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        out.setRGB(0, 0, w, h, px, 0, w);
        return out;
    }

    /** Histogram equalisation look-up table. */
    private static int[] equalisationMap(int[] hist, long total) {
        int[] map = new int[256];
        long cdf = 0;
        long cdfMin = -1;
        for (int v = 0; v < 256; v++) {
            cdf += hist[v];
            if (cdfMin < 0 && hist[v] > 0) {
                cdfMin = cdf;
            }
            long denom = total - cdfMin;
            map[v] = denom <= 0 ? v : (int) Math.round(255.0 * Math.max(0, cdf - cdfMin) / denom);
        }
        return map;
    }

    /** Linear stretch between the 0.5% and 99.5% percentiles. */
    private static int[] stretchMap(int[] hist, long total) {
        long lowTarget = (long) (total * 0.005);
        long highTarget = (long) (total * 0.995);
        long acc = 0;
        int lo = 0;
        int hi = 255;
        boolean haveLo = false;
        for (int v = 0; v < 256; v++) {
            acc += hist[v];
            if (!haveLo && acc > lowTarget) {
                lo = v;
                haveLo = true;
            }
            if (acc >= highTarget) {
                hi = v;
                break;
            }
        }
        int[] lut = new int[256];
        for (int v = 0; v < 256; v++) {
            if (hi <= lo) {
                lut[v] = v; // flat region: leave unchanged
            } else {
                lut[v] = clamp((int) Math.round(255.0 * (v - lo) / (hi - lo)));
            }
        }
        return lut;
    }
}
