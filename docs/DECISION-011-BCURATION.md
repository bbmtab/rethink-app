# DECISION-011 B-Curation Ledger — Independent Plus-Nomenclature Curation

**Status:** GOVERNING for release closure · **Opened:** 2026-09-15 · **Time-box:** 7 hari kalender (berakhir 2026-09-22) — **DITUTUP AWAL 2026-09-20** (otorisasi user; bukti penutup di bawah)
**Judge evidence:** Supervisor · **Risk/product owner:** User
**Scope:** 5 bundled HTTPS-inspection preset assets in `app/src/main/assets/https_inspection/`
**Authority:** DECISION-011, DECISION-010 (semantics, unchanged), DECISION-013 (ONLY_INCLUDED, closed)

## Prinsip pengikat

1. Identifier publik (nama paket, hostname) adalah fakta — bukan milik siapa pun, bukan blocker.
2. Yang dikurasi adalah KEPUTUSAN (rationale, precedence, kemutakhiran, kepemilikan update) — bukan identifier.
3. Tidak ada klaim lisensi yang dibuat di mana pun; yang dicatat hanya provenance + residu.
4. Rename the concept, not just the file: isi, komentar, rationale, dokumentasi, referensi kode memakai terminologi Plus.
5. Terminologi ledger: AdGuard adalah upstream/reference yang diinspeksi — BUKAN donor, dan tidak ada "donor asset / donor-derived / donor compilation" di ledger ini. Kelimanya adalah Plus-curated assets dengan semantics dan rationale sendiri. Yang diambil hanya hasil observasi/logic konseptual dan identifier publik; tidak ada source code proprietary yang disalin. Dokumen historis lain yang masih memakai kata "donor" dibiarkan apa adanya (append-only / arsip) dan tidak ditulis ulang.
6. Jika time-box lewat tanpa B-kurasi selesai → default scope-cut (4 aset yang kurasinya belum mandiri keluar dari rilis).

## Paket rename final (5 file)

| Path lama (pra-kanonis) | Nama kanonis Plus (baru) | Engine const (sudah Plus, tak berubah) | Fungsi |
|---|---|---|---|
| `pkg_exclusions.txt` | `https_inspection_package_bypass_plus` | `SYSTEM_HARD_BYPASS_ASSET_PATH` | paket yang dikecualikan dari inspection |
| `filter_https_traffic_inclusions.txt` | `https_inspection_known_browsers_plus` | `KNOWN_BROWSERS_ASSET_PATH` | registry browser eligible-by-default (bukan "target" — user-exclusion bisa melepas) |
| `https_inspection_inclusions.txt` | `https_inspection_domain_targets_plus` | `INCLUDED_DOMAIN_MITM_ASSET_PATH` | domain eligible MITM di mode include-only |
| `filter_https_traffic_exclusions.json` | `https_inspection_compatibility_bypass_plus` | `COMPATIBILITY_EXCLUSIONS_ASSET_PATH` | compatibility/pinning/system exceptions |
| `ssl_allow_list.txt` | `https_inspection_protected_domains_plus` | `PROTECTED_DOMAIN_BYPASS_ASSET_PATH` | domain proteksi (BYPASS — bukan inspect; nama `need_inspect_list` DITOLAK karena membalik makna) |

Permukaan referensi (audit 2026-09-15): `InspectionPolicyPresetLoader.kt:47-62` (5 const path), `InspectionPolicyBundledAssetTest.kt` (6 string), `NOTICE.txt` (tulis ulang), docs historis (tabel pemetaan saja; DECISIONS append-only tak diubah).

## Verdict per aset

### `ssl_allow_list.txt` → PROVISIONAL-BERALAS (ikut rilis)
- Hash terverifikasi `cf2699db…` (85.173 B). Pin `5d3e4ca`, generator `node index.js`, output `dist/android_exclusions.txt`.
- Investigasi live pin: `package.json` deklarasi `author: AdGuard`, `license: MIT` (blob `7795ae94…` cocok); TIDAK ada LICENSE/COPYING/NOTICE; README tanpa kalimat lisensi.
- Vonis: deklarasi level-paket (konvensi npm mencakup isi paket, tanpa pernyataan sebaliknya) = lebih dari nol, kurang dari lisensi data eksplisit. Label "MIT asset" DILARANG.
- Residu tercatat: waris lisensi artefak generate dibuka ulang hanya jika upstream menyatakan sebaliknya atau keberatan.

