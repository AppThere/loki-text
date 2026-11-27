# Strict export

How `wordcraft-cli convert --strict`, `render --strict` and the engine's strict export behave. The terms are
defined in [GLOSSARY.md](../GLOSSARY.md#export).

```sh
wordcraft-cli convert report.docx report.pdf --strict   # exit 3, no file, if a character or picture would be left out
```

`convert --strict` (a `.pdf` or `.png` output only) and `render --strict` (a PNG of one page) refuse a silently incomplete render: they exit 3
and write nothing if the output would draw a character no font has (an empty box) or leave out a
picture. Exit codes are 0 (written), 1 (unreadable input, usage error, or `--strict` with another
output type, checked before the input is read) and 3 (strict refusal). On exit 3, stderr has one
`wordcraft-cli: strict: …` line per kind, then one JSON line:

```sh
wordcraft-cli convert in.docx out.pdf --strict
# wordcraft-cli: strict: 1 character cannot be drawn: U+2D30 (×3)
# wordcraft-cli: strict: 1 picture(s) left out: word/media/image2.bin (header2.xml): unsupported format
# {"schema":1,"strict":"failed","missing_glyphs":[{"cp":"U+2D30","count":3}],"missing_pictures":[{"part":"word/media/image2.bin","source_part":"word/header2.xml","reason":"unsupported_format"}],"truncated":false,"total_missing_glyphs":1,"total_missing_pictures":1,"glyphs_truncated":false,"pictures_truncated":false}
```

The JSON line (schema 1, also what `file.strictReport` returns) has `schema`, `strict`
(`"passed"` or `"failed"`), `missing_glyphs` (entries `{"cp", "count"}`, sorted by code point),
`missing_pictures` (entries `{"part", "source_part", "reason"}`: the picture's part or target, the
story part it sits in such as `word/header2.xml`, and a reason code; `source_part` is also
`background` (the page picture), `body`, `header`, `footer`, `footnote`, `endnote`, `comment` or
`text box` where the part name is not known (a document not opened from a DOCX), or a chunk path such as
`word/afchunk1.docx > word/header1.xml` for a picture inside an embedded DOCX chunk), `truncated`, the two totals
`total_missing_glyphs` and `total_missing_pictures`, and `glyphs_truncated` and
`pictures_truncated` for each list. The reason codes are `missing_part`, `external_target`,
`link_only`, `undecodable` and `unsupported_format`; the stderr lines use plain words for them. When a list is cut, the stderr line gives the total
(`300 characters cannot be drawn (first 256 listed)`).
Each list stops at 256 entries separately; `truncated` is true when either was cut, and the totals
count every distinct character and picture found, those past the cap included. Only characters the output draws count (not hidden text, or deleted text
without `--markup`), and a character a fallback font draws is fine. Text inside a WMF or EMF picture counts too, shaped in the font the picture names with no fallback. A Private Use Area character
fails only if no installed font maps it. The result depends on the fonts installed on the host
(see storytold/wordcraft#5624 for a fixed font set). An external picture target is never fetched: it is reported, not
loaded. Pictures dropped when a DOCX is opened (a missing part, an external target, or a `link only` picture with no embedded copy) are reported for DOCX input only. They have no page, so a PNG of any page is also refused for a missing or external picture anywhere in the document, unused headers included. `render --strict` checks only the page it
writes (`--page`; `--scale` doesn't matter), prints nothing on stdout when it refuses, and reports the
same way. The engine takes `strict: true` on `file.save`, `file.saveAs`, `file.exportPdf` and
`file.exportPng`, and `file.strictReport {markup?, page?}` returns the report without writing.
