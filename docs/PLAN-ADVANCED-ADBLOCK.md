# Advanced Adblock Features â€” Implementation Plan

> **Tujuan:** Membawa kemampuan adblock RethinkDNS setara dengan AdGuard/uBlock Origin
> dengan mengimplementasikan 4 fitur lanjutan di atas infrastruktur MITM proxy yang sudah ada.

---

## Konteks Arsitektur

Semua fitur ini berjalan di atas pipeline yang sudah ada:

```
Traffic â†’ VPN Tunnel â†’ LocalHttpsProxy (port 8443) â†’ MITM Inspection
                              â†“
                    pipeResponseBody()          â† titik injeksi utama
                              â†“
                    HTML body sudah dibuffer    â† bisa dimodifikasi
                              â†“
                    CosmeticFilter (CSS)        â† sudah inject <style>
                              â†“
                    Browser menerima response yang sudah dimodifikasi
```

**File utama yang terlibat:**
- `core/proxy/LocalHttpsProxy.kt` â€” MITM proxy server
- `core/filter/FilterEngine.kt` â€” parser aturan adblock
- `core/filter/CosmeticFilter.kt` â€” CSS element hiding
- `service/DomainRulesManager.kt` â€” manajemen domain rules

**Prasyarat wajib:**
- User harus install CA certificate RethinkDNS
- HTTPS inspection harus aktif (opt-in)
- App dengan certificate pinning akan otomatis bypass via `dynamicBypassSet`

---

---

## âœ… Phase 1: CSP Injection â€” COMPLETED

**Status:** Done. 13/13 tests passed.

**Yang diimplementasikan:**
- `CspInjector.kt` â€” parse `$csp=` rules dari FilterEngine, domain matching, LRU cache, merge dengan CSP existing
- `FilterEngine.kt` â€” tambah `cspRules: ArrayList<String>`, deteksi `$csp=` modifier di `parseRule()`, bump `CACHE_VERSION` ke 2
- `LocalHttpsProxy.kt` â€” inject/merge `Content-Security-Policy` header di `pipeResponseBody()`
- `CspInjectorTest.kt` â€” 13 test cases

**Cara kerja:** Filter list pakai syntax `||example.com^$csp=script-src 'self'`. FilterEngine detect modifier `$csp=`, store raw rule di `cspRules`. CspInjector lazy-parse saat pertama kali dibutuhkan, return merged CSP directive per domain. Proxy inject sebagai response header.

---

## âœ… Phase 2: Procedural Cosmetic Filters â€” COMPLETED

**Status:** Done. 20/20 tests passed.

**Yang diimplementasikan:**
- `ProceduralFilter.kt` â€” parse `#?#` rules, build JS snippet per domain, LRU cache. Operators yang didukung: `:has()`, `:has-text()`, `:matches-css()`, `:upward()`, `:nth-ancestor()`, `:xpath()`, `:remove()`
- `FilterEngine.kt` â€” tambah `proceduralRules: ArrayList<String>`, deteksi `#?#` sebelum `##` (karena `##` juga match `#?#`), bump `CACHE_VERSION` ke 3
- `LocalHttpsProxy.kt` â€” inject procedural JS **sebelum** CSS agar berjalan lebih awal; gabungkan CSS + JS injection dalam satu pass
- `ProceduralFilterTest.kt` â€” 20 test cases

**Cara kerja:** Filter list pakai syntax `example.com#?#.banner:has(img[src*="ads"])`. FilterEngine deteksi `#?#`, store di `proceduralRules`. ProceduralFilter lazy-parse dan generate JS IIFE yang: (1) run immediate, (2) run on DOMContentLoaded, (3) MutationObserver untuk dynamic pages. Proxy inject `<script>` ke `</head>`.

---

### Syntax yang akan didukung:
```
example.com#?#.banner:has(img[src*="ads"])
example.com#?#div:xpath(//div[@data-ad])
##:upward(.ad-wrapper)
##:matches-css(display: block)
```

