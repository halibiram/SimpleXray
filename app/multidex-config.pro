# MultiDex Configuration for Ultimate Performance
# Keep only essential classes in main dex file

# Keep Application class
-keep public class com.simplexray.an.** extends android.app.Application

# Keep Activity classes
-keep public class * extends android.app.Activity
-keep public class * extends androidx.activity.ComponentActivity
-keep public class * extends androidx.appcompat.app.AppCompatActivity

# Keep Service classes
-keep public class * extends android.app.Service
-keep public class * extends android.app.IntentService

# Keep BroadcastReceiver classes
-keep public class * extends android.content.BroadcastReceiver

# Keep ContentProvider classes
-keep public class * extends android.content.ContentProvider

# Keep essential ViewModels
-keep public class * extends androidx.lifecycle.ViewModel

# Keep Compose classes needed at startup
-keep public class androidx.compose.runtime.** { *; }
-keep public class androidx.compose.ui.platform.** { *; }

# Minimize main dex for faster startup
-dontpreverify
