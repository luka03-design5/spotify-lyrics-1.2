package dev.axiom.lyrics;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix3x2fStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Zeichnet die Lyrics. Modus 0: jede Zeile hängt als 3D-Schrift fest an einer Stelle in der Welt vor dir,
 * die Wörter erscheinen nacheinander. Die Welt-Position wird pro Frame auf den Bildschirm projiziert.
 * Modus 1: einfache Anzeige unten am Bildschirm.
 */
public final class LyricsRenderer {
    private static final int MAX_CHARS = 16;       // Zeichen pro Zeile
    private static final float ROW_H = 17f;        // Zeilenabstand in Schrift-Einheiten
    private static final float UNIT = 0.10f;       // Weltmaß (Blöcke) pro Schrift-Einheit bei Größe 100 %

    private record Row(List<String> words, float[] widths, float width, int firstWord, float xOff, float roll) {}
    private record Phrase(Vec3d anchor, Vector3f right, Vector3f up, Vector3f normal,
                          List<Row> rows, long start, long dur, float step) {}

    private static final Map<Integer, Phrase> PHRASES = new HashMap<>();
    private static String cacheTrack = "";
    private static String lastStatus = "";
    private static long statusSince = 0;

    public static void render(DrawContext g, RenderTickCounter tc) {
        if (!LyricsState.enabled) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;

        TextRenderer tr = mc.textRenderer;
        int W = g.getScaledWindowWidth(), H = g.getScaledWindowHeight();

        String track = LyricsState.trackId;
        if (!track.equals(cacheTrack)) { PHRASES.clear(); cacheTrack = track; }

        List<LyricsState.LrcLine> lines = LyricsState.lines;
        if (lines.isEmpty()) { drawStatus(g, tr, W, H); return; }

        long t = LyricsState.now() + LyricsState.offsetMs;
        int cur = LyricsState.indexAt(lines, t);
        if (cur < 0) return;

        if (LyricsState.mode == 1) drawBottom(g, tr, lines, cur, t, W, H);
        else draw3d(g, mc, tr, lines, cur, t, W, H, tc.getTickProgress(true));
    }

    // ------------------------------------------------------------------ Status

    private static void drawStatus(DrawContext g, TextRenderer tr, int W, int H) {
        String s = LyricsState.status;
        long now = System.currentTimeMillis();
        if (!s.equals(lastStatus)) { lastStatus = s; statusSince = now; }
        if (now - statusSince > 8000) return;          // nur kurz nach einer Änderung zeigen
        String text = tr.trimToWidth(s, W - 20);
        g.drawText(tr, text, (W - tr.getWidth(text)) / 2, H - 64, 0xFFB79CFF, true);
    }

    // ------------------------------------------------------------------ Modus 1: unten

    private static void drawBottom(DrawContext g, TextRenderer tr, List<LyricsState.LrcLine> lines,
                                   int cur, long t, int W, int H) {
        LyricsState.Theme th = LyricsState.theme();
        String prev = cur > 0 ? lines.get(cur - 1).text() : "";
        String now = lines.get(cur).text();
        String next = cur + 1 < lines.size() ? lines.get(cur + 1).text() : "";
        float k = LyricsState.size;
        int base = H - (int) (86 * Math.min(1.5f, k));
        centered(g, tr, prev, W / 2f, base - 18 * k, 0.9f * k, argb(150, th.past()));
        centered(g, tr, now, W / 2f, base, 1.7f * k, argb(255, th.front()));
        centered(g, tr, next, W / 2f, base + 26 * k, 0.9f * k, argb(150, th.past()));
    }

    private static void centered(DrawContext g, TextRenderer tr, String s, float cx, float y, float scale, int color) {
        if (s == null || s.isBlank()) return;
        Matrix3x2fStack m = g.getMatrices();
        m.pushMatrix();
        m.translate(cx, y);
        m.scale(scale, scale);
        g.drawText(tr, s, -tr.getWidth(s) / 2, 0, color, true);
        m.popMatrix();
    }

    // ------------------------------------------------------------------ Modus 0: 3D

