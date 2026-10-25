package dev.axiom.lyrics;

import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

/**
 * Liest den aktuellen Song aus der Windows-Medienanzeige (die Lautstärke-/Medien-Kachel).
 * Dort melden sich die Spotify-App UND Spotify im Browser an. Kein Spotify-Login nötig.
 */
public final class WindowsMedia {
    private static final String SCRIPT = """
        param([int]$ParentPid = 0)
        $ErrorActionPreference = 'Stop'
        Add-Type -AssemblyName System.Runtime.WindowsRuntime
        $null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType=WindowsRuntime]
        $null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties, Windows.Media.Control, ContentType=WindowsRuntime]
        $asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
            $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
        })[0]
        function Await($op, $type) {
            $task = $asTask.MakeGenericMethod($type).Invoke($null, @($op))
            [void]$task.Wait(-1)
            return $task.Result
        }
        function B64([string]$s) { return [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($s)) }
        $mgr = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])
        while ($true) {
            if ($ParentPid -gt 0 -and -not (Get-Process -Id $ParentPid -ErrorAction SilentlyContinue)) { exit }
            try {
                $pick = $null
                foreach ($s in $mgr.GetSessions()) {
                    if ($s.SourceAppUserModelId -match 'spotify') { $pick = $s; break }
                }
                if ($null -eq $pick) { $pick = $mgr.GetCurrentSession() }
                if ($null -eq $pick) {
                    [Console]::Out.WriteLine('NONE')
                } else {
                    $props = Await ($pick.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])
                    $tl = $pick.GetTimelineProperties()
                    $status = [string]$pick.GetPlaybackInfo().PlaybackStatus
                    $pos = [int64]$tl.Position.TotalMilliseconds
                    $end = [int64]$tl.EndTime.TotalMilliseconds - [int64]$tl.StartTime.TotalMilliseconds
                    if ($status -eq 'Playing') {
                        $age = [int64]([DateTimeOffset]::UtcNow - $tl.LastUpdatedTime).TotalMilliseconds
                        if ($age -gt 0 -and $age -lt 60000) { $pos += $age }
                    }
                    $tab = [string][char]9
                    $line = 'OK' + $tab + $status + $tab + (B64 $props.Title) + $tab + (B64 $props.Artist) + $tab + $pos + $tab + $end + $tab + (B64 $pick.SourceAppUserModelId)
                    [Console]::Out.WriteLine($line)
                }
            } catch {
                [Console]::Out.WriteLine('ERR' + [string][char]9 + (B64 $_.Exception.Message))
            }
            [Console]::Out.Flush()
            Start-Sleep -Milliseconds 600
        }
        """;

    private static volatile Process proc;
    private static long ownProgress, lastLineAt;

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    public static void start() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Process p = proc;
            if (p != null) p.destroyForcibly();
        }));
        Thread t = new Thread(WindowsMedia::loop, "lyrics-windows-media");
        t.setDaemon(true);
        t.start();
    }

    private static void loop() {
        while (true) {
            try {
                if (LyricsState.source != 0) { Thread.sleep(1000); continue; }
                if (!isWindows()) {
                    LyricsState.status = "Windows-Medien gibt es nur unter Windows. Wähle „Spotify-Konto“.";
                    Thread.sleep(3000);
                    continue;
                }
                runOnce();
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                LyricsState.status = "Medien-Fehler: " + e.getMessage();
                try { Thread.sleep(5000); } catch (InterruptedException ie) { return; }
            }
        }
    }

    private static void runOnce() throws Exception {
        Path script = FabricLoader.getInstance().getConfigDir().resolve("spotifylyrics-media.ps1");
        Files.writeString(script, SCRIPT, StandardCharsets.UTF_8);

        ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
            "-WindowStyle", "Hidden", "-ExecutionPolicy", "Bypass", "-File", script.toString(),
            "-ParentPid", String.valueOf(ProcessHandle.current().pid()));
        pb.redirectErrorStream(true);
        Process p = pb.start();
        proc = p;
        LyricsState.status = "Suche Medien-Wiedergabe ...";
        try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (LyricsState.source != 0) break;
                handle(line.trim());
            }
        } finally {
            p.destroyForcibly();
            proc = null;
        }
    }

    private static void nothing(String msg) {
        LyricsState.playing = false;
        LyricsState.lines = List.of();
        LyricsState.trackId = "";
        LyricsState.title = "";
        LyricsState.artist = "";
        LyricsState.durationMs = 0;
        LyricsState.status = msg;
    }

    private static String b64(String s) {
        try { return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8); }
        catch (Exception e) { return ""; }
    }

    private static long num(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0; }
    }

    private static void handle(String line) {
        if (line.isEmpty()) return;
        String[] p = line.split("\t", -1);
        if (p[0].equals("NONE")) { nothing("Keine Wiedergabe gefunden. Starte Spotify (App oder Browser)."); return; }
        if (p[0].equals("ERR")) { LyricsState.status = "Medien-Fehler: " + (p.length > 1 ? b64(p[1]) : ""); return; }
        if (!p[0].equals("OK") || p.length < 7) {
            String shown = line.length() > 110 ? line.substring(0, 110) : line;
            LyricsState.status = "Medien-Skript: " + shown;
            return;
        }

        String state = p[1];
        String title = b64(p[2]).trim();
        String artist = b64(p[3]).trim();
        long pos = num(p[4]);
        long end = num(p[5]);
        if (title.isEmpty()) { nothing("Kein Song erkannt."); return; }

        boolean playing = state.equals("Playing");
        long now = System.currentTimeMillis();
        String key = title + "\u0001" + artist;

        if (!key.equals(LyricsState.trackId)) {
            ownProgress = 0;
            LyricsState.trackId = key;
            LyricsState.lines = List.of();
            LyricsState.title = title;
            LyricsState.artist = artist;
            LyricsState.durationMs = Math.max(0, end);
            LyricsState.status = "Lade Lyrics für „" + title + "“ ...";
            long durSec = end > 0 ? end / 1000 : 0;
            Thread t = new Thread(() -> {
                List<LyricsState.LrcLine> l = LrcLib.fetch(title, artist, durSec);
                if (key.equals(LyricsState.trackId)) {
                    LyricsState.lines = l;
                    LyricsState.status = l.isEmpty()
                        ? "Keine synchronisierten Lyrics gefunden."
                        : "Lyrics bereit (" + l.size() + " Zeilen)";
                }
            }, "lyrics-fetch");
            t.setDaemon(true);
            t.start();
        }

        if (end <= 0) {                       // App meldet keine Position: selbst mitzählen
            if (playing && lastLineAt > 0) ownProgress += Math.min(2000, now - lastLineAt);
            pos = ownProgress;
        }
        lastLineAt = now;

        LyricsState.progressMs = pos;
        LyricsState.fetchedAt = now;
        LyricsState.playing = playing;
    }

    private WindowsMedia() {}
}