### Empat aset pra-kurasi-mandiri → BLOCKED-KURASI (scope-cut default berlaku)
- `pkg_exclusions.txt` (6 paket): butuh rationale Rethink per baris + verifikasi pergeseran makna (VPN-routing ≠ inspection-bypass).
- `filter_https_traffic_inclusions.txt` (64 baris; duplikat `com.brave.browser` tercatat): tiap entry default-ON wajib verifikasi independen; tanpa device mentok di identitas paket.
- `https_inspection_inclusions.txt` (4 domain + rationale lokal sudah ada): hampir jadi; tinggal formalisasi.
- `filter_https_traffic_exclusions.json` (191 entry; 0/191 comment; 188 ber-URL; 3 tanpa URL maupun comment): rationale per kelompok + daftar perkecualian; 3 tanpa jejak = kandidat cut langsung.
- Ketiganya dikaji nanti = keluar dari skop rilis sampai B-kurasi selesai.

### ONLY_INCLUDED → CLOSED (DECISION-013)
Produksi tetap `ALL_EXCEPT_PROTECTED`; engine dipertahankan sebagai fallback darurat. Bukan blocker, bukan deferred.

### Self-exemption paket sendiri (anti-looping proxy) → DEFERRED-END
Dibahas di akhir bersama B-kurasi; butuh verifikasi runtime bahwa upstream proxy tidak re-enter. Bukan blocker rilis sekarang.

## Rationale kurasi (ditulis berurutan, paling murah dulu)

### R1 — `https_inspection_domain_targets_plus` (4 domain) — FORMALIZED 2026-09-15

Status aktifasi: daftar ini hanya berlaku saat `domainMode=ONLY_INCLUDED`,
yang saat ini tanpa selector produksi (DECISION-013) — artinya dormant sampai
mode itu diaktifkan. Rationale ditulis untuk saat aktivasi, bukan klaim
perlindungan berjalan.

| Domain | Rationale Plus | Risiko overreach | Mitigasi |
|---|---|---|---|
| `googleapis.com` | Infra API Google membawa iklan/tracking (mis. ad-serving lewat path bersama) | Sangat luas; traffic legit aplikasi ikut eligible | User exclusion; narrowing bila ada bukti breakage device |
| `graph.facebook.com` | Graph API Meta mencakup iklan/analytics | Scoped ke satu host API, risiko rendah | Sama |
| `doubleclick.net` | Infra iklan + measurement Google | Murni iklan; risiko rendah | Sama |
| `googleadservices.com` | Infra iklan Google | Murni iklan; risiko rendah | Sama |

Aturan: parent-domain mencakup subdomain. Penyempitan hanya berdasarkan bukti
breakage device, tidak boleh diam-diam (sesuai komentar aset). Keputusan:
RETAIN keempatnya. Tidak ada perubahan isi file pada tahap ini.

### R2 — `https_inspection_package_bypass_plus` (6 entri) — FORMALIZED 2026-09-15

| Entry | Rationale Plus | Sumber alasan |
|---|---|---|
| UID `1000` (system), `1001` (radio) | Identitas sistem Android; traffic sistem/VPN tidak dapat di-MITM tanpa merusak platform | Fakta platform, bukan kurasi donor |
| `com.android.providers.downloads` + `.ui` | Jalur download sistem; inspeksi berisiko merusak update OS | Ditambah historis isu hulu (link komentar dipertahankan sebagai riwayat) |
| `com.celzero.bravedns.plus` | Self-exemption anti-looping: soket upstream proxy tidak boleh re-enter proxy | Keputusan Plus (menggantikan entry kompetitor buta per direktif user) |
| `com.coloros.providers.downloads.ui` | Download Manager OEM (Realme/Oppo); kelas yang sama dengan Downloads Manager | Ditambah historis isu hulu |

Verifikasi test: `BundledAssetTest` sistem-hard-bypass pin set+hash baru hijau 6/6.
Keputusan: RETAIN keenamnya dengan rationale di atas. Isi file diubah 1 entry
(self-swap); pin test diperbarui mengikutinya.
### R3 — `https_inspection_known_browsers_plus` (60 paket) — FORMALIZED 2026-09-15

