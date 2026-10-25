package dev.axiom.lyrics;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.joml.Matrix3x2fStack;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/** Einstellungs-Menü (Taste K) im Stil moderner Clients: dunkles Panel, Seitenleiste, Karten, Schalter. */
public class LyricsScreen extends Screen {
    private static final int BG = 0xF2101319, SIDE = 0xFF0B0D12, CARD = 0xFF171B23, CARD_HI = 0xFF1E2430;
    private static final int TEXT = 0xFFE8EAF0, MUTED = 0xFF8B93A5, OFF = 0xFF3A4150;

    private static int tab = 0;
    private TextFieldWidget clientIdField;
    private int px, py, pw, ph, cx, cy, cw;
    private final int sideW = 96;

    public LyricsScreen() {
        super(Text.literal("Spotify Lyrics"));
    }

    private static int accent() { return 0xFF000000 | LyricsState.theme().glow(); }

    // ------------------------------------------------------------------ Aufbau

    @Override
    protected void init() {
        pw = Math.min(this.width - 16, 430);
        ph = Math.min(this.height - 16, 258);
        px = (this.width - pw) / 2;
        py = (this.height - ph) / 2;
        cx = px + sideW + 14;
        cw = pw - sideW - 28;
        cy = py + 42;

        String[] names = {"Übersicht", "Anzeige", "Verbindung"};
        for (int i = 0; i < names.length; i++) {
            addDrawableChild(new TabButton(px + 8, py + 54 + i * 24, sideW - 16, 20, names[i], i));
        }

        if (tab == 0) {
            addDrawableChild(new Toggle(cx, cy + 78, cw, 30, "Lyrics anzeigen",
                () -> LyricsState.enabled, v -> { LyricsState.enabled = v; LyricsState.saveSettings(); }));
        } else if (tab == 1) {
            addDrawableChild(new Segment(this.textRenderer, cx, cy, cw, 30, "Modus",
                new String[]{"Schwebend 3D", "Unten"},
                () -> LyricsState.mode, i -> { LyricsState.mode = i; LyricsState.saveSettings(); }));
            addDrawableChild(new Swatches(cx, cy + 38, cw, 30, "Farbe"));
            addDrawableChild(slider(cx, cy + 76, cw, "Größe", 40, 300, LyricsState.size * 100.0,
                v -> { LyricsState.size = (float) (v / 100.0); LyricsState.saveSettings(); },
                v -> Math.round(v) + " %"));
            addDrawableChild(slider(cx, cy + 114, cw, "Timing", -1500, 1500, LyricsState.offsetMs,
                v -> { LyricsState.offsetMs = (int) (Math.round(v / 10.0) * 10); LyricsState.saveSettings(); },
                v -> (Math.round(v / 10.0) * 10) + " ms"));
        } else {
            addDrawableChild(new Segment(this.textRenderer, cx, cy, cw, 30, "Quelle",
                new String[]{"Windows-Medien", "Spotify-Konto"},
                () -> LyricsState.source, i -> {
                    LyricsState.source = i;
                    LyricsState.saveSettings();
                    this.clearAndInit();
                }));
            if (LyricsState.source == 1) {
                clientIdField = new TextFieldWidget(this.textRenderer, cx + 8, cy + 82, cw - 16, 18, Text.literal("Client ID"));
                clientIdField.setMaxLength(64);
                clientIdField.setPlaceholder(Text.literal("Spotify Client ID einfügen"));
                if (SpotifyAuth.clientId != null) clientIdField.setText(SpotifyAuth.clientId);
                addDrawableChild(clientIdField);
                addDrawableChild(new PillButton(cx + 8, cy + 106, cw - 16, 20, "Mit Spotify verbinden", () -> {
                    String id = clientIdField.getText().trim();
                    if (id.isEmpty() && SpotifyAuth.clientId == null) {
                        LyricsState.status = "Bitte zuerst die Client ID eintragen.";
                    } else {
                        LyricsState.status = "Browser öffnet sich ...";
                        SpotifyAuth.login(id.isEmpty() ? null : id);
                    }
                }));
            }
        }
    }

