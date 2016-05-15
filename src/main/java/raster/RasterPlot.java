package raster;

import utils.Logger;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.LinkedList;

/**
 * The <code>RasterPlot</code> class encapsulates such things as plot plane, data series, and coloring
 * rule for this data series.
 */

public class RasterPlot {
    LinkedList<float[]> chunks;
    ChunkPool pool;
    int[] plotPixels;

    private BufferedImage plot;
    private Dimension resolution;

    private int maxThreadCount;
    private int imageType;

    private Logger logger;

    private ColoringRule coloringRule;
    private Bounds bounds;

    private double scaleX, scaleY;

    /**
     * Constructor for <code>RasterPlot</code> class.
     *
     * @param resolution Resolution of plot plane image, in pixels.
     */
    public RasterPlot(Dimension resolution) {
        this(resolution, Bounds.createDefaultBounds(), ColoringRule.createDefaultColoringRule());
    }

    /**
     * Constructor for <code>RasterPlot</code> class.
     *
     * @param resolution   Resolution of plot plane image, in pixels.
     * @param bounds       Bounds of the plot plane.
     * @param coloringRule Coloring rule.
     */
    public RasterPlot(Dimension resolution, Bounds bounds, ColoringRule coloringRule) {
        this(Runtime.getRuntime().availableProcessors(), resolution, bounds, coloringRule, BufferedImage.TYPE_INT_ARGB,
                new Logger(System.out, Logger.Level.NOTHING));
    }

    /**
     * Constructor for <code>RasterPlot</code> class.
     *
     * @param resolution Resolution of plot plane image, in pixels
     * @param imageType Image type of plot. Valid values are all of <code>BufferedImage.TYPE_INT_*</code>.
     */
    public RasterPlot(Dimension resolution, int imageType) {
        this(Runtime.getRuntime().availableProcessors(), resolution,
                Bounds.createDefaultBounds(), ColoringRule.createDefaultColoringRule(), imageType,
                new Logger(System.out, Logger.Level.NOTHING));
    }

    /**
     * Constructor for <code>RasterPlot</code> class.
     *
     * @param maxThreadCount Maximum number of threads, which RasterPlot will use for rendering
     *                       images (actually, <code>RasterPlot</code> will <i>always</i> try to use this amount of threads).
     * @param resolution     Resolution of plot plane image, in pixels.
     * @param bounds         Bounds of coordinate plane.
     * @param coloringRule   Coloring rule.
     * @param imageType      Image type of plot. Valid values are all of <code>BufferedImage.TYPE_INT_*</code>.
     * @param logger         <code>utils.Logger</code> instance used for logging results.
     */
    public RasterPlot(int maxThreadCount,
                      Dimension resolution,
                      Bounds bounds,
                      ColoringRule coloringRule,
                      int imageType,
                      Logger logger) {
        this.chunks = new LinkedList<>();
        this.imageType = imageType;
        setMaxThreadCount(maxThreadCount);
        setResolution(resolution);
        setLogger(logger);
        setBounds(bounds);
        setColoringRule(coloringRule);
    }

    /**
     * This function puts a chunk of float data to a render chain.
     * This happens if and only if the data is representing a set of points,
     * each one having two coordinates: x and y; otherwise (if length of array
     * is an odd number) it does nothing
     *
     * @param xy float array {x1, y1, ... xN, yN}
     */
    public synchronized RasterPlot putChunk(float[] xy) {
        if (xy.length % 2 == 0) this.chunks.add(xy);
        return this;
    }