    private static void draw3d(DrawContext g, MinecraftClient mc, TextRenderer tr,
                               List<LyricsState.LrcLine> lines, int cur, long t, int W, int H, float tickProgress) {
        PHRASES.keySet().removeIf(k -> k < cur - 2 || k > cur);

        Camera cam = mc.gameRenderer.getCamera();
        Vec3d camPos = mc.player.getCameraPosVec(tickProgress);
        Quaternionf q = cam.getRotation();
        Quaternionf inv = new Quaternionf(q).conjugate();

        float fov = mc.options.getFov().getValue();
        float tanHalf = (float) Math.tan(Math.toRadians(fov) / 2.0);
        float aspect = (float) W / (float) H;

        for (int k = Math.max(0, cur - 2); k <= cur; k++) {
            Phrase p = PHRASES.get(k);
            if (p == null) {
                if (k != cur) continue;                     // nur die aktuelle Zeile neu erzeugen
                p = spawn(k, lines, camPos, q, tr);
                if (p == null) continue;
                PHRASES.put(k, p);
            }
            drawPhrase(g, tr, p, t, camPos, inv, tanHalf, aspect, W, H);
        }
    }

    private static Phrase spawn(int k, List<LyricsState.LrcLine> lines, Vec3d camPos, Quaternionf q, TextRenderer tr) {
        LyricsState.LrcLine ln = lines.get(k);
        String text = ln.text().trim();
        if (text.isEmpty()) return null;

        long end = k + 1 < lines.size() ? lines.get(k + 1).ms() : ln.ms() + 4000;
        long dur = Math.max(1200, Math.min(6000, end - ln.ms()));
        Random rnd = new Random(31L * k + LyricsState.trackId.hashCode());

        // Wörter in Zeilen packen
        String[] ws = text.split("\\s+");
        List<Row> rows = new ArrayList<>();
        List<String> bucket = new ArrayList<>();
        int chars = 0, gi = 0, first = 0;
        for (String w : ws) {
            if (!bucket.isEmpty() && chars + 1 + w.length() > MAX_CHARS) {
                rows.add(makeRow(tr, bucket, first, rows.size(), rnd));
                first = gi;
                bucket = new ArrayList<>();
                chars = 0;
            }
            bucket.add(w);
            chars += (chars == 0 ? 0 : 1) + w.length();
            gi++;
        }
        if (!bucket.isEmpty()) rows.add(makeRow(tr, bucket, first, rows.size(), rnd));

        float step = Math.min(420f, dur * 0.7f / Math.max(1, ws.length));

        // Ort: vor dem Spieler, seitlich versetzt, etwas höher
        Vector3f fwd = q.transform(new Vector3f(0f, 0f, -1f));
        Vector3f right = q.transform(new Vector3f(1f, 0f, 0f));
        Vector3f up = q.transform(new Vector3f(0f, 1f, 0f));
        double dist = 8.0 + rnd.nextDouble() * 3.0;
        double side = (k % 2 == 0 ? -1 : 1) * (0.10 + rnd.nextDouble() * 0.30);
        double lift = -0.02 + rnd.nextDouble() * 0.26;
        Vec3d pos = camPos.add(
            fwd.x * dist + right.x * side * dist,
            fwd.y * dist + right.y * side * dist + lift * dist,
            fwd.z * dist + right.z * side * dist);

        return new Phrase(pos, right, up, fwd, rows, ln.ms(), dur, step);
    }

    private static Row makeRow(TextRenderer tr, List<String> words, int first, int r, Random rnd) {
        float[] wd = new float[words.size()];
        float total = 0;
        for (int i = 0; i < wd.length; i++) {
            wd[i] = tr.getWidth(words.get(i));
            total += wd[i] + (i > 0 ? 4f : 0f);
        }
        float xOff = (r % 2 == 0 ? -1 : 1) * (3f + rnd.nextFloat() * 20f);
        float roll = (float) Math.toRadians((rnd.nextFloat() - 0.5f) * 10f);
        return new Row(words, wd, total, first, xOff, roll);
    }