    private Slide slider(int x, int y, int w, String label, double min, double max, double value,
                         DoubleConsumer onChange, DoubleFunction<String> fmt) {
        return new Slide(this.textRenderer, x, y, w, 30, label, min, max, value, onChange, fmt);
    }

    // ------------------------------------------------------------------ Zeichnen

    @Override
    public void renderBackground(DrawContext g, int mouseX, int mouseY, float delta) {
        super.renderBackground(g, mouseX, mouseY, delta);
        TextRenderer tr = this.textRenderer;
        int ac = accent();

        // Panel und Seitenleiste
        rrect(g, px - 1, py - 1, pw + 2, ph + 2, 8, 0x40FFFFFF);
        rrect(g, px, py, pw, ph, 7, BG);
        rrect(g, px, py, sideW, ph, 7, SIDE);
        g.fill(px + sideW - 8, py, px + sideW, py + ph, SIDE);

        rrect(g, px + 12, py + 14, 8, 8, 4, ac);
        big(g, tr, "Lyrics", px + 26, py + 13, 1.3f, TEXT);
        g.drawText(tr, "für Minecraft", px + 12, py + 30, MUTED, false);
        g.drawText(tr, "Taste K = Menü", px + 12, py + ph - 16, 0xFF5B6377, false);

        // Titel der Seite
        String[] titles = {"Übersicht", "Anzeige", "Verbindung"};
        big(g, tr, titles[tab], cx, py + 14, 1.5f, TEXT);

        if (tab == 0) drawOverview(g, tr);
        else if (tab == 1) drawCards(g, new int[][]{{0, 30}, {38, 30}, {76, 30}, {114, 30}});
        else drawConnection(g, tr);
    }

    private void drawCards(DrawContext g, int[][] cards) {
        for (int[] c : cards) rrect(g, cx, cy + c[0], cw, c[1], 5, CARD);
    }

    private void drawOverview(DrawContext g, TextRenderer tr) {
        int ac = accent();
        // Karte: Jetzt läuft
        rrect(g, cx, cy, cw, 70, 5, CARD);
        g.drawText(tr, "JETZT LÄUFT", cx + 10, cy + 8, MUTED, false);

        String title = LyricsState.title.isEmpty() ? "Nichts erkannt" : LyricsState.title;
        String artist = LyricsState.artist.isEmpty() ? "Starte Spotify (App oder Browser)" : LyricsState.artist;
        big(g, tr, tr.trimToWidth(title, (int) ((cw - 20) / 1.25f)), cx + 10, cy + 20, 1.25f, TEXT);
        g.drawText(tr, tr.trimToWidth(artist, cw - 20), cx + 10, cy + 36, MUTED, false);

        // Fortschritt
        long dur = LyricsState.durationMs;
        float frac = dur > 0 ? Math.max(0f, Math.min(1f, LyricsState.now() / (float) dur)) : 0f;
        int bx = cx + 10, bw = cw - 20, by = cy + 54;
        rrect(g, bx, by, bw, 4, 2, OFF);
        if (frac > 0.01f) rrect(g, bx, by, Math.max(4, (int) (bw * frac)), 4, 2, ac);

        // Karte: Status
        rrect(g, cx, cy + 116, cw, 44, 5, CARD);
        int dot = statusColor();
        rrect(g, cx + 10, cy + 124, 8, 8, 4, dot);
        g.drawText(tr, LyricsState.lines.isEmpty() ? "Status" : "Lyrics bereit - " + LyricsState.lines.size() + " Zeilen",
            cx + 24, cy + 124, TEXT, false);
        String s = LyricsState.status;
        String l1 = tr.trimToWidth(s, cw - 20);
        g.drawText(tr, l1, cx + 10, cy + 138, MUTED, false);
        if (s.length() > l1.length()) {
            g.drawText(tr, tr.trimToWidth(s.substring(l1.length()), cw - 20), cx + 10, cy + 148, MUTED, false);
        }
    }

