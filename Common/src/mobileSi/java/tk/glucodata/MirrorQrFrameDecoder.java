package tk.glucodata;

import androidx.camera.core.ImageProxy;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.Map;

final class MirrorQrFrameDecoder {
    private static final Map<DecodeHintType, Object> HINTS = new EnumMap<>(DecodeHintType.class);

    static {
        HINTS.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        HINTS.put(DecodeHintType.ALSO_INVERTED, Boolean.TRUE);
    }

    private MirrorQrFrameDecoder() {
    }

    static String decode(ImageProxy imageProxy) {
        try {
            ImageProxy.PlaneProxy[] planes = imageProxy.getPlanes();
            if (planes.length == 0) {
                return null;
            }
            ImageProxy.PlaneProxy yPlane = planes[0];
            ByteBuffer source = yPlane.getBuffer().duplicate();
            byte[] luma = new byte[source.remaining()];
            source.get(luma);
            return decodeLuma(
                    luma,
                    imageProxy.getWidth(),
                    imageProxy.getHeight(),
                    yPlane.getRowStride(),
                    yPlane.getPixelStride()
            );
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    static String decodeLuma(
            byte[] source,
            int width,
            int height,
            int rowStride,
            int pixelStride
    ) {
        if (source == null || width <= 0 || height <= 0 || rowStride <= 0 || pixelStride <= 0) {
            return null;
        }

        long packedSize = (long) width * height;
        long sourceSize = (long) (height - 1) * rowStride + (long) (width - 1) * pixelStride + 1;
        if (packedSize > Integer.MAX_VALUE || sourceSize > source.length) {
            return null;
        }

        byte[] packed = new byte[(int) packedSize];
        for (int row = 0; row < height; row++) {
            int sourceRow = row * rowStride;
            int targetRow = row * width;
            int lastSource = sourceRow + (width - 1) * pixelStride;
            if (sourceRow < 0 || lastSource >= source.length) {
                return null;
            }
            if (pixelStride == 1) {
                System.arraycopy(source, sourceRow, packed, targetRow, width);
            } else {
                for (int column = 0; column < width; column++) {
                    packed[targetRow + column] = source[sourceRow + column * pixelStride];
                }
            }
        }

        PlanarYUVLuminanceSource luminance = new PlanarYUVLuminanceSource(
                packed,
                width,
                height,
                0,
                0,
                width,
                height,
                false
        );
        try {
            Result result = new QRCodeReader().decode(
                    new BinaryBitmap(new HybridBinarizer(luminance)),
                    HINTS
            );
            return result.getText();
        } catch (NotFoundException ignored) {
            return null;
        } catch (com.google.zxing.FormatException | com.google.zxing.ChecksumException ignored) {
            return null;
        }
    }
}
