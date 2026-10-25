package dev.axiom.lyrics;

import com.google.gson.*;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

public final class LrcLib {
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final Pattern LINE = Pattern.compile("^\\[(\\d+):(\\d+(?:\\.\\d+)?)](.*)$");

    public static List<LyricsState.LrcLine> fetch(String title, String artist, long durSec) {
        List<LyricsState.LrcLine> r = durSec > 0 ? tryFetch(title, artist, durSec) : List.of();
        if (r.isEmpty()) r = search(title, artist, durSec);
        if (r.isEmpty()) {
            String cleaned = title.replaceAll("\\s+-\\s+.*$", "").replaceAll("\\s*\\(.*?\\)", "").trim();
            if (!cleaned.isEmpty() && !cleaned.equals(title)) {
                r = durSec > 0 ? tryFetch(cleaned, artist, durSec) : List.of();
                if (r.isEmpty()) r = search(cleaned, artist, durSec);
            }
        }
        return r;
    }

    /** Unscharfe Suche, falls der exakte Treffer fehlt: nimmt den Treffer mit der passendsten Länge. */
    private static List<LyricsState.LrcLine> search(String title, String artist, long durSec) {
        try {
            String url = "https://lrclib.net/api/search?track_name=" + enc(title)
                + "&artist_name=" + enc(artist);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "SpotifyLyricsMod/1.0 (Fabric client mod)").GET().build();
            HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) return List.of();
            JsonArray arr = JsonParser.parseString(r.body()).getAsJsonArray();
            JsonObject best = null;
            double bestDiff = Double.MAX_VALUE;
            for (JsonElement el : arr) {
                JsonObject o = el.getAsJsonObject();
                if (!o.has("syncedLyrics") || o.get("syncedLyrics").isJsonNull()) continue;
                if (o.get("syncedLyrics").getAsString().isBlank()) continue;
                double d = o.has("duration") && !o.get("duration").isJsonNull() ? o.get("duration").getAsDouble() : 0;
                double diff = durSec > 0 ? Math.abs(d - durSec) : 0;
                if (diff < bestDiff) { bestDiff = diff; best = o; }
            }
            if (best == null || bestDiff > 10) return List.of();
            return parse(best.get("syncedLyrics").getAsString());
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<LyricsState.LrcLine> tryFetch(String title, String artist, long durSec) {
        try {
            String url = "https://lrclib.net/api/get?track_name=" + enc(title)
                + "&artist_name=" + enc(artist) + "&duration=" + durSec;
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "SpotifyLyricsMod/1.0 (Fabric client mod)").GET().build();
            HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) return List.of();
            JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
            if (!o.has("syncedLyrics") || o.get("syncedLyrics").isJsonNull()) return List.of();
            return parse(o.get("syncedLyrics").getAsString());
        } catch (Exception e) {
            return List.of();
        }
    }

    static List<LyricsState.LrcLine> parse(String lrc) {
        List<LyricsState.LrcLine> out = new ArrayList<>();
        for (String raw : lrc.split("\n")) {
            Matcher m = LINE.matcher(raw.trim());
            if (!m.matches()) continue;
            long ms = (long) ((Integer.parseInt(m.group(1)) * 60 + Double.parseDouble(m.group(2))) * 1000);
            out.add(new LyricsState.LrcLine(ms, m.group(3).trim()));
        }
        out.sort(Comparator.comparingLong(LyricsState.LrcLine::ms));
        return out;
    }

    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    private LrcLib() {}
}
