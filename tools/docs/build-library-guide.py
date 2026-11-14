#!/usr/bin/env python3
"""Build the offline library guide from the maintained manual and public Kotlin declarations.

Paths are resolved from {{PATHS_FILE}}. Run from any directory; no third-party packages are needed.
This is a documentation assembler, not a Kotlin compiler. Unknown example parameters fail the build.
"""

import html
import pathlib
import re
import subprocess
from collections import Counter
from html.parser import HTMLParser

ROOT = pathlib.Path(__file__).resolve().parents[2]
PATHS = dict(re.findall(r"\| `(\w+)` \| `([^`]+)` \|", (ROOT / "docs/PATHS.md").read_text()))


def path(key):
    value = PATHS[key]
    while "{{" in value:
        value = re.sub(r"\{\{(\w+)\}\}", lambda m: PATHS[m[1]], value)
    return ROOT / value


MD = path("LIB_DOCS_DIR")
HTML = path("LIB_DOCS_HTML_DIR")
SDK = ROOT / "sdk/src/commonMain/kotlin/dev/mirolab/teslable"
DOMAIN = ROOT / "domain/src/commonMain/kotlin/dev/mirolab/teslable"
NAME = "11-lib-user-guide"
CHAPTERS = [
    "11-lib-overview", "12-lib-getting-started", "13-lib-pairing", "14-lib-reading-state",
    "15-lib-commands", "16-lib-errors", "17-lib-platform-notes", "18-lib-troubleshooting",
    "19-lib-architecture",
]
REV = subprocess.check_output(["git", "rev-parse", "--short", "HEAD"], cwd=ROOT, text=True).strip()
BT = chr(96)


def tr(ko, en, ja):
    return {"ko": ko, "en": en, "ja": ja}


TEXT = {
    "title": tr("Tesla BLE KMP 사용 가이드", "Tesla BLE KMP User Guide", "Tesla BLE KMP 利用ガイド"),
    "intro": tr(
        "소스 빌드부터 키 등록, 상태 조회, 모든 제어 API까지. 앱을 만드는 데 필요한 설명과 예제를 한 문서에 모았습니다.",
        "From a source build to key enrollment, state queries and every control API. Instructions and examples for app developers in one document.",
        "ソースビルドからキー登録、状態取得、すべての制御 API まで。アプリ開発に必要な説明と例を一つの文書にまとめました。"),
    "snapshot": tr("문서 기준 커밋", "Source revision", "文書の基準コミット"),
    "status": tr("M5 구현 기준 · Android 12+ · iOS 16+", "M5 implementation · Android 12+ · iOS 16+", "M5 実装時点 · Android 12+ · iOS 16+"),
    "offline": tr("이 파일은 스타일과 스크립트를 포함해 오프라인으로 열 수 있습니다. 언어 전환에는 다른 언어의 파일이 필요합니다.",
                  "Styles and scripts are embedded for offline use. Switching languages requires the other language files.",
                  "スタイルとスクリプトを含むためオフラインで開けます。言語切り替えには他の言語のファイルが必要です。"),
    "nav": tr("목차", "Contents", "目次"),
    "setup": tr("설치와 프로젝트 설정", "Installation and project setup", "インストールとプロジェクト設定"),
    "api": tr("메서드별 API 레퍼런스", "Method-by-method API reference", "メソッド別 API リファレンス"),
    "models": tr("클래스와 반환 데이터 구조", "Classes and returned data structures", "クラスと戻り値のデータ構造"),
    "schemas": tr("getState 전체 필드 스키마", "Full getState field schemas", "getState の全フィールドスキーマ"),
    "find": tr("API 검색: 클래스, 메서드, 파라미터", "Find an API: class, method, parameter", "API 検索: クラス、メソッド、パラメーター"),
    "clear": tr("검색 지우기", "Clear search", "検索を消去"),
    "print": tr("인쇄 / PDF 저장", "Print / save PDF", "印刷 / PDF 保存"),
    "parameter": tr("파라미터", "Parameter", "パラメーター"),
    "type": tr("타입 / 기본값", "Type / default", "型 / 初期値"),
    "meaning": tr("의미 · 단위 · 허용값", "Meaning, units and accepted values", "意味・単位・指定可能な値"),
    "example": tr("호출 예제", "Call example", "呼び出し例"),
    "returns": tr("반환값", "Return value", "戻り値"),
    "required": tr("필수", "required", "必須"),
    "none": tr("파라미터 없음", "No parameters", "パラメーターなし"),
    "details": tr("기능 설명과 사용 절차", "Feature details and call order", "機能の説明と呼び出し手順"),
    "results": tr("반환 필드와 오류 처리", "Returned fields and error handling", "戻り値のフィールドとエラー処理"),
    "fragment": tr(
        "아래 코드는 개별 호출 예제입니다. ble, vehicle, key, vin은 시작하기 절차에서 얻은 객체이며 입력은 설명용입니다. 각 호출은 독립된 예제로, 연속 실행할 스크립트가 아닙니다. suspend 호출은 코루틴 안에서 실행하세요. import: dev.mirolab.teslable.*, dev.mirolab.teslable.model.*, dev.mirolab.teslable.port.*, kotlin.time.Duration.Companion.seconds. 일정 타입은 com.tesla.generated.carserver.common.*입니다.",
        "These are individual call examples. ble, vehicle, key and vin come from the getting-started flow; inputs are illustrative. Each call is independent, not a script to execute in sequence. Run suspend calls in a coroutine. Imports: dev.mirolab.teslable.*, dev.mirolab.teslable.model.*, dev.mirolab.teslable.port.*, kotlin.time.Duration.Companion.seconds. Schedule types are in com.tesla.generated.carserver.common.*.",
        "以下は個別の呼び出し例です。ble、vehicle、key、vin は開始手順で取得したオブジェクトで、入力値は説明用です。連続実行するスクリプトではありません。suspend 関数はコルーチン内で実行します。import: dev.mirolab.teslable.*、dev.mirolab.teslable.model.*、dev.mirolab.teslable.port.*、kotlin.time.Duration.Companion.seconds。スケジュール型は com.tesla.generated.carserver.common.* にあります。"),
    "scope": tr(
        "대상은 앱이 :sdk를 통해 사용하는 공개 API와 입력·반환 모델입니다.",
        "Scope: the public APIs and input/result models an app uses through :sdk.",
        "対象は :sdk を通じてアプリが使う公開 API と入力・戻り値モデルです。"),
}


def t(key, lang):
    return TEXT[key][lang]


def e(value):
    return html.escape(str(value), quote=True)


def code(value, language="kotlin"):
    return '<pre><code class="language-' + language + '">' + html.escape(value, quote=False) + "</code></pre>"


def strip_comments(source):
    return re.sub(r"/\*.*?\*/|//[^\n]*", "", source, flags=re.S)


def close_paren(source, start):
    depth = 0
    for i in range(start, len(source)):
        if source[i] == "(":
            depth += 1
        elif source[i] == ")":
            depth -= 1
            if not depth:
                return i
    raise ValueError("Unclosed declaration")


def split_params(source):
    parts, start, depth = [], 0, 0
    for i, char in enumerate(source):
        if char in "(<[":
            depth += 1
        elif char in ")>]":
            depth -= 1
        elif char == "," and not depth:
            parts.append(source[start:i].strip())
            start = i + 1
    parts.append(source[start:].strip())
    return [p for p in parts if p]


def functions(file, owner, receiver):
    source = strip_comments(file.read_text())
    out = []
    for match in re.finditer(r"public\s+(?:(?:expect|suspend)\s+)*fun\s+([\w.]+)\s*\(", source):
        start = match.end() - 1
        end = close_paren(source, start)
        result = re.match(r"\s*:\s*([^\n={]+)", source[end + 1:])
        result = result[1].strip() if result else "Unit"
        params = []
        for param in split_params(source[start + 1:end]):
            name, typ = param.split(":", 1)
            typ, sep, default = typ.strip().partition(" = ")
            params.append((name.strip(), typ.strip(), default.strip() if sep else None))
        signature = re.sub(r"\s+", " ", source[match.start():end + 1]).replace(", )", ")")
        out.append(dict(owner=owner, receiver=receiver, name=match[1], params=params,
                        returns=result, signature=signature + ": " + result))
    return out


APIS = functions(SDK / "Vehicle.kt", "Vehicle", "vehicle")
assert len(APIS) == 73, "Review Vehicle API coverage when adding methods"
for file, owner, receiver in [
    (SDK / "TeslaBle.kt", "TeslaBle", "ble"),
    (SDK / "TeslaBleConfig.kt", "TeslaBleConfig", "TeslaBleConfig"),
    (SDK / "Timeouts.kt", "Timeouts", "Timeouts"),
    (SDK / "KeyFormFactors.kt", "platformKeyFormFactor", ""),
    (DOMAIN / "port/VehicleKeyStore.kt", "VehicleKeyStore", "ble.keys"),
    (DOMAIN / "model/Vin.kt", "Vin", "vin"),
    (DOMAIN / "model/PublicKeyBytes.kt", "PublicKeyBytes", "key.publicKey"),
    (DOMAIN / "model/VehicleError.kt", "VehicleError", ""),
    (DOMAIN / "port/TeslaLogger.kt", "TeslaLogger", "logger"),
    (DOMAIN / "port/SessionCache.kt", "SessionCache", "cache"),
    (DOMAIN / "port/EcdhPrivateKey.kt", "VehicleKey", "key"),
]:
    APIS.extend(functions(file, owner, receiver))

