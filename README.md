# AutoEnchantingSkill (Fabric 1.21.11, client-side)

## Build
Butuh JDK 21. Cara paling mudah (supaya ada Gradle wrapper):
1. Buka https://fabricmc.net/develop/template -> pilih Minecraft 1.21.11, Yarn mappings -> download.
2. Salin folder `src/` dari proyek ini ke template tadi (timpa), dan samakan `gradle.properties` dengan versi di situ.
3. `./gradlew build` -> hasil: `build/libs/autoenchantingskill-1.0.0.jar` -> taruh di folder `mods` bersama Fabric API.

## Pemakaian
1. Berdiri sehingga meja enchant DAN grindstone sama-sama dalam jangkauan tangan (maks ~4.5 blok). Taruh bookshelf di sekitar meja.
2. Siapkan di inventory: item target (mis. banyak pickaxe) dan lapis lazuli. Punya level XP.
3. Tekan **K** (atau tombol "AES Setting" di layar meja enchant). Pilih item (klik ikon), atur enchant + level, tier, siklus.
4. Klik **MULAI OTOMATIS** (atau tombol J). Tekan J lagi untuk berhenti.

## Logika
- Tier 1/2/3 = slot meja enchant atas/tengah/bawah. SMART = pilih slot terbaik yang terjangkau XP + lapis, utamakan slot yang clue-nya cocok target.
- Hasil cocok -> item disimpan, lanjut item berikutnya. Tidak cocok -> tutup meja -> grindstone -> enchant lagi.
- Siklus = jumlah percobaan enchant (atau tak terbatas). "Sukses" = berhenti setelah N item sesuai (All = sampai item habis).
- Lapis habis -> bunyi alarm + toast + pesan chat, proses berhenti rapi.