    private void drawConnection(DrawContext g, TextRenderer tr) {
        rrect(g, cx, cy, cw, 30, 5, CARD);
        if (LyricsState.source == 0) {
            rrect(g, cx, cy + 38, cw, 96, 5, CARD);
            String[] t = {
                "Liest den Song aus der Windows-Medienanzeige.",
                "Funktioniert mit der Spotify-App und mit",
                "Spotify im Browser. Kein Login, kein Premium",
                "und keine Entwickler-App nötig.",
                "",
                "Status: " + LyricsState.status
            };
            for (int i = 0; i < t.length; i++) {
                String line = tr.trimToWidth(t[i], cw - 20);
                g.drawText(tr, line, cx + 10, cy + 48 + i * 12, i == 5 ? MUTED : TEXT, false);
            }
        } else {
            rrect(g, cx, cy + 38, cw, 94, 5, CARD);
            g.drawText(tr, "Client ID deiner Spotify-App", cx + 10, cy + 46, MUTED, false);
            g.drawText(tr, tr.trimToWidth("Redirect URI: http://127.0.0.1:8888/callback", cw - 20), cx + 10, cy + 60, TEXT, false);
            g.drawText(tr, tr.trimToWidth("Seit 2026 braucht die App Premium.", cw - 20), cx + 10, cy + 70, MUTED, false);
            String s = tr.trimToWidth(LyricsState.status, cw - 20);
            g.drawText(tr, s, cx + 10, cy + 136, MUTED, false);
        }
    }

    private static int statusColor() {
        String s = LyricsState.status;
        if (!LyricsState.lines.isEmpty()) return 0xFF4ADE80;
        if (s.startsWith("Lade") || s.startsWith("Suche") || s.startsWith("Verbinde") || s.startsWith("Browser")) return 0xFFFBBF24;
        if (s.contains("Fehler") || s.contains("Keine") || s.contains("403") || s.contains("blockiert")) return 0xFFF87171;
        return 0xFF8B93A5;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    // ------------------------------------------------------------------ Hilfsfunktionen

    static void rrect(DrawContext g, int x, int y, int w, int h, int r, int color) {
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        if (r == 0) { g.fill(x, y, x + w, y + h, color); return; }
        g.fill(x, y + r, x + w, y + h - r, color);
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5;
            int inset = (int) Math.round(r - Math.sqrt(r * r - dy * dy));
            g.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
            g.fill(x + inset, y + h - 1 - i, x + w - inset, y + h - i, color);
        }
    }

    static void big(DrawContext g, TextRenderer tr, String s, int x, int y, float scale, int color) {
        Matrix3x2fStack m = g.getMatrices();
        m.pushMatrix();
        m.translate(x, y);
        m.scale(scale, scale);
        g.drawText(tr, s, 0, 0, color, false);
        m.popMatrix();
    }

    // ------------------------------------------------------------------ eigene Bedienelemente

    private abstract static class Base extends ClickableWidget {
        final int bx, by, bw, bh;
        int mx, my;

        Base(int x, int y, int w, int h) {
            super(x, y, w, h, Text.empty());
            this.bx = x; this.by = y; this.bw = w; this.bh = h;
        }

        boolean over() { return mx >= bx && mx < bx + bw && my >= by && my < by + bh; }

        @Override
        public void renderWidget(DrawContext g, int mouseX, int mouseY, float delta) {
            mx = mouseX; my = mouseY;
            paint(g);
        }

        abstract void paint(DrawContext g);

        @Override
        public void appendClickableNarrations(NarrationMessageBuilder builder) {}
    }

    private final class TabButton extends Base {
        final String name;
        final int idx;

        TabButton(int x, int y, int w, int h, String name, int idx) {
            super(x, y, w, h);
            this.name = name; this.idx = idx;
        }

