# Sample documents for testing Gotcha's attachment feature

Transfer these files to your phone (USB / cloud / `adb push`), then in chat mode
tap **+**, pick a file, send, and check the model's reply references the text
below.

## Happy path — should extract text and be answered

| File | Extracted text (the model sees this) |
|------|--------------------------------------|
| `report.pdf` | "Project Falcon — Launch Report", 2 pages, with a table of format adoption |
| `proposal.docx` | "Project Falcon — Q3 Proposal" with headings and a rollout table |
| `budget.xlsx` | Two sheets: "Budget" rows (Item / Qty / Unit Price / Total) then "Forecast" rows (Category / Qty / Total) |
| `deck.pptx` | Three slides: Quarterly Review → Document attachments → What's next |
| `page.html` | Same launch report as text (HTML tags stripped) |
| `document.rtf` | The proposal text (with RTF markup — it's parsed as plain text) |
| `readme.txt`, `notes.md`, `data.csv`, `data.tsv`, `config.json`, `data.xml`, `settings.yaml`, `config.ini`, `app.properties`, `sample.env`, `.gitignore`, `app.log`, `server.py`, `Main.kt`, `styles.css`, `queries.sql` | Each file's own content; nothing else |

## Error path — should NOT be sent, an ERROR bubble explains why

| File | Expected result |
|------|-----------------|
| `legacy.doc` | ERROR: "Legacy .doc files are not supported" |
| `legacy.xls` | ERROR: "Legacy .xls files are not supported" |
| `corrupt.pdf` | ERROR: "Could not read this document" |
| `long.txt` | Sends, but truncated (~40k chars) with a note: "document text was truncated" |

## Tips

- After picking a file you'll see a document chip above the composer; send to attach.
- You can re-edit a sent document message — the extracted text is retained.
- Files over 20 MB are rejected ("File too large"). `long.txt` is ~173 KB on purpose
  (it tests the *character* truncation, not the size cap).
- All Office/PDF files were verified to open in LibreOffice and to parse with
  the app's own `DocumentParser` unit tests.
