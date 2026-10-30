# Fixtures: sources and findings (fetched 2026-10-30)

All files are raw bytes as received (no reformatting), fetched with `curl` and `User-Agent: investor-personal/1.0`, at most 1 request/s per host.
Note: a Firefox/Chrome-like User-Agent got `Connection reset` from www.gpw.pl; the honest `investor-personal/1.0` UA works. Use it in `PoliteHttpClient`.
Attachments (PDF) on gpw.pl are rejected by a WAF ("Request Rejected", HTTP 200 with HTML) for plain curl. Not circumvented, so no PDFs saved.

## Files

| File(s) | URL / request | Fetched | Findings |
|---|---|---|---|
| `gpw/listing-2026-09-21-p1.html` | POST www.gpw.pl/ajaxindex.php, date=21-09-2026, offset=0 | 2026-10-30 | 49 items. ASBIS RB 23/2026 at 08:09:21, geru_id=497297. |
| `gpw/listing-2026-08-21-p1.html` | same, 21-08-2026 | 2026-10-30 | 40 items. ASBIS RB 20/2026 (July sales) at 08:15:21. |
| `gpw/listing-2026-08-06-p1.html`, `-p2.html` | same, 06-08-2026, offset 0 and 50 | 2026-10-30 | 51 items. ASBIS H1 (Półroczny) at 00:20:21 is the only item on p2 (geru_id=494951). Midnight case. |
| `gpw/listing-2026-08-05-p1.html` | same, 05-08-2026 | 2026-10-30 | 29 items, last item of the day 20:52:12 (previous-day side of the midnight case). |
| `gpw/listing-2026-07-29-p1.html` | same, 29-07-2026 | 2026-10-30 | 47 items (NOT more than 50). XTB RB 18/2026 at 17:14:57 (geru_id=494560). 23 items after 16:30. |
| `gpw/listing-2026-05-12-p1.html`, `-p2.html` | same, 12-05-2026, offset 0 and 50 | 2026-10-30 | 62 items over 2 pages. Creepy Jar RB 9/2026 at 17:22:21 (geru_id=488521). 25 items after 16:30. Real multi-page day. |
| `gpw/backfill-asbis.html` | POST www.gpw.pl/ajaxindex.php, searchText=ASBIS, date empty, typeRaports[]=RB, offset=0, limit=200 | 2026-10-30 | Raw MONTHLY_BACKFILL response, 200 items (newest 20-10-2026, back to 2020). |
| `biznesradar/symbols-raw.js` | GET https://www.biznesradar.pl/service-data-js/8 | 2026-10-30 | Raw full symbol list (`var symbols = [...]`, 1.68 MB). |
| `nc/report-present24-q2-2026-08-14.html` | GET https://newconnect.pl/komunikat?geru_id=243314 | 2026-10-30 | NC quarterly EBI (Present24, Kwartalny 14-08-2026 23:34:03): PDF attachments only, no financial table in HTML. |
| `nc/report-beein-current-sales-2026-10-29.html` | GET https://newconnect.pl/komunikat?geru_id=244589 | 2026-10-30 | NC current ESPI with results text (BEEIN "Szacunkowe łączne przychody ... za III kwartał 2026"), full text in HTML. |
| `nc/report-lokaty-budowlane-current-2026-09-21.html` | GET https://newconnect.pl/komunikat?geru_id=243921 | 2026-10-30 | NC current EBI from the saved listing (schedule change), shows the detail page for a listing entry. |
| `nc/listing-2026-09-21-p1.html` | POST newconnect.pl/ajaxindex.php, page=spolki-komunikaty-spolek, ESPI+EBI | 2026-10-30 | 21 items, ESPI and EBI. Host must be `newconnect.pl` (www. redirects 302 to it). |
| `reports/asbis-rb-23-2026.html` | GET https://www.gpw.pl/komunikat?geru_id=497297 | 2026-10-30 | Contains "ok. 604 mln USD" and "_316 mln USD_". |
| `reports/asbis-rb-sales-YYYY-MM.html` (12) | GET https://www.gpw.pl/komunikat?geru_id=ID | 2026-10-30 | YYYY-MM is the PUBLICATION month, 2025-09 to 2026-08. IDs: 2025-09=473946, 2025-10=475584, 2025-11=477234, 2025-12=479118, 2026-01=480594, 2026-02=482189, 2026-03=484128, 2026-04=486464, 2026-05=489193, 2026-06=491914, 2026-07=494055, 2026-08=495649 (they report Aug 2025 .. Jul 2026 sales). The 21-09-2026 RB (Aug 2026 sales) is `asbis-rb-23-2026.html`; 20-10-2026 (geru_id=499087, Sep 2026) not saved. |
| `reports/asbis-ps-2026.html` | GET https://www.gpw.pl/komunikat?geru_id=494951 | 2026-10-30 | H1 periodic report page, no body text (periodic reports have no title). Lists 4 PDF attachments as `attachment/zalNN_...pdf,20260806_002021_0000616603,20260806`; fetching one returned the WAF rejection page, so no PDF saved. |
| `reports/xtb-prelim-q2-2026.html` | geru_id=494560 | 2026-10-30 | Title "Wstępne wyniki finansowe i operacyjne za I półrocze 2026 roku". |
| `reports/crj-prelim-q1-2026.html` | geru_id=488521 | 2026-10-30 | Title "Wstępne wybrane dane finansowe za 1 kwartał 2026 r." |
| `reports/irrelevant-nwz.html` | geru_id=488507 | 2026-10-30 | CDRL "Ogłoszenie o zwołaniu Walnego Zgromadzenia" (12-05-2026 15:16:19). |
| `reports/correction.html` | geru_id=488512 | 2026-10-30 | MO-BRUK "Korekta raportu bieżącego nr 7/2026 ..." (12-05-2026 15:46:00). |
| `biznesradar/{ASBISC,XTB,CRJ}-pl-q.html` | GET https://www.biznesradar.pl/raporty-finansowe-rachunek-zyskow-i-strat/{SYMBOL},Q | 2026-10-30 | Has row "Data publikacji" (ASBISC last: 2026-08-06). |
| `biznesradar/{ASBISC,XTB,CRJ}-valuation.html` | GET https://www.biznesradar.pl/wskazniki-wartosci-rynkowej/{SYMBOL} | 2026-10-30 | ASBISC 2026/Q2: Kurs 98,00, Cena / Zysk 12,00. |
| `biznesradar/symbol-lookup-asbis-xtb-crj.json` | extracted from https://www.biznesradar.pl/service-data-js/8 | 2026-10-30 | The three entries (only derived file: entries cut out of the JS array, not the full response). |
| `quotes/gpw-YYYY-MM-DD.xls` (87) | GET https://www.gpw.pl/archiwum-notowan?fetch=1&type=10&instrument=&date=DD-MM-YYYY | 2026-10-30 | Every session 2026-04-08..2026-05-12 and 2026-06-25..2026-09-21 (each event has at least 20 sessions before it). ASBIS 2026-09-18 close 164,2; 2026-09-21 close 175,0. |
| `quotes/nc-2026-09-21.xls` | GET https://newconnect.pl/pub/NEWCONNECT/statystyki/statystyki_dzienne/NC_20260921.xls | 2026-10-30 | NC daily sample (see NC_DAILY). |
| `quotes/nc-2026-10-29.xlsx` | GET https://newconnect.pl/pub/NEWCONNECT/statystyki/statystyki_dzienne/NC_20261029.xlsx | 2026-10-30 | NC daily sample in the current `.xlsx` format (102 KB, magic `PK`), sheet `akcje` has 3 extra columns. 1SOLUTION close 0.099, volume 43689, turnover 4270.41. Non-session or wrong extension: 302 to `/page-404` (HTML, 200 after redirect). Main archive `Obrót` is in thousands of PLN; NC `Wartość obrotu` is in PLN. |
| `index/gpw-indices-YYYY-MM-DD.xls` (87) | GET https://www.gpw.pl/archiwum-notowan?fetch=1&type=1&instrument=&date=DD-MM-YYYY | 2026-10-30 | Same dates as quotes. Contains WIG, WIG20, NCINDEX, NCFINDEX, ... |

