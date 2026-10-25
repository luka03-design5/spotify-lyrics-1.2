package dev.axiom.lyrics;

import com.google.gson.*;

import java.net.URI;
import java.net.http.*;
import java.util.List;

public final class SpotifyPoller {
    static final HttpClient HTTP = HttpClient.newHttpClient();

    public static void start() {
        Thread t = new Thread(SpotifyPoller::loop, "spotify-poller");
        t.setDaemon(true);
        t.start();
    }

    private static void loop() {
        while (true) {
            try {
                if (LyricsState.source != 1) { Thread.sleep(1000); continue; }
                poll();
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                LyricsState.status = "Fehler: " + e.getMessage();
                try { Thread.sleep(5000); } catch (InterruptedException ie) { return; }
            }
        }
    }

    private static void poll() throws Exception {
        String tok = SpotifyAuth.token();
        if (tok == null) return;

        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.spotify.com/v1/me/player/currently-playing"))
            .header("Authorization", "Bearer " + tok).GET().build();
        long sent = System.currentTimeMillis();
        HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        long mid = (sent + System.currentTimeMillis()) / 2;

        switch (r.statusCode()) {
            case 204 -> { LyricsState.playing = false; LyricsState.lines = List.of();
                          LyricsState.trackId = ""; LyricsState.status = "Spotify spielt gerade nichts."; return; }
            case 401 -> { SpotifyAuth.invalidate(); return; }
            case 429 -> { Thread.sleep(r.headers().firstValueAsLong("Retry-After").orElse(5) * 1000); return; }
            case 200 -> {}
            default -> {
                LyricsState.playing = false;
                LyricsState.status = describeError(r.statusCode(), r.body());
                Thread.sleep(4000);
                return;
            }
        }

        JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
        if (!o.has("item") || o.get("item").isJsonNull()) {
            LyricsState.playing = false;
            LyricsState.status = "Spotify meldet keinen Song (Werbung oder privater Modus?).";
            return;
        }
        JsonObject item = o.getAsJsonObject("item");
        if (!"track".equals(item.get("type").getAsString())) {
            LyricsState.lines = List.of(); LyricsState.status = "Kein Song (Podcast/Werbung)."; return;
        }

        LyricsState.progressMs = o.get("progress_ms").getAsLong();
        LyricsState.fetchedAt = mid;
        LyricsState.playing = o.get("is_playing").getAsBoolean();

        String title = item.get("name").getAsString();
        String artist = item.getAsJsonArray("artists").get(0).getAsJsonObject().get("name").getAsString();
        long durMs = item.get("duration_ms").getAsLong();
        LyricsState.title = title;
        LyricsState.artist = artist;
        LyricsState.durationMs = durMs;
        String id = item.get("id").isJsonNull() ? title + artist : item.get("id").getAsString();

        if (!id.equals(LyricsState.trackId)) {
            LyricsState.trackId = id;
            LyricsState.lines = List.of();
            LyricsState.status = "Lade Lyrics für „" + title + "“ …";
            List<LyricsState.LrcLine> lines = LrcLib.fetch(title, artist, durMs / 1000);
            if (id.equals(LyricsState.trackId)) {
                LyricsState.lines = lines;
                LyricsState.status = lines.isEmpty()
                    ? "Keine synchronisierten Lyrics gefunden."
                    : "Lyrics bereit (" + lines.size() + " Zeilen)";
            }
        }
    }
    private static String describeError(int code, String body) {
        String msg = "";
        try {
            JsonObject e = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("error");
            if (e != null && e.has("message")) msg = e.get("message").getAsString();
        } catch (Exception ignored) {}
        if (code == 403) {
            return "Spotify blockiert den Zugriff (403). Seit 2026 braucht der Besitzer der Spotify-App Premium. "
                + "Wechsle unter Verbindung auf Windows-Medien. " + msg;
        }
        return "Spotify-Fehler " + code + (msg.isEmpty() ? "" : ": " + msg);
    }

    private SpotifyPoller() {}
}