### File baru yang perlu dibuat

**`core/filter/ProceduralFilter.kt`**

```kotlin
object ProceduralFilter {
    data class ProceduralRule(
        val selector: String,       // CSS selector dasar
        val operator: String,       // has, xpath, upward, matches-css, dll
        val argument: String,       // argumen operator
        val allowedDomains: Set<String>?,
        val excludedDomains: Set<String>?
    )

    fun getScriptForDomain(domain: String): String?
    fun clear()
    fun onRulesReloaded()

    // Generate JS yang mengeksekusi procedural filtering
    private fun buildJsForRule(rule: ProceduralRule): String
    private fun parseProceduralRule(rawLine: String): ProceduralRule?
}
```

### Perubahan di file yang sudah ada

**`core/filter/FilterEngine.kt`**
- Tambah deteksi `#?#` sebagai `isProceduralCosmetic` (berbeda dari `##`)
- Simpan ke `proceduralRules: ArrayList<String>` (mirip `cosmeticRules`)
- Update `saveToCache()` dan `loadFromCache()` untuk include `proceduralRules`

**`core/filter/CosmeticFilter.kt`**
- Tidak perlu banyak perubahan, sudah handle `##`

**`core/proxy/LocalHttpsProxy.kt`** â€” di `pipeResponseBody()`:
```kotlin
// Yang sudah ada:
val css = if (isHtml) CosmeticFilter.getCssForDomain(host) else null

// Yang perlu ditambah:
val proceduralJs = if (isHtml) ProceduralFilter.getScriptForDomain(host) else null

// Update injection block:
val injection = buildString {
    if (css != null) append("<style>$css</style>")
    if (proceduralJs != null) append("<script>$proceduralJs</script>")
}
```

### Operator yang perlu diimplementasikan (prioritas)

| Operator | Implementasi JS | Prioritas |
|----------|----------------|-----------|
| `:has(selector)` | `el.querySelectorAll(':has()')` atau polyfill | Tinggi |
| `:upward(n)` | traversal `parentElement` sebanyak n | Tinggi |
| `:xpath(expr)` | `document.evaluate(expr, ...)` | Sedang |
| `:matches-css(prop: val)` | `getComputedStyle(el)` | Sedang |
| `:matches-attr(attr)` | `el.hasAttribute(attr)` | Rendah |
| `:watch-attr(attr)` | `MutationObserver` | Rendah |

### Testing
- Tambah test case di `FilterEngineTest.kt` untuk parsing `#?#`
- Tambah `ProceduralFilterTest.kt` untuk JS generation
- Test domain matching (allowed/excluded)

---

## âœ… Phase 3: Scriptlet Injection â€” COMPLETED

**Status:** Done. 28/28 tests passed (`ScriptletFilterTest`, build XML ts 2026-07-26, 0 failures / 0 errors).

**Yang diimplementasikan:**
- `ScriptletFilter.kt` (289 lines) â€” parse `#%#//scriptlet(...)` rules, domain matching, per-domain code generation.
- `app/src/main/assets/scriptlets.js` (~11.5 KB) â€” **self-written** scriptlet library (Apache-2.0 RethinkDNS header incl.), 8 core scriptlets: `abort-on-property-read`, `abort-on-property-write`, `set-constant`, `remove-attr`, `remove-class`, `no-fetch-if`, `no-xhr-if`, `json-prune`. **Not an AdGuard Scriptlets port** â†’ konsisten dengan DECISION-004 clean-room policy (docs/DECISIONS.md); rencana "Opsi A (port AdGuard)" TIDAK diambil.
- `FilterEngine.kt` â€” `scriptletRules: ArrayList<String>`, deteksi `#%#//scriptlet(` sebagai `isScriptlet`.
- `LocalHttpsProxy.kt` â€” inject scriptlet `<script>` **pertama** (sebelum CSS/procedural) agar berjalan sebelum script halaman.
- `ScriptletFilterTest.kt` â€” 28 test cases.