        @Override
        void paint(DrawContext g) {
            boolean sel = tab == idx;
            if (sel) {
                rrect(g, bx, by, bw, bh, 4, (accent() & 0x00FFFFFF) | 0x33000000);
                rrect(g, bx, by + 4, 2, bh - 8, 1, accent());
            } else if (over()) {
                rrect(g, bx, by, bw, bh, 4, 0x18FFFFFF);
            }
            g.drawText(LyricsScreen.this.textRenderer, name, bx + 10, by + (bh - 8) / 2, sel ? TEXT : MUTED, false);
        }

        @Override
        public void onClick(Click click, boolean doubled) {
            if (tab != idx) {
                tab = idx;
                LyricsScreen.this.clearAndInit();
            }
        }
    }

    private static final class Toggle extends Base {
        final String label;
        final BooleanSupplier get;
        final Consumer<Boolean> set;

        Toggle(int x, int y, int w, int h, String label, BooleanSupplier get, Consumer<Boolean> set) {
            super(x, y, w, h);
            this.label = label; this.get = get; this.set = set;
        }

        @Override
        void paint(DrawContext g) {
            boolean on = get.getAsBoolean();
            rrect(g, bx, by, bw, bh, 5, over() ? CARD_HI : CARD);
            net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
            g.drawText(mc.textRenderer, label, bx + 10, by + (bh - 8) / 2, TEXT, false);
            int sx = bx + bw - 34, sy = by + (bh - 14) / 2;
            rrect(g, sx, sy, 26, 14, 7, on ? accent() : OFF);
            rrect(g, on ? sx + 13 : sx + 1, sy + 1, 12, 12, 6, 0xFFFFFFFF);
        }

        @Override
        public void onClick(Click click, boolean doubled) {
            set.accept(!get.getAsBoolean());
        }
    }

    private static final class Segment extends Base {
        final TextRenderer tr;
        final String label;
        final String[] opts;
        final IntSupplier get;
        final IntConsumer set;

        Segment(TextRenderer tr, int x, int y, int w, int h, String label, String[] opts, IntSupplier get, IntConsumer set) {
            super(x, y, w, h);
            this.tr = tr; this.label = label; this.opts = opts; this.get = get; this.set = set;
        }

        private int[] starts() {
            int total = 0;
            int[] widths = new int[opts.length];
            for (int i = 0; i < opts.length; i++) { widths[i] = tr.getWidth(opts[i]) + 14; total += widths[i] + (i > 0 ? 4 : 0); }
            int[] r = new int[opts.length * 2];
            int x = bx + bw - 8 - total;
            for (int i = 0; i < opts.length; i++) { r[i * 2] = x; r[i * 2 + 1] = widths[i]; x += widths[i] + 4; }
            return r;
        }

        @Override
        void paint(DrawContext g) {
            g.drawText(tr, label, bx + 10, by + (bh - 8) / 2, TEXT, false);
            int[] p = starts();
            for (int i = 0; i < opts.length; i++) {
                boolean sel = get.getAsInt() == i;
                boolean hov = mx >= p[i * 2] && mx < p[i * 2] + p[i * 2 + 1] && my >= by && my < by + bh;
                rrect(g, p[i * 2], by + 5, p[i * 2 + 1], bh - 10, 4, sel ? accent() : (hov ? CARD_HI : 0xFF222733));
                g.drawText(tr, opts[i], p[i * 2] + 7, by + (bh - 8) / 2, sel ? 0xFFFFFFFF : MUTED, false);
            }
        }

        @Override
        public void onClick(Click click, boolean doubled) {
            int[] p = starts();
            for (int i = 0; i < opts.length; i++) {
                if (mx >= p[i * 2] && mx < p[i * 2] + p[i * 2 + 1]) { set.accept(i); return; }
            }
        }
    }

    private static final class Swatches extends Base {
        final String label;

        Swatches(int x, int y, int w, int h, String label) {
            super(x, y, w, h);
            this.label = label;
        }

