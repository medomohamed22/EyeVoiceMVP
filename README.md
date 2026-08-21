# Eye Voice MVP

نسخة أولى تجريبية للتحكم في Android باتجاه الوجه + أوامر صوتية عربية.

## ما الذي يعمل؟
- مؤشر Overlay فوق التطبيقات.
- تحريك المؤشر باستخدام Head Pose من ML Kit.
- أوامر: اضغط / ارجع / الرئيسية / انزل / اطلع.
- تنفيذ Tap وSwipe وBack وHome عبر AccessibilityService.
- Camera + Microphone يعملان في Foreground Service.

## مهم
هذه النسخة ليست Eye Tracking دقيق للبؤبؤ. هي MVP تستخدم Head Pose كبديل سريع.
المرحلة التالية: MediaPipe Face Landmarker/Iris + Calibration 9 نقاط + Snap-to-target.

## التشغيل على الهاتف
1. ثبّت APK.
2. افتح التطبيق.
3. امنح Camera + Microphone.
4. امنح Display over other apps.
5. افتح Accessibility Settings وفعّل Eye Voice MVP.
6. ارجع للتطبيق واضغط "تشغيل التحكم".
7. افتح أي تطبيق آخر.
8. حرّك رأسك قليلًا يمين/يسار وأعلى/أسفل.
9. قل "اضغط" عندما يكون المؤشر فوق الهدف.

## بناء APK على Codemagic
- ارفع المشروع إلى GitHub.
- في Codemagic: Add application.
- اربط GitHub repository.
- اختر codemagic.yaml.
- شغّل workflow: android-debug.
- بعد نجاح البناء نزّل app-debug.apk من Artifacts.

## ملاحظات أمنية وخصوصية
- الكاميرا تُعالج محليًا في هذا المشروع.
- لا يوجد رفع فيديو أو صوت إلى سيرفر من كود التطبيق نفسه.
- SpeechRecognizer قد يعتمد على خدمة التعرف الموجودة على الجهاز، وقد تختلف طريقة المعالجة حسب الجهاز/مزود الخدمة.
