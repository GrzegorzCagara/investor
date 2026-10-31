# Uruchomienie na mini-PC

Instrukcja krok po kroku. Serwer działa na mini-PC w Dockerze i jest wystawiony publicznie przez Tailscale Funnel pod adresem HTTPS. Telefon nie potrzebuje Tailscale ani VPN.

## 1. Docker i Docker Compose na mini-PC

1. Zainstaluj Docker Engine razem z wtyczką Compose: https://docs.docker.com/engine/install/
2. Sprawdź: `docker --version` i `docker compose version`.
3. Na Linuxie dodaj siebie do grupy `docker` (`sudo usermod -aG docker $USER`) i zaloguj się ponownie.
4. Skopiuj katalog `investor/` na mini-PC. Dalsze komendy wykonuj w jego głównym katalogu.

## 2. Tailscale Funnel na mini-PC

Tailscale jest potrzebny tylko na mini-PC. Telefon nie potrzebuje aplikacji Tailscale ani włączonego VPN.

1. Zainstaluj Tailscale na mini-PC (https://tailscale.com/download) i zaloguj się: `sudo tailscale up`.
2. Włącz HTTPS i Funnel dla swojego tailnetu. Najprościej uruchom Funnel i otwórz link, który wypisze:
   ```
   sudo tailscale funnel --bg 8080
   ```
   Przy pierwszym razie Tailscale poprosi w panelu o włączenie certyfikatów HTTPS i Funnel. Funnel działa też w planie darmowym.
3. Ustaw nazwę maszyny na `minipc` (https://login.tailscale.com/admin/machines), a nazwę tailnetu na `investor` (https://login.tailscale.com/admin/dns → Tailnet name). Jeśli `investor` jest już zajęta, Tailscale doda losowy sufiks i adres będzie inny.
4. Publiczny adres serwera to `https://minipc.investor.ts.net` — bez `:8080`. Funnel sam łączy się z `127.0.0.1:8080`, dlatego `HOST_BIND` zostaje na `127.0.0.1`.
5. Sprawdź stan: `tailscale funnel status`. Wyłączenie: `sudo tailscale funnel reset`.
6. Flaga `--bg` sprawia, że Funnel działa po restarcie mini-PC, o ile Tailscale też startuje z systemem. Port 8080 nie jest wystawiony w sieci domowej.

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
curl -H "X-Investor-Token: $T" https://minipc.investor.ts.net/api/status
```

Możesz też sprawdzić lokalnie z mini-PC: `curl http://127.0.0.1:8080/api/status` z tym samym nagłówkiem. Publiczny ruch idzie przez HTTPS, więc token jest szyfrowany.

Oczekiwane: kod 200, lista źródeł z `lastSuccessAt` z ostatnich 2 minut (w godzinach publikacji) i `fcmConfigured: true`. `GET /actuator/health` działa bez tokenu.

## 7. Backfill historii

Bez historii oceny nie mają danych do porównań. Backfill robi cztery rzeczy, w tle:
1. Przegląda listy komunikatów GPW i NewConnect z ostatnich 13 miesięcy, dzień po dniu, aż do wczoraj, i zbiera z nich raporty ze sprzedażą miesięczną. Dzisiejsze raporty obsługuje bieżący polling.
2. Grupuje te raporty według spółki (po ISIN) i wyciąga z nich przychody przez Claude. Raporty z NewConnect są zapisywane jako NC.
3. Pobiera notowania GPW i NewConnect oraz indeksy WIG i NCIndex z ostatnich 165 dni.
4. Przelicza od nowa wyniki alertów z ostatnich 120 dni: reakcję rynku i zmianę kursu po 5, 20 i 60 sesjach. Dzięki temu backfill naprawia też dni, w których pobranie notowań się nie udało.

Krok 2 zużywa limit AI (pula GPW/NC, 1,90 USD miesięcznie). Gdy pula się wyczerpie albo Anthropic odrzuci klucz API, krok 2 się zatrzymuje, także dla spółek, które jeszcze czekały. Kroki 3 i 4 wykonują się mimo to. Czas trwania nie był mierzony: od kilku do kilkudziesięciu minut. Postęp widać w logach: `docker compose logs -f server`.

```
curl -X POST -H "X-Investor-Token: $T" https://minipc.investor.ts.net/api/admin/backfill
```

Odpowiedź 202 oznacza, że ruszył. Odpowiedź 409 oznacza, że już trwa.

**Po aktualizacji z fazy 1 do fazy 2** uruchom backfill jeszcze raz. Faza 1 pobierała notowania tylko z 60 dni, a wyniki alertów potrzebują ich ze 165 dni. Raporty sprzedaży miesięcznej zapisane wcześniej są pomijane, więc koszt AI jest minimalny.

## 8. Budowa APK w Android Studio

Robisz to na drugim komputerze.

1. Upewnij się, że `android/app/google-services.json` jest na miejscu (krok 3).
2. Android Studio → Open → katalog `android/`.
3. Poczekaj na synchronizację Gradle. Studio może zaproponować aktualizację wersji, możesz się zgodzić.
4. W terminalu w `android/`: `./gradlew testDebugUnitTest assembleDebug`.
5. APK: `android/app/build/outputs/apk/debug/app-debug.apk`.

## 9. Instalacja APK

- Przez USB: włącz debugowanie USB w telefonie i wykonaj `adb install -r app-debug.apk`.
- Przez plik: prześlij APK na telefon, otwórz go i zezwól na instalację z nieznanych źródeł.
- Przy pierwszym uruchomieniu zezwól na powiadomienia.

## 10. Ekran konfiguracji w apce

1. Adres serwera: `https://minipc.investor.ts.net`, bez `:8080` i bez ścieżki.
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

- **Ranking:** spółki ocenione w ostatnich 30 dniach, od najwyższej oceny. Każda spółka występuje raz, z najnowszym raportem. Filtr rynku: GPW, NC, USA.
- **Reakcja rynku** (w szczegółach raportu): zmiana kursu na pierwszej sesji zamkniętej po publikacji, obok zmiana indeksu (WIG albo NCIndex) i wolumen (liczba akcji) względem średniej z 20 sesji. Pojawia się po wieczornym przeliczeniu w dniu tej sesji.
- **Skuteczność:** dla ocen 50–59, 60–69, 70–79 i 80+ średni zwrot ponad indeks po 5, 20 i 60 sesjach. Zwrot liczony jest od zamknięcia pierwszej sesji po raporcie, czyli od ceny, po której dało się kupić po alercie. Osobno wyniki według reakcji rynku w dniu raportu (spadek, płasko, wzrost).
- Wyniki przelicza serwer w dni robocze o 22:00, po pobraniu notowań. Wynik po 60 sesjach pojawia się po około 3 miesiącach, więc przez pierwsze tygodnie zakładka będzie prawie pusta.
- **Konsensus PAP** (w szczegółach raportu): dla większych spółek z GPW PAP publikuje kilka godzin po raporcie notkę „wyniki vs konsensus”. Serwer szuka jej na Bankierze co godzinę (o :15, w godz. 6–16 i 19–23, z przerwą na szczyt publikacji) przez 36 godzin po raporcie. Konsensus jest tylko informacją: nie zmienia oceny i nie wysyła pushu. Małe spółki zwykle go nie mają.

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
1. Po 7 dniach pracy: `curl -H "X-Investor-Token: $T" https://minipc.investor.ts.net/api/status` (zmienna `T` jak w kroku 6).
2. `medianLatencySeconds7d` ma być ≤ 300 (5 minut). Dopóki nie ma żadnych wyników, wartość to `null`.
3. Wynik: `____`

**(c) Koszt AI.**
1. Odczytaj `aiSpentUsd` z `/api/status` (suma obu pul za bieżący miesiąc; miesiąc liczony w UTC, tak jak w Console).
2. Porównaj z kosztem w Anthropic Console → Usage (workspace `investor`, ten sam miesiąc).
3. Różnica ma być ≤ 10%.
4. Wynik: `____`

## Znane ograniczenia

- Kopie raportów na Bankierze potrafią się spóźniać. Raporty okresowe są ponawiane do 2 godzin, potem serwer sięga po dane z BiznesRadaru (do 48 godzin).
- Źródła to scraping stron. Strona może zmienić układ i adapter przestanie działać. Pokaże to alert o awarii źródła.
- Apkę i serwer aktualizuj razem. Gdy serwer dostanie nowy rynek albo typ zdarzenia (fazy 3 i 5), starsza apka nie odczyta odpowiedzi.
- Polling sprawdza najpierw GPW, potem NewConnect. W wieczór z dużą liczbą raportów alerty z NewConnect mogą się spóźnić, bo czekają na obsłużenie raportów z GPW.
- Dwa pytania kalibracyjne czekają na decyzję. Pierwsze: czy zapowiedź zarządu w raporcie półrocznym ASBIS liczyć jako pozytywną. Drugie: czy kara za rajd kursu ma blokować alert o sprzedaży za lipiec (65,83 pkt zamiast ok. 76).
