# Förderungen – Prototyp (Schulische Sport- und Projekttage)

Prototyp des Backoffice für die Förderung „Schulische Sport- und Projekttage" des Landes
Burgenland. Grundlage sind der Online-Antrag (AFS, `BF_SKF_SSP`) und die Richtlinie
(<https://www.burgenland.at/themen/bildung/schulen/sport-und-projekttage/>).

## Rollen

| Rolle | Darf |
|---|---|
| Sachbearbeiter | prüfen, Nachbesserung anfordern (14 Tage, anpassbar), Nachbesserung als eingelangt markieren, stornieren |
| Genehmiger | bewilligen (Betrag wird berechnet), ablehnen, stornieren |
| Finanzabteilung | Sammelakt bilden, SEPA-XML exportieren, Zahlung freigeben |

Im Prototyp ist die Rolle umschaltbar (Demo-Schalter auf `/foerderungen.xsp`), weil der
Designer-Preview nur einen Benutzer kennt.

## Statusmaschine

```
Eingereicht ──prüfen──> In Pruefung ──nachbesserung──> Nachbesserung
                              │                              │
                              │<────────wiedervorlage────────┘
                              ├──bewilligen──> Bewilligt
                              └──ablehnen───> Abgelehnt
beliebiger offener Status ──storno──> Storniert
```

`Bewilligt` ist die Vorstufe für den Sammelakt; `Storniert` löst das Storno-Schreiben aus,
bei bereits geleisteter Zahlung zusätzlich die Rückforderung.

## Datenmodell (Prototyp: ein Dokument je Antrag)

Dokument `Form = "FoerderAntrag"`:

- Antragsteller*in, Kind, Schule, Schulart, Schulstufe, Veranstaltungstyp, Kosten
- Haushalt: Erwachsene, Kinder, Nettoeinkommen (Transparenzportal)
- berechnet: `stage` (1–3, 0 = über der Grenze), `amount`
- `status`, `due` (Nachbesserungsfrist), `created`, `history` (Verlauf)
- später: `personId`, Beilagen, Dokumente, Sammelakt-Zuordnung

## Berechnung (aus der Richtlinie)

- **Stufe** aus Netto-Haushaltseinkommen je (Erwachsene × Kinder):
  1 Erwachsener + 1 Kind: 1.770 / 1.950 / 2.130 €; +350 € je weiterem Kind,
  +600 € je weiterem Erwachsenen; 2 Erwachsene + 1 Kind: 2.400 / 2.640 / 2.880 €.
- **Betrag** je Stufe und Schulstufe:
  bis 4. Schulstufe 100/80/60 € (Wintersport) bzw. 80/60/40 € (sonstig),
  ab 5. Schulstufe 135/110/85 € bzw. 110/85/60 €.
- **Mehrlinge:** +30 % je Antrag, wenn die Geschwister an derselben Veranstaltung teilnehmen.
- Stufe und Betrag sind **Vorschläge**; der Genehmiger entscheidet.

## Dokumente

Word-Vorlagen liegen als eigene Notes-Dokumente mit angehängter `.docx`-Vorlage in der
Datenbank. Beim Erzeugen wird die Vorlage gelesen, Platzhalter (`${antragsteller}` …) ersetzt
und das Ergebnis als neues Dokument/Attachments am Antrag abgelegt. Kandidaten:
Nachbesserungsauftrag, Bewilligung/Förderzusage, Storno, Rückforderung.

## Sammelakt und Zahlung

- monatlicher Sammelakt über alle `Bewilligt`-Anträge (Summe, Anzahl)
- SEPA-XML (pain.001) als Datenträger, Erzeugung aus IBAN, Betrag, Name
- Freigabe durch die Finanzabteilung; danach Status `Zahlung freigegeben` / `Ausgezahlt`
- Änderungen nach Überleitung nur mehr über Storno + neuen Antrag

## Stand und nächste Schritte

1. ✔ Liste, Rollen, Statuswechsel, Nachbesserungsfrist (14 Tage), Stufen-/Betragsberechnung
2. Formular-Eingang (XML-Import des AFS-Antrags) und Personen-Matching
3. Word-Erzeugung (Vorlagen-Dokumente + Platzhalter)
4. Sammelakt + SEPA-XML + Freigabe
5. Beilagen (Studien-/Anmeldeformular) und Dokumentenablage
