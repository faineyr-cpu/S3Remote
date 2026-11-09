# S3 Player – Android pultelis ESP32-S3 grotuvui

Programėlė ieško YouTube, gauna garso nuorodas (itag 140) ir siunčia jas į ESP32-S3.
ESP groja pats; telefonas reikalingas tik dainoms pridėti ir pasenusioms nuorodoms atnaujinti.
Serverio, Termux, Python **nereikia**.

## Įdiegimas
1. GitHub → **Actions** → paskutinis žalias „Build APK“ → apačioje **S3Player-apk** (zip) → išarchyvuoti.
2. Telefone atidaryti `app-release.apk` (leisti diegti iš šio šaltinio).
3. Programėlė → ⚙ → **Rasti ESP tinkle** (arba įvesk IP) → API raktas → Išsaugoti.

## Naudojimas
- Paieška arba įklijuota nuoroda (vaizdas, grojaraštis, Mix).
- Paspausti kūrinį = groti dabar; **+** = į eilę; **Groti visus** = pakeisti eilę rezultatais.
- Eilėje: paspausti = groti šį; **✕** = pašalinti.
- YouTube programėlėje **Bendrinti → S3 Player** = pridėti į eilę.
- Eilė saugoma ESP atmintyje. Nuorodos galioja ~6 val.; atidarius programėlę ji pati jas atnaujina.

## YouTube „Pasirinkti įrenginį“ (perdavimas)
1. Programėlė → ⋮ → įjungti **Rodyti YouTube „Pasirinkti įrenginį“ sąraše** → Išsaugoti (leisti pranešimus).
2. Juostoje matosi pranešimas „S3 Player – perdavimas įjungtas“.
3. YouTube → perdavimo ikona → **S3 Player**. Groja ESP; pauzė, kita, persukimas, garsas – iš YouTube.
- Veikia, kol paslauga įjungta (pranešimas matomas) ir telefonas namų WiFi.
- Neoficialus protokolas (pagal atvirą projektą *yt-cast-receiver*). Jei nustos veikti – žiūrėti, ar tas projektas atnaujintas, ir perkelti pakeitimus į `Cast.kt`.
- Sąraše nesimato: perkrauti YouTube programėlę; kai kurie maršrutizatoriai blokuoja multicast (SSDP, prievadas 1900).

## Kai YouTube „sulūžta“ (paieška/grojimas nebeveikia)
1. https://github.com/TeamNewPipe/NewPipeExtractor/releases – pažiūrėti naujausią versiją (pvz. `v0.27.1`).
2. GitHub svetainėje atidaryti `app/build.gradle.kts` → pieštukas → pakeisti eilutę
   `val newPipeExtractorVersion = "v0.27.0"` → **Commit**.
3. Palaukti, kol Actions sukompiliuos (~5 min), parsisiųsti naują APK, įdiegti ant viršaus.

## Jei neveikia
| Požymis | Priežastis / sprendimas |
|---|---|
| „ESP nepasiekiamas“ | telefonas ne tame pačiame WiFi; patikrink IP (⚙) |
| „Neteisingas API raktas“ | ⚙ → įvesk tą patį raktą kaip ESP |
| Kūrinys praleidžiamas | nuoroda pasenusi – atidaryk programėlę; arba vaizdas privatus |
| Paieškos klaida | atnaujink NewPipeExtractor (žr. aukščiau) |

## ESP API (S3Player)
`GET /app/state`, `POST /app/add`, `POST /app/urls`, `GET /app/cmd?c=next|stop|clear|seek|vol|play|del|pause|resume&v=`
Raktas – antraštė `X-Key`. mDNS paslauga `_s3player._tcp`.
