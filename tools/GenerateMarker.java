import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Random;
import javax.imageio.ImageIO;

/** Generates a deterministic, feature-rich ARCore image marker. */
public final class GenerateMarker {
    private static final int SIZE = 1024;
    private static final int BORDER = 38;
    private static final long SEED = 0x4B554E454B554E45L;

    private GenerateMarker() {
    }

    public static void main(String[] args) throws IOException {
        File output = new File("app/src/main/assets/marker.png");
        File parent = output.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create output directory: " + parent);
        }

        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_BYTE_BINARY);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, SIZE, SIZE);

            graphics.setColor(Color.BLACK);
            graphics.setStroke(new BasicStroke(24f));
            graphics.drawRect(BORDER, BORDER, SIZE - BORDER * 2, SIZE - BORDER * 2);
            graphics.setStroke(new BasicStroke(5f));
            graphics.drawRect(BORDER + 25, BORDER + 25, SIZE - (BORDER + 25) * 2, SIZE - (BORDER + 25) * 2);

            Random layout = new Random(SEED);
            drawFeatureField(graphics, layout);
            drawAsymmetricLandmarks(graphics, layout);
        } finally {
            graphics.dispose();
        }

        if (!ImageIO.write(image, "png", output)) {
            throw new IOException("Could not write PNG: " + output);
        }
        System.out.println("Generated: " + output.getPath());
    }

    /**
     * Fills an 8x8 field with varied, non-repeating shapes. Keeping one cluster per cell
     * makes features available across the whole marker, while the seeded jitter prevents
     * the layout from becoming a regular texture.
     */
    private static void drawFeatureField(Graphics2D graphics, Random random) {
        final int cells = 8;
        final int cellSize = 112;
        final int origin = 120;

        for (int row = 0; row < cells; row++) {
            for (int column = 0; column < cells; column++) {
                int centerX = origin + column * cellSize + random.nextInt(49) - 24;
                int centerY = origin + row * cellSize + random.nextInt(49) - 24;
                int shapeCount = 1 + random.nextInt(3);
                for (int shape = 0; shape < shapeCount; shape++) {
                    drawRandomShape(graphics, random, centerX, centerY);
                    centerX += random.nextInt(37) - 18;
                    centerY += random.nextInt(37) - 18;
                }
            }
        }

        // Extra small details increase corner density without making a repeated motif.
        for (int i = 0; i < 42; i++) {
            int x = 82 + random.nextInt(860);
            int y = 82 + random.nextInt(860);
            int width = 7 + random.nextInt(25);
            int height = 7 + random.nextInt(25);
            graphics.setColor(Color.BLACK);
            if (random.nextBoolean()) {
                graphics.fillRect(x, y, width, height);
            } else {
                graphics.setStroke(new BasicStroke(3f + random.nextInt(5)));
                graphics.drawLine(x, y, x + width, y + height);
            }
        }
    }

    private static void drawRandomShape(Graphics2D graphics, Random random, int centerX, int centerY) {
        graphics.setColor(Color.BLACK);
        switch (random.nextInt(5)) {
            case 0:
                drawIrregularPolygon(graphics, random, centerX, centerY);
                break;
            case 1:
                int width = 13 + random.nextInt(35);
                int height = 11 + random.nextInt(35);
                graphics.fillRect(centerX - width / 2, centerY - height / 2, width, height);
                break;
            case 2:
                graphics.setStroke(new BasicStroke(3f + random.nextInt(8)));
                graphics.drawLine(
                        centerX - 8 - random.nextInt(25),
                        centerY - 8 - random.nextInt(25),
                        centerX + 8 + random.nextInt(25),
                        centerY + 8 + random.nextInt(25));
                break;
            case 3:
                drawAngularStroke(graphics, random, centerX, centerY);
                break;
            default:
                int outer = 13 + random.nextInt(25);
                graphics.fillOval(centerX - outer, centerY - outer, outer * 2, outer * 2);
                graphics.setColor(Color.WHITE);
                int inner = 4 + random.nextInt(Math.max(5, outer - 5));
                graphics.fillOval(centerX - inner, centerY - inner, inner * 2, inner * 2);
                break;
        }
    }

    private static void drawIrregularPolygon(Graphics2D graphics, Random random, int centerX, int centerY) {
        int sides = 3 + random.nextInt(5);
        double rotation = random.nextDouble() * Math.PI * 2.0;
        Path2D polygon = new Path2D.Double();
        for (int i = 0; i < sides; i++) {
            double angle = rotation + i * Math.PI * 2.0 / sides;
            int radiusX = 10 + random.nextInt(27);
            int radiusY = 10 + random.nextInt(27);
            double x = centerX + Math.cos(angle) * radiusX;
            double y = centerY + Math.sin(angle) * radiusY;
            if (i == 0) {
                polygon.moveTo(x, y);
            } else {
                polygon.lineTo(x, y);
            }
        }
        polygon.closePath();
        graphics.fill(polygon);
    }

    private static void drawAngularStroke(Graphics2D graphics, Random random, int centerX, int centerY) {
        Path2D stroke = new Path2D.Double();
        stroke.moveTo(centerX - 25, centerY + random.nextInt(17) - 8);
        int points = 2 + random.nextInt(3);
        for (int i = 1; i <= points; i++) {
            stroke.lineTo(
                    centerX - 25 + i * 15 + random.nextInt(17),
                    centerY + random.nextInt(45) - 22);
        }
        graphics.setStroke(new BasicStroke(3f + random.nextInt(7), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
        graphics.draw(stroke);
    }

    /** Adds a few intentionally unbalanced landmarks to make the overall image asymmetric. */
    private static void drawAsymmetricLandmarks(Graphics2D graphics, Random random) {
        graphics.setColor(Color.BLACK);

        Path2D landmark = new Path2D.Double();
        landmark.moveTo(154, 166);
        landmark.lineTo(214, 142);
        landmark.lineTo(251, 187);
        landmark.lineTo(226, 226);
        landmark.lineTo(169, 211);
        landmark.closePath();
        graphics.fill(landmark);
        graphics.setColor(Color.WHITE);
        graphics.fillRect(187, 175, 28, 11);

        graphics.setColor(Color.BLACK);
        graphics.setStroke(new BasicStroke(11f));
        graphics.drawLine(760, 780, 894, 846);
        graphics.drawLine(894, 846, 842, 900);
        graphics.setStroke(new BasicStroke(4f));
        graphics.drawLine(735, 814, 806, 875);

        graphics.fillRect(478, 790, 31, 69);
        graphics.setColor(Color.WHITE);
        graphics.fillRect(484, 806, 12, 22);

        // A final seeded set of isolated corners keeps the lower-detail areas distinctive.
        graphics.setColor(Color.BLACK);
        for (int i = 0; i < 12; i++) {
            int x = 110 + random.nextInt(790);
            int y = 110 + random.nextInt(790);
            graphics.fillRect(x, y, 9 + random.nextInt(14), 9 + random.nextInt(14));
        }
    }
}