### Rencana awal (referensi â€” per implementasi di atas)

**Syntax yang akan didukung:**
```
example.com#%#//scriptlet('abort-on-property-read', 'adsbygoogle')
example.com#%#//scriptlet('set-constant', 'canRunAds', 'true')
example.com#%#//scriptlet('remove-attr', 'onload', 'div.ad')
```

### Komponen yang dibutuhkan

#### A. Scriptlet Library

Bundling scriptlet JS library sebagai asset. Dua opsi:
- **Opsi A (Recommended):** Port scriptlet library dari AdGuard Scriptlets (open-source, Apache 2.0)
  - Source: https://github.com/AdguardTeam/Scriptlets
  - Bundle sebagai `assets/scriptlets.js`
- **Opsi B:** Tulis sendiri scriptlet yang paling umum digunakan (~20 scriptlets)

Scriptlets yang paling penting untuk diimplementasikan terlebih dahulu:
```
abort-on-property-read     â€” bypass anti-adblock paling umum
abort-on-property-write    â€” bypass anti-adblock
set-constant               â€” override variabel JS
remove-attr                â€” hapus attribute HTML
remove-class               â€” hapus CSS class
no-fetch-if                â€” intercept fetch() calls
no-xhr-if                  â€” intercept XMLHttpRequest
json-prune                 â€” modifikasi JSON response
```

#### B. File baru: `core/filter/ScriptletFilter.kt`

```kotlin
object ScriptletFilter {
    data class ScriptletRule(
        val scriptletName: String,
        val arguments: List<String>,
        val allowedDomains: Set<String>?,
        val excludedDomains: Set<String>?
    )

    fun getScriptletCodeForDomain(domain: String, context: Context): String?
    fun clear()

    // Load scriptlet template dari assets/scriptlets.js
    private fun loadScriptletLibrary(context: Context): String
    // Wrap scriptlet call dengan argumen
    private fun buildScriptletInvocation(rule: ScriptletRule, library: String): String
}
```

#### C. Perubahan di `FilterEngine.kt`
- Deteksi `#%#//scriptlet(` sebagai `isScriptlet`
- Simpan ke `scriptletRules: ArrayList<String>`
- Parse nama scriptlet dan argumen saat load

#### D. Perubahan di `LocalHttpsProxy.kt`
```kotlin
// Di pipeResponseBody(), tambahkan:
val scriptlet = if (isHtml) ScriptletFilter.getScriptletCodeForDomain(host, context) else null

// Inject sebelum </head>, SEBELUM semua script lain:
val injection = buildString {
    if (scriptlet != null) append("<script>$scriptlet</script>")  // scriptlet dulu
    if (css != null) append("<style>$css</style>")
    if (proceduralJs != null) append("<script>$proceduralJs</script>")
}
```

**Catatan penting:** Scriptlet harus diinjeksikan **pertama** (sebelum CSS/procedural) karena harus berjalan sebelum script halaman.

---

## âœ… Phase 4: HTML Filtering â€” COMPLETED

**Status:** Done. 24/24 tests passed (`HtmlFilterTest`, build XML ts 2026-07-26, 0 failures / 0 errors).

**Yang diimplementasikan:**
- `HtmlFilter.kt` (229 lines) â€” implementasi `##^` HTML element-removal rules. **Menggunakan Jsoup** (`import org.jsoup.Jsoup` + `Document`/`Element`/`Elements`) â†’ robust terhadap nested tags; pilihan **Opsi B (Jsoup)** dari rencana, bukan Opsi A (regex fragile).
- `FilterEngine.kt` â€” `htmlFilterRules: ArrayList<String>`, deteksi `##^` sebagai `isHtmlFilter`.
- `LocalHttpsProxy.kt` â€” `HtmlFilter.applyFilters(host, workingBody)` setelah decompressedBody tersedia; size-limit guard untuk halaman besar.
- `HtmlFilterTest.kt` â€” 24 test cases.
- Deps: `app/build.gradle` L375 `implementation 'org.jsoup:jsoup:1.19.1'`. **Catatan non-gating:** ditemukan **dua** baris `jsoup` dengan versi berbeda di build.gradle (L375 `1.19.1` + L404â€“405 `1.18.3`) â€” latent duplication footgun; belum dibersihkan (tidak gate, ditandai saja).