# Every parameter has an explicit sample and an explanation. No guessed placeholders.
PARAMS = {
    "timeout": ("10.seconds", tr("호출 전체 시간 제한. null이면 설정 기본값. 명령 5초, 핸드셰이크 20초, 연결 30초, 페어링 60초.",
        "Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.",
        "呼び出し全体の制限時間。null は設定値: コマンド5秒、ハンドシェイク20秒、接続30秒、ペアリング60秒。")),
    "domains": ("setOf(VehicleDomain.VCSEC, VehicleDomain.INFOTAINMENT)", tr("시작할 도메인 집합. ALL은 VCSEC와 INFOTAINMENT.",
        "Domains to establish. ALL contains VCSEC and INFOTAINMENT.", "確立するドメインの集合。ALL は VCSEC と INFOTAINMENT。")),
    "category": ("StateCategory.CHARGE", tr("요청당 하나. 12개 값과 반환 필드는 상태 조회 절 참고.", "One category per request. All 12 values and fields are in Reading state.", "1リクエストに1種類。12種類の値と戻り値は状態取得の章を参照。")),
    "publicKey": ("key.publicKey", tr("65바이트 비압축 P-256 공개키. 0x04 || X(32) || Y(32).", "65-byte uncompressed P-256 public key: 0x04 || X(32) || Y(32).", "65バイトの非圧縮 P-256 公開鍵: 0x04 || X(32) || Y(32)。")),
    "domain": ("VehicleDomain.INFOTAINMENT", tr("VCSEC 또는 INFOTAINMENT. 페어링 완료는 INFOTAINMENT로 확인.", "VCSEC or INFOTAINMENT. Verify enrollment using INFOTAINMENT.", "VCSEC または INFOTAINMENT。登録完了は INFOTAINMENT で確認。")),
    "role": ("KeyRole.OWNER", tr("등록할 키 역할. OWNER가 기본. 전체 값은 모델 목록 참고.", "Role for the enrolled key; default OWNER. See models for every value.", "登録するキーの役割。初期値は OWNER。全値はモデル一覧を参照。")),
    "formFactor": ("platformKeyFormFactor()", tr("키 형태. 기본 Android=ANDROID_DEVICE, iOS=IOS_DEVICE, JVM=CLOUD_KEY.", "Key form factor. Platform default: Android=ANDROID_DEVICE, iOS=IOS_DEVICE, JVM=CLOUD_KEY.", "キーの種類。既定値: Android=ANDROID_DEVICE、iOS=IOS_DEVICE、JVM=CLOUD_KEY。")),
    "levels": ("mapOf(SeatPosition.FRONT_LEFT to SeatClimateLevel.HIGH)", tr("좌석별 히터 단계. OFF/LOW/MED/HIGH. 빈 맵도 전송하며 좌석 선언 순서로 정렬.", "Heater levels per seat: OFF/LOW/MED/HIGH. Empty maps are sent; declaration order is used.", "座席別ヒーターレベル: OFF/LOW/MED/HIGH。空のマップも送信し、座席の宣言順に並べます。")),
    "driverCelsius": ("22.0f", tr("운전석 설정 온도(섭씨). 라이브러리 범위 검사 없음.", "Driver temperature in Celsius. No local range check.", "運転席の設定温度(摂氏)。ローカルの範囲検証なし。")),
    "passengerCelsius": ("21.5f", tr("조수석 설정 온도(섭씨). 라이브러리 범위 검사 없음.", "Passenger temperature in Celsius. No local range check.", "助手席の設定温度(摂氏)。ローカルの範囲検証なし。")),
    "level": ("SeatClimateLevel.LOW", tr("좌석 쿨러: OFF/LOW/MED/HIGH. 선루프의 Int level은 절대 위치 값이며 범위를 검사하지 않음.", "Seat cooler: OFF/LOW/MED/HIGH. The sunroof Int level is an absolute position with no local range check.", "シートクーラー: OFF/LOW/MED/HIGH。サンルーフの Int level は絶対位置で、範囲検証なし。")),
    "seat": ("SeatPosition.FRONT_RIGHT", tr("쿨러는 FRONT_LEFT/FRONT_RIGHT만 허용. 다른 좌석은 InvalidArgument.", "Cooler accepts only FRONT_LEFT/FRONT_RIGHT; otherwise InvalidArgument.", "クーラーは FRONT_LEFT/FRONT_RIGHT のみ。それ以外は InvalidArgument。")),
    "seats": ("listOf(SeatPosition.FRONT_LEFT, SeatPosition.FRONT_RIGHT)", tr("자동 공조는 UNKNOWN/FRONT_LEFT/FRONT_RIGHT만 유지. 나머지는 오류 없이 제외.", "Auto climate retains UNKNOWN/FRONT_LEFT/FRONT_RIGHT and silently drops others.", "自動空調は UNKNOWN/FRONT_LEFT/FRONT_RIGHT のみ保持し、他はエラーなく除外。")),
    "enabled": ("true", tr("true: 켜기/활성화, false: 끄기/비활성화.", "true enables; false disables.", "true で有効、false で無効。")),
    "manualOverride": ("true", tr("차량에 보낼 수동 재정의 플래그.", "Manual override flag sent to the vehicle.", "車両へ送る手動オーバーライドのフラグ。")),
    "mode": ("ClimateKeeperMode.DOG", tr("OFF/ON/DOG/CAMP.", "OFF/ON/DOG/CAMP.", "OFF/ON/DOG/CAMP。")),
    "fanOnly": ("true", tr("과열 방지에 팬만 사용하도록 요청.", "Request fan-only cabin overheat protection.", "車内過熱保護でファンのみの使用を要求。")),
    "temperature": ("CabinOverheatProtectionTemperature.HIGH", tr("UNSPECIFIED/LOW/MEDIUM/HIGH. 단계이며 섭씨 값이 아님. UNSPECIFIED를 끄기로 해석하지 않음.", "UNSPECIFIED/LOW/MEDIUM/HIGH. A level, not Celsius; UNSPECIFIED does not mean off.", "UNSPECIFIED/LOW/MEDIUM/HIGH。摂氏ではなく段階。UNSPECIFIED はオフを意味しません。")),
    "percent": ("80", tr("충전 한도(%). 라이브러리 범위 검사 없이 차량에 전달.", "Charge limit, percent. Forwarded without a local range check.", "充電上限(%)。ローカルの範囲検証なしで車両へ送信。")),
    "amps": ("16", tr("충전 전류(A). 라이브러리 범위 검사 없음.", "Charge current in amperes. No local range check.", "充電電流(A)。ローカルの範囲検証なし。")),
    "minutesAfterMidnight": ("120", tr("차량 현지 자정 이후 분. 120=02:00. 범위 검사 없음.", "Minutes after vehicle-local midnight; 120=02:00. No local range check.", "車両の現地時刻の午前0時からの分。120=02:00。範囲検証なし。")),
    "departAtMinutes": ("480", tr("차량 현지 자정 이후 분. 480=08:00. 0..1440(양끝 포함).", "Minutes after vehicle-local midnight; 480=08:00. Range 0..1440 inclusive.", "車両の現地時刻の午前0時からの分。480=08:00。0..1440(両端を含む)。")),
    "offPeakEndMinutes": ("360", tr("비혼잡 시간 종료: 차량 현지 자정 이후 분. 360=06:00. 검사 없음.", "Off-peak end, minutes after vehicle-local midnight; 360=06:00. No local check.", "オフピーク終了: 車両の現地時刻の午前0時からの分。360=06:00。検証なし。")),
    "preconditioning": ("ChargingPolicy.ALL_DAYS", tr("OFF/ALL_DAYS/WEEKDAYS. OFF면 해당 하위 설정을 보내지 않음.", "OFF/ALL_DAYS/WEEKDAYS. OFF omits that sub-setting.", "OFF/ALL_DAYS/WEEKDAYS。OFF は該当の下位設定を省略。")),
    "offPeak": ("ChargingPolicy.WEEKDAYS", tr("OFF/ALL_DAYS/WEEKDAYS. OFF면 해당 하위 설정을 보내지 않음.", "OFF/ALL_DAYS/WEEKDAYS. OFF omits that sub-setting.", "OFF/ALL_DAYS/WEEKDAYS。OFF は該当の下位設定を省略。")),
    "id": ("1_700_000_000L", tr("제거할 일정의 id(Long, wire uint64). 조회한 일정의 실제 id 사용.", "Schedule id to remove (Long, wire uint64). Use the actual id from a query.", "削除するスケジュールの id(Long、wire uint64)。取得した実際の id を使用。")),
    "home": ("true", tr("집으로 분류된 일정을 제거할지.", "Whether to remove schedules classified as home.", "自宅に分類されたスケジュールを削除するか。")),
    "work": ("false", tr("직장으로 분류된 일정을 제거할지.", "Whether to remove schedules classified as work.", "職場に分類されたスケジュールを削除するか。")),
    "other": ("false", tr("집/직장이 아닌 일정을 제거할지.", "Whether to remove schedules classified as neither home nor work.", "自宅・職場以外のスケジュールを削除するか。")),
    "pin": ('"1234"', tr("설명용 PIN. 정확히 ASCII 숫자 4자리. 실제 PIN을 코드나 로그에 남기지 않음.", "Illustrative PIN. Exactly four ASCII digits. Never keep a real PIN in code or logs.", "説明用 PIN。ASCII 数字で正確に4桁。実際の PIN をコードやログへ残さないこと。")),
    "volume": ("5.5f", tr("0..10(양끝 포함). NaN/무한대 거부.", "0..10 inclusive. NaN and infinities are rejected.", "0..10(両端を含む)。NaN と無限大は拒否。")),
    "delaySeconds": ("7200", tr("소프트웨어 업데이트까지 지연(초). 7200=2시간. 검사 없음.", "Update delay in seconds; 7200=2 hours. No local check.", "更新までの遅延(秒)。7200=2時間。検証なし。")),
    "vin": ("vin", tr("Vin.parseOrNull(사용자 입력)으로 검증한 VIN 객체.", "VIN object validated using Vin.parseOrNull(user input).", "Vin.parseOrNull(ユーザー入力) で検証した VIN オブジェクト。")),
    "key": ("key", tr("키 핸들. null이면 인증 없는 조회만 가능.", "Key handle; null permits unauthenticated queries only.", "キーハンドル。null は認証不要の取得のみ。")),
    "target": ("null", tr("scan에서 얻은 광고. null이면 connect가 스캔.", "Advertisement obtained from scan. null makes connect scan.", "scan で取得した広告。null は connect がスキャン。")),
    "options": ("ConnectOptions(timeout = 30.seconds)", tr("연결 옵션. timeout은 스캔·연결·구독 전체.", "Connection options. timeout covers scan, open and subscription.", "接続オプション。timeout はスキャン・接続・購読の全体。")),
    "platform": ("PlatformContext(context)", tr("Android: PlatformContext(context). iOS/JVM: PlatformContext().", "Android: PlatformContext(context). iOS/JVM: PlatformContext().", "Android: PlatformContext(context)。iOS/JVM: PlatformContext()。")),
    "config": ("TeslaBleConfig()", tr("로거, 캐시, 제한 시간 설정. 기본값은 설정 표 참고.", "Logger, cache and time limits; defaults are in the configuration table.", "ロガー、キャッシュ、制限時間。初期値は設定表を参照。")),
    "logger": ("TeslaLogger.NoOp", tr("기본 로그 없음. DebugLogger()는 개발용.", "No logging by default. DebugLogger() is for development.", "既定ではログなし。DebugLogger() は開発用。")),
    "alias": ('"teslable-default"', tr("[A-Za-z0-9._-]{1,64}; . 및 .. 금지. 기본 teslable-default.", "[A-Za-z0-9._-]{1,64}; neither . nor ... Default teslable-default.", "[A-Za-z0-9._-]{1,64}。. と .. は不可。既定は teslable-default。")),
    "policy": ("KeyPolicy.PREFER_HARDWARE", tr("PREFER_HARDWARE/REQUIRE_HARDWARE/SOFTWARE_ONLY.", "PREFER_HARDWARE/REQUIRE_HARDWARE/SOFTWARE_ONLY.", "PREFER_HARDWARE/REQUIRE_HARDWARE/SOFTWARE_ONLY。")),
    "accessibility": ("KeyAccessibility.WHEN_UNLOCKED", tr("WHEN_UNLOCKED/AFTER_FIRST_UNLOCK. 기존 키는 변경하지 않음. Android/JVM은 무시.", "WHEN_UNLOCKED/AFTER_FIRST_UNLOCK. Existing keys are unchanged. Ignored on Android/JVM.", "WHEN_UNLOCKED/AFTER_FIRST_UNLOCK。既存キーは変更せず、Android/JVM は無視。")),
    "seconds": ("10.0", tr("양의 유한 초(Double). 0 이하·NaN·무한이면 IllegalArgumentException.", "Positive finite seconds (Double). Nonpositive, NaN or infinity throws IllegalArgumentException.", "正の有限秒(Double)。0以下・NaN・無限大は IllegalArgumentException。")),
    "value": ("vinText", tr("사용자 VIN 입력 문자열. 17자 형식 검증, 대문자 정규화. 잘못되면 null.", "User-entered VIN string. Checks 17-character format, normalizes uppercase; invalid input returns null.", "ユーザー入力の VIN 文字列。17文字を検証し大文字化。不正な入力は null。")),
    "level:LogLevel": ("LogLevel.INFO", tr("DEBUG/INFO/WARN/ERROR.", "DEBUG/INFO/WARN/ERROR.", "DEBUG/INFO/WARN/ERROR。")),
    "tag": ('"my-app"', tr("로그 태그.", "Log tag.", "ログタグ。")),
    "message": ('{ "Connection changed" }', tr("지연 평가되는 로그 문자열. 비밀값을 포함하지 않음.", "Lazily evaluated message. Do not include secrets.", "遅延評価するログ文字列。秘密情報を含めないこと。")),
    "keyId": ("keyId", tr("클라이언트 공개키의 식별자. 이 키로 만든 캐시만 로드.", "Client public-key identifier. Load only the cache for that key.", "クライアント公開鍵の識別子。このキーのキャッシュのみロード。")),
    "entries": ("emptyList()", tr("저장할 세션 스냅샷 목록. 빈 목록은 사실상 삭제.", "Session snapshots to store; an empty list effectively deletes.", "保存するセッションスナップショット。空のリストは実質的に削除。")),
    "peer": ("peerPublicKey", tr("ECDH 상대 공개키. 핸드셰이크 내부용이며 앱은 직접 호출할 필요 없음.", "Peer ECDH public key. Used by the handshake; apps need not call this.", "ECDH の相手の公開鍵。ハンドシェイク用で、アプリが直接呼ぶ必要なし。")),
}
for name, sample in [("commandTimeoutSeconds", "5.0"), ("handshakeTimeoutSeconds", "20.0"),
                     ("connectTimeoutSeconds", "30.0"), ("pairingTimeoutSeconds", "60.0")]:
    PARAMS[name] = (sample, PARAMS["seconds"][1])


