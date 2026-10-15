package app.unidroid.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

/** Draws a {@link QrCode} on a white card with a quiet zone, scaled to the view. */
final class QrView extends android.view.View {
    private static final int QUIET = 3;
    private QrCode qr;
    private final Paint dark = new Paint();

    QrView(Context ctx) {
        super(ctx);
        dark.setColor(Ui.INK);
    }

    void setText(String text) {
        try {
            qr = QrCode.encode(text);
        } catch (IllegalArgumentException e) {
            qr = null;
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(Color.WHITE);
        if (qr == null) return;
        int cells = qr.size + QUIET * 2;
        float side = Math.min(getWidth(), getHeight());
        // Whole pixels per module keep the code sharp on a 720p projector.
        float cell = Math.max(1, (float) Math.floor(side / cells));
        float off = (side - cell * cells) / 2 + QUIET * cell;
        float ox = (getWidth() - side) / 2 + off, oy = (getHeight() - side) / 2 + off;
        for (int y = 0; y < qr.size; y++) {
            for (int x = 0; x < qr.size; x++) {
                if (qr.get(x, y)) c.drawRect(ox + x * cell, oy + y * cell, ox + (x + 1) * cell, oy + (y + 1) * cell, dark);
            }
        }
    }
}
