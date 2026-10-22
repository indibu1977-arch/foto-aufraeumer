# Foto-Aufräumer

Android-App zum Aufräumen der Galerie – läuft komplett offline auf dem Handy.

## Funktionen
- **Doppelte Fotos** finden (exakt gleiche Dateien)
- **Ähnliche Fotos** finden (Serienbilder, Fast-Gleiches) – „Beste behalten“ behält das schärfste
- **Qualität prüfen**: unscharf, zu dunkel, überbelichtet
- **Wisch-Sortierer**: nach unten = löschen, links/oben/rechts = frei wählbare Ordner, mit Rückgängig
- **Galerie**: alle Fotos durchscrollen – Raster, 2 Bilder oder 1 Bild pro Bildschirm, Schnellscroll-Leiste mit Monatsanzeige; nach unten wischen bzw. lange drücken = zum Löschen markieren, alle markierten auf einmal löschen
- **Verbessern**: Automatisch, Helligkeit, Kontrast, Farbkraft, Wärme, Schärfe – speichert als Kopie

Gelöschte Fotos landen im Papierkorb (30 Tage wiederherstellbar).

## Installieren
1. Auf dem Handy die Seite **Releases** öffnen:
   https://github.com/indibu1977-arch/foto-aufraeumer/releases/latest
2. `FotoAufraeumer.apk` antippen und herunterladen
3. Datei öffnen → bei Nachfrage „Installation aus dieser Quelle zulassen“ erlauben → Installieren

Updates genauso installieren – die App wird einfach überschrieben, Einstellungen bleiben erhalten.

## Bauen
Jeder Push auf `main` baut automatisch eine neue APK (GitHub Actions) und legt sie als Release ab.