def parameter(param, api=None):
    name, typ, default = param
    if name == "timeout" and api and api["name"] == "millisecondsOf":
        return "10.seconds", tr(
            "변환할 박싱된 Duration?. null이면 -1을 반환.",
            "Boxed Duration? to convert. null returns -1.",
            "変換するボックス化された Duration?。null は -1 を返します。")
    if name == "schedule":
        cls = typ
        sample = (
            'ChargeSchedule(id = 1_700_000_000L, name = "Home", days_of_week = 127, '
            'start_enabled = true, start_time = 120, enabled = true)'
            if cls == "ChargeSchedule" else
            'PreconditionSchedule(id = 2L, name = "Work", days_of_week = 62, precondition_time = 450, enabled = true)'
        )
        return sample, tr("Wire 일정 객체. 필드는 그대로 전송. 모든 생성자 필드와 Swift 예제는 플랫폼 절 참고.",
                         "Wire schedule object, sent as supplied. See Platform notes for every constructor field and Swift examples.",
                         "Wire のスケジュールオブジェクトをそのまま送信。全コンストラクターフィールドと Swift 例はプラットフォームの章を参照。")
    if name == "level" and typ == "Int":
        return "0", tr("선루프의 절대 위치 값. 0도 전송. 로컬 범위 검사 없음.",
                       "Absolute sunroof position. Zero is sent too. No local range check.",
                       "サンルーフの絶対位置。0も送信。ローカルの範囲検証なし。")
    if name == "level" and typ == "LogLevel":
        return PARAMS["level:LogLevel"]
    if name == "timeout" and typ == "Duration?":
        return "10.seconds", PARAMS[name][1]
    if name not in PARAMS:
        raise ValueError("Missing documented example parameter: " + name)
    return PARAMS[name]


def related(api):
    name = api["name"]
    if api["owner"] == "Vehicle":
        if name in ("vehicleStatus", "getState", "keySummary", "listKeys", "sessionInfo"):
            return "ch14"
        if name in ("addKeyRequest", "addKey", "removeKey"):
            return "ch13"
        if name == "disconnect":
            return "ch12"
        return "ch15"
    return {"VehicleKeyStore": "ch13", "VehicleKey": "ch13", "TeslaBleConfig": "ch17",
            "Timeouts": "ch17", "VehicleError": "ch16", "TeslaLogger": "ch18",
            "SessionCache": "ch17"}.get(api["owner"], "ch12")


def call_example(api):
    owner, name, receiver = api["owner"], api["name"], api["receiver"]
    if "." in name:
        call = "error." + name.split(".")[-1]
    elif name == "create":
        call = "TeslaBle.create"
    elif name == "parseOrNull":
        call = "Vin.parseOrNull"
    elif name == "requireValidAlias":
        call = "VehicleKeyStore.requireValidAlias"
    else:
        call = (receiver + "." if receiver else "") + name
    args = ", ".join(n + " = " + parameter((n, ty, d), api)[0] for n, ty, d in api["params"])
    result = call + "(" + args + ")"
    if api["returns"].startswith("Flow<"):
        return result + ".collect { event ->\n    // Handle each event in the UI.\n}"
    return ("val result = " if api["returns"] != "Unit" else "") + result


