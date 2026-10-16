package kr.closeforyou.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.closeforyou.accessibility.AccessibilityRuntime

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = CorePreferences.get(this)
        setContent {
            MaterialTheme {
                CoreSetupScreen(this, preferences)
            }
        }
    }
}

@Composable
private fun CoreSetupScreen(context: Context, preferences: CorePreferences) {
    val settings by preferences.state.collectAsStateWithLifecycle()
    val coreStatus by AccessibilityRuntime.status.collectAsStateWithLifecycle()
    var serviceEnabled by remember { mutableStateOf(isServiceEnabled(context)) }
    var packageInput by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) serviceEnabled = isServiceEnabled(context)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Scaffold { insets ->
        Column(
            modifier = Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("닫아줄게", style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.semantics { heading() })
            Text("TalkBack 사용자를 위한 팝업 접근성 보조 앱 · 개발판")
            Text(
                "현재는 허용한 앱의 팝업 후보를 수집하는 단계입니다. 다른 앱을 자동으로 누르는 기능은 연결하지 않았습니다.",
            )
            Text("화면 정보 사용 안내", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() })
            Text(
                "접근성 서비스를 켜고 아래 수집에 동의하면, 개별 허용한 앱에서 창 변경과 접근성 노드의 글자, 버튼 라벨, 클릭 가능 여부, 화면상 위치를 임시로 읽습니다. 팝업 후보와 민감 화면 차단을 확인하는 데 사용합니다.",
            )
            Text(
                "화면 원문과 이미지는 일반 로그에 기록하지 않으며 서버로 전송하거나 영구 저장하지 않습니다. 이미지 글자 읽기는 아래에서 별도로 동의한 앱에서만 실행합니다. 비밀번호와 민감 화면은 분석을 중단하지만 모든 민감 화면을 알아낸다고 보장할 수 없습니다.",
            )
            Text("언제든 일시정지하거나 동의를 철회할 수 있습니다. 서비스 활성화만으로 앱 분석을 허용하지 않습니다.")
            Row(
                modifier = Modifier.fillMaxWidth().toggleable(
                    value = settings.consentGranted, role = Role.Checkbox,
                    onValueChange = preferences::updateConsent,
                ).padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = settings.consentGranted, onCheckedChange = null)
                Text("허용한 앱의 접근성 화면 정보 수집과 임시 분석에 동의합니다.")
            }
            HorizontalDivider()
            Text("접근성 서비스", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() })
            Text(
                if (serviceEnabled) "시스템 접근성 서비스: 켜짐" else "시스템 접근성 서비스: 꺼짐",
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Text("설정에서 ‘닫아줄게’를 선택하고 서비스를 켜세요. 외부 설치 앱은 기기의 제한된 설정 확인이 필요할 수 있습니다. 설정에서 돌아오면 상태를 다시 확인합니다.")
            Button(onClick = {
                try {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (_: ActivityNotFoundException) {
                    message = "이 기기에서 접근성 설정 화면을 열 수 없습니다. 기기 설정에서 직접 접근성을 찾아 주세요."
                }
            }) { Text("접근성 설정 열기") }
            Text("분석할 앱을 직접 허용", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() })
            Text("기본 허용 앱은 없습니다. 시험용 앱의 패키지 이름을 직접 추가하세요. 허용해도 보호 화면의 차단 규칙은 적용됩니다.")
            OutlinedTextField(
                value = packageInput,
                onValueChange = { packageInput = it },
                label = { Text("허용할 앱 패키지 이름") },
                placeholder = { Text("예: kr.example.testpopup") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                enabled = settings.consentGranted,
                onClick = {
                    message = if (preferences.addAllowedPackage(packageInput)) {
                        packageInput = ""
                        if (preferences.currentSettings().paused) {
                            "앱을 허용했습니다. 분석 시작 버튼을 눌러야 수집합니다."
                        } else "앱을 허용했습니다. 해당 앱에도 화면 분석을 적용합니다."
                    } else "점으로 구분된 올바른 패키지 이름을 입력하세요."
                },
            ) { Text("이 앱 분석 허용") }
            if (settings.allowedPackages.isEmpty()) Text("허용한 앱이 없습니다.")
            Text("이미지 팝업의 한국어 글자 읽기", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() })
            Text("별도 동의 후 아래 앱별로 켤 수 있습니다. 영역을 확인할 수 있는 팝업에서 필요한 경우에만 화면을 캡처해 기기 안에서 글자를 읽습니다. Android 11~13은 전체 화면을 잠시 캡처하므로 팝업 밖 개인정보가 메모리에 포함될 수 있습니다. 팝업 영역으로 자른 뒤 나머지는 폐기하며 이미지나 글자를 저장·전송하지 않습니다.")
            Text("보안 화면, 불명확한 영역, 민감 징후, 읽기 실패에서는 이미지 분석을 보류합니다. 화면 변경이나 조작 시 이전 결과를 취소합니다. 한국어 인식 정확도와 실제 캡처는 실기기 미검증입니다.")
            Row(
                modifier = Modifier.fillMaxWidth().toggleable(
                    value = settings.captureConsentGranted, role = Role.Checkbox,
                    enabled = settings.consentGranted, onValueChange = preferences::updateCaptureConsent,
                ).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = settings.captureConsentGranted, onCheckedChange = null, enabled = settings.consentGranted)
                Text("위 설명을 확인하고 허용 앱의 임시 화면 캡처와 기기 내 한국어 글자 분석에 동의합니다.")
            }
            settings.allowedPackages.sorted().forEach { allowedPackage ->
                Column {
                    Text(allowedPackage)
                    Row(modifier = Modifier.fillMaxWidth().toggleable(
                        value = allowedPackage in settings.captureAllowedPackages,
                        role = Role.Checkbox, enabled = settings.captureConsentGranted,
                        onValueChange = { preferences.setCaptureAllowed(allowedPackage, it) },
                    ).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = allowedPackage in settings.captureAllowedPackages,
                            onCheckedChange = null, enabled = settings.captureConsentGranted)
                        Text("$allowedPackage 이미지 팝업 글자 읽기 허용")
                    }
                    OutlinedButton(onClick = { preferences.removeAllowedPackage(allowedPackage) }) {
                        Text("$allowedPackage 허용 취소")
                    }
                }
            }
            Text(
                when {
                    !settings.consentGranted -> "분석 중지: 수집 동의 필요"
                    settings.paused -> "분석 일시정지 중"
                    settings.allowedPackages.isEmpty() -> "분석 대기: 허용한 앱 없음"
                    !serviceEnabled -> "분석 대기: 접근성 서비스 꺼짐"
                    else -> "허용한 앱의 팝업 후보 수집 준비됨 · 실기기 동작 미검증"
                },
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Button(
                enabled = !settings.paused || (settings.consentGranted && settings.allowedPackages.isNotEmpty() && serviceEnabled),
                onClick = { preferences.setPaused(!settings.paused) },
            ) { Text(if (settings.paused) "허용 앱 분석 시작" else "화면 분석 일시정지") }
            Text("최근 완료된 분석에서 읽은 이미지 글자 영역: ${coreStatus.lastCompletedOcrTextCount}개. 0개이면 이미지 글자가 없거나 분석하지 못한 상태일 수 있습니다.")
            message?.let {
                Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            HorizontalDivider()
            Text("개발 중인 기능", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() })
            Text("팝업 음성 안내, 선택 기억, 버튼을 누르고 시작하는 음성 명령은 아직 사용할 수 없습니다. 현재 개발판은 실제 팝업 닫기를 지원하지 않습니다.")
            Text("팝업 감지 정확도, TalkBack 동시 사용, 제조사별 설정과 실제 음성 출력은 실기기 미검증입니다.")
        }
    }
}

private fun isServiceEnabled(context: Context): Boolean {
    val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
    val expected = ComponentName(context.packageName, "kr.closeforyou.accessibility.CloseForYouAccessibilityService")
    return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { info ->
            val service = info.resolveInfo.serviceInfo
            ComponentName(service.packageName, service.name) == expected
        }
}
