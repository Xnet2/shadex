import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Draws icon.png (the launcher icon, for the README and the website). Run: java tools/MakeIcon.java */
public class MakeIcon {
    public static void main(String[] a) throws Exception {
        int s = 512;
        float k = s / 72f; // the icon's visible area is 72 of its 108 units, from 18 to 90
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setClip(new RoundRectangle2D.Float(0, 0, s, s, s * 0.45f, s * 0.45f));
        g.setPaint(new GradientPaint(0, 0, new Color(0x3A3A3C), s, s, new Color(0x050505)));
        g.fillRect(0, 0, s, s);
        g.translate(-18 * k, -18 * k);
        g.scale(k, k);
        Color white = Color.WHITE;
        g.setColor(new Color(1f, 1f, 1f, 0.16f));
        g.fill(new RoundRectangle2D.Float(28, -20, 52, 88, 22, 22));
        g.setColor(white);
        g.fill(new Ellipse2D.Float(35, 27, 14, 14));
        g.setColor(new Color(1f, 1f, 1f, 0.45f));
        g.fill(new Ellipse2D.Float(53, 27, 14, 14));
        g.setColor(new Color(1f, 1f, 1f, 0.35f));
        g.fill(new RoundRectangle2D.Float(36, 46, 34, 8, 8, 8));
        g.setColor(white);
        g.fill(new RoundRectangle2D.Float(36, 46, 20, 8, 8, 8));
        g.setColor(new Color(1f, 1f, 1f, 0.7f));
        g.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Float(47, 62, 61, 62));
        g.dispose();
        ImageIO.write(img, "png", new File("icon.png"));
    }
}
