package com.tkb.pandora.image.boofcv;

import boofcv.abst.feature.detect.extract.ConfigExtract;
import boofcv.abst.feature.detect.extract.NonMaxSuppression;
import boofcv.abst.feature.orientation.ConfigSlidingIntegral;
import boofcv.abst.feature.orientation.OrientationIntegral;
import boofcv.alg.feature.describe.DescribePointSurf;
import boofcv.alg.feature.detect.interest.FastHessianFeatureDetector;
import boofcv.alg.transform.ii.GIntegralImageOps;
import boofcv.core.image.ConvertImage;
import boofcv.core.image.GeneralizedImageOps;
import boofcv.factory.feature.describe.FactoryDescribePointAlgs;
import boofcv.factory.feature.detect.extract.FactoryFeatureExtractor;
import boofcv.factory.feature.orientation.FactoryOrientationAlgs;
import boofcv.io.image.ConvertBufferedImage;
import boofcv.struct.feature.ScalePoint;
import boofcv.struct.feature.SurfFeature;
import boofcv.struct.feature.TupleDesc_F64;
import boofcv.struct.image.ImageFloat32;
import boofcv.struct.image.ImageSingleBand;
import boofcv.struct.image.MultiSpectral;
import java.awt.image.BufferedImage;
import java.util.List;
import com.tkb.pandora.image.Description;
import com.tkb.pandora.image.FeatureDetector;
import com.tkb.pandora.math.Normalizer;

/**
 * A local detector extracting stable colorful SURF local descriptors given an
 * image using the BoofCV library.
 *
 * This class is a modification of a class written by Eleftherios
 * Spyromitros-Xioufis, please see <a href="https://goo.gl/HSl3JG">more</a>.
 *
 * @author Akis Papadopoulos
 */
public class ColorSurf implements FeatureDetector {

    // Radius of the non-maximum region
    private int radius;

    // Minimum feature intensity
    private float threshold;

    // Maximum number of returned features per scale, le to 0 returns all features finds
    private int maxFeaturesPerScale;

    // How often pixels are sampled in the first octave
    private int initialSampleRate;

    // Width of the smallest feature/kernel in the lowest octave
    private int initialSize;

    // How many different feature sizes are considered in a single octave
    private int numberScalesPerOctave;

    // How many different octaves are considered
    private int numberOfOctaves;

    // Sliding orientation estimator mode
    private boolean slided;

    // Normalization option
    private boolean normalize;

    /**
     * A constructor initiating the default parameters.
     */
    public ColorSurf() {
        radius = 1;
        threshold = 0F;
        maxFeaturesPerScale = -1;
        initialSampleRate = 2;
        initialSize = 9;
        numberScalesPerOctave = 4;
        numberOfOctaves = 4;
        slided = false;
        normalize = false;
    }

    /**
     * A constructor initiating the given parameters.
     *
     * @param radius radius of the non-maximum region.
     * @param threshold minimum feature intensity.
     * @param maxFeaturesPerScale the maximum number of returned features per
     * scale, less equal to 0 returns all features finds.
     * @param initialSampleRate how often pixels are sampled in the first
     * octave.
     * @param initialSize width of the smallest feature/kernel in the lowest
     * octave.
     * @param numberScalesPerOctave different feature sizes are considered in a
     * single octave.
     * @param numberOfOctaves how many different octaves are considered.
     * @param slided true to enable sliding orientation estimator otherwise
     * false.
     * @param normalize the normalization option.
     */
    public ColorSurf(int radius, float threshold, int maxFeaturesPerScale, int initialSampleRate, int initialSize, int numberScalesPerOctave, int numberOfOctaves, boolean slided, boolean normalize) {
        this.radius = radius;
        this.threshold = threshold;
        this.maxFeaturesPerScale = maxFeaturesPerScale;
        this.initialSampleRate = initialSampleRate;
        this.initialSize = initialSize;
        this.numberScalesPerOctave = numberScalesPerOctave;
        this.numberOfOctaves = numberOfOctaves;
        this.slided = slided;
        this.normalize = normalize;
    }

