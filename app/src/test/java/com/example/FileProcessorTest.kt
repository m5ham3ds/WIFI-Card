package com.example

import org.junit.Test
import java.io.File

class FileProcessorTest {

    @Test
    fun extractStrings() {
        val resDir = File("src/main/res")
        val layoutDir = File(resDir, "layout")
        
        val stringMapAr = mutableMapOf<String, String>()
        val stringMapEn = mutableMapOf<String, String>()
        
        var stringCounter = 1
        
        fun addString(arabicValue: String, englishValue: String): String {
            val key = "auto_string_${stringCounter++}"
            stringMapAr[key] = arabicValue
            stringMapEn[key] = englishValue
            return "@string/$key"
        }

        layoutDir.listFiles()?.filter { it.extension == "xml" }?.forEach { file ->
            var content = file.readText()
            
            // Very naive replacements (could be improved, but sufficient just to sweep exact matches)
            val replacements = mapOf(
                "إلغاء الأمر" to "Cancel",
                "حفظ الإعدادات" to "Save Settings",
                "ملف تعريف جهاز جديد" to "New Device Profile",
                "تم تسجيل الدخول بنجاح" to "Logged in successfully",
                "اسم المستخدم أو كلمة المرور خاطئة" to "Invalid username or password",
                "حفظ التعريف" to "Save Profile",
                "عنوان التنبيه" to "Alert Title",
                "هذا هو نص الرسالة التنبيهية لتأكيد العملية أو إظهار الخطأ وتوضيح التفاصيل للتطبيق بشكل ممتاز ومقروء." to "This is the alert message text to confirm standard operational dialogs and display errors clearly.",
                "تأكيد" to "Confirm",
                "إلغاء" to "Cancel",
                "إغلاق" to "Close",
                "جاري الاختبار" to "Testing in progress",
                "فحص بطاقات الهوت سبوت..." to "Testing Hotspot Cards...",
                "الكل: " to "Total: ",
                "إيقاف مؤقت" to "Pause",
                "إلغاء وايقاف" to "Cancel and Stop",
                "رجوع للجلسات" to "Back to Sessions",
                "تصدير النتائج" to "Export Results",
                "الكل" to "All",
                "الناجحة فقط" to "Success Only",
                "الفاشلة فقط" to "Failed Only",
                "لا توجد بيانات سجلات متاحة حالياً" to "No history data available currently",
                "لا توجد أجهزة راوتر مضافة للعمليات" to "No routers added for operations",
                "اختر ملف الراوتر المستهدف" to "Select Target Router Profile",
                "إعدات توليد بطاقات الهوت سبوت" to "Hotspot Card Generator Settings",
                "تقدم الاختبار الجاري:" to "Current Test Progress:",
                "بدء عملية الاختبار" to "Start Testing Operation",
                "إيقاف الاختبار" to "Stop Testing",
                "سجل العمليات المباشر" to "Live Operations Log",
                "مسح" to "Clear",
                "افتراضي" to "Default",
                "تعيين كافتراضي" to "Set as Default",
                "تعديل" to "Edit",
                "حذف" to "Delete",
                "اهلاً بك في WiFi Master Pro" to "Welcome to WiFi Master Pro",
                "هذه البرنامج متخصص في عمل تخمينات بطريقة احترافية و مميز و سرعة لكرت الشبكة اي ( كرت الواي فاي )" to "This app is specialized in professional, distinctive and fast hotspot card testing for WiFi.",
                "لكي تستطيع استخدام الخدمة يجب ان تقوم بادخال كلمة السر لفتح معك البرنامج" to "To use this service, you must enter the password to unlock the app.",
                "ملاحظة هذه الصفحة تظهر فقط عندما تأكون لم تؤكد كلمة السر بعد التأكد ستختفي نهائياً أيضا هناك 3 محاولة فقط لادخال كلمة السر اذا تمت بشكل خاطئ سيقفل البرنامج بل كامل و يطلب منك حذف البرنامج و تثبيته مجددا" to "Note: This page only appears when you haven't confirmed the password. Once confirmed, it disappears permanently. You only have 3 attempts. If exhausted, the app locks out entirely and must be reinstalled.",
                "المطور : MOHAMED" to "Developer: MOHAMED",
                "هنا وسائل التواصل الاجتماعي مثل الفيسبوك و التويتر و التلجرام و الانستغرام و الواتساب و التيك توك" to "Here are social media links like Facebook, Twitter, Telegram, Instagram, WhatsApp, and TikTok.",
                "تم قفل البرنامج بشكل دائم" to "App Locked Permanently",
                "تم اكتشاف محاولة غير مصرح بها للوصول أو استخدام البرنامج.\\nللحفاظ على أمان بياناتك، تم قفل البرنامج بشكل دائم." to "Unauthorized attempt detected.\\nTo secure your data, the application has been locked permanently.",
                "لا يمكن استخدام البرنامج بعد الآن. يرجى حذف التطبيق بالكامل وإعادة تثبيته من المصدر الرسمي." to "The app cannot be used anymore. Please delete and reinstall it from the official source.",
                "حذف التطبيق" to "Delete Application",
                "بعد حذف التطبيق، يرجى إعادة تثبيته من الموقع الرسمي للحصول على نسخة آمنة." to "After deleting, please reinstall from the official site.",
                "تحتاج إلى مساعدة؟\\ntواصل معنا" to "Need Help?\\nContact us",
                "تحتاج إلى مساعدة؟\\nتواصل معنا" to "Need Help?\\nContact us",
                "🔄 جارٍ" to "🔄 In progress",
                "إيقاف" to "Stop",
                "استئناف" to "Resume"
            ).forEach { (ar, en) ->
                if (content.contains("\"$ar\"")) {
                    val key = addString(ar, en)
                    content = content.replace("\"$ar\"", "\"$key\"")
                }
            }
            if(content != file.readText()) {
               file.writeText(content)
            }
        }
        
        // Also fix `fragment_home.xml` specific fields if we missed them
        
        // Now append to values/strings.xml (English)
        val stringsEnFile = File(resDir, "values/strings.xml")
        var enContent = stringsEnFile.readText()
        val enInject = stringMapEn.map { "    <string name=\"${it.key}\">${it.value}</string>" }.joinToString("\n")
        enContent = enContent.replace("</resources>", "$enInject\n</resources>")
        stringsEnFile.writeText(enContent)
        
        // And values-ar/strings.xml
        val stringsArFile = File(resDir, "values-ar/strings.xml")
        var arContent = stringsArFile.readText()
        val arInject = stringMapAr.map { "    <string name=\"${it.key}\">${it.value}</string>" }.joinToString("\n")
        arContent = arContent.replace("</resources>", "$arInject\n</resources>")
        stringsArFile.writeText(arContent)

        // Let's modify arrays.xml to update thread count options from 5 to 100
        val arraysFile = File(resDir, "values/arrays.xml")
        var arraysContent = arraysFile.readText()
        
        // replace preload_pages_entries and values entirely
        val newEntries = (5..100 step 5).map { "<item>$it</item>" }.joinToString("\n        ")
        
        val arraysRegex = Regex("<string-array name=\"preload_pages_entries\">[\\s\\S]*?</string-array>")
        val newArraySt = "<string-array name=\"preload_pages_entries\">\n        $newEntries\n    </string-array>"
        arraysContent = arraysContent.replace(arraysRegex, newArraySt)
        
        val valuesRegex = Regex("<string-array name=\"preload_pages_values\">[\\s\\S]*?</string-array>")
        val newValuesSt = "<string-array name=\"preload_pages_values\">\n        $newEntries\n    </string-array>"
        arraysContent = arraysContent.replace(valuesRegex, newValuesSt)
        
        arraysFile.writeText(arraysContent)
        
        println("SUCCESSFULLY_PROCESSED_STRINGS")
    }
}
