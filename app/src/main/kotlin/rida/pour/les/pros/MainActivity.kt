package rida.pour.les.pros

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import rida.pour.les.pros.ui.RidaNavHost
import rida.pour.les.pros.ui.theme.RidaTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RidaTheme {
                RidaNavHost()
            }
        }
    }
}