def result_text(api, lang):
    ret, name = api["returns"], api["name"]
    special = {
        "startSession": tr(
            "Success(Unit): 요청한 도메인의 인증 준비 완료(캐시 복원도 성공). Failure(error): RequiresKey/KeyNotPaired/Locked/Timeout 등. 기본 제한 시간은 handshakeTimeout.",
            "Success(Unit): requested domains are ready for authentication, including cache restores. Failure(error): RequiresKey/KeyNotPaired/Locked/Timeout, etc. Uses handshakeTimeout by default.",
            "Success(Unit): 要求したドメインの認証準備完了(キャッシュ復元を含む)。Failure(error): RequiresKey/KeyNotPaired/Locked/Timeout など。既定の制限時間は handshakeTimeout。"),
        "delete": tr(
            "Success(Unit): 로컬 키 저장소에서 삭제. 원래 없어도 성공. 차량 등록 제거는 별도로 removeKey 사용. Failure(error): 키 저장소 오류.",
            "Success(Unit): removed from the local key store; an absent key also succeeds. Remove vehicle enrollment separately with removeKey. Failure(error): key-store error.",
            "Success(Unit): ローカルキーストアから削除。存在しなくても成功。車両登録の削除は別途 removeKey。Failure(error): キーストアエラー。"),
        "toByteArray": tr(
            "ByteArray 사본. Vin은 마스킹되지 않은 VIN ASCII 17바이트(로그 금지), PublicKeyBytes는 공개키 65바이트.",
            "A ByteArray copy: Vin gives 17 unmasked VIN ASCII bytes (never log them); PublicKeyBytes gives 65 public-key bytes.",
            "ByteArray のコピー。Vin はマスクしていない VIN の ASCII 17バイト(ログ禁止)、PublicKeyBytes は公開鍵65バイト。"),
        "VehicleError.shouldRetry": tr(
            "!mayHaveSucceeded && temporary. true여도 키 저장소 호출과 Locked 핸드셰이크를 자동 재시도하지는 않음.",
            "!mayHaveSucceeded && temporary. A true value does not make the library retry key-store calls or a Locked handshake.",
            "!mayHaveSucceeded && temporary。true でもキーストアや Locked のハンドシェイクは自動再試行しません。"),
        "VehicleError.toResult": tr(
            "mayHaveSucceeded=true이면 Uncertain(this), 아니면 Failure(this).",
            "mayHaveSucceeded=true gives Uncertain(this); otherwise Failure(this).",
            "mayHaveSucceeded=true は Uncertain(this)、それ以外は Failure(this)。"),
        "requireValidAlias": tr(
            "유효하면 Unit. 형식 위반은 IllegalArgumentException(프로그래밍 오류).",
            "Unit when valid; an invalid format throws IllegalArgumentException (a programming error).",
            "有効なら Unit。不正な形式は IllegalArgumentException(プログラミングエラー)。"),
        "platformKeyFormFactor": tr(
            "KeyFormFactor. Android=ANDROID_DEVICE, iOS=IOS_DEVICE, JVM=CLOUD_KEY.",
            "KeyFormFactor: Android=ANDROID_DEVICE, iOS=IOS_DEVICE, JVM=CLOUD_KEY.",
            "KeyFormFactor。Android=ANDROID_DEVICE、iOS=IOS_DEVICE、JVM=CLOUD_KEY。"),
        "default": tr(
            "TeslaBleConfig. 지정한 logger 외에는 기본 설정 사용. commandTimeout=5초, commandLifetime=5초, handshakeTimeout=20초, connectTimeout=30초, pairingTimeout=60초, sessionCache=null.",
            "TeslaBleConfig with the supplied logger and other defaults: commandTimeout=5 s, commandLifetime=5 s, handshakeTimeout=20 s, connectTimeout=30 s, pairingTimeout=60 s, sessionCache=null.",
            "指定の logger と他の初期値を使う TeslaBleConfig。commandTimeout=5秒、commandLifetime=5秒、handshakeTimeout=20秒、connectTimeout=30秒、pairingTimeout=60秒、sessionCache=null。"),
        "withSeconds": tr(
            "TeslaBleConfig. 초 인자는 양의 유한 Double이어야 하며 위반하면 IllegalArgumentException. commandLifetime은 5초 유지, 플랫폼 기본 캐시 사용.",
            "TeslaBleConfig. Seconds must be positive finite Doubles, otherwise IllegalArgumentException. commandLifetime stays 5 s; uses the platform cache.",
            "TeslaBleConfig。秒は正の有限 Double。それ以外は IllegalArgumentException。commandLifetime は5秒のまま、キャッシュはプラットフォームの既定。"),
    }
    if name in special:
        return special[name][lang]
    if name in ("disconnect", "close"):
        return tr(
            "Unit. 멱등 정리: 세션 캐시 저장, 세션 키 제거, 링크 해제. close는 모든 연결을 정리하며 닫힌 TeslaBle은 재사용하지 않음.",
            "Unit. Idempotent cleanup: save cache, wipe session keys, release links. close cleans up all connections; a closed TeslaBle cannot be reused.",
            "Unit。冪等な後始末: キャッシュ保存、セッション鍵消去、リンク解放。close は全接続を終了し、閉じた TeslaBle は再利用できません。")[lang]
    if ret == "VehicleResult<Unit>":
        return tr(
            "Success(Unit): 차량의 확인 응답, 데이터 없음. 화면에 결과 상태가 필요하면 재조회. Uncertain(error): 실행됐을 수 있으므로 재전송하지 말고 상태 확인. Failure(error): 오류 타입에 따라 조치.",
            "Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.",
            "Success(Unit): 車両の確認応答でデータなし。画面に状態が必要なら再取得。Uncertain(error): 実行済みの可能性があるため再送せず状態確認。Failure(error): 型に応じて対処。")[lang]
    if ret.startswith("VehicleResult<"):
        inner = ret[len("VehicleResult<"):-1]
        special = {
            "Vehicle": tr("새 연결 객체. connection/sessions/events를 관찰하고 finally에서 disconnect().", "A new connection object. Observe connection/sessions/events and disconnect in finally.", "新しい接続オブジェクト。connection/sessions/events を監視し、finally で disconnect()。"),
            "VehicleStatus": tr("lockState, closures, sleepStatus, userPresence, tonneauPercentOpen, gear. 예: LOCKED/ASLEEP/PARK는 설명용 값이며 실제 응답은 차량마다 다름.", "lockState, closures, sleepStatus, userPresence, tonneauPercentOpen, gear. LOCKED/ASLEEP/PARK are illustrative, not a promised response.", "lockState、closures、sleepStatus、userPresence、tonneauPercentOpen、gear。LOCKED/ASLEEP/PARK は説明用で、応答を保証しません。"),
            "VehicleData": tr("category에 해당하는 nullable 상태 메시지. 예: charge_state?.battery_level=80(설명용). 누락=null이며 0과 다름. 빈 VehicleData도 성공.", "Nullable state messages for category; e.g. charge_state?.battery_level=80 (illustrative). Missing=null, not zero. Empty VehicleData can also succeed.", "category に対応する nullable な状態。例: charge_state?.battery_level=80(説明用)。欠損は null で0とは異なり、空の VehicleData でも成功し得ます。"),
            "WhitelistSummary": tr("numberOfEntries, slotMask, occupiedSlots. 예: slotMask=5u이면 occupiedSlots=[0,2].", "numberOfEntries, slotMask, occupiedSlots. Example: slotMask=5u gives occupiedSlots=[0,2].", "numberOfEntries、slotMask、occupiedSlots。例: slotMask=5u は occupiedSlots=[0,2]。"),
            "List<WhitelistEntry>": tr("키별 slot, publicKey?, keyIdSha1Hex?, role, formFactor 목록. 전체 시간 제한 적용.", "List of slot, publicKey?, keyIdSha1Hex?, role and formFactor per key; timeout covers the whole list.", "キーごとの slot、publicKey?、keyIdSha1Hex?、role、formFactor の一覧。制限時間は一覧全体。"),
            "SessionInfoSummary": tr("counter(UInt), clockTime(UInt, epoch 이후 초), vehiclePublicKey?. INFOTAINMENT 성공으로 등록 확인.", "counter(UInt), clockTime(UInt, seconds since epoch start), vehiclePublicKey?. INFOTAINMENT success verifies enrollment.", "counter(UInt)、clockTime(UInt、epoch 開始からの秒)、vehiclePublicKey?。INFOTAINMENT の成功で登録確認。"),
            "VehicleKey": tr("alias, protection, accessibility, publicKey를 가진 키 핸들. 개인키 바이트는 반환하지 않음.", "Key handle with alias, protection, accessibility and publicKey. No private-key bytes are returned.", "alias、protection、accessibility、publicKey を持つキーハンドル。秘密鍵のバイト列は返しません。"),
            "VehicleKey?": tr("저장된 키 핸들. 없으면 Success(null). 오류와 구분.", "Stored key handle; absent means Success(null), distinct from an error.", "保存済みのキーハンドル。存在しない場合は Success(null) で、エラーとは異なります。"),
        }.get(inner)
        return "Success(value: " + inner + "). " + (special[lang] if special else "") + " Uncertain(error) / Failure(error)."
    if ret == "Flow<PairingEvent>":
        return tr("cold Flow. Sent/WaitingForTap/Unknown 후 Accepted/Rejected/Failed/TimedOut 중 하나로 종료. 수집할 때마다 요청하므로 중복 수집 금지. Accepted 뒤 sessionInfo(INFOTAINMENT) 확인.",
                  "Cold Flow: Sent/WaitingForTap/Unknown, then terminal Accepted/Rejected/Failed/TimedOut. Every collection sends a request; avoid duplicate collectors. Verify with sessionInfo(INFOTAINMENT) after Accepted.",
                  "cold Flow。Sent/WaitingForTap/Unknown の後、Accepted/Rejected/Failed/TimedOut で終了。購読ごとに要求を送るため重複購読しないこと。Accepted 後は sessionInfo(INFOTAINMENT) で確認。")[lang]
    if ret == "Flow<ScanEvent>":
        return tr("Found(advertisement) 또는 Failed(error). 수집 취소로 스캔 중단. 스캔 대기 시간은 호출자가 제한.",
                  "Found(advertisement) or Failed(error). Cancel collection to stop scanning; the caller bounds scan time.",
                  "Found(advertisement) または Failed(error)。購読のキャンセルで停止。待機時間は呼び出し側で制限。")[lang]
    if name == "millisecondsOf":
        return tr("밀리초 Long. null 입력이면 -1. 예: 10초 → 10000.", "Long milliseconds; null input gives -1. Example: 10 seconds → 10000.", "ミリ秒の Long。null 入力は -1。例: 10秒 → 10000。")[lang]
    if name == "seconds":
        return tr("Swift timeout 인자에 넘길 박싱된 Duration?. 예: seconds(10.0) → 10초.", "Boxed Duration? for a Swift timeout argument; seconds(10.0) represents 10 seconds.", "Swift の timeout に渡せるボックス化された Duration?。seconds(10.0) は10秒。")[lang]
    if name == "parseOrNull":
        return tr("정상 입력은 대문자 Vin; 형식 오류는 null. 체크섬·실차 존재를 검증하지 않음.", "Valid input gives uppercase Vin; malformed input gives null. Does not verify checksum or vehicle existence.", "正常な入力は大文字の Vin、不正な形式は null。チェックサムや実車の存在は検証しません。")[lang]
    if name == "sharedX":
        return tr("ECDH 공유 X 좌표 바이트. 내부 핸드셰이크용이며 저장·로그 금지. 잠긴 키는 KeyLockedException.", "ECDH shared X bytes for the handshake. Never store or log them. A locked key throws KeyLockedException.", "ハンドシェイク用の ECDH 共有 X 座標。保存・ログ出力は禁止。ロック中のキーは KeyLockedException。")[lang]
    return ret + ". " + tr("상세 의미와 예외는 연결된 사용 절 및 모델 선언 참고.", "See the linked instructions and model declarations for semantics and exceptions.", "意味と例外はリンク先の使用手順とモデル宣言を参照。")[lang]


