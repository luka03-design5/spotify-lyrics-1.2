package dev.axiom.lyrics;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Util;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public final class SpotifyAuth {
    static final String REDIRECT = "http://127.0.0.1:8888/callback";
    static final String SCOPE = "user-read-currently-playing user-read-playback-state";
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("spotifylyrics.json");

    static String clientId, accessToken, refreshToken;
    static long expiresAt;

    public static synchronized void load() {
        try {
            if (!Files.exists(FILE)) return;
            JsonObject o = JsonParser.parseString(Files.readString(FILE)).getAsJsonObject();
            clientId = str(o, "clientId");
            refreshToken = str(o, "refreshToken");
            if (refreshToken != null) LyricsState.status = "Verbinde mit Spotify …";
        } catch (Exception ignored) {}
    }

    static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }

    static synchronized void save() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("clientId", clientId);
            if (refreshToken != null) o.addProperty("refreshToken", refreshToken);
            Files.writeString(FILE, o.toString());
        } catch (IOException ignored) {}
    }

    public static void login(String id) {
        if (id != null) clientId = id;
        if (clientId == null) { LyricsState.status = "Client ID fehlt: /lyrics login <clientId>"; return; }
        Thread t = new Thread(() -> {
            try { doLogin(); }
            catch (Exception e) { LyricsState.status = "Login-Fehler: " + e.getMessage(); }
        }, "spotify-login");
        t.setDaemon(true);
        t.start();
    }

    private static void doLogin() throws Exception {
        SecureRandom rnd = new SecureRandom();
        byte[] vb = new byte[48];
        rnd.nextBytes(vb);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(vb);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        String state = Long.toHexString(rnd.nextLong());

        String url = "https://accounts.spotify.com/authorize?response_type=code"
            + "&client_id=" + enc(clientId)
            + "&redirect_uri=" + enc(REDIRECT)
            + "&scope=" + enc(SCOPE)
            + "&code_challenge_method=S256&code_challenge=" + challenge
            + "&state=" + state;

        try (ServerSocket server = new ServerSocket(8888, 1, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(120_000);
            Util.getOperatingSystem().open(URI.create(url));
            try (Socket s = server.accept()) {
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
                String reqLine = in.readLine();
                String query = reqLine.split(" ")[1];
                query = query.contains("?") ? query.substring(query.indexOf('?') + 1) : "";
                String code = null, gotState = null;
                for (String p : query.split("&")) {
                    String[] kv = p.split("=", 2);
                    if (kv.length < 2) continue;
                    String v = URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                    if (kv[0].equals("code")) code = v;
                    if (kv[0].equals("state")) gotState = v;
                }
                String body = "<html><body style='font-family:sans-serif'><h2>Fertig – zurück zu Minecraft.</h2></body></html>";
                s.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n"
                    + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length
                    + "\r\nConnection: close\r\n\r\n" + body).getBytes(StandardCharsets.UTF_8));
                if (code == null || !state.equals(gotState)) throw new IOException("Ungültiger Callback");
                tokenRequest("grant_type=authorization_code&code=" + enc(code)
                    + "&redirect_uri=" + enc(REDIRECT) + "&client_id=" + enc(clientId)
                    + "&code_verifier=" + verifier);
            }
        }
        LyricsState.status = "Eingeloggt. Spiel etwas auf Spotify ab.";
    }

    private static synchronized void tokenRequest(String form) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://accounts.spotify.com/api/token"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IOException("Token-Antwort " + r.statusCode());
        JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
        accessToken = o.get("access_token").getAsString();
        expiresAt = System.currentTimeMillis() + o.get("expires_in").getAsLong() * 1000;
        if (o.has("refresh_token")) refreshToken = o.get("refresh_token").getAsString();
        save();
    }

    public static synchronized String token() {
        try {
            if (refreshToken == null || clientId == null) return null;
            if (accessToken == null || System.currentTimeMillis() > expiresAt - 30_000)
                tokenRequest("grant_type=refresh_token&refresh_token=" + enc(refreshToken) + "&client_id=" + enc(clientId));
            return accessToken;
        } catch (Exception e) {
            LyricsState.status = "Token-Fehler: " + e.getMessage();
            return null;
        }
    }

    public static synchronized void invalidate() { accessToken = null; }

    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    private SpotifyAuth() {}
}
