package io.github.smallmiro.teslable.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.smallmiro.teslable.Teslable

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var vin by rememberSaveable { mutableStateOf("5YJS0000000000000") }
                val localName = runCatching { Teslable.localNameFor(vin) }.getOrElse { "VIN 형식 오류" }
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("Teslable ${Teslable.VERSION}", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(value = vin, onValueChange = { vin = it }, label = { Text("VIN") })
                    Text("BLE local name: $localName")
                }
            }
        }
    }
}
