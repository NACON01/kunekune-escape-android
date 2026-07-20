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

/** 固定シードで、印刷後も特徴点が残りやすい幾何学マーカーを生成する。 */
public final class GenerateMarker {
    private static final int SIZE = 1024;
    private static final long SEED = 0x4B554E454B554E45L;

    private GenerateMarker() {
    }

    public static void main(String[] args) throws IOException {
        File output = new File("app/src/main/assets/marker.png");
        File parent = output.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) {
            throw new IOException("出力先を作成できません: " + parent);
        }

        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_BYTE_BINARY);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, SIZE, SIZE);

            graphics.setColor(Color.BLACK);
            graphics.setStroke(new BasicStroke(26f));
            graphics.drawRect(30, 30, SIZE - 60, SIZE - 60);
            graphics.setStroke(new BasicStroke(8f));
            graphics.drawRect(76, 76, SIZE - 152, SIZE - 152);

            // 大小の異なる図形を重ね、左右非対称の輪郭を作る。
            drawRing(graphics, 256, 270, 154, 58);
            drawRing(graphics, 256, 270, 68, 22);
            graphics.fillOval(222, 236, 68, 68);

            Path2D triangle = new Path2D.Double();
            triangle.moveTo(540, 132);
            triangle.lineTo(874, 300);
            triangle.lineTo(608, 448);
            triangle.closePath();
            graphics.fill(triangle);

            graphics.setColor(Color.WHITE);
            graphics.fillRect(610, 242, 174, 56);
            graphics.fillOval(704, 326, 74, 74);

            graphics.setColor(Color.BLACK);
            graphics.fillRect(152, 496, 286, 104);
            graphics.fillRect(206, 646, 136, 190);
            graphics.fillRect(394, 684, 248, 88);

            graphics.setColor(Color.WHITE);
            graphics.fillRect(198, 522, 78, 52);
            graphics.fillRect(310, 522, 104, 52);
            graphics.fillRect(236, 680, 76, 122);
            graphics.fillRect(432, 700, 172, 42);

            graphics.setColor(Color.BLACK);
            drawRing(graphics, 760, 700, 126, 34);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(702, 664, 116, 48);
            graphics.setColor(Color.BLACK);
            graphics.fillOval(734, 676, 52, 52);

            // 配置は固定し、ランダムノイズは描かない。
            Random layout = new Random(SEED);
            for (int i = 0; i < 7; i++) {
                int x = 120 + layout.nextInt(760);
                int y = 120 + layout.nextInt(760);
                int size = 18 + layout.nextInt(42);
                graphics.fillRect(x, y, size, 12);
                graphics.fillRect(x, y, 12, size);
            }
        } finally {
            graphics.dispose();
        }

        if (!ImageIO.write(image, "png", output)) {
            throw new IOException("PNGを書き出せません: " + output);
        }
        System.out.println("生成しました: " + output.getPath());
    }

    private static void drawRing(Graphics2D graphics, int x, int y, int outer, int inner) {
        graphics.fillOval(x - outer, y - outer, outer * 2, outer * 2);
        graphics.setColor(Color.WHITE);
        graphics.fillOval(x - outer + inner, y - outer + inner,
                (outer - inner) * 2, (outer - inner) * 2);
        graphics.setColor(Color.BLACK);
    }
}