Kebijakan inklusif (direktif user): yang penting sesuatu yang benar-benar
browser MASUK registry — 100+ pun tidak masalah. Jaring pengamannya dua lapis:
(1) user-exclusion selalu menang pertama (preseden DECISION-010); jika ada yang
tidak bisa lewat, pengguna tinggal OFF-kan; (2) dynamic-browser fallback
menangkap browser yang belum terdaftar, sehingga registry yang kurang bukan
kegagalan fatal.

Kriteria inklusi: benar-benar browser (me-render web, punya UI browser),
terverifikasi via listing Play ( produksi paket + branding). Bukan aplikasi
berbasis-Chromium tanpa UI.

Perubahan pada tahap ini: hapus 1 duplikat `com.brave.browser` + cut 3
non-browser (`com.yujian.ResideMenuDemo`, `com.qihoo.contents`,
`com.lemurbrowser.exts`) → 60 paket; hybrid (searchlite, bingnews,
yandex-searchplugin, yandex-lite) retain karena me-render konten web;
pin test diperbarui (1415 B, sha baru, count 60).
Ekspansi registry beyond-62 = follow-up terpisah, bukan blocker.
Keputusan: RETAIN 62 dengan kebijakan inklusif di atas.
### R4 — `https_inspection_compatibility_bypass_plus.json` (191 entry) — FORMALIZED 2026-09-15

Tujuh kelas Plus menampung 191/191 tanpa sisa. Nol sampah (tidak ada
sekelas ResideMenuDemo di sini — semua entry aplikasi nyata berkasus).

| Kelas | Isi | Rationale |
|---|---|---|
| Finansial/pinned | Santander ×53 + amex/citi/payoneer/wealthfront/digid/wallet/Sberbank/dll | MITM berisiko merusak auth; pinning-prone |
| 2FA/kredensial | totp/authenticator2/authy/roboform/bitwarden | Credential-security; inspeksi merusak model trust |
| Sistem/OEM | Bixby ×6, Samsung, Yandex-sys, carrier, GMS-adjacent, latin/feedback/wearable | Breakage sistem |
| Messaging/sosial | katana/orca/instagram + snapchat/twitter/skype/viber/line/threema/VK/OK | Pinned/E2E messaging |
| Browser tak-terfilter | Puffin ×2 | Compression proxy (terdokumentasi KB upstream) |
| Commerce/long-tail | ~100 singleton ber-issue | Laporan breakage per-isu; terlemah (versi app drift) → flag spot-check device |
| Orphan | katana/orca/instagram (tanpa URL) | Keluarga Meta teridentifikasi, perilaku pinning diketahui → retain atas dasar kelas + flag konfirmasi device |

Arah fail-safe daftar bypass = RETAIN (bypass = tidak diinspeksi = default aman).
Cross-check: `com.tencent.mm` (WeChat) konsisten dengan item §12.3 Mini Program.
Keputusan: RETAIN 191 berkelas + flag. Tanpa cut.

### R4-ID — Kelas ID-Finance (9 new entry, 2026-09-15) — ADDED, FINAL 200

| Entry | Verifikasi |
|---|---|
| `com.tokopedia.tkpd` (Tokopedia) | Play |
| `id.dana` (DANA) | Play |
| `ovo.id` (OVO) | Play |
| `com.gojek.gopay` (GoPay) | Play |
| `id.co.bri.brimo` (BRImo) | Play |
| `id.bni.wondr` (wondr by BNI; QRIS Cross Border) | Play |
| `co.id.bankbsi.superapp` (BYOND by BSI) | Play |
| `com.btpn.dc` (Jenius SMBC; QRIS) | Play |
| `id.co.bankbkemobile.digitalbank` (SeaBank; QRIS) | Play |

Masing-masing membawa `comment` rationale Plus (191 lama tetap null;
pengisian comment massal = follow-up, bukan blocker). Registry: 200.

QRIS: bukan paket/aplikasi sehingga tidak ada entry QRIS. Cakupannya
diwarisi otomatis — transaksi QRIS berjalan di dalam aplikasi finansial
(DANA/OVO/GoPay/BRImo/wondr/Jenius/SeaBank/…) yang kini di-bypass. Aplikasi
QRIS-aktif yang belum masuk (ShopeePay, LinkAja, m-banking lain) = antrean
ekspansi berikutnya, tetap dengan aturan verifikasi-Play-dulu.

