# Spotify Lyrics (Fabric, Minecraft 1.21.11)

Zeigt die Lyrics des laufenden Songs als schwebende Schrift im Spiel.

## JAR bauen
Projekt in ein GitHub-Repo hochladen. Unter "Actions" läuft "build",
dort das Artifact "spotify-lyrics-jar" herunterladen.
(Der Workflow baut mit Java 25, weil das Fabric-Build-Plugin das verlangt.
Die Mod selbst ist für Java 21 gebaut.)

## Installieren
JAR (nicht die -sources.jar) nach .minecraft/mods/ kopieren.
Benötigt: Fabric Loader + Fabric API für Minecraft 1.21.11.

## Benutzen
Taste K öffnet das Menü.
- Verbindung -> Quelle "Windows-Medien": liest den Song aus der Windows-Medienanzeige
  (Spotify-App und Spotify im Browser). Kein Login nötig.
- Verbindung -> Quelle "Spotify-Konto": braucht eine eigene Spotify-App (Premium) mit
  Redirect URI http://127.0.0.1:8888/callback und deren Client ID.
