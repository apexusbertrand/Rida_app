package rida.pour.les.pros

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import rida.pour.les.pros.agent.ChatService
import rida.pour.les.pros.daily.DailyScheduler
import rida.pour.les.pros.daily.Notifications
import rida.pour.les.pros.data.repo.SettingsRepository
import rida.pour.les.pros.ui.RidaNavHost
import rida.pour.les.pros.ui.theme.RidaTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var chatService: ChatService
    @Inject lateinit var settings: SettingsRepository

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Notifications.canNotify(this)) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            RidaTheme {
                RidaNavHost()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val s = settings.current()
            DailyScheduler.schedule(this@MainActivity, s)
            // Rattrapage : si l'heure du digest est passée et qu'il n'a pas tourné aujourd'hui (téléphone éteint…).
            if (DailyScheduler.isDue(s)) runCatching { chatService.runDaily() }
        }
    }
}
