# Project Status

- **Last reviewed:** 2026-11-25
- **Commit:** 5d79322 (main) — نفس commit الوسم `v0.5.0`
- **Summary:** 18 done · 1 in progress · 0 remaining
- البناء والاختبارات لم تُشغَّل في هذه المراجعة. يوجد 88 اختبار JUnit في 11 ملفًا، و14 حالة في `tools/tests/Packaging.Tests.ps1`.

## Done

### المكتبة (`src/main/java/com/hamza/updater`)
- [x] فحص الإصدارات من GitHub Releases، مع Pre-release اختياريًا — `GitHubReleases.java`
- [x] ملف manifest على خادم خاص (`version`، `url`، `sha256`، `size`، `notes`، `minimum`، `signature`) — `ManifestUrl.java`
- [x] تنزيل يكمل من حيث انقطع (`Range`) مع شريط تقدّم — `Download.java`، `internal/Http.java`
- [x] التحقق من الحجم و SHA-256 قبل التشغيل — `Download.java`، `DownloadTest.java`
- [x] Windows: تثبيت صامت ثم إعادة الفتح، وطلب إغلاق البرامج الأخرى من نفس التثبيت — `InstallerLaunch.java`
- [x] أنواع المثبّت: jpackage، `INNO_SETUP`، `WIZARD` — `InstallerKind.java`
- [x] `InstallerKind.ZIP`: فك الضغط بجوار المجلد، تبديل الأسماء فقط، والرجوع للقديم إن فشل الجديد خلال 30 ثانية — `ZipFolder.java`، `InstallerLaunch.java`، `FolderSwapTest.java`
- [x] التحديث المطلوب: `<!-- update: required -->`، و `minimum` في الـ manifest، و `enforceRequired` — `Release.java`، `GitHubReleases.java`، `fx/UpdatePrompt.java`
- [x] Ed25519 لتوقيع الإصدارات (`signedBy`) — `ReleaseSignature.java`، `ReleaseSignatureTest.java`
- [x] Linux و macOS: ملف لكل نظام ومعالج (`assetFor`، `{arch}`)، ثم فتحه بـ `xdg-open` / `open` — `Os.java`، `GitHubReleases.java`، `AppUpdater.java`

### واجهة JavaFX (`src/main/java/com/hamza/updater/fx`)
- [x] نافذة بالعربية أو الإنجليزية: تحديث الآن / لاحقًا / تخطي، و`checkNow`، و`checkQuietly`، و`checkEvery`، و`onExit` — `UpdatePrompt.java`، `Texts.java`
- [x] «ما الجديد» بـ Markdown مقروء، مع اتجاه RTL/LTR حسب النص — `Notes.java`، `NotesView.java`، `NotesTest.java`
- [x] التنزيل في الخلفية ثم «جاهز للتثبيت» (على Windows) — `UpdatePrompt.java`

### أدوات النشر والتحزيم (`tools/`، `.github/`)
- [x] سكربت الإصدار بكل الخيارات المذكورة في README (`-DryRun`، `-CreateTag`، `-Required`، `-VerifyOnly`، `OtherAssets`، التوقيع) — `tools/publish-release.ps1`، `tools/release.example.ps1`
- [x] مفتاح التوقيع — `tools/signing-key.ps1`
- [x] وحدة التحزيم المشتركة (`Find-JPackage`، `Invoke-MavenBuild`، `Get-PomVersion`، `Find-WiX`، `Rename-PlatformPackage`، …) واختباراتها على الأنظمة الثلاثة — `tools/Packaging.psm1`، `tools/tests/Packaging.Tests.ps1`
- [x] الـ workflow المشترك لبناء ملفات Linux و macOS — `.github/workflows/package-unix.yml`
- [x] CI: `mvn verify` على Windows، واختبار وحدة التحزيم على Windows (PS 5.1 و 7) و Linux و macOS — `.github/workflows/ci.yml`
- [x] skill لـ Claude يربط المكتبة بأي برنامج — `claude-skill/desktop-auto-update/SKILL.md`
- [x] GitHub Release لـ `v0.5.0` (Latest)، وملاحظاته تشمل ZIP ووحدة التحزيم والـ workflow المشترك — `docs/release-notes/v0.5.0.md`

## In progress
- [ ] Linux و macOS لم يُجرَّبا على جهاز حقيقي، فقط بالاختبارات وبمحاكاة النظام على Windows — `README.md:226`، `docs/release-notes/v0.4.0.md:20`

## Remaining
- لا يوجد. لا توجد issues مفتوحة، ولا TODO/FIXME، ولا ROADMAP، ولا اختبارات معطلة.

## Open questions
- هل يشرح `claude-skill/desktop-auto-update/SKILL.md` استخدام `Packaging.psm1` و `package-unix.yml`؟ هو لا يذكرهما الآن.
- هل يبقى التنزيل في الخلفية على Windows فقط دائمًا، أم هو مؤجل لـ Linux و macOS؟

## History
- 2026-11-25 (5d79322): أول مراجعة. المشروع عند `v0.5.0`، والميزات المذكورة في README موجودة كلها في الكود. في اليوم نفسه نُشر GitHub Release لـ `v0.5.0`، وأُكملت ملاحظاته.