    private static void drawPhrase(DrawContext g, TextRenderer tr, Phrase p, long t, Vec3d camPos,
                                   Quaternionf inv, float tanHalf, float aspect, int W, int H) {
        if (t < p.start()) return;
        long end = p.start() + p.dur();

        float fade = 1f;
        long fadeStart = end + 900;
        if (t > fadeStart) fade = 1f - (t - fadeStart) / 1400f;
        if (fade <= 0.02f) return;

        LyricsState.Theme th = LyricsState.theme();
        int main = t > end ? th.past() : th.front();
        float unit = UNIT * LyricsState.size;
        float totalH = p.rows().size() * ROW_H;

        for (int r = 0; r < p.rows().size(); r++) {
            Row row = p.rows().get(r);
            float rowY = r * ROW_H - totalH / 2f;
            float cosR = (float) Math.cos(row.roll()), sinR = (float) Math.sin(row.roll());
            float x = -row.width() / 2f;

            for (int w = 0; w < row.words().size(); w++) {
                float ww = row.widths()[w];
                int gi = row.firstWord() + w;
                float prog = (t - (p.start() + gi * p.step())) / 260f;
                float dx = x + ww / 2f;
                x += ww + 4f;
                if (prog <= 0f) continue;

                float e = 1f - (float) Math.pow(1f - Math.min(1f, prog), 3);
                int alpha = (int) (255 * e * fade);
                if (alpha < 8) continue;

                // Mitte des Wortes in Schrift-Einheiten (y nach unten), mit Neigung der Zeile
                float cx = row.xOff() + dx * cosR;
                float cy = rowY + 4f + dx * sinR + (1f - e) * 6f
                    + (float) Math.sin((t + gi * 230L) / 650.0) * 0.8f;

                double wx = p.anchor().x + (p.right().x * cx - p.up().x * cy) * unit;
                double wy = p.anchor().y + (p.right().y * cx - p.up().y * cy) * unit;
                double wz = p.anchor().z + (p.right().z * cx - p.up().z * cy) * unit;

                // in Kamera-Raum drehen und auf den Bildschirm projizieren
                Vector3f v = new Vector3f((float) (wx - camPos.x), (float) (wy - camPos.y), (float) (wz - camPos.z));
                float len = v.length();
                inv.transform(v);
                float depth = -v.z;
                if (depth < 0.6f || depth > 70f) continue;      // hinter dir oder zu weit weg
                float sx = W * 0.5f * (1f + v.x / (depth * tanHalf * aspect));
                float sy = H * 0.5f * (1f - v.y / (depth * tanHalf));
                if (sx < -250 || sx > W + 250 || sy < -120 || sy > H + 120) continue;

                float s = (H * 0.5f) / (tanHalf * depth) * unit;   // Bildschirm-Pixel pro Schrift-Einheit
                if (s < 0.25f) continue;
                float pop = 0.7f + 0.3f * e;
                float dot = Math.abs(((float) (wx - camPos.x) * p.normal().x
                    + (float) (wy - camPos.y) * p.normal().y
                    + (float) (wz - camPos.z) * p.normal().z) / Math.max(0.001f, len));
                float squish = Math.max(0.45f, Math.min(1f, dot));

                drawWord(g, tr, row.words().get(w), ww, sx, sy, s * squish * pop, s * pop, row.roll(),
                    alpha, main, th);
            }
        }
    }

    private static void drawWord(DrawContext g, TextRenderer tr, String word, float ww,
                                 float sx, float sy, float scaleX, float scaleY, float roll,
                                 int alpha, int main, LyricsState.Theme th) {
        Matrix3x2fStack m = g.getMatrices();
        m.pushMatrix();
        m.translate(sx, sy);
        m.rotate(roll);
        m.scale(scaleX, scaleY);
        m.translate(-ww / 2f, -4f);

        int ga = alpha / 5;                                    // Leuchten
        if (ga >= 8) {
            for (int dx = -1; dx <= 1; dx += 2)
                for (int dy = -1; dy <= 1; dy += 2)
                    put(g, tr, word, dx * 1.2f, dy * 1.2f, argb(ga, th.glow()));
        }
        for (int i = 4; i >= 0; i--) {                         // Tiefe, hinten zuerst
            float o = (i + 1) * 0.55f;
            put(g, tr, word, o, o, argb((int) (alpha * 0.95f), th.depth(i)));
        }
        put(g, tr, word, 0f, 0f, argb(alpha, main));           // Vorderseite

        m.popMatrix();
    }

    private static void put(DrawContext g, TextRenderer tr, String word, float ox, float oy, int color) {
        Matrix3x2fStack m = g.getMatrices();
        m.pushMatrix();
        m.translate(ox, oy);
        g.drawText(tr, word, 0, 0, color, false);
        m.popMatrix();
    }

    private static int argb(int a, int rgb) {
        return (Math.min(255, Math.max(0, a)) << 24) | rgb;
    }

    private LyricsRenderer() {}
}