Non-session days (weekend, holiday, e.g. 2026-05-01) return an HTML page (HTTP 200) instead of XLS. Detect by magic bytes `D0 CF 11 E0`.

## Resolved formats (formerly NIEZWERYFIKOWANE)

### GPW_LISTING
- `POST https://www.gpw.pl/ajaxindex.php`, form-urlencoded. Works with plain curl and the `investor-personal/1.0` UA. robots.txt allows it (only `/pub/NEWCONNECT/DI/EUROSNACK` is disallowed).
- Form: `action=GPWEspiReportUnion&start=ajaxSearch&page=komunikaty&format=html&lang=PL&letter=&offset=N&limit=50&categoryRaports[]=ESPI&typeRaports[]=RB&typeRaports[]=Q&typeRaports[]=P&typeRaports[]=O&typeRaports[]=R&searchText=&date=DD-MM-YYYY`. (The spec's `typeRaports[]=RB/Q/P/O/R` means the field repeated once per type.)
- Pagination: `offset` (0, 50, 100, ...) and `limit` (50 used; limit=200 also accepted). Newest first. A page with fewer than `limit` items is the last; an empty result is an 11-byte response. Verified: 12-05-2026 gives 50 + 12; 06-08-2026 gives 50 + 1.
- Response: HTML fragment of `<li>` items:
  - `<span class="date">21-09-2026 08:09:21 | Bieżący | ESPI | 23/2026</span>` is Warsaw time to the second | type (Bieżący/Półroczny/Kwartalny/...) | ESPI/EBI | number (`/2026` for periodic reports);
  - `<strong class="name"><a href="komunikat?geru_id=497297&title=...">ASBISC ENTERPRISES PLC (CY1000031710)</a>` gives name and ISIN;
  - `<p>` holds the title (empty for periodic reports), plus "Kurs" and "Zmiana" spans (GPW only).
- YES, each item links to its detail page: `komunikat?geru_id=ID` (the `title` param is optional).
- NewConnect: same call on host `newconnect.pl` (NOT www.) with `page=spolki-komunikaty-spolek` and `categoryRaports[]=ESPI&categoryRaports[]=EBI`. Detail links there are also `komunikat?geru_id=ID` (ids 24xxxx, relative to newconnect.pl).

### REPORT_DETAIL
- `GET https://www.gpw.pl/komunikat?geru_id=ID` (plain curl, 100-120 KB HTML). Contains the full ESPI body text (Polish and an English copy) and the list of attachments. Bankier is not needed for RB text.
- Example: geru_id=497297 gives "szacunkowe skonsolidowane przychody za sierpień 2026 r. wyniosły ok. 604 mln USD ... _316 mln USD_".
- Periodic reports: the page lists attachments (PDF, relative URL `attachment/zalNN_name.pdf,<id>,<date>`), body is mostly empty. PDF download is blocked by the WAF for plain curl.
- Fallback: Bankier detail page (`https://www.bankier.pl/wiadomosc/ASBISc-Wyniki-finansowe-PS-2026-9178519.html`) returns 200 with plain curl (450 KB).

### REPORT_DETAIL for NewConnect
- Same pattern on the NC host: `GET https://newconnect.pl/komunikat?geru_id=ID` (plain curl, 40-70 KB HTML); the listing links are identical to GPW's. Verified for ESPI and EBI.
- Current reports (RB, ESPI or EBI) carry the full text in HTML, e.g. BEEIN geru_id=244589 (estimated Q3 2026 revenue text). No "Wybrane dane finansowe" table was found in any saved NC page.
- Periodic quarterly EBI (Present24, geru_id=243314) has NO text and NO table in HTML: only an "Załączniki" list with PDFs (`/ebi/files/gpwdata/4899_20260814_233500_Present24_Raport_KartalnyII-Q2026.pdf`, `..._SF-Raport-kwartalny-II-Q2026.pdf`) and an XHTML link `/ebi/files/gpwdata/4899_20260814_233500_main.xhtml`. The `main.xhtml` and PDF downloads were not fetched. This matches the plan (NC quarterly = `UNSCORED_NO_DATA` in phase 1).
- NC listing items for periodic reports have no title text for P/Q but EBI quarterly ones show "Raport kwartalny za II kwartał" in `<p>`.

### BR_SYMBOL_LOOKUP
- `GET https://www.biznesradar.pl/service-data-js/8` (plain curl, about 1.7 MB JS: `var symbols = [ ... ];`, all instruments). Entry for ASBIS: `{"oid":96,"shortName":"ASB","mediumName":"ASBIS","fullName":"ASBISC","source":"BDM","ut":"ASBISC","currency":"PLN",...}`.
- `shortName` = GPW ticker, `ut` = BiznesRadar URL symbol (ASB -> ASBISC, XTB -> XTB, CRJ -> CRJ), `source` BDM = GPW/NC, `close` = last close. No ISIN in it.
- ISIN to symbol: the GPW archive XLS has columns Data, Nazwa, ISIN, Waluta, ... where Nazwa is a short name (ASBIS, CREEPYJAR, XTB) equal to BiznesRadar `mediumName`. Chain: ISIN -> Nazwa (archive XLS) -> `mediumName` -> `ut`. NC instruments appear in the same list but take their name from the NC daily file (NC_DAILY).
- Search URLs `/szukaj/?q=` return 404 (the site's autocomplete runs client-side on this same list).

### MONTHLY_BACKFILL
- Same GPW_LISTING call with `searchText=ASBIS`, EMPTY `date`, `categoryRaports[]=ESPI`, `typeRaports[]=RB`, `limit=200` (use offset to paginate). Returns the company's RBs across all years, newest first (200 items reached back to 2020 for ASBIS). Filter client-side by title (contains "szacunkowych miesięcznych") and by ISIN.
- Example: `...&offset=0&limit=200&categoryRaports[]=ESPI&typeRaports[]=RB&searchText=ASBIS&date=` gave the 12 monthly RBs in 2025-09-01..2026-08-31 (ids listed above). Plain curl works. A date-range value for `date` was not tested; empty date plus client-side filter is enough.

### INDEX_DAILY
- `GET https://www.gpw.pl/archiwum-notowan?fetch=1&type=1&instrument=&date=DD-MM-YYYY` returns XLS (BIFF/OLE2, read with Apache POI HSSF) with all indices. Plain curl works. Columns: Data, Nazwa, ISIN, Waluta, Kurs otwarcia, Kurs max, Kurs min, Kurs zamknięcia, Zmiana, Wolumen, Liczba Transakcji, Obrót, ...
- Example 2026-09-21: `WIG` (PL9999999995) close 158381,53; `NCINDEX` (PL9999999565) close 276,25; WIG20 4214,61; NCFINDEX 1770,8.
- Non-session day returns HTML. Saved in `index/`.

### NC_DAILY
- NewConnect stocks are not in the main stock archive (`type=10` has about 400 main-market rows). `newconnect.pl/archiwum-notowan` redirects 301 to `statystyki-okresowe`, a year/month statistics page, not daily quotes.
- Daily file per session: `GET https://newconnect.pl/pub/NEWCONNECT/statystyki/statystyki_dzienne/NC_YYYYMMDD.xls` (links listed on `https://newconnect.pl/statystyki-okresowe`, section "dane po zakończeniu sesji"). Plain curl works. Extension changed: `.xls` (BIFF) up to early October 2026, `.xlsx` from 2026-10-05 (NC_20261005.xlsx verified, magic `PK`); the wrong extension gives a 302. Try one, fall back to the other.
- The workbook has sheets: `indeksy`, `statystyka`, `akcje` (per-company quotes, the one to read), `prawa poboru`, `transakcje pozasesyjne`, `wskaźniki spółek` (ISIN, sector, shares, market value, book value, last report date/type, P/BV, P/E, yield), `wskaźniki sektorowe`, `legenda`. Caution: do not confuse `akcje` with `wskaźniki spółek`; both list TREX.
- Sheet `akcje`, 0-based columns. Data rows are those whose column 8 is an ISIN (the header block above them is 7 non-empty rows in `.xls`, 8 in `.xlsx` where one extra header row exists; on `.xlsx` the sheet also starts with one blank row). Unlike the main-market archive, headers are bilingual (PL / EN) over several rows.

| Col (.xls) | Col (.xlsx) | Header (PL / EN) | Meaning |
|---|---|---|---|
| 5 | 5 | Sektor / Sector | sector code (text, e.g. "660") |
| 6 | 6 | System notowań / Trading system | e.g. `C` continuous |
| 7 | 7 | Segment | `NB` NC Base, `NF` NC Focus, `NA` NC Alert |
| 8 | 8 | Kod ISIN / ISIN code | ISIN |
| 9 | 9 | Nazwa / Security name | name, e.g. `TREX`, `1SOLUTION` (BiznesRadar `mediumName`) |
| 11 | 11 | Skrót / Ticker | short ticker, e.g. `TRX` |
| 12 | 12 | Waluta notowania | currency |
| 13 | 13 | Kurs zamknięcia / Closing price | close |
| 14 | 14 | Kurs odniesienia / Reference price | previous reference |
| 15 | 15 | Zmiana kursu (%) / Change | change % |
| 16 | 16 | Kurs otwarcia / Opening | open |
| 17 | 17 | Kurs min. / Low | low |
| 18 | 18 | Kurs maks. / High | high |
| 19 | 19 | Kurs ostatni / Last | last trade price |
| 20 | 20 | Kurs średni (zagregowany) / Average | average price (`.xlsx`: aggregated) |
| n/a | 21 | Kurs średni (arkusz zleceń) | `.xlsx` only: order book average |
| n/a | 22 | Kurs średni (transakcje Cross) | `.xlsx` only, `---` when none |
| 21 | 23 | Liczba transakcji / Number of trades | trades |
| 22 | 24 | Wolumen (szt.) / Volume | volume |
| 23 | 25 | Wartość obrotu / Trading value | turnover (PLN) |

- Example `.xls` 2026-09-21 TREX: `[..., '660','C','NB','PLAUXLM00026','TREX','','TRX','PLN', 4.2, 4.06, 3.45, 4.06, 4.06, 4.2, 4.2, 4.1205, 29, 17290, 71243.44]` (close 4.2, ref 4.06, change 3.45%, open 4.06, low 4.06, high 4.2, last 4.2, avg 4.1205, 29 trades, volume 17290, turnover 71243.44). Example `.xlsx` 2026-10-05 TREX: close 4.88, ref 4.8, change 1.67, open 4.9, low 4.8, high 4.9, last 4.88, avg 4.8625, trades 18, volume 4652.
- Numbers in `.xlsx` may be stored as text strings for some rows (e.g. '0.0998'): parse both numeric and string cells.
- Fallback: BiznesRadar `/notowania-historyczne/{ut}` (not tested for NC).

## Step 5 checks (actual values)

| Check | Expected | Actual | Result |
|---|---|---|---|
| asbis-rb-23-2026.html contains 604 and 316 | yes | "ok. 604 mln USD", "_316 mln USD_" | OK |
| 2026-09-21 listing has ASBIS at 08:09 | 08:09 | 08:09:21 | OK |
| 2026-08-06 listing has ASBIS at 00:22 | 00:22 | **00:20:21** (on page 2, offset 50) | MISMATCH |
| XLS 2026-09-18 ASBIS close | 164,20 | 164,2 | OK |
| XLS 2026-09-21 ASBIS close | 175,00 | 175,0 | OK |
| ASBISC 2026/Q2 Kurs / C/Z | 98,00 / 12,00 | 98,00 / 12,00 | OK |

Other differences from the research facts (GPW listing times; the earlier numbers were probably Bankier times):
- ASBIS July sales RB: GPW 21-08-2026 08:15:21 (research said 08:16).
- XTB prelim: GPW 29-07-2026 17:14:57 (research said 17:56); title is "za I półrocze 2026".
- Creepy Jar prelim: GPW 12-05-2026 17:22:21 (research said 17:45).
- 2026-07-29 has 47 ESPI items (task expected more than 50). The days with more than 50 are 2026-05-12 (62) and 2026-08-06 (51); both saved with all pages.

## ATTACHMENTS (Task 9b Step 1 spike, 2026-10-30)

PDF attachment URL form on the page: `attachment/zalNN_name.pdf,<id>,<date>` (does NOT end with `.pdf`). Targets: ASBIS H1 `zal03_2026_Q2_ASBIS_EN_report_Q2_andH1_2026.pdf,20260806_002021_0000616603,20260806` and XTB `zal01_Zalacznik_do_RB_nr_18_2026_PL.pdf,20260729_171457_0000616140,20260729`. All attempts used User-Agent `investor-personal/1.0`. Every response: HTTP 200, Content-Type text/html, body `<html><head><title>Request Rejected</title></head><body>The requested URL was rejected. Please consult with your administrator. Your support ID is: ...` followed by a bot-defence JS blob.

| Attempt | Result |
|---|---|
| Referer = komunikat page, `Accept: application/pdf,*/*` | Request Rejected |
| Fetch komunikat page first (cookie jar: SID, lang_code, SESSIONID), then PDF with `-b` cookies + Referer + Accept | Request Rejected |
| Same with Accept-Language `pl,en;q=0.8`, Accept `*/*`, cookies persisted across both requests | Request Rejected |
| Comma percent-encoded (`%2C`) in the URL | Request Rejected |
| URL under `/komunikat/attachment/...` | Request Rejected |
| XTB PDF with Referer + cookies | Request Rejected |

Conclusion: BLOCKED. The WAF requires a JS-capable client; no legitimate header/cookie method works. No PDFs saved.

### ATTACHMENTS: Bankier route (spike 2, 2026-10-30) - WORKS

- Bankier ESPI copy pages link the PDFs on a static host: `https://bonnier.pl/static/att/emitent/YYYY-MM/<gpwAttachmentId>_<digits>_<fileName>.pdf` (e.g. `.../2026-08/20260806_002021_1918648160_Raport_polroczny_H1_2026.pdf`). The prefix `YYYYMMDD_HHMMSS` equals the GPW attachment id prefix; the numeric part differs from GPW's.
- Plain curl, UA `investor-personal/1.0`, Referer = Bankier page: HTTP 200, `%PDF`, no WAF, no cookies. Fetched 4 ASBIS H1 PDFs (680 KB, 1.36 MB, 1.08 MB, 430 KB) and the XTB PDF (2.3 MB). Saved as `attachments/asbis-ps-2026-{1..4}.pdf` (1 Sprawozdanie_finansowe, 2 Raport_polroczny, 3 EN report Q2 and H1 with "revenues were USD 1,696.7 million" in Q2, 4 ASBISc_FS) and `attachments/xtb-prelim-q2-2026-1.pdf`. Text extractable (pdftotext 65-116k chars each).
- ASBIS page: `https://www.bankier.pl/wiadomosc/ASBISc-Wyniki-finansowe-PS-2026-9178519.html`. XTB page: `https://www.bankier.pl/wiadomosc/XTB-Wstepne-wyniki-finansowe-i-operacyjne-za-I-polrocze-2026-roku-9175226.html` (found on the company list). The page has `2026-07-29T17:14:00+0200` (minute precision, matches GPW 17:14:57) and the same title as GPW.
- Mapping options: (a) `https://www.bankier.pl/rss/espi.xml` has only 10 items (global, all companies), so useless in a 16:30 peak. (b) Per-company list `https://www.bankier.pl/gielda/notowania/akcje/{TICKER}/komunikaty` (GPW main; NC: `.../notowania/new-connect/{NAME}/komunikaty`; `/espi/{page}` also 200): HTML with `a.m-quotes-announcements-item__anchor` href to the article, title text, and `span.m-quotes-announcements-item__date` "DD.MM.YYYY HH:mm" (minute precision). ~680 KB per request. (c) Global `/gielda/wiadomosci/komunikaty-spolek` not tested.
- Most robust mapping: fetch the company list (ticker = Bankier symbol; XTB works as `XTB`, ASBIS is on the NC path `new-connect/ASBIS` in the link found, main path unverified for ASBIS) and match by publication minute (listing publishedAt truncated to minute, Europe/Warsaw) plus normalised title (periodic GPW reports have empty titles, so match by minute and, if several, by "PS/P/Q" title keywords). Fallback: match title prefix + date.
- Latency not measured for fresh reports: both mapped reports were weeks old. Bankier list showed items at "28.10.2026 17:09" the same minute as GPW publication style, but the lag between GPW publication and the Bankier page/attachment appearance is unknown. Needs a retry loop (poll the list every few minutes for up to ~1 h after a periodic report) and the pipeline must treat a missing Bankier copy as "try later", not as no data.
- BiznesRadar: ASBISC `Data publikacji` row only carries report dates (e.g. 2026-08-06, past quarters); the page has only a quote `<time class="q_ch_date">` (last price time), no statement update timestamp. Update latency after a periodic report could not be measured (the Q2 report is already there weeks later). Would need a live check right after the next report.

### ATTACHMENTS: Bankier fixtures (Task 9b)
- `bankier/asbis-komunikaty.html`: GET https://www.bankier.pl/gielda/notowania/akcje/ASBIS/komunikaty (main-market path works; `/new-connect/ASBIS/` redirects to it). `bankier/xtb-komunikaty.html`: same for XTB. Fetched 2026-10-30.
- `bankier/asbis-article-ps-2026.html`, `bankier/xtb-article-prelim-q2-2026.html`: the Bankier article pages (PDF links on bonnier.pl).
- `attachments/*.pdf`: see the Bankier route above. Slug: the GPW short name (ASBIS, XTB), not derivable from the company name "ASBISC ENTERPRISES PLC": the locator tries first word, then trimmed by 1 and 2 characters (ASBISC returns 500, ASBIS works).
