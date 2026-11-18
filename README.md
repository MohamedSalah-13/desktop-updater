# desktop-updater

مكتبة Java صغيرة تضيف لأي برنامج سطح مكتب (JavaFX + jpackage) خاصية: **يوجد إصدار جديد ← نزّله وثبّته**.
تُكتب مرة واحدة هنا وتُستخدم في كل البرامج، بدل إعادة كتابة كود التحديث في كل مشروع.

- تفحص الإصدارات المنشورة على **GitHub Releases** (أو ملف JSON على خادمك للمستودعات الخاصة).
- تُظهر للعميل نافذة بالعربية أو الإنجليزية: الإصدار الجديد، ما الجديد، «تحديث الآن / لاحقًا / تخطي هذا الإصدار».
- تنزّل المثبّت مع شريط تقدّم، وتكمل التنزيل المقطوع من حيث توقف.
- تتحقق من الحجم و **SHA-256** قبل تشغيل أي ملف.
- تغلق البرنامج، تشغّل المثبّت بصمت، ثم تفتح البرنامج من جديد.
- بلا أي اعتماديات: تحتاج `java.base` فقط، وتستخدم JavaFX الموجود في برنامجك. Java 21 فأحدث.

## الاستخدام

`pom.xml`:

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.github.MohamedSalah-13</groupId>
    <artifactId>desktop-updater</artifactId>
    <version>v0.1.0</version>
</dependency>
```

مع `module-info.java` أضف: `requires com.hamza.updater;`

في `start(Stage)`:

```java
AppUpdater updater = AppUpdater.builder("File Transfer")
        .source(ReleaseSource.github("MohamedSalah-13/file-transfer").asset("*-setup.exe"))
        .build();

UpdatePrompt updates = UpdatePrompt.create(updater, stage)
        .language("ar")      // "ar" للعربية، وأي قيمة أخرى للإنجليزية
        .start();            // فحص بعد 8 ثوانٍ من التشغيل ثم كل 6 ساعات

checkButton.setOnAction(e -> updates.checkNow());   // زر «تحقق من التحديثات»
```

هذا كل شيء. رقم إصدار البرنامج لا يُكتب يدويًا: المشغّل الذي يصنعه `jpackage` يعطيه للبرنامج
(`jpackage.app-version`)، والنسخة التي تعمل من الـ IDE ليس لها إصدار فلا يُعرض عليها تحديث.

## شروط النشر

1. المثبّت مصنوع بـ `jpackage` مع `--win-upgrade-uuid` ثابت (ويُفضَّل `--win-per-user-install`).
2. الوسم على GitHub بصيغة `vX.Y.Z` ويطابق إصدار `pom.xml`.
3. الإصدار ليس Draft ولا Pre-release (أو استخدم `.includePreReleases(true)`).
4. ملف المثبّت مرفوع في الإصدار باسم يطابق `.asset("...")`.

وصف الإصدار على GitHub هو ما يراه العميل تحت «ما الجديد».

## خيارات

| الحاجة | الطريقة |
| --- | --- |
| مستودع خاص أو خادم خاص | `ReleaseSource.manifest("https://example.com/app/latest.json")` |
| مثبّت Inno Setup | `.installer(InstallerKind.INNO_SETUP)` |
| إظهار صفحات المثبّت العادية | `.installer(InstallerKind.WIZARD)` |
| برنامج له أيقونة Tray أو طريقة إغلاق خاصة | `UpdatePrompt.onExit(() -> ...)` |
| تغيير اللغة أثناء التشغيل | `updates.language("en")` |
| تغيير فترة الفحص | `updates.checkEvery(Duration.ofHours(12))` |
| بدون نافذة JavaFX | `updater.check()` ثم `download(...)` ثم `install(...)` من خيط خلفي |

ملف الـ manifest:

```json
{
  "version": "2.5.0",
  "url": "https://example.com/downloads/Program-2.5.0-setup.exe",
  "sha256": "9e08356040c651fbcf3134a702854cadfc4eaae15390dd159cb4e4f32dcb2d0e",
  "size": 34853376,
  "notes": "ما الجديد في هذا الإصدار"
}
```

## كيف يتم التثبيت

المثبّت لا يستطيع استبدال ملفات برنامج ما زال يعمل. لذلك تكتب المكتبة سكربت PowerShell صغيرًا في
`%LOCALAPPDATA%\<اسم البرنامج>-updates\` ينتظر إغلاق البرنامج (حتى دقيقتين)، يشغّل المثبّت، ثم يفتح
البرنامج من جديد. سجل العملية في `update.log` بنفس المجلد، وهو أول ما يُقرأ إن لم يتم التحديث.

## تجربة النافذة أثناء التطوير

شغّل البرنامج من الـ IDE مع `-Djpackage.app-version=0.0.1` فيظن أنه نسخة قديمة مثبّتة ويعرض آخر إصدار
حقيقي. اضغط «لاحقًا» — «تحديث الآن» سينزّل المثبّت الحقيقي ويشغّله.

## البناء

```bash
mvn verify
```

## مع Claude Code

المجلد `claude-skill/desktop-auto-update` فيه skill يربط المكتبة بأي مشروع حالي أو جديد. انسخه إلى
`~/.claude/skills/` ثم قل لـ Claude: «أضف التحديث التلقائي لهذا البرنامج».