def api_context(api, lang):
    name = api["name"]
    if api["owner"] != "Vehicle":
        return ""
    if name in ("vehicleStatus", "keySummary", "listKeys", "sessionInfo", "addKeyRequest"):
        return tr("인증 세션 없이 사용. 먼저 BLE 연결이 필요합니다.",
                  "No authenticated session required. Connect over BLE first.",
                  "認証セッション不要。先に BLE 接続が必要です。")[lang]
    if name in ("startSession", "disconnect"):
        return ""
    vcsec = {"addKey", "removeKey", "lock", "unlock", "wakeUp", "remoteDrive", "autoSecureVehicle",
             "openTrunk", "actuateTrunk", "closeTrunk", "openFrunk", "openTonneau", "closeTonneau", "stopTonneau"}
    if name in vcsec:
        return tr(
            "VCSEC 인증 세션 필요: startSession(setOf(VehicleDomain.VCSEC)) 성공 후 호출. 불확실하면 키 변경은 listKeys(), 다른 제어는 vehicleStatus()로 확인하세요.",
            "Requires a VCSEC session: call after startSession(setOf(VehicleDomain.VCSEC)) succeeds. After an uncertain key change use listKeys(); for other controls use vehicleStatus().",
            "VCSEC 認証セッションが必要: startSession(setOf(VehicleDomain.VCSEC)) の成功後に呼び出します。不確実なキー変更は listKeys()、他の操作は vehicleStatus() で確認。")[lang]
    return tr(
        "INFOTAINMENT 인증 세션과 깨어 있는 차량이 필요합니다. startSession(setOf(VehicleDomain.INFOTAINMENT)) 성공 후 호출하세요. 자동으로 세션을 시작하거나 차를 깨우지 않습니다.",
        "Requires an INFOTAINMENT session and an awake vehicle. Call after startSession(setOf(VehicleDomain.INFOTAINMENT)) succeeds. Does not start a session or wake the vehicle automatically.",
        "INFOTAINMENT 認証セッションと起きている車両が必要です。startSession(setOf(VehicleDomain.INFOTAINMENT)) の成功後に呼び出します。自動のセッション確立や起動は行いません。")[lang]


def api_html(lang):
    out = ['<section class="chapter" id="api"><h2>' + t("api", lang) + '</h2><p>' + t("scope", lang) + '</p>',
           '<p>' + t("fragment", lang) + '</p>',
           '<div class="api-tools"><label for="api-search">' + t("find", lang) + '</label><input id="api-search" type="search" autocomplete="off">',
           '<button id="clear-search" type="button">' + t("clear", lang) + '</button><output id="api-count" aria-live="polite"></output></div>']
    counts = Counter()
    for api in APIS:
        owner, name = api["owner"], api["name"]
        counts[(owner, name)] += 1
        suffix = "" if counts[(owner, name)] == 1 else "-" + str(counts[(owner, name)])
        ident = "api-" + owner + "-" + name.replace(".", "-") + suffix
        label = name if "." in name or owner == name else owner + "." + name
        out += ['<article class="api-entry" id="' + ident + '"><h3><code>' + e(label) + '()</code></h3>',
                code(api["signature"])]
        if api_context(api, lang):
            out += ['<p class="api-context">' + e(api_context(api, lang)) + '</p>']
        if api["params"]:
            out += ['<div class="table-wrap"><table><thead><tr><th>' + t("parameter", lang) + '</th><th>' + t("type", lang) +
                    '</th><th>' + t("meaning", lang) + '</th></tr></thead><tbody>']
            for param in api["params"]:
                n, typ, default = param
                sample, desc = parameter(param, api)
                out += ['<tr><td><code>' + e(n) + '</code></td><td><code>' + e(typ) + '</code><br>' +
                        (e(t("required", lang)) if default is None else '<code>' + e(default) + '</code>') +
                        '</td><td>' + e(desc[lang]) + '</td></tr>']
            out += ['</tbody></table></div>']
        else:
            out += ['<p>' + t("none", lang) + '</p>']
        out += ['<h4>' + t("example", lang) + '</h4>', code(call_example(api)),
                '<h4>' + t("returns", lang) + '</h4><p><code>' + e(api["returns"]) + '</code></p><p>' + e(result_text(api, lang)) + '</p>',
                '<p><a href="#' + related(api) + '">' + t("details", lang) + '</a> · <a href="#models">' + t("results", lang) + '</a></p></article>']
    out += ['</section>']
    return "\n".join(out)


def chapter_html(name, lang):
    source = (HTML / lang / (name + ".html")).read_text()
    body = re.search(r'<main class="content">(.*?)</main>', source, re.S)[1]
    body = re.sub(r'<footer class="page-nav">.*?</footer>', "", body, flags=re.S)
    body = re.sub(r'<p class="guide-entry">.*?</p>', "", body, flags=re.S)
    num = name.split("-")[0]
    body = re.sub(r'\bid="([^"]+)"', lambda m: 'id="ch' + num + '-' + m[1] + '"', body)

    def link(m):
        href = m[1]
        if href.startswith("#"):
            href = "#ch" + num + "-" + href[1:]
        else:
            target, sep, fragment = href.partition("#")
            if target.removesuffix(".html") in CHAPTERS:
                href = "#ch" + target[:2] + ("-" + fragment if sep else "")
        return 'href="' + href + '"'

    body = re.sub(r'href="([^"]+)"', link, body)
    body = re.sub(r'url\(#([^)]+)\)', lambda m: 'url(#ch' + num + '-' + m[1] + ')', body)
    body = re.sub(r'aria-labelledby="([^"]+)"',
                  lambda m: 'aria-labelledby="' + ' '.join('ch' + num + '-' + ident for ident in m[1].split()) + '"', body)
    body = re.sub(r'<(/?)h([1-5])\b', lambda m: '<' + m[1] + 'h' + str(int(m[2]) + 1), body)
    return '<section class="chapter" id="ch' + num + '">' + body + '</section>'


