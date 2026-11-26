# desktop-updater

«يوجد إصدار جديد: نزّله وثبّته»، لبرامج سطح المكتب المبنية بـ JavaFX والمحزّمة بـ jpackage.

[![CI](https://github.com/MohamedSalah-13/desktop-updater/actions/workflows/ci.yml/badge.svg)](https://github.com/MohamedSalah-13/desktop-updater/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/tag/MohamedSalah-13/desktop-updater)](https://github.com/MohamedSalah-13/desktop-updater/releases)
[![JitPack](https://jitpack.io/v/MohamedSalah-13/desktop-updater.svg)](https://jitpack.io/#MohamedSalah-13/desktop-updater)
![Java](https://img.shields.io/badge/Java-21%2B-ED8B00?logo=openjdk&logoColor=white)
![JavaFX](https://img.shields.io/badge/JavaFX-21%2B-2E7DAF?logo=openjdk&logoColor=white)
![Maven](https://img.shields.io/badge/Maven-build-C71A36?logo=apachemaven&logoColor=white)
![License](https://img.shields.io/badge/license-source--available-lightgrey)

[English](README.md) | **العربية**

<div dir="rtl">

## نبذة

مكتبة Java صغيرة تضيف لأي برنامج سطح مكتب (JavaFX + jpackage) خاصية: **يوجد إصدار جديد ← نزّله وثبّته**.
تُكتب مرة واحدة هنا وتُستخدم في كل البرامج، بدل إعادة كتابة كود التحديث في كل مشروع.

## الميزات

- تفحص الإصدارات المنشورة على **GitHub Releases** (أو ملف JSON على خادمك للمستودعات الخاصة).
- تُظهر للعميل نافذة بالعربية أو الإنجليزية: الإصدار الجديد، ما الجديد، «تحديث الآن / لاحقًا / تخطي هذا الإصدار».
- تنزّل المثبّت مع شريط تقدّم، وتكمل التنزيل المقطوع من حيث توقف.
- تتحقق من الحجم و **SHA-256** قبل تشغيل أي ملف، ومن **توقيع Ed25519** بمفتاحك إن أردت.
- **التحديث المطلوب**: إصدار لا يحق للنسخ الأقدم تخطيه.
- على Windows: تغلق البرنامج، تشغّل المثبّت بصمت، ثم تفتح البرنامج من جديد. أو تضع مجلد البرنامج الجديد
  من ملف zip مكان القديم، لبرنامج يُوزَّع بلا مثبّت.
- على Linux و macOS: تنزّل المثبّت الخاص بالنظام والمعالج وتتحقق منه ثم تفتحه ليكمل المستخدم التثبيت.
- أدوات للنشر والتحزيم: سكربت إصدار ينشر ما ستجده النسخ المثبّتة، ووحدة PowerShell مشتركة للتحزيم، و workflow
  مشترك في GitHub Actions لمثبّتات Linux و macOS.
- بلا اعتماديات خارجية: `java.base` و JavaFX الموجود في برنامجك فقط. Java 21 فأحدث.

## ما الجديد

### v0.6.2

- **إصلاح: تحديث الـ zip كان يفشل لو فُتح البرنامج من مجلده** بالضغط المزدوج أو من اختصار، كما يفعل العملاء.
  سكربت التحديث كان يرث مجلد عمل البرنامج، وWindows لا يغيّر اسم مجلد تعمل فيه عملية، فتنتهى كل محاولة بـ
  «Could not move … being used by another process» ويُفتح الإصدار القديم. السكربت الآن ينتقل لمجلده أولًا، ويفتح
  البرنامج فى مجلد البرنامج. النسخ على إصدار أقدم تكتب السكربت القديم: تحتاج تحديثًا واحدًا باليد، أو تشغيلها من
  مجلد عمل آخر.

### v0.6.1

- `Announcement.update()` يفتح النافذة بعد انتهاء حدث الزر بدل الانتظار داخله. الضغط عبر UI Automation (قارئ
  الشاشة أو أداة اختبار) كان يغلق النافذة فورًا ويبدأ التنزيل دون سؤال.

### v0.6.0

- **إعلان الإصدار بطريقة البرنامج.** `updates.onAvailable(found -> ...)` يسلّم الإصدار الذي يجده البرنامج بنفسه
  للبرنامج بدل فتح النافذة، فيعرضه بطاقةً في الشريط الجانبي أو علامةً على قائمة. `found.update()` يفتح نافذة
  التحديث لهذا الإصدار حين يطلبها المستخدم، و`found.openPage()` يفتح صفحته. التحديث المطلوب يفتح النافذة أيضًا.

### v0.5.0

- **برنامج يُوزَّع مجلدًا مضغوطًا.** `InstallerKind.ZIP` يحدّث برنامجًا يفك العميل ضغطه ويشغّله من مكانه، بلا مثبّت
  وبلا WiX. يُفك الضغط بجوار المجلد القديم والبرنامج ما زال يعمل، ويتبادل المجلدان الاسم بعد الإغلاق، ويعود
  المجلد القديم إن انتهى الإصدار الجديد بخطأ خلال 30 ثانية.
- **خطوات التحزيم المشتركة.** `tools/Packaging.psm1` يجمع الخطوات التي يكررها كل سكربت jpackage (إيجاد jpackage
  و WiX، تشغيل Maven، قراءة الإصدار، خيارات مثبّت Windows، أسماء ملفات Linux و macOS)، ومُختبر على الأنظمة الثلاثة.
- **مثبّتات Linux و macOS في GitHub Actions.** الـ workflow المشترك `package-unix.yml` يبنيها على أجهزة GitHub
  ويضيفها إلى الإصدار.
- سكربت الإصدار يزيل علامة «للقراءة فقط» من مجلدات البناء قبل البناء.

### v0.4.0

- **Linux و macOS.** لكل نظام ملفه لكل معالج، والإصدار الذي ليس فيه ملف لنظام النسخة لا يُعرض عليها إطلاقًا. الزر
  يصير «تنزيل المثبّت» والبرنامج لا يُغلق نفسه.
- إصلاح: نظام اسمه «Darwin» كان يُقرأ Windows لأن الاسم يحتوي «win».
- زر «موافق» في رسائل النافذة صار بلغة النافذة.

الإصدارات الأقدم: [docs/release-notes](docs/release-notes).

## التقنيات

| | |
| --- | --- |
| اللغة | Java 21 (`maven.compiler.release` 21) |
| الواجهة | JavaFX 21 (`provided`: البرنامج يحضر نسخته) |
| البناء | Maven، و JUnit Jupiter 5.12 |
| التوزيع | JitPack (`com.github.MohamedSalah-13:desktop-updater`) |
| أدوات الإصدار | PowerShell (Windows PowerShell 5.1 و PowerShell 7)، و GitHub CLI، و jpackage، و WiX 3 |
| CI | GitHub Actions على Windows و Linux و macOS |

## البدء

هذه الخطوات للبرامج المأذون لها باستخدام المكتبة: [الترخيص](LICENSE) لا يسمح باستخدامها أو نسخها أو توزيعها
دون إذن كتابي مسبق من صاحب الحقوق. لطلب الإذن افتح issue في هذا المستودع.

### إضافة المكتبة

`pom.xml`:

<div dir="ltr">

```xml
<repositories>
    <!-- قبل JitPack دائمًا: انظر الملاحظة أسفل المقطع -->
    <repository>
        <id>central</id>
        <url>https://repo.maven.apache.org/maven2</url>
        <snapshots>
            <enabled>false</enabled>
        </snapshots>
    </repository>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.github.MohamedSalah-13</groupId>
    <artifactId>desktop-updater</artifactId>
    <version>v0.6.2</version>
</dependency>
```

</div>

**لا تحذف `central` من المقطع.** مستودعات المشروع تُسأل قبل Maven Central الموروث، فلو ذُكر `jitpack.io` وحده
لسأله Maven أولًا عن كل مكتبة في المشروع. و JitPack يردّ على بعض ما ليس عنده بملف فارغ بدل 404 (مثل
`xml-apis:xml-apis-ext`)، فيُحفظ جار فارغ ويفشل البناء بـ `zip file is empty` على أي جهاز مستودعه المحلي فارغ،
وأولها خادم CI. يعمل على جهازك لأن الجار السليم محفوظ عندك من قبل.

مع `module-info.java` أضف: `requires com.hamza.updater;`

### الربط بالبرنامج

في `start(Stage)`:

<div dir="ltr">

```java
AppUpdater updater = AppUpdater.builder("File Transfer")
        .source(ReleaseSource.github("MohamedSalah-13/file-transfer").asset("*-setup.exe"))
        .build();

UpdatePrompt updates = UpdatePrompt.create(updater, stage)
        .language("ar")      // "ar" للعربية، وأي قيمة أخرى للإنجليزية
        .start();            // فحص بعد 8 ثوانٍ من التشغيل ثم كل 6 ساعات

checkButton.setOnAction(e -> updates.checkNow());   // زر «تحقق من التحديثات»
```

</div>

هذا كل شيء. رقم إصدار البرنامج لا يُكتب يدويًا: المشغّل الذي يصنعه `jpackage` يعطيه للبرنامج
(`jpackage.app-version`)، والنسخة التي تعمل من الـ IDE ليس لها إصدار فلا يُعرض عليها تحديث.

### شروط النشر

1. المثبّت مصنوع بـ `jpackage` مع `--win-upgrade-uuid` ثابت (ويُفضَّل `--win-per-user-install`).
2. الوسم على GitHub بصيغة `vX.Y.Z` ويطابق إصدار `pom.xml`.
3. الإصدار ليس Draft ولا Pre-release (أو استخدم `.includePreReleases(true)`).
4. ملف المثبّت مرفوع في الإصدار باسم يطابق `.asset("...")`.

وصف الإصدار على GitHub هو ما يراه العميل تحت «ما الجديد».

### تجربة النافذة أثناء التطوير

شغّل البرنامج من الـ IDE مع `-Djpackage.app-version=0.0.1` فيظن أنه نسخة قديمة مثبّتة ويعرض آخر إصدار
حقيقي. اضغط «لاحقًا» — «تحديث الآن» سينزّل المثبّت الحقيقي ويشغّله.

### بناء المكتبة

<div dir="ltr">

```bash
mvn verify
```

</div>

## الإعدادات

| الحاجة | الطريقة |
| --- | --- |
| مستودع خاص أو خادم خاص | `ReleaseSource.manifest("https://example.com/app/latest.json")` |
| مثبّت Inno Setup | `.installer(InstallerKind.INNO_SETUP)` |
| إظهار صفحات المثبّت العادية | `.installer(InstallerKind.WIZARD)` |
| برنامج يُوزَّع مجلدًا مضغوطًا (zip من `jpackage --type app-image`) لا مثبّتًا | `.installer(InstallerKind.ZIP)` |
| برنامج له أيقونة Tray أو طريقة إغلاق خاصة | `UpdatePrompt.onExit(() -> ...)` |
| تغيير اللغة أثناء التشغيل | `updates.language("en")` |
| تغيير فترة الفحص (5 دقائق على الأقل) | `updates.checkEvery(Duration.ofHours(12))` |
| منع العمل بنسخة قديمة عند تحديث مطلوب | `updates.enforceRequired(true)` |
| فحص فوري صامت (مثلًا عند رفض الخادم للإصدار) | `updates.checkQuietly()` |
| تنزيل الإصدار في الخلفية ثم عرض «جاهز للتثبيت» | `updates.downloadInBackground(true)` |
| إعلان الإصدار بطريقة البرنامج (بطاقة، علامة) بدل النافذة | `updates.onAvailable(found -> ...)` |
| قبول الإصدارات الموقّعة بمفتاحك فقط | `AppUpdater.builder(...).signedBy("...")` |
| بدون نافذة JavaFX | `updater.check()` ثم `download(...)` ثم `install(...)` من خيط خلفي |

ملف الـ manifest:

<div dir="ltr">

```json
{
  "version": "2.5.0",
  "url": "https://example.com/downloads/Program-2.5.0-setup.exe",
  "sha256": "9e08356040c651fbcf3134a702854cadfc4eaae15390dd159cb4e4f32dcb2d0e",
  "size": 34853376,
  "notes": "ما الجديد في هذا الإصدار"
}
```

</div>

### التحديث المطلوب (الإجباري)

إصدار يكسر التوافق (بروتوكول جديد، قاعدة بيانات جديدة، ثغرة أمنية) يُعلَّم «مطلوبًا» بسطر واحد في وصفه على
GitHub، وهو تعليق HTML لا يظهر في الصفحة:

<div dir="ltr">

```html
<!-- update: required -->
```

</div>

أو من سكربت الإصدار: `.\packaging\release.ps1 -Required`.

- كل نسخة **أقدم** من هذا الإصدار تُعرض عليها نافذة «تحديث مطلوب» بلا زر «تخطي هذا الإصدار»، وتعود عند كل
  فحص حتى لو سبق أن تخطّاه المستخدم.
- يبقى الإصدار مطلوبًا بعد نشر إصدارات أحدث منه: من لديه 2.6 والمطلوب 2.7 والأحدث 2.9 يُطلب منه التحديث،
  ومن لديه 2.7 أو أحدث يراه تحديثًا عاديًا.
- افتراضيًا يبقى زر «لاحقًا». إن كان البرنامج لا يصح أن يعمل بنسخة قديمة، أضف `enforceRequired(true)`:
  يصبح الخيار «تحديث الآن» أو «إغلاق البرنامج». هذا قرار البرنامج لا المكتبة، لأنه وحده يعرف هل النسخة
  القديمة ما زالت صالحة للعمل. وإن تعذّر الفحص (لا اتصال) يعمل البرنامج كالمعتاد.
- في ملف الـ manifest: الحقل `"minimum": "2.7.0"` يعني أن كل نسخة أقدم من 2.7.0 مطلوب منها التحديث.

### «ما الجديد» والتنزيل في الخلفية

وصف الإصدار يُعرض كما يُقرأ لا كما يُكتب: العناوين عناوين، والقوائم قوائم، والخط العريض والمائل والكود
والروابط تظهر على حقيقتها، والملاحظات العربية تُقرأ من اليمين حتى في واجهة إنجليزية والعكس. الجداول تبقى
نصًا، والصور يظهر وصفها فقط.

مع `updates.downloadInBackground(true)` يُنزَّل الإصدار الذي يجده البرنامج بنفسه قبل سؤال المستخدم، فتظهر
له نافذة «الإصدار 2.5 جاهز للتثبيت» والضغط يثبّت فورًا بلا انتظار. الإصدار الذي تخطّاه المستخدم لا يُنزَّل،
وما لم يكتمل تنزيله يُعرض بالطريقة العادية. الخيار مغلق افتراضيًا لأنه يستهلك اتصال المستخدم (عشرات
الميجابايت) دون سؤاله.

### إعلان الإصدار بطريقة البرنامج

النافذة فوق العمل ليست الطريقة الوحيدة لقول إن هناك إصدارًا جديدًا. مع `onAvailable` يذهب الإصدار الذي يجده
البرنامج بنفسه إلى البرنامج، فيعرضه حيث يشاء، وتُفتح النافذة حين يطلبها المستخدم:

```java
updates = UpdatePrompt.create(updater, stage)
        .language("ar")
        .onAvailable(found -> {
            card.setText("يتوفر الإصدار " + found.version());
            updateButton.setOnAction(e -> found.update());   // النافذة المعتادة لهذا الإصدار
            whatsNew.setOnAction(e -> found.openPage());     // صفحة الإصدار في المتصفح
            card.setVisible(true);
        })
        .start();
```

- يُستدعى على خيط JavaFX في كل فحص يجد إصدارًا لم يتخطّه المستخدم ولم يؤجّله في هذا التشغيل. ويحمل `found`
  أيضًا `current()` و`required()` و`ready()` (نُزِّل مسبقًا) و`notes()` و`page()`.
- التحديث المطلوب يُعلَن ويفتح النافذة أيضًا، حتى لا يفوت أحدًا.
- «التحقق من التحديثات» (`checkNow()`) يجيب بالنافذة كما كان.

### توقيع الإصدارات

فحص SHA-256 يضمن أن الملف هو ملف الإصدار، لكنه لا يحمي إن كان **الإصدار نفسه** مزوّرًا، كأن يستولي أحد على
حسابك في GitHub وينشر مثبّتًا خبيثًا. مع التوقيع يحمل البرنامج مفتاحك العام، ولا يُعرض عليه إلا إصدار
وقّعته بمفتاحك الخاص الذي لا يوجد على GitHub أصلًا.

1. أنشئ المفتاح مرة واحدة (يُحفظ افتراضيًا في `%USERPROFILE%\.desktop-updater\release-signing.key`):

   <div dir="ltr">

   ```powershell
   .\tools\signing-key.ps1 -New
   ```

   </div>

2. ضع المفتاح العام الذي يطبعه السكربت في كود البرنامج:

   <div dir="ltr">

   ```java
   AppUpdater.builder("File Transfer").source(...).signedBy("MCowBQYDK2VwAyEA...").build();
   ```

   </div>

3. في `packaging/release.ps1` الخاص بالمشروع أضف اسم البرنامج (كما في `builder("...")` تمامًا) ومكان المفتاح:

   <div dir="ltr">

   ```powershell
   $release.AppName    = 'File Transfer'
   $release.SigningKey = Join-Path $env:USERPROFILE '.desktop-updater\release-signing.key'
   ```

   </div>

   بعدها يوقّع السكربت المثبّت وينشر التوقيع بجواره باسم `<المثبّت>.sig`، ويتأكد منه قبل النشر وبعده.

ما يجب معرفته قبل التفعيل:

- **ملف المفتاح سر ونسخة وحيدة.** لا يوضع في أي مستودع، ويُحفظ له نسخة احتياطية في مكان آمن. إن ضاع فلن
  تستطيع توقيع أي إصدار، والنسخ المثبّتة التي تحمل نصفه العام لن تقبل غيره.
- **من أول إصدار فيه `signedBy` يجب توقيع كل إصدار**، وإلا فلن يُعرض على النسخ المثبّتة أي تحديث (والسبب
  يظهر في «تحقق الآن»). `release.ps1 -VerifyOnly` يقول هل الإصدار موقّع.
- **النسخ الأقدم لا تتحقق.** الحماية تبدأ ممن ثبّت إصدارًا فيه المفتاح؛ من قبله يقبل أي إصدار كالسابق.
- الموقَّع هو: اسم البرنامج، رقم الإصدار، وSHA-256 المثبّت وحجمه. لذلك لا يصلح توقيع برنامج لبرنامج آخر
  بالمفتاح نفسه، ولا يصلح مثبّت قديم موقّع كإصدار جديد.
- الخوارزمية Ed25519. صورة تشغيل مبنية بـ JDK 21 أو أقدم تحتاج الوحدة `jdk.crypto.ec`.
- في ملف الـ manifest يوضع التوقيع في الحقل `"signature"`.

### Linux و macOS

برنامج يُنشر لأكثر من نظام يسمّي ملف كل نظام، و`{arch}` تُستبدل بمعالج الجهاز (`x64` أو `arm64`):

<div dir="ltr">

```java
ReleaseSource.github("owner/app")
        .asset("*-setup.exe")                          // Windows، وكل نظام لم يُسمَّ
        .assetFor(Os.LINUX, "*-linux-{arch}.deb")
        .assetFor(Os.MAC, "*-macos-{arch}.dmg")
```

</div>

- **كل نسخة يُعرض عليها ملف نظامها فقط.** الإصدار الذي ليس فيه ملف لنظام ما (أو لمعالجه) لا يُعرض على
  نسخ ذلك النظام إطلاقًا، فلا يرى أحد تحديثًا لا يستطيع تثبيته. هذا مهم حين يُرفع مثبّت Windows أولًا وتُضاف
  بقية الملفات بعده بدقائق.
- **على Windows** لا شيء تغيّر: تثبيت صامت ثم إعادة فتح.
- **على Linux و macOS** الزر يصير «تنزيل المثبّت»: يُنزَّل الملف ويُفحص حجمه و SHA-256 (وتوقيعه إن كان
  البرنامج يستخدم `signedBy`) ثم يُفتح بما يفتح به النظام هذا النوع (`xdg-open` / `open`)، ويكمل المستخدم
  التثبيت ويعيد تشغيل البرنامج. البرنامج لا يُغلق نفسه. إن لم يوجد ما يفتح الملف تُعرض رسالة بمكانه.
- التنزيل في الخلفية يعمل على Windows فقط.
- في سكربت الإصدار: `$release.OtherAssets = @('*-linux-x64.deb', '*-macos-arm64.dmg')` يجعل `-VerifyOnly`
  يقول هل الإصدار يحمل ملفات الأنظمة الأخرى. السكربت نفسه ينشر مثبّت Windows فقط.

لم يُجرَّب هذا المسار على جهاز Linux أو macOS حقيقي بعد؛ جُرّب منطقه بالاختبارات وبمحاكاة النظام على Windows.

#### بناء ملفات Linux و macOS في GitHub Actions

jpackage يبني لنظامه فقط، فملفات Linux و macOS تُبنى على أجهزة GitHub. الـ workflow المشترك
`.github/workflows/package-unix.yml` يشغّل سكربت التحزيم الخاص بالمشروع على ثلاثة أجهزة: Linux، و macOS
بمعالج Apple، و macOS بمعالج Intel. ثم يحفظ الملفات مع التشغيل، ويضيفها إلى الإصدار إن أُعطي وسمًا.
المشروع يستدعيه من workflow خاص به يحتفظ بمتى يعمل:

<div dir="ltr">

```yaml
on:
  release:
    types: [published]   # نشر مثبّت Windows بسكربت الإصدار يبدأ هذا
  pull_request:
    paths: [packaging/**, pom.xml, .github/workflows/packages.yml]
  workflow_dispatch:
    inputs:
      tag:
        required: false
        default: ''

jobs:
  packages:
    name: Packages
    uses: MohamedSalah-13/desktop-updater/.github/workflows/package-unix.yml@<commit>
    permissions:
      contents: write
    with:
      script: packaging/build-installer.ps1
      files: target/installer/MyProgram-*
      tag: ${{ github.event.release.tag_name || inputs.tag }}
```

</div>

- **`script` و `files` إلزاميان.** السكربت يُشغَّل بـ `pwsh` ويسمّي ملفاته بنفسه، فالبرنامج يبحث عنها بأسمائها.
- **`tag` فارغ** في طلبات الدمج: تُبنى الملفات وتُحفظ مع التشغيل فقط.
- **اختيارية:** `java-version` (الافتراضي `25`)، و `apt-packages` (الافتراضي `fakeroot`)، و `virtual-screen`
  لاختبارات JavaFX على Linux مع `xvfb libgtk-3-0` في `apt-packages`، و `test-reports` ليُحفظ مجلد التقارير عند
  الفشل، و `artifact-name`، و `desktop-updater-ref` لسكربت يستورد `tools/Packaging.psm1` (الـ commit نفسه الذي في
  `uses:`).
- **ثبّت الاستدعاء على commit لا على `main`**، فلا يتغيّر بناء أي مشروع دون طلب دمج فيه.

### برنامج يُوزَّع ملف zip

برنامج يفك العميل ضغطه ويشغّله من مكانه، بلا مثبّت، يُنشر إصداره ملف zip لمجلده كما صنعه
`jpackage --type app-image`، ويقول `.installer(InstallerKind.ZIP)` مع `.asset("*-windows-x64.zip")`. لا يحتاج WiX.

- **المجلد الذي يُستبدل** هو مجلد البرنامج الذي يُفتح بعد التحديث (`relaunch`)، أي افتراضيًا مجلد المشغّل
  الذي شغّل هذه النسخة. ملف zip يحمل هذا المشغّل في أعلاه أو داخل مجلد واحد في أعلاه، كما يصنعه
  `Compress-Archive`، وإلا رُفض.
- **`install` يفك الضغط والبرنامج ما زال يعمل**، في `<المجلد>.update` بجواره على القرص نفسه. فمجلد لا يُكتب
  بجواره (داخل Program Files مثلًا) أو ملف ليس لهذا البرنامج خطأٌ يظهر للعميل والبرنامج مفتوح، ولا يُمس شيء.
- **بعد الإغلاق** يُعاد تسمية المجلد القديم إلى `<المجلد>.previous` والجديد إلى اسمه، ثم يُفتح البرنامج.
  إعادة تسمية لا نسخ، فلا يكون المجلد نصفه إصدار ونصفه آخر، وإن تعذرت الثانية رُدّت الأولى. ملف ما زال مفتوحًا
  يُنتظر عشر ثوانٍ، ثم يُفتح البرنامج كما كان.
- **إذا انتهى الإصدار الجديد بخطأ خلال 30 ثانية** يُعاد المجلد القديم ويُفتح هو. وإلا يُحذف المجلد القديم.
- الملفات التي يضيفها العميل داخل مجلد البرنامج لا تنتقل إلى الجديد: بيانات البرنامج مكانها خارجه.

### كيف يتم التثبيت

المثبّت لا يستطيع استبدال ملفات برنامج ما زال يعمل. لذلك تكتب المكتبة سكربت PowerShell صغيرًا في
`%LOCALAPPDATA%\<اسم البرنامج>-updates\` ينتظر إغلاق البرنامج (حتى دقيقتين)، يشغّل المثبّت، ثم يفتح
البرنامج من جديد. سجل العملية في `update.log` بنفس المجلد، وهو أول ما يُقرأ إن لم يتم التحديث.

إذا كان التثبيت الواحد يحتوي أكثر من برنامج (عميل وخادم مثلًا) أو كانت هناك نافذة أخرى من البرنامج نفسه،
تطلب المكتبة من المستخدم إغلاقها أولًا قبل بدء التثبيت، لأن المثبّت لا يستبدل ملفات ما زالت مفتوحة.

## أدوات النشر والتحزيم

### سكربت الإصدار

النشر اليدوي يخطئ بصمت: اسم ملف لا يطابق، أو إصدار Draft، أو رقم ليس أحدث، فلا يظهر التحديث ولا تظهر أي
رسالة خطأ. السكربت `tools/publish-release.ps1` ينفّذ الخطوات كلها ويتوقف قبل النشر إن اختلّ شرط:

1. يفحص المشروع: لا تعديلات غير محفوظة، رقم الإصدار بصيغة `X.Y.Z`، الوسم عند `HEAD`، لا يوجد إصدار
   بنفس الرقم، والرقم أحدث من آخر إصدار منشور.
2. يشغّل سكربت التحزيم الخاص بالمشروع.
3. يجد المثبّت الناتج وينسخه بالاسم المنشور.
4. ينشئ الإصدار على GitHub ويرفع المثبّت.
5. يقرأ الإصدار كما يقرؤه البرنامج، ويقول أي إصدار سيُعرض على النسخ المثبّتة.

في كل مشروع: انسخ `tools/release.example.ps1` إلى `packaging/release.ps1` واملأ القيم الأربع في أعلاه.
السكربت المشترك يُؤخذ من نسخة desktop-updater المجاورة للمشروع، أو من المسار في `DESKTOP_UPDATER_HOME`.

<div dir="ltr">

```powershell
.\packaging\release.ps1 -DryRun       # كل شيء عدا النشر
.\packaging\release.ps1               # الوسم v<version> يجب أن يكون موجودًا وعند HEAD
.\packaging\release.ps1 -CreateTag    # ينشئ الوسم عند HEAD أولًا
.\packaging\release.ps1 -Required     # إصدار مطلوب: لا «تخطي» لمن لديه نسخة أقدم
.\packaging\release.ps1 -VerifyOnly   # أي إصدار يُعرض الآن على النسخ المثبّتة، ولماذا
```

</div>

«ما الجديد» يُؤخذ من `docs/release-notes/v<version>.md` إن وُجد، وإلا من قائمة الـ commits التي يولّدها
GitHub. يحتاج `git` و `gh`، ويعمل على Windows PowerShell 5.1.

### خطوات التحزيم المشتركة

سكربت التحزيم في كل برنامج يكرر الخطوات نفسها، وكل برنامج كتبها بطريقته. الوحدة `tools/Packaging.psm1` تكتبها
مرة واحدة، ويبقى في سكربت البرنامج ما يخصه وحده: اسمه، ووحداته، وصنفه الرئيسي، ومشغّلاته، وخيارات Java،
ورمز الترقية. تُوجد كما يُوجد سكربت الإصدار: نسخة desktop-updater بجوار المستودع، أو المسار في
`DESKTOP_UPDATER_HOME`.

<div dir="ltr">

```powershell
$updaterHome = $env:DESKTOP_UPDATER_HOME
if (-not $updaterHome) {
    $repository = & git -C $PSScriptRoot rev-parse --show-toplevel 2>$null
    $updaterHome = Join-Path (Split-Path -Parent $repository) 'desktop-updater'
}
Import-Module (Join-Path $updaterHome 'tools/Packaging.psm1') -Force
```

</div>

| الدالة | ما تفعله |
| --- | --- |
| `Find-JPackage -MinimumVersion 25` | تجد JDK فيه jpackage في `JAVA_HOME` أو على PATH أو في `~/.jdks`، وترفض الأقدم |
| `Invoke-MavenBuild -Pom … -Arguments …` | تشغّل Maven (الـ wrapper إن وُجد)، بعد إزالة علامة «للقراءة فقط» من `target` |
| `Get-PomVersion -Pom …` | الإصدار بلا `-SNAPSHOT`، والإصدار الذي يُعطى لـ jpackage (macOS يرفض 0.x فيصير 1.x) |
| `New-JPackageInput -Path … -Item …` | مجلد `--input` جديد لا فيه إلا ما سُمّي |
| `Resolve-PackageType` و `Get-IconExtension` | نوع المثبّت وملف الأيقونة لكل نظام |
| `Find-WiX` | تجد WiX 3 لـ exe و msi وتضعه على PATH |
| `Get-WinInstallerArgument` | خيارات مثبّت Windows بترتيب واحد، مع فحص رمز الترقية |
| `Rename-PlatformPackage` | الاسم المنشور على Linux و macOS: `<الاسم>-<الإصدار>-<النظام>-<المعالج>.<النوع>` |
| `Invoke-Native` | تشغّل برنامجًا وتتوقف إن خرج بغير 0 |

تعمل على Windows PowerShell 5.1 وعلى PowerShell 7 في الأنظمة الثلاثة، واختبارها
`tools/tests/Packaging.Tests.ps1` يعمل في CI على الثلاثة. الـ workflow المشترك لـ Linux و macOS (أعلاه) يجلب
desktop-updater بجوار البناء حين يُعطى `desktop-updater-ref`: الـ commit نفسه المكتوب في `uses:`، لأن الـ workflow
المستدعى لا يعرف الـ commit الذي استُدعي منه.

### مع Claude Code

المجلد `claude-skill/desktop-auto-update` فيه skill يربط المكتبة بأي مشروع حالي أو جديد. انسخه إلى
`~/.claude/skills/` ثم قل لـ Claude: «أضف التحديث التلقائي لهذا البرنامج».

## خارطة الطريق

- تجربة مسار التحديث على جهاز Linux و macOS حقيقي؛ حتى الآن تغطيه الاختبارات ومحاكاة النظام على Windows.

حالة المشروع مسجّلة في [docs/STATUS.md](docs/STATUS.md).

## الترخيص

حقوق النشر (c) 2026 Mohamed Salah. جميع الحقوق محفوظة. الكود منشور ليُقرأ، وليس برنامجًا مفتوح المصدر:
استخدامه أو نسخه أو تعديله أو توزيعه يحتاج إذنًا كتابيًا مسبقًا. انظر [LICENSE](LICENSE). لأسئلة الترخيص افتح
issue في هذا المستودع.

</div>
