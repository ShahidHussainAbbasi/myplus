package com.myplus.business_service.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.EnumMap;
import java.util.Map;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

/**
 * The QR code a tax authority wants on the face of an invoice.
 *
 * <h3>Why this is on the SERVER and not in the browser</h3>
 * The same code has to appear on three outputs — the printed HTML, the PDF, and the ESC/POS raster bitmap.
 * Generating it per output format is three implementations of one fact, and they drift: the invoice a
 * customer photographs would eventually not match the one filed. Built once here, it travels on the document
 * payload as a data URI and every renderer simply draws it.
 *
 * <h3>Why the ENCODING is not hand-written</h3>
 * A QR encoder is Reed–Solomon error correction plus mask selection. A subtle error produces a code that
 * scans on the developer's phone and fails on an inspector's — the worst possible failure, because it looks
 * finished. ZXing does that part. Turning the resulting {@link BitMatrix} into a PNG is a loop over pixels
 * and is the only thing written here.
 *
 * <h3>The payload is a TEMPLATE, because the requirement is not ours to fix</h3>
 * Pakistan's FBR wants one arrangement of fields; another authority wants another; both change by regulation
 * more often than this product ships. A tenant writes the layout their rules ask for and we substitute the
 * values — so a new jurisdiction is a settings change, never a release.
 */
@Service
public class DocumentQrService {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentQrService.class);

    /**
     * A hard ceiling on the payload.
     *
     * <p>QR capacity falls away sharply with length, and a template that accidentally interpolates something
     * huge would produce a dense code no thermal printer can render legibly at 200 dots. Refusing is better
     * than printing a square nobody can scan.
     */
    private static final int MAX_PAYLOAD = 512;

    /**
     * Substitute the document's values into the tenant's template.
     *
     * <p>An unknown placeholder is left ALONE rather than blanked: a shop that mistypes {@code {totl}} should
     * see it on the sample they print while setting this up, not silently lose a field their tax authority
     * requires. Silence is the failure mode that reaches an audit.
     */
    public String renderPayload(String template, Map<String, String> values) {
        if (template == null || template.isBlank()) return null;
        String out = template;
        for (Map.Entry<String, String> e : values.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        }
        return out;
    }

    /**
     * The QR for a payload, as a {@code data:image/png;base64,...} URI.
     *
     * @param size pixels square. 200 suits an 80mm roll: large enough for a phone camera, small enough that
     *             a thermal head resolves the modules without them bleeding into each other.
     * @return null when there is nothing to encode, or when encoding failed — a document must print without
     *         its QR rather than not print at all. A missing square is visible; a failed sale is not.
     */
    public String dataUri(String payload, int size) {
        if (payload == null || payload.isBlank()) return null;
        if (payload.length() > MAX_PAYLOAD) {
            LOG.warn("QR payload is {} characters, over the {} limit — not printing a code that could not be "
                    + "scanned. Shorten pos.document.qrTemplate.", payload.length(), MAX_PAYLOAD);
            return null;
        }
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            /*
             * Error correction M (~15%). L would fit more data but a till receipt gets folded, thermal print
             * fades, and the code still has to scan months later off a crumpled slip. H would be tougher
             * still and makes the modules smaller at a fixed size, which a 203dpi head then cannot resolve.
             */
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 1);

            int px = Math.max(80, Math.min(size <= 0 ? 200 : size, 600));
            BitMatrix matrix = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, px, px, hints);

            BufferedImage img = new BufferedImage(matrix.getWidth(), matrix.getHeight(),
                    BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < matrix.getHeight(); y++) {
                for (int x = 0; x < matrix.getWidth(); x++) {
                    img.setRGB(x, y, matrix.get(x, y) ? 0x000000 : 0xFFFFFF);
                }
            }

            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(img, "png", png);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(png.toByteArray());
        } catch (Exception failed) {
            // Best-effort, like the letterhead and the GL post: a shop must always be able to hand over a
            // receipt. The warning is how an operator finds out the code is missing.
            LOG.warn("Could not build the document QR (payload {} chars) — the document prints without it.",
                    payload.length(), failed);
            return null;
        }
    }
}
