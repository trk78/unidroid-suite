package app.unidroid.launcher;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The pairing code, shown over whatever is on screen when a new phone opens the remote.
 * Started by the root remote server; closes when the phone is paired (extra "done"),
 * on Back, or after three minutes.
 */
public final class PairActivity extends Activity {
    private static final long TIMEOUT_MS = 3 * 60_000L;

    private TextView code, name;
    private final Runnable close = this::finish;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (getIntent().getBooleanExtra("done", false)) { // paired before this window showed
            finish();
            return;
        }
        Ui ui = new Ui(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.withAlpha(Ui.INK, 170));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(ui.px(72), ui.px(56), ui.px(72), ui.px(56));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.SHEET);
        bg.setCornerRadius(ui.px(36));
        card.setBackground(bg);

        TextView title = ui.text(34, Ui.FG, Typeface.DEFAULT_BOLD);
        title.setText(R.string.pair_title);
        card.addView(title);
        name = ui.text(24, Ui.DIM, Typeface.DEFAULT);
        name.setPadding(0, ui.px(10), 0, ui.px(28));
        card.addView(name);
        code = ui.text(150, Ui.FG, Typeface.create("sans-serif-light", Typeface.NORMAL));
        code.setLetterSpacing(0.25f);
        card.addView(code);
        TextView help = ui.text(24, Ui.DIM, Typeface.DEFAULT);
        help.setText(R.string.pair_help);
        help.setGravity(Gravity.CENTER);
        help.setPadding(0, ui.px(24), 0, 0);
        card.addView(help);

        root.addView(card, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        setContentView(root);
        show(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        show(intent);
    }

    private void show(Intent intent) {
        if (intent.getBooleanExtra("done", false)) {
            finish();
            return;
        }
        code.setText(intent.getStringExtra("code"));
        String who = intent.getStringExtra("name");
        name.setText(getString(R.string.pair_from, who == null || who.isEmpty() ? "?" : who));
        code.removeCallbacks(close);
        code.postDelayed(close, TIMEOUT_MS);
    }

    @Override
    protected void onDestroy() {
        if (code != null) code.removeCallbacks(close);
        super.onDestroy();
    }
}