### Rencana awal (referensi â€” per implementasi di atas)

**Syntax yang akan didukung:**
```
example.com##^script:has-text(adsbygoogle)
example.com##^div[id="ad-container"]
##^.advertisement
example.com##^script[src*="analytics"]
```

### File baru: `core/filter/HtmlFilter.kt`

```kotlin
object HtmlFilter {
    data class HtmlFilterRule(
        val tagSelector: String,     // tag + attribute selector
        val hasTextPattern: String?, // konten teks yang dicari (opsional)
        val allowedDomains: Set<String>?,
        val excludedDomains: Set<String>?
    )

    // Fungsi utama: hapus matching tags dari HTML string
    fun applyFilters(domain: String, htmlBody: String): String
    fun clear()

    // Parser rule dari syntax ##^
    private fun parseHtmlFilterRule(rawLine: String): HtmlFilterRule?
    // Remove tag dari HTML menggunakan string manipulation (tanpa full DOM parser)
    private fun removeMatchingTags(html: String, rule: HtmlFilterRule): String
}
```

### Strategi Parsing HTML

**Opsi A â€” Regex-based (Sederhana, tapi fragile):**
```kotlin
// Contoh untuk ##^script:has-text(adsbygoogle)
val pattern = Regex("<script[^>]*>[^<]*adsbygoogle[^<]*</script>", RegexOption.DOT_MATCHES_ALL)
html.replace(pattern, "")
```
- Pro: cepat, no dependency
- Con: tidak handle nested tags, bisa rusak HTML yang kompleks

**Opsi B â€” Jsoup (Recommended):**
```kotlin
// Tambah dependency: implementation("org.jsoup:jsoup:1.17.2")
val doc = Jsoup.parse(htmlBody)
doc.select("script:containsData(adsbygoogle)").remove()
doc.select("div#ad-container").remove()
return doc.outerHtml()
```
- Pro: robust, handle nested tags, sudah battle-tested
- Con: tambah ~500KB ke APK, sedikit lebih lambat

**Rekomendasi:** Pakai Jsoup. Trade-off ukuran APK worth it untuk correctness.

### Perubahan di `FilterEngine.kt`
- Deteksi `##^` sebagai `isHtmlFilter`
- Simpan ke `htmlFilterRules: ArrayList<String>`

### Perubahan di `LocalHttpsProxy.kt`
```kotlin
// Di pipeResponseBody(), setelah decompressedBody tersedia:
var workingBody = decompressedBody ?: ""
if (isHtml && workingBody.isNotEmpty()) {
    workingBody = HtmlFilter.applyFilters(host, workingBody)
}
// Gunakan workingBody untuk injeksi CSS/JS berikutnya
```

### Performance Guard

Sudah ada limit 5MB di kode existing. Perlu tambahkan:
```kotlin
// Lewati HTML filtering untuk halaman sangat besar atau kompleks
val shouldHtmlFilter = isHtml &&
    (contentLength == -1L || contentLength < 2 * 1024 * 1024) && // 2MB limit untuk HTML filter
    HtmlFilter.hasRulesForDomain(host)
```

---

## Feature 4: CSP Injection

**Tujuan:** Inject atau modifikasi `Content-Security-Policy` header untuk memblokir
resource dari tracking/ad domains di level browser.

**Syntax filter list yang akan didukung (custom):**
```
! CSP rules (custom RethinkDNS extension)
example.com$csp=script-src 'self' 'unsafe-inline'
$csp=connect-src 'self',third-party
```

### File baru: `core/filter/CspInjector.kt`

