# Sesi Bridge 18–20 Sep 2026 — Ringkasan Progres, Perubahan, Tertunda

## 1. Vonis investigasi (terbukti perangkat, bukan dugaan)
- Render lambat/blank situs berita: **bukan filter** (match 347k rules <0,2 dtk).
- **Exit SS Singapura (35.240.184.25, GCP)** di-flag bot-management AWS WAF /
  Cloudflare: tarpit 30 dtk, challenge 202, 403. Kompas/tempo/tribun/anichin.
- **Bulk stall**: header lolos, body macet mid-transfer via exit SS; exit
  direct ngebut; OVH 1 MB lolos. Rute, bukan kode. ICMP mati total (tanpa PTB).
- **DNS goyah**: RC=6 episode, hangover/discard Firestack.
- **Kill sistem**: Mi A1 7x LOW_MEMORY (+4x Soak); Poco restart-diam.
  exit-info = bukti resmi (bukan opini).
- **UDP SS mati**: `libsslocal WARN socks5 udp is disabled` → voice kadang
  (TCP fallback), video mati. Masalah SS (v2ray-plugin; UDP Fallback tak
  bisa di-enable). Bukan bug Rethink — vonis user, jangan ganggu SS.
- **Iklan aplikasi** (DaTuner): policy hanya MITM browser
  (BYPASS_DEFAULT by design); toggle include per-app menyembuhkan (11
  decision MITM_USER_APP live). Frame kosong tersisa = tradeoff opaque.
- **Desync UI**: flag vs tunnel (2x terkonfirmasi); fix reconcile+gate 10 dtk.
- **UI freeze/tab-mati**: kadang, tak terulang saat testing (catat saja).
- **Firefox terkontaminasi uBlock** → semua vonis visual diulang di Chrome.
- **Tun Rethink ori tak tersentuh Plus** (cek diff: hanya setHttpProxy
  kondisional master+CA + komentar threading). MTU 1500 benar (9000 milik
  AdGuard; jumbo di path 1500 + ICMP mati = blackhole).
- **Family Link error = VPN-independent** (terbukti dua arah, retry sembuhkan;
  Poco = device orang-tua).

## 2. Perubahan kode (7 commit di bridge, tanpa push)
- `6661ce331` WAF auto-bypass v1 (exact-host, challenge/timeout, Plus row).
- `85d57448f` desync-fix (reconcile onResume + gate 10 dtk + 6 test).
- `5592160d3` toggle kategori (8 switch + remember-restore + 6 test).
- `e55f04d76` hardening-batch: v1.1 windowed strikes, master toggle WAF,
  exit-health canary, user-stop marker, watchdog alarm-chain + UX Plus.
- `e930218de` upstream-dial breaker (restart debounced, fail-closed).
- `eaab9e20b` merge upstream Phase-2 (34 commit; 12 konflik teratasi).
- `b88871bc5` kill-switch global + `c0ad63f81` hot-plug arm + `0c9e4daa0`
  tombstone-di-email-support.
- GHA hijau semua run (0 fail akhir; 1 fail antara = Robolectric
  apply()-race → hook deterministik). Temp branch selalu dibersihkan.
- Resign lokal pin 046e80…; lineage install -r tanpa wipe ×berkali-kali.
- Sisa dirt: DECISIONS.md saja (gate dokumen terpisah).

## 3. Bukti perangkat
- Poco: kompas/tempo/tribun/detik/thedodo/anichin render (Chrome bersih);
  toggle WAF OFF/ON dua arah; watchdog tick 15 dtk; DaTuner include +
  AdGuard DNS 180k; ipleak exit proof; Discover; category switch visual.
- Mi A1: kontrol exit-direct; watchdog HEAL ×2 (46 dtk, 45 dtk) + Soak
  DITUTUP (4 jam stabil); kill-switch hot-plug dua arah; install-r HEAD.
- Baterai (harness otonom, Mi A1): S1 ~2%/jam, S2 ~1,3%/jam, S3 full
  ~1,1%/jam — tanpa red flag. Watchdog 15 dtk tak terlihat di drain.
- Crash reporting: audit → capture+kumpul+email LENGKAP (tombstone kini
  ikut email); fdroid stubs by design; agregasi metrik = triase manual.

## 4. Tertunda (butuh keputusan/auth)
1. **Push** (HOLD LOCAL + sekuens post-release; temp bridge-plus ada).
2. **Direct-upstream redesign** (DITUNDA eksplisit).
3. Stabilitas: UI freeze root-cause, label FAILING, whitelist domain-user,
   custom rule popup anichin, tes non-WiFi (QUIC).
4. Beta-prep: triase crash manual, kill-switch done, Play policy, exit decision.
5. DECISIONS.md formal (usulan DECISION baru di bawah — TANPA sign-off).
