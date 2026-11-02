# Uruchomienie na mini-PC

Instrukcja krok po kroku. Serwer działa na mini-PC w Dockerze i jest wystawiony publicznie przez Tailscale Funnel pod adresem HTTPS. Telefon nie potrzebuje Tailscale ani VPN.

## 1. Docker i Docker Compose na mini-PC

1. Zainstaluj Docker Engine razem z wtyczką Compose: https://docs.docker.com/engine/install/
2. Sprawdź: `docker --version` i `docker compose version`.
3. Na Linuxie dodaj siebie do grupy `docker` (`sudo usermod -aG docker $USER`) i zaloguj się ponownie.
4. Skopiuj z PC na mini-PC do `/home/grzegorz/investor` plik `docker-compose.yml` i katalog `server` (rozdział 13). Dalsze komendy wykonuj w tym katalogu.

## 2. Tailscale Funnel na mini-PC

Tailscale jest potrzebny tylko na mini-PC. Telefon nie potrzebuje aplikacji Tailscale ani włączonego VPN.

1. Zainstaluj Tailscale na mini-PC (https://tailscale.com/download) i zaloguj się: `sudo tailscale up`.
2. Włącz HTTPS i Funnel dla swojego tailnetu i wystaw port 8080 pod ścieżką `/investor`. Przy pierwszym razie Tailscale poprosi w panelu o włączenie certyfikatów HTTPS i Funnel. Funnel działa też w planie darmowym.
3. Mini-PC nazywa się w Tailscale `debian-server`, a tailnet `tail8124e8`.
4. Publiczny adres serwera to `https://debian-server.tail8124e8.ts.net/investor`, bez `:8080`. Funnel sam łączy się z `127.0.0.1:8080`, dlatego `HOST_BIND` zostaje na `127.0.0.1`.
5. Sprawdź konfigurację: `tailscale funnel status`. Wyłączenie: `sudo tailscale funnel reset`.
6. Funnel uruchomiony z flagą `--bg` działa też po restarcie mini-PC, o ile Tailscale startuje z systemem. Port 8080 nie jest wystawiony w sieci domowej.

## 3. Firebase

Projekt Firebase już istnieje. Potrzebne są dwa pliki.

**Klucz konta serwisowego (dla serwera):**
1. Firebase Console → Ustawienia projektu → Konta usług → „Generuj nowy klucz prywatny”.
2. Na mini-PC utwórz katalog `secrets/` w głównym katalogu projektu i zapisz plik jako `secrets/firebase.json`.
3. Serwer w kontenerze działa jako użytkownik o numerze 10001. Nadaj mu plik: `sudo chown 10001 secrets/firebase.json && chmod 400 secrets/firebase.json`. Montowanie tylko do odczytu zachowuje właściciela pliku, więc ten użytkownik go odczyta, a nikt inny nie.
4. Nie ustawiaj `FIREBASE_CREDENTIALS_PATH` w `.env`. Compose sam montuje plik tylko do odczytu i ustawia ścieżkę `/run/secrets/firebase.json`.
5. Katalog `secrets/` jest w `.gitignore`. Plik musi istnieć przed `docker compose up`, inaczej Docker utworzy w jego miejscu pusty katalog.

**`google-services.json` (dla apki):**
1. Firebase Console → Ustawienia projektu → Twoje aplikacje → aplikacja Android (nazwa pakietu `pl.investor.app`).
2. Pobierz `google-services.json` i zapisz jako `android/app/google-services.json`.
3. Ten plik jest wymagany przed pierwszym buildem Androida. Bez niego build się nie uda. Jest w `.gitignore`.

## 4. Konto Anthropic

1. https://console.anthropic.com → Settings → Limits: ustaw miesięczny limit wydatków na **5 USD**.
2. Utwórz workspace o nazwie `investor`.
3. W tym workspace utwórz klucz API i skopiuj go (widać go tylko raz).
4. Serwer ma własny, niższy limit 4,75 USD miesięcznie. Limit 5 USD w Console to twarda ochrona.
5. Klucz mam już wygenerowany zobacz plik: C:\Users\moler\OneDrive\Dokumenty\klucz-api-claude.txt przekopiuj go do .env

## 5. Plik `.env`

1. `cp .env.example .env`
2. Uzupełnij wartości:

| Zmienna | Znaczenie |
|---|---|
| `ANTHROPIC_API_KEY` | klucz z kroku 4 |
| `INVESTOR_API_TOKEN` | token do API; wymyśl długi losowy ciąg, np. `openssl rand -hex 32`; ten sam wpiszesz w apce |
| `CONTACT_EMAIL` | moler555@gmail.com; trafia do nagłówka `From` zapytań do źródeł (GPW, Bankier, BiznesRadar) |
| `POSTGRES_PASSWORD` | dowolne hasło do bazy; baza nie jest wystawiona na zewnątrz |
| `HOST_BIND` | adres, na którym serwer nasłuchuje; zostaw `127.0.0.1`, bo Funnel łączy się lokalnie |

3. `docker compose up` odmówi startu, gdy którakolwiek z czterech pierwszych zmiennych jest pusta albo nieustawiona. To celowe.
4. `.env` nie wchodzi do repozytorium. Nie wysyłaj go nigdzie.

## 6. Start serwera

```
docker compose up -d --build
docker compose logs -f server
```

Pierwszy build trwa kilka minut. Serwer dostaje do 1 GB RAM (heap to połowa), baza do 512 MB. Po starcie sprawdź:

Wpisz token bez zostawiania go w historii powłoki:

```
read -rs T
curl -H "X-Investor-Token: $T" https://debian-server.tail8124e8.ts.net/investor/api/status
```

Możesz też sprawdzić lokalnie z mini-PC: `curl http://127.0.0.1:8080/api/status` z tym samym nagłówkiem. Publiczny ruch idzie przez HTTPS, więc token jest szyfrowany.

Na mini-PC token możesz też pobrać prosto z działającego serwera, bez wpisywania. Komendę wykonaj w `/home/grzegorz/investor`:

```
T=$(docker compose exec -T server printenv INVESTOR_API_TOKEN)
```

Odpowiedź 401 oznacza zły albo pusty token, np. gdy w nowym terminalu zmienna `T` nie jest ustawiona. Dodaj `-i` do `curl`, żeby widzieć kod odpowiedzi.

Oczekiwane: kod 200, lista źródeł z `lastSuccessAt` z ostatnich 2 minut (w godzinach publikacji) i `fcmConfigured: true`. `GET /actuator/health` działa bez tokenu.

## 7. Backfill historii

Bez historii oceny nie mają danych do porównań. Backfill robi cztery rzeczy, w tle:
1. Przegląda listy komunikatów GPW i NewConnect z ostatnich 13 miesięcy, dzień po dniu, aż do wczoraj, i zbiera z nich raporty ze sprzedażą miesięczną. Dzisiejsze raporty obsługuje bieżący polling.
2. Grupuje te raporty według spółki (po ISIN) i wyciąga z nich przychody przez Claude. Raporty z NewConnect są zapisywane jako NC.
3. Pobiera notowania GPW i NewConnect oraz indeksy WIG i NCIndex z ostatnich 165 dni.
4. Przelicza od nowa wyniki alertów z ostatnich 120 dni: reakcję rynku i zmianę kursu po 5, 20 i 60 sesjach. Dzięki temu backfill naprawia też dni, w których pobranie notowań się nie udało.

Krok 2 zużywa limit AI (pula GPW/NC, 1,90 USD miesięcznie). Gdy pula się wyczerpie albo Anthropic odrzuci klucz API, krok 2 się zatrzymuje, także dla spółek, które jeszcze czekały. Kroki 3 i 4 wykonują się mimo to. Całość trwa od kilku do kilkudziesięciu minut.

```
curl -i -X POST -H "X-Investor-Token: $T" https://debian-server.tail8124e8.ts.net/investor/api/admin/backfill
```

Odpowiedź 202 oznacza, że ruszył. Odpowiedź 409 oznacza, że już trwa. Odpowiedź 401 oznacza zły token (rozdział 6). Odpowiedź nie ma treści, dlatego bez `-i` curl nic nie wypisuje.

Postęp śledź w logach: `docker compose logs -f server | grep -iE 'backfill|outcomes'`. Kolejne linie wyglądają tak:

```
Backfill started
Monthly sales backfill scan at 2025-10-01, 0 reports found so far        (co 30 przejrzanych dni)
Monthly sales backfill scan finished, 66 reports of 5 companies found
Monthly sales backfill of ASBIS: 13 of 13 reports to read                (dla każdej spółki)
Monthly sales backfill finished, 60 monthly revenues saved
Quotes backfill of 118 weekdays from 2026-05-20 to 2026-11-01 started
Quotes backfill finished, latest session 2026-10-30
Outcomes refreshed for 12 reports                                        (tylko gdy są już alerty)
Backfill finished in 40 min
```

Ostatnia linia oznacza koniec. Linie z `ERROR` lub `WARN` pokazują dni albo raporty, których nie udało się odczytać. Pojedyncze takie linie nie są problemem.

Backfill nie dodaje niczego do zakładki Alerty. Zapisuje tylko historię, z którą serwer porównuje nowe raporty. Efekt widać w ⚙ → Stan źródeł: pole „Notowania (ostatnia sesja)” pokazuje datę ostatniej sesji.

**Po aktualizacji z fazy 1 do fazy 2** uruchom backfill jeszcze raz. Faza 1 pobierała notowania tylko z 60 dni, a wyniki alertów potrzebują ich ze 165 dni. Raporty sprzedaży miesięcznej zapisane wcześniej są pomijane, więc koszt AI jest minimalny.

**Po aktualizacji z 1.11.2026** też uruchom backfill jeszcze raz. Serwer rozpoznaje teraz więcej tytułów raportów ze sprzedażą miesięczną, np. „Przychody ze sprzedaży za wrzesień” albo „Szacunkowe przychody w lipcu”, więc znajdzie więcej spółek.

## 8. Budowa APK w Android Studio

Robisz to na drugim komputerze.

1. Upewnij się, że `android/app/google-services.json` jest na miejscu (krok 3).
2. Android Studio → Open → katalog `android/`.
3. Poczekaj na synchronizację Gradle. Studio może zaproponować aktualizację wersji, możesz się zgodzić.
4. W terminalu (PowerShell) zbuduj APK. Komenda najpierw uruchamia testy apki, a potem buduje plik:
   ```
   cd C:\projekty\investor\android
   .\gradlew.bat testDebugUnitTest assembleDebug
   ```
5. APK: `C:\projekty\investor\android\app\build\outputs\apk\debug\app-debug.apk`.

## 9. Instalacja APK

- Przez USB: włącz debugowanie USB w telefonie i wykonaj `adb install -r app-debug.apk`.
- Przez plik: prześlij APK na telefon, otwórz go i zezwól na instalację z nieznanych źródeł.
- Przy pierwszym uruchomieniu zezwól na powiadomienia.

## 10. Ekran konfiguracji w apce

1. Adres serwera: `https://debian-server.tail8124e8.ts.net/investor`, z `/investor` na końcu i bez `:8080`.
2. Token: ten sam, co `INVESTOR_API_TOKEN`.
3. Tailscale ani VPN na telefonie nie są potrzebne. Aplikację Tailscale możesz odinstalować.
4. Sprawdź, że `GET /api/status` zwraca dane przez `curl` jak wyżej.

Dopóki telefon się nie zarejestruje, alerty czekają na serwerze (do 24 godzin) i przychodzą po rejestracji.

## 11. Ustawienia w apce (⚙)

- **Próg alertu:** liczba od 0 do 100, domyślnie 70. Push przychodzi, gdy dokładna ocena jest równa progowi albo wyższa. Apka pokazuje ocenę zaokrągloną, więc raport z oceną 69,6 widać jako 70, ale pushu nie ma.
- **Rynki i typy zdarzeń:** wyłączony rynek albo typ nadal jest analizowany i widać go w zakładce Alerty, tylko push nie przychodzi. Raporty nadal zużywają limit AI.
- **Ciche godziny:** domyślnie wyłączone. W tych godzinach (czas warszawski, np. 22:00–07:00) push przychodzi bez dźwięku. Dotyczy wszystkich pushy, także o awarii źródła.
- **Stan źródeł** i **AI w tym miesiącu** (wydane / limit w USD) są tylko do odczytu.

## 12. Ranking, Skuteczność i dane po raporcie

- **Ranking:** spółki ocenione w ostatnich 30 dniach, od najwyższej oceny. Każda spółka występuje raz z najnowszym raportem wyników i raz z najnowszym wydarzeniem (od 50 pkt). Filtr rynku: GPW, NC, USA.
- **Reakcja rynku** (w szczegółach raportu): zmiana kursu na pierwszej sesji zamkniętej po publikacji, obok zmiana indeksu (WIG albo NCIndex) i wolumen (liczba akcji) względem średniej z 20 sesji. Pojawia się po wieczornym przeliczeniu w dniu tej sesji.
- **Skuteczność:** dla ocen 50–59, 60–69, 70–79 i 80+ średni zwrot ponad indeks po 5, 20 i 60 sesjach. Zwrot liczony jest od zamknięcia pierwszej sesji po raporcie, czyli od ceny, po której dało się kupić po alercie. Osobno wyniki według reakcji rynku w dniu raportu (spadek, płasko, wzrost).
- Wyniki przelicza serwer w dni robocze o 22:00, po pobraniu notowań. Wynik po 60 sesjach pojawia się po około 3 miesiącach, więc przez pierwsze tygodnie zakładka będzie prawie pusta.
- **Konsensus PAP** (w szczegółach raportu): dla większych spółek z GPW PAP publikuje kilka godzin po raporcie notkę „wyniki vs konsensus”. Serwer szuka jej na Bankierze co godzinę (o :15, w godz. 6–16 i 19–23, z przerwą na szczyt publikacji) przez 36 godzin po raporcie. Konsensus jest tylko informacją: nie zmienia oceny i nie wysyła pushu. Małe spółki zwykle go nie mają.

## 12a. Wydarzenia

- **Co to jest:** drugi typ alertu obok wyników. Raport bieżący z GPW albo NewConnect (po polsku albo po angielsku), który nie dotyczy wyników: umowa, przejęcie, wezwanie, skup akcji, dywidenda, notowanie na innej giełdzie, prognoza, emisja akcji, finansowanie, sąd, zmiany w zarządzie. Wydarzeniem jest też raport bieżący z wynikami, który nie podaje przychodów (np. tylko zysk albo wstępne wyniki bez sprzedaży) albo podaje dane niepieniężne (np. produkcja w tonach). Takiego raportu nie da się ocenić jak wyników, więc AI ocenia go jak wydarzenie.
- **Filtr rutyny:** serwer najpierw odrzuca po samym tytule komunikaty, które prawie nigdy nie ruszają kursem: transakcje osób z zarządu (art. 19 MAR), zawiadomienia o stanie posiadania (art. 69), walne zgromadzenia, terminy raportów, statut, rada nadzorcza, audytor, raporty miesięczne NewConnect, raporty z bieżącego skupu akcji i podobne. Takie komunikaty nie trafiają do AI. Na zapisanych listach z GPW i NC odpada około połowy tytułów.
- **Ocena AI:** Claude czyta tytuł, początek treści i przychody spółki z ostatnich 12 miesięcy (z BiznesRadaru). Zwraca kategorię, kierunek (pozytywny, negatywny, neutralny), siłę wpływu 0–100 i jedno zdanie uzasadnienia. Ocena wydarzenia to siła wpływu, gdy kierunek jest pozytywny, a w innym razie 0. Umowa bez wartości potwierdzonej w treści ma najwyżej 60.
- **Push:** tylko dla wydarzenia pozytywnego, gdy ocena jest równa progowi alertu albo wyższa (ten sam próg co dla wyników, domyślnie 70). Przy progu niższym niż 50 push o wydarzeniu przychodzi dopiero od 50 pkt. Tytuł pushu: `Spółka (GPW) · WYDARZENIE · ocena 78`.
- **Lista:** w Alertach i Rankingu widać tylko wydarzenia z oceną od 50 (etykieta „Wydarzenie”). Wydarzenia negatywne, neutralne i słabsze są zapisane, ale niewidoczne.
- **Przełącznik:** w ustawieniach (⚙) typ zdarzenia „Wydarzenie” można wyłączyć osobno. Wtedy wydarzenia nadal są oceniane i widać je w Alertach, tylko push nie przychodzi.
- **Koszt:** osobna pula AI `NEWS` ma 1,00 USD miesięcznie (około 4 zł). Pochodzi z puli USA, która ma teraz 1,85 USD. Pula GPW zostaje 1,90 USD, razem nadal 4,75 USD. Gdy pula `NEWS` się wyczerpie, wydarzenia do końca miesiąca nie są oceniane, a apka dostaje jeden push „Do końca miesiąca wydarzenia nie są oceniane”. Ocena wyników nigdy nie korzysta z puli `NEWS`.
- **Skuteczność:** wydarzenia mają w zakładce Skuteczność własne przedziały ocen, osobno od wyników.
- **Aktualizacja:** apkę i serwer zaktualizuj razem. Starsza apka nie zna typu „Wydarzenie” i nie odczyta odpowiedzi serwera.

## 13. Deploy na mini-PC

Serwer działa na mini-PC (w sieci domowej `minipc`, w Tailscale `debian-server`) z katalogu `/home/grzegorz/investor`. Repozytorium trzymasz na PC w `C:\projekty\investor`. Nową wersję pobierasz z GitHuba na PC, kopiujesz na mini-PC i tam przebudowujesz kontener.

### Połączenie przez SSH

Na PC otwórz PowerShell (`ssh` i `scp` są wbudowane w Windows 10 i 11):

```
ssh grzegorz@minipc
```

- Jeśli nazwa `minipc` nie działa, użyj adresu IP mini-PC w sieci domowej. Sprawdzisz go na mini-PC komendą `hostname -I` (pierwszy adres na liście).
- Publiczny adres `https://debian-server.tail8124e8.ts.net/investor` wystawia tylko serwer apki. SSH przez niego nie działa.
- Gdy połączenie jest odrzucane, zainstaluj na mini-PC serwer SSH: `sudo apt install openssh-server`.
- Żeby nie wpisywać hasła przy każdym logowaniu, utwórz klucz na PC i skopiuj go na mini-PC:
  ```
  ssh-keygen -t ed25519
  type $env:USERPROFILE\.ssh\id_ed25519.pub | ssh grzegorz@minipc "mkdir -p ~/.ssh && cat >> ~/.ssh/authorized_keys"
  ```
  Przy `ssh-keygen` wciśnij Enter na wszystkie pytania. Hasło do mini-PC wpiszesz ostatni raz przy kopiowaniu klucza.

### Aktualizacja do nowej wersji

Mini-PC potrzebuje z repozytorium tylko pliku `docker-compose.yml` i katalogu `server`. Pliki `.env` i `secrets/firebase.json` są tylko na mini-PC i poniższe komendy ich nie ruszają. Baza też zostaje, bo leży w wolumenie Dockera.

1. Na PC, w PowerShell, pobierz nową wersję:
   ```
   cd C:\projekty\investor
   git pull
   ```
2. Usuń stary kod serwera na mini-PC. Dzięki temu nie zostaną tam pliki skasowane w nowej wersji:
   ```
   ssh grzegorz@minipc "rm -rf /home/grzegorz/investor/server"
   ```
3. Skopiuj nowy kod. Uruchom komendy na PC w `C:\projekty\investor`, a nie w `C:\projekty\investor\server`: komenda `dir` musi tam pokazać katalog `server` i plik `docker-compose.yml`.
   ```
   scp -r server grzegorz@minipc:/home/grzegorz/investor/
   scp docker-compose.yml grzegorz@minipc:/home/grzegorz/investor/
   ```
   Pierwsza komenda kopiuje cały katalog `server` z podkatalogami (`-r`), druga sam plik. Sprawdź wynik: `ssh grzegorz@minipc "ls /home/grzegorz/investor/server"` powinno pokazać m.in. `Dockerfile` i `src`.
   Jeśli na PC jest katalog `server\build` (powstaje po uruchomieniu testów), możesz go najpierw usunąć, żeby kopiowanie trwało krócej. Przy budowie obrazu i tak jest pomijany.
4. Zaloguj się na mini-PC i zbuduj nową wersję:
   ```
   ssh grzegorz@minipc
   cd /home/grzegorz/investor
   docker compose up -d --build
   docker compose logs -f server
   ```
   `docker compose up -d --build` buduje nowy obraz (kilka minut) i podmienia kontener serwera. Zmiany w bazie (migracje) wykonują się same przy starcie.
5. W logach poczekaj na linię `Started InvestorApplication`. Ctrl+C zamyka podgląd logów, serwer działa dalej.
6. Sprawdź API:
   ```
   T=$(docker compose exec -T server printenv INVESTOR_API_TOKEN)
   curl -i -H "X-Investor-Token: $T" http://127.0.0.1:8080/api/status
   ```
   Oczekiwane: `HTTP/1.1 200`.
7. Jeśli w rozdziale 7 jest akapit „Po aktualizacji z …” dla tej wersji, uruchom backfill.
8. Jeśli nowa wersja zmienia też apkę (np. dochodzi nowy typ alertu), zbuduj nowy APK (rozdział 8) i zainstaluj go na telefonie (rozdział 9). Apkę i serwer aktualizuj wtedy razem.
9. Raz na jakiś czas usuń stare obrazy, które zajmują miejsce na dysku: `docker image prune -f`.

Przy pierwszej instalacji przed `docker compose up` wykonaj jeszcze rozdziały 3 (`secrets/firebase.json`) i 5 (`.env`), a po starcie rozdział 7 (backfill).

- Katalog musi nazywać się `investor`, bo od tej nazwy zależy wolumen z bazą (`investor_pgdata`). W katalogu o innej nazwie serwer wystartuje z pustą bazą.
- Nigdy nie używaj `docker compose down -v`, bo ta komenda usuwa bazę. Samo `docker compose down` zatrzymuje kontenery i bazę zostawia.

## 14. Sprawdzanie, czy wszystko działa

Komendy wykonuj na mini-PC w `/home/grzegorz/investor`.

**Połączenie apki z serwerem.** W apce ⚙ → Stan źródeł pokazuje dane z serwera. Jeśli je widać, adres i token są dobre.

**Czy telefon jest zarejestrowany do powiadomień:**

```
docker compose exec db psql -U investor -d investor -c "SELECT count(*) FROM device;"
```

- Wynik 1 oznacza, że telefon jest zapisany i push dotrze.
- Wynik 0 oznacza, że powiadomienia nie przyjdą. Apka rejestruje telefon przy każdym uruchomieniu, więc otwórz ją i sprawdź jeszcze raz. Powiadomienia muszą też być dozwolone w ustawieniach telefonu.

**Raporty z ostatniej doby według statusu:**

```
docker compose exec db psql -U investor -d investor -c "SELECT event_type, status, count(*) FROM report WHERE detected_at > now() - interval '1 day' GROUP BY event_type, status ORDER BY 1, 2;"
```

Kolumna `event_type` to `RESULTS` (wyniki) albo `NEWS` (wydarzenie). Tytuły po angielsku też są rozpoznawane jako wyniki (np. „Interim report”, „Preliminary sales revenue”).

| Status | Znaczenie |
|---|---|
| `IRRELEVANT` | komunikat bez znaczenia dla kursu: rutynowy komunikat odrzucony po tytule (np. zwołanie walnego zgromadzenia, transakcje osób z zarządu, art. 69) albo raport, który AI uznało za nieistotny; serwer go pomija; tych jest najwięcej |
| `CORRECTION_SKIPPED` | korekta wcześniejszego raportu; pomijana |
| `SCORED` | raport oceniony; wyniki widać w zakładce Alerty zawsze, wydarzenie tylko z oceną od 50 (wydarzenia negatywne, neutralne i słabsze są zapisane, ale ukryte) |
| `PUSH_PENDING` | ocena gotowa, push czeka na wysłanie (np. telefon jeszcze niezarejestrowany) |
| `PENDING_ATTACHMENTS`, `PENDING_DATA`, `PENDING_AI` | serwer czeka na pliki raportu, dane albo AI i ponowi próbę; wyniki widać w Alertach jako „czeka”, wydarzenia są ukryte do oceny; na pliki serwer czeka tylko wtedy, gdy strona raportu ma załączniki |
| `UNSCORED_NO_AI`, `UNSCORED_NO_DATA` | raport bez oceny, bo zabrakło limitu AI albo danych; wyniki widać w Alertach bez oceny, wydarzenia bez oceny są ukryte |
| `NEW`, `EXTRACTED` | raport w trakcie obsługi |
| `FAILED` | raport nie dał się obsłużyć; szczegóły w `docker compose logs server` |

Pusta lista w zakładce Alerty jest normalna, dopóki nie przyjdą nowe raporty. Spółki publikują je zwykle rano przed sesją albo wieczorem po 17:00. Backfill niczego do tej listy nie dodaje.

**Ostatnie wysłane pushe:**

```
docker compose exec db psql -U investor -d investor -c "SELECT sent_at, title FROM push_log ORDER BY sent_at DESC LIMIT 10;"
```

Push przychodzi tylko przy ocenie równej progowi albo wyższej (domyślnie 70) i przy alertach o awarii źródła.

**Historia z backfillu według spółek:**

```
docker compose exec db psql -U investor -d investor -c "SELECT c.name, count(DISTINCT r.id) AS raporty, count(rp.report_id) AS miesiace FROM report r JOIN company c ON c.id = r.company_id LEFT JOIN revenue_period rp ON rp.report_id = r.id WHERE r.status = 'BACKFILLED' GROUP BY c.name ORDER BY 1;"
```

Kolumna `miesiace` to liczba miesięcy z zapisanymi przychodami. Wartość 0 oznacza, że raporty spółki nie podają przychodów w pieniądzach, np. podają tylko produkcję w tonach.

## Pomiary

Trzy ręczne sprawdziany po uruchomieniu. Wyniki wpisz poniżej.

**(a) Awaria źródła: push w ciągu 35 minut.**
Serwer zgłasza awarię, gdy od ostatniego udanego odczytu listy minęło 30 minut. Sprawdza to co 5 minut i wysyła push tylko wtedy, gdy w tej chwili trwa okno publikacji. Dlatego:
1. Zacznij co najmniej 40 minut przed końcem okna, czyli w jednym z przedziałów (czas warszawski): 00:00–00:20, 07:00–08:20 albo 16:30–23:20. Poza oknem awaria nie jest zgłaszana.
2. Telefon musi być już zarejestrowany w serwerze (apka skonfigurowana), a `/api/status` musi pokazywać `fcmConfigured: true`.
3. Przed blokadą poczekaj na co najmniej jeden udany odczyt listy GPW: `GPW_LISTING` w `/api/status` ma mieć `lastSuccessAt`. Bez niego awaria nie jest liczona.
4. Zablokuj GPW w kontenerze:
   `docker compose exec -u root server sh -c 'echo "127.0.0.1 www.gpw.pl" >> /etc/hosts'`
5. Czekaj. Oczekiwany push „Źródło GPW_LISTING nie działa” w ciągu 35 minut (30 minut od ostatniego sukcesu plus do 5 minut do następnego sprawdzenia).
6. Cofnij blokadę: `docker compose restart server` (kontener dostaje świeży `/etc/hosts`).
7. Wynik: `____` (czas od blokady do pushu).

**(b) Opóźnienie: mediana w 7 dni.**
1. Po 7 dniach pracy: `curl -H "X-Investor-Token: $T" https://debian-server.tail8124e8.ts.net/investor/api/status` (zmienna `T` jak w kroku 6).
2. `medianLatencySeconds7d` ma być ≤ 300 (5 minut). Dopóki nie ma żadnych wyników, wartość to `null`.
3. Wynik: `____`

**(c) Koszt AI.**
1. Odczytaj `aiSpentUsd` z `/api/status` (suma wszystkich pul za bieżący miesiąc; miesiąc liczony w UTC, tak jak w Console).
2. Porównaj z kosztem w Anthropic Console → Usage (workspace `investor`, ten sam miesiąc).
3. Różnica ma być ≤ 10%.
4. Wynik: `____`

## Znane ograniczenia

- Kopie raportów na Bankierze potrafią się spóźniać. Raporty okresowe są ponawiane do 2 godzin, potem serwer sięga po dane z BiznesRadaru (do 48 godzin).
- Źródła to scraping stron. Strona może zmienić układ i adapter przestanie działać. Pokaże to alert o awarii źródła.
- Apka łączy się z głównym adresem `https://debian-server.tail8124e8.ts.net`, bez `/investor`. Działa, bo Funnel wystawia serwer także pod głównym adresem. Jeśli przypiszesz główny adres innej usłudze, apka straci połączenie z serwerem i trzeba ją poprawić.
- Apkę i serwer aktualizuj razem. Gdy serwer dostanie nowy rynek albo typ zdarzenia (fazy 3 i 5), starsza apka nie odczyta odpowiedzi.
- Ocena wydarzeń przez AI nie jest jeszcze sprawdzona na prawdziwych reakcjach kursu. Po kilku tygodniach zajrzyj do Skuteczności (przedziały „Wydarzenie”) i dopiero wtedy ufaj pushom o wydarzeniach. Filtr rutyny działa po tytule, więc nietypowo nazwany ważny komunikat może zostać odrzucony, a rutynowy może trafić do AI.
- Polling sprawdza najpierw GPW, potem NewConnect. W wieczór z dużą liczbą raportów alerty z NewConnect mogą się spóźnić, bo czekają na obsłużenie raportów z GPW.
- Kalibracja (2026-10-31):
  - Kara za rajd kursu (−10 pkt) nie działa, gdy spółka ma za sobą co najmniej 3 mocne raporty z rzędu i tanią wycenę. Rajd jest wtedy pokazany jako ostrzeżenie. Lipcowa sprzedaż ASBIS ma 75,83 pkt i push.
  - Raport oznaczony przez AI jako jednorazowy kwartał (np. premiera gry) dostaje za niespodziankę najwyżej połowę punktów. Creepy Jar za I kw. 2026 ma 55 pkt i nie dostaje pushu.
  - Raport półroczny ASBIS nie zawiera zapowiedzi zarządu, więc ocena „brak zapowiedzi” jest poprawna.
  - Próg zostaje 70. Po około 3 miesiącach zakładka Skuteczność pokaże, czy oceny 60–69 też dają zysk; wtedy można obniżyć próg.
