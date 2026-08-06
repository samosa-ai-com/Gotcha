# Project Falcon — Q3 Proposal

> **Status:** Draft v2.1 — for review by the whole team.

## Summary

Project Falcon is a voice-first assistant that runs locally on Android. This
quarter we are adding **document attachments to chat mode** so users can ask
questions about PDFs, spreadsheets and presentations.

## Feature highlights

- Attach PDF, DOCX, XLSX, PPTX and plain-text files from the system picker.
- Extracted text is fed to the model inside the user message.
- A truncated document shows an explicit note so the user knows.
- Legacy `.doc` / `.xls` files are rejected with a clear error.

## Acceptance criteria

1. Selecting a PDF shows its extracted text and page count.
2. A spreadsheet with multiple sheets returns each sheet's rows.
3. A corrupt file surfaces an ERROR bubble instead of crashing.

## Rollout plan

| Phase | Milestone | Date |
|-------|-----------|------|
| Alpha  | Internal dogfood | Aug 6 |
| Beta   | 1,000 invited users | Aug 15 |
| GA     | Play Store release | Sep 30 |

## Open questions

- Should we support images inside DOCX (e.g. embedded screenshots)?
- What is the cap for a single attachment in the free tier?