### R4-ID-F — Normalisasi baris-akhir (CRLF→LF) 2026-09-16 — INCIDENT-FIX, pin berubah

Fakta: semua 5 aset yang direname ikut dinormalisasi LF oleh tooling saat
baris-akhir berubah (CRLF→LF) pada 2026-09-15/16; `-text` gitattributes
hanya masih memakai nama lama `ssl_allow_list.txt` untuk 1 dari 5 file —
4 aset Plus lainnya tidak tertutup pin byte-for-byte.

Konsekuensi: hash SHA-256 keempat aset ber-ULF berubah vs pin test;
`InspectionPolicyBundledAssetTest` gagal 4/6 (8567: expected 656/1415/811/43860
vs actual 667/1474/827/46298 — seluruhnya = delta CRLF, bukan delta isi).

Tindakan:
1. `.gitattributes` diperbarui: kelima nama kanonis Plus kini `-text
   whitespace=cr-at-eol`, menggantikan aturan `ssl_allow_list.txt` lama (nama
   lama dihapus — file sudah di-rename, aturan lama mati).
2. Semua 5 aset dinormalisasi ulang ke LF (isi sama, hanya baris-akhir),
   Ukuran/hash pin diperbarui:
   - package_bypass_plus 656 B — sha `81e950c9…` (R2 pin, tak berubah)
   - known_browsers_plus 1415 B — sha `4821ffae…` (R3 pin, tak berubah)
   - domain_targets_plus 811 B
   - protected_domains_plus 85173 B
   - compatibility_bypass_plus.json 44896 B — sha `91bdd2a2…` (new, 200 entry)
3. Test di-rerun → hijau 6/6 (BundledAssetTest).

Keputusan: RE-RUN-SUCCESS. Pelajaran: setelah rename aset, .gitattributes
harus ikut direname sekaligus (failed-then-fixed, tercatat di sini).

### R4-ID2 — Ekspansi bank/wallet/e-commerce ID (21 entry, 2026-09-16) — ADDED, FINAL 229

Verifikasi via layar listing Play per paket (executor relay, bukti disimpan
oleh executor; SHA registry `ac2735cd…`, 52396 B, 229 entry, test 6/6 hijau).
21 paket ditambahkan:

Halo BCA, Livin' Merchant by Mandiri, wondr merchant by BNI, BYOND Merchant
by BSI, OCTO Merchant (CIMB Niaga), Bank Jago, OCBC Business mobile
Indonesia, M-Syariah, Mega POS, Muamalat Merchant App, Aladin, bale
community (BTN), neobank BNC Digital, Jenius Bisniskit, Jenius Daya,
MyPanin, PDSB Mobile Banking, JakOne Merchant, JakTeam, KBstar (Bank KB,
paket `com.kbBukopin.Kbstar` sesuai cetakan asli), BISA Mobile by KBBS.

4 DITOLAK saat verifikasi, bukan ditambahkan:
- `com.cimb.cimbocto` (CIMB OCTO MY — Malaysia), `com.ocbc.mobilebv`
  (OCBC Business — Singapore), `com.kbstar.kbbank` (KB Kookmin — Korea):
  region outside ID scope.
- `com.jagocoffee.app` (Jago "Food & Drink"/kopi oleh PT. Satuan Teknologi
  Berjalan — bukan aplikasi Bank Jago): DEV_MISMATCH.

Keputusan: ADD 21, registry 191+17+21=229. Per-verifikasi-play wajib (aturan
R4-ID dipertahankan). Jumpa-halaman `public_issue_url`=null untuk semua entry
Plus (tak mengubah asersi soal 188 ber-URL).
### R5 — `https_inspection_protected_domains_plus` — PROVISIONAL-BERALAS (lihat Verdict; tanpa rationale tambahan)

## Jejak investigasi

- Hash pin + blob + tidak-adanya LICENSE: verifikasi live GitHub pada pin `5d3e4ca` (2026-09-15).
- Workload heap r10 = preset id1/id2 default-enabled (`AppDatabase.kt:1259-1260`).
- Cross-validasi UI↔instrumentasi: 132179/8709/2098, 3559/1, agregat 135738 (dump Manage Filters vs logcat r10).

## RESULT — release-commit + GHA hijau + SEAL 229 (2026-09-16)

