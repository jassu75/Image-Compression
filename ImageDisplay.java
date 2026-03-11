
import java.awt.*;
import java.awt.event.*;
import java.awt.image.*;
import java.io.*;
import java.util.*;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import javax.swing.*;

public class ImageDisplay {

    JFrame frame;
    JLabel lbIm1;
    BufferedImage imgOne;

    // Modify the height and width values here to read and display an image with
    // different dimensions.
    int width = 512;
    int height = 512;
    int maxBlockSize = 32;
    int minBlockSize = 2;
    boolean showGrid = false;

    /**
     * Read Image RGB
     * Reads the image of given width and height at the given imgPath into the
     * provided BufferedImage.
     */
    private void readImageRGB(int width, int height, String imgPath, BufferedImage img) {
        try {
            int frameLength = width * height * 3;

            File file = new File(imgPath);
            RandomAccessFile raf = new RandomAccessFile(file, "r");
            raf.seek(0);

            long len = frameLength;
            byte[] bytes = new byte[(int) len];

            raf.read(bytes);

            int ind = 0;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    byte a = 0;
                    byte r = bytes[ind];
                    byte g = bytes[ind + height * width];
                    byte b = bytes[ind + height * width * 2];

                    int pix = 0xff000000 | ((r & 0xff) << 16) | ((g & 0xff) << 8) | (b & 0xff);
                    // int pix = ((a << 24) + (r << 16) + (g << 8) + b);
                    img.setRGB(x, y, pix);
                    ind++;
                }
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private double lumaVariance(BufferedImage img, int x, int y, int blockSize) {
        int imgWidth = img.getWidth();
        int imgHeight = img.getHeight();
        int dx = Math.min(blockSize, imgWidth - x);
        int dy = Math.min(blockSize, imgHeight - y);

        double sum = 0, sumOfSquares = 0;
        int n = 0;

        for (int xi = x; xi < x + dx; xi++) {
            for (int yi = y; yi < y + dy; yi++) {

                int pixel = img.getRGB(xi, yi);
                Color color = new Color(pixel);
                int r = color.getRed(), g = color.getGreen(), b = color.getBlue();
                double luma = (0.299 * r) + (0.587 * g) + (0.114 * b);
                sum += luma;
                sumOfSquares += Math.pow(luma, 2);
                n++;

            }
        }

        if (n == 0) {
            return 0.0;
        }

        double mean = sum / n;
        double variance = (sumOfSquares / n) - (mean * mean);
        return variance;

    }

    private void setAdaptiveBlockSize(int[][] blockSizeMap, BufferedImage img, int x, int y, double varianceThreshold,
            int blockSize) {
        int imgWidth = img.getWidth();
        int imgHeight = img.getHeight();

        double lumaVariance = lumaVariance(img, x, y, blockSize);
        int halfBloackSize = (int) blockSize / 2;

        if (lumaVariance > varianceThreshold && halfBloackSize >= minBlockSize) {
            for (int dx = 0; dx < blockSize; dx += halfBloackSize) {
                for (int dy = 0; dy < blockSize; dy += halfBloackSize) {
                    if (x + dx < imgWidth && y + dy < imgHeight) {
                        setAdaptiveBlockSize(blockSizeMap, img, x + dx, y + dy, varianceThreshold, halfBloackSize);
                    }
                }
            }
        } else {
            for (int xi = x; xi < Math.min(x + blockSize, imgWidth); xi++) {
                for (int yi = y; yi < Math.min(y + blockSize, imgHeight); yi++) {
                    blockSizeMap[xi][yi] = blockSize;
                }

            }
        }

    }

    private double computeVarianceThreshold(BufferedImage img) {
        int imgWidth = img.getWidth();
        int imgHeight = img.getHeight();
        List<Double> variances = new ArrayList<>();
        for (int x = 0; x < imgWidth; x += maxBlockSize) {
            for (int y = 0; y < imgHeight; y += maxBlockSize) {
                double lumaVariance = lumaVariance(img, x, y, maxBlockSize);
                variances.add(lumaVariance);

            }
        }

        Collections.sort(variances);
        int index = (int) Math.floor(0.7 * (variances.size() - 1));
        return variances.get(index);

    }

    private int[][] buildBlockSizeMap(BufferedImage img, int M) {
        int imgWidth = img.getWidth();
        int imgHeight = img.getHeight();

        int[][] blockSizeMap = new int[imgWidth][imgHeight];

        if (M == 1) {
            for (int x = 0; x < imgWidth; x++) {
                for (int y = 0; y < imgHeight; y++) {
                    blockSizeMap[x][y] = 8;
                }
            }
        } else if (M == 2) {
            double varianceThreshold = computeVarianceThreshold(img);
            for (int x = 0; x < imgWidth; x += maxBlockSize) {
                for (int y = 0; y < imgHeight; y += maxBlockSize) {
                    setAdaptiveBlockSize(blockSizeMap, img, x, y, varianceThreshold, maxBlockSize);
                }
            }

        }

        return blockSizeMap;

    }

    private double[][][] encodeImage(BufferedImage img, int[][] blockSizeMap, int Q) {

        int imgWidth = img.getWidth();
        int imgHeight = img.getHeight();
        double[][][] encodedImage = new double[3][imgWidth][imgHeight];

        for (int x = 0; x < imgWidth; x++) {
            for (int y = 0; y < imgHeight; y++) {
                int blockSize = blockSizeMap[x][y];

                if (x % blockSize != 0 || y % blockSize != 0)
                    continue;

                int dx = Math.min(blockSize, imgWidth - x);
                int dy = Math.min(blockSize, imgHeight - y);
                int dctBlockSize = ceilPow2(Math.max(dx, dy));

                double[][][] freqCoefficients = new double[3][dctBlockSize][dctBlockSize];

                for (int dctX = 0; dctX < dctBlockSize; dctX++) {
                    int orgX = Math.min(x + dctX, imgWidth - 1);
                    for (int dctY = 0; dctY < dctBlockSize; dctY++) {
                        int orgY = Math.min(y + dctY, imgHeight - 1);
                        Color color = new Color(img.getRGB(orgX, orgY));
                        int[] rgb = { color.getRed(), color.getGreen(), color.getBlue() };
                        for (int i = 0; i < 3; i++)
                            freqCoefficients[i][dctX][dctY] = rgb[i];
                    }
                }

                for (int i = 0; i < 3; i++) {
                    freqCoefficients[i] = computeDCT(freqCoefficients[i], dctBlockSize);

                    for (int dctX = 0; dctX < dctBlockSize; dctX++)
                        for (int dctY = 0; dctY < dctBlockSize; dctY++)
                            freqCoefficients[i][dctX][dctY] = (long) (freqCoefficients[i][dctX][dctY]
                                    / Math.pow(2.0, Q));

                    for (int dctX = 0; dctX < dx; dctX++)
                        for (int dctY = 0; dctY < dy; dctY++)
                            encodedImage[i][x + dctX][y + dctY] = freqCoefficients[i][dctX][dctY];
                }

            }
        }
        return encodedImage;
    }

    private double[][] computeDCT(double[][] freqCoefficients, int blockSize) {

        double[][] dctCoefficients = new double[blockSize][blockSize];

        for (int u = 0; u < blockSize; u++) {
            for (int v = 0; v < blockSize; v++) {
                double s = 0.0;
                for (int x = 0; x < blockSize; x++) {
                    for (int y = 0; y < blockSize; y++) {
                        s += freqCoefficients[x][y]
                                * Math.cos(Math.PI * u * (2 * x + 1) / (2.0 * blockSize))
                                * Math.cos(Math.PI * v * (2 * y + 1) / (2.0 * blockSize));
                    }
                }

                double cu = (u == 0) ? Math.sqrt(1.0 / blockSize) : Math.sqrt(2.0 / blockSize);
                double cv = (v == 0) ? Math.sqrt(1.0 / blockSize) : Math.sqrt(2.0 / blockSize);

                dctCoefficients[u][v] = cu * cv * s;
            }
        }

        return dctCoefficients;
    }

    private double[][] computeInverseDCT(double[][] dctCoefficients, int blockSize) {

        double[][] spatial = new double[blockSize][blockSize];

        for (int x = 0; x < blockSize; x++) {
            for (int y = 0; y < blockSize; y++) {

                double s = 0.0;

                for (int u = 0; u < blockSize; u++) {
                    for (int v = 0; v < blockSize; v++) {

                        double cu = (u == 0) ? Math.sqrt(1.0 / blockSize) : Math.sqrt(2.0 / blockSize);
                        double cv = (v == 0) ? Math.sqrt(1.0 / blockSize) : Math.sqrt(2.0 / blockSize);

                        s += cu * cv * dctCoefficients[u][v]
                                * Math.cos(Math.PI * u * (2 * x + 1) / (2.0 * blockSize))
                                * Math.cos(Math.PI * v * (2 * y + 1) / (2.0 * blockSize));
                    }
                }

                spatial[x][y] = s;
            }
        }

        return spatial;
    }

    private void createDCT(String path, double[][][] encodedImage, int[][] blockSizeMap, int M, int Q) {
        try (DataOutputStream dos = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(path)))) {

            int imgWidth = encodedImage[0].length;
            int imgHeight = encodedImage[0][0].length;

            dos.writeInt(imgWidth);
            dos.writeInt(imgHeight);
            dos.writeInt(M);
            dos.writeInt(Q);

            for (int x = 0; x < imgWidth; x++) {
                for (int y = 0; y < imgHeight; y++) {
                    int blockSize = blockSizeMap[x][y];

                    if (x % blockSize != 0 || y % blockSize != 0)
                        continue;

                    int dx = Math.min(blockSize, imgWidth - x);
                    int dy = Math.min(blockSize, imgHeight - y);
                    int dctBlockSize = ceilPow2(Math.max(dx, dy));

                    dos.writeInt(dx);
                    dos.writeInt(dy);
                    dos.writeInt(dctBlockSize);

                    for (int i = 0; i < 3; i++) {
                        for (int xi = 0; xi < dx; xi++) {
                            for (int yi = 0; yi < dy; yi++) {
                                dos.writeFloat((float) encodedImage[i][x + xi][y + yi]);
                            }
                        }
                    }
                }

            }

        } catch (Exception e) {
            System.err.println("Error creating DCT file " + path);
            e.printStackTrace();
        }
    }

    private double zippedBpp(String path, double[][][] encodedImage) {
        try {
            int imgWidth = encodedImage[0].length;
            int imgHeight = encodedImage[0][0].length;

            byte[] raw = java.nio.file.Files.readAllBytes(new File(path).toPath());
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
                gz.write(raw);
            }
            return (bos.size() * 8.0) / (imgWidth * imgHeight);
        } catch (Exception e) {
            System.err.println("Error creating DCT file " + path);
            e.printStackTrace();
            return -1.0;
        }
    }

    private int computeQ(BufferedImage img, String path, int M, double B, int[][] blockSizeMap) {

        String tempPath = path + ".temp.DCT";
        int low = 0, high = 30, Q = high;

        while (low <= high) {
            int mid = (low + high) / 2;
            double[][][] encodedImage = encodeImage(img, blockSizeMap, mid);
            createDCT(tempPath, encodedImage, blockSizeMap, M, mid);
            double zippedBpp = zippedBpp(tempPath, encodedImage);

            if (zippedBpp <= B) {
                Q = mid;
                high = mid - 1;
            } else {
                low = mid + 1;
            }
        }

        new File(tempPath).delete();
        return Q;

    }

    private double[][][] decodeImage(double[][][] encodedImage, int[][] blockSizeMap, int Q) {
        int imgWidth = encodedImage[0].length;
        int imgHeight = encodedImage[0][0].length;
        double[][][] decodedImage = new double[3][imgWidth][imgHeight];

        for (int x = 0; x < imgWidth; x++) {
            for (int y = 0; y < imgHeight; y++) {
                int blockSize = blockSizeMap[x][y];

                if (x % blockSize != 0 || y % blockSize != 0)
                    continue;

                int dx = Math.min(blockSize, imgWidth - x);
                int dy = Math.min(blockSize, imgHeight - y);
                int dctBlockSize = ceilPow2(Math.max(dx, dy));

                double[][][] freqCoefficients = new double[3][dctBlockSize][dctBlockSize];

                for (int i = 0; i < 3; i++) {
                    for (int dctX = 0; dctX < dx; dctX++)
                        for (int dctY = 0; dctY < dy; dctY++)
                            freqCoefficients[i][dctX][dctY] = encodedImage[i][x + dctX][y + dctY] * Math.pow(2.0, Q);

                    freqCoefficients[i] = computeInverseDCT(freqCoefficients[i], dctBlockSize);

                    for (int dctX = 0; dctX < dx; dctX++)
                        for (int dctY = 0; dctY < dy; dctY++)
                            decodedImage[i][x + dctX][y + dctY] = Math.min(255,
                                    Math.max(0, freqCoefficients[i][dctX][dctY]));
                }
            }
        }

        return decodedImage;
    }

    private int ceilPow2(int n) {
        if (n <= 2) {
            return 2;
        }
        int power = 2;
        while (power < n) {
            power = power * 2;
        }
        return power;
    }

    private BufferedImage toBufferedImage(double[][][] input) {

        int imgWidth = input[0].length;
        int imgHeight = input[0][0].length;
        BufferedImage img = new BufferedImage(imgWidth, imgHeight, BufferedImage.TYPE_INT_RGB);

        for (int x = 0; x < imgWidth; x++) {
            for (int y = 0; y < imgHeight; y++) {

                int[] rgb = new int[3];
                for (int i = 0; i < 3; i++) {
                    rgb[i] = Math.max(0, Math.min(255, (int) Math.round(input[i][x][y])));
                }
                Color color = new Color(rgb[0], rgb[1], rgb[2]);
                img.setRGB(x, y, color.getRGB());
            }
        }

        return img;

    }

    BufferedImage drawBlocks(BufferedImage img, int[][] blockSizeMap) {

        int imgWidth = img.getWidth();
        int imgHeight = img.getHeight();

        BufferedImage imgCopy = new BufferedImage(imgWidth, imgHeight, img.getType());
        Graphics2D g2 = imgCopy.createGraphics();
        g2.drawImage(img, 0, 0, null);

        g2.setColor(Color.PINK);
        g2.setStroke(new BasicStroke(1f));

        for (int x = 0; x < imgWidth; x++) {
            for (int y = 0; y < imgHeight; y++) {
                int blockSize = blockSizeMap[x][y];

                if (x % blockSize != 0 || y % blockSize != 0)
                    continue;

                int dx = Math.min(blockSize, imgWidth - x);
                int dy = Math.min(blockSize, imgHeight - y);
                g2.drawRect(x, y, dx - 1, dy - 1);

            }
        }

        g2.dispose();
        return imgCopy;
    }

    public void showIms(String[] args) {

        if (args.length != 4) {
            System.out.println("Incorrect parameters. Require Path, M, Q, B. ");
            return;
        }
        String path = args[0];
        int M = Integer.parseInt(args[1]);
        int Q = Integer.parseInt(args[2]);
        double B = Double.parseDouble(args[3]);

        // Read in the specified image
        imgOne = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        readImageRGB(width, height, args[0], imgOne);
        int[][] blockSizeMap = buildBlockSizeMap(imgOne, M);

        if (Q == -1) {
            Q = computeQ(imgOne, path, M, B, blockSizeMap);
        }

        double[][][] encodedImage = encodeImage(imgOne, blockSizeMap, Q);

        String dctPath = path.substring(0, path.lastIndexOf('.')) + ".DCT";
        createDCT(dctPath, encodedImage, blockSizeMap, M, Q);

        double[][][] decodedImage = decodeImage(encodedImage, blockSizeMap, Q);
        BufferedImage output = toBufferedImage(decodedImage);
        BufferedImage outputWithBlocks = drawBlocks(output, blockSizeMap);

        // Use label to display the image
        frame = new JFrame();
        GridBagLayout gLayout = new GridBagLayout();
        frame.getContentPane().setLayout(gLayout);

        lbIm1 = new JLabel(new ImageIcon(output));

        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.CENTER;
        c.weightx = 0.5;
        c.gridx = 0;
        c.gridy = 0;

        frame.getContentPane().add(lbIm1, c);

        frame.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                char ch = e.getKeyChar();
                if (ch == 'b' || ch == 'B') {

                    showGrid = !showGrid;

                    lbIm1.setIcon(new ImageIcon(
                            showGrid ? outputWithBlocks : output));

                }
            }
        });

        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        frame.setFocusable(true);
        frame.requestFocusInWindow();
    }

    public static void main(String[] args) {
        ImageDisplay ren = new ImageDisplay();
        ren.showIms(args);
    }

}