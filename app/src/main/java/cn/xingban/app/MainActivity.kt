package cn.xingban.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import cn.xingban.app.data.StateCodec
import cn.xingban.app.ui.CompanionApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val model: ChatViewModel by viewModels()
    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        model.notify(if (allowed) "麦克风权限已允许，请再次按住说话。" else "未授予麦克风权限，可继续文字聊天。")
    }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                val data = model.exportBackup()
                withContext(Dispatchers.IO) { requireNotNull(contentResolver.openOutputStream(uri)).use { it.write(data.toByteArray(Charsets.UTF_8)) } }
                model.notify("备份已导出，不包含 API Key。请妥善保管其中的聊天和记忆。")
            } catch (_: Exception) { model.notify("导出失败，请重新选择文件位置。") }
        }
    }
    private val restore = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                val raw = withContext(Dispatchers.IO) {
                    requireNotNull(contentResolver.openInputStream(uri)).use {
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = it.read(buffer); if (count < 0) break
                            require(output.size() + count <= StateCodec.MAX_BACKUP_BYTES)
                            output.write(buffer, 0, count)
                        }
                        output.toString("UTF-8")
                    }
                }
                model.restoreBackup(raw)
            } catch (_: Exception) { model.notify("恢复失败：文件损坏、过大或版本不受支持。原有数据已保留。") }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CompanionApp(model, onMicrophone = {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) model.startListening()
                else microphone.launch(Manifest.permission.RECORD_AUDIO)
            }, onExport = { export.launch("星伴备份-${java.time.LocalDate.now()}.json") }, onRestore = { restore.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) })
        }
    }
    override fun onStart() { super.onStart(); model.foreground() }
    override fun onStop() { model.background(); super.onStop() }
}