        private int startX() {
            int n = LyricsState.THEMES.length;
            return bx + bw - 10 - (n * 14 + (n - 1) * 6);
        }

        @Override
        void paint(DrawContext g) {
            g.drawText(net.minecraft.client.MinecraftClient.getInstance().textRenderer, label,
                bx + 10, by + (bh - 8) / 2, TEXT, false);
            int x = startX();
            for (int i = 0; i < LyricsState.THEMES.length; i++) {
                boolean sel = LyricsState.themeIdx == i;
                if (sel) rrect(g, x - 2, by + (bh - 18) / 2, 18, 18, 5, 0xFFFFFFFF);
                rrect(g, x, by + (bh - 14) / 2, 14, 14, 4, 0xFF000000 | LyricsState.THEMES[i].glow());
                x += 20;
            }
        }

        @Override
        public void onClick(Click click, boolean doubled) {
            int x = startX();
            for (int i = 0; i < LyricsState.THEMES.length; i++) {
                if (mx >= x - 2 && mx < x + 16) { LyricsState.themeIdx = i; LyricsState.saveSettings(); return; }
                x += 20;
            }
        }
    }

    private static final class PillButton extends Base {
        final String text;
        final Runnable action;

        PillButton(int x, int y, int w, int h, String text, Runnable action) {
            super(x, y, w, h);
            this.text = text; this.action = action;
        }

        @Override
        void paint(DrawContext g) {
            rrect(g, bx, by, bw, bh, 5, over() ? (accent() & 0x00FFFFFF) | 0xFF000000 : (accent() & 0x00FFFFFF) | 0xCC000000);
            TextRenderer tr = net.minecraft.client.MinecraftClient.getInstance().textRenderer;
            g.drawText(tr, text, bx + (bw - tr.getWidth(text)) / 2, by + (bh - 8) / 2, 0xFFFFFFFF, false);
        }

        @Override
        public void onClick(Click click, boolean doubled) {
            action.run();
        }
    }

    /** Regler: Beschriftung links, Wert rechts, Schiene in der Mitte. */
    private static final class Slide extends SliderWidget {
        final TextRenderer tr;
        final int bx, by, bw, bh, trackX, trackW;
        final String label;
        final double min, max;
        final DoubleConsumer onChange;
        final DoubleFunction<String> fmt;

        Slide(TextRenderer tr, int x, int y, int w, int h, String label, double min, double max, double value,
              DoubleConsumer onChange, DoubleFunction<String> fmt) {
            super(x + 76 - 4, y, w - 76 - 64 + 8, h, Text.empty(), Math.max(0.0, Math.min(1.0, (value - min) / (max - min))));
            this.tr = tr;
            this.bx = x; this.by = y; this.bw = w; this.bh = h;
            this.trackX = x + 76;
            this.trackW = w - 76 - 64;
            this.label = label; this.min = min; this.max = max;
            this.onChange = onChange; this.fmt = fmt;
        }

        @Override
        protected void updateMessage() {}

        @Override
        protected void applyValue() {
            onChange.accept(min + (max - min) * this.value);
        }

        @Override
        public void renderWidget(DrawContext g, int mouseX, int mouseY, float delta) {
            rrect(g, bx, by, bw, bh, 5, CARD);
            g.drawText(tr, label, bx + 10, by + (bh - 8) / 2, TEXT, false);
            String v = fmt == null ? "" : fmt.apply(min + (max - min) * this.value);
            g.drawText(tr, v, bx + bw - 10 - tr.getWidth(v), by + (bh - 8) / 2, MUTED, false);
            int ty = by + bh / 2 - 2;
            rrect(g, trackX, ty, trackW, 4, 2, OFF);
            int fill = (int) (trackW * this.value);
            if (fill > 0) rrect(g, trackX, ty, Math.max(4, fill), 4, 2, accent());
            rrect(g, trackX + fill - 5, by + bh / 2 - 5, 10, 10, 5, 0xFFFFFFFF);
        }
    }
}