def outline(file):
    """Readable signature inventory. Function bodies and private state are deliberately omitted."""
    source = strip_comments(file.read_text())
    lines = []
    for match in re.finditer(r"(?:public\s+)?(?:(?:data|sealed|enum|fun|expect)\s+)*(?:class|interface|object)\s+\w+(?:<[^>\n]+>)?", source):
        if "companion object" in source[max(0, match.start() - 10):match.end()]:
            continue
        end = match.end()
        while end < len(source) and source[end].isspace():
            end += 1
        if end < len(source) and source[end] == "(":
            end = close_paren(source, end) + 1
        tail_end = source.find("\n", end)
        tail = source[end:tail_end if tail_end != -1 else len(source)].split("{")[0].strip()
        declaration = source[match.start():end].strip()
        declaration = re.sub(r"\n\s*\n", "\n", declaration)
        declaration = "\n".join(line.strip() if i == 0 else "    " + line.strip() for i, line in enumerate(declaration.splitlines()))
        if tail.startswith(":"):
            declaration += " " + tail
        lines.append(declaration)
        if "enum" in match[0]:
            opening = source.find("{", end)
            closing = source.find("}", opening)
            entries = re.findall(r"^\s*([A-Z][A-Z_0-9]*)(?:\([^\n]*?\))?[,;]?\s*$", source[opening + 1:closing], re.M)
            lines.append("    " + ", ".join(entries))
    properties = []
    for m in re.finditer(r"(?:public|override)\s+(?:const\s+)?val\s+([\w.]+)\s*:\s*([^\n=]+)", source):
        declaration = m[1] + ": " + m[2].strip().removesuffix(",").split(" get()")[0]
        if not declaration.startswith(("message:", "mayHaveSucceeded:", "temporary:")) or file.name == "VehicleError.kt":
            properties.append(declaration)
    if properties:
        lines += ["", "// Properties (including derived properties)", *dict.fromkeys(properties)]
    return "\n".join(lines)


MODEL_FILES = sorted((DOMAIN / "model").glob("*.kt")) + [
    SDK / "SessionStatus.kt", SDK / "TeslaBleConfig.kt", SDK / "Vehicle.kt",
    DOMAIN / "port/VehicleKeyStore.kt", DOMAIN / "port/TransportFactory.kt",
    DOMAIN / "port/TeslaLogger.kt", DOMAIN / "cache/CachedSession.kt",
    DOMAIN / "port/EcdhPrivateKey.kt",
]
# Transport is an internal assembly boundary; only its returned TransportState is app-facing.
TRANSPORT_STATE = """sealed interface TransportState
data object Connected : TransportState
data class Disconnected(val reason: VehicleError.TransportError?) : TransportState"""


def models_html(lang):
    out = ['<section class="chapter" id="models"><h2>' + t("models", lang) + '</h2><p>' + tr(
        "생성자 필드의 타입, nullable 여부와 enum 값을 소스 선언에서 추출했습니다. 함수 본문은 생략한 참조용 선언이며 그대로 컴파일할 코드가 아닙니다. 상속한 필드도 포함해 실제 의미·기본값·오류별 조치는 상태 조회/키/오류 처리 절을 함께 보세요. Wire 생성 VehicleData의 반환 필드 표는 상태 조회 절에 있습니다.",
        "Constructor field types, nullability and enum values are extracted from source. Function bodies are omitted: these are reference declarations, not compilable implementations. For inherited fields, semantics, defaults and error handling, also read Reading state, Keys and Errors. VehicleData fields are tabulated in Reading state.",
        "コンストラクターフィールドの型、nullable の有無と enum 値をソース宣言から抽出しています。本体を省略した参照用の宣言で、そのままコンパイルするコードではありません。継承フィールドや意味、初期値、エラー対処は状態取得・キー・エラーの章も参照してください。VehicleData のフィールド表は状態取得の章にあります。")[lang] + '</p>',
        code(TRANSPORT_STATE)]
    for file in MODEL_FILES:
        title = file.stem
        if title == "Vehicle":
            # Method bodies are already documented, this block indexes the facade properties.
            value = "\n".join(re.findall(r"public val \w+: [^\n=]+", strip_comments(file.read_text())))
        else:
            value = outline(file)
        out += ['<h3 id="model-' + title + '">' + e(title) + '</h3>', code(value)]
    # These generated constructor fields are inputs and outputs and are not hand-written models.
    for name in ["ChargeSchedule", "PreconditionSchedule"]:
        source = (ROOT / "domain/src/commonMain/proto/common.proto").read_text()
        match = re.search(r"message " + name + r"\s*\{(.*?)\n\}", source, re.S)
        if not match:
            raise ValueError("Schedule schema not found: " + name)
        out += ['<h3 id="model-' + name + '">' + name + '</h3>',
                code("message " + name + " {\n" + match[1].strip() + "\n}", "protobuf")]
    out += ['</section>']
    return "\n".join(out)


def schemas_html(lang):
    source = (ROOT / "domain/src/commonMain/proto/vehicle.proto").read_text()
    # Keep offsets while ignoring comments for balanced-brace matching.
    masked = re.sub(r"/\*.*?\*/|//[^\n]*", lambda m: " " * len(m[0]), source, flags=re.S)
    names = [
        "VehicleData", "ChargeState", "ClimateState", "DriveState", "LocationState", "ClosuresState",
        "ChargeScheduleState", "PreconditioningScheduleState", "TirePressureState", "MediaState",
        "MediaDetailState", "SoftwareUpdateState", "ParentalControlsState",
    ]
    out = ['<section class="chapter" id="schemas"><h2>' + t("schemas", lang) + '</h2><p>' + tr(
        "12개 조회 범주의 반환 메시지와 VehicleData의 전체 필드 선언입니다. 필드명·wire 타입·번호·oneof의 가능한 멤버를 확인할 수 있습니다. 이는 스키마 구조이며 모든 필드가 차량에서 반환된다는 뜻이 아닙니다. TESLEMETRY-EXT 주석이 붙은 필드는 지원 대상이 아니므로 의존하지 마세요. 단위·null 처리·Kotlin/Swift 접근 예제는 상태 조회 절을 참고하세요.",
        "Complete field declarations for VehicleData and the 12 queried state messages: field names, wire types, numbers and oneof alternatives. Schema presence does not promise a vehicle will send a field. Fields marked TESLEMETRY-EXT are unsupported; do not depend on them. Units, null handling and Kotlin/Swift access examples are in Reading state.",
        "VehicleData と12種類の取得対象メッセージの全フィールド宣言です。フィールド名、wire 型、番号、oneof の候補を確認できます。スキーマに存在しても車両から返る保証はありません。TESLEMETRY-EXT のフィールドはサポート対象外です。単位、null 処理、Kotlin/Swift の参照例は状態取得の章を参照。")[lang] + '</p>']
    for name in names:
        match = re.search(r"^message " + name + r"\s*\{", masked, re.M)
        if not match:
            raise ValueError("Missing state schema: " + name)
        depth, end = 1, match.end()
        while depth:
            if masked[end] == "{":
                depth += 1
            elif masked[end] == "}":
                depth -= 1
            end += 1
        out += ['<h3 id="schema-' + name + '">' + name + '</h3>', code(source[match.start():end], "protobuf")]
    common = (ROOT / "domain/src/commonMain/proto/common.proto").read_text().split("// ===== TESLEMETRY-EXT BEGIN")[0]
    out += ['<h3 id="schema-common">CarServer common</h3>', code(common, "protobuf"), '</section>']
    return "\n".join(out)