    /**
     * A method detecting a visual description given an image item.
     *
     * @param image the image item.
     * @return the visual description detected.
     * @throws Exception throws unknown error exceptions.
     */
    @Override
    public Description extract(BufferedImage image) throws Exception {
        // Setting up image representation
        MultiSpectral<ImageFloat32> colorful = ConvertBufferedImage.convertFromMulti(image, null, true, ImageFloat32.class);

        // Defining a fast hessian feature detection algorithm
        ConfigExtract ce = new ConfigExtract(radius, threshold, 5, true);
        NonMaxSuppression extractor = FactoryFeatureExtractor.nonmax(ce);

        FastHessianFeatureDetector<ImageSingleBand> detector = new FastHessianFeatureDetector<ImageSingleBand>(extractor, maxFeaturesPerScale, initialSampleRate, initialSize, numberScalesPerOctave, numberOfOctaves);

        // Working off of integral images
        Class<ImageSingleBand> integralType = GIntegralImageOps.getIntegralType(ImageFloat32.class);

        // Setting up a sliding ii estimator algorithm for orientation
        ConfigSlidingIntegral csi = null;

        if (slided) {
            csi = new ConfigSlidingIntegral(0.65, Math.PI / 3.0, 8, -1, 6);
        }

        OrientationIntegral<ImageSingleBand> orientator = FactoryOrientationAlgs.sliding_ii(null, integralType);

        DescribePointSurf<ImageSingleBand> describer = FactoryDescribePointAlgs.<ImageSingleBand>surfStability(null, integralType);

        // Convert the colorful image to greyscale
        ImageFloat32 grayscale = ConvertImage.average((MultiSpectral<ImageFloat32>) colorful, null);

        // Computing the integral image of the grayscale image
        ImageSingleBand integralGrayscale = GeneralizedImageOps.createSingleBand(integralType, grayscale.width, grayscale.height);

        // Transforming grayscale image into integral
        GIntegralImageOps.transform(grayscale, integralGrayscale);

        // Detecting fast hessian features
        detector.detect(integralGrayscale);

        // Telling algorithms which image to process
        orientator.setImage(integralGrayscale);

        // Computing the orientation angles for each point
        List<ScalePoint> points = detector.getFoundPoints();

        // Checking if no interest points detected within image
        if (points.isEmpty()) {
            throw new Exception("No local colorful SURF descriptors detected for the given image");
        }

        double[] angles = new double[points.size()];

        for (int i = 0; i < points.size(); i++) {
            ScalePoint p = points.get(i);

            orientator.setScale(p.scale);
            angles[i] = orientator.compute(p.x, p.y);
        }

        double[][] descriptors = new double[points.size()][3 * describer.getDescriptionLength()];

        // Computing for each color band regarding rgb
        for (int i = 0; i < 3; i++) {
            // Setting the next band
            ImageFloat32 band = null;

            if (colorful.getNumBands() == 1) {
                band = colorful.getBand(0);
            } else {
                band = colorful.getBand(i);
            }

            // Computing integral colorful image of the next band
            ImageSingleBand integralBand = GeneralizedImageOps.createSingleBand(integralType, band.width, band.height);
            GIntegralImageOps.transform(band, integralBand);

            // Telling algorithms which image to process
            describer.setImage(integralBand);

            // Extracting local descriptors for each point
            for (int j = 0; j < points.size(); j++) {
                ScalePoint p = points.get(j);

                SurfFeature descriptor = describer.createDescription();

                describer.describe(p.x, p.y, angles[j], p.scale, (TupleDesc_F64) descriptor);

                double[] bandDescriptor = descriptor.getValue();

                for (int k = 0; k < bandDescriptor.length; k++) {
                    descriptors[j][i * bandDescriptor.length + k] = bandDescriptor[k];
                }
            }
        }

        // Normalizing the final local descriptors
        if (normalize) {
            for (int i = 0; i < descriptors.length; i++) {
                Normalizer.euclidean(descriptors[i]);
            }
        }

        return new Description(descriptors);
    }

    public int getRadius() {
        return radius;
    }

    public void setRadius(int radius) {
        this.radius = radius;
    }

    public float getThreshold() {
        return threshold;
    }

    public void setThreshold(float threshold) {
        this.threshold = threshold;
    }

    public int getMaxFeaturesPerScale() {
        return maxFeaturesPerScale;
    }

    public void setMaxFeaturesPerScale(int maxFeaturesPerScale) {
        this.maxFeaturesPerScale = maxFeaturesPerScale;
    }

    public int getInitialSampleRate() {
        return initialSampleRate;
    }

    public void setInitialSampleRate(int initialSampleRate) {
        this.initialSampleRate = initialSampleRate;
    }

    public int getInitialSize() {
        return initialSize;
    }

    public void setInitialSize(int initialSize) {
        this.initialSize = initialSize;
    }

    public int getNumberScalesPerOctave() {
        return numberScalesPerOctave;
    }

    public void setNumberScalesPerOctave(int numberScalesPerOctave) {
        this.numberScalesPerOctave = numberScalesPerOctave;
    }

    public int getNumberOfOctaves() {
        return numberOfOctaves;
    }

    public void setNumberOfOctaves(int numberOfOctaves) {
        this.numberOfOctaves = numberOfOctaves;
    }

    public boolean isSlided() {
        return slided;
    }

    public void setSlided(boolean slided) {
        this.slided = slided;
    }

    public boolean isNormalize() {
        return normalize;
    }

    public void setNormalize(boolean normalize) {
        this.normalize = normalize;
    }
}