    /**
     * Renders all chunks that currently are in render chain, drawing
     * each point according to current coloring rule and bounds.
     */
    public RasterPlot renderChunks() {
        long time = System.nanoTime();
        pool = new ChunkPool(this.chunks.size());
        int threadCount = this.chunks.size() < maxThreadCount ? this.chunks.size() : maxThreadCount;
        logger.info(String.format(
                "Started rendering %d chunks using %d threads.",
                this.chunks.size(), threadCount));

        Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(new Plotter(this, Plotter.Mode.CHUNKS), "PlotterThread#" + i);
            threads[i].start();
        }
        for (int i = 0; i < threadCount; i++) {
            try {
                threads[i].join();
            } catch (InterruptedException e) {
                logger.error(e.getMessage());
            }
        }
        plot.flush();
        time = System.nanoTime() - time;
        logger.info(String.format("Rendering finished in %d ms!", time / 1000000));
        return this;
    }

    /**
     * Fills whole plot plane with colors determined by <code>ColoringRule</code>
     */
    public RasterPlot renderSolid() {
        long time = System.nanoTime();
        logger.info(String.format("Rendering solid picture using %d threads", this.maxThreadCount));
        int portion = resolution.height / this.maxThreadCount;
        Thread[] threads = new Thread[4];
        for (int i = 0; i < this.maxThreadCount; i++) {
            threads[i] = new Thread(new Plotter(this, Plotter.Mode.SOLID, i * portion, (i + 1) * portion));
            threads[i].start();
        }
        for (int i = 0; i < this.maxThreadCount; i++) {
            try {
                threads[i].join();
            } catch (InterruptedException e) {
                logger.error(e.getMessage());
            }
        }
        plot.flush();
        time = System.nanoTime() - time;
        logger.info(String.format("Rendering finished in %d ms!", time / 1000000));
        return this;
    }

    /**
     * Clears render chain of RasterPlot.
     */
    public RasterPlot clearData() {
        this.chunks.clear();
        return this;
    }

    /**
     * Clears plot with color, specified in field <code>ColoringRule.backColor</code> of current
     * coloring rule.
     */
    public RasterPlot clearPlot() {
        long time = System.nanoTime();
        logger.info(String.format("Clearing plot using %d threads", maxThreadCount));
        Thread[] threads = new Thread[maxThreadCount];
        int seg = plot.getHeight() * plot.getWidth() / maxThreadCount;
        for (int i = 0; i < maxThreadCount; i++) {
            threads[i] = new Thread(new Plotter(this, Plotter.Mode.CLEAR,
                    seg * i, i == (maxThreadCount - 1) ? plot.getHeight() * plot.getWidth() : seg * (i + 1)));
            threads[i].start();
        }
        for (int i = 0; i < maxThreadCount; i++) {
            try {
                threads[i].join();
            } catch (InterruptedException e) {
                logger.error(e.getMessage());
            }
        }
        plot.flush();
        time = System.nanoTime() - time;
        logger.info(String.format("Clearing took %d ms", time / 1000000));
        return this;
    }

    /**
     * Sets new coloring rule.
     *
     * @param rule New coloring rule
     */
    public RasterPlot setColoringRule(ColoringRule rule) {
        this.coloringRule = rule;
        return this;
    }

    /**
     * @return Current coloring rule.
     */
    public ColoringRule getColoringRule() {
        return this.coloringRule;
    }

    /**
     * Sets new bounds of the plot plane.
     *
     * @param bounds new bounds
     */
    public RasterPlot setBounds(Bounds bounds) {
        this.bounds = bounds;
        if (resolution != null) calculateScales();
        return this;
    }

    /**
     * @return Current bounds of plot plane.
     */
    public Bounds getBounds() {
        return this.bounds;
    }

    /**
     * @return Current resolution (in pixels).
     */
    public Dimension getResolution() {
        return resolution;
    }

    /**
     * Sets new resolution of plot plane (in pixels).
     *
     * @param resolution New resolution.
     */
    public RasterPlot setResolution(Dimension resolution) {
        this.resolution = resolution;
        reallocImage();
        if (bounds != null) calculateScales();
        return this;
    }

    /**
     * Returns current <code>utils.Logger</code> instance.
     *
     * @return <code>utils.Logger</code> instance.
     */
    public Logger getLogger() {
        return logger;
    }

    /**
     * Sets new <code>utils.Logger</code> instance, which can be used to obtain information about
     * current state, errors, and warnings.
     *
     * @param logger New <code>utils.Logger</code> instance.
     */
    public RasterPlot setLogger(Logger logger) {
        this.logger = logger;
        return this;
    }

    /**
     * Returns number of threads this instance of <code>RasterPlot</code> can use.
     *
     * @return Number of threads
     */
    public int getMaxThreadCount() {
        return maxThreadCount;
    }

    /**
     * Set maximum number of threads to specified value.
     *
     * @param maxThreadCount new thread limit.
     */
    public RasterPlot setMaxThreadCount(int maxThreadCount) {
        this.maxThreadCount = maxThreadCount;
        return this;
    }

    /**
     * @return plot plane image.
     */
    public BufferedImage getPlot() {
        return plot;
    }

    /**
     * Saves current plot image to specified file.
     *
     * @param filename file to be written
     * @param format   image format
     */
    public RasterPlot saveToFile(String filename, String format) throws IOException {
        long time = System.nanoTime();
        logger.info("Writing current plot image to file (" + filename + ")");
        FileOutputStream out = new FileOutputStream(filename);
        ImageIO.write(this.plot, format, out);
        out.close();
        time = System.nanoTime() - time;
        logger.info("File written (" + filename + ") in " + time / 1000000 + " ms");
        return this;
    }

    private void reallocImage() {
        if (this.plot != null)
            plot.getGraphics().dispose();
        this.plot = new BufferedImage(resolution.width, resolution.height, imageType);
        this.plotPixels = ((DataBufferInt) this.plot.getRaster().getDataBuffer()).getData();
    }

    private void calculateScales() {
        scaleX = (bounds.getMaxX() - bounds.getMinX()) / resolution.getWidth();
        scaleY = (bounds.getMaxY() - bounds.getMinY()) / resolution.getHeight();
    }

    private Point planeToPixel(double x, double y) {
        return new Point(
                (int) ((x - bounds.getMinX()) / scaleX),
                (int) (resolution.getHeight() - 1 - ((y - bounds.getMinY()) / scaleY)));
    }

    private Point pixelToPlane(int x, int y) {
        Point result = new Point();
        result.setLocation(bounds.getMinX() + (double) x * scaleX, -bounds.getMinY() - (double) y * scaleY);
        return result;
    }

    double getScaleY() {
        return scaleY;
    }

    double getScaleX() {
        return scaleX;
    }
}
