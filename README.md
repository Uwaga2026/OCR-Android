# OCR – Android-Projekt

Eine einfache Android-App zur lokalen Texterkennung aus bereits vorhandenen JPG-/PNG-Fotos.

## Funktionen
- Mehrere Bilder über den Android-Dateiauswahldialog auswählen
- Texterkennung mit dem gebündelten Google ML Kit Latin-Modell
- Erkannte Seiten fortlaufend zusammenfügen und im Textfeld bearbeiten
- Ergebnis als TXT-Datei exportieren
- Keine Anmeldung und kein eigener Server

## Wichtig zur Seitenreihenfolge
Die App verarbeitet die Bilder in der Reihenfolge, in der sie vom Android-Dateiauswahldialog geliefert werden. Bitte die Bilder dort möglichst in der gewünschten Reihenfolge auswählen. Die Seiten werden im Text zusätzlich nummeriert.

## Build
Das Projekt kann in Android Studio geöffnet werden. Alternativ kann GitHub Actions automatisch eine APK bauen.

## GitHub Actions
Nach dem Hochladen des Projekts auf GitHub startet der Workflow bei jedem Push auf `main` oder `master` sowie manuell über **Actions → Android APK bauen → Run workflow**.
Die APK liegt nach erfolgreichem Lauf unter **Actions → Workflow-Lauf → Artifacts → OCR-debug-apk**.

## Datenschutz
Die App enthält keine Upload-Funktion. Die ausgewählten Bilder werden zur Texterkennung lokal auf dem Gerät verarbeitet. Für die erstmalige Installation der APK und den Build werden Internetverbindungen benötigt.
