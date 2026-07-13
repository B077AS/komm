package komm.ui.avatar;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
public class AvatarImageUtils {

    /**
     * Crops a square region out of {@code source} (centred, adjusted by zoom /
     * offset) and scales it to {@code targetSize × targetSize}.
     *
     * @param source     the original {@link BufferedImage}
     * @param targetSize side length of the resulting square image in pixels
     * @param zoom       zoom factor (1.0 = no zoom)
     * @param xOffset    horizontal pan offset in the range [-2, 2]
     * @param yOffset    vertical   pan offset in the range [-2, 2]
     * @return a new {@link BufferedImage} of size {@code targetSize × targetSize}
     */
    public static BufferedImage cropAndScale(BufferedImage source, int targetSize,
                                             double zoom, double xOffset, double yOffset) {
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();

        int cropSize = (int) (Math.min(sourceWidth, sourceHeight) / zoom);
        cropSize = Math.max(1, Math.min(cropSize, Math.min(sourceWidth, sourceHeight)));

        int centerX = sourceWidth / 2;
        int centerY = sourceHeight / 2;
        int offsetX = (int) (xOffset * (sourceWidth - cropSize) / 2);
        int offsetY = (int) (yOffset * (sourceHeight - cropSize) / 2);

        int x = Math.max(0, Math.min(sourceWidth - cropSize, centerX - cropSize / 2 + offsetX));
        int y = Math.max(0, Math.min(sourceHeight - cropSize, centerY - cropSize / 2 + offsetY));

        BufferedImage cropped = source.getSubimage(x, y, cropSize, cropSize);

        BufferedImage scaled = new BufferedImage(targetSize, targetSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = scaled.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.drawImage(cropped, 0, 0, targetSize, targetSize, null);
        g2d.dispose();
        return scaled;
    }
}
