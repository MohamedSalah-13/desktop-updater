package com.hamza.updater.fx;

import java.util.Locale;

/** The window's words, in English and Arabic. Kept in code so that packaging has no resource to lose. */
record Texts(boolean rightToLeft, String title, String available, String current, String whatsNew,
             String updateNow, String later, String skip, String openPage, String downloading,
             String megabytes, String starting, String willRestart, String cancel, String upToDate,
             String notInstalled, String checkFailed, String downloadFailed, String installFailed,
             String closeOthers, String requiredHeader, String requiredText, String closeProgram,
             String readyHeader, String installNow, String downloadInstaller, String openedInstaller,
             String openFailed, String ok) {

    static final Texts ENGLISH = new Texts(false,
            "Software update",
            "Version %s of %s is available",
            "You have version %s.",
            "What's new:",
            "Update now",
            "Later",
            "Skip this version",
            "Open the download page",
            "Downloading the update…",
            "%s of %s MB",
            "Starting the installer…",
            "The program will close, install the update and open again.",
            "Cancel",
            "You have the latest version (%s).",
            "This copy was not installed with the installer, so it does not update itself.",
            "Could not check for updates. Check the internet connection and try again.",
            "The update could not be downloaded.",
            "The installer could not be started.",
            "Close these programs first, then press “Update now” again:",
            "Required update: version %s of %s",
            "This update is required. The version you have is no longer supported.",
            "Close the program",
            "Version %s of %s is ready to install",
            "Install now",
            "Download the installer",
            "The installer was downloaded and opened. Finish the installation, then start the program again.",
            "The installer was downloaded, but nothing on this computer opened it. It is here:",
            "OK");

    static final Texts ARABIC = new Texts(true,
            "تحديث البرنامج",
            "يتوفر الإصدار %s من %s",
            // No full stop after the number: in right-to-left text it would land before it.
            "الإصدار الحالي لديك: %s",
            "ما الجديد:",
            "تحديث الآن",
            "لاحقًا",
            "تخطي هذا الإصدار",
            "فتح صفحة التنزيل",
            "جارٍ تنزيل التحديث…",
            "%s من %s ميجابايت",
            "جارٍ بدء التثبيت…",
            "سيُغلق البرنامج ويُثبَّت التحديث ثم يُفتح من جديد.",
            "إلغاء",
            "لديك أحدث إصدار: %s",
            "هذه النسخة لم تُثبَّت من ملف التثبيت، لذلك لا تحدّث نفسها.",
            "تعذّر التحقق من وجود تحديثات. تحقق من اتصال الإنترنت وحاول مرة أخرى.",
            "تعذّر تنزيل التحديث.",
            "تعذّر بدء التثبيت.",
            "أغلق هذه البرامج أولًا ثم اضغط «تحديث الآن» من جديد:",
            "تحديث مطلوب: الإصدار %s من %s",
            "هذا التحديث مطلوب. الإصدار الذي لديك لم يعد مدعومًا.",
            "إغلاق البرنامج",
            "الإصدار %s من %s جاهز للتثبيت",
            "تثبيت الآن",
            "تنزيل المثبّت",
            "تم تنزيل المثبّت وفتحه. أكمل التثبيت ثم شغّل البرنامج من جديد.",
            "تم تنزيل المثبّت، لكن لم يفتحه شيء على هذا الجهاز. تجده هنا:",
            "موافق");

    /** Arabic for {@code ar}, English for everything else. */
    static Texts of(String language) {
        return language != null && language.toLowerCase(Locale.ROOT).startsWith("ar") ? ARABIC : ENGLISH;
    }
}