def setup_html(lang):
    text = tr(
        """<p>현재 Maven 아티팩트와 SPM 패키지는 배포 전(M6)입니다. 임의 버전의 implementation 좌표나 SPM URL을 사용하지 말고, 소스 체크아웃에서 빌드하세요. 빌드 시 의존성 다운로드에는 인터넷이 필요하지만 차량 통신은 BLE만 사용합니다.</p>
<h3>준비물과 소스 빌드</h3><p>Git, JDK 17 툴체인(Gradle이 자동 확보), Android SDK 37 및 Android API 31 이상 기기가 필요합니다. iOS는 macOS, Xcode, 명령줄 도구와 실제 iPhone(iOS 16+)을 준비하세요. 시뮬레이터는 컴파일·테스트용이며 BLE가 없습니다. 저장소의 Gradle Wrapper와 고정 의존성 버전을 사용하세요.</p>
<h3>Android 앱에 연결</h3><p>현재 검증된 방식은 전체 소스의 Gradle 빌드 안에서 앱 모듈이 :sdk를 의존하는 것입니다. 기존 Android 샘플로 먼저 빌드한 후, 같은 빌드에 앱 모듈을 추가하고 아래 의존성을 넣으세요. :sdk 폴더만 복사하면 내부 모듈과 빌드 플러그인이 없어 빌드할 수 없습니다.</p>""",
        """<p>Maven artifacts and the SPM package are not published yet (M6). Build from a source checkout rather than inventing version coordinates or an SPM URL. Dependency downloads need Internet access during a build; vehicle communication uses BLE only.</p>
<h3>Prerequisites and source build</h3><p>Use Git, a JDK 17 toolchain (provisioned by Gradle), Android SDK 37 and an Android API 31+ device. For iOS, use macOS, Xcode with command-line tools and a real iPhone on iOS 16+. The simulator supports compilation/tests but has no Bluetooth. Use the repository Gradle Wrapper and pinned dependency versions.</p>
<h3>Connect an Android app</h3><p>The verified path is an app module depending on :sdk within the complete source Gradle build. Build the Android sample first, then add your app module to that same build and add this dependency. Copying only :sdk omits required internal modules and build plugins.</p>""",
        """<p>Maven アーティファクトと SPM パッケージは未公開(M6)です。仮のバージョン座標や SPM URL を使わず、ソースからビルドしてください。ビルド時の依存関係の取得にはインターネットが必要ですが、車両との通信は BLE のみです。</p>
<h3>必要環境とソースビルド</h3><p>Git、JDK 17 ツールチェーン(Gradle が取得)、Android SDK 37 と API 31 以上の Android 端末が必要です。iOS は macOS、Xcode とコマンドラインツール、iOS 16 以上の実機 iPhone を用意します。シミュレーターはコンパイル・テスト用で Bluetooth はありません。リポジトリの Gradle Wrapper と固定した依存バージョンを使います。</p>
<h3>Android アプリへの接続</h3><p>検証済みの方法は、完全なソースの Gradle ビルド内でアプリモジュールから :sdk に依存する構成です。まず Android サンプルをビルドし、同じビルドにアプリモジュールを追加して以下を設定します。:sdk だけをコピーすると内部モジュールやビルドプラグインが不足します。</p>""")[lang]
    commands = "\n".join([
        "git clone " + PATHS["REPO_URL"],
        "cd " + PATHS["LIB_NAME"],
        "./gradlew :sdk:assemble",
        "./gradlew :samples:android:assembleDebug",
        "./gradlew :sdk:linkDebugFrameworkIosSimulatorArm64",
        "./gradlew :sdk:linkDebugFrameworkIosArm64",
    ])
    text += code(commands, "bash")
    text += code('dependencies {\n    implementation(project(":sdk"))\n}', "kotlin")
    text += tr(
        '<p>앱의 minSdk는 31 이상입니다. 매니페스트와 런타임에서 BLUETOOTH_SCAN 및 BLUETOOTH_CONNECT를 허용한 뒤 스캔하세요. 키 파일의 백업 제외도 필요합니다. 정확한 XML과 권한·키 저장 설정은 <a href="#ch17">플랫폼 설정</a>에 있습니다. 아래 기본 설정 예제는 로그를 남기지 않습니다.</p>',
        '<p>Set app minSdk to at least 31. Declare and request BLUETOOTH_SCAN and BLUETOOTH_CONNECT before scanning. Exclude key files from backup. Exact XML and storage settings are in <a href="#ch17">Platform notes</a>. The default configuration below does not log.</p>',
        '<p>アプリの minSdk は31以上です。スキャン前に BLUETOOTH_SCAN と BLUETOOTH_CONNECT を宣言し実行時に許可を取得します。キーファイルはバックアップから除外します。XML と保存設定は<a href="#ch17">プラットフォーム設定</a>を参照。以下の既定設定はログを出力しません。</p>')[lang]
    text += code("val ble = TeslaBle.create(PlatformContext(context), TeslaBleConfig())")
    text += '<h3>iOS / Swift</h3>' + tr(
        '<p>실기기에는 iosArm64, Apple Silicon 시뮬레이터에는 iosSimulatorArm64 정적 프레임워크를 링크합니다. 출력 폴더를 Framework Search Paths에 추가하고 Teslable.framework를 Do Not Embed(정적)로 링크하세요. Kotlin 코드가 바뀌면 다시 빌드합니다. 기존 XcodeGen 샘플에 두 구성이 있습니다.</p>',
        '<p>Link the iosArm64 static framework for a device, and the iosSimulatorArm64 framework for an Apple Silicon simulator. Add the matching output directory to Framework Search Paths and link Teslable.framework with Do Not Embed (it is static). Rebuild the framework when Kotlin code changes. The existing XcodeGen sample shows both configurations.</p>',
        '<p>実機は iosArm64、Apple Silicon シミュレーターは iosSimulatorArm64 の静的フレームワークをリンクします。出力先を Framework Search Paths に追加し、Teslable.framework を Do Not Embed(静的)でリンクします。Kotlin コードの変更時は再ビルドします。XcodeGen サンプルに両方の構成があります。</p>')[lang]
    sdk_rel = "sdk"
    text += code("\n".join([
        sdk_rel + "/build/bin/iosArm64/debugFramework/Teslable.framework",
        sdk_rel + "/build/bin/iosSimulatorArm64/debugFramework/Teslable.framework",
    ]), "text")
    text += tr(
        '<p>Info.plist에 NSBluetoothAlwaysUsageDescription을 추가하고 import Teslable을 사용하세요. Swift는 Kotlin 기본 인자의 오버로드를 만들지 않으므로 timeout: nil을 포함한 모든 인자를 넘깁니다. withSeconds와 Timeouts로 시간 제한을 설정합니다. 전체 Swift 예제는 <a href="#ch12">시작하기</a> 및 <a href="#ch17">플랫폼 설정</a>에 있습니다.</p>',
        '<p>Add NSBluetoothAlwaysUsageDescription to Info.plist and import Teslable. Swift gets no Kotlin default-argument overloads, so pass every argument, including timeout: nil. Use withSeconds and Timeouts for time limits. Full Swift examples are in <a href="#ch12">Getting started</a> and <a href="#ch17">Platform notes</a>.</p>',
        '<p>Info.plist に NSBluetoothAlwaysUsageDescription を追加し、import Teslable を使います。Swift には Kotlin のデフォルト引数のオーバーロードがないため、timeout: nil を含む全引数を指定します。時間制限は withSeconds と Timeouts を使います。Swift の全例は<a href="#ch12">開始手順</a>と<a href="#ch17">プラットフォーム設定</a>を参照。</p>')[lang]
    text += code("""import Teslable
let ble = TeslaBle.companion.create(
    platform: PlatformContext(),
    config: TeslaBleConfig.companion.default(logger: TeslaLoggerCompanion.shared.NoOp)
)""", "swift")
    return '<section class="chapter" id="setup"><h2>' + t("setup", lang) + '</h2>' + text + '</section>'


class MarkdownText(HTMLParser):
    """Preserve code and headings when writing the English companion."""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.parts = []
        self.in_pre = False

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "pre":
            self.in_pre = True
        elif tag == "code" and self.in_pre:
            language = attrs.get("class", "").removeprefix("language-")
            self.parts.append("\n\n" + BT * 3 + language + "\n")
        elif tag in ("h2", "h3", "h4"):
            self.parts.append("\n\n" + "#" * int(tag[1]) + " ")
        elif tag == "p":
            self.parts.append("\n\n")
        elif tag == "br":
            self.parts.append("\n")

    def handle_endtag(self, tag):
        if tag == "pre":
            self.parts.append("\n" + BT * 3 + "\n")
            self.in_pre = False
        elif tag in ("p", "h2", "h3", "h4"):
            self.parts.append("\n")

    def handle_data(self, data):
        self.parts.append(data)


def markdown_text(source):
    parser = MarkdownText()
    parser.feed(source)
    return "".join(parser.parts)