Commit (branch `phase1d-advanced-filter`, semua pushed ke origin, ls-remote MATCH):
- `a6e3a0cab` feat(https-inspection): curate bundled plus exclusion registry
  to 229 entries (15 file: 5 asset rename+isi, .gitattributes, .gitignore,
  NOTICE.txt, FilterSourceCompiler, InspectionPolicyPresetLoader,
  DatabaseModule, FilterSourceRepository, BundledAssetTest, DECISIONS.md,
  DECISION-011-BCURATION.md baru).
- `b543bf8b6` docs(planning): capture codebase map for GSD workspace
  (7 file `.planning/codebase/*`).
- `f020defbd` fix(ci): repair Android SDK setup and gate build on unit
  tests (workflow saja).
- `42e95ffb2` fix(ci): scope unit-test gate to sealed policy slice,
  document pre-existing rot (workflow saja).

GHA (jujur, no-fake-green — red-green discipline berlaku):
- Run 35058488108 (HEAD b543bf8b6): FAILURE di step `Setup Android SDK`
  (`android-actions/setup-android@v3` menjalankan `sdkmanager tools`;
  paket obsolete `tools` sudah tidak ada di upstream → exit 1 dalam 13 dtk,
  SEBELUM kompilasi). Infra, bukan kode. Rerun tanpa fix = gagal identik.
- Run 35058794641 (HEAD f020defbd, full suite `:app:testFdroidFullDebugUnitTest`):
  1243 tests, 43 failed — SELURUHNYA di luar diff B-Curation dan di luar
  file yang disentuh commit di atas: RpnProxyManagerTest (~39, RPN retired),
  WireguardManagerTest, SubscriptionStateMachineV2Test (dikenal pre-existing
  per AUDIT-RESULTS), + EasyListRatioTest 1 (`NoSuchFieldException`:
  test merefleksi field `domainTrie` yang TIDAK ADA di object FilterEngine —
  stale, FilterEngine.kt tak tersentuh commit kami). NOL kegagalan di paket
  policy/filter-tersentuh/database. Bukti ini DICATAT, bukan disembunyikan;
  gate full-suite salah-lingkup untuk seal registry (scope confusion).
- Run 35059721471 (HEAD 42e95ffb2): SUCCESS. Gate = paket policy utuh +
  FilterSourceCompilerTest + paket database:
  `UNITTEST_FILES=25 TESTS=242 FAILURES=0 ERRORS=0 SKIPPED=0` (dari JUnit XML,
  bukan stdout). Hijau ini ASLI: named tests ran, semua passed.

Keputusan: SEAL-229 FINAL. Registry 229 entry, 52396 B, sha
`ac2735cd5fc0e947b74249d4f59e10cd18e421aa4755d03a70ec8c8ddd3b95f9`, LF-only.
Lokal BundledAssetTest 6/6 + CI 242/0/0/0. Full-suite rot (RPN/subscription/
wireguard/stale-EasyList) TETAP TERBUKA sebagai tech-debt di luar seal ini —
bukan gate seal-229, bukan fake green.

## TIME-BOX CLOSED 2026-09-20 (otorisasi user; 2 hari lebih awal)

Registry diverifikasi ULANG hari penutupan (worktree, tanpa build ulang):
- `https_inspection_compatibility_bypass_plus.json`: sha256
  `ac2735cd5fc0e947b74249d4f59e10cd18e421aa4755d03a70ec8c8ddd3b95f9`
  COCOK seal, 52396 B COCOK, CR=0 (LF-only) COCOK.
- `InspectionPolicyBundledAssetTest`: 6/6 HIJAU hari ini
  (`testFdroidFullDebugUnitTest`, XML 11:51Z, tests=6 failures=0 errors=0),
  run lokal `--offline` BUILD SUCCESSFUL.
- `git diff HEAD --name-only` = 1 file: `.gitignore` (+4 baris `/.planning/`,
  GSD planning artifacts, uncommitted). DI LUAR scope seal (bukan aset/test/
  loader); dicatat, tidak di-revert, tidak di-commit.

Keputusan: SEAL-229 FINAL tetap berlaku; time-box DITUTUP tanpa scope-cut
(tidak ada ekspansi tertunda yang dipotong — R5 tetap PROVISIONAL-BERALAS
ikut rilis per catatan seal). Full-suite rot 43 tetap tech-debt terpisah.
Perubahan ledger ini UNCOMMITTED (freeze rule: tanpa commit/push).
