package com.voiceagent.automation.model

enum class ActionType(val labelEn: String, val labelAr: String) {
    LAUNCH_APP("Launch Application", "تشغيل تطبيق"),
    OPEN_URL("Open URL in Browser", "فتح رابط في المتصفح"),
    CLICK_ELEMENT("Click UI Element", "الضغط على عنصر"),
    CLICK_COORDINATE("Tap Screen Coordinates", "الضغط على إحداثيات الشاشة"),
    SET_TEXT("Enter Text", "كتابة نص"),
    INJECT_CREDENTIAL("Enter Saved Password", "إدخال كلمة مرور محفوظة"),
    CLEAR_TEXT("Clear Input Field", "مسح حقل النص"),
    WAIT_FOR_ELEMENT("Wait for Element", "انتظار ظهور عنصر"),
    DELAY("Wait Duration", "انتظار زمني"),
    SCROLL_FORWARD("Scroll Down / Forward", "تمرير لأسفل"),
    SCROLL_BACKWARD("Scroll Up / Backward", "تمرير لأعلى"),
    SWIPE("Swipe Gesture", "سحب على الشاشة"),
    PRESS_BACK("Press Back Button", "الرجوع للخلف"),
    PRESS_HOME("Press Home", "الرجوع للرئيسية"),
    PRESS_ENTER("Press Enter / Submit", "الضغط على زر الإدخال (Enter)"),
    CHECK_ELEMENT_EXISTS("Condition: Element Exists", "شرط: فحص وجود عنصر"),
    CHECK_TEXT_EXISTS("Condition: Text Exists", "شرط: فحص وجود نص"),
    SEND_DEEP_LINK("Send Deep Link / Intent", "إرسال رابط تطبيق مباشر"),
    SPEAK_TEXT("Speak Spoken Message", "نطق رسالة صوتية"),
    SHOW_NOTIFICATION("Show Notification", "إظهار إشعار"),
    ASK_CONFIRMATION("Ask User Confirmation", "طلب تأكيد المستخدم"),
    RUN_WORKFLOW("Run Sub-Workflow", "تشغيل مهمة فرعية"),
    SET_INDEXED_RADIO("Set Indexed Device Radio", "ضبط خيار الجهاز بالاسم")
}

enum class StepStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    SKIPPED
}
