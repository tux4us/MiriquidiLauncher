# SPDX-License-Identifier: GPL-3.0-or-later
# Die App selbst nutzt weder Reflection noch Serialisierungsbibliotheken. WorkManager (Chat-Benachrichtigungen)
# lädt seine Room-Datenbank und die Worker per Reflection; R8 im Vollmodus entfernt deren Konstruktoren sonst
# (Absturz beim Start: NoSuchMethodException androidx.work.impl.WorkDatabase_Impl.<init>).
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class androidx.work.impl.WorkDatabase_Impl { *; }
-keep class * extends androidx.work.ListenableWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}