CSS = """
:root { color-scheme: light; --guide-ink:#152d45; --guide-blue:#1c527e; --guide-paper:#f3f7fb; }
html { scroll-behavior:auto; scroll-padding-top:90px; }
body { margin:0; background:var(--guide-paper); color:var(--guide-ink); font-family:"Avenir Next","Apple SD Gothic Neo","Yu Gothic",sans-serif; }
.guide-header { position:sticky; top:0; z-index:20; display:flex; flex-wrap:wrap; align-items:center; gap:16px; padding:13px 28px; border-bottom:1px solid #d4e0ec; background:#fff; }
.guide-header strong { color:var(--guide-blue); font-size:18px; }
.guide-header .languages { margin-left:auto; display:flex; gap:14px; }
.guide-header a { color:var(--guide-blue); }
.guide-header [aria-current] { font-weight:800; text-decoration:underline; text-underline-offset:5px; }
button { font:inherit; padding:7px 12px; border:1px solid #b8cada; background:white; color:#153e60; border-radius:5px; cursor:pointer; }
.guide-layout { display:grid; grid-template-columns:240px minmax(0,1fr); max-width:1500px; margin:0 auto; }
.guide-nav { position:sticky; top:76px; align-self:start; max-height:calc(100vh - 100px); overflow:auto; padding:30px 18px; font-size:14px; }
.guide-nav strong { display:block; margin-bottom:16px; }
.guide-nav a { display:block; padding:8px 10px; color:#38556f; text-decoration:none; border-left:2px solid transparent; line-height:1.5; }
.guide-nav a:hover { border-color:var(--guide-blue); background:#e4edf6; }
main.content { min-width:0; width:100%; max-width:none; margin:0; padding:36px 44px 100px; background:#fff; border-left:1px solid #e0e8ef; }
.guide-intro { border-top:5px solid #c92435; padding:25px 0 32px; }
.guide-intro h1 { margin:0 0 20px; font-size:clamp(30px,4vw,48px); letter-spacing:-1.6px; color:#153a5c; line-height:1.2; }
.guide-intro .lead { max-width:68ch; font-size:19px; line-height:1.8; color:#3e5a72; }
.guide-meta { font-size:13px; color:#526b81; }
.chapter { margin-top:54px; padding-top:24px; border-top:1px solid #cfdde9; }
.chapter h2 { font-size:30px; color:#163f60; margin-top:8px; }
.chapter h3 { color:#214b6e; margin-top:32px; }
.chapter p,.chapter li { line-height:1.85; overflow-wrap:anywhere; }
.chapter p { max-width:82ch; }
.content a { color:#175f96; text-underline-offset:3px; }
.content pre { max-width:100%; overflow:auto; background:#f2f6fb; color:#17344c; border:1px solid #d9e5ef; border-radius:6px; box-shadow:none; }
.content code { color:#25527b; }
.content pre code { color:inherit; font-size:12.5px; }
.content pre .c { color:#596d7f; }.content pre .k { color:#713e88; }.content pre .s { color:#256443; }
.content pre .f { color:#185c95; }.content pre .n { color:#a43e2c; }.content pre .t { color:#6b468c; }
.content table { display:block; width:100%; max-width:100%; overflow:auto; font-size:13px; border-collapse:collapse; }
.content th { background:#eaf1f7; color:#244662; white-space:nowrap; }
.content td,.content th { padding:11px 12px; border-color:#dae4ed; vertical-align:top; }
.content .callout,.content .toc { background:#f2f6fa; color:#24435c; border-color:#b6cddd; }
.content .callout.warn { background:#fff7e7; border-color:#d5ad5a; color:#684b16; }
.api-tools { position:relative; padding:18px; background:#eaf1f7; border-radius:6px; margin:24px 0; }
.api-tools label { display:block; font-weight:700; margin-bottom:10px; }
.api-tools input { box-sizing:border-box; width:min(100%,480px); border:1px solid #9db5c9; padding:10px 12px; font:inherit; background:#fff; color:#152d45; border-radius:4px; }
.api-tools output { display:inline-block; padding:8px; font-variant-numeric:tabular-nums; }
.api-entry { padding:10px 0 25px; margin-bottom:24px; border-bottom:1px solid #dce5ee; scroll-margin-top:90px; }
.api-entry h3 { font-size:21px; }.api-entry h4 { font-size:14px; margin:22px 0 8px; }
[hidden] { display:none!important; }
:focus-visible { outline:3px solid #2978b5; outline-offset:4px; }
.skip-link { position:fixed; top:-90px; left:12px; z-index:50; background:white; padding:10px; }
.skip-link:focus { top:8px; }
@media(max-width:900px) { .guide-layout { display:block; }.guide-nav { position:static; max-height:none; display:flex; flex-wrap:wrap; gap:2px; padding:15px; }.guide-nav strong { width:100%; margin:0 0 5px; }.guide-nav a { padding:5px 8px; }.content { border-left:0; }main.content { padding:25px 20px 70px; }.guide-header { padding:12px 16px; gap:10px; }.guide-header .languages { margin-left:0; }.guide-header strong { width:100%; }.chapter h2 { font-size:25px; } }
@media print { .guide-header,.guide-nav,.api-tools,.skip-link { display:none!important; }.guide-layout { display:block; }main.content { border:0; padding:0; }.chapter { break-before:page; }.api-entry { break-inside:avoid; }.content pre { white-space:pre-wrap; overflow-wrap:anywhere; }.content table { display:table; font-size:10px; }.content td { overflow-wrap:anywhere; }.content a { color:inherit; }body { background:white; } }
"""

JS = """
(() => {
  const search = document.getElementById('api-search');
  const entries = [...document.querySelectorAll('.api-entry')];
  const index = entries.map(entry => entry.textContent.toLocaleLowerCase());
  const count = document.getElementById('api-count');
  function filter() {
    const terms = search.value.toLocaleLowerCase().trim().split(/\s+/).filter(Boolean);
    entries.forEach((entry, i) => { entry.hidden = !terms.every(term => index[i].includes(term)); });
    count.textContent = entries.filter(entry => !entry.hidden).length + ' / ' + entries.length;
  }
  search.addEventListener('input', filter);
  document.getElementById('clear-search').addEventListener('click', () => { search.value = ''; filter(); search.focus(); });
  document.getElementById('print-guide').addEventListener('click', () => window.print());
  window.addEventListener('beforeprint', () => entries.forEach(entry => { entry.hidden = false; }));
  window.addEventListener('afterprint', filter);
  filter();
})();
"""


def build():
    for lang in ("ko", "en", "ja"):
        chapters = [chapter_html(name, lang) for name in CHAPTERS]
        labels = [html.unescape(re.sub("<[^>]+>", "", re.search(r"<h2[^>]*>(.*?)</h2>", c, re.S)[1])) for c in chapters]
        nav = [('setup', t("setup", lang)), ('api', t("api", lang)), ('models', t("models", lang))]
        nav += [('schemas', t("schemas", lang))]
        nav += [("ch" + name[:2], label) for name, label in zip(CHAPTERS, labels)]
        language_links = "".join('<a href="../' + loc + '/' + NAME + '.html" lang="' + loc + '"' +
                                 (' aria-current="page"' if loc == lang else '') + '>' + label + '</a>'
                                 for loc, label in [("ko", "한국어"), ("en", "English"), ("ja", "日本語")])
        # Reuse the manual palette/syntax foundations, then apply a light reading layout locally.
        css = (HTML / "assets/style.css").read_text() + "\n" + CSS
        body = '\n'.join([
            '<!DOCTYPE html><html lang="' + lang + '"><head><meta charset="utf-8">',
            '<script>document.documentElement.dataset.theme="light";</script>',
            '<meta name="viewport" content="width=device-width, initial-scale=1">',
            '<meta name="description" content="' + e(t("intro", lang)) + '">',
            '<title>' + t("title", lang) + '</title><style>' + css + '</style></head><body>',
            '<a class="skip-link" href="#guide-top">' + t("title", lang) + '</a>',
            '<header class="guide-header"><strong>Teslable / BLE SDK</strong><div class="languages">' + language_links +
            '</div><button id="print-guide" type="button">' + t("print", lang) + '</button></header>',
            '<div class="guide-layout"><nav class="guide-nav" aria-label="' + t("nav", lang) + '"><strong>' + t("nav", lang) + '</strong>' +
            ''.join('<a href="#' + ident + '">' + e(label) + '</a>' for ident, label in nav) + '</nav>',
            '<main class="content" id="guide-top"><div class="guide-intro"><h1>' + t("title", lang) + '</h1><p class="lead">' +
            t("intro", lang) + '</p><p class="guide-meta">' + t("status", lang) + '<br>' + t("snapshot", lang) +
            ': <code>' + e(REV) + '</code><br>' + t("offline", lang) + '</p></div>',
            setup_html(lang), api_html(lang), models_html(lang), schemas_html(lang), *chapters,
            '</main></div><script>' + JS + '</script></body></html>',
        ])
        (HTML / lang / (NAME + ".html")).write_text(body)

    md = [
        "# Tesla BLE KMP User Guide",
        "",
        "An offline, single-page guide for the current app-facing library APIs.",
        "Scope: :sdk facade, key management, configuration, input models and returned values. Internal protocol/adapter APIs are not app integration APIs.",
        "Requirements and implementation semantics are unchanged. Sources: {{LIB_DOCS_INDEX}} and the maintained 12-19 library chapters.",
        "Reference manual: {{MANUAL_DIR}}04-go-api-reference.md, 05-command-catalog.md, 08-errors.md and 09-recipes.md (read-only).",
        "",
        "Human-readable editions: [Korean](../html/ko/" + NAME + ".html), [English](../html/en/" + NAME + ".html), [Japanese](../html/ja/" + NAME + ".html).",
        "",
        "## Installation",
        "",
        markdown_text(setup_html("en")),
        "",
        "## Complete method reference",
        "",
        TEXT["fragment"]["en"],
    ]
    for api in APIS:
        md += ["", "### " + api["owner"] + "." + api["name"], "", BT * 3 + "kotlin", api["signature"], BT * 3, ""]
        if api_context(api, "en"):
            md += [api_context(api, "en"), ""]
        for param in api["params"]:
            n, typ, default = param
            md += ["- " + BT + n + ": " + typ + BT + " (" + (default if default is not None else "required") +
                   "): " + parameter(param, api)[1]["en"]]
        md += ["", BT * 3 + "kotlin", call_example(api), BT * 3, "", result_text(api, "en")]
    md += ["", markdown_text(models_html("en")), "", markdown_text(schemas_html("en"))]
    for name in CHAPTERS:
        source = (MD / (name + ".md")).read_text()
        source = re.sub(r"<!-- user-guide-entry -->.*?<!-- /user-guide-entry -->", "", source, flags=re.S)
        md += ["", "---", "", source]
    (MD / (NAME + ".md")).write_text("\n".join(md) + "\n")
    print("Built " + str(len(APIS)) + " method entries, " + str(len(MODEL_FILES)) +
          " model groups and " + str(len(CHAPTERS)) + " chapters in ko/en/ja + English Markdown.")


if __name__ == "__main__":
    build()