```kotlin
object CspInjector {
    data class CspRule(
        val cspDirective: String,       // nilai CSP yang akan ditambahkan
        val allowedDomains: Set<String>?,
        val excludedDomains: Set<String>?
    )

    // Kembalikan CSP header value untuk domain, atau null
    fun getCspForDomain(domain: String): String?

    // Merge CSP baru dengan CSP yang sudah ada di response
    fun mergeWithExistingCsp(existing: String, addition: String): String
    fun clear()
}
```

### Perubahan di `LocalHttpsProxy.kt` â€” di `pipeResponseBody()`

```kotlin
// Setelah finalHeaders dibuat, sebelum write ke output:
val additionalCsp = CspInjector.getCspForDomain(host)
if (additionalCsp != null) {
    val existingIdx = finalHeaders.indexOfFirst {
        it.lowercase().startsWith("content-security-policy:")
    }
    if (existingIdx != -1) {
        val merged = CspInjector.mergeWithExistingCsp(
            finalHeaders[existingIdx],
            additionalCsp
        )
        finalHeaders[existingIdx] = merged
    } else {
        finalHeaders.add("Content-Security-Policy: $additionalCsp")
    }
}
```

### CSP Rules Default (baked-in)

Selain user-defined rules, bisa include default CSP rules untuk memblokir
tracker yang paling umum:

```kotlin
// Default rules yang bisa di-toggle user:
val DEFAULT_TRACKER_BLOCK_CSP = mapOf(
    "connect-src" to listOf(
        "https://www.google-analytics.com",
        "https://www.googletagmanager.com",
        "https://connect.facebook.net",
        "https://static.hotjar.com"
    )
)
```

**Catatan:** CSP injection bisa membreak beberapa website jika terlalu agresif.
Implementasikan sebagai **opt-in per-domain** atau hanya untuk rules eksplisit dari filter list.

---

## Urutan Implementasi yang Disarankan

Implementasikan secara berurutan â€” setiap fitur berdiri sendiri dan bisa di-ship terpisah:

```
Phase 1 (Paling mudah, impact tinggi)
â””â”€â”€ Feature 4: CSP Injection
    - ~200 baris kode baru
    - Tidak perlu file baru yang kompleks
    - Zero risk merusak fungsionalitas existing

Phase 2 (Fondasi untuk Phase 3)
â””â”€â”€ Feature 1: Procedural Cosmetic Filters
    - ~400 baris kode baru
    - Extend FilterEngine + CosmeticFilter yang sudah ada
    - JS injection sudah ada piping-nya

Phase 3 (Perlu bundled JS library)
â””â”€â”€ Feature 2: Scriptlet Injection
    - ~300 baris Kotlin + bundled scriptlet JS assets
    - Bergantung pada Phase 2 (shared injection mechanism)
    - Perlu keputusan: port AdGuard scriptlets atau tulis sendiri

Phase 4 (Paling kompleks)
â””â”€â”€ Feature 3: HTML Filtering
    - ~400 baris Kotlin + dependency Jsoup
    - Perlu benchmark performa sebelum ship
    - Perlu extensive testing â€” risiko merusak halaman tinggi
```

---

## Dependency yang Perlu Ditambahkan

```gradle
// app/build.gradle

// Feature 3: HTML Filtering (Jsoup)
implementation("org.jsoup:jsoup:1.17.2")

// Tidak ada dependency tambahan untuk Feature 1, 2, 4
// Feature 2 (Scriptlet) menggunakan bundled JS assets, bukan library Android
```

---

## Struktur File Baru

```
app/src/main/java/com/celzero/bravedns/
â””â”€â”€ core/
    â””â”€â”€ filter/
        â”œâ”€â”€ FilterEngine.kt          (modifikasi existing)
        â”œâ”€â”€ CosmeticFilter.kt        (modifikasi minor)
        â”œâ”€â”€ ProceduralFilter.kt      (BARU â€” Feature 1)
        â”œâ”€â”€ ScriptletFilter.kt       (BARU â€” Feature 2)
        â”œâ”€â”€ HtmlFilter.kt            (BARU â€” Feature 3)
        â””â”€â”€ CspInjector.kt           (BARU â€” Feature 4)

app/src/main/assets/
â””â”€â”€ scriptlets.js                    (BARU â€” Feature 2, bundled JS library)

app/src/test/java/com/celzero/bravedns/core/filter/
â”œâ”€â”€ FilterEngineTest.kt              (modifikasi existing â€” tambah test case baru)
â”œâ”€â”€ CosmeticFilterTest.kt            (modifikasi existing)
â”œâ”€â”€ ProceduralFilterTest.kt          (BARU)
â”œâ”€â”€ ScriptletFilterTest.kt           (BARU)
â”œâ”€â”€ HtmlFilterTest.kt                (BARU)
â””â”€â”€ CspInjectorTest.kt               (BARU)
```

---

## Perubahan di `FilterEngine.kt` â€” Summary

Ini adalah file yang paling banyak berubah. Perubahan yang diperlukan:

```kotlin
// Tambahan field baru:
val proceduralRules = ArrayList<String>()   // untuk #?#
val scriptletRules = ArrayList<String>()    // untuk #%#
val htmlFilterRules = ArrayList<String>()   // untuk ##^
val cspRules = ArrayList<String>()          // untuk $csp modifier

// Di parseRule(), tambahkan deteksi:
val isProceduralCosmetic = line.contains("#?#")
val isScriptlet = line.contains("#%#//scriptlet(")
val isHtmlFilter = line.contains("##^")
val hasCspModifier = modifierPart?.contains("csp=") == true

// Di clear(), tambahkan:
proceduralRules.clear()
scriptletRules.clear()
htmlFilterRules.clear()
cspRules.clear()

// Di saveToCache() dan loadFromCache(), tambahkan serialization untuk rule baru
```

---

## Risiko dan Mitigasi

| Risiko | Level | Mitigasi |
|--------|-------|----------|
| JS injection membreak halaman | Sedang | Wrap di try-catch, tambah error boundary |
| HTML filtering merusak DOM | Tinggi | Gunakan Jsoup, extensive testing, opt-in per-domain |
| CSP terlalu strict memblokir fungsi site | Sedang | Default off, hanya aktif jika ada rule eksplisit |
| Performa menurun akibat body buffering besar | Sedang | Size limit sudah ada (5MB), turunkan ke 2MB untuk HTML filter |
| Scriptlet conflict dengan site scripts | Rendah | Inject sebelum DOMContentLoaded, gunakan IIFE |
| Memory overhead dari JS library bundling | Rendah | Lazy-load `scriptlets.js`, cache di memory setelah load pertama |

---

## Checklist Sebelum Implementasi

- [x] Review lisensi AdGuard Scriptlets library â†’ **RESOLVED**: library self-written (RethinkDNS Apache-2.0 header, bukan port AdGuard), tidak ada adopsi AdGuard/GPL â†’ konsisten DECISION-004 clean-room policy (docs/DECISIONS.md).
- [ ] Benchmark `pipeResponseBody()` dengan dan tanpa HTML filter aktif pada halaman 1-2MB
- [ ] Konfirmasi CA certificate flow sudah stabil untuk majority use case
- [ ] Pastikan `dynamicBypassSet` coverage cukup untuk app-app dengan cert pinning
- [ ] Tentukan apakah fitur ini di-gate di belakang setting baru atau langsung aktif jika filter list support syntax baru

---

## Referensi

- AdGuard scriptlets source: https://github.com/AdguardTeam/Scriptlets
- AdGuard extended CSS source: https://github.com/AdguardTeam/ExtendedCss
- uBlock Origin procedural cosmetic filters docs: https://github.com/gorhill/uBlock/wiki/Procedural-cosmetic-filters
- EasyList filter syntax: https://help.eyeo.com/adblockplus/how-to-write-filters
- CSP spec: https://developer.mozilla.org/en-US/docs/Web/HTTP/CSP
- Jsoup: https://jsoup.org
